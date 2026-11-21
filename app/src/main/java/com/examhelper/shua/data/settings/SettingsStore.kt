package com.examhelper.shua.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.examhelper.shua.data.llm.ReasoningEffort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** OCR 引擎选项 */
object OcrEngine {
    const val ML_KIT = "mlkit"      // 轻量，内置无需额外模型
    const val RAPID_OCR = "rapidocr" // 默认：高精度（PP-OCRv5 + 版面分析）
}

data class AppSettings(
    // 默认走 RapidOCR：中文题库基本是扫描件/照片，ML Kit 对双栏与生僻字明显更差
    val ocrEngine: String = OcrEngine.RAPID_OCR,
    /** RapidOCR 页级并行数；默认 1，实验档 2..4。ML Kit 始终单路。 */
    val rapidOcrConcurrency: Int = 1,
    val speedModeSeconds: Int = 2,
    val sanShengLiuBu: Boolean = false, // 三省六部模式开关
    val parseConcurrency: Int = 4,      // AI 解析分块并发数
    val fontScale: Float = 1.0f,        // 全局字体缩放
    val uiScale: Float = 1.0f,          // 全局渲染比例缩放（0.80 ~ 1.25）
    /** 震动反馈开关 */
    val hapticEnabled: Boolean = true,
    /** 震动强度：1..5（默认 3） */
    val hapticStrength: Int = 3,
    /** 震动时长：1..5（默认 3） */
    val hapticDuration: Int = 3,
    /** 暗黑护眼（橄榄柔光夜读）模式；null = 跟随系统 */
    val darkMode: Boolean? = null,
    /** 自定义系统提示词（空 = 默认） */
    val customParsePrompt: String = "",
    val customAnswerPrompt: String = "",
    /**
     * 深度解题 / 补全解析的思考强度。
     *
     * 默认 [ReasoningEffort.LOW] 而不是官方默认的 high：实测 high 会把
     * max_tokens 预算整段吃掉（一次 5613 字的分块，思考烧光 8192 输出预算、
     * 一道题都没解析出来），low 才是「想一点但不至于烧穿」的档。
     *
     * 导入解析**故意不读这个设置**：那是结构化抽取，思考只会白烧钱，
     * 见 [com.examhelper.shua.data.parse.LlmQuestionParser.PARSE_REASONING]。
     */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.LOW,
    val onboarded: Boolean = false
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        val RAPID_OCR_CONCURRENCY = intPreferencesKey("rapid_ocr_concurrency")
        val SPEED_SECONDS = intPreferencesKey("speed_seconds")
        val SAN_SHENG = booleanPreferencesKey("san_sheng_liu_bu")
        val PARSE_CONCURRENCY = intPreferencesKey("parse_concurrency")
        val FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("font_scale")
        val UI_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("ui_scale")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength_v2")
        val HAPTIC_DURATION = intPreferencesKey("haptic_duration_v2")
        val DARK_MODE = stringPreferencesKey("dark_mode") // "system" / "on" / "off"
        val CUSTOM_PARSE_PROMPT = stringPreferencesKey("custom_parse_prompt")
        val CUSTOM_ANSWER_PROMPT = stringPreferencesKey("custom_answer_prompt")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ocrEngine = p[Keys.OCR_ENGINE] ?: OcrEngine.RAPID_OCR,
            rapidOcrConcurrency = (p[Keys.RAPID_OCR_CONCURRENCY] ?: 1).coerceIn(1, 4),
            speedModeSeconds = (p[Keys.SPEED_SECONDS] ?: 2).coerceIn(1, 10),
            sanShengLiuBu = p[Keys.SAN_SHENG] ?: false,
            parseConcurrency = (p[Keys.PARSE_CONCURRENCY] ?: 4).coerceIn(1, 8),
            fontScale = (p[Keys.FONT_SCALE] ?: 1.0f).coerceIn(0.8f, 1.5f),
            uiScale = (p[Keys.UI_SCALE] ?: 1.0f).coerceIn(0.8f, 1.25f),
            hapticEnabled = p[Keys.HAPTIC_ENABLED] ?: true,
            hapticStrength = (p[Keys.HAPTIC_STRENGTH] ?: 3).coerceIn(1, 5),
            hapticDuration = (p[Keys.HAPTIC_DURATION] ?: 3).coerceIn(1, 5),
            darkMode = when (p[Keys.DARK_MODE]) {
                "on" -> true
                "off" -> false
                else -> null
            },
            customParsePrompt = p[Keys.CUSTOM_PARSE_PROMPT] ?: "",
            customAnswerPrompt = p[Keys.CUSTOM_ANSWER_PROMPT] ?: "",
            reasoningEffort = ReasoningEffort.fromName(p[Keys.REASONING_EFFORT]),
            onboarded = p[Keys.ONBOARDED] ?: false
        )
    }

    suspend fun setOcrEngine(v: String) = context.dataStore.edit { it[Keys.OCR_ENGINE] = v }
    suspend fun setRapidOcrConcurrency(v: Int) =
        context.dataStore.edit { it[Keys.RAPID_OCR_CONCURRENCY] = v.coerceIn(1, 4) }
    suspend fun setSpeedSeconds(v: Int) = context.dataStore.edit { it[Keys.SPEED_SECONDS] = v.coerceIn(1, 10) }
    suspend fun setSanShengLiuBu(v: Boolean) = context.dataStore.edit { it[Keys.SAN_SHENG] = v }
    suspend fun setParseConcurrency(v: Int) = context.dataStore.edit { it[Keys.PARSE_CONCURRENCY] = v.coerceIn(1, 8) }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.8f, 1.5f) }
    suspend fun setUiScale(v: Float) = context.dataStore.edit { it[Keys.UI_SCALE] = v.coerceIn(0.8f, 1.25f) }
    suspend fun setHapticEnabled(v: Boolean) = context.dataStore.edit { it[Keys.HAPTIC_ENABLED] = v }
    suspend fun setHapticStrength(v: Int) = context.dataStore.edit { it[Keys.HAPTIC_STRENGTH] = v.coerceIn(1, 5) }
    suspend fun setHapticDuration(v: Int) = context.dataStore.edit { it[Keys.HAPTIC_DURATION] = v.coerceIn(1, 5) }
    suspend fun setDarkMode(v: Boolean?) = context.dataStore.edit {
        it[Keys.DARK_MODE] = when (v) {
            true -> "on"
            false -> "off"
            null -> "system"
        }
    }
    suspend fun setCustomParsePrompt(v: String) = context.dataStore.edit { it[Keys.CUSTOM_PARSE_PROMPT] = v.take(8000) }
    suspend fun setCustomAnswerPrompt(v: String) = context.dataStore.edit { it[Keys.CUSTOM_ANSWER_PROMPT] = v.take(8000) }
    suspend fun setReasoningEffort(v: ReasoningEffort) =
        context.dataStore.edit { it[Keys.REASONING_EFFORT] = v.name }
}
