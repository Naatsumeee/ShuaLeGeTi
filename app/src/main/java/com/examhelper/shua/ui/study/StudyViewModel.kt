package com.examhelper.shua.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.data.repo.StudySession
import com.examhelper.shua.data.settings.AppSettings
import com.examhelper.shua.data.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StudyUiState(
    val loading: Boolean = true,
    val bankName: String = "",
    val mode: StudyMode = StudyMode.SEQUENTIAL,
    val session: StudySession? = null,
    val answered: Boolean = false,
    val correct: Boolean? = null,
    val selected: Set<String> = emptySet(),
    val textInput: String = "",
    val revealed: Boolean = false,
    val countdown: Int? = null,
    val favorite: Boolean = false,
    val killed: Boolean = false,
    val filterSummary: String? = null
)

class StudyViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    private val mode: StudyMode = runCatching {
        StudyMode.valueOf(savedStateHandle.get<String>("mode") ?: "SEQUENTIAL")
    }.getOrDefault(StudyMode.SEQUENTIAL)
    private val filter: StudyFilter? =
        StudyFilter.fromFingerprint(savedStateHandle.get<String>("filter"))
    private val filterJson: String? = filter?.fingerprint()

    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo
    private val studyRepo: StudyRepository = ShuaApp.instance.container.studyRepo
    private val settingsStore: SettingsStore = ShuaApp.instance.container.settings

    private val _state = MutableStateFlow(StudyUiState(mode = mode))
    val state: StateFlow<StudyUiState> = _state

    private var questions: List<Question> = emptyList()
    private var appSettings: AppSettings = AppSettings()
    private var countdownJob: Job? = null
    private var questionShownAt: Long = 0L

    init {
        viewModelScope.launch {
            appSettings = settingsStore.settings.first()
            reload()
        }
    }

    private suspend fun reload() {
        val bank = bankRepo.getBank(bankId)
        questions = studyRepo.filteredQuestions(bankId, filter)
        val session = studyRepo.currentSession(bankId, mode, questions, filterJson)
        questionShownAt = System.currentTimeMillis()
        _state.value = StudyUiState(
            loading = false,
            bankName = bank?.name.orEmpty(),
            mode = mode,
            session = session,
            revealed = mode == StudyMode.RECITE,
            favorite = session.question?.let { studyRepo.isFavorite(it.id) } ?: false,
            killed = session.question?.let { studyRepo.isKilled(it.id) } ?: false,
            filterSummary = filter?.takeIf { !it.isDefault }?.let { f ->
                buildList {
                    if (f.chapters.isNotEmpty()) add("${f.chapters.size}章")
                    if (f.types.isNotEmpty()) add("${f.types.size}种题型")
                    if (f.subtypes.isNotEmpty()) add(f.subtypes.joinToString("/"))
                    StudyFilter.STATUS_OPTIONS.firstOrNull { it.first == f.status }?.second?.let { add(it) }
                }.joinToString("·")
            }
        )
    }

    private fun elapsedMs(): Long = System.currentTimeMillis() - questionShownAt

    fun onOptionTap(key: String) {
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.answered) return
        when (q.type) {
            com.examhelper.shua.data.db.QuestionType.SINGLE,
            com.examhelper.shua.data.db.QuestionType.JUDGE -> {
                if (s.selected.isNotEmpty()) return
                submitChoice(setOf(key))
            }
            com.examhelper.shua.data.db.QuestionType.MULTIPLE -> {
                _state.value = s.copy(
                    selected = if (key in s.selected) s.selected - key else s.selected + key
                )
            }
            else -> Unit
        }
    }

    fun confirmMultiple() {
        val s = _state.value
        if (s.selected.isEmpty() || s.answered) return
        submitChoice(s.selected)
    }

    private fun submitChoice(selected: Set<String>) {
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.answered) return
        val answerStr = selected.sorted().joinToString("")
        val hasAnswer = !q.answer.isNullOrBlank()
        val correct = if (hasAnswer) studyRepo.judge(q, answerStr) else null
        val duration = elapsedMs()
        _state.value = s.copy(
            answered = true,
            correct = correct,
            revealed = true,
            selected = selected
        )
        if (hasAnswer) {
            viewModelScope.launch {
                studyRepo.submitAnswer(
                    bankId = bankId, question = q, mode = mode,
                    userAnswer = answerStr, correct = correct == true,
                    durationMs = duration
                )
            }
        }
        if (mode == StudyMode.SPEED) beginCountdown(appSettings.speedModeSeconds)
    }

    fun updateTextInput(v: String) {
        _state.value = _state.value.copy(textInput = v)
    }

    fun revealReference() {
        val s = _state.value
        if (s.session?.question == null || s.revealed) return
        _state.value = s.copy(revealed = true)
    }

    fun selfRate(correct: Boolean) {
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.answered) return
        val duration = elapsedMs()
        viewModelScope.launch {
            studyRepo.submitAnswer(
                bankId = bankId, question = q, mode = mode,
                userAnswer = s.textInput.take(200),
                correct = correct, selfRated = true,
                durationMs = duration
            )
            _state.value = _state.value.copy(answered = true, correct = correct)
            if (mode == StudyMode.SPEED) beginCountdown(appSettings.speedModeSeconds)
        }
    }

    fun next() {
        countdownJob?.cancel()
        val s = _state.value
        if (s.session?.finished != true) {
            viewModelScope.launch {
                studyRepo.advance(bankId, mode)
                reload()
            }
        }
    }

    fun prev() {
        if (mode == StudyMode.RANDOM) return
        val index = (_state.value.session?.indexInQueue ?: 1) - 2
        if (index < 0) return
        countdownJob?.cancel()
        viewModelScope.launch {
            studyRepo.seek(bankId, mode, index)
            reload()
        }
    }

    /** 答题卡跳题（顺序类模式） */
    fun jumpTo(indexInQueue: Int) {
        if (mode == StudyMode.RANDOM) return
        countdownJob?.cancel()
        viewModelScope.launch {
            studyRepo.seek(bankId, mode, (indexInQueue - 1).coerceAtLeast(0))
            reload()
        }
    }

    fun resetRandom() {
        countdownJob?.cancel()
        viewModelScope.launch {
            studyRepo.resetRandom(bankId, questions.map { it.id })
            reload()
        }
    }

    fun restart() {
        countdownJob?.cancel()
        viewModelScope.launch {
            studyRepo.seek(bankId, mode, 0)
            reload()
        }
    }

    fun toggleFavorite() {
        val q = _state.value.session?.question ?: return
        viewModelScope.launch {
            val fav = studyRepo.toggleFavorite(q)
            _state.value = _state.value.copy(favorite = fav)
        }
    }

    /** 斩题：默认不再出现在"未斩"筛选里 */
    fun toggleKill() {
        val q = _state.value.session?.question ?: return
        viewModelScope.launch {
            val killed = studyRepo.toggleKill(q)
            _state.value = _state.value.copy(killed = killed)
        }
    }

    private fun beginCountdown(seconds: Int) {
        countdownJob?.cancel()
        _state.value = _state.value.copy(countdown = seconds)
        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                delay(1000)
                _state.value = _state.value.copy(countdown = i - 1)
            }
            next()
        }
    }

    fun pauseCountdown() {
        countdownJob?.cancel()
        _state.value = _state.value.copy(countdown = null)
    }
}
