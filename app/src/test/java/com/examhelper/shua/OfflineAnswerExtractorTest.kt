package com.examhelper.shua

import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随题答案的多种真实排版回归。
 *
 * 用户反馈的根因：`D. 选项正文 参考答案：A` 整行先命中选项正则，
 * 「参考答案：A」被当成 D 的正文追加。这里覆盖冒号/方括号/文字句式、
 * 答案与解析同行、答案夹在选项后、多选与判断等场景。
 */
class OfflineAnswerExtractorTest {

    private fun parseOne(body: String) = TextQuestionParser.parse(body).first()

    @Test
    fun `选项后紧跟参考答案时答案不会被吞进选项`() {
        val q = parseOne(
            """
            1. 下列哪项正确（ ）
            A. 甲
            B. 乙
            C. 丙
            D. 丁 参考答案：A
            """.trimIndent()
        )
        assertEquals("A", q.answer)
        assertEquals(4, q.options.size)
        assertEquals("丁", q.options[3].text)
    }

    @Test
    fun `常见答案标签与文字句式都能识别`() {
        val variants = listOf(
            "答案：A", "参考答案：A", "【答案】A", "【参考答案】A",
            "正确答案：A", "标准答案：A", "本题答案：A", "答案为A",
            "正确答案是A", "参考答案及解析：A", "Answer: A", "答案 A"
        )
        variants.forEachIndexed { i, line ->
            val q = TextQuestionParser.parse(
                """
                1. 第${i}题（ ）
                A. 甲
                B. 乙
                $line
                """.trimIndent()
            ).first()
            assertEquals("variant=$line", "A", q.answer)
        }
    }

    @Test
    fun `答案和解析在同一行时分别入库`() {
        val q = parseOne(
            """
            1. 下列说法正确的是（ ）
            A. 甲
            B. 乙
            参考答案：B　解析：乙是唯一正确项。
            """.trimIndent()
        )
        assertEquals("B", q.answer)
        assertEquals("乙是唯一正确项。", q.analysis)
        assertEquals("乙", q.options[1].text)
    }

    @Test
    fun `选项与答案解析同一行时也拆开`() {
        val q = parseOne(
            """
            1. 单选题（ ）
            A. 甲
            B. 乙
            C. 丙
            D. 丁 【正确答案】C 解析：丙符合题意。
            """.trimIndent()
        )
        assertEquals("C", q.answer)
        assertEquals("丁", q.options[3].text)
        assertEquals("丙符合题意。", q.analysis)
    }

    @Test
    fun `判断题的多种正确答案写法`() {
        val q = parseOne(
            """
            1. 线粒体DNA呈母系遗传（ ） 正确答案：正确
            """.trimIndent()
        )
        // 判断题统一归一成 A/B（A=正确），与库内 JudgeOptions 的约定一致。
        assertEquals("A", q.answer)
        assertNotNull(q.options)
        assertTrue(q.options.size == 2)
    }

    @Test
    fun `多选题紧凑答案不会多吞字符`() {
        val q = parseOne(
            """
            1. 下列正确的有（ ）
            A. 甲
            B. 乙
            C. 丙
            D. 丁
            参考答案：A,C,D
            """.trimIndent()
        )
        assertEquals("ACD", q.answer!!.replace(",", "").replace("，", ""))
    }

    @Test
    fun `答案解析不是答案标签`() {
        val q = parseOne(
            """
            1. 题干（ ）
            A. 甲
            B. 乙
            答案解析：因为乙正确。
            答案：B
            """.trimIndent()
        )
        assertEquals("B", q.answer)
        assertTrue(q.options[1].text == "乙")
    }
}
