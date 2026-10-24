package com.examhelper.shua.ui.bank

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.parse.TextQuestionParser
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class BankListViewModel @JvmOverloads constructor(
    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo
) : ViewModel() {
    val banks: StateFlow<List<QuestionBank>> = bankRepo.observeBanks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 导入任务横幅（进行中/待确认） */
    val jobs: StateFlow<List<com.examhelper.shua.data.db.ImportJob>> =
        ShuaApp.instance.container.database.importJobDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteBank(bankId: Long) {
        viewModelScope.launch { bankRepo.deleteBank(bankId) }
    }
}

class ImportTextViewModel @JvmOverloads constructor(
    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo
) : ViewModel() {

    data class UiState(
        val text: String = "",
        val parsed: List<ParsedQuestion>? = null,
        val bankName: String = "",
        val importing: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun updateText(text: String) {
        _state.value = _state.value.copy(text = text, parsed = null)
    }

    fun updateBankName(name: String) {
        _state.value = _state.value.copy(bankName = name)
    }

    fun parse() {
        val result = TextQuestionParser.parse(_state.value.text)
        _state.value = _state.value.copy(
            parsed = result,
            error = if (result.isEmpty()) "没有解析出题目，请检查格式（题号如 1. 或 1、，选项如 A.）" else null
        )
    }

    fun confirmImport(onDone: () -> Unit) {
        val s = _state.value
        val parsed = s.parsed ?: return
        if (parsed.isEmpty() || s.importing) return
        viewModelScope.launch {
            _state.value = s.copy(importing = true)
            val name = s.bankName.ifBlank { "导入题库 ${System.currentTimeMillis() % 10000}" }
            runCatching { bankRepo.createBank(name, null, parsed) }
                .onSuccess {
                    _state.value = _state.value.copy(importing = false)
                    onDone()
                }
                .onFailure {
                    _state.value = _state.value.copy(importing = false, error = "导入失败: ${it.message}")
                }
        }
    }
}

class BankDetailViewModel @JvmOverloads constructor(
    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo,
    private val studyRepo: StudyRepository = ShuaApp.instance.container.studyRepo
) : ViewModel() {
    suspend fun loadBank(bankId: Long): QuestionBank? = bankRepo.getBank(bankId)
    suspend fun countNoAnswer(bankId: Long): Int = bankRepo.countNoAnswer(bankId)
    suspend fun countNoAnalysis(bankId: Long): Int = bankRepo.countNoAnalysis(bankId)
    fun deleteBank(bankId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            bankRepo.deleteBank(bankId)
            onDone()
        }
    }

    var lastExport: String? = null
        private set

    data class ExportStats(val total: Int, val withAnswer: Int, val aiCount: Int)

    suspend fun exportStats(bankId: Long): ExportStats? {
        val bank = bankRepo.getBank(bankId) ?: return null
        val noAnswer = bankRepo.countNoAnswer(bankId)
        return ExportStats(
            total = bank.questionCount,
            withAnswer = bank.questionCount - noAnswer,
            aiCount = bankRepo.countAiGenerated(bankId)
        )
    }

    /** 导出为标准题库 JSON 文件（includeAiGenerated=false 时剔除 AI 生成的答案解析） */
    fun exportTo(uri: android.net.Uri, bankId: Long, includeAiGenerated: Boolean, context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val file = bankRepo.exportBank(bankId, includeAiGenerated) ?: error("题库不存在")
                // 换行缩进美化，便于用户阅读与二次编辑
                val text = kotlinx.serialization.json.Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                }.encodeToString(file)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入文件")
            }.onSuccess {
                lastExport = "导出成功（${if (includeAiGenerated) "含" else "不含"}AI答案）"
            }.onFailure { lastExport = "导出失败：${it.message}" }
        }
    }
}
