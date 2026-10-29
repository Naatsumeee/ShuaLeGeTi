package com.examhelper.shua

import com.examhelper.shua.data.generate.Cost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 成本估算的单测。时段判断以前用的是设备默认时区 + 「00:30–08:30」这条根本不存在的规则，
 * 这里把官方规则钉死：北京时间周一至周五（法定节假日除外）9:00–12:00、14:00–18:00 为高峰，
 * 其余（含周末、法定节假日全天）为空闲且半价。
 */
class CostTest {

    private val bj: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millisAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(bj).toInstant().toEpochMilli()

    /** 固定一个设备时区，证明结果与设备时区无关 */
    private fun <T> inUtc(block: () -> T): T {
        val old = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            return block()
        } finally {
            java.util.TimeZone.setDefault(old)
        }
    }

    @Test
    fun peakWindowsOnOrdinaryWorkday() {
        // 2026-10-28 是周三，非节假日
        assertTrue(Cost.isPeak(millisAt(2026, 10, 28, 9, 0)))
        assertTrue(Cost.isPeak(millisAt(2026, 10, 28, 11, 59)))
        assertFalse("12:00 整点已出高峰", Cost.isPeak(millisAt(2026, 10, 28, 12, 0)))
        assertFalse("午休 12–14 为空闲", Cost.isPeak(millisAt(2026, 10, 28, 13, 0)))
        assertTrue(Cost.isPeak(millisAt(2026, 10, 28, 14, 0)))
        assertFalse("18:00 整点已出高峰", Cost.isPeak(millisAt(2026, 10, 28, 18, 0)))
        assertFalse("凌晨空闲", Cost.isPeak(millisAt(2026, 10, 28, 2, 0)))
    }

    @Test
    fun weekendIsOffPeakAllDay() {
        // 2026-10-31 周六、2026-11-01 周日
        assertFalse(Cost.isPeak(millisAt(2026, 10, 31, 10, 0)))
        assertFalse(Cost.isPeak(millisAt(2026, 11, 1, 15, 0)))
    }

    @Test
    fun statutoryHolidayWeekdayIsOffPeak() {
        // 2026 国庆 10/1–10/7，其中 10/5 是周一 —— 必须算空闲
        assertFalse("国庆中的周一仍为空闲时段", Cost.isPeak(millisAt(2026, 10, 5, 10, 0)))
        // 2026 春节 2/15–2/23，其中 2/17 是周二
        assertFalse(Cost.isPeak(millisAt(2026, 2, 17, 15, 30)))
    }

    @Test
    fun makeupWorkSaturdayStillOffPeakPerOfficialWording() {
        // 官方措辞只看「周一至周五 + 法定节假日」，2026-10-10（周六补班）不在节日表里，
        // 按规则仍是空闲。这里固化该理解：若 DeepSeek 后续改口径，本测试会提醒复审。
        assertFalse(Cost.isPeak(millisAt(2026, 10, 10, 10, 0)))
    }

    @Test
    fun resultDoesNotDependOnDeviceTimeZone() {
        val t = millisAt(2026, 10, 28, 10, 0)   // 北京时间周三上午 → 高峰
        assertTrue(inUtc { Cost.isPeak(t) })
    }

    @Test
    fun flashPriceMatchesOfficialTable() {
        val peak = millisAt(2026, 10, 28, 10, 0)
        val off = millisAt(2026, 10, 31, 10, 0)
        // 1M 命中 + 1M 未命中 + 1M 输出，flash 高峰 = 0.04 + 2 + 8 = 10.04
        assertEquals(10.04, Cost.yuan(1_000_000, 1_000_000, 1_000_000, "deepseek-flash", peak)!!, 1e-9)
        // 空闲半价
        assertEquals(5.02, Cost.yuan(1_000_000, 1_000_000, 1_000_000, "deepseek-flash", off)!!, 1e-9)
    }

    @Test
    fun proPriceMatchesOfficialTable() {
        val peak = millisAt(2026, 10, 28, 10, 0)
        // v4-pro 高峰 = 0.30 + 9 + 27 = 36.30
        assertEquals(36.30, Cost.yuan(1_000_000, 1_000_000, 1_000_000, "deepseek-v4-pro", peak)!!, 1e-9)
    }

    @Test
    fun legacyFlashNameBilledAsFlash() {
        val peak = millisAt(2026, 10, 28, 10, 0)
        assertEquals(
            Cost.yuan(500_000, 500_000, 500_000, "deepseek-flash", peak),
            Cost.yuan(500_000, 500_000, 500_000, "deepseek-v4-flash", peak)
        )
    }

    @Test
    fun unknownModelIsNotPriced() {
        assertNull(Cost.yuan(1_000_000, 1_000_000, 1_000_000, "glm-4-plus"))
        assertNull(Cost.yuan(1_000_000, 1_000_000, 1_000_000, ""))
        assertTrue(Cost.costNote("kimi-k2").contains("不计价"))
    }

    @Test
    fun hitRateAndFormatting() {
        assertEquals(0, Cost.hitRate(0, 0))
        assertEquals(25, Cost.hitRate(250, 750))
        assertEquals("0.00", Cost.format(0.0))
        assertEquals("0.0020", Cost.format(0.002))
        assertEquals("1.23", Cost.format(1.234))
        assertEquals("1.00 M", Cost.tokens(1_000_000))
        assertEquals("1.5 K", Cost.tokens(1500))
        assertEquals("999", Cost.tokens(999))
    }
}
