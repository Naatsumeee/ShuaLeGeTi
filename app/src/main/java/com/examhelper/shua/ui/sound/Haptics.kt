package com.examhelper.shua.ui.sound

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.examhelper.shua.ShuaApp

/**
 * 震动反馈（与音效完全独立的两套开关）。
 *
 * 1. 支持 Android 11+ 原语合成（`supportedPrimitives` 非空）时：用 `startComposition()` 精确缩放振幅。
 * 2. 支持 Android 10+ 硬件预设（如小米 HyperOS `EFFECT_TICK / CLICK / HEAVY_CLICK / DOUBLE_CLICK`）时：
 *    直接走硬件主动刹车预设（最清脆、零余振），并把 1–5 档强度映射到 轻弹 / 标准 / 强击 三档硬件波形。
 * 3. 更旧设备兜底使用短波形 `createWaveform`。
 */
object Haptics {

    enum class Beat(val ms: Long) {
        TAP(5L),     // 选项点击 / 取消选择
        TICK(6L),    // 切题 / 滑块刻度 / 热力图滑动
        OK(8L),      // 答对
        NG(11L),     // 答错
        LONG(8L),    // 长按触发
        DONE(9L)     // 完成本轮 / 返回 / 重刷
    }

    private const val IDLE_WAKE_THRESHOLD_MS = 2_500L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabled: Boolean = true

    /** 强度：1..5（默认 3 档） */
    @Volatile
    private var strength: Int = 3

    /** 时长：1..5（默认 3 档；仅旧版波形/原语模式生效） */
    @Volatile
    private var duration: Int = 3

    @Volatile
    private var lastPlayedAt: Long = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 强度 1–5（1 最轻、5 最重） */
    fun setStrength(value: Int) {
        strength = value.coerceIn(1, 5)
    }

    /** 时长 1–5（1 最短促、5 最饱满） */
    fun setDuration(value: Int) {
        duration = value.coerceIn(1, 5)
    }

    fun tap() = play(Beat.TAP)
    fun tick() = play(Beat.TICK)
    fun ok() = play(Beat.OK)
    fun ng() = play(Beat.NG)
    fun longPress() = play(Beat.LONG)
    fun done() = play(Beat.DONE)
    fun click() = play(Beat.TAP)
    fun preview() = play(Beat.TICK)

    fun play(beat: Beat) {
        if (!enabled) return
        runCatching {
            val ctx: Context = ShuaApp.instance
            val vibrator = resolve(ctx) ?: return
            if (!vibrator.hasVibrator()) return

            // 1. Android 11+ 硬件原语合成（支持连续强度缩放）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                composePrimitiveEffect(vibrator, beat)?.let { effect ->
                    vibrateEffect(vibrator, effect)
                    return
                }
            }

            // 2. Android 10+ 硬件预设波形（含主动刹车，无软件余振）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                playPredefined(vibrator, beat)
                return
            }

            // 3. 兜底：旧版波形驱动
            val ms = durationOf(beat)
            val hasAmplitude = vibrator.hasAmplitudeControl()
            val now = System.currentTimeMillis()
            val idle = lastPlayedAt == 0L || (now - lastPlayedAt) > IDLE_WAKE_THRESHOLD_MS
            lastPlayedAt = now
            val amp = amplitudeOf()
            val (mainTimings, mainAmps) = thumpWaveform(ms, amp, hasAmplitude)
            if (idle) {
                val wakeAmp = if (hasAmplitude) 85 else VibrationEffect.DEFAULT_AMPLITUDE
                vibrateEffect(
                    vibrator,
                    VibrationEffect.createWaveform(
                        longArrayOf(2L, 3L) + mainTimings,
                        intArrayOf(wakeAmp, 0) + mainAmps,
                        -1
                    )
                )
            } else {
                vibrateEffect(vibrator, VibrationEffect.createWaveform(mainTimings, mainAmps, -1))
            }
        }
    }

    private fun playPredefined(vibrator: Vibrator, beat: Beat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // 小米 HyperOS 等 HAL 实测：HEAVY_CLICK(57ms) 最轻短、CLICK(78ms) 适中、TICK(90ms) 最重，
        // 按 1..5 档由弱到强正序映射：1-2 轻、3-4 适中、5 强。
        val baseId = when {
            strength <= 2 -> VibrationEffect.EFFECT_HEAVY_CLICK
            strength <= 4 -> VibrationEffect.EFFECT_CLICK
            else -> VibrationEffect.EFFECT_TICK
        }
        vibrateEffect(vibrator, VibrationEffect.createPredefined(baseId))
    }

    private fun composePrimitiveEffect(vibrator: Vibrator, beat: Beat): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val supportsClick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        val supportsTick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
        if (!supportsClick && !supportsTick) return null

        val clickPrim = if (supportsClick) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK
        val tickPrim = if (supportsTick) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
        val scale = primitiveScale()

        val comp = VibrationEffect.startComposition()
        when (beat) {
            Beat.TICK -> {
                val p = if (duration >= 4) clickPrim else tickPrim
                comp.addPrimitive(p, scale)
            }
            Beat.TAP -> {
                val p = if (duration <= 2) tickPrim else clickPrim
                comp.addPrimitive(p, scale)
            }
            Beat.LONG -> {
                comp.addPrimitive(clickPrim, (scale * 1.12f).coerceAtMost(1f))
            }
            Beat.OK, Beat.DONE -> {
                comp.addPrimitive(tickPrim, (scale * 0.8f).coerceAtLeast(0.25f))
                comp.addPrimitive(clickPrim, scale, 60)
            }
            Beat.NG -> {
                val supportsThud = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
                val supportsLowTick = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                val first = if (supportsThud) VibrationEffect.Composition.PRIMITIVE_THUD else clickPrim
                val second = if (supportsLowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else tickPrim
                comp.addPrimitive(first, scale)
                comp.addPrimitive(second, (scale * 0.85f).coerceAtLeast(0.3f), 70)
            }
        }
        return comp.compose()
    }

    /** 当前设备是否走硬件固定时长预设（API 29+ 且无原语连续缩放能力） */
    fun usesFixedHardwarePresets(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching false
        val v = resolve(ShuaApp.instance) ?: return@runCatching false
        if (!v.hasVibrator()) return@runCatching false
        val hasPrimitives = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ||
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK))
        !hasPrimitives
    }.getOrDefault(false)

    private fun primitiveScale(): Float = when (strength) {
        1 -> 0.18f
        2 -> 0.36f
        3 -> 0.58f
        4 -> 0.80f
        else -> 1.00f
    }

    private fun durationOf(beat: Beat): Long {
        val scale = when (duration) {
            1 -> 0.55f
            2 -> 0.78f
            3 -> 1.00f
            4 -> 1.20f
            else -> 1.40f
        }
        return (beat.ms * scale).toLong().coerceIn(3L, 15L)
    }

    private fun amplitudeOf(): Int = when (strength) {
        1 -> 120
        2 -> 165
        3 -> 205
        4 -> 235
        else -> 255
    }

    private fun thumpWaveform(ms: Long, amp: Int, hasAmplitude: Boolean): Pair<LongArray, IntArray> {
        if (!hasAmplitude) {
            return longArrayOf(ms) to intArrayOf(VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val kickAmp = minOf(255, (amp * 1.32f).toInt())
        if (ms < 5L) {
            return longArrayOf(ms) to intArrayOf(kickAmp)
        }
        val tailMs = if (ms >= 10L) 2L else 1L
        val kickMs = (ms - tailMs).coerceAtLeast(3L)
        val tailAmp = (amp * 0.14f).toInt().coerceIn(15, kickAmp)
        return longArrayOf(kickMs, tailMs) to intArrayOf(kickAmp, tailAmp)
    }

    fun supportsAmplitude(): Boolean = runCatching {
        val v = resolve(ShuaApp.instance) ?: return@runCatching false
        v.hasAmplitudeControl() || (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            )
    }.getOrDefault(false)

    private fun vibrateEffect(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

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
