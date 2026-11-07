package com.examhelper.shua.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.llm.BalanceClient
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.repo.LocalDataRepository
import com.examhelper.shua.data.settings.OcrEngine
import com.examhelper.shua.data.settings.SettingsStore
import com.examhelper.shua.ui.sound.Haptics
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
    private val balanceClient: BalanceClient = container.balanceClient
    private val localDataRepo: LocalDataRepository = container.localDataRepo

    val providers: StateFlow<List<LlmProvider>> =
        db.providerDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class UiState(
        val ocrEngine: String = OcrEngine.RAPID_OCR,
        val speedSeconds: Int = 2,
        val sanShengLiuBu: Boolean = false,
        val parseConcurrency: Int = 4,
        val fontScale: Float = 1.0f,
        val hapticEnabled: Boolean = true,
        val hapticStrength: Int = 2,
        val hapticDuration: Int = 1,
        val darkMode: Boolean? = null,
        val customParsePrompt: String = "",
        val customAnswerPrompt: String = ""
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 数据管理操作的一次性反馈（toast 用） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    init {
        viewModelScope.launch {
            settingsStore.settings.collect {
                _ui.value = UiState(
                    ocrEngine = it.ocrEngine,
                    speedSeconds = it.speedModeSeconds,
                    sanShengLiuBu = it.sanShengLiuBu,
                    parseConcurrency = it.parseConcurrency,
                    fontScale = it.fontScale,
                    hapticEnabled = it.hapticEnabled,
                    hapticStrength = it.hapticStrength,
                    hapticDuration = it.hapticDuration,
                    darkMode = it.darkMode,
                    customParsePrompt = it.customParsePrompt,
                    customAnswerPrompt = it.customAnswerPrompt
                )
                Haptics.setEnabled(it.hapticEnabled)
                Haptics.setStrength(it.hapticStrength)
                Haptics.setDuration(it.hapticDuration)
            }
        }
    }

    fun setOcrEngine(v: String) = viewModelScope.launch { settingsStore.setOcrEngine(v) }
    fun setSpeedSeconds(v: Int) = viewModelScope.launch { settingsStore.setSpeedSeconds(v) }
    fun setSanShengLiuBu(v: Boolean) = viewModelScope.launch { settingsStore.setSanShengLiuBu(v) }
    fun setParseConcurrency(v: Int) = viewModelScope.launch { settingsStore.setParseConcurrency(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { settingsStore.setFontScale(v) }
    fun setCustomParsePrompt(v: String) = viewModelScope.launch { settingsStore.setCustomParsePrompt(v) }
    fun setCustomAnswerPrompt(v: String) = viewModelScope.launch { settingsStore.setCustomAnswerPrompt(v) }

    // ---- 震动 / 暗黑模式 ----

    fun setHapticEnabled(v: Boolean) = viewModelScope.launch {
        settingsStore.setHapticEnabled(v)
        Haptics.setEnabled(v)
        if (v) Haptics.ok() // 打开时给一次触感试听
    }

    fun setHapticStrength(v: Int) = viewModelScope.launch {
        settingsStore.setHapticStrength(v)
        Haptics.setStrength(v)
        // 停 60ms 再震：连续两次震动若首尾相接，多数 ROM 会合并成一次，
        // 用户根本分不出前后差异（这就是"调了强度没感觉"的观感来源之一）
        kotlinx.coroutines.delay(60)
        Haptics.tick()
    }

    fun setHapticDuration(v: Int) = viewModelScope.launch {
        settingsStore.setHapticDuration(v)
        Haptics.setDuration(v)
        kotlinx.coroutines.delay(60)
        Haptics.tick()
    }

    /** null = 跟随系统；true/false = 强制橄榄夜读 / 暖纸 */
    fun setDarkMode(v: Boolean?) = viewModelScope.launch { settingsStore.setDarkMode(v) }

    // ---- 本地学习数据管理 ----

    /** 导出文件建议名（日期戳） */
    fun suggestedFileName(): String = localDataRepo.suggestedFileName()

    fun exportLearningRecords(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching { localDataRepo.exportLearningRecords(getApplication(), uri) }
                .onSuccess { s ->
                    _message.value =
                        "已导出：作答 ${s.attempts} · 错题 ${s.wrong} · 收藏 ${s.favorites} · 斩题 ${s.kills}"
                }
                .onFailure { _message.value = "导出失败：${it.message?.take(80)}" }
        }
    }

    fun clearFavorites() = viewModelScope.launch {
        val n = localDataRepo.clearFavorites()
        _message.value = if (n > 0) "已清空收藏夹（$n 条）" else "收藏夹本来就是空的"
    }

    fun resetStudyProgress() = viewModelScope.launch {
        val n = localDataRepo.resetStudyProgress()
        _message.value = if (n > 0) "已重置刷题进度（$n 条进度记录）" else "没有可重置的进度"
    }

    // ---- 模型管理 ----

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

    // ---- 账户余额（DeepSeek 官方接口） ----

    data class BalanceState(
        val loading: Boolean = false,
        val text: String? = null,
        val ok: Boolean? = null
    )

    private val _balance = MutableStateFlow(BalanceState())
    val balance: StateFlow<BalanceState> = _balance

    /**
     * 查询账户余额。只对 DeepSeek 官方端点有效 —— 中转站没有 /user/balance 路由。
     * 优先用主部模型，没有主部就用第一个启用的模型。
     */
    fun refreshBalance() {
        val list = providers.value
        val provider = list.firstOrNull { it.isPrimary && it.enabled }
            ?: list.firstOrNull { it.enabled }
        if (provider == null) {
            _balance.value = BalanceState(text = "还没有启用任何模型", ok = false)
            return
        }
        if (!balanceClient.supports(provider)) {
            _balance.value = BalanceState(
                text = "当前模型「${provider.name.ifBlank { provider.model }}」用的是第三方端点，官方不提供余额查询；成本行按本地 token 统计估算",
                ok = null
            )
            return
        }
        _balance.value = BalanceState(loading = true)
        viewModelScope.launch {
            balanceClient.fetch(provider).fold(
                onSuccess = { b ->
                    val info = b.primary
                    _balance.value = if (info == null) {
                        BalanceState(text = "余额接口返回空数据", ok = false)
                    } else {
                        BalanceState(
                            text = "余额 ${info.totalBalance} ${info.currency}" +
                                "（充值 ${info.toppedUpBalance} + 赠送 ${info.grantedBalance}）",
                            ok = b.isAvailable
                        )
                    }
                },
                onFailure = {
                    _balance.value = BalanceState(
                        text = "查询失败：${it.message?.take(90)}",
                        ok = false
                    )
                }
            )
        }
    }

    /** 用系统浏览器打开项目页 / 反馈邮箱 */
    fun openUrl(url: String) {
        runCatching {
            val ctx = getApplication<Application>()
            val intent = if (url.startsWith("mailto:")) {
                Intent(Intent.ACTION_SENDTO, Uri.parse(url))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { _message.value = "没有可打开该链接的应用" }
    }
}
