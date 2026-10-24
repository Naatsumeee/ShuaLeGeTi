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

class BankListViewModel @JvmOverloads constructor(
    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo
) : ViewModel() {
    val banks: StateFlow<List<QuestionBank>> = bankRepo.observeBanks()
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
    fun deleteBank(bankId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            bankRepo.deleteBank(bankId)
            onDone()
        }
    }
}
