package com.examhelper.shua

import com.examhelper.shua.data.parse.LlmQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块尺寸与输出预算的**实测约束**。
 *
 * 这几个数字不是拍的，是在 `.shots/app_raw13.txt`（47 页扫描件 OCR 全文，
 * 45946 字 / 8 章）上对 DeepSeek 官方端点逐块跑出来的：
 *
 * | chunk | 块数 | 撞线 | 输出峰值 | 总输入 | 题数 | 耗时 |
 * |---|---|---|---|---|---|---|
 * | **6000** | 8 | **0/8** | 8471 | **32786** | **261** | 131.2s |
 * | 12000 | 4 | **2/4** | 12000 打满 | 30352 | 117 | 135.5s |
 *
 * chunk=12000 时有两块 `finish=length`、**返回 0 题**，总题数掉 55%，
 * 耗时反而略升。所以**不能放大 chunk** —— 省下的 2434 输入 token
 * 远不值得丢掉一半的题。
 *
 * 要让 12000 可用，`PARSE_MAX_TOKENS` 得同步抬到 24000+；但单块越大，
 * 一次撞线的损失就越大（一整块全丢），而 6000 块撞线时只丢半块。
 */
class ChunkBudgetTest {

    @Test
    fun `分块保持 6000 不放大`() {
        assertEquals(
            "实测 chunk=12000 有 50% 撞线率、题数掉 55%，不能放大",
            6000,
            LlmQuestionParser.CHUNK_SIZE
        )
    }

    @Test
    fun `输出预算必须覆盖 6000 块的实测峰值`() {
        // 6000 字块的实测输出峰值 8471（题量最大的第六章），取 12000 有 42% 余量
        assertTrue(
            "PARSE_MAX_TOKENS 必须大于实测峰值 8471",
            LlmQuestionParser.PARSE_MAX_TOKENS > 8471
        )
    }

    @Test
    fun `输出预算与分块必须同步调整`() {
        // 护栏：如果哪天有人放大 chunk，PARSE_MAX_TOKENS 也得跟着涨，
        // 否则必然重现「整块 finish=length、0 题」的失败。
        // 经验系数：实测每 1 字原文约需 1.4 token 输出（8471 / 5996）。
        val need = (LlmQuestionParser.CHUNK_SIZE * 1.4).toInt()
        assertTrue(
            "chunk=" + LlmQuestionParser.CHUNK_SIZE + " 至少需要 $need 输出预算，" +
                "当前只有 " + LlmQuestionParser.PARSE_MAX_TOKENS,
            LlmQuestionParser.PARSE_MAX_TOKENS >= need
        )
    }
}
