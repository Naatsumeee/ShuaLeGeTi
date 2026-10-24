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
    val onboarded: Boolean = false
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        val SPEED_SECONDS = intPreferencesKey("speed_seconds")
        val SAN_SHENG = booleanPreferencesKey("san_sheng_liu_bu")
        val PARSE_CONCURRENCY = intPreferencesKey("parse_concurrency")
        val FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("font_scale")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ocrEngine = p[Keys.OCR_ENGINE] ?: OcrEngine.ML_KIT,
            speedModeSeconds = (p[Keys.SPEED_SECONDS] ?: 3).coerceIn(1, 10),
            sanShengLiuBu = p[Keys.SAN_SHENG] ?: false,
            parseConcurrency = (p[Keys.PARSE_CONCURRENCY] ?: 4).coerceIn(1, 8),
            fontScale = (p[Keys.FONT_SCALE] ?: 1.0f).coerceIn(0.8f, 1.5f),
            onboarded = p[Keys.ONBOARDED] ?: false
        )
    }

    suspend fun setOcrEngine(v: String) = context.dataStore.edit { it[Keys.OCR_ENGINE] = v }
    suspend fun setSpeedSeconds(v: Int) = context.dataStore.edit { it[Keys.SPEED_SECONDS] = v.coerceIn(1, 10) }
    suspend fun setSanShengLiuBu(v: Boolean) = context.dataStore.edit { it[Keys.SAN_SHENG] = v }
    suspend fun setParseConcurrency(v: Int) = context.dataStore.edit { it[Keys.PARSE_CONCURRENCY] = v.coerceIn(1, 8) }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.8f, 1.5f) }
}
