package com.examhelper.shua.data.pdf

import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 错题本 PDF 的最小排版器；字体由调用方提供，便于 Android assets 与 JVM 测试共用。 */
object WrongBookPdfRenderer {
    private const val MARGIN = 48f
    private const val TITLE_SIZE = 20f
    private const val BODY_SIZE = 12f
    private const val LINE_GAP = 5f
    private const val PAGE_MARKER_Q = "QuestionPage"
    private const val PAGE_MARKER_A = "AnswerPage"

    fun render(
        title: String,
        export: WrongBookExport,
        mode: PdfAnswerMode,
        sansFont: InputStream,
        serifFont: InputStream,
        notice: String? = null
    ): ByteArray {
        require(export.questions.isNotEmpty()) { "没有可导出的错题" }
        return PDDocument().use { doc ->
            val sans = PDType0Font.load(doc, sansFont)
            val serif = PDType0Font.load(doc, serifFont)
            val writer = Writer(doc, sans, serif, title, notice)
            writer.page(PAGE_MARKER_Q, title)
            export.questionBlocks(mode).forEachIndexed { i, block ->
                writer.heading("${i + 1}. ${export.questions[i].stem}")
                block.lineSequence().drop(1).forEach(writer::line)
                writer.space(8f)
            }
            if (mode == PdfAnswerMode.CONCENTRATED) {
                writer.newPage()
                writer.page(PAGE_MARKER_A)
                writer.heading("答案与解析")
                export.answerLines(mode).forEach { answer ->
                    answer.lines().forEach(writer::line)
                    writer.space(6f)
                }
            }
            writer.close()
            ByteArrayOutputStream().use { out ->
                doc.save(out)
                out.toByteArray()
            }
        }
    }

    private class Writer(
        private val doc: PDDocument,
        private val sans: PDType0Font,
        private val serif: PDType0Font,
        private val title: String,
        private val notice: String?
    ) {
        private var page: PDPage? = null
        private var stream: PDPageContentStream? = null
        private var y = 0f

        fun newPage() {
            y = MARGIN
            closeStream()
        }

        fun page(marker: String, pageTitle: String = title) {
            closeStream()
            page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
            stream = PDPageContentStream(doc, page).also { s ->
                s.beginText()
                s.setFont(sans, 1f)
                s.newLineAtOffset(MARGIN, MARGIN)
                s.showText(marker)
                s.endText()
            }
            y = page!!.mediaBox.height - MARGIN
            if (pageTitle.isNotBlank()) title(pageTitle)
            notice?.takeIf { it.isNotBlank() }?.let { line(it) }
        }

        private fun title(text: String) {
            withStream { s ->
                s.beginText()
                s.setFont(sans, TITLE_SIZE)
                s.newLineAtOffset(MARGIN, y)
                s.showText(text)
                s.endText()
            }
            y -= TITLE_SIZE + 14f
        }

        fun heading(text: String) = line(text, bold = true)

        fun line(text: String, bold: Boolean = false) {
            if (text.isBlank()) {
                y -= BODY_SIZE + LINE_GAP
                return
            }
            val size = BODY_SIZE
            val lines = wrap(text, if (bold) sans else serif, size)
            lines.forEach { part ->
                ensureSpace(size)
                withStream { s ->
                    s.beginText()
                    s.setFont(if (bold) sans else serif, size)
                    s.newLineAtOffset(MARGIN, y)
                    s.showText(part)
                    s.endText()
                }
                y -= size + LINE_GAP
            }
        }

        fun space(height: Float) {
            y -= height
        }

        private fun ensureSpace(size: Float) {
            if (y >= MARGIN + size) return
            val marker = PAGE_MARKER_Q
            closeStream()
            page(PAGE_MARKER_Q)
        }

        private fun wrap(text: String, font: PDType0Font, size: Float): List<String> {
            val max = page!!.mediaBox.width - MARGIN * 2
            val out = mutableListOf<String>()
            var current = StringBuilder()
            text.forEach { c ->
                val next = current.toString() + c
                if (current.isNotEmpty() && font.getStringWidth(next) / 1000f * size > max) {
                    out += current.toString()
                    current = StringBuilder()
                }
                current.append(c)
            }
            if (current.isNotEmpty()) out += current.toString()
            return out.ifEmpty { listOf("") }
        }

        private fun withStream(block: (PDPageContentStream) -> Unit) {
            stream?.let(block)
        }

        private fun closeStream() {
            stream?.close()
            stream = null
        }

        fun close() = closeStream()
    }
}
