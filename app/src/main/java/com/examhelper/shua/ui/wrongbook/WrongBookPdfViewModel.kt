package com.examhelper.shua.ui.wrongbook

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.analysis.AnalysisLevel
import com.examhelper.shua.data.analysis.PdfAnalysisPlan
import com.examhelper.shua.data.analysis.PdfAnalysisWorker
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfAnswerPlacement
import com.examhelper.shua.data.model.PdfExportOptions
import com.examhelper.shua.data.model.PdfExportRequest
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.data.model.PdfExportSource
import com.examhelper.shua.data.model.PdfQuestion
import com.examhelper.shua.data.model.WrongBookExport
import com.examhelper.shua.data.pdf.PdfExportNaming
import com.examhelper.shua.data.pdf.WrongBookPdfRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    data class MaintenancePrompt(
        val level: AnalysisLevel,
        val questionIds: List<Long>,
        val existingCount: Int
    )

    data class UiState(
        val loading: Boolean = true,
        val source: PdfExportSource = PdfExportSource.UNMASTERED,
        val banks: List<Pair<Long, String>> = emptyList(),
        val selectedBankId: Long? = null,
        val questions: List<PdfQuestion> = emptyList(),
        val chapters: List<String> = emptyList(),
        val selectedChapters: Set<String> = emptySet(),
        val scope: PdfExportScope = PdfExportScope.BANK,
        val includeAnswers: Boolean = true,
        val answerMode: PdfAnswerMode = PdfAnswerMode.DEEP,
        val answerPlacement: PdfAnswerPlacement = PdfAnswerPlacement.AFTER_EACH,
        val maintenanceRunning: Boolean = false,
        val maintenanceLevel: AnalysisLevel? = null,
        val maintenanceMessage: String? = null,
        val maintenanceFailed: Int = 0,
        val maintenancePrompt: MaintenancePrompt? = null,
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

        fun selectedBankName(): String =
            banks.firstOrNull { it.first == selectedBankId }?.second.orEmpty()

        fun missingAnalysisCount(): Int = if (includeAnswers) {
            WrongBookExport(source, selectedQuestions()).missingAnalysisIds(answerMode).size
        } else {
            0
        }
    }

    private val _state = MutableStateFlow(UiState(source = source))
    val state: StateFlow<UiState> = _state
    private var questionEntities: Map<Long, Question> = emptyMap()
    private var maintenanceJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val questions = loadSourceQuestions()
        questionEntities = questions.associateBy { it.id }
        val banks = bankDao.observeAll().first()
        val presentBankIds = questions.map { it.bankId }.distinct()
        val names = presentBankIds.mapNotNull { id ->
            banks.firstOrNull { it.id == id }?.let { id to it.name }
        }.sortedBy { it.second }
        val firstBank = names.firstOrNull()?.first
        val pdfQuestions = questions.map { it.toPdfQuestion() }
        val chapters = pdfQuestions.filter { it.bankId == firstBank }
            .mapNotNull { it.chapter?.takeIf(String::isNotBlank) }
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
        firstBank?.let(::observeMaintenance)
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
            .mapNotNull { it.chapter?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
        _state.value = _state.value.copy(
            selectedBankId = bankId,
            chapters = chapters,
            selectedChapters = emptySet()
        )
        observeMaintenance(bankId)
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

    fun setAnswerPlacement(placement: PdfAnswerPlacement) {
        _state.value = _state.value.copy(answerPlacement = placement)
    }

    fun setOnlyQuestions(onlyQuestions: Boolean) {
        _state.value = _state.value.copy(includeAnswers = !onlyQuestions)
    }

    fun startDeepSolve() = requestMaintenance(AnalysisLevel.DEEP)

    fun startMinimalSolve() = requestMaintenance(AnalysisLevel.MINIMAL)

    fun cancelMaintenance() {
        val bankId = _state.value.selectedBankId ?: return
        PdfAnalysisWorker.cancel(getApplication(), source.name, bankId)
        _state.value = _state.value.copy(
            maintenanceRunning = false,
            maintenanceMessage = "已停止当前生成任务"
        )
    }

    private fun requestMaintenance(level: AnalysisLevel) {
        if (_state.value.maintenanceRunning) return
        val selected = _state.value.selectedQuestions()
        if (selected.isEmpty()) return
        val ids = selected.map { it.id }
        val existing = PdfAnalysisPlan.create(
            ids.mapNotNull(questionEntities::get),
            level,
            skipExisting = true
        ).existingCount
        if (existing > 0) {
            _state.value = _state.value.copy(
                maintenancePrompt = MaintenancePrompt(level, ids, existing)
            )
        } else {
            launchMaintenance(level, ids, forceAll = false)
        }
    }

    fun confirmMaintenance(regenerate: Boolean) {
        val prompt = _state.value.maintenancePrompt ?: return
        _state.value = _state.value.copy(maintenancePrompt = null)
        val plan = PdfAnalysisPlan.create(
            prompt.questionIds.mapNotNull(questionEntities::get),
            prompt.level,
            skipExisting = !regenerate
        )
        if (plan.targets.isEmpty()) {
            _state.value = _state.value.copy(
                maintenanceMessage = "所选题目都已有所需解析，本次未生成"
            )
            return
        }
        launchMaintenance(prompt.level, plan.targets.map { it.id }, forceAll = regenerate)
    }

    fun dismissMaintenancePrompt() {
        _state.value = _state.value.copy(maintenancePrompt = null)
    }

    private fun launchMaintenance(level: AnalysisLevel, ids: List<Long>, forceAll: Boolean) {
        val bankId = _state.value.selectedBankId ?: return
        val name = PdfAnalysisWorker.enqueue(
            context = getApplication(),
            source = source.name,
            bankId = bankId,
            questionIds = ids,
            level = level,
            forceAll = forceAll
        )
        _state.value = _state.value.copy(
            maintenanceRunning = true,
            maintenanceLevel = level,
            maintenanceMessage = "已转入后台维护，切换页面不会中断",
            maintenanceFailed = 0
        )
        observeWorker(name)
    }

    private fun observeMaintenance(bankId: Long) {
        observeWorker(PdfAnalysisWorker.uniqueName(source.name, bankId))
    }

    private fun observeWorker(name: String) {
        maintenanceJob?.cancel()
        maintenanceJob = viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(name)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    if (info == null) {
                        _state.value = _state.value.copy(maintenanceRunning = false)
                        return@collect
                    }
                    val level = info.progress.getString(PdfAnalysisWorker.KEY_LEVEL)
                        ?.let { runCatching { AnalysisLevel.valueOf(it) }.getOrNull() }
                    val running = info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                    val message = when (info.state) {
                        WorkInfo.State.CANCELLED -> "已停止当前生成任务"
                        WorkInfo.State.FAILED -> info.progress.getString(PdfAnalysisWorker.KEY_MESSAGE)
                            ?: "题目解析维护失败，可重试"
                        else -> info.progress.getString(PdfAnalysisWorker.KEY_MESSAGE)
                    } ?: when (info.state) {
                            WorkInfo.State.ENQUEUED -> "等待后台开始…"
                            WorkInfo.State.RUNNING -> "后台维护中…"
                            WorkInfo.State.SUCCEEDED -> "题目解析维护完成"
                            else -> null
                        }
                    _state.value = _state.value.copy(
                        maintenanceRunning = running,
                        maintenanceLevel = level ?: _state.value.maintenanceLevel,
                        maintenanceMessage = message,
                        maintenanceFailed = info.progress.getInt(PdfAnalysisWorker.KEY_FAILED, 0)
                    )
                    if (info.state.isFinished) refreshAnalysisQuestions()
                }
        }
    }

    private suspend fun refreshAnalysisQuestions() {
        val ids = questionEntities.keys.toList()
        if (ids.isEmpty()) return
        questionEntities = questionDao.getByIds(ids).associateBy { it.id }
        _state.value = _state.value.copy(
            questions = _state.value.questions.mapNotNull { old ->
                questionEntities[old.id]?.toPdfQuestion()
            }
        )
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
                    bankName = bankName,
                    export = WrongBookExport(_state.value.source, selected),
                    options = PdfExportOptions(
                        includeAnswers = _state.value.includeAnswers,
                        mode = _state.value.answerMode,
                        placement = _state.value.answerPlacement
                    ),
                    sansFont = context.assets.open("pdf/NotoSansSC-Bold.ttf"),
                    serifFont = context.assets.open("pdf/NotoSerifSC-Regular.ttf"),
                    notice = buildList {
                        val failed = _state.value.maintenanceFailed
                        if (failed > 0) {
                            val label = if (_state.value.maintenanceLevel == AnalysisLevel.DEEP) {
                                "深度解析"
                            } else {
                                "极简解析"
                            }
                            add("本次${label}失败 $failed 道")
                        }
                    }.takeIf { it.isNotEmpty() }
                        ?.joinToString("；", postfix = "，缺失解析在对应题目中标注为“无”。")
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
        return PdfExportNaming.fileName(name)
    }

    private fun Question.toPdfQuestion(): PdfQuestion {
        val opts = JsonCodec.decodeOptions(optionsJson)
            .ifEmpty { JsonCodec.decodeOptions(sharedOptionsJson) }
        return PdfQuestion(
            id = id,
            bankId = bankId,
            chapter = chapter,
            type = type.name,
            stem = stem,
            options = opts.map { "${it.key}. ${it.text}" },
            answer = answer,
            analysis = analysis,
            deepSolved = deepSolved,
            minimalSolved = minimalSolved
        )
    }
}
