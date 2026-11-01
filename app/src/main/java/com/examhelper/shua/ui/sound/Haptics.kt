package com.examhelper.shua.ui.sound

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.examhelper.shua.ShuaApp

/**
 * 震动反馈（与音效完全独立的两套开关）。
 *
 * 设计目标是**短促有力**：一次触感 8–20ms，起振即收，像机械按键的段落感。
 * 早期版本 TAP=12ms、NG=38ms 偏拖，NG 的 38ms 在多数线性马达上会明显"嗡"一下
 * 带余震感。
 *
 * 强度三档同时映射到 **amplitude 与 duration**：
 * - 有振幅控制的设备（绝大多数 X 轴线性马达）用 amplitude 区分，时长保持最短；
 * - 没有振幅控制的设备（老转子马达、部分低端机 / 模拟器）amplitude 会被系统忽略，
 *   此时靠 **时长** 区分——否则三档手感完全一样，这正是"强度调节好像没触发"
 *   的真实原因。
 */
object Haptics {

    /**
     * 预设动作；[ms] 是有振幅控制时的时长，[msSoft] 是无振幅控制时的基准时长
     * （后者必须拉开差距，否则三档一样）。
     */
    enum class Beat(val ms: Long, val msSoft: Long, val factor: Float) {
        TAP(8L, 10L, 0.72f),    // 选项点击：最轻的一下
        TICK(10L, 14L, 0.88f),  // 切换下一题
        OK(14L, 20L, 1.0f),     // 答对
        NG(20L, 30L, 1.15f),    // 答错：稍重，但不再拖 38ms
        LONG(16L, 24L, 1.0f)    // 长按
    }

    @Volatile
    private var enabled: Boolean = true

    /** 1=轻 2=中 3=强 */
    @Volatile
    private var strength: Int = 2

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setStrength(value: Int) {
        strength = value.coerceIn(1, 3)
    }

    fun tap() = play(Beat.TAP)
    fun tick() = play(Beat.TICK)
    fun ok() = play(Beat.OK)
    fun ng() = play(Beat.NG)
    fun longPress() = play(Beat.LONG)

    fun play(beat: Beat) {
        if (!enabled) return
        runCatching {
            val ctx: Context = ShuaApp.instance
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return

            if (vibrator.hasAmplitudeControl()) {
                // 先取消上一条，避免快速连点时排队成"拖尾"
                runCatching { vibrator.cancel() }
                val amp = (amplitudeOf(strength) * beat.factor).toInt().coerceIn(1, 255)
                // 时长兜一层最小 5ms：太短部分机型会直接忽略这一次震动
                vibrator.vibrate(VibrationEffect.createOneShot(beat.ms.coerceAtLeast(5L), amp))
            } else {
                runCatching { vibrator.cancel() }
                val ms = when (strength) {
                    1 -> beat.msSoft
                    3 -> (beat.msSoft * 1.8f).toLong()
                    else -> (beat.msSoft * 1.25f).toLong()
                }
                vibrator.vibrate(
                    VibrationEffect.createOneShot(ms.coerceAtLeast(5L), VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
    }

    /** 有振幅控制时的基准强度：轻 70 / 中 140 / 强 235（255 为上限，留一点余量） */
    private fun amplitudeOf(level: Int): Float = when (level) {
        1 -> 70f
        3 -> 235f
        else -> 140f
    }
}
