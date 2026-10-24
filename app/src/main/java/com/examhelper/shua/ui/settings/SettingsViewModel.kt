package com.examhelper.shua.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.settings.OcrEngine
import com.examhelper.shua.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val container = (application as ShuaApp).container
    private val db = container.database
    private val settingsStore: SettingsStore = container.settings
    private val llmClient: LlmClient = container.llmClient

    val providers: StateFlow<List<LlmProvider>> =
        db.providerDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class UiState(
        val ocrEngine: String = OcrEngine.ML_KIT,
        val speedSeconds: Int = 3,
        val sanShengLiuBu: Boolean = false,
        val parseConcurrency: Int = 4,
        val fontScale: Float = 1.0f
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch {
            settingsStore.settings.collect {
                _ui.value = UiState(
                    it.ocrEngine, it.speedModeSeconds, it.sanShengLiuBu,
                    it.parseConcurrency, it.fontScale
                )
            }
        }
    }

    fun setOcrEngine(v: String) = viewModelScope.launch { settingsStore.setOcrEngine(v) }
    fun setSpeedSeconds(v: Int) = viewModelScope.launch { settingsStore.setSpeedSeconds(v) }
    fun setSanShengLiuBu(v: Boolean) = viewModelScope.launch { settingsStore.setSanShengLiuBu(v) }
    fun setParseConcurrency(v: Int) = viewModelScope.launch { settingsStore.setParseConcurrency(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { settingsStore.setFontScale(v) }

    fun saveProvider(provider: LlmProvider) = viewModelScope.launch {
        if (provider.id == 0L) {
            val id = db.providerDao().insert(provider)
            // 第一个添加的模型自动设为主部
            if (db.providerDao().getPrimary() == null) {
                db.providerDao().update(provider.copy(id = id, isPrimary = true))
            }
        } else {
            db.providerDao().update(provider)
        }
    }

    fun setPrimary(id: Long) = viewModelScope.launch {
        db.providerDao().clearPrimary()
        db.providerDao().getById(id)?.let { db.providerDao().update(it.copy(isPrimary = true)) }
    }

    fun deleteProvider(id: Long) = viewModelScope.launch { db.providerDao().deleteById(id) }

    fun toggleEnabled(provider: LlmProvider) = viewModelScope.launch {
        db.providerDao().update(provider.copy(enabled = !provider.enabled))
    }

    data class TestResult(val providerId: Long, val testing: Boolean, val ok: Boolean?, val message: String?)

    private val _test = MutableStateFlow<TestResult?>(null)
    val test: StateFlow<TestResult?> = _test

    fun testConnection(provider: LlmProvider) {
        _test.value = TestResult(provider.id, testing = true, ok = null, message = null)
        viewModelScope.launch {
            val r = llmClient.testConnection(provider)
            _test.value = TestResult(
                provider.id,
                testing = false,
                ok = r.isSuccess,
                message = r.fold({ "连通正常: $it" }, { it.message?.take(120) })
            )
        }
    }
}
