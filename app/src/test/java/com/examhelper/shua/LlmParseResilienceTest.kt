package com.examhelper.shua

import com.examhelper.shua.data.parse.LlmQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首批次 AI 解析失败（m12473 bug1）的回归测试。
 *
 * 根因：`parseChunk` 调用 LLM 时没传 max_tokens，用服务端默认（4096），
 * 6000 字分块转成的 JSON 常被从中间截断 → 整体反序列化失败 → 整块题目消失。
 */
class LlmParseResilienceTest {

    private val parser = LlmQuestionParser()

    @Test
    fun parsesNormalJsonObject() {
        val json = """
            {"questions":[
              {"stem":"第一题","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":"A"},
              {"stem":"第二题","options":[{"key":"A","text":"甲"}],"answer":"B"}
            ]}
        """.trimIndent()
        val got = parser.recoverQuestions(json)
        assertEquals(2, got.size)
        assertEquals("第一题", got[0].stem)
        assertEquals("A", got[0].answer)
    }

    /** 截断在第二个对象中间：第一个完好，必须救回来而不是整块丢弃 */
    @Test
    fun recoversTruncatedJsonTail() {
        val json = """
            {"questions":[
              {"stem":"第一题","options":[{"key":"A","text":"甲"}],"answer":"A"},
              {"stem":"第二题","options":[{"key":"A","text":"甲"}],"answer":"B"},
              {"stem":"第三题","options":[{"key":"A","text":"甲"}],"ans
        """.trimIndent()
        val got = parser.recoverQuestions(json)
        assertEquals(2, got.size)
        assertEquals(listOf("第一题", "第二题"), got.map { it.stem })
    }

    /** 花括号出现在字符串内容里（题干含 { }）时不能被当成对象边界 */
    @Test
    fun bracesInsideStringsDoNotBreakScan() {
        val json = """
            {"questions":[
              {"stem":"设集合 A={1,2}，则 { } 表示","options":[{"key":"A","text":"空集"}],"answer":"A"},
              {"stem":"第二题","options":[],"answer":"B"}
            ]}
        """.trimIndent()
        val got = parser.recoverQuestions(json)
        assertEquals(2, got.size)
        assertTrue(got[0].stem.contains("{1,2}"))
    }

    /** 转义引号不能被当成字符串结束 */
    @Test
    fun escapedQuoteInsideStringIsHandled() {
        val json = """{"questions":[{"stem":"他说\"你好\"","options":[],"answer":"A"}]}"""
        val got = parser.recoverQuestions(json)
        assertEquals(1, got.size)
        assertTrue(got[0].stem.contains("你好"))
    }

    @Test
    fun emptyOrGarbageYieldsNothing() {
        assertTrue(parser.recoverQuestions("").isEmpty())
        assertTrue(parser.recoverQuestions("抱歉我无法完成").isEmpty())
        assertTrue(parser.recoverQuestions("{\"questions\":[]}").isEmpty())
    }

    /** 单块输出预算必须足够一个 6000 字分块转成 JSON，不能再退回服务端默认值 */
    @Test
    fun parseBudgetCoversFullChunk() {
        assertTrue(
            "PARSE_MAX_TOKENS 必须明显大于分块字符数对应的输出量",
            LlmQuestionParser.PARSE_MAX_TOKENS >= 8000
        )
    }
}
