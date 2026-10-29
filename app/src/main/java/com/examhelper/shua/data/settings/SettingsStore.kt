package com.examhelper.shua.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** OCR 引擎选项 */
object OcrEngine {
    const val ML_KIT = "mlkit"      // 默认，轻量
    const val RAPID_OCR = "rapidocr" // 高精度（M2 提供）
}

data class AppSettings(
    val ocrEngine: String = OcrEngine.ML_KIT,
    val speedModeSeconds: Int = 3,
    val sanShengLiuBu: Boolean = false, // 三省六部模式开关
    val parseConcurrency: Int = 4,      // AI 解析分块并发数
    val fontScale: Float = 1.0f,        // 全局字体缩放
    /** 震动反馈开关 */
    val hapticEnabled: Boolean = true,
    /** 震动强度：1=轻 2=中 3=强 */
    val hapticStrength: Int = 2,
    /** 暗黑护眼（橄榄柔光夜读）模式；null = 跟随系统 */
    val darkMode: Boolean? = null,
    /** 自定义系统提示词（空 = 默认） */
    val customParsePrompt: String = "",
    val customAnswerPrompt: String = "",
    val onboarded: Boolean = false
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        val SPEED_SECONDS = intPreferencesKey("speed_seconds")
        val SAN_SHENG = booleanPreferencesKey("san_sheng_liu_bu")
        val PARSE_CONCURRENCY = intPreferencesKey("parse_concurrency")
        val FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("font_scale")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val DARK_MODE = stringPreferencesKey("dark_mode") // "system" / "on" / "off"
        val CUSTOM_PARSE_PROMPT = stringPreferencesKey("custom_parse_prompt")
        val CUSTOM_ANSWER_PROMPT = stringPreferencesKey("custom_answer_prompt")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ocrEngine = p[Keys.OCR_ENGINE] ?: OcrEngine.ML_KIT,
            speedModeSeconds = (p[Keys.SPEED_SECONDS] ?: 3).coerceIn(1, 10),
            sanShengLiuBu = p[Keys.SAN_SHENG] ?: false,
            parseConcurrency = (p[Keys.PARSE_CONCURRENCY] ?: 4).coerceIn(1, 8),
            fontScale = (p[Keys.FONT_SCALE] ?: 1.0f).coerceIn(0.8f, 1.5f),
            hapticEnabled = p[Keys.HAPTIC_ENABLED] ?: true,
            hapticStrength = (p[Keys.HAPTIC_STRENGTH] ?: 2).coerceIn(1, 3),
            darkMode = when (p[Keys.DARK_MODE]) {
                "on" -> true
                "off" -> false
                else -> null
            },
            customParsePrompt = p[Keys.CUSTOM_PARSE_PROMPT] ?: "",
            customAnswerPrompt = p[Keys.CUSTOM_ANSWER_PROMPT] ?: "",
            onboarded = p[Keys.ONBOARDED] ?: false
        )
    }

    suspend fun setOcrEngine(v: String) = context.dataStore.edit { it[Keys.OCR_ENGINE] = v }
    suspend fun setSpeedSeconds(v: Int) = context.dataStore.edit { it[Keys.SPEED_SECONDS] = v.coerceIn(1, 10) }
    suspend fun setSanShengLiuBu(v: Boolean) = context.dataStore.edit { it[Keys.SAN_SHENG] = v }
    suspend fun setParseConcurrency(v: Int) = context.dataStore.edit { it[Keys.PARSE_CONCURRENCY] = v.coerceIn(1, 8) }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.8f, 1.5f) }
    suspend fun setHapticEnabled(v: Boolean) = context.dataStore.edit { it[Keys.HAPTIC_ENABLED] = v }
    suspend fun setHapticStrength(v: Int) = context.dataStore.edit { it[Keys.HAPTIC_STRENGTH] = v.coerceIn(1, 3) }
    suspend fun setDarkMode(v: Boolean?) = context.dataStore.edit {
        it[Keys.DARK_MODE] = when (v) {
            true -> "on"
            false -> "off"
            null -> "system"
        }
    }
    suspend fun setCustomParsePrompt(v: String) = context.dataStore.edit { it[Keys.CUSTOM_PARSE_PROMPT] = v.take(8000) }
    suspend fun setCustomAnswerPrompt(v: String) = context.dataStore.edit { it[Keys.CUSTOM_ANSWER_PROMPT] = v.take(8000) }
}
