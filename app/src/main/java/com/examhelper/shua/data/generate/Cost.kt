package com.examhelper.shua.data.generate

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DeepSeek 官方分时峰谷定价 → 人民币成本估算。
 *
 * 官方价目（元 / 百万 tokens，取自 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
 * 校准日期 2026-10-29）。**空闲时段 = 高峰时段的一半**，所以只存高峰价，空闲价现场除以 2。
 *
 * | 项目          | flash 高峰 | v4-pro 高峰 |
 * |---------------|-----------|------------|
 * | 输入·缓存命中 | 0.04      | 0.30       |
 * | 输入·未命中   | 2.0       | 9.0        |
 * | 输出          | 8.0       | 27.0       |
 *
 * 时段规则（官方原文）：北京时间**周一至周五（不含中国法定节假日）9:00–12:00、14:00–18:00
 * 为高峰时段**；其余时段，**包括周末及中国法定节假日全天**均为空闲时段。
 *
 * 两个易错点（旧实现都踩了）：
 * 1. 时段判得用 `Calendar.getInstance()` 取设备默认时区。机器改过时区、或者人在境外，
 *    算出来的峰谷就是错的 —— 这里固定 Asia/Shanghai，因为 DeepSeek 按北京时间结算。
 * 2. 「调休上班的周六」按官方措辞**仍是空闲时段**（规则只看「周一至周五」+「法定节假日」），
 *    所以只要节日表，不需要上班补班表。
 *
 * 中转/第三方端点可能另有加价，UI 上标注的是「参考成本」。
 */
object Cost {

    /** 一挡模型的**高峰**单价（元 / 百万 tokens）；空闲单价 = 各字段 / 2 */
    data class PeakPrice(val hit: Double, val miss: Double, val out: Double)

    private val FLASH = PeakPrice(0.04, 2.0, 8.0)
    private val PRO = PeakPrice(0.30, 9.0, 27.0)

    /**
     * 按模型名挑价目。兼容官方下线的旧名 `deepseek-v4-flash` / `deepseek-v4-flash-vision-exp`
     * （官方明确「请求由 Flash 提供服务并按 Flash 价格计费」）。
     * 认不出的模型（GLM / Kimi / Qwen / Ollama…）返回 null：宁可不显示成本，
     * 也不要拿 DeepSeek 的价去冒充别家的账单。
     */
    fun priceFor(model: String?): PeakPrice? = when {
        model == null -> null
        model.isBlank() -> null
        model.contains("v4-pro") || model.contains("pro-0813") -> PRO
        model.contains("flash") || model.contains("deepseek") -> FLASH
        else -> null
    }

    /** 固定北京时区：DeepSeek 的分时计价按北京时间，与设备时区无关 */
    private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 中国法定节假日（全天空闲）。只到 2026 年 —— 国务院一般在前一年 11 月才公布次年安排，
     * 超出覆盖范围的年份退化成「只看星期」，即周六日仍算空闲、工作日一律按高峰，
     * 误差最多是节假日内的高估，可接受。
     * 依据：国办发明电〔2024〕12 号（2025）、国办发明电〔2025〕7 号（2026）。
     */
    private val HOLIDAYS: Set<LocalDate> = buildSet {
        // ---- 2025 ----
        addAll(dates(2025, 1, 1, 1))            // 元旦
        addAll(dates(2025, 1, 28, 8))           // 春节
        addAll(dates(2025, 4, 4, 3))            // 清明
        addAll(dates(2025, 5, 1, 5))            // 劳动节
        addAll(dates(2025, 5, 31, 3))           // 端午
        addAll(dates(2025, 10, 1, 8))           // 中秋 + 国庆
        // ---- 2026 ----
        addAll(dates(2026, 1, 1, 3))            // 元旦（1/4 周日补班，仍算空闲）
        addAll(dates(2026, 2, 15, 9))           // 春节
        addAll(dates(2026, 4, 4, 3))            // 清明
        addAll(dates(2026, 5, 1, 5))            // 劳动节
        addAll(dates(2026, 6, 19, 3))           // 端午
        addAll(dates(2026, 9, 25, 3))           // 中秋
        addAll(dates(2026, 10, 1, 7))           // 国庆
    }

    private fun dates(year: Int, month: Int, day: Int, count: Int): List<LocalDate> =
        (0 until count).map { LocalDate.of(year, month, day).plusDays(it.toLong()) }

    /**
     * 该时刻是否处于**高峰**时段：北京时间周一至周五（法定节假日除外）
     * 9:00–12:00、14:00–18:00。边界按左闭右开：9:00:00 起算高峰，12:00:00 整点结束。
     */
    fun isPeak(millis: Long = System.currentTimeMillis()): Boolean {
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), BEIJING)
        val dow = z.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
        if (z.toLocalDate() in HOLIDAYS) return false
        val minutes = z.hour * 60 + z.minute
        return minutes in (9 * 60) until (12 * 60) || minutes in (14 * 60) until (18 * 60)
    }

    /** 该时刻是否处于空闲（优惠）时段 */
    fun isOffPeak(millis: Long = System.currentTimeMillis()): Boolean = !isPeak(millis)

    /**
     * 估算成本（元）。峰谷按[millis]时刻判断 —— 生成过程可能跨时段，
     * 但用户看的是「这批活大概花了多少」，按结束时刻结算足够。
     * 认不出模型时返回 null（调用方直接不显示成本行）。
     */
    fun yuan(
        hitTokens: Long,
        missTokens: Long,
        outputTokens: Long,
        model: String? = null,
        millis: Long = System.currentTimeMillis()
    ): Double? {
        val p = priceFor(model ?: "deepseek-flash") ?: return null
        val factor = if (isPeak(millis)) 1.0 else 0.5
        return (hitTokens * p.hit + missTokens * p.miss + outputTokens * p.out) * factor / 1_000_000.0
    }

    /**
     * 成本行下面那一行完整说明：价目挡位 + 时段规则。
     * 认不出模型时只说明不计价，不要拿 DeepSeek 的时段规则去解释别家账单。
     */
    fun costNote(model: String?, millis: Long = System.currentTimeMillis()): String {
        val p = priceFor(model) ?: return "非 DeepSeek 模型 · 仅统计用量不计价"
        val which = if (p === PRO) "v4-pro" else "flash"
        val peak = isPeak(millis)
        return "DeepSeek $which · " + if (peak) {
            "当前高峰价；空闲时段半价（北京时间工作日 9:00–12:00、14:00–18:00 之外，含周末与法定节假日全天）"
        } else {
            "当前空闲时段，按半价计；高峰为北京时间工作日 9:00–12:00、14:00–18:00"
        }
    }

    /** 金额格式化：小于 0.01 元时给两位有效数字，避免一律显示 0.00 */
    fun format(yuan: Double): String = when {
        yuan <= 0.0 -> "0.00"
        yuan < 0.01 -> String.format("%.4f", yuan)
        else -> String.format("%.2f", yuan)
    }

    /** token 数格式化为 M / K 单位 */
    fun tokens(total: Long): String = when {
        total >= 1_000_000 -> String.format("%.2f M", total / 1_000_000.0)
        total >= 1_000 -> String.format("%.1f K", total / 1_000.0)
        else -> total.toString()
    }

    /** 命中率百分比（总输入为 0 时给 0） */
    fun hitRate(hit: Long, miss: Long): Int {
        val total = hit + miss
        return if (total <= 0) 0 else (hit * 100 / total).toInt()
    }
}
