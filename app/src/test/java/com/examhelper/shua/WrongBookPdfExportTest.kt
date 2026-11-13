package com.examhelper.shua

import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfExportRequest
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.data.model.PdfExportSource
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrongBookPdfExportTest {

    @Test
    fun `导出范围继承选项卡并按题库章节筛选且保持列表顺序`() {
        val export = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(
                question(id = 31, bankId = 2, chapter = "第三章", stem = "第三题"),
                question(id = 11, bankId = 1, chapter = "第一章", stem = "第一题"),
                question(id = 12, bankId = 1, chapter = "第一章", stem = "第二题")
            )
        )

        val result = export.select(
            PdfExportRequest(
                bankId = 1,
                scope = PdfExportScope.CHAPTERS,
                chapters = setOf("第一章")
            )
        )

        assertEquals(listOf(11L, 12L), result.map { it.id })
        assertEquals(listOf("第一题", "第二题"), result.map { it.stem })
    }

    @Test
    fun `集中答案使用与正文一致的连续题号且缺解析写无`() {
        val result = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(
                question(1, 1, "第一章", "甲", answer = "A", analysis = "解析甲"),
                question(2, 1, "第一章", "乙", answer = "B", analysis = null),
                question(3, 1, "第一章", "丙", answer = "C", analysis = "解析丙")
            )
        )

        val lines = result.answerLines(PdfAnswerMode.CONCENTRATED)

        assertEquals(
            listOf(
                "1.【答案】A\n【解析】解析甲",
                "2.【答案】B\n【解析】无",
                "3.【答案】C\n【解析】解析丙"
            ),
            lines
        )
    }

    @Test
    fun `单章标题和带缺章号的多章标题符合打印规则`() {
        assertEquals(
            "题库名称-第一章",
            WrongBookExport.title("题库名称", listOf("第一章"))
        )
        assertEquals(
            "题库名称-第一、三、五、六章",
            WrongBookExport.title(
                "题库名称",
                listOf("第一章", "第三章", "第五章", "第六章")
            )
        )
        assertEquals(
            "题库名称-第一章、第三章、并发症",
            WrongBookExport.title(
                "题库名称",
                listOf("第一章", "第三章", "并发症")
            )
        )
    }

    @Test
    fun `题型选项和答案会进入 PDF 正文数据`() {
        val q = question(
            7,
            1,
            "第一章",
            "下列说法正确的是？",
            answer = "AB",
            analysis = "甲乙均正确",
            options = listOf("A. 甲", "B. 乙", "C. 丙")
        )
        val rendered = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(q)
        ).questionBlocks(PdfAnswerMode.ANSWER_ONLY)

        assertTrue(rendered.single().contains("下列说法正确的是？"))
        assertTrue(rendered.single().contains("A. 甲"))
        assertTrue(rendered.single().contains("【答案】AB"))
    }

    @Test
    fun `深度解析模式在没有深度解题时标注无`() {
        val q = question(8, 1, "第一章", "未深度解题", analysis = "旧简析")
        val rendered = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(q)
        ).questionBlocks(PdfAnswerMode.DEEP)

        assertTrue(rendered.single().contains("【解析】无"))
    }

    private fun question(
        id: Long,
        bankId: Long,
        chapter: String,
        stem: String,
        answer: String? = "A",
        analysis: String? = "解析",
        options: List<String> = listOf("A. 甲", "B. 乙")
    ) = PdfQuestion(
        id = id,
        bankId = bankId,
        chapter = chapter,
        type = "SINGLE",
        stem = stem,
        options = options,
        answer = answer,
        analysis = analysis,
        deepSolved = false
    )
}
