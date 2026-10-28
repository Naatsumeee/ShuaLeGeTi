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
 * 强度分三档，映射到 [VibrationEffect] 的 amplitude：
 * - 轻（1）= 36/255，几乎无声的"触感确认"，适合选项点击
 * - 中（2）= 90/255，默认
 * - 强（3）= 160/255，明显
 *
 * 注意：`setSoundEnabled` 与 [setEnabled] 互不影响——用户可以把音效关掉只留震动。
 */
object Haptics {

    /** 预设动作；时长单位为毫秒 */
    enum class Beat(val ms: Long, val factor: Float) {
        TAP(12L, 0.75f),   // 选项点击
        TICK(16L, 0.9f),   // 切换下一题
        OK(22L, 1.0f),     // 答对
        NG(38L, 1.1f),     // 答错
        LONG(30L, 1.0f)    // 长按
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
            val amp = amplitudeOf(strength) * beat.factor
            val a = amp.roundToIntInclusive()
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createOneShot(beat.ms, a))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(beat.ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun amplitudeOf(level: Int): Float = when (level) {
        1 -> 40f
        3 -> 165f
        else -> 92f
    }

    private fun Float.roundToIntInclusive(): Int = toInt().coerceIn(1, 255)
}
