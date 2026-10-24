package com.examhelper.shua.ui.exam

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ExamUiState(
    val loading: Boolean = true,
    val bankName: String = "",
    val index: Int = 0,                    // 当前题下标
    val questions: List<Question> = emptyList(),
    /** 选择题已选 key 集 */
    val selected: Map<Long, Set<String>> = emptyMap(),
    /** 主观题输入 */
    val textInputs: Map<Long, String> = emptyMap(),
    val elapsedSec: Long = 0,
    val submitting: Boolean = false,
    val submittedRunId: String? = null
) {
    val current: Question? get() = questions.getOrNull(index)
    val answeredCount: Int
        get() = questions.count { q ->
            !(selected[q.id]).isNullOrEmpty() || !textInputs[q.id].isNullOrBlank()
        }
}

class ExamViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    private val filter: StudyFilter? =
        StudyFilter.fromFingerprint(savedStateHandle.get<String>("filter"))
    private val count: Int = savedStateHandle.get<String>("count")?.toIntOrNull() ?: 30

    private val container = ShuaApp.instance.container
    private val studyRepo: StudyRepository = container.studyRepo
    private val bankRepo = container.bankRepo

    private val _state = MutableStateFlow(ExamUiState())
    val state: StateFlow<ExamUiState> = _state

    /** 每题累计停留毫秒 */
    private val durations = mutableMapOf<Long, Long>()
    private var shownAt = 0L
    private val runId: String = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            val bank = bankRepo.getBank(bankId)
            val pool = studyRepo.filteredQuestions(bankId, filter)
            val picked = if (count in 1 until pool.size) pool.shuffled().take(count) else pool.shuffled()
            shownAt = System.currentTimeMillis()
            _state.value = ExamUiState(loading = false, bankName = bank?.name.orEmpty(), questions = picked)
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _state.value = _state.value.copy(elapsedSec = _state.value.elapsedSec + 1)
            }
        }
    }

    private fun leaveCurrent() {
        val q = _state.value.current ?: return
        durations[q.id] = (durations[q.id] ?: 0L) + (System.currentTimeMillis() - shownAt)
        shownAt = System.currentTimeMillis()
    }

    fun onOptionTap(key: String) {
        val s = _state.value
        val q = s.current ?: return
        when (q.type) {
            com.examhelper.shua.data.db.QuestionType.SINGLE,
            com.examhelper.shua.data.db.QuestionType.JUDGE ->
                _state.value = s.copy(selected = s.selected + (q.id to setOf(key)))
            com.examhelper.shua.data.db.QuestionType.MULTIPLE -> {
                val cur = s.selected[q.id] ?: emptySet()
                _state.value = s.copy(selected = s.selected + (q.id to (if (key in cur) cur - key else cur + key)))
            }
            else -> Unit
        }
    }

    fun updateText(v: String) {
        val q = _state.value.current ?: return
        _state.value = _state.value.copy(textInputs = _state.value.textInputs + (q.id to v))
    }

    fun next() { leaveCurrent(); _state.value = _state.value.copy(index = (_state.value.index + 1).coerceAtMost(_state.value.questions.size - 1)) }
    fun prev() { leaveCurrent(); _state.value = _state.value.copy(index = (_state.value.index - 1).coerceAtLeast(0)) }
    fun jumpTo(i: Int) { leaveCurrent(); _state.value = _state.value.copy(index = i.coerceIn(0, _state.value.questions.size - 1)) }

    /** 交卷：逐题判分入库（带 runId 与用时），返回报告 runId */
    fun submit(onSubmitted: (String) -> Unit) {
        if (_state.value.submitting) return
        _state.value = _state.value.copy(submitting = true)
        leaveCurrent()
        viewModelScope.launch {
            val s = _state.value
            s.questions.forEach { q ->
                val choice = s.selected[q.id]
                val text = s.textInputs[q.id].orEmpty()
                val userAnswer = when (q.type) {
                    com.examhelper.shua.data.db.QuestionType.SINGLE,
                    com.examhelper.shua.data.db.QuestionType.MULTIPLE,
                    com.examhelper.shua.data.db.QuestionType.JUDGE ->
                        choice?.sorted()?.joinToString("").orEmpty()
                    else -> text.take(200)
                }
                val correct = if (q.answer.isNullOrBlank()) false
                else StudyRepository.judgeAnswer(q, userAnswer.ifBlank { "∅" })
                studyRepo.submitAnswer(
                    bankId = bankId, question = q, mode = StudyMode.EXAM,
                    userAnswer = userAnswer, correct = correct,
                    runId = runId, durationMs = durations[q.id]
                )
            }
            _state.value = _state.value.copy(submittedRunId = runId)
            onSubmitted(runId)
        }
    }
}
