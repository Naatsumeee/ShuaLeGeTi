package com.examhelper.shua

import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfExportSource
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import com.examhelper.shua.data.pdf.WrongBookPdfRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WrongBookPdfRendererTest {

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `PDF 使用 A4 尺寸并为集中答案单独分页`() {
        val export = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(
                PdfQuestion(1, 1, "第一章", "SINGLE", "题干一", listOf("A. 甲", "B. 乙"), "A", "解析一", true),
                PdfQuestion(2, 1, "第一章", "SINGLE", "题干二", listOf("A. 丙", "B. 丁"), "B", null, false)
            )
        )

        val bytes = WrongBookPdfRenderer.render(
            title = "题库-第一章",
            export = export,
            mode = PdfAnswerMode.CONCENTRATED,
            sansFont = File("src/main/assets/pdf/NotoSansSC-Bold.ttf").inputStream(),
            serifFont = File("src/main/assets/pdf/NotoSerifSC-Regular.ttf").inputStream()
        )

        assertTrue(bytes.size > 1000)
        PDDocument.load(bytes).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val box = doc.getPage(0).mediaBox
            assertEquals(595.28f, box.width, 0.1f)
            assertEquals(841.89f, box.height, 0.1f)
        }
    }

    @Test
    fun `没有题目时不生成空 PDF`() {
        val error = runCatching {
            WrongBookPdfRenderer.render(
                title = "题库",
                export = WrongBookExport(PdfExportSource.UNMASTERED, emptyList()),
                mode = PdfAnswerMode.NONE,
                sansFont = File("src/main/assets/pdf/NotoSansSC-Bold.ttf").inputStream(),
                serifFont = File("src/main/assets/pdf/NotoSerifSC-Regular.ttf").inputStream()
            )
        }.exceptionOrNull()

        assertEquals("没有可导出的错题", error?.message)
    }
}
