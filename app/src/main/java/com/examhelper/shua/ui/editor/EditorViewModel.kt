package com.examhelper.shua.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L

    data class UiState(
        val loading: Boolean = true,
        val bankName: String = "",
        val questions: List<Question> = emptyList(),
        val editing: Question? = null,
        val showBatchDialog: Boolean = false,
        val toast: String? = null
    ) {
        val chapterSummary: List<Pair<String, Int>>
            get() = questions.groupBy { it.chapter ?: "未分章" }
                .map { (k, v) -> k to v.size }
                .sortedByDescending { it.second }
        val pageRange: IntRange?
            get() = questions.mapNotNull { it.sourcePage }.takeIf { it.isNotEmpty() }
                ?.let { it.min()..it.max() }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val container = ShuaApp.instance.container
    private val bankRepo = container.bankRepo

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val bank = bankRepo.getBank(bankId)
            val questions = bankRepo.getQuestions(bankId)
            _state.value = _state.value.copy(
                loading = false,
                bankName = bank?.name.orEmpty(),
                questions = questions
            )
        }
    }

    fun openBatchDialog() {
        _state.value = _state.value.copy(showBatchDialog = true)
    }

    fun closeBatchDialog() {
        _state.value = _state.value.copy(showBatchDialog = false)
    }

    /** 按题号范围批量分章（1-based，闭区间） */
    fun assignByIndex(fromQ: Int, toQ: Int, chapterName: String) {
        if (chapterName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val qs = _state.value.questions
            val range = (fromQ.coerceIn(1, qs.size) - 1) until toQ.coerceIn(fromQ, qs.size)
            qs.subList(range.first, range.last + 1).forEach { q ->
                bankRepo.updateQuestion(q.copy(chapter = chapterName.trim()))
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showBatchDialog = false,
                    toast = "已将第 ${range.first + 1}-${range.last + 1} 题划分为「${chapterName.trim()}」(${range.count()} 题)"
                )
                reload()
            }
        }
    }

    /** 按页码范围批量分章 */
    fun assignByPage(fromPage: Int, toPage: Int, chapterName: String) {
        val qs = _state.value.questions
        val hit = qs.filter { it.sourcePage != null && it.sourcePage in fromPage..toPage }
        if (chapterName.isBlank() || hit.isEmpty()) {
            _state.value = _state.value.copy(toast = "该页码范围内没有题目")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            hit.forEach { bankRepo.updateQuestion(it.copy(chapter = chapterName.trim())) }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showBatchDialog = false,
                    toast = "已把第 $fromPage-$toPage 页的 ${hit.size} 题划分为「${chapterName.trim()}」"
                )
                reload()
            }
        }
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun openEdit(questionId: Long) {
        _state.value = _state.value.copy(editing = _state.value.questions.firstOrNull { it.id == questionId })
    }

    fun closeEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun saveQuestion(
        chapter: String?, type: QuestionType, subtype: String?, stem: String,
        options: List<QuestionOption>, answer: String?, analysis: String?
    ) {
        val q = _state.value.editing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestion(
                q.copy(
                    chapter = chapter?.trim()?.takeIf { it.isNotBlank() },
                    type = type,
                    subtype = subtype,
                    stem = stem.trim(),
                    optionsJson = options.filter { it.text.isNotBlank() }.takeIf { it.isNotEmpty() }
                        ?.let(JsonCodec::encodeOptions),
                    answer = answer?.trim()?.takeIf { it.isNotBlank() },
                    analysis = analysis?.trim()?.takeIf { it.isNotBlank() }
                )
            )
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(editing = null, toast = "题目已保存")
                reload()
            }
        }
    }
}
