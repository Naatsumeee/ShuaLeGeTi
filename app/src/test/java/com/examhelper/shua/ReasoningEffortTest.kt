package com.examhelper.shua

import com.examhelper.shua.data.llm.ChatCompletionRequest
import com.examhelper.shua.data.llm.ChatMessage
import com.examhelper.shua.data.llm.ReasoningEffort
import com.examhelper.shua.data.parse.LlmQuestionParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度参数的回归护栏。
 *
 * 背景（实测，不是推测）：DeepSeek 的 `reasoning_effort` 缺省即 `high`，
 * 一次 5613 字的分块用 8192 输出预算时，**思考把预算全额吃光、一道题都没解析出来**。
 * 关掉思考后同样输入只需 6132 token 就 `finish=stop`、出 46 题。
 *
 * 所以这里钉死三件事：
 * 1. 导入解析固定发 `none`（不受设置影响）；
 * 2. 请求体序列化出来的字段名必须是 `reasoning_effort`，值必须是官方四档之一；
 * 3. 不传时字段**不能出现**（序列化省略），否则不认这个字段的端点会 400。
 */
class ReasoningEffortTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `四档的接口值符合官方口径`() {
        // 官方文档：Possible values: none / low / high / max（medium 映射为 high，故不提供）
        assertEquals("none", ReasoningEffort.NONE.apiValue)
        assertEquals("low", ReasoningEffort.LOW.apiValue)
        assertEquals("high", ReasoningEffort.HIGH.apiValue)
        assertEquals("max", ReasoningEffort.MAX.apiValue)
        assertEquals(4, ReasoningEffort.entries.size)
    }

    @Test
    fun `不存在 medium 档位`() {
        // 给用户一个「看着更低、实际等同 high」的档比不给更糟
        assertTrue(ReasoningEffort.entries.none { it.apiValue == "medium" })
        assertTrue(ReasoningEffort.entries.none { it.label.equals("Medium", true) })
    }

    @Test
    fun `设置值解析容错`() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromName("high"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromName("HIGH"))
        assertEquals(ReasoningEffort.NONE, ReasoningEffort.fromName("none"))
        // 认不出的（空、脏数据、旧版本残留）一律回落到安全默认 Low
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.fromName(null))
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.fromName(""))
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.fromName("turbo"))
    }

    @Test
    fun `请求体字段名与取值正确`() {
        val req = ChatCompletionRequest(
            model = "deepseek-flash",
            messages = listOf(ChatMessage("user", "hi")),
            maxTokens = 12000,
            reasoningEffort = ReasoningEffort.NONE.apiValue
        )
        val text = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertTrue("必须带 reasoning_effort", text.contains("\"reasoning_effort\":\"none\""))
        assertTrue("必须带 max_tokens", text.contains("\"max_tokens\":12000"))
        // 不能混进别的拼写
        assertFalse(text.contains("reasoningEffort"))
        assertFalse(text.contains("thinking"))
    }

    @Test
    fun `不传强度时字段必须省略`() {
        val req = ChatCompletionRequest(
            model = "deepseek-flash",
            messages = listOf(ChatMessage("user", "hi")),
            reasoningEffort = null
        )
        val text = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertFalse("null 不能序列化出该字段：不认它的端点会 400", text.contains("reasoning_effort"))
    }

    @Test
    fun `导入解析固定关闭思考`() {
        assertEquals(ReasoningEffort.NONE, LlmQuestionParser.PARSE_REASONING)
    }

    @Test
    fun `解析输出预算按实测留足余量`() {
        // 5613 字 / 6132 token、8667 字 / 9747、9759 字 / 9827 —— 取 12000 有冗余
        assertTrue(
            "PARSE_MAX_TOKENS 必须大于实测的 9827",
            LlmQuestionParser.PARSE_MAX_TOKENS >= 10000
        )
        assertEquals(12000, LlmQuestionParser.PARSE_MAX_TOKENS)
    }
}
