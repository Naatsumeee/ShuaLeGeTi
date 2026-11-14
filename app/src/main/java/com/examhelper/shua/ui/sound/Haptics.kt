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
        TAP(7L),     // 选项点击 / 取消选择：最轻最快的一下
        TICK(9L),    // 切到上一题 / 下一题
        OK(13L),     // 答对（保留给需要区分对错的场景，正常流程只用 click）
        NG(18L),     // 答错
        LONG(11L),   // 长按按下/松开的两段触感
        DONE(16L)    // 完成本轮 / 返回 / 重刷
    }

    @Volatile
    private var enabled: Boolean = true

    /** 强度：1..10，只映射到 amplitude（默认 2 = 用户要的"轻"起手） */
    @Volatile
    private var strength: Int = 2

    /** 时长：1..10，只映射到毫秒数（默认 2 = 短促但能被感知） */
    @Volatile
    private var duration: Int = 2

    @Volatile
    private var lastPlayedAt: Long = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 强度 1–10（1 最轻、10 最重），只影响振幅 */
    fun setStrength(value: Int) {
        strength = value.coerceIn(1, 10)
    }

    /** 时长 1–10（1 最短、10 最长），只影响毫秒 */
    fun setDuration(value: Int) {
        duration = value.coerceIn(1, 10)
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
            val idle = now - lastPlayedAt > 30_000L
            lastPlayedAt = now
            val amp = amplitudeOf()
            if (idle && hasAmplitude) {
                // 部分 ROM 空闲后会吞掉第一记短脉冲；预脉冲与真实反馈合成一个波形。
                vibrateEffect(
                    vibrator,
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 2L, 12L) + strengthWaveform(ms, amp, hasAmplitude).first,
                        intArrayOf(0, 8, 0) + strengthWaveform(ms, amp, hasAmplitude).second,
                        -1
                    )
                )
            } else {
                val (timings, amplitudes) = strengthWaveform(ms, amp, hasAmplitude)
                vibrateEffect(vibrator, VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        }
    }

    /**
     * 实际毫秒数 = 基准时长 × 时长系数。
     *
     * 系数在 1..10 档之间铺开：1 档 0.35 倍（最清脆，TAP 只有 2.5ms → 取 6ms 下限），
     * 2 档 1.42 倍（TAP 10ms），10 档 10 倍（TAP 70ms、DONE 160ms）。
     * **强度不参与**，否则两个旋钮又糊在一起。
     *
     * 上限取到 10 倍而不是更窄：用户实测「时长拉到 10 能感觉到延长，但没有特别长」，
     * 说明 0.4..7.0 仍然不够。震动的「长」是被感知成拖尾的，想明显就得给到
     * 60~160ms 这个量级，再往上就变成「嗡」而不是「嗒」。
     */
    private fun durationOf(beat: Beat): Long {
        val scale = 0.35f + (duration - 1) * (9.65f / 9f)   // 0.35f .. 10.0f
        return (beat.ms * scale).toLong().coerceIn(6L, 180L)
    }

    /**
     * 振幅 = 强度滑块的直接映射，**与具体动作无关，也不掺任何别的系数**。
     *
     * 用户在真机上给了两条明确反馈：
     * 1. 「2 档（默认）已经偏强」→ 低档必须明显轻下去；
     * 2. 「拉到 10 和 2 感觉不出差别」→ 根因是**动作权重也被乘进了振幅**：
     *    TAP 的 0.62 / NG 的 1.25 把同一档位压成 27~255 的窄带，高档全部顶到 255
     *    饱和，再往上和一百多没有区别。现在动作之间的轻重差异**只由 [Beat.ms]
     *    的时长表达**（TAP 7ms 最轻、NG 18ms 最重），振幅只管强度滑块。
     *
     * 曲线用 1.8 次幂：1 档 20（极轻）、2 档 24（默认，轻）、5 档 63、8 档 152、
     * 10 档 255（满幅）—— 两端差 12 倍以上，且中段不再挤在一起。
     *
     * 下限 20 而不是 1：低于 20 在多数线性马达上会被系统当成「无意义的一次极弱驱动」
     * 直接吞掉，用户会以为震动坏了。
     */
    private fun amplitudeOf(): Int {
        val t = (strength - 1) / 9f
        val base = 20f + 235f * Math.pow(t.toDouble(), 1.8).toFloat()
        return base.toInt().coerceIn(20, 255)
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

    /**
     * 同样总时长内，高档位使用更多脉冲。不能只依赖 amplitude：部分 ROM 会把
     * USAGE_TOUCH 的振幅统一压缩，用户无论怎么调都感觉一样。
     */
    private fun strengthWaveform(ms: Long, amp: Int, hasAmplitude: Boolean): Pair<LongArray, IntArray> {
        val pulses = 1 + (strength - 1) / 3
        val gap = if (pulses > 1) 2L else 0L
        val pulseMs = ((ms - gap * (pulses - 1)) / pulses).coerceAtLeast(2L)
        val timings = LongArray(pulses * 2 - 1)
        val amplitudes = IntArray(timings.size)
        for (i in 0 until pulses) {
            timings[i * 2] = pulseMs
            amplitudes[i * 2] = if (hasAmplitude) amp else VibrationEffect.DEFAULT_AMPLITUDE
            if (i < pulses - 1) {
                timings[i * 2 + 1] = gap
                amplitudes[i * 2 + 1] = 0
            }
        }
        return timings to amplitudes
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
