package com.examhelper.shua.ui.wrongbook

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.llm.ChatMessage
import com.examhelper.shua.data.llm.Prompts
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfExportRequest
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.data.model.PdfExportSource
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import com.examhelper.shua.data.pdf.WrongBookPdfRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class WrongBookPdfViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val source = runCatching {
        PdfExportSource.valueOf(savedStateHandle.get<String>("source").orEmpty())
    }.getOrDefault(PdfExportSource.UNMASTERED)
    private val container = (application as ShuaApp).container
    private val questionDao = container.database.questionDao()
    private val bankDao = container.database.bankDao()
    private val recordDao = container.database.recordDao()
    private val providerDao = container.database.providerDao()
    private val llmClient = container.llmClient
    private val settings = container.settings

    data class UiState(
        val loading: Boolean = true,
        val source: PdfExportSource = PdfExportSource.UNMASTERED,
        val banks: List<Pair<Long, String>> = emptyList(),
        val selectedBankId: Long? = null,
        val questions: List<PdfQuestion> = emptyList(),
        val chapters: List<String> = emptyList(),
        val selectedChapters: Set<String> = emptySet(),
        val scope: PdfExportScope = PdfExportScope.BANK,
        val answerMode: PdfAnswerMode = PdfAnswerMode.DEEP,
        val deepRunning: Boolean = false,
        val deepMessage: String? = null,
        val deepFailed: Set<Long> = emptySet(),
        val generating: Boolean = false,
        val message: String? = null,
        val error: String? = null
    ) {
        fun selectedQuestions(): List<PdfQuestion> {
            val bankId = selectedBankId ?: return emptyList()
            return WrongBookExport(source, questions).select(
                PdfExportRequest(bankId, scope, selectedChapters)
            )
        }

        fun selectedBankName(): String = banks.firstOrNull { it.first == selectedBankId }?.second.orEmpty()
    }

    private val _state = MutableStateFlow(UiState(source = source))
    val state: StateFlow<UiState> = _state
    private var questionEntities: Map<Long, Question> = emptyMap()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val questions = loadSourceQuestions()
        questionEntities = questions.associateBy { it.id }
        val banks = bankDao.observeAll().first()
        val presentBankIds = questions.map { it.bankId }.distinct()
        val names = presentBankIds.mapNotNull { id -> banks.firstOrNull { it.id == id }?.let { id to it.name } }
            .sortedBy { it.second }
        val firstBank = names.firstOrNull()?.first
        val pdfQuestions = questions.map { it.toPdfQuestion() }
        val chapters = pdfQuestions.filter { it.bankId == firstBank }
            .mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sorted()
        _state.value = UiState(
            loading = false,
            source = source,
            banks = names,
            selectedBankId = firstBank,
            questions = pdfQuestions,
            chapters = chapters
        )
    }

    private suspend fun loadSourceQuestions(): List<Question> {
        val ids = when (source) {
            PdfExportSource.UNMASTERED -> recordDao.observeWrongBook().first().map { it.questionId }
            PdfExportSource.MASTERED -> recordDao.observeMasteredWrong().first().map { it.questionId }
            PdfExportSource.FLAGGED -> recordDao.observeFlaggedWrong().first().map { it.questionId }
            PdfExportSource.FAVORITES -> recordDao.observeFavorites().first().map { it.questionId }
            PdfExportSource.KILLED -> recordDao.observeKills().first().map { it.questionId }
        }
        val byId = questionDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    fun selectBank(bankId: Long) {
        val chapters = _state.value.questions.filter { it.bankId == bankId }
            .mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sorted()
        _state.value = _state.value.copy(
            selectedBankId = bankId,
            chapters = chapters,
            selectedChapters = emptySet()
        )
    }

    fun setScope(scope: PdfExportScope) {
        _state.value = _state.value.copy(scope = scope)
    }

    fun toggleChapter(chapter: String) {
        val current = _state.value.selectedChapters
        _state.value = _state.value.copy(
            selectedChapters = if (chapter in current) current - chapter else current + chapter
        )
    }

    fun setAnswerMode(mode: PdfAnswerMode) {
        _state.value = _state.value.copy(answerMode = mode)
    }

    /** 只维护最终导出范围内的错题解析，不自动触发 PDF，也不动范围外题目。 */
    fun startDeepSolve() {
        val selected = _state.value.selectedQuestions()
        if (_state.value.deepRunning || selected.isEmpty()) return
        val pendingIds = selected.filterNot { it.deepSolved }.map { it.id }.toSet()
        if (pendingIds.isEmpty()) {
            _state.value = _state.value.copy(deepMessage = "所选错题都已经有深度解析")
            return
        }
        _state.value = _state.value.copy(deepRunning = true, deepMessage = "准备生成 ${pendingIds.size} 道题的深度解析", deepFailed = emptySet())
        viewModelScope.launch(Dispatchers.IO) {
            val provider = runCatching { resolveProvider() }.getOrElse { e ->
                _state.value = _state.value.copy(deepRunning = false, deepMessage = e.message ?: "未配置 AI 模型")
                return@launch
            }
            var done = 0
            val failed = mutableSetOf<Long>()
            for (q in selected) {
                if (q.deepSolved) continue
                val entity = questionEntities[q.id] ?: continue
                runCatching {
                    val result = llmClient.chatWithMessages(
                        provider = provider,
                        messages = listOf(
                            ChatMessage("system", Prompts.DEEP_SOLVE_DEFAULT),
                            ChatMessage("user", buildPrompt(entity))
                        ),
                        maxTokens = 4096,
                        reasoningEffort = settings.settings.first().reasoningEffort
                    )
                    val parsed = parseDeepSolve(result.content)
                    if (parsed.analysis.isNullOrBlank()) error("模型未返回解析")
                    val updated = container.studyRepo.applyDeepSolve(entity, parsed.analysis, parsed.answer)
                    questionEntities = questionEntities + (entity.id to updated)
                    _state.value = _state.value.copy(
                        questions = _state.value.questions.map { if (it.id == updated.id) updated.toPdfQuestion() else it }
                    )
                }.onFailure { failed += q.id }
                done++
                _state.value = _state.value.copy(
                    deepMessage = "深度解析：$done/${pendingIds.size}，失败 ${failed.size}"
                )
            }
            _state.value = _state.value.copy(
                deepRunning = false,
                deepFailed = failed,
                deepMessage = "已完成，失败 ${failed.size} 道；可再次点击只重试失败部分"
            )
        }
    }

    fun exportPdf(context: Context, uri: Uri) {
        val selected = _state.value.selectedQuestions()
        if (_state.value.generating || selected.isEmpty()) return
        _state.value = _state.value.copy(generating = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                PDFBoxResourceLoader.init(context.applicationContext)
                val bankName = _state.value.selectedBankName().ifBlank { "错题本" }
                val title = WrongBookExport.title(bankName, _state.value.selectedChapters.sorted())
                val bytes = WrongBookPdfRenderer.render(
                    title = title,
                    export = WrongBookExport(_state.value.source, selected),
                    mode = _state.value.answerMode,
                    sansFont = context.assets.open("pdf/NotoSansSC-Bold.ttf"),
                    serifFont = context.assets.open("pdf/NotoSerifSC-Regular.ttf"),
                    notice = _state.value.deepFailed.takeIf { it.isNotEmpty() }
                        ?.let { "本次深度解析失败 ${it.size} 道，缺失解析在对应题目中标注为“无”。" }
                )
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("无法写入 PDF")
            }.onSuccess {
                _state.value = _state.value.copy(generating = false, message = "PDF 已生成")
            }.onFailure {
                _state.value = _state.value.copy(generating = false, error = "PDF 生成失败：${it.message}")
            }
        }
    }

    fun suggestedName(): String {
        val name = _state.value.selectedBankName().ifBlank { "错题本" }
        val title = WrongBookExport.title(name, _state.value.selectedChapters.sorted())
        return title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80) + ".pdf"
    }

    private suspend fun resolveProvider() = providerDao.getPrimary()
        ?: providerDao.observeAll().first().firstOrNull { it.enabled }
        ?: error("尚未配置 AI 模型，请到设置页添加")

    private fun Question.toPdfQuestion(): PdfQuestion {
        val opts = JsonCodec.decodeOptions(optionsJson).ifEmpty { JsonCodec.decodeOptions(sharedOptionsJson) }
        return PdfQuestion(
            id = id,
            bankId = bankId,
            chapter = chapter,
            type = type.name,
            stem = stem,
            options = opts.map { "${it.key}. ${it.text}" },
            answer = answer,
            analysis = analysis,
            deepSolved = deepSolved
        )
    }

    private fun buildPrompt(q: Question): String = buildString {
        appendLine("题型：${q.type.name}")
        q.stem.takeIf { it.isNotBlank() }?.let { appendLine("题干：$it") }
        JsonCodec.decodeOptions(q.optionsJson).takeIf { it.isNotEmpty() }?.let { opts ->
            appendLine("选项：" + opts.joinToString("；") { "${it.key}.${it.text}" })
        }
        q.answer?.takeIf { it.isNotBlank() }?.let { appendLine("题库参考答案：$it") }
        q.analysis?.takeIf { it.isNotBlank() }?.let { appendLine("现有解析：${it.take(500)}") }
    }

    @Serializable
    private data class DeepReply(val answer: String? = null, val analysis: String? = null)

    private fun parseDeepSolve(raw: String): DeepReply {
        val text = raw.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
        if (text.isBlank()) return DeepReply()
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
        if (start >= 0 && end > start) {
            runCatching { JsonCodec.json.decodeFromString<DeepReply>(text.substring(start, end + 1)) }
                .getOrNull()?.let { if (!it.analysis.isNullOrBlank()) return it }
        }
        val lines = text.lines()
        val answer = lines.firstOrNull()?.let { Regex("""答案\s*[:：]\s*(.+)""").find(it)?.groupValues?.get(1)?.trim()?.trim('*', '`') }
        return DeepReply(answer, lines.drop(1).joinToString("\n").trim().ifBlank { text })
    }
}
