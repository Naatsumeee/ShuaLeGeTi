package com.examhelper.shua

import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.generate.AnswerGenerator
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGeneratorTest {

    @Test
    fun `array answer from model is accepted`() {
        val dto = Json.decodeFromString<AnswerGenerator.AnswerDto>(
            """{"i":1,"answer":["i-1","i-1","1"],"analysis":"按空作答"}"""
        )
        assertEquals("i-1|i-1|1", AnswerGenerator.answerText(dto.answer))
    }

    @Test
    fun `multiple choice array becomes contiguous letters`() {
        val dto = Json.decodeFromString<AnswerGenerator.AnswerDto>(
            """{"i":1,"answer":["C","A"],"analysis":"多选"}"""
        )
        val question = Question(
            bankId = 1, orderInBank = 0, type = QuestionType.MULTIPLE, stem = "多选"
        )
        assertEquals("AC", AnswerGenerator.answerText(dto.answer, question))
    }

    @Test
    fun `b1 and b2 are distinguished in batch prompt`() {
        val shared = JsonCodec.encodeOptions(
            listOf(QuestionOption("A", "甲"), QuestionOption("B", "乙"))
        )
        val questions = listOf(
            Question(
                bankId = 1, orderInBank = 0, type = QuestionType.SINGLE,
                subtype = "B1", stem = "B1 题干", sharedOptionsJson = shared
            ),
            Question(
                bankId = 1, orderInBank = 1, type = QuestionType.MULTIPLE,
                subtype = "B2", stem = "B2 题干", sharedOptionsJson = shared
            )
        )
        val prompt = AnswerGenerator.buildBatchPrompt(questions)
        assertTrue(prompt.contains("B1型题"))
        assertTrue(prompt.contains("B2型题"))
    }
}
