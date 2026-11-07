package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JudgeOptions
import com.examhelper.shua.data.model.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判断题：真实题库几乎从不写「A.正确 / B.错误」，只有一句题干 + 「答案：对」。
 * 这里锁死「一定能补出两个选项、答案一定归一成 A/B」的行为。
 */
class JudgeOptionsTest {

    @Test
    fun `没有选项时补出正确与错误两项`() {
        val (opts, ans) = JudgeOptions.normalize(QuestionType.JUDGE, emptyList(), "对")
        assertEquals(2, opts.size)
        assertEquals("正确", opts[0].text)
        assertEquals("错误", opts[1].text)
        assertEquals("A", opts[0].key)
        assertEquals("B", opts[1].key)
        assertEquals("A", ans)
    }

    @Test
    fun `答案写错时归一成 B`() {
        val (_, ans) = JudgeOptions.normalize(QuestionType.JUDGE, emptyList(), "错误")
        assertEquals("B", ans)
    }

    @Test
    fun `各种写法都能判断正误`() {
        listOf("对", "正确", "√", "T", "是", "TRUE", "A").forEach {
            assertEquals("应为真：$it", true, JudgeOptions.truthValue(it))
        }
        listOf("错", "错误", "×", "F", "否", "FALSE", "B").forEach {
            assertEquals("应为假：$it", false, JudgeOptions.truthValue(it))
        }
        assertNull(JudgeOptions.truthValue("见解析"))
    }

    @Test
    fun `已有的对错选项沿用不重建`() {
        val existing = listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
        val (opts, ans) = JudgeOptions.normalize(QuestionType.JUDGE, existing, "错")
        assertEquals(existing, opts)
        assertEquals("B", ans)
    }

    @Test
    fun `只有一个错误选项时不要错配成 A`() {
        val (opts, ans) = JudgeOptions.normalize(
            QuestionType.JUDGE, listOf(QuestionOption("A", "错误")), null
        )
        assertEquals(2, opts.size)
        assertEquals("B", ans)
    }

    @Test
    fun `非判断题原样返回`() {
        val existing = listOf(QuestionOption("A", "甲"), QuestionOption("B", "乙"))
        val (opts, ans) = JudgeOptions.normalize(QuestionType.SINGLE, existing, "A")
        assertEquals(existing, opts)
        assertEquals("A", ans)
    }

    @Test
    fun `答案认不出时不去瞎猜`() {
        val (opts, ans) = JudgeOptions.normalize(QuestionType.JUDGE, emptyList(), "见教材第 12 页")
        assertEquals(2, opts.size)
        assertEquals("见教材第 12 页", ans)
    }

    @Test
    fun `渲染层把勾叉换成文字`() {
        val opts = listOf(QuestionOption("A", "√"), QuestionOption("B", "×"))
        val shown = JudgeOptions.displayFor(QuestionType.JUDGE, opts)
        assertEquals("正确", shown[0].text)
        assertEquals("错误", shown[1].text)
    }

    @Test
    fun `判断题补出的选项一定能被渲染成两行`() {
        val (opts, _) = JudgeOptions.normalize(QuestionType.JUDGE, emptyList(), null)
        assertTrue("必须恰好两行", opts.size == 2)
        opts.forEach { assertNotNull(it.key); assertTrue(it.text.isNotBlank()) }
    }
}
