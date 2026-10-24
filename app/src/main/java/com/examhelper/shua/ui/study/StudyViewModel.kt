package com.examhelper.shua.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.StudyMode
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
    /** 已作答（选项题已提交 / 主观题已自评） */
    val answered: Boolean = false,
    /** 判分结果：null=未判分或无参考答案 */
    val correct: Boolean? = null,
    val selected: Set<String> = emptySet(),
    val textInput: String = "",
    /** 参考答案与解析是否已展示 */
    val revealed: Boolean = false,
    /** 速刷倒计时剩余秒数；null=不在倒计时 */
    val countdown: Int? = null,
    val favorite: Boolean = false
)

class StudyViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    private val mode: StudyMode = runCatching {
        StudyMode.valueOf(savedStateHandle.get<String>("mode") ?: "SEQUENTIAL")
    }.getOrDefault(StudyMode.SEQUENTIAL)

    private val bankRepo: BankRepository = ShuaApp.instance.container.bankRepo
    private val studyRepo: StudyRepository = ShuaApp.instance.container.studyRepo
    private val settingsStore: SettingsStore = ShuaApp.instance.container.settings

    private val _state = MutableStateFlow(StudyUiState(mode = mode))
    val state: StateFlow<StudyUiState> = _state

    private var questions: List<Question> = emptyList()
    private var appSettings: AppSettings = AppSettings()
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            appSettings = settingsStore.settings.first()
            reload()
        }
    }

    private suspend fun reload() {
        val bank = bankRepo.getBank(bankId)
        questions = bankRepo.getQuestions(bankId)
        val session = studyRepo.currentSession(bankId, mode, questions)
        _state.value = StudyUiState(
            loading = false,
            bankName = bank?.name.orEmpty(),
            mode = mode,
            session = session,
            revealed = mode == StudyMode.RECITE,
            favorite = session.question?.let { studyRepo.isFavorite(it.id) } ?: false
        )
    }

    /** 选项点击：单选/判断立即判分；多选切换选中 */
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

    /** 多选确认提交 */
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
        _state.value = s.copy(
            answered = true,
            correct = correct,
            revealed = true,
            selected = selected
        )
        if (hasAnswer) {
            viewModelScope.launch {
                studyRepo.submitAnswer(bankId, q, mode, answerStr, correct == true)
            }
        }
        if (mode == StudyMode.SPEED) beginCountdown(appSettings.speedModeSeconds)
    }

    fun updateTextInput(v: String) {
        _state.value = _state.value.copy(textInput = v)
    }

    /** 填空/简答：对照参考答案 */
    fun revealReference() {
        val s = _state.value
        if (s.session?.question == null || s.revealed) return
        _state.value = s.copy(revealed = true)
    }

    /** 填空/简答自评 */
    fun selfRate(correct: Boolean) {
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.answered) return
        viewModelScope.launch {
            studyRepo.submitAnswer(
                bankId = bankId,
                question = q,
                mode = mode,
                userAnswer = s.textInput.take(200),
                correct = correct,
                selfRated = true
            )
            _state.value = _state.value.copy(answered = true, correct = correct)
            if (mode == StudyMode.SPEED) beginCountdown(appSettings.speedModeSeconds)
        }
    }

    /** 下一题（未作答则视为跳过，不记录） */
    fun next() {
        countdownJob?.cancel()
        val s = _state.value
        val finished = s.session?.finished == true
        if (!finished) {
            viewModelScope.launch {
                studyRepo.advance(bankId, mode)
                reload()
            }
        }
    }

    /** 上一题（随机巩固无此功能） */
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

    fun resetRandom() {
        countdownJob?.cancel()
        viewModelScope.launch {
            studyRepo.resetRandom(bankId, questions.map { it.id })
            reload()
        }
    }

    /** 顺序类模式从头再来 */
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

    /** 用户点击暂停速刷倒计时 */
    fun pauseCountdown() {
        countdownJob?.cancel()
        _state.value = _state.value.copy(countdown = null)
    }
}
