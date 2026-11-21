package com.examhelper.shua.ui.sound

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.examhelper.shua.ShuaApp

/**
 * 震动反馈（与音效完全独立的两套开关）。
 *
 * ## 两个旋钮必须彻底拆开（v1.0.12 后的修正）
 *
 * - **强度 1–10**：决定 [VibrationEffect] 的 amplitude 与脉冲密度，不改总毫秒。
 * - **时长 1–10**：只决定震动的毫秒数，不动 amplitude。
 *
 * 上一版把「强度」也乘进了时长（0.88/1.0/1.12 倍），本意是让强的那一下多走几个
 * 马达周期，但结果是两个参数互相干扰 —— 调强度会顺带改变时长，调时长又像在改强度，
 * 于是用户测起来只会得到「怎么调都一样」的结论。现在职责完全分开。
 *
 * ## 为什么低档位以前"没感觉"
 *
 * 线性马达（LRA）的谐振频率约 150–200Hz，一个整周期才 5–6.6ms。极短的驱动
 * （例如 4–6ms）只够走 1 个周期，**振子还没加速到设定振幅就被断电**，感受到的
 * 是起振瞬态而不是 amplitude 参数。所以这里把时长下限抬到 6ms 起，并且让「时长」
 * 这个旋钮真正能拉长到 40ms 上下 —— 想听出弱，就得给足够的时间让它慢起振。
 *
 * ## 另外两个历史坑
 *
 * 1. 以前每次 vibrate 前无条件 `cancel()`。部分 ROM（MIUI/HyperOS、部分 AOSP 定制）
 *    上 cancel 紧接 vibrate 会把新的一次吞掉或截成极短一下，表现就是「点了没反应」。
 *    现在只在明确要打断时取消。
 * 2. 不带 `VibrationAttributes` 的震动走 `USAGE_UNKNOWN`，部分 ROM 会按
 *    **电话/通知** 通道套用系统「触感强度」，覆盖掉 app 传入的 amplitude。
 *    API 33+ 显式标 `USAGE_MEDIA`：实测部分 ROM 会压缩 `USAGE_TOUCH` 的振幅。
 */
object Haptics {

    /**
     * 预设动作。[ms] 是基准时长（标准时长档位下的毫秒数）。
     *
     * 各动作的"轻重"已经由 [ms] 本身表达（TAP 7ms 最轻、NG 18ms 最重），
     * 不再额外挂一个 factor —— 那个系数以前被乘进振幅，正是「强度拉到 10
     * 和 2 没区别」的元凶（高档全部饱和到 255）。
     */
    enum class Beat(val ms: Long) {
        TAP(8L),     // 选项点击 / 取消选择：干脆有力的“咚”一记
        TICK(9L),    // 切到上一题 / 下一题
        OK(11L),     // 答对
        NG(15L),     // 答错
        LONG(11L),   // 长按按下/松开的两段触感
        DONE(13L)    // 完成本轮 / 返回 / 重刷
    }

    /** 硬件休眠阈值：多数线性马达驱动芯片空闲 2~5 秒即进待机，设为 2.5s 主动预唤醒 */
    private const val IDLE_WAKE_THRESHOLD_MS = 2_500L

    @Volatile
    private var enabled: Boolean = true

    /** 强度：1..5，映射到振幅（默认 3 档） */
    @Volatile
    private var strength: Int = 3

    /** 时长：1..5，映射到毫秒数（默认 3 档） */
    @Volatile
    private var duration: Int = 3

    @Volatile
    private var lastPlayedAt: Long = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 强度 1–5（1 最轻、5 最重），只影响振幅 */
    fun setStrength(value: Int) {
        strength = value.coerceIn(1, 5)
    }

    /** 时长 1–5（1 最短、5 最长），只影响毫秒 */
    fun setDuration(value: Int) {
        duration = value.coerceIn(1, 5)
    }

    fun tap() = play(Beat.TAP)
    fun tick() = play(Beat.TICK)
    fun ok() = play(Beat.OK)
    fun ng() = play(Beat.NG)
    fun longPress() = play(Beat.LONG)
    fun done() = play(Beat.DONE)

    /**
     * 普通「一记」点击反馈。
     *
     * 全站所有点击（选项、上一题/下一题、按钮、卡片）都只调它**一次**。
     * 以前选项点击先在 `onOptionTap` 震一下、判完对错又在 `submitChoice` 震一下，
     * 用户听到的就是「无论怎么点都震两下」。
     */
    fun click() = play(Beat.TAP)

    /** 设置页滑块预览：按当前强度/时长震一记 */
    fun preview() = play(Beat.TICK)

    fun play(beat: Beat) {
        if (!enabled) return
        runCatching {
            val ctx: Context = ShuaApp.instance
            val vibrator = resolve(ctx) ?: return
            if (!vibrator.hasVibrator()) return
            val ms = durationOf(beat)
            val hasAmplitude = vibrator.hasAmplitudeControl()
            val now = System.currentTimeMillis()
            val idle = lastPlayedAt == 0L || (now - lastPlayedAt) > IDLE_WAKE_THRESHOLD_MS
            lastPlayedAt = now
            val amp = amplitudeOf()
            val (mainTimings, mainAmps) = thumpWaveform(ms, amp, hasAmplitude)
            if (idle) {
                // 空闲后驱动 IC 会进入低功耗待机；先给一记高于门限的短预激 + 5ms 间隔再推主冲击，
                // 避免首记冲击被硬件上电过程吞掉。
                val wakeAmp = if (hasAmplitude) 65 else VibrationEffect.DEFAULT_AMPLITUDE
                vibrateEffect(
                    vibrator,
                    VibrationEffect.createWaveform(
                        longArrayOf(3L, 5L) + mainTimings,
                        intArrayOf(wakeAmp, 0) + mainAmps,
                        -1
                    )
                )
            } else {
                vibrateEffect(vibrator, VibrationEffect.createWaveform(mainTimings, mainAmps, -1))
            }
        }
    }

    /**
     * 5 档时长映射（5ms..22ms）：3 档默认约 8–13ms 扎实短促，绝不进入拖沓“嗡”振区。
     */
    private fun durationOf(beat: Beat): Long {
        val scale = when (duration) {
            1 -> 0.70f
            2 -> 0.85f
            3 -> 1.00f
            4 -> 1.22f
            else -> 1.48f
        }
        return (beat.ms * scale).toLong().coerceIn(5L, 22L)
    }

    /**
     * 5 档基础振幅（110..255），默认 3 档 195；结合前段 1.18x 过载爆发（默认即达 230），
     * 保证短时长下也有饱满有力的“咚”感。
     */
    private fun amplitudeOf(): Int = when (strength) {
        1 -> 110
        2 -> 150
        3 -> 195
        4 -> 230
        else -> 255
    }

    /**
     * 前强后脆冲击包络（Overdrive Kick + Instant Cutoff）：
     * 前 78% 时间以 1.18x 过载高幅猛推建立动量，后 22% 仅留 2–3ms 陡降收尾并立刻断电，
     * 呈现出「前段爆发强、尾音干脆收住」的“咚”一下触感。
     */
    private fun thumpWaveform(ms: Long, amp: Int, hasAmplitude: Boolean): Pair<LongArray, IntArray> {
        if (!hasAmplitude || ms < 7L) {
            val a = if (hasAmplitude) amp else VibrationEffect.DEFAULT_AMPLITUDE
            return longArrayOf(ms) to intArrayOf(a)
        }
        val kickAmp = minOf(255, (amp * 1.18f).toInt())
        val tailMs = (ms * 22L / 100L).coerceIn(2L, 4L)
        val kickMs = (ms - tailMs).coerceAtLeast(4L)
        val tailAmp = (amp * 0.22f).toInt().coerceIn(25, kickAmp)
        return longArrayOf(kickMs, tailMs) to intArrayOf(kickAmp, tailAmp)
    }

    /**
     * 本机马达是否支持振幅调节。
     *
     * 不支持时 [amplitudeOf] 的结果会被系统忽略（只能靠时长表达强弱），
     * 设置页应当直说，而不是让用户反复拖动一个不起作用的滑块。
     */
    fun supportsAmplitude(): Boolean = runCatching {
        resolve(ShuaApp.instance)?.hasAmplitudeControl() ?: false
    }.getOrDefault(false)

    private fun vibrate(vibrator: Vibrator, ms: Long, amp: Int) {
        vibrateEffect(vibrator, VibrationEffect.createOneShot(ms, amp))
    }

    private fun vibrateEffect(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

    /** 打断当前震动（仅用于「一按新的一下就掐掉上一段」的场景，不要每次播放都调） */
    fun cancel() {
        runCatching { resolve(ShuaApp.instance)?.cancel() }
    }

    private fun resolve(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
