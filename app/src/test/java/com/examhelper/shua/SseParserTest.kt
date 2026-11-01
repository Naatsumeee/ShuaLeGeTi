package com.examhelper.shua

import com.examhelper.shua.data.llm.SseParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式（SSE）解析的回归测试。
 *
 * 这些用例锁住的是「兼容性契约」：不同厂商/中转的 SSE 帧格式差异很大，
 * 一旦解析退回只认某一种写法，用户侧就会表现为「流式没反应、最后一次性蹦出来」
 * 或者「回答只剩最后一段」——这类问题在真机上极难复现，只能靠单测兜住。
 */
class SseParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun plainContentFrame() {
        val f = SseParser.parse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
            json
        )
        assertEquals("你好", f?.content)
        assertNull(f?.reasoning)
        assertNull(f?.usage)
    }

    @Test
    fun reasoningFrameIsSeparatedFromContent() {
        // 推理型模型先吐 thinking：绝不能和正文拼在一起，否则界面上思考过程会混进答案
        val f = SseParser.parse(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先看题意\"}}]}",
            json
        )
        assertEquals("先看题意", f?.reasoning)
        assertNull(f?.content)
    }

    @Test
    fun doneSentinel() {
        assertTrue(SseParser.parse("data: [DONE]", json)?.done == true)
    }

    @Test
    fun heartbeatAndBlankLinesAreIgnored() {
        assertNull(SseParser.parse("", json))
        assertNull(SseParser.parse(": keep-alive", json))
        assertNull(SseParser.parse("event: message", json))
    }

    @Test
    fun noSpaceAfterColonIsAccepted() {
        // 有些中转不写「data: 」里的空格
        val f = SseParser.parse("data:{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}", json)
        assertEquals("A", f?.content)
    }

    @Test
    fun usageOnlyFrameKeepsUsageAndNoContent() {
        val f = SseParser.parse(
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100," +
                "\"completion_tokens\":20,\"prompt_cache_hit_tokens\":64}}",
            json
        )
        assertNull(f?.content)
        assertEquals(100, f?.usage?.promptTokens)
        assertEquals(64, f?.usage?.normalizedCacheHit())
    }

    @Test
    fun finishReasonLengthIsVisible() {
        // 截断必须能被上层识别，否则半截解析会被当成完整结果写库
        val f = SseParser.parse(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
            json
        )
        assertEquals("length", f?.finishReason)
    }

    @Test
    fun errorFrameSurfacesMessage() {
        val f = SseParser.parse(
            "data: {\"error\":{\"message\":\"Insufficient Balance\"}}",
            json
        )
        assertEquals("Insufficient Balance", f?.error)
    }

    @Test
    fun malformedFrameDoesNotThrow() {
        // 半包 / 非 JSON 的垃圾行只能跳过，不能把整个流打断
        assertNull(SseParser.parse("data: {not json", json))
    }
}
