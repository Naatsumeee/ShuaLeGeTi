package com.examhelper.shua

import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.data.parse.OptionNormalizer
import com.examhelper.shua.data.repo.StudyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeAndNormalizeTest {

    // ---------- OptionNormalizer ----------

    @Test
    fun `duplicate option letters are merged`() {
        val opts = listOf(
            QuestionOption("A", "自主原则"),
            QuestionOption("B", "不伤害"),
            QuestionOption("B", "原则"),   // OCR 拆行重复
            QuestionOption("B", "不伤害"), // 完全重复
            QuestionOption("C", "有利原则")
        )
        val (normalized, _) = OptionNormalizer.normalize(opts, null)
        assertEquals(listOf("A", "B", "C"), normalized.map { it.key })
        assertEquals("不伤害原则", normalized[1].text)
    }

    @Test
    fun `letters re-sequenced with answer remap`() {
        val opts = listOf(
            QuestionOption("A", "选项一"),
            QuestionOption("C", "选项二"),
            QuestionOption("D", "选项三")
        )
        val (normalized, answer) = OptionNormalizer.normalize(opts, "D")
        assertEquals(listOf("A", "B", "C"), normalized.map { it.key })
        assertEquals("C", answer) // D → C
    }

    // ---------- 判分：内容兜底 ----------

    private fun question(
        type: QuestionType = QuestionType.SINGLE,
        answer: String?,
        options: List<QuestionOption>
    ) = Question(
        bankId = 1, orderInBank = 0, type = type, stem = "题干",
        optionsJson = JsonCodec.encodeOptions(options), answer = answer
    )

    @Test
    fun `letter match works`() {
        val q = question(
            answer = "B",
            options = listOf(QuestionOption("A", "甲"), QuestionOption("B", "乙"))
        )
        assertTrue(StudyRepository.judgeAnswer(q, "B"))
        assertFalse(StudyRepository.judgeAnswer(q, "A"))
    }

    @Test
    fun `text answer resolved to option letter`() {
        val q = question(
            answer = "不伤害原则",
            options = listOf(
                QuestionOption("A", "自主原则"),
                QuestionOption("B", "不伤害原则")
            )
        )
        assertTrue(StudyRepository.judgeAnswer(q, "B"))
    }

    @Test
    fun `letter plus content answer resolved`() {
        val q = question(
            answer = "B. 不伤害原则",
            options = listOf(
                QuestionOption("A", "自主原则"),
                QuestionOption("B", "不伤害原则")
            )
        )
        assertTrue(StudyRepository.judgeAnswer(q, "B"))
    }

    @Test
    fun `misaligned letters judged by content`() {
        // OCR 重排后：答案字母 B 指向的内容实际在 C 上（内容相同）
        val q = question(
            answer = "B",
            options = listOf(
                QuestionOption("A", "甲"),
                QuestionOption("B", "正确说法"),
                QuestionOption("C", "正确说法")
            )
        )
        assertTrue(StudyRepository.judgeAnswer(q, "C"))
        assertFalse(StudyRepository.judgeAnswer(q, "A"))
    }

    @Test
    fun `judge question accepts option letter`() {
        val q = question(
            type = QuestionType.JUDGE,
            answer = "对",
            options = listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
        )
        assertTrue(StudyRepository.judgeAnswer(q, "A"))
        assertTrue(StudyRepository.judgeAnswer(q, "对"))
        assertFalse(StudyRepository.judgeAnswer(q, "B"))
    }

    @Test
    fun `fill answer normalized text compare`() {
        val q = question(type = QuestionType.FILL, answer = "古希腊", options = emptyList())
        assertTrue(StudyRepository.judgeAnswer(q, "古 希腊。"))
        assertFalse(StudyRepository.judgeAnswer(q, "古罗马"))
    }

    @Test
    fun `multiple answer order insensitive`() {
        val q = question(
            type = QuestionType.MULTIPLE,
            answer = "ABD",
            options = listOf(
                QuestionOption("A", "甲"), QuestionOption("B", "乙"),
                QuestionOption("C", "丙"), QuestionOption("D", "丁")
            )
        )
        assertTrue(StudyRepository.judgeAnswer(q, "DAB"))
        assertFalse(StudyRepository.judgeAnswer(q, "AB"))
    }
}
