package com.examhelper.shua

import com.examhelper.shua.data.parse.MarkdownToText
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ④ Markdown 题库导入：验证「结构降级 → 离线解析」这条链路的真实产出。
 * 断言落在解析结果上，避免测成一堆正则实现细节。
 */
class MarkdownImportTest {

    /** 数学定界符。Kotlin 字符串里 "$" 会触发模板插值，统一用这个常量拼。 */
    private val d = "${'$'}"

    private val sample: String = buildString {
        appendLine("# 药理学 第一章")
        appendLine()
        appendLine("## 单项选择题")
        appendLine()
        appendLine("**1.** 下列属于受体阻断药的是 " + d + "x_1" + d + " 与 " + d + "a_{n+1}=a_n+d" + d)
        appendLine()
        appendLine("- A. 普萘洛尔")
        appendLine("- B. 肾上腺素 ~~删除线~~")
        appendLine("- C. `阿托品`")
        appendLine("- D. 麻黄碱")
        appendLine()
        appendLine("【答案】A")
        appendLine("【解析】要点见 **下表**：")
        appendLine()
        appendLine("| 药物 | 受体 |")
        appendLine("| --- | --- |")
        appendLine("| 普萘洛尔 | β |")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("### 名词解释")
        appendLine()
        appendLine("2. 解释肝药酶：___")
        appendLine()
        appendLine("答案：肝药酶指肝脏微粒体混合功能氧化酶系。")
    }

    @Test
    fun markdownBecomesImportableQuestions() {
        val normalized = MarkdownToText.normalize(sample)

        // # 标题原样透传（TextQuestionParser.headingRegex 负责当章节）
        assertTrue(normalized.contains("# 药理学 第一章"))
        // 强调 / 行内代码 / 删除线 / 独立分隔线全部降级
        assertFalse(normalized.contains("**"))
        assertFalse(normalized.contains("~~"))
        assertFalse(normalized.contains("`"))
        // 表格的 `| --- | --- |` 分隔行要保留（解析区靠它渲染真表格），
        // 但独立成行的 `---` 分隔线必须消失：正则判断整行是否就是分隔线。
        assertFalse(
            "独立分隔线应被丢弃",
            normalized.split('\n').any { it.trim().matches(Regex("^[-*_]\\s*([-*_]\\s*){2,}$")) }
        )
        // 数学区一个字符都不能被吃掉
        assertTrue(normalized.contains(d + "x_1" + d))
        assertTrue(normalized.contains(d + "a_{n+1}=a_n+d" + d))
        // 表格结构保留（竖线与表头都还在）
        assertTrue(normalized.contains("| 药物 | 受体 |"))
        assertTrue(normalized.contains("| 普萘洛尔 | β |"))

        val qs = TextQuestionParser.parse(normalized)
        assertEquals(2, qs.size)

        val choice = qs[0]
        assertEquals("药理学 第一章", choice.chapter)
        assertTrue(choice.stem.contains("受体阻断药"))
        assertEquals(4, choice.options.size)
        assertEquals("A", choice.answer)
        assertEquals("普萘洛尔", choice.options[0].text)
        // 行内代码剥掉、删除线标记去掉但内容保留
        assertEquals("阿托品", choice.options[2].text)
        assertTrue(choice.options[1].text.contains("肾上腺素"))

        val term = qs[1]
        // `### 名词解释` 是题型分组标题，章节仍归属 `# 药理学 第一章`
        assertEquals("药理学 第一章", term.chapter)
        assertEquals("肝药酶指肝脏微粒体混合功能氧化酶系。", term.answer)
        // 题干带 `___` 但答案是长句、且属概念题 → 名词解释而非填空
        assertEquals(com.examhelper.shua.data.db.QuestionType.TERM, term.type)
    }

    @Test
    fun mathAndLinksAreNeverRewritten() {
        val src = "求 " + d + "\\frac{a}{b}" + d + " 的值，详见 [教材](http://x.cn/a_b_c)。"
        val out = MarkdownToText.normalize(src).trim()
        // LaTeX 原样保留；链接只留锚文本，URL 里的下划线不会被当成强调
        assertEquals("求 " + d + "\\frac{a}{b}" + d + " 的值，详见 教材。", out)
    }

    @Test
    fun fencedCodeSurvivesInlineCleanup() {
        val src = "普通一行\n```\ncode | keep **stars**\n```\n下一行"
        val out = MarkdownToText.normalize(src)
        assertTrue(out.contains("keep **stars**"))
        assertFalse(out.contains("```"))
    }
}
