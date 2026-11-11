package com.examhelper.shua.data.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.examhelper.shua.data.ocr.MlKitOcrEngine
import com.examhelper.shua.data.parse.MarkdownToText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.max

/** 提取进度回调 */
data class IngestProgress(
    val stage: String,     // "extract" / "ocr"
    val current: Int,
    val total: Int,
    val message: String
)

/**
 * 统一文档入口：根据 Uri 与扩展名分流到 docx / PDF / Markdown / 纯文本 / 图片五条路径，
 * 全部产出纯文本。
 */
class DocumentIngestor(
    private val context: Context,
    private val ocrEngine: com.examhelper.shua.data.ocr.OcrProvider = com.examhelper.shua.data.ocr.MlKitOcrEngine()
) {

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
        val lower = name.lowercase()
        // 部分文件管理器 / 云盘返回的 display name 没有扩展名，退回 ContentResolver 的 MIME 判断
        val mime = if (lower.endsWith(".docx") || lower.endsWith(".pdf") || lower.endsWith(".doc") ||
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") ||
            lower.endsWith(".text")
        ) "" else context.contentResolver.getType(uri).orEmpty().lowercase()
        val asMarkdown = lower.endsWith(".md") || lower.endsWith(".markdown") ||
            mime.contains("markdown")
        val asPlainText = asMarkdown || lower.endsWith(".txt") || lower.endsWith(".text") ||
            (mime.startsWith("text/") && !mime.contains("html"))
        when {
            lower.endsWith(".docx") -> extractDocx(uri)
            lower.endsWith(".pdf") -> ingestPdf(uri, onProgress)
            lower.endsWith(".doc") ->
                throw IllegalArgumentException("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后导入")
            asPlainText -> extractPlainText(uri, asMarkdown, onProgress)
            else -> ingestImage(uri, onProgress)
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
        input.use { stream ->
            val zip = java.util.zip.ZipInputStream(stream)
            var entry = zip.nextEntry
            val paragraphs = StringBuilder()
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xmlBytes = zip.readBytes()
                    paragraphs.append(parseDocxXml(xmlBytes))
                    break
                }
                entry = zip.nextEntry
            }
            if (paragraphs.isBlank()) throw IllegalArgumentException("docx 中没有找到文本内容")
            return paragraphs.toString()
        }
    }

    private fun parseDocxXml(bytes: ByteArray): String {
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val sb = StringBuilder()
        var paragraph = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG ->
                    if (parser.name == "t") paragraph.append(parser.nextText())
                XmlPullParser.END_TAG ->
                    if (parser.name == "p") {
                        if (paragraph.isNotBlank()) sb.appendLine(paragraph.toString().trim())
                        paragraph = StringBuilder()
                    }
            }
            event = parser.next()
        }
        if (paragraph.isNotBlank()) sb.appendLine(paragraph.toString().trim())
        return sb.toString()
    }

    // ---------- PDF：文字页直接抽文本，扫描页 OCR ----------

    private suspend fun ingestPdf(uri: Uri, onProgress: suspend (IngestProgress) -> Unit): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val file = cachePdf(uri)
        val result = StringBuilder()

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
            val usablePages = textByPage.count { it.value.isNotBlank() }
            val mostlyScanned = usablePages * 2 < total

            // 续跑：把已落盘的页原样接回去，从断点页继续
            if (resumePrefix.isNotBlank()) result.append(resumePrefix)
            val startPage = resumeFrom.coerceIn(1, total + 1)

            // 渲染器在整个循环里复用：一页一建等于把整份 PDF 的索引重建 total 遍。
            val renderer = try {
                PdfRendererHolder.acquire(file)
            } catch (e: Throwable) {
                null
            }
            try {
                for (i in startPage..total) {
                    val pageText = textByPage[i].orEmpty().trim()
                    // 两个条件都要过：长度够（不是几乎空白的扫描页），且不是乱码。
                    // 嵌入式字体缺 ToUnicode CMap 时 PDFBox 会抽出成片 U+FFFD 或私有区字符，
                    // 长度能过阈值但内容全是垃圾——这类页必须走 OCR，否则垃圾会直接进题库。
                    if (pageText.length >= 20 && !TextDecoder.looksGarbled(pageText)) {
                        onProgress(IngestProgress("extract", i, total, "读取第 $i/$total 页"))
                        result.appendLine("[[PG:$i]]")
                        result.appendLine(pageText)
                    } else {
                        // 文字过少或乱码 → 判定为扫描页，走 OCR
                        result.appendLine("[[PG:$i]]")
                        result.appendLine(
                            ocrPdfPage(renderer, i, onProgress, i, total)
                        )
                        // 每页 OCR 完立刻落一次盘：47 页扫描件在低端机上要跑十几分钟，
                        // 中途被系统杀掉时，已识别的页不该跟着白干。见 [resumeFrom]。
                        onPageDone?.invoke(i, result.toString())
                    }
                }
            } finally {
                PdfRendererHolder.release(file)
            }
        } finally {
            file.delete()
        }
        if (result.isBlank()) throw IllegalArgumentException("PDF 中没有可识别的内容")
        return result.toString()
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
     * 渲染 + OCR 单页。
     *
     * `PdfRenderer` 的开销在**构造**（要解析 xref、建立页索引），不在打开单页。
     * 早期实现每页都 `ParcelFileDescriptor.open + PdfRenderer(...)`，47 页就是把
     * 同一份 45MB 扫描件的索引重建 47 次 —— 这是扫描件导入慢到「看起来没反应」
     * 的第二个来源。现在渲染器由 [ingestPdf] 跨页复用，这里只负责开页、渲染、回收。
     */
    private suspend fun ocrPdfPage(
        renderer: PdfRenderer?,
        pageIndex: Int,
        onProgress: suspend (IngestProgress) -> Unit,
        current: Int,
        total: Int
    ): String {
        onProgress(IngestProgress("ocr", current, total, "OCR 第 $current/$total 页"))
        if (renderer == null) return ""
        if (pageIndex - 1 >= renderer.pageCount) return ""
        return renderer.openPage(pageIndex - 1).use { page ->
            // 长边固定到 1400 左右：RapidOcrEngine 的检测长边上限是 1920，
            // 渲染再大只会多占内存、不会多识别出一个字。
            val scale = max(2f, 1400f / page.width)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(), (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                runCatching { ocrEngine.recognize(bitmap).text }.getOrDefault("")
            } finally {
                // 早先只在成功路径 recycle，OCR 抛异常时这张十几 MB 的位图会漏掉，
                // 后面几页在内存吃紧时被系统拖慢甚至 OOM
                bitmap.recycle()
            }
        }
    }
    /**
     * 进程内复用的 [PdfRenderer]。
     *
     * `PdfRenderer` 必须与它打开的 `ParcelFileDescriptor` 同生共死，且不能并发使用，
     * 所以按文件路径缓存并在用完后 release。
     */
    private object PdfRendererHolder {
        private var holder: Pair<String, Pair<android.os.ParcelFileDescriptor, PdfRenderer>>? = null

        @Synchronized
        fun acquire(file: java.io.File): PdfRenderer? {
            val key = file.absolutePath
            holder?.let { (k, v) -> if (k == key) return v.second }
            // 换文件：先把上一份的渲染器与 fd 收掉，再开新的
            closeHolder()
            val pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ) ?: return null
            val renderer = runCatching { PdfRenderer(pfd) }.getOrElse {
                runCatching { pfd.close() }
                return null
            }
            holder = key to (pfd to renderer)
            return renderer
        }

        @Synchronized
        fun release(file: java.io.File) {
            val (k, _) = holder ?: return
            if (k != file.absolutePath) return
            closeHolder()
        }

        private fun closeHolder() {
            holder?.let { (_, v) ->
                runCatching { v.second.close() }
                runCatching { v.first.close() }
            }
            holder = null
        }
    }

    // ---------- 图片 ----------

    private suspend fun ingestImage(uri: Uri, onProgress: suspend (IngestProgress) -> Unit): String {
        onProgress(IngestProgress("ocr", 1, 1, "识别图片文字"))
        val bitmap = decodeImage(uri)
            ?: throw IllegalArgumentException("无法解码图片")
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

