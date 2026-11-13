package com.examhelper.shua.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StudySetupViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    val mode: StudyMode = runCatching {
        StudyMode.valueOf(savedStateHandle.get<String>("mode") ?: "SEQUENTIAL")
    }.getOrDefault(StudyMode.SEQUENTIAL)

    data class UiState(
        /** 题库组内的子题库；普通题库为空 */
        val groupBanks: List<QuestionBank> = emptyList(),
        val bank: QuestionBank? = null,
        val chapters: List<String> = emptyList(),
        val typesPresent: List<String> = emptyList(),
        val subtypesPresent: List<String> = emptyList(),
        val filter: StudyFilter = StudyFilter(),
        val matchCount: Int = 0,
        val examCount: Int = 30,
        /** 该题库下未做完的进度（倒序，用于「回到上次进度」） */
        val saved: List<StudyRepository.SavedProgress> = emptyList(),
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val container = ShuaApp.instance.container
    private var allCount = 0
    private var groupQuestions: List<com.examhelper.shua.data.db.Question> = emptyList()

    init {
        viewModelScope.launch {
            val bank = container.bankRepo.getBank(bankId)
            val questions = if (bank?.isGroup == true) {
                container.bankRepo.getQuestionsForCard(bank)
            } else {
                container.bankRepo.getQuestions(bankId)
            }
            allCount = questions.size
            groupQuestions = questions
            val groupBanks = if (bank?.isGroup == true) {
                container.bankRepo.getGroupBanks(bankId)
            } else emptyList()
            _state.value = UiState(
                bank = bank,
                groupBanks = groupBanks,
                chapters = questions.mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }.distinct(),
                typesPresent = questions.map { it.type.name }.distinct().sorted(),
                subtypesPresent = questions.mapNotNull { it.subtype }.distinct().sorted(),
                filter = StudyFilter(),
                matchCount = questions.size,
                saved = runCatching { container.studyRepo.savedProgress(bankId) }.getOrDefault(emptyList()),
                loading = false
            )
        }
    }

    /** 丢弃某条进度记录（用户不想再回到它） */
    fun forget(mode: StudyMode) {
        viewModelScope.launch {
            runCatching { container.studyRepo.resetProgress(bankId, mode) }
            _state.value = _state.value.copy(saved = _state.value.saved.filterNot { it.mode == mode })
        }
    }

    fun toggleBank(id: Long) {
        val current = _state.value.filter
        val next = if (id in current.bankIds) current.bankIds - id else current.bankIds + id
        val selected = if (next.isEmpty()) groupQuestions else groupQuestions.filter { it.bankId in next }
        _state.value = _state.value.copy(
            filter = current.copy(bankIds = next, chapters = emptyList(), types = emptyList(), subtypes = emptyList()),
            chapters = selected.mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }.distinct(),
            typesPresent = selected.map { it.type.name }.distinct().sorted(),
            subtypesPresent = selected.mapNotNull { it.subtype }.distinct().sorted()
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(matchCount = container.studyRepo.filteredQuestions(bankId, _state.value.filter.copy(bankIds = next, chapters = emptyList(), types = emptyList(), subtypes = emptyList())).size)
        }
    }

    fun selectAllBanks() {
        val ids = _state.value.groupBanks.map { it.id }
        _state.value = _state.value.copy(
            filter = _state.value.filter.copy(bankIds = ids, chapters = emptyList(), types = emptyList(), subtypes = emptyList()),
            chapters = groupQuestions.mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }.distinct(),
            typesPresent = groupQuestions.map { it.type.name }.distinct().sorted(),
            subtypesPresent = groupQuestions.mapNotNull { it.subtype }.distinct().sorted(),
            matchCount = groupQuestions.size
        )
    }

    fun toggleChapter(c: String) = mutateFilter {
        it.copy(chapters = toggle(it.chapters, c))
    }

    fun toggleType(t: String) = mutateFilter {
        it.copy(types = toggle(it.types, t))
    }

    fun toggleSubtype(s: String) = mutateFilter {
        it.copy(subtypes = toggle(it.subtypes, s))
    }

    fun setStatus(s: String) = mutateFilter { it.copy(status = s) }

    fun setExamCount(n: Int) {
        _state.value = _state.value.copy(examCount = n)
    }

    private fun <T> toggle(list: List<T>, item: T): List<T> =
        if (item in list) list - item else list + item

    private fun mutateFilter(mutate: (StudyFilter) -> StudyFilter) {
        val newFilter = mutate(_state.value.filter)
        _state.value = _state.value.copy(filter = newFilter)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                matchCount = container.studyRepo.filteredQuestions(bankId, newFilter).size
            )
        }
    }
}
