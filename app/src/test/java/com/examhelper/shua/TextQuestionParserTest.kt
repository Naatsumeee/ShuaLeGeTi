package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextQuestionParserTest {

    private val sample = """
        一、单项选择题
        1、医学伦理学的基本原则不包括（ ）
        A、自主原则
        B、不伤害原则
        C、有利原则
        D、平等原则 √
        2、下列说法正确的是：B
        A、错误说法
        B、正确说法
        3、下列哪项属于医生的权利？
        A、诊治权
        B、干涉权
        答案：AB
        解析：本题考查医生权利。
        4、医学伦理学的一词最早出现在对
        5、填空题：希波克拉底誓言出自___
        答案：古希腊
        6、简述尊重原则的内容。
        答案：尊重病人的自主权、知情同意权等。
    """.trimIndent()

    @Test
    fun parseBasicFormats() {
        val questions = TextQuestionParser.parse(sample)
        assertEquals(6, questions.size)

        val q1 = questions[0]
        assertEquals(QuestionType.SINGLE, q1.type)
        assertEquals("D", q1.answer)
        assertEquals(4, q1.options.size)
        assertEquals("平等原则", q1.options[3].text) // √ 已剥离
        assertEquals("单项选择题", q1.chapter?.takeLast(5)?.let { q1.chapter })

        val q2 = questions[1]
        assertEquals("B", q2.answer)
        assertEquals(QuestionType.SINGLE, q2.type)

        val q3 = questions[2]
        assertEquals(QuestionType.MULTIPLE, q3.type)
        assertEquals("AB", q3.answer)
        assertNotNull(q3.analysis)

        val q5 = questions[4]
        assertEquals(QuestionType.FILL, q5.type)
        assertEquals("古希腊", q5.answer)

        val q6 = questions[5]
        assertEquals(QuestionType.SHORT_ANSWER, q6.type)
    }

    @Test
    fun judgeOptions() {
        val judge = """
            1、医德是一种职业道德。答案：对
        """.trimIndent()
        val q = TextQuestionParser.parse(judge).first()
        assertEquals(QuestionType.JUDGE, q.type)
        assertEquals("对", q.answer)
    }

    @Test
    fun decimalNumbersNotQuestions() {
        val text = "给药剂量为1.5毫升，每日2次。"
        assertEquals(0, TextQuestionParser.parse(text).size)
    }
}
