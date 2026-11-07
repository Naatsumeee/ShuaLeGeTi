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
    /** 深度解题流式正文（逐字增长，完成后并入解析并清空） */
    val deepStream: String = "",
    /** 深度解题流式思维链（推理型模型先吐思考过程） */
    val deepReasoning: String = "",
    /** 追问面板是否展开 */
    val askOpen: Boolean = false,
    /** 当前题目的追问对话（切题即清空） */
    val chat: List<ChatTurn> = emptyList(),
    /** 追问请求进行中 */
    val asking: Boolean = false,
    /** 追问流式正文（逐字增长，完成后并入 chat） */
    val askStream: String = "",
    /** 追问失败原因 */
    val askError: String? = null,
    /** 追问所用模型名（面板上明示走的是哪个模型，空 = 未配置） */
    val askModelName: String? = null,
    /** 本轮做错的题数（完成页「刷本轮错题」按钮用） */
    val roundWrongCount: Int = 0,
    /** 当前是否已在「刷本轮错题」的子会话里 */
    val roundWrongOnly: Boolean = false
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

/** 兼容模型误用批量结构 {"results":[...]} 的情况 */
@Serializable
private data class DeepSolveBatchPayload(
    val results: List<DeepSolvePayload> = emptyList()
)

/**
 * 深度解题的输出预算。
 *
 * 推理型模型（deepseek-v4-pro / reasoner 一类）会先花预算写思维链，正文才轮到输出。
 * 2600 在带 Markdown 章节的深度解析上时常不够，会出现「正文被砍半」或「只有思考没有正文」，
 * 用户侧表现就是"概率性生成失败"。4096 兼顾完整性；仍被截断时上层会重试。
 */
private const val DEEP_SOLVE_MAX_TOKENS = 4096

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

    /** 本轮做错的题号集合（完成页「刷本轮错题」用；开始新一轮即清空） */
    private var roundWrongIds: MutableSet<Long> = mutableSetOf()

    /** 非空 = 当前在「刷本轮错题」子会话里，题目只取这些 id */
    private var roundWrongFilter: Set<Long>? = null

    /** 主轮总题数（错题子会话结束后要回到主轮完成页，得知道它是几题） */
    private var mainRoundTotal: Int = 0

    init {
        viewModelScope.launch {
            appSettings = settingsStore.settings.first()
            reload()
        }
    }

    private suspend fun reload() {
        val bank = bankRepo.getBank(bankId)
        val base = studyRepo.filteredQuestions(bankId, filter)
        // 「刷本轮错题」子会话：题目只取本轮错的那几道，进度指纹也换成独立的，
        // 这样它自己是一轮新的进度，不会污染/续上原来的整轮进度。
        val wrongOnly = roundWrongFilter
        questions = if (wrongOnly == null) base else base.filter { it.id in wrongOnly }
        if (wrongOnly == null && mainRoundTotal == 0) mainRoundTotal = questions.size
        val sessionFilter = if (wrongOnly == null) filterJson else roundFilterKey()
        val session = studyRepo.currentSession(bankId, mode, questions, sessionFilter)
        // 这轮已经刷完（游标推到队尾）→ 立刻清掉这条进度。
        // 否则范围页会一直挂着「第 30 / 30 题 · 继续」，点进去还是个空页。
        // 也顺带清掉进程被杀后残留的这种"已完成"进度。
        if (session.finished && questions.isNotEmpty()) {
            runCatching { studyRepo.clearRound(bankId, mode) }
        }
        questionShownAt = System.currentTimeMillis()
        // 记住已作答结果：划回做过的题不再要求重答。
        // freshAnswer = true 时不还原（错题本重刷/抽检要求重新作答），
        // 否则一进来就是满屏解析，用户根本没机会答。
        val q = session.question
        val last = if (filter?.freshAnswer == true) null
        else q?.let { runCatching { studyRepo.latestAttempt(it.id) }.getOrNull() }
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
            roundWrongCount = roundWrongIds.size,
            roundWrongOnly = roundWrongFilter != null,
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
        // 震动反馈：答对/答错微振动（设置里可关）
        if (correct == true) Haptics.ok() else Haptics.ng()
        // 记进「本轮错题」：完成页据此提供「刷本轮错题」入口
        if (correct == false) roundWrongIds.add(q.id)
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
            if (!correct) roundWrongIds.add(q.id)
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
        roundWrongFilter = null
        roundWrongIds = mutableSetOf()
        viewModelScope.launch {
            studyRepo.seek(bankId, mode, 0)
            reload()
        }
    }

    /**
     * 只刷本轮做错的题。
     *
     * 用独立进度指纹开一条子会话（而不是复用原进度）：
     * ① 原进度在本轮结束时已被 clearRound 删掉，硬塞回去会让「上次进度」列表复活；
     * ② 错题子会话自己刷完就该自然消失，不该留痕。
     */
    fun brushRoundWrong() {
        val ids = roundWrongIds.toSet()
        if (ids.isEmpty()) return
        countdownJob?.cancel()
        Haptics.tap()
        roundWrongFilter = ids
        roundWrongIds = mutableSetOf()
        viewModelScope.launch {
            studyRepo.resetRoundWrong(bankId, mode, roundFilterKey())
            reload()
        }
    }

    /**
     * 错题子会话也刷完、用户点「完成」离开：只清掉这条子进度，不 reload。
     * reload 会让主轮进度被 ensureProgress 重建成第 1 题，页面闪一下再被导航销毁。
     */
    fun dismissRoundWrong() {
        countdownJob?.cancel()
        roundWrongFilter = null
        viewModelScope.launch { runCatching { studyRepo.clearRound(bankId, mode) } }
    }

    private fun roundFilterKey(): String {
        val ids = roundWrongFilter.orEmpty().sorted().joinToString(",")
        return """{"roundWrong":[$ids]}"""
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
        _state.value = s.copy(deepSolving = true, deepError = null, deepStream = "", deepReasoning = "")
        Haptics.tap()
        viewModelScope.launch {
            val result = requestDeepSolveWithRetry(q)
            result.fold(
                onSuccess = { payload ->
                    val analysis = payload.analysis?.takeIf { it.isNotBlank() }
                    if (analysis == null) {
                        _state.value = _state.value.copy(
                            deepSolving = false,
                            deepError = "模型未返回解析内容，请重试",
                            deepStream = "",
                            deepReasoning = ""
                        )
                    } else {
                        // 深度解题给出的答案回写为最终答案（与最初解析不一致时以它为准）
                        val updated = studyRepo.applyDeepSolve(q, analysis, payload.answer)
                        // 同步内存里的这道题，否则页面仍显示旧解析（deepSolve 后要立刻读到新内容）
                        questions = questions.map { if (it.id == updated.id) updated else it }
                        // 只有还停在原来那道题时才回填，否则请求期间的切题会被旧结果覆盖
                        val stillHere = _state.value.session?.question?.id == q.id
                        _state.value = _state.value.copy(
                            deepSolving = false,
                            deepSolved = if (stillHere) true else _state.value.deepSolved,
                            deepError = null,
                            deepStream = "",
                            deepReasoning = "",
                            // 深度解题完成后**不要**自动弹出追问面板：解析刚生成，用户要先读。
                            // 追问入口常驻在解析下方（DeepSolveRow），想接着问点一下即可。
                            session = if (stillHere) {
                                _state.value.session?.copy(question = updated)
                            } else {
                                _state.value.session
                            }
                        )
                        Haptics.ok()
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        deepSolving = false,
                        deepError = e.message?.take(120) ?: "深度解题失败",
                        // 失败时保留已收到的片段，用户能看到模型到底吐了什么，便于判断是否该重试
                        deepStream = _state.value.deepStream,
                        deepReasoning = _state.value.deepReasoning
                    )
                }
            )
        }
    }

    /**
     * 深度解题重试包装。
     *
     * 「概率性生成失败」的真实成因是一批**非确定性**失败：模型偶发返回空内容
     * （推理型模型把 token 先花在思考上，被 max_tokens 截断后 content 为空）、
     * 网关偶发 5xx / 连接被重置、长回答撞上读超时。这些全都换一次请求就能过，
     * 直接抛给用户就表现为「有时能生成、有时不能」。所以这里做 3 次带退避的重试，
     * 只对「确定不该重试」的错误（未配置模型、鉴权失败、参数错误）立即放弃。
     */
    private suspend fun requestDeepSolveWithRetry(q: Question): Result<DeepSolvePayload> {
        var lastError: Throwable? = null
        val delays = longArrayOf(0L, 900L, 2200L)
        for ((attempt, wait) in delays.withIndex()) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            // 每次重试都清空上一轮的流式片段，否则界面会把两次尝试的文字接在一起
            _state.value = _state.value.copy(deepStream = "", deepReasoning = "")
            val r = runCatching { requestDeepSolve(q) }
            val payload = r.getOrNull()
            if (payload != null && !payload.analysis.isNullOrBlank()) return r
            val err = r.exceptionOrNull()
                ?: IllegalStateException("模型未返回解析内容（第 ${attempt + 1} 次尝试）")
            lastError = err
            if (!isRetryable(err)) break
        }
        return Result.failure(lastError ?: IllegalStateException("深度解题失败"))
    }

    /** 鉴权 / 配置 / 参数类错误重试多少次都一样，立刻失败（省时也省钱） */
    private fun isRetryable(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        if (msg.contains("尚未配置 AI 模型")) return false
        if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) return false
        if (msg.contains("HTTP 400") || msg.contains("HTTP 404") || msg.contains("HTTP 422")) return false
        if (msg.contains("无效") && msg.contains("API Key")) return false
        // 余额不足 / 鉴权失败这类错误重复请求没有意义
        if (msg.contains("Insufficient Balance") || msg.contains("insufficient_quota")) return false
        if (msg.contains("Authentication") || msg.contains("Unauthorized")) return false
        return true
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
        // 深度解题用「单题 Markdown」提示词（用户明确要求输出 Markdown，不再强制 JSON）。
        // 早先这里错把「批量生成答案」的自定义提示词（长度>200 即采用）拿来复用，
        // 而它要求模型输出 {"results":[...]}，解析成 DeepSolvePayload 时 analysis 必为 null，
        // 于是永远报「模型未返回解析内容」——那曾是该 bug 的根因。
        val system = com.examhelper.shua.data.llm.Prompts.DEEP_SOLVE_DEFAULT
        // 流式输出：推理型模型会先吐一段思维链（reasoning_content），正文随后逐字到达。
        // 之前是「等整包返回」，模型思考 1-3 分钟期间界面只有一个转圈，用户以为卡死；
        // 现在思考和正文都实时上屏，等待感显著下降，也不再需要靠「猜」判断是否在跑。
        //
        // 状态更新用 40ms 节流：SSE 每帧都写一次 StateFlow 会触发大量重组，
        // 在低端机上反而更卡；40ms≈25fps 的观感已经足够「逐字」。
        val streamBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        var lastPush = 0L
        fun push(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPush < 40L) return
            lastPush = now
            _state.value = _state.value.copy(
                deepStream = streamBuf.toString(),
                deepReasoning = reasoningBuf.toString()
            )
        }
        val result = llmClient.chatStream(
            provider = provider,
            systemPrompt = system,
            userPrompt = prompt,
            jsonMode = false,
            maxTokens = DEEP_SOLVE_MAX_TOKENS,
            onReasoning = { delta ->
                reasoningBuf.append(delta)
                push()
            },
            onDelta = { delta ->
                streamBuf.append(delta)
                push()
            }
        )
        push(force = true)
        val payload = parseDeepSolve(result.content)
        // 被 max_tokens 截断时解析必然是残缺的，直接判失败让上层重试，
        // 绝不能把半截解析写库覆盖原解析（那比报错更糟，用户会以为解析就该这么短）。
        if (result.truncated && payload.analysis.isNullOrBlank()) {
            error("模型输出被长度限制截断，请重试")
        }
        return payload
    }

    /**
     * 容错解析深度解题返回。
     *
     * 模型（尤其非 DeepSeek 的兼容端点）常常不严格输出 {"analysis":...}：
     * 可能包在代码块里、可能输出 {"results":[{...}]}、也可能干脆只吐一段纯文本解析。
     * 这几种都要能取到解析，否则用户会反复看到「模型未返回解析内容」。
     */
    private val NL_JOIN = "\n"

    private fun parseDeepSolve(raw: String): DeepSolvePayload {
        val cleaned = raw.trim()
            .removePrefix("```markdown").removePrefix("```md").removePrefix("```")
            .removeSuffix("```").trim()
        if (cleaned.isEmpty()) return DeepSolvePayload(null, null, null)

        // 新格式（Markdown）：首行「答案：X」，其余整段是解析。
        // 但历史数据 / 部分兼容端点仍可能回 JSON，所以先试 JSON，再按 Markdown 解析。
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))
        if (start >= 0 && end > start) {
            val body = cleaned.substring(start, end + 1)
            runCatching { json.decodeFromString<DeepSolvePayload>(body) }
                .getOrNull()
                ?.takeIf { !it.analysis.isNullOrBlank() }
                ?.let { return it }
            runCatching {
                json.decodeFromString<DeepSolveBatchPayload>(body).results.firstOrNull()
            }.getOrNull()
                ?.takeIf { !it.analysis.isNullOrBlank() }
                ?.let { return it }
        }

        // Markdown 正文：取首行的「答案：…」，剩下的作为解析
        val lines = cleaned.lines()
        val answerRe = Regex("""^\s*(?:\*\*)?(?:最终)?答案(?:\*\*)?\s*[:：]\s*(.+)$""")
        var answer: String? = null
        var consumed = 0
        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) {
                if (answer != null) { consumed = idx + 1; break }
                continue
            }
            val m = answerRe.find(line)
            if (m != null) {
                answer = m.groupValues[1].trim().trim('*', ' ', '`', '。')
                consumed = idx + 1
                break
            }
            // 首行不是答案行，说明模型没按格式走，整段当解析
            break
        }
        val body = lines.drop(consumed).joinToString(NL_JOIN).trim().ifBlank { cleaned }
        return DeepSolvePayload(
            answer = answer?.takeIf { it.isNotBlank() },
            analysis = body.takeIf { it.isNotBlank() },
            confidence = null
        )
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
        _state.value = s.copy(chat = history, asking = true, askError = null, askOpen = true, askStream = "")
        Haptics.tap()
        // 记下提问时的题目 id：请求途中切题的话，回答不能落到新题目头上
        val askedForId = q.id
        viewModelScope.launch {
            val buf = StringBuilder()
            var lastPush = 0L
            fun push(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastPush < 40L) return
                lastPush = now
                if (_state.value.session?.question?.id != askedForId) return
                _state.value = _state.value.copy(askStream = buf.toString())
            }
            runCatching {
                requestAsk(q, history) { delta ->
                    buf.append(delta)
                    push()
                }
            }.fold(
                onSuccess = { reply ->
                    if (_state.value.session?.question?.id != askedForId) return@fold
                    _state.value = _state.value.copy(
                        chat = _state.value.chat + ChatTurn("assistant", reply),
                        asking = false,
                        askError = null,
                        askStream = ""
                    )
                },
                onFailure = { e ->
                    if (_state.value.session?.question?.id != askedForId) return@fold
                    // 失败时把这条提问留在列表里（用户能改改再问），并给出原因
                    _state.value = _state.value.copy(
                        asking = false,
                        askError = e.message?.take(140) ?: "追问失败，请重试",
                        askStream = ""
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

    /** 当前题目 + 对话轮次 → 消息列表（流式：边收边通过 [onDelta] 上屏） */
    private suspend fun requestAsk(
        q: Question,
        history: List<ChatTurn>,
        onDelta: (String) -> Unit = {}
    ): String {
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
        return llmClient.chatStreamMessages(
            provider = provider,
            messages = messages,
            jsonMode = false,
            maxTokens = 1500,
            onDelta = onDelta
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
        com.examhelper.shua.data.db.QuestionType.TERM -> "名词解释题"
    }
}
