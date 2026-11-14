package com.examhelper.shua

import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfAnswerPlacement
import com.examhelper.shua.data.model.PdfExportRequest
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.data.model.PdfExportSource
import com.examhelper.shua.data.model.PdfExportOptions
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import com.examhelper.shua.data.pdf.PdfExportNaming
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
                question(1, 1, "第一章", "甲", answer = "A", analysis = "解析甲").copy(minimalSolved = true),
                question(2, 1, "第一章", "乙", answer = "B", analysis = null),
                question(3, 1, "第一章", "丙", answer = "C", analysis = "解析丙").copy(minimalSolved = true)
            )
        )

        val lines = result.answerLines(
            PdfExportOptions(
                includeAnswers = true,
                mode = PdfAnswerMode.MINIMAL,
                placement = PdfAnswerPlacement.AT_END
            )
        )

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
        ).questionBlocks(
            PdfExportOptions(
                includeAnswers = true,
                mode = PdfAnswerMode.ANSWER_ONLY,
                placement = PdfAnswerPlacement.AFTER_EACH
            )
        )

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
        ).questionBlocks(
            PdfExportOptions(
                includeAnswers = true,
                mode = PdfAnswerMode.DEEP,
                placement = PdfAnswerPlacement.AFTER_EACH
            )
        )

        assertTrue(rendered.single().contains("【解析】无"))
    }

    @Test
    fun `极简解析模式不会把深度解析当成极简解析`() {
        val q = question(15, 1, "第一章", "只有深度解析", analysis = "深度解析")
            .copy(deepSolved = true)
        val rendered = WrongBookExport(
            source = PdfExportSource.UNMASTERED,
            questions = listOf(q)
        ).questionBlocks(
            PdfExportOptions(
                includeAnswers = true,
                mode = PdfAnswerMode.MINIMAL,
                placement = PdfAnswerPlacement.AFTER_EACH
            )
        )

        assertTrue(rendered.single().contains("【解析】无"))
    }

    @Test
    fun `Markdown 解析在 PDF 前转成可打印文本`() {
        val q = question(
            9,
            1,
            "第一章",
            "Markdown 题",
            analysis = "## 考点\n- **重点**：`ATP`\n\n| 项目 | 内容 |\n|---|---|\n| A | 正确 |"
        ).copy(deepSolved = true)
        val rendered = WrongBookExport(PdfExportSource.UNMASTERED, listOf(q)).questionBlocks(
            PdfExportOptions(true, PdfAnswerMode.DEEP, PdfAnswerPlacement.AFTER_EACH)
        ).single()

        assertTrue(rendered.contains("考点"))
        assertTrue(rendered.contains("重点"))
        assertTrue(rendered.contains("ATP"))
        assertTrue(!rendered.contains("##"))
        assertTrue(!rendered.contains("**"))
        assertTrue(!rendered.contains("`"))
        assertTrue(!rendered.contains("|---"))
    }

    @Test
    fun `仅导出题目时不带答案和解析`() {
        val q = question(10, 1, "第一章", "只导题", answer = "A", analysis = "不应出现")
        val rendered = WrongBookExport(PdfExportSource.UNMASTERED, listOf(q)).questionBlocks(
            PdfExportOptions(false, PdfAnswerMode.DEEP, PdfAnswerPlacement.AFTER_EACH)
        ).single()

        assertTrue(rendered.contains("只导题"))
        assertTrue(!rendered.contains("【答案】"))
        assertTrue(!rendered.contains("不应出现"))
    }

    @Test
    fun `解析位置切换决定解析在题后还是文末`() {
        val q = question(11, 1, "第一章", "位置题", answer = "C", analysis = "位置解析")
            .copy(minimalSolved = true)
        val export = WrongBookExport(PdfExportSource.UNMASTERED, listOf(q))

        val after = export.questionBlocks(PdfExportOptions(true, PdfAnswerMode.MINIMAL, PdfAnswerPlacement.AFTER_EACH)).single()
        val endQuestions = export.questionBlocks(PdfExportOptions(true, PdfAnswerMode.MINIMAL, PdfAnswerPlacement.AT_END)).single()
        val endAnswers = export.answerLines(PdfExportOptions(true, PdfAnswerMode.MINIMAL, PdfAnswerPlacement.AT_END)).single()

        assertTrue(after.contains("位置解析"))
        assertTrue(!endQuestions.contains("位置解析"))
        assertTrue(endAnswers.contains("位置解析"))
    }

    @Test
    fun `按解析程度检测缺失题目`() {
        val export = WrongBookExport(
            PdfExportSource.UNMASTERED,
            listOf(
                question(12, 1, "第一章", "有深度", analysis = "深度解析").copy(deepSolved = true),
                question(13, 1, "第一章", "只有极简解析", analysis = "极简解析").copy(minimalSolved = true),
                question(14, 1, "第一章", "无解析", analysis = null)
            )
        )

        assertEquals(listOf(13L, 14L), export.missingAnalysisIds(PdfAnswerMode.DEEP))
        assertEquals(listOf(12L, 14L), export.missingAnalysisIds(PdfAnswerMode.MINIMAL))
    }

    @Test
    fun `导出文件名使用题库名和时间`() {
        val name = PdfExportNaming.fileName(
            bankName = "内科学:呼吸/循环",
            timestamp = java.util.Date(0L),
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        )

        assertTrue(name.startsWith("导出错题-内科学_呼吸_循环-"))
        assertTrue(name.endsWith(".pdf"))
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
