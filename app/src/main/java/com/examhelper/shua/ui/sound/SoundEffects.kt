package com.examhelper.shua.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.examhelper.shua.ShuaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 做题音效反馈（与震动完全独立的两套开关）。
 *
 * 不植入音频资源，运行时用 [SoundPool] 播放极短的 PCM 合成音：
 * 正确 = 上行两声（C6→E6），错误 = 下行低沉双音（A3→F3），轻点 = 单声短促（G5），
 * 切题 = 极短提示（D6）。
 *
 * ⚠ 历史 bug（本次修复）：WAV 头原先用 `String.toByteArray(US_ASCII)` 写出，
 * SAMPLE_RATE=44100 的字节里含 0xAC（char 172 > 127），US_ASCII 编码会把它替换成
 * '?'（0x3F），采样率字段被写坏 → SoundPool 加载失败 → 全程静音。
 * 现在一律用 [ByteArray] 直接拼装头部，不经过字符编码。
 */
object SoundEffects {

    private const val SAMPLE_RATE = 44100

    private var pool: SoundPool? = null
    private var correctId = 0
    private var wrongId = 0
    private var tapId = 0
    private var tickId = 0

    /** SoundPool 是异步加载的，记录每个 id 是否就绪 */
    private val ready = HashSet<Int>()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    /** 在 App 启动或从设置页返回时调用；幂等 */
    fun prepare(context: Context = ShuaApp.instance) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(ready) { ready.add(sampleId) }
        }
        correctId = p.load(synth("correct", listOf(1046.5f, 1318.5f), 90, 60), 1)
        wrongId = p.load(synth("wrong", listOf(220f, 174.6f), 130, 40), 1)
        tapId = p.load(synth("tap", listOf(784f), 45, 0), 1)
        tickId = p.load(synth("tick", listOf(1174.7f), 32, 0), 1)
        pool = p
    }

    fun correct() = play(correctId, 0.55f)
    fun wrong() = play(wrongId, 0.5f)
    fun tap() = play(tapId, 0.28f)
    fun tick() = play(tickId, 0.22f)

    /** 判分反馈：correct=null 表示该题无标准答案，不发声 */
    fun verdict(correct: Boolean?) {
        when (correct) {
            true -> correct()
            false -> wrong()
            null -> Unit
        }
    }

    private fun play(id: Int, volume: Float) {
        if (!_enabled.value || id == 0) return
        val p = pool ?: return
        val ok = synchronized(ready) { ready.contains(id) }
        if (!ok) return
        runCatching { p.play(id, volume, volume, 1, 0, 1f) }
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        synchronized(ready) { ready.clear() }
    }

    // ---- PCM 合成：把若干频率按顺序拼接成正弦波，写成缓存 wav 供 SoundPool 读取 ----

    private fun synth(name: String, freqs: List<Float>, durationMsPerNote: Int, gapMs: Int): String {
        val perNote = SAMPLE_RATE * durationMsPerNote / 1000
        val gap = SAMPLE_RATE * gapMs / 1000
        val total = perNote * freqs.size + gap * (freqs.size - 1)
        val samples = ShortArray(total)
        var cursor = 0
        for ((idx, f) in freqs.withIndex()) {
            for (i in 0 until perNote) {
                // 指数衰减包络，避免爆音
                val env = Math.exp(-4.0 * i / perNote).toFloat()
                val v = (Math.sin(2.0 * Math.PI * f * i / SAMPLE_RATE) * env * Short.MAX_VALUE * 0.75).toInt()
                samples[cursor + i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            cursor += perNote
            if (idx < freqs.size - 1) cursor += gap
        }
        val file = com.examhelper.shua.data.storage.AppStorage.sfxFile(ShuaApp.instance, name)
        // 每次启动重写一遍：文件很小（<30KB），避免旧版本写坏的缓存文件继续生效
        runCatching {
            java.io.FileOutputStream(file).use { out ->
                out.write(wavHeader(samples.size * 2))
                val bytes = ByteArray(samples.size * 2)
                var p = 0
                for (s in samples) {
                    bytes[p++] = (s.toInt() and 0xFF).toByte()
                    bytes[p++] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                out.write(bytes)
            }
        }
        return file.absolutePath
    }

    /** 44 字节标准 PCM WAV 头（全部按字节写入，不经字符编码） */
    private fun wavHeader(dataLen: Int): ByteArray {
        val byteRate = SAMPLE_RATE * 2 // 单声道 16bit
        val out = ByteArray(44)
        var i = 0
        fun ascii(s: String) { for (c in s) out[i++] = c.code.toByte() }
        fun le(value: Int, bytes: Int) {
            for (b in 0 until bytes) out[i++] = ((value shr (8 * b)) and 0xFF).toByte()
        }
        ascii("RIFF")
        le(dataLen + 36, 4)
        ascii("WAVE")
        ascii("fmt ")
        le(16, 4)          // SubChunk1Size
        le(1, 2)           // PCM
        le(1, 2)           // Mono
        le(SAMPLE_RATE, 4)
        le(byteRate, 4)
        le(2, 2)           // BlockAlign
        le(16, 2)          // BitsPerSample
        ascii("data")
        le(dataLen, 4)
        return out
    }
}
