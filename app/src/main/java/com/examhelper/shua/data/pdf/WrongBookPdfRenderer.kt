package com.examhelper.shua.data.pdf

import com.examhelper.shua.data.model.PdfAnswerPlacement
import com.examhelper.shua.data.model.PdfExportOptions
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
    internal const val TOP_MARGIN = 76.34647f
    private const val SIDE_MARGIN = 48f
    private const val BOTTOM_MARGIN = 48f
    private const val HEADER_Y = 36f
    private const val FOOTER_Y = 22f
    private const val TITLE_SIZE = 20f
    private const val BODY_SIZE = 12f
    private const val LINE_GAP = 5f

    fun render(
        title: String,
        bankName: String,
        export: WrongBookExport,
        options: PdfExportOptions,
        sansFont: InputStream,
        serifFont: InputStream,
        notice: String? = null
    ): ByteArray {
        require(export.questions.isNotEmpty()) { "没有可导出的错题" }
        return PDDocument().use { doc ->
            val sans = PDType0Font.load(doc, sansFont)
            val serif = PDType0Font.load(doc, serifFont)
            val writer = Writer(doc, sans, serif, title, bankName, notice)
            writer.newQuestionPage(firstPage = true)
            export.questionBlocks(options).forEachIndexed { i, block ->
                writer.heading("${i + 1}. ${export.questions[i].stem}")
                block.lineSequence().drop(1).forEach(writer::line)
                writer.space(8f)
            }
            if (options.includeAnswers && options.placement == PdfAnswerPlacement.AT_END) {
                writer.newAnswerPage()
                writer.heading("答案与解析")
                export.answerLines(options).forEach { answer ->
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
        private val bankName: String,
        private val notice: String?
    ) {
        private var page: PDPage? = null
        private var stream: PDPageContentStream? = null
        private var y = 0f

        fun newQuestionPage(firstPage: Boolean = false) = newPage(firstPage, title)

        fun newAnswerPage() = newPage(firstPage = false, pageTitle = "答案与解析")

        private fun newPage(firstPage: Boolean, pageTitle: String) {
            closeStream()
            page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
            stream = PDPageContentStream(doc, page)
            y = page!!.mediaBox.height - TOP_MARGIN
            if (firstPage) title(pageTitle) else header()
            notice?.takeIf { it.isNotBlank() }?.let { line(it) }
            footer(doc.numberOfPages)
        }

        private fun title(text: String) {
            withStream { s ->
                s.beginText()
                s.setFont(sans, TITLE_SIZE)
                s.newLineAtOffset(SIDE_MARGIN, y)
                s.showText(text)
                s.endText()
            }
            y -= TITLE_SIZE + 14f
        }

        private fun header() {
            if (bankName.isBlank()) return
            withStream { s ->
                s.beginText()
                s.setFont(sans, 9f)
                s.newLineAtOffset(SIDE_MARGIN, page!!.mediaBox.height - HEADER_Y)
                s.showText(bankName)
                s.endText()
            }
        }

        private fun footer(pageNumber: Int) {
            val text = pageNumber.toString()
            val width = serif.getStringWidth(text) / 1000f * 9f
            withStream { s ->
                s.beginText()
                s.setFont(serif, 9f)
                s.newLineAtOffset((page!!.mediaBox.width - width) / 2f, FOOTER_Y)
                s.showText(text)
                s.endText()
            }
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
                    s.newLineAtOffset(SIDE_MARGIN, y)
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
            if (y >= BOTTOM_MARGIN + size) return
            newQuestionPage()
        }

        private fun wrap(text: String, font: PDType0Font, size: Float): List<String> {
            val max = page!!.mediaBox.width - SIDE_MARGIN * 2
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
