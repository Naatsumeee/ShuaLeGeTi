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
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.sound.SoundEffects
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val filterSummary: String? = null,
    /** 该题是否已深度解题过 */
    val deepSolved: Boolean = false,
    /** 深度解题进行中 */
    val deepSolving: Boolean = false,
    /** 深度解题失败原因 */
    val deepError: String? = null,
    /** 追问面板是否展开 */
    val askOpen: Boolean = false,
    /** 当前题目的追问对话（切题即清空） */
    val chat: List<ChatTurn> = emptyList(),
    /** 追问请求进行中 */
    val asking: Boolean = false,
    /** 追问失败原因 */
    val askError: String? = null,
    /** 追问所用模型名（面板上明示走的是哪个模型，空 = 未配置） */
    val askModelName: String? = null
)

/** 一条追问对话 */
data class ChatTurn(val role: String, val text: String) {
    val isUser: Boolean get() = role == "user"
}

/** 深度解题返回的 JSON 结构 */
@Serializable
private data class DeepSolvePayload(
    val answer: String? = null,
    val analysis: String? = null,
    val confidence: Int? = null
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
    private val providerDao = ShuaApp.instance.container.database.providerDao()
    private val llmClient = ShuaApp.instance.container.llmClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        // 记住已作答结果：划回做过的题不再要求重答
        val q = session.question
        val last = q?.let { runCatching { studyRepo.latestAttempt(it.id) }.getOrNull() }
        val restored = last != null && last.userAnswer.isNotBlank()
        val restoredLetters = when {
            !restored -> emptySet()
            q?.type == com.examhelper.shua.data.db.QuestionType.MULTIPLE ||
                q?.type == com.examhelper.shua.data.db.QuestionType.SINGLE ||
                q?.type == com.examhelper.shua.data.db.QuestionType.JUDGE ->
                last!!.userAnswer.filter { it.isLetter() }.map { it.uppercaseChar().toString() }.toSet()
            else -> emptySet()
        }
        _state.value = StudyUiState(
            loading = false,
            bankName = bank?.name.orEmpty(),
            mode = mode,
            session = session,
            answered = restored,
            correct = last?.correct,
            selected = restoredLetters,
            textInput = if (restored && restoredLetters.isEmpty()) last?.userAnswer.orEmpty() else "",
            revealed = restored || mode == StudyMode.RECITE,
            favorite = session.question?.let { studyRepo.isFavorite(it.id) } ?: false,
            killed = session.question?.let { studyRepo.isKilled(it.id) } ?: false,
            deepSolved = q?.deepSolved ?: false,
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
        Haptics.tap()
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
        // 音效反馈：答对/答错轻提示音 + 微振动（设置里可关）
        SoundEffects.verdict(correct)
        if (correct == true) Haptics.ok() else Haptics.ng()
        if (hasAnswer) {
            viewModelScope.launch {
                studyRepo.submitAnswer(
                    bankId = bankId, question = q, mode = mode,
                    userAnswer = answerStr, correct = correct == true,
                    durationMs = duration
                )
                // 作答即刷新进度时间戳：即便用户直接杀进程，下次也能看到准确时间
                runCatching { studyRepo.touchProgress(bankId, mode) }
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
            runCatching { studyRepo.touchProgress(bankId, mode) }
            _state.value = _state.value.copy(answered = true, correct = correct)
            SoundEffects.verdict(correct)
            if (correct) Haptics.ok() else Haptics.ng()
            if (mode == StudyMode.SPEED) beginCountdown(appSettings.speedModeSeconds)
        }
    }

    fun next() {
        countdownJob?.cancel()
        val s = _state.value
        Haptics.tick()
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
        Haptics.tick()
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

    /**
     * 深度解题：调 AI 详细分析，结果**替换原解析**并落库（deepSolved = true）。
     * 主部模型优先，没有配置模型时给明确提示。
     */
    fun deepSolve() {
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.deepSolving) return
        _state.value = s.copy(deepSolving = true, deepError = null)
        Haptics.tap()
        viewModelScope.launch {
            val result = runCatching { requestDeepSolve(q) }
            result.fold(
                onSuccess = { payload ->
                    val analysis = payload.analysis?.takeIf { it.isNotBlank() }
                    if (analysis == null) {
                        _state.value = _state.value.copy(
                            deepSolving = false,
                            deepError = "模型未返回解析内容，请重试"
                        )
                    } else {
                        val updated = studyRepo.applyDeepSolve(q, analysis)
                        // 同步内存里的这道题，否则页面仍显示旧解析（deepSolve 后要立刻读到新内容）
                        questions = questions.map { if (it.id == updated.id) updated else it }
                        _state.value = _state.value.copy(
                            deepSolving = false,
                            deepSolved = true,
                            deepError = null,
                            // 需求：深度解题完成后自动开启对话输入框，方便接着追问
                            askOpen = true,
                            session = _state.value.session?.copy(question = updated)
                        )
                        SoundEffects.verdict(true)
                        Haptics.ok()
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        deepSolving = false,
                        deepError = e.message?.take(120) ?: "深度解题失败"
                    )
                }
            )
        }
    }

    private suspend fun requestDeepSolve(q: Question): DeepSolvePayload {
        val provider = resolveProvider()
        val options = com.examhelper.shua.data.model.JsonCodec.decodeOptions(q.optionsJson)
        val prompt = buildString {
            appendLine("题型：${typeCn(q.type)}" + (q.subtype?.let { "（$it）" } ?: ""))
            q.sharedStem?.takeIf { it.isNotBlank() }?.let { appendLine("【共用题干】$it") }
            appendLine("题干：${q.stem}")
            val sharedOpts = q.sharedOptionsJson?.let {
                com.examhelper.shua.data.model.JsonCodec.decodeOptions(it)
            }.orEmpty()
            if (sharedOpts.isNotEmpty()) {
                appendLine("【共用选项】" + sharedOpts.joinToString("；") { "${it.key}.${it.text}" })
            }
            if (options.isNotEmpty()) {
                appendLine("选项：" + options.joinToString("；") { "${it.key}.${it.text}" })
            }
            val bank = q.answer?.takeIf { it.isNotBlank() }
            if (bank != null) appendLine("题库给出的参考答案：$bank（如你认为有误，请在解析中明确指出）")
            q.analysis?.takeIf { it.isNotBlank() }?.let {
                appendLine("现有简析（供参考，可推翻）：${it.take(300)}")
            }
        }
        val system = appSettings.customAnswerPrompt
            .takeIf { it.contains("深度") || it.length > 200 }
            ?.let { it }
            ?: com.examhelper.shua.data.llm.Prompts.DEEP_SOLVE_DEFAULT
        val raw = llmClient.chat(
            provider = provider,
            systemPrompt = system,
            userPrompt = prompt,
            jsonMode = true,
            maxTokens = 2000
        )
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return json.decodeFromString<DeepSolvePayload>(cleaned)
    }

    // ------------------------------------------------------------------
    // 问 AI：做题页常驻入口 + 深度解题后的连续追问
    // ------------------------------------------------------------------

    /** 展开 / 收起追问面板；展开时若还没有对话，先放一句开场提示 */
    fun toggleAsk() {
        val s = _state.value
        // 收起永远允许（哪怕题目已经切换/卸载），否则面板会卡在屏幕上关不掉
        if (s.askOpen) {
            Haptics.tap()
            _state.value = s.copy(askOpen = false, askError = null)
            return
        }
        if (s.session?.question == null) return
        Haptics.tap()
        _state.value = s.copy(askOpen = true, askError = null)
        // 面板上写明这次走哪个模型：用户配了多个模型时不该靠猜
        if (s.askModelName == null) {
            viewModelScope.launch {
                val name = resolveModelName()
                if (_state.value.askModelName == null) {
                    _state.value = _state.value.copy(askModelName = name)
                }
            }
        }
    }

    /**
     * 向模型追问当前题目。
     *
     * 上下文 = 系统提示（追问专用）+ 题目全文（题干/选项/参考答案/解析）+ 已有对话轮次，
     * 这样连续追问能接着上一轮说，而不是每问一次都从零开始。
     * 默认使用主部模型（未设置主部时退化为第一个启用中的模型）。
     */
    fun ask(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val s = _state.value
        val q = s.session?.question ?: return
        if (s.asking) return

        val history = s.chat + ChatTurn("user", text)
        _state.value = s.copy(chat = history, asking = true, askError = null, askOpen = true)
        Haptics.tap()
        // 记下提问时的题目 id：请求途中切题的话，回答不能落到新题目头上
        val askedForId = q.id
        viewModelScope.launch {
            runCatching { requestAsk(q, history) }.fold(
                onSuccess = { reply ->
                    if (_state.value.session?.question?.id != askedForId) return@fold
                    _state.value = _state.value.copy(
                        chat = _state.value.chat + ChatTurn("assistant", reply),
                        asking = false,
                        askError = null
                    )
                    SoundEffects.verdict(true)
                },
                onFailure = { e ->
                    if (_state.value.session?.question?.id != askedForId) return@fold
                    // 失败时把这条提问留在列表里（用户能改改再问），并给出原因
                    _state.value = _state.value.copy(
                        asking = false,
                        askError = e.message?.take(140) ?: "追问失败，请重试"
                    )
                }
            )
        }
    }

    /** 清空当前题目的追问记录 */
    fun clearAsk() {
        Haptics.tap()
        _state.value = _state.value.copy(chat = emptyList(), askError = null)
    }

    /** 当前题目 + 对话轮次 → 消息列表 */
    private suspend fun requestAsk(q: Question, history: List<ChatTurn>): String {
        val provider = resolveProvider()
        val system = com.examhelper.shua.data.llm.Prompts.ASK_DEFAULT
        val questionContext = buildString {
            appendLine("【当前题目】")
            appendLine("题型：${typeCn(q.type)}" + (q.subtype?.let { "（$it）" } ?: ""))
            q.sharedStem?.takeIf { it.isNotBlank() }?.let { appendLine("共用题干：$it") }
            appendLine("题干：${q.stem}")
            val opts = com.examhelper.shua.data.model.JsonCodec.decodeOptions(q.optionsJson)
            if (opts.isNotEmpty()) {
                appendLine("选项：" + opts.joinToString("；") { "${it.key}.${it.text}" })
            }
            q.answer?.takeIf { it.isNotBlank() }?.let { appendLine("参考答案：$it") }
            q.aiAnswer?.takeIf { it.isNotBlank() && !q.answerVerified }?.let { appendLine("AI 给出但存疑的答案：$it") }
            q.analysis?.takeIf { it.isNotBlank() }?.let { appendLine("现有解析：${it.take(1200)}") }
            val picked = _state.value.selected.sorted().joinToString("")
            if (_state.value.answered) {
                appendLine("学生的作答：${picked.ifBlank { _state.value.textInput.take(120) }}")
                _state.value.correct?.let { appendLine(if (it) "作答结果：正确" else "作答结果：错误") }
            }
        }
        val messages = buildList {
            add(com.examhelper.shua.data.llm.ChatMessage("system", system))
            add(com.examhelper.shua.data.llm.ChatMessage("user", questionContext))
            // 首轮把题目铺完，之后按真实轮次交替，模型才能"接着上一句"答
            history.forEach { turn ->
                val role = if (turn.isUser) "user" else "assistant"
                add(com.examhelper.shua.data.llm.ChatMessage(role, turn.text))
            }
        }
        return llmClient.chatWithMessages(
            provider = provider,
            messages = messages,
            jsonMode = false,
            maxTokens = 1500
        ).content.trim()
    }

    /** 主部模型优先；没配主部时退化为第一个启用中的模型 */
    private suspend fun resolveProvider(): com.examhelper.shua.data.db.LlmProvider {
        return providerDao.getPrimary()
            ?: providerDao.observeAll().first().firstOrNull { it.enabled }
            ?: error("尚未配置 AI 模型，请到设置页添加")
    }

    /** 读出追踪模型名给面板显示（失败不影响提问） */
    private suspend fun resolveModelName(): String? =
        runCatching { resolveProvider().let { "${it.name} · ${it.model}" } }.getOrNull()

    private fun typeCn(t: com.examhelper.shua.data.db.QuestionType): String = when (t) {
        com.examhelper.shua.data.db.QuestionType.SINGLE -> "单选题"
        com.examhelper.shua.data.db.QuestionType.MULTIPLE -> "多选题"
        com.examhelper.shua.data.db.QuestionType.JUDGE -> "判断题"
        com.examhelper.shua.data.db.QuestionType.FILL -> "填空题"
        com.examhelper.shua.data.db.QuestionType.SHORT_ANSWER -> "简答题"
    }
}
