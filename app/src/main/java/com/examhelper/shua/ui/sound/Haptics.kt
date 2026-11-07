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
 * ## 为什么「强度」以前调了没感觉（v1.0.12 排查结论）
 *
 * 旧实现里**有振幅控制的设备永远用同一个时长**（TAP 固定 8ms），只改 amplitude。
 * 这个思路在理论上成立，但在真实硬件上不成立：
 *
 * 线性马达（LRA）的谐振频率约 150–200Hz，一个整周期才 5–6.6ms。8ms 的驱动只够
 * 走 1~2 个周期，**振子还没加速到设定的振幅就被断电了**，实际感受到的是起振瞬态
 * 而不是 amplitude 参数。所以 70 / 140 / 235 三档在听感上几乎没有差别 —— 用户反馈
 * 「强度功能依然失效」就是这个原因，不是代码没生效。
 *
 * 修法：**时长也随强度一起变**（见 [Beat] 与 [durationOf]），并且把时长做成用户可调
 * 的第二维度（短 / 标准 / 长）。短促仍然短促，但短和强之间真的能听出差别。
 *
 * 第二个坑：旧实现在每次 vibrate 前无条件调用 `cancel()`。部分 ROM（MIUI/HyperOS、
 * 部分 AOSP 定制）上 cancel 紧接 vibrate 会把新的一次吞掉或截成极短的一下，
 * 表现就是「点了没反应」或「强弱一样」。现在只在**明确要打断**时取消。
 *
 * 第三个坑：不带 `VibrationAttributes` 的震动走 `USAGE_UNKNOWN`，部分 ROM 会按
 * **电话/通知** 通道去套用系统「触感强度」设置，覆盖掉 app 传入的 amplitude。
 * API 33+ 显式标 `USAGE_TOUCH`，让系统按触摸反馈处理（也就不会忽略 amplitude）。
 */
object Haptics {

    /**
     * 预设动作。[ms] 是基准时长（毫秒），[factor] 是相对强度权重。
     *
     * 时长刻意都压在 6–22ms：机械键盘的段落感就在这个区间，再长就成了「嗡」。
     */
    enum class Beat(val ms: Long, val factor: Float) {
        TAP(7L, 0.62f),     // 选项点击 / 取消选择：最轻最快的一下
        TICK(9L, 0.80f),    // 切到上一题 / 下一题
        OK(13L, 1.0f),      // 答对
        NG(18L, 1.25f),     // 答错：稍重，但不拖
        LONG(11L, 0.95f),   // 长按按下/松开的两段触感
        DONE(16L, 1.1f)     // 完成本轮 / 返回 / 重刷
    }

    @Volatile
    private var enabled: Boolean = true

    /** 1=轻 2=中 3=强 */
    @Volatile
    private var strength: Int = 2

    /** 1=短 2=标准 3=长 */
    @Volatile
    private var duration: Int = 1

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setStrength(value: Int) {
        strength = value.coerceIn(1, 3)
    }

    fun setDuration(value: Int) {
        duration = value.coerceIn(1, 3)
    }

    fun tap() = play(Beat.TAP)
    fun tick() = play(Beat.TICK)
    fun ok() = play(Beat.OK)
    fun ng() = play(Beat.NG)
    fun longPress() = play(Beat.LONG)
    fun done() = play(Beat.DONE)

    fun play(beat: Beat) {
        if (!enabled) return
        runCatching {
            val ctx: Context = ShuaApp.instance
            val vibrator = resolve(ctx) ?: return
            if (!vibrator.hasVibrator()) return
            if (!vibrator.hasAmplitudeControl()) {
                // 转子马达：只能靠时长表达强弱，amplitude 会被系统忽略
                val ms = durationOf(beat).coerceIn(6L, 60L)
                vibrate(vibrator, ms, VibrationEffect.DEFAULT_AMPLITUDE)
                return
            }
            val amp = amplitudeOf(beat)
            val ms = durationOf(beat).coerceIn(4L, 60L)
            vibrate(vibrator, ms, amp)
        }
    }

    /**
     * 实际时长 = 基准 × 时长档位 × 强度补偿。
     *
     * 强度也参与时长是这次的**核心修复**：短时长下 amplitude 参数表达不出来
     * （见类注释），必须让「强」的那一下**也稍长一点**，马达才有时间真正加速。
     * 补偿系数刻意很小（0.88 / 1.0 / 1.12），不破坏「短促」的整体手感。
     */
    private fun durationOf(beat: Beat): Long {
        val durScale = when (duration) {
            1 -> 0.80f
            3 -> 1.45f
            else -> 1.0f
        }
        val strengthScale = when (strength) {
            1 -> 0.88f
            3 -> 1.12f
            else -> 1.0f
        }
        return (beat.ms * durScale * strengthScale).toLong().coerceAtLeast(4L)
    }

    /**
     * 有振幅控制时的取值：轻 90 / 中 165 / 强 255，再乘动作权重。
     *
     * 下限抬到 60 而不是 1：极低 amplitude 在多数 LRA 上根本起不振，
     * 用户点下去只会觉得"没反应"而不是"很轻"。
     */
    private fun amplitudeOf(beat: Beat): Int {
        val base = when (strength) {
            1 -> 90f
            3 -> 255f
            else -> 165f
        }
        return (base * beat.factor).toInt().coerceIn(60, 255)
    }

    private fun vibrate(vibrator: Vibrator, ms: Long, amp: Int) {
        val effect = VibrationEffect.createOneShot(ms, amp)
        if (Build.VERSION.SDK_INT >= 33) {
            // 显式标注为触摸反馈：不标的话部分 ROM 会套用系统「触感强度」把
            // amplitude 覆盖掉（这也是"强度调节没感觉"的第二个来源）。
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            )
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
