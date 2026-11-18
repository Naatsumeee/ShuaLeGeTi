package com.examhelper.shua.data.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.examhelper.shua.data.ocr.MlKitOcrEngine
import com.examhelper.shua.data.parse.MarkdownToText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 提取进度回调 */
data class IngestProgress(
    val stage: String,     // "extract" / "ocr"
    val current: Int,
    val total: Int,
    val message: String
)

internal class OrderedPageAccumulator(firstPage: Int, prefix: String) {
    private val pending = HashMap<Int, String>()
    private var nextPage = firstPage
    private val text = StringBuilder(prefix)

    /** 返回本次连续推进到的最后一页；没有填上缺口时返回 null。 */
    fun put(page: Int, pageText: String): Int? {
        pending[page] = pageText
        var flushed: Int? = null
        while (pending.containsKey(nextPage)) {
            val current = pending.remove(nextPage).orEmpty()
            if (text.isNotEmpty() && text.last() != '\n') text.appendLine()
            text.appendLine("[[PG:$nextPage]]")
            text.appendLine(current)
            flushed = nextPage
            nextPage++
        }
        return flushed
    }

    fun snapshot(): String = text.toString()
}

private data class RenderedPdfPage(val page: Int, val bitmap: Bitmap)
private data class RecognizedPdfPage(val page: Int, val text: String)

/**
 * 统一文档入口：根据 Uri 与扩展名分流到 docx / PDF / Markdown / 纯文本 / 图片五条路径，
 * 全部产出纯文本。
 */
class DocumentIngestor(
    private val context: Context,
    private val ocrEngine: com.examhelper.shua.data.ocr.OcrProvider = com.examhelper.shua.data.ocr.MlKitOcrEngine()
) {

    /** Whether this ingest actually invoked OCR (as opposed to a text layer). */
    var usedOcr: Boolean = false
        private set

    /**
     * 每完成一页 OCR 就回调一次（页码从 1 起 + 当前累计文本）。
     *
     * 47 页扫描件在真机上要跑十几分钟，一旦进程被杀，早先的实现**整段作废**：
     * OCR 结果只存在内存里的 StringBuilder，`raw_<jobId>.txt` 要等全部页跑完才写。
     * 用户看到的就是「跑很久突然回到原点」。现在逐页落盘，续跑时跳过已完成的页。
     */
    var onPageDone: ((page: Int, partial: String) -> Unit)? = null

    /** 续跑起点：此页码**之前**的页已经落盘，从 `page` 开始（1 起）。 */
    var resumeFrom: Int = 1

    /** 续跑时已经落盘的那部分文本 */
    var resumePrefix: String = ""

    suspend fun ingest(
        uri: Uri,
        onProgress: suspend (IngestProgress) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: ""
        val mime = context.contentResolver.getType(uri)
        val metadata = IngestFormatResolver.fromMetadata(name, mime)
        when (metadata) {
            IngestFormatResolver.Format.DOCX -> extractDocx(uri)
            IngestFormatResolver.Format.PDF -> ingestPdf(uri, onProgress)
            IngestFormatResolver.Format.LEGACY_DOC ->
                throw IllegalArgumentException("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后导入")
            IngestFormatResolver.Format.MARKDOWN -> extractPlainText(uri, true, onProgress)
            IngestFormatResolver.Format.TEXT -> extractPlainText(uri, false, onProgress)
            IngestFormatResolver.Format.IMAGE -> ingestImage(uri, onProgress)
            else -> {
                // 云盘/无扩展名：读一小段文件头再兜底。文本不能被误送进 OCR。
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("无法读取文件")
                when (val detected = IngestFormatResolver.fromBytes(bytes)) {
                    IngestFormatResolver.Format.DOCX -> DocxTextExtractor.extract(bytes)
                    IngestFormatResolver.Format.PDF -> {
                        // 已读出的字节不能直接交给 PdfRenderer；把 URI 重新走 PDF 路径。
                        ingestPdf(uri, onProgress)
                    }
                    IngestFormatResolver.Format.MARKDOWN ->
                        extractPlainTextBytes(bytes, true, onProgress)
                    IngestFormatResolver.Format.TEXT ->
                        extractPlainTextBytes(bytes, false, onProgress)
                    IngestFormatResolver.Format.IMAGE -> ingestImage(uri, onProgress)
                    else -> throw IllegalArgumentException(IngestFormatResolver.unsupportedMessage(name))
                }
            }
        }
    }

    /**
     * Markdown / 纯文本直接读取：不走 OCR，也不做 PDF 那种逐页标记。
     * Markdown 先做一次结构降级（详见 [MarkdownToText]），把 `## 章节`、`**粗体**`、
     * 表格竖线、围栏代码块交给下游解析器能吃的形态；LaTeX 数学区原样保留。
     */
    private suspend fun extractPlainText(
        uri: Uri,
        asMarkdown: Boolean,
        onProgress: suspend (IngestProgress) -> Unit
    ): String {
        onProgress(IngestProgress("extract", 1, 1, if (asMarkdown) "读取 Markdown 文本" else "读取文本文件"))
        val raw = readTextUtf8(uri)
        if (raw.isBlank()) throw IllegalArgumentException("文件中没有可读文本")
        return if (asMarkdown) MarkdownToText.normalize(raw) else raw
    }

    private suspend fun extractPlainTextBytes(
        bytes: ByteArray,
        asMarkdown: Boolean,
        onProgress: suspend (IngestProgress) -> Unit
    ): String {
        onProgress(IngestProgress("extract", 1, 1, if (asMarkdown) "读取 Markdown 文本" else "读取文本文件"))
        val raw = TextDecoder.decode(bytes)
        if (raw.isBlank()) throw IllegalArgumentException("文件中没有可读文本")
        return if (asMarkdown) MarkdownToText.normalize(raw) else raw
    }

    private fun readTextUtf8(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取文件")
        // 不能直接 String(bytes, UTF_8)：Windows 上的 .txt/.md 多为 GBK，
        // 按 UTF-8 硬解会得到成片 U+FFFD，表现为「文字完全错乱」的乱码。
        // TextDecoder 先看 BOM，再严格试 UTF-8，最后回退 GB18030，并顺带剥掉 BOM。
        return TextDecoder.decode(bytes)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---------- docx：zip + XML 文本抽取 ----------

    private fun extractDocx(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取文件")
        return try {
            input.use { stream -> extractDocxBytes(stream.readBytes()) }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("docx 解析失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * 纯函数入口：从 docx 的 zip 字节提取正文。
     *
     * 这样做有两个目的：
     * 1. 不依赖 Android `ContentResolver`，可以在纯 JVM 单测里把真实 docx 跑一遍；
     * 2. 出错时把「文件损坏 / 不是 zip / 缺少 document.xml」区分开，界面上能看到
     *    可行动的原因，而不是统一一句「没有找到文本内容」。
     */
    internal fun extractDocxBytes(bytes: ByteArray): String = DocxTextExtractor.extract(bytes)

    /** 纯字节入口：只返回 `word/document.xml`，供 JVM 单测与本体复用。 */
    internal fun extractDocxDocumentXml(bytes: ByteArray): ByteArray? = DocxTextExtractor.readDocumentXml(bytes)

    // ---------- PDF：文字页直接抽文本，扫描页 OCR ----------

    private suspend fun ingestPdf(uri: Uri, onProgress: suspend (IngestProgress) -> Unit): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val file = cachePdf(uri)

        // 临时 PDF 必须保证删除：中途 OCR 抛异常时也要落到 finally，否则缓存会一直涨
        try {
            // 第一遍：PDFBox 只用来抽「有文字层」的页。
            //
            // 扫描件整本都是图片，PDFBox 必然每页都抽不出东西；此时再对每页分别
            // 做一次 OCR 判断，等于把整本 PDF 解析两遍。先一次性统计「有效文字页」
            // 的占比，扫描件直接跳过 PDFBox 的逐页 getText（那是纯开销）。
            val textByPage: Map<Int, String> = runCatching { extractPdfTextLayer(file) }
                .getOrDefault(emptyMap())
            val total = runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { it.numberOfPages } }
                .getOrElse {
                    file.delete()
                    throw IllegalArgumentException("无法打开 PDF：${it.message}")
                }
            val startPage = resumeFrom.coerceIn(1, total + 1)
            val accumulator = OrderedPageAccumulator(startPage, resumePrefix)
            val pagesToOcr = mutableListOf<Int>()
            var lastFlushed: Int? = null

            // 先把能用文字层读出的页放入有序缓冲。它们即使排在后段，也会等前面的
            // OCR 页补齐后才真正落盘，断点文件永远不会出现中间缺页。
            for (i in startPage..total) {
                val pageText = textByPage[i].orEmpty().trim()
                if (pageText.length >= 20 && !TextDecoder.looksGarbled(pageText)) {
                    onProgress(IngestProgress("extract", i, total, "读取第 $i/$total 页"))
                    accumulator.put(i, pageText)?.let { lastFlushed = it }
                } else {
                    pagesToOcr += i
                }
            }
            fun persistContiguous() {
                lastFlushed?.let { page ->
                    onPageDone?.invoke(page, accumulator.snapshot())
                    lastFlushed = null
                }
            }
            persistContiguous()

            if (pagesToOcr.isNotEmpty()) {
                usedOcr = true
                onProgress(
                    IngestProgress(
                        "ocr", 0, total,
                        if (ocrEngine.concurrency > 1) {
                            "RapidOCR 已启动 ${ocrEngine.concurrency} 路并行识别"
                        } else {
                            "准备识别扫描页"
                        }
                    )
                )
                val pfd = android.os.ParcelFileDescriptor.open(
                    file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                ) ?: throw IllegalArgumentException("无法打开 PDF 渲染器")
                try {
                    val renderer = try {
                        PdfRenderer(pfd)
                    } catch (t: Throwable) {
                        pfd.close()
                        throw IllegalArgumentException("无法打开 PDF：${t.message}", t)
                    }
                    try {
                        ocrPdfPages(
                            renderer = renderer,
                            pages = pagesToOcr,
                            startPage = startPage,
                            total = total,
                            accumulator = accumulator,
                            onProgress = onProgress,
                            onFlushed = { page ->
                                onPageDone?.invoke(page, accumulator.snapshot())
                            }
                        )
                    } finally {
                        renderer.close()
                    }
                } finally {
                    pfd.close()
                }
            }
            val result = accumulator.snapshot()
            if (result.isBlank()) throw IllegalArgumentException("PDF 中没有可识别的内容")
            return result
        } finally {
            file.delete()
        }
    }

    /**
     * 用 PDFBox 抽出各页文字层，返回 `页码(1 起) → 文本`。
     *
     * **整本只解析一次**：早先是在 1..total 的循环里对 EACH 页做
     * `stripper.startPage/endPage + getText(doc)`，一次导入相当于把整份 PDF
     * 走 total 遍。47 页扫描件上这就是「导入极慢」的主要来源。
     */
    private fun extractPdfTextLayer(file: java.io.File): Map<Int, String> {
        val out = HashMap<Int, String>()
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val total = doc.numberOfPages
            for (i in 1..total) {
                stripper.startPage = i
                stripper.endPage = i
                out[i] = runCatching { stripper.getText(doc) }.getOrDefault("")
            }
        }
        return out
    }

    private fun cachePdf(uri: Uri): java.io.File {
        val target = com.examhelper.shua.data.storage.AppStorage.newIngestPdf(context)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalArgumentException("无法读取 PDF")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    /**
     * 单 PdfRenderer 负责顺序渲染，固定数量 worker 负责并行 OCR。
     *
     * 页面通道容量为 0：渲染线程只有在有空闲 OCR worker 时才创建下一张位图，
     * 不会为了追求并发先把整本 PDF 渲成位图塞进内存。结果通道由单一 writer
     * 消费，并只在连续页都完成时推进 raw 与断点侧车。
     */
    private suspend fun ocrPdfPages(
        renderer: PdfRenderer,
        pages: List<Int>,
        startPage: Int,
        total: Int,
        accumulator: OrderedPageAccumulator,
        onProgress: suspend (IngestProgress) -> Unit,
        onFlushed: (Int) -> Unit
    ) = coroutineScope {
        val lanes = ocrEngine.concurrency.coerceIn(1, pages.size.coerceAtLeast(1))
        val rendered = Channel<RenderedPdfPage>(Channel.RENDEZVOUS)
        val recognized = Channel<RecognizedPdfPage>(lanes)

        val writer = launch {
            for (result in recognized) {
                accumulator.put(result.page, result.text)?.let { flushed ->
                    onProgress(
                        IngestProgress(
                            "ocr",
                            (flushed - startPage + 1).coerceAtLeast(1),
                            total,
                            if (lanes > 1) {
                                "RapidOCR 已完成 ${flushed - startPage + 1}/$total 页（$lanes 路）"
                            } else {
                                "OCR 第 $flushed/$total 页"
                            }
                        )
                    )
                    onFlushed(flushed)
                }
            }
        }

        val workers = List(lanes) {
            launch(Dispatchers.Default) {
                for (page in rendered) {
                    try {
                        val text = try {
                            ocrEngine.recognize(page.bitmap).text
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            val hint = if (lanes > 1) {
                                "RapidOCR 并行识别失败，请到设置把 OCR 并发调回 1 路后重试"
                            } else {
                                "OCR 识别失败"
                            }
                            throw IllegalStateException("$hint：${t.message ?: t.javaClass.simpleName}", t)
                        }
                        recognized.send(RecognizedPdfPage(page.page, text))
                    } finally {
                        page.bitmap.recycle()
                    }
                }
            }
        }

        try {
            for (pageIndex in pages) {
                if (pageIndex - 1 >= renderer.pageCount) {
                    throw IllegalStateException("PDF 页数不一致：无法渲染第 $pageIndex 页")
                }
                rendered.send(RenderedPdfPage(pageIndex, renderPdfPage(renderer, pageIndex)))
            }
            rendered.close()
            workers.joinAll()
        } finally {
            rendered.close()
            recognized.close()
        }
        writer.join()
    }

    private fun renderPdfPage(renderer: PdfRenderer, pageIndex: Int): Bitmap =
        renderer.openPage(pageIndex - 1).use { page ->
            // RapidOcrEngine 的检测长边上限是 1920，渲染再大只会多占内存。
            val scale = max(2f, 1400f / page.width)
            Bitmap.createBitmap(
                (page.width * scale).toInt(), (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            ).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

    // ---------- 图片 ----------

    private suspend fun ingestImage(uri: Uri, onProgress: suspend (IngestProgress) -> Unit): String {
        onProgress(IngestProgress("ocr", 1, 1, "识别图片文字"))
        val bitmap = decodeImage(uri)
            ?: throw IllegalArgumentException("无法解码图片")
        usedOcr = true
        val text = ocrEngine.recognize(bitmap).text
        bitmap.recycle()
        return text
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        // 上限 2200：RapidOcrEngine 的检测长边上限是 1920，
        // 解码时留一点余量（拍照常有边缘空白），再大只是白占内存。
        while (bounds.outWidth / sample > 2200) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        }.getOrNull()
}

