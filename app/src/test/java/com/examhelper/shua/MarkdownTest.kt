package com.examhelper.shua

import com.examhelper.shua.ui.theme.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 标记识别的单测。
 *
 * 曾经的 bug：`looksLikeMarkdown` 只认 `**`，所以 AI 回答里的单行 `*重点*` / `_重点_`
 * 被判成纯文本原样显示，星号裸露。这里把识别范围钉死，同时固化「公式内部不算 Markdown」
 * 的约束 —— 否则 `$x_1$` 这类下标会被斜体解析器吃掉下划线，把 v1.0.6 修好的公式渲染弄坏。
 */
class MarkdownTest {

    @Test
    fun singleAsteriskItalicIsDetected() {
        assertTrue(Markdown.looksLikeMarkdown("*重点*"))
        assertTrue(Markdown.looksLikeMarkdown("请注意 *这一点* 非常重要"))
    }

    @Test
    fun singleUnderscoreItalicIsDetectedWhenDelimited() {
        assertTrue(Markdown.looksLikeMarkdown("_重点_"))
        assertTrue(Markdown.looksLikeMarkdown("答案是 _选 B_ 因为"))
    }

    @Test
    fun boldAndCodeAndStrikeAndHeadingStillDetected() {
        assertTrue(Markdown.looksLikeMarkdown("**粗**"))
        assertTrue(Markdown.looksLikeMarkdown("__粗__"))
        assertTrue(Markdown.looksLikeMarkdown("`code`"))
        assertTrue(Markdown.looksLikeMarkdown("~~删~~"))
        assertTrue(Markdown.looksLikeMarkdown("## 小标题"))
        assertTrue(Markdown.looksLikeMarkdown("> 引用"))
        assertTrue(Markdown.looksLikeMarkdown("- 列表项"))
        assertTrue(Markdown.looksLikeMarkdown("1. 有序项"))
    }

    @Test
    fun multiLineTextIsAlwaysTreatedAsMarkdown() {
        assertTrue(Markdown.looksLikeMarkdown("第一行\n第二行"))
    }

    @Test
    fun plainTextIsNotMarkdown() {
        assertFalse(Markdown.looksLikeMarkdown(""))
        assertFalse(Markdown.looksLikeMarkdown("这是一句普通的话"))
        assertFalse(Markdown.looksLikeMarkdown("A. 选项内容"))
        // 未闭合的标记不构成 Markdown
        assertFalse(Markdown.looksLikeMarkdown("这里有 * 一个星号"))
        assertFalse(Markdown.looksLikeMarkdown("a * b = c"))
    }

    @Test
    fun snakeCaseIsNotItalic() {
        // 下划线夹在单词中间，不能当斜体
        assertFalse(Markdown.looksLikeMarkdown("user_name_is_taken"))
        assertFalse(Markdown.looksLikeMarkdown("max_retry_count"))
    }

    @Test
    fun latexInsideDollarIsNotMarkdown() {
        // 公式体里的下标/星号不得触发 Markdown 判定
        assertFalse(Markdown.looksLikeMarkdown("\$x_1\$"))
        assertFalse(Markdown.looksLikeMarkdown("求 \$a_{n+1} = a_n + d\$ 的通项"))
        assertFalse(Markdown.looksLikeMarkdown("\$\$\\sum_{i=1}^{n} i\$\$"))
        assertFalse(Markdown.looksLikeMarkdown("\$H_2O\$ 与 \$CO_2\$"))
    }

    @Test
    fun markupOutsideFormulaStillCounts() {
        // 公式被跳过，但公式外的 ** 仍要识别
        assertTrue(Markdown.looksLikeMarkdown("**答案** 是 \$x_1\$"))
        assertTrue(Markdown.looksLikeMarkdown("\$x^2\$ *注意*"))
    }

    // ---- 行间公式 $$..$$ 必须被切成独立块，而不是当普通文字打出来 ----

    /** 反斜杠转义的美元符，避免 Kotlin 模板插值 */
    private val NL = "\n"
    private val dd = "${'$'}${'$'}"

    @Test
    fun displayMathInsideParagraphIsSplitOut() {
        val src = "解析：" + NL + dd + NL + "x^2+1" + NL + dd + NL + "所以选 A"
        val blocks = Markdown.debugParaBlocks(src)
        assertEquals(
            listOf("解析：" to false, "x^2+1" to true, "所以选 A" to false),
            blocks
        )
    }

    @Test
    fun displayMathAtStartAndEndIsSplitOut() {
        val blocks = Markdown.debugParaBlocks(dd + "\\frac{a}{b}" + dd)
        assertEquals(listOf("\\frac{a}{b}" to true), blocks)
    }

    @Test
    fun multipleDisplayMathInOneParagraph() {
        val blocks = Markdown.debugParaBlocks(
            "先看 " + dd + " a+b " + dd + " 再看 " + dd + " c-d " + dd + " 结束"
        )
        assertEquals(
            listOf(
                "先看" to false, "a+b" to true,
                "再看" to false, "c-d" to true,
                "结束" to false
            ),
            blocks
        )
    }

    @Test
    fun unclosedDisplayMathIsKeptAsText() {
        // 未闭合时不能吞掉后半段，交给行内解析器原样保留
        val blocks = Markdown.debugParaBlocks("公式 " + dd + " x^2 没写完")
        // 未闭合 时不能当行间公式，且後半段不能丢失
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.none { it.second })
        assertTrue(blocks.joinToString("") { it.first }.contains("x^2"))
    }

    @Test
    fun latexBracketDelimitersAreNormalized() {
        // \[ .. \] 是 LaTeX 原生写法，模型很爱用，必须归一成 $$
        val blocks = Markdown.debugParaBlocks("\\[ a^2+b^2 \\]")
        assertEquals(listOf("a^2+b^2" to true), blocks)
    }
}
