package com.examhelper.shua

import com.examhelper.shua.data.generate.Cost
import com.examhelper.shua.data.llm.UsageStats
import com.examhelper.shua.data.db.AiUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用量情况」与缓存命中率的回归护栏。
 *
 * 背景（实测，不是推测）：
 * - DeepSeek **没有**用量/账单接口（`/user/usage`、`/user/bills`、`/billing` 等全 404），
 *   所以「近 N 天消费」只能靠余额变化反推；
 * - 任务级「实扣」恒为 0：计费有几分钟延迟，实测某任务前后余额都是 61.29，
 *   但实际消耗了 7431 输入 + 8997 输出 token；
 * - `hitRate` 在「没有数据」时也返回 0，用户看到「缓存命中 0%」会以为统计坏了。
 */
class UsageStatsTest {

    // ---------- 余额下降累计 ----------

    @Test
    fun `只累加下降量`() {
        // 100 → 98 → 95：消费 2 + 3 = 5
        assertEquals(5.0, UsageStats.sumDecreases(listOf(100.0, 98.0, 95.0)), 1e-9)
    }

    @Test
    fun `充值回升不算负消费`() {
        // 100 → 80（消费 20）→ 200（充值 120，跳过）→ 190（消费 10）
        // 正确结果 30；若把回升算成负数会得到 -90
        assertEquals(30.0, UsageStats.sumDecreases(listOf(100.0, 80.0, 200.0, 190.0)), 1e-9)
    }

    @Test
    fun `持平记为 0`() {
        assertEquals(0.0, UsageStats.sumDecreases(listOf(61.29, 61.29)), 1e-9)
        // 说明：任务级「实扣」失效就是这个场景 —— 计费有延迟，前后读数相等
    }

    @Test
    fun `样本不足返回 0`() {
        assertEquals(0.0, UsageStats.sumDecreases(emptyList()), 1e-9)
        assertEquals(0.0, UsageStats.sumDecreases(listOf(100.0)), 1e-9)
    }

    @Test
    fun `只有一条快照时实际消费必须是 null 而不是 0`() {
        // 实测踩到：只有一条快照时 sumDecreases 返回 0.0，界面显示「实际消费 0.00」，
        // 和「确实没消费」完全分不清 —— 和无数据时显示「缓存命中 0%」是同型误导。
        // compute() 现在用 series.size < 2 判空，这里锁住这条边界。
        val w = UsageStats.Window(days = 7, actualSpent = null, estimateSpent = 0.05)
        assertFalse("只有估算、没有实际时不应判定为 hasActual", w.hasActual)
        assertTrue("仍应显示估算", w.hasEstimate)
        assertFalse("有估算就不算空窗口", w.isEmpty)
    }

    @Test
    fun `多段消费与充值混合`() {
        val s = listOf(500.0, 480.0, 470.0, 600.0, 580.0, 560.0)
        // 20 + 10 + (充值跳过) + 20 + 20 = 70
        assertEquals(70.0, UsageStats.sumDecreases(s), 1e-9)
    }

    // ---------- 命中率：区分「无数据」和「0%」 ----------

    @Test
    fun `没有数据时命中率是 null 而不是 0`() {
        // 旧行为：hitRate(0,0) = 0 → 界面显示「缓存命中 0%」，看着像统计坏了
        assertNull(Cost.hitRateOrNull(0, 0))
        assertEquals(0, Cost.hitRate(0, 0))
    }

    @Test
    fun `有数据时正常计算`() {
        // 实测 DeepSeek：第 2 次请求 in=962 hit=768 miss=194 → 79%
        assertEquals(79, Cost.hitRateOrNull(768, 194))
        assertEquals(0, Cost.hitRateOrNull(0, 962))
        assertEquals(100, Cost.hitRateOrNull(100, 0))
    }

    @Test
    fun `首次请求必然 0 命中`() {
        // 前缀缓存要等第一次请求**完成**才建立，所以冷启动那一批必然全 miss。
        // 这不算 bug，UI 要解释而不是报错。
        val hit = 0L
        val miss = 962L
        assertEquals(0, Cost.hitRateOrNull(hit, miss))
    }

    // ---------- 窗口 ----------

    @Test
    fun `窗口只有 7 天和 30 天`() {
        assertEquals(listOf(7, 30), UsageStats.WINDOWS)
    }

    @Test
    fun `空窗口不显示任何数字`() {
        val w = UsageStats.Window(days = 7, actualSpent = null, estimateSpent = null)
        assertTrue(w.isEmpty)
        assertFalse(w.hasActual)
        assertFalse(w.hasEstimate)
        assertEquals(0L, w.totalTokens)
    }

    @Test
    fun `有数据时能拆出 token 构成`() {
        val w = UsageStats.Window(
            days = 30, actualSpent = 1.23, estimateSpent = 1.50,
            hitTokens = 8000, missTokens = 2000, outTokens = 10000, taskCount = 3
        )
        assertFalse(w.isEmpty)
        assertEquals(20000L, w.totalTokens)
        assertEquals(80, Cost.hitRateOrNull(w.hitTokens, w.missTokens))
    }

    @Test
    fun `本地估算汇总所有 AI 类别并按各自模型计价`() {
        val calls = listOf(
            AiUsageRecord(
                id = 1, callId = "c1", taskId = "task-import", providerId = 1,
                providerName = "主部", model = "deepseek-flash", category = "IMPORT_PARSE",
                cacheHitTokens = 10_000, cacheMissTokens = 20_000, outputTokens = 5_000,
                createdAt = 1L
            ),
            AiUsageRecord(
                id = 2, callId = "c2", taskId = "task-ask", providerId = 1,
                providerName = "主部", model = "deepseek-v4-pro", category = "ASK_AI",
                cacheHitTokens = 1_000, cacheMissTokens = 2_000, outputTokens = 500,
                createdAt = 2L
            ),
            AiUsageRecord(
                id = 3, callId = "c3", taskId = "task-ask", providerId = 2,
                providerName = "其他部", model = "deepseek-flash", category = "DEEP_SOLVE",
                cacheHitTokens = 0, cacheMissTokens = 500, outputTokens = 200,
                createdAt = 3L
            )
        )

        val estimate = UsageStats.estimate(calls, fallbackModel = null)

        assertEquals(2, estimate.taskCount)
        assertEquals(11_000L, estimate.hitTokens)
        assertEquals(22_500L, estimate.missTokens)
        assertEquals(5_700L, estimate.outTokens)
        assertTrue((estimate.spent ?: 0.0) > 0.0)
    }
}
