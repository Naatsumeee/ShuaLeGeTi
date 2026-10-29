package com.examhelper.shua.data.generate

import java.util.Calendar

/**
 * DeepSeek 官方分时峰谷定价 → 人民币成本估算。
 *
 * 官方价目（元 / 百万 tokens，deepseek-chat 标准价）：
 * | 项目           | 标准时段 | 优惠时段 |
 * |----------------|---------|---------|
 * | 输入·缓存命中  | 0.5     | 0.25    |
 * | 输入·未命中    | 2.0     | 1.0     |
 * | 输出           | 8.0     | 2.0     |
 *
 * 优惠时段为北京时间 00:30–08:30（对应 UTC 16:30–00:30）。
 * 中转/第三方端点可能另有加价，这里只给「参考成本」，UI 上也这么标注。
 */
object Cost {

    /** 每百万 tokens 单价（元） */
    private const val HIT_STD = 0.5
    private const val MISS_STD = 2.0
    private const val OUT_STD = 8.0

    private const val HIT_OFF = 0.25
    private const val MISS_OFF = 1.0
    private const val OUT_OFF = 2.0

    /** 该时刻是否处于优惠（谷）时段：北京时间 00:30–08:30 */
    fun isOffPeak(millis: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return minutes in (0 * 60 + 30) until (8 * 60 + 30)
    }

    /**
     * 估算成本（元）。峰谷按当前时刻判断——生成过程可能跨时段，
     * 但用户看的是「这批活大概花了多少」，按结束时刻结算足够。
     */
    fun yuan(
        hitTokens: Long,
        missTokens: Long,
        outputTokens: Long,
        millis: Long = System.currentTimeMillis()
    ): Double {
        val off = isOffPeak(millis)
        val hit = if (off) HIT_OFF else HIT_STD
        val miss = if (off) MISS_OFF else MISS_STD
        val out = if (off) OUT_OFF else OUT_STD
        return (hitTokens * hit + missTokens * miss + outputTokens * out) / 1_000_000.0
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
