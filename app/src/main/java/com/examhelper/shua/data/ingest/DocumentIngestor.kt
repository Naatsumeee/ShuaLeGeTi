package com.examhelper.shua.data.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.examhelper.shua.data.ocr.MlKitOcrEngine
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
 * 统一文档入口：根据 Uri 与扩展名分流到 docx / PDF / 图片三条路径，
 * 全部产出纯文本。
 */
class DocumentIngestor(
    private val context: Context,
    private val ocrEngine: MlKitOcrEngine = MlKitOcrEngine()
) {

    suspend fun ingest(
        uri: Uri,
        onProgress: suspend (IngestProgress) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: ""
        val lower = name.lowercase()
        when {
            lower.endsWith(".docx") -> extractDocx(uri)
            lower.endsWith(".pdf") -> ingestPdf(uri, onProgress)
            lower.endsWith(".doc") ->
                throw IllegalArgumentException("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后导入")
            else -> ingestImage(uri, onProgress)
        }
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

        // 第一遍：PDFBox 打开并逐页处理
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val total = doc.numberOfPages
            for (i in 1..total) {
                onProgress(IngestProgress("extract", i, total, "读取第 $i/$total 页"))
                stripper.startPage = i
                stripper.endPage = i
                val pageText = runCatching { stripper.getText(doc) }.getOrDefault("").trim()
                if (pageText.length >= 20) {
                    result.appendLine(pageText)
                } else {
                    // 文字过少 → 判定为扫描页，走 OCR
                    result.appendLine(ocrPdfPage(file, i, onProgress, i, total))
                }
            }
        }
        file.delete()
        if (result.isBlank()) throw IllegalArgumentException("PDF 中没有可识别的内容")
        return result.toString()
    }

    private fun cachePdf(uri: Uri): java.io.File {
        val target = java.io.File(context.cacheDir, "ingest_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("无法读取 PDF")
        return target
    }

    private suspend fun ocrPdfPage(
        file: java.io.File,
        pageIndex: Int,
        onProgress: suspend (IngestProgress) -> Unit,
        current: Int,
        total: Int
    ): String {
        onProgress(IngestProgress("ocr", current, total, "OCR 第 $current/$total 页"))
        return android.os.ParcelFileDescriptor.open(
            file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
        )?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex - 1 >= renderer.pageCount) return@use ""
                renderer.openPage(pageIndex - 1).use { page ->
                    val scale = max(2f, 1400f / page.width)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt(), (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = runCatching { ocrEngine.recognize(bitmap).text }.getOrDefault("")
                    bitmap.recycle()
                    text
                }
            }
        } ?: ""
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
        while (bounds.outWidth / sample > 2200) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        }.getOrNull()
}

