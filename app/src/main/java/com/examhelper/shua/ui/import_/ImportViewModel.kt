package com.examhelper.shua.ui.import_

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.ImportJob
import com.examhelper.shua.data.importjob.ImportPipelineWorker
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.model.toParsedQuestion
import com.examhelper.shua.data.parse.OptionNormalizer
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    enum class Stage { IDLE, CHOOSING_PARSE, RUNNING, PREVIEW, SAVING }

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val jobId: String? = null,
        val job: ImportJob? = null,
        val preview: List<ParsedLine> = emptyList(),
        val bankName: String = "",
        val sourceName: String = "",
        val pendingUri: Uri? = null,
        val error: String? = null,
        val providersReady: Boolean = false,
        val concurrency: Int = 4,
        /** 本次导入使用的 OCR 引擎 */
        val ocrEngine: String = com.examhelper.shua.data.settings.OcrEngine.ML_KIT,
        /** 分析过程日志（最新在下） */
        val log: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val container = (application as ShuaApp).container
    private val bankRepo = container.bankRepo
    private val providerDao = container.database.providerDao()
    private val jobDao = container.database.importJobDao()
    private val settings = container.settings

    /** 活跃任务（题库页 banner 用） */
    val activeJobs = jobDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val concurrency = settings.settings.first().parseConcurrency
            val providers = providerDao.observeAll().first()
            _state.value = _state.value.copy(
                concurrency = concurrency,
                providersReady = providers.any { it.enabled }
            )
            // 自动恢复进行中的任务（切走/重开后回来自动续上）
            jobDao.observeAll().first()
                .filter { it.stage == "INGEST" || it.stage == "PARSE" }
                .maxByOrNull { it.createdAt }
                ?.let { resumeJob(it.id) }
        }
    }

    /** 恢复某个已有任务（从题库 banner 点入） */
    fun resumeJob(jobId: String) {
        viewModelScope.launch {
            val job = jobDao.getById(jobId) ?: return@launch
            val preview = readPreview(job)
            _state.value = _state.value.copy(
                stage = when (job.stage) {
                    "DONE" -> Stage.PREVIEW
                    "FAILED", "CANCELED" -> if (preview.isEmpty()) Stage.RUNNING else Stage.PREVIEW
                    else -> Stage.RUNNING
                },
                jobId = jobId,
                job = job,
                preview = preview,
                bankName = job.bankName,
                sourceName = job.sourceName
            )
            observeJob(jobId)
        }
    }

    /** 选好文件后：等待用户选择解析方式 */
    fun filePicked(uri: Uri, name: String) {
        _state.value = _state.value.copy(
            stage = Stage.CHOOSING_PARSE,
            pendingUri = uri,
            sourceName = name,
            bankName = _state.value.bankName.ifBlank { name.substringBeforeLast('.') }
        )
    }

    fun updateBankName(v: String) {
        _state.value = _state.value.copy(bankName = v)
        _state.value.jobId?.let { jid ->
            viewModelScope.launch {
                jobDao.getById(jid)?.let { jobDao.upsert(it.copy(bankName = v)) }
            }
        }
    }

    fun cancelChoosing() {
        _state.value = UiState(providersReady = _state.value.providersReady, concurrency = _state.value.concurrency)
    }

    /** 启动后台流水线 */
    fun startPipeline(parseMode: String, ocrEngine: String = _state.value.ocrEngine) {
        val uri = _state.value.pendingUri ?: return
        val jobId = UUID.randomUUID().toString().take(18)
        val s = _state.value
        viewModelScope.launch {
            // 预写任务行，抢占唯一工作名
            jobDao.upsert(
                ImportJob(
                    id = jobId, bankName = s.bankName.ifBlank { "导入题库" },
                    sourceName = s.sourceName, stage = "INGEST"
                )
            )
            val provider = withContext(Dispatchers.IO) {
                providerDao.getPrimary() ?: providerDao.observeAll().first().firstOrNull { it.enabled }
            }
            ImportPipelineWorker.enqueue(
                context = getApplication(),
                jobId = jobId, uri = uri,
                bankName = s.bankName.ifBlank { "导入题库" },
                sourceName = s.sourceName,
                parseMode = parseMode,
                providerId = provider?.id,
                concurrency = s.concurrency,
                ocrEngine = ocrEngine
            )
            _state.value = _state.value.copy(stage = Stage.RUNNING, jobId = jobId, pendingUri = null)
            observeJob(jobId)
        }
    }

    fun setOcrEngine(v: String) {
        _state.value = _state.value.copy(ocrEngine = v)
    }

    private fun observeJob(jobId: String) {
        viewModelScope.launch {
            var lastMessage: String? = null
            jobDao.observe(jobId).collect { job ->
                if (job == null) return@collect
                val needRefresh = job.parsedCount != _state.value.preview.size || job.stage == "DONE"
                val preview = if (needRefresh) readPreview(job) else _state.value.preview
                val newLog = if (job.message != null && job.message != lastMessage) {
                    lastMessage = job.message
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(job.updatedAt))
                    _state.value.log + "$ts  ${job.message}"
                } else _state.value.log
                _state.value = _state.value.copy(
                    job = job,
                    preview = preview,
                    log = newLog,
                    stage = when {
                        job.stage == "DONE" -> Stage.PREVIEW
                        job.stage == "INGEST" || job.stage == "PARSE" -> Stage.RUNNING
                        job.stage == "FAILED" -> if (preview.isEmpty()) Stage.RUNNING else Stage.PREVIEW
                        else -> _state.value.stage
                    }
                )
            }
        }
    }

    private suspend fun readPreview(job: ImportJob): List<ParsedLine> = withContext(Dispatchers.IO) {
        job.parsedPath?.let { JsonCodec.readParsedLines(File(it)) }?.sortedBy { it.chunkIndex * 100000 + it.indexInChunk }
            ?: emptyList()
    }

    /** 运行中刷新预览（增量） */
    fun refreshPreview() {
        val jid = _state.value.jobId ?: return
        viewModelScope.launch {
            val job = jobDao.getById(jid) ?: return@launch
            _state.value = _state.value.copy(preview = readPreview(job))
        }
    }

    fun cancelJob() {
        val jid = _state.value.jobId ?: return
        ImportPipelineWorker.cancel(getApplication(), jid)
        viewModelScope.launch {
            jobDao.getById(jid)?.let { jobDao.upsert(it.copy(stage = "CANCELED", message = "已取消")) }
        }
    }

    /** 取消后继续 / 失败段重跑 */
    fun resumeJob() {
        val jid = _state.value.jobId ?: return
        val s = _state.value
        val job = _state.value.job
        viewModelScope.launch {
            ImportPipelineWorker.enqueue(
                context = getApplication(),
                jobId = jid, uri = null,
                bankName = job?.bankName ?: s.bankName,
                sourceName = job?.sourceName ?: "",
                parseMode = "ai",
                providerId = null,
                concurrency = s.concurrency
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val job = s.job ?: return
        if (s.preview.isEmpty() || s.stage == Stage.SAVING) return
        viewModelScope.launch {
            _state.value = s.copy(stage = Stage.SAVING)
            runCatching {
                val parsed = s.preview.map { line ->
                    val pq = line.toParsedQuestion()
                    // 落库前再过一次选项规范化
                    val (opts, ans) = OptionNormalizer.normalize(pq.options, pq.answer)
                    val (sopts, _) = OptionNormalizer.normalize(pq.sharedOptions, null)
                    pq.options.clear()
                    pq.options.addAll(opts)
                    pq.answer = ans
                    pq.sharedOptions.clear()
                    pq.sharedOptions.addAll(sopts)
                    pq
                }
                bankRepo.createBank(s.bankName.ifBlank { job.bankName }, job.sourceName.ifBlank { null }, parsed)
            }.onSuccess {
                // 清理任务与临时文件
                job.rawTextPath?.let { File(it).delete() }
                job.parsedPath?.let { File(it).delete() }
                jobDao.delete(job.id)
                onDone()
            }.onFailure {
                _state.value = _state.value.copy(stage = Stage.PREVIEW, error = "导入失败：${it.message}")
            }
        }
    }

    /** 标准题库 JSON 直接导入（不经后台） */
    fun importBankFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: error("无法读取文件")
                com.examhelper.shua.data.model.JsonCodec.json
                    .decodeFromString<com.examhelper.shua.data.model.ShuaBankFile>(text)
            }.fold(
                onSuccess = { file ->
                    bankRepo.importBankFile(file)
                    _state.value = _state.value.copy(error = "已导入《${file.name}》${file.questions.size} 题")
                },
                onFailure = {
                    _state.value = _state.value.copy(error = "题库文件解析失败：${it.message}")
                }
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
