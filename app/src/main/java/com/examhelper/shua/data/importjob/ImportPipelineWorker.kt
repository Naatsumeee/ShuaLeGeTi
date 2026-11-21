package com.examhelper.shua.data.importjob

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.examhelper.shua.R
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.ImportJob
import com.examhelper.shua.data.generate.Cost
import com.examhelper.shua.data.ingest.DocumentIngestor
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.ChunkPlanner
import com.examhelper.shua.data.parse.ChunkWorkResult
import com.examhelper.shua.data.parse.FailedChunk
import com.examhelper.shua.data.parse.IncompleteModelResponseException
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.parse.ParseReportBuilder
import com.examhelper.shua.data.parse.TextQuestionParser
import com.examhelper.shua.data.parse.runChunkScheduler
import com.examhelper.shua.data.storage.OcrCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

private class EmptyModelResultException : IllegalStateException("模型未返回题目")

/**
 * 后台导入流水线：文件 →（docx/PDF/OCR）→ 原文 → 分块并行 AI/离线解析 → JSONL 落盘。
 * 前台服务通知常驻，切出应用/锁屏不中断；已完成分块落盘，重跑只补失败段。
 */
class ImportPipelineWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_URI = "uri"
        const val KEY_BANK_NAME = "bankName"
        const val KEY_SOURCE_NAME = "sourceName"
        const val KEY_PARSE_MODE = "parseMode" // "ai" / "offline"
        const val KEY_PROVIDER_ID = "providerId"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_OCR_ENGINE = "ocrEngine" // "mlkit" / "rapidocr"
        const val KEY_OCR_CONCURRENCY = "ocrConcurrency"
        const val KEY_OCR_FINGERPRINT = "ocrFingerprint"
        const val KEY_REUSE_CACHED_OCR = "reuseCachedOcr"
        const val CHANNEL_ID = "import_pipeline"

        /** 预览框里保留的模型输出尾巴长度（字符） */
        private const val STREAM_TAIL_CHARS = 1200
        /** 流式尾巴落库的最小间隔：模型吐字极快，逐字写 Room 会把 IO 打满 */
        private const val STREAM_FLUSH_MS = 350L
        private val PAGE_MARKER = Regex("""\[\[PG:(\d+)]]""")

        /** 单份原始文本上限：超过后明确拒绝，避免“长时间无响应” */
        private const val MAX_RAW_TEXT_CHARS = 5_000_000

        fun enqueue(
            context: Context,
            jobId: String,
            uri: Uri?,
            bankName: String,
            sourceName: String,
            parseMode: String,
            providerId: Long?,
            concurrency: Int,
            ocrEngine: String = "mlkit",
            ocrConcurrency: Int = 1,
            ocrFingerprint: String? = null,
            reuseCachedOcr: Boolean = false
        ): UUID {
            val request = OneTimeWorkRequestBuilder<ImportPipelineWorker>()
                .setInputData(
                    workDataOf(
                        KEY_JOB_ID to jobId,
                        KEY_URI to uri?.toString(),
                        KEY_BANK_NAME to bankName,
                        KEY_SOURCE_NAME to sourceName,
                        KEY_PARSE_MODE to parseMode,
                        KEY_PROVIDER_ID to providerId,
                        KEY_CONCURRENCY to concurrency,
                        KEY_OCR_ENGINE to ocrEngine,
                        KEY_OCR_CONCURRENCY to ocrConcurrency.coerceIn(1, 4),
                        KEY_OCR_FINGERPRINT to (ocrFingerprint ?: ""),
                        KEY_REUSE_CACHED_OCR to reuseCachedOcr
                    )
                )
                .setConstraints(Constraints.NONE)
                // 必须 expedited：普通排队的 WorkRequest 由 JobScheduler 择机调度，
                // 用户点完「开始解析」立刻回桌面时任务往往还没被拉起，
                // 等它真正启动时应用已在后台 → startForeground 被系统拒绝
                // （ForegroundServiceStartNotAllowedException）→ 没有前台服务保护，
                // 进程一被冻住 OCR 就停在那里。expedited 会在应用仍处于前台时立即启动。
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(jobId)
        }

        /**
         * 进程被杀 / 系统清理后自愈：把库里还停在 INGEST、PARSE 的任务重新排上。
         *
         * WorkManager 的队列会随进程一起没，但已解析的分块是落盘的，
         * 重新入队后 worker 会发现 raw/parsed 已存在，直接从断点续跑。
         * 只在确认 WorkManager 里已无该任务的活跃实例时才补，避免重复跑。
         */
        fun reviveStale(context: Context) {
            val app = context.applicationContext
            val dao = (app as? ShuaApp)?.container?.database?.importJobDao() ?: return
            val wm = WorkManager.getInstance(app)
            runCatching {
                val jobs = kotlinx.coroutines.runBlocking { dao.activeJobs() }
                for (job in jobs) {
                    val alive = runCatching {
                        wm.getWorkInfosForUniqueWork(job.id).get().any { !it.state.isFinished }
                    }.getOrDefault(true) // 查不到就当作还活着，宁可不补也别重复跑
                    if (alive) continue
                    // 阶段 1 还没落盘且拿不到原始 URI 的没法续（权限与来源都已失效），
                    // 标成失败让用户重新选文件，而不是让界面永远转圈。
                    val raw = com.examhelper.shua.data.storage.AppStorage
                        .importRawFile(app, job.id)
                    if (!raw.exists()) {
                        kotlinx.coroutines.runBlocking {
                            dao.upsert(
                                job.copy(
                                    stage = "FAILED",
                                    errorMessage = "任务被系统中断，请重新选择文件导入",
                                    message = "任务被系统中断"
                                )
                            )
                        }
                        continue
                    }
                    enqueue(
                        context = app, jobId = job.id, uri = null,
                        bankName = job.bankName, sourceName = job.sourceName,
                        parseMode = "ai", providerId = null, concurrency = 4
                    )
                }
            }
        }
    }

    private val container by lazy { (applicationContext as? ShuaApp)?.container ?: ShuaApp.instance.container }

    private fun jobDao() = container.database.importJobDao()

    private fun rawFile(jobId: String) = com.examhelper.shua.data.storage.AppStorage.importRawFile(applicationContext, jobId)
    private fun parsedFile(jobId: String) = com.examhelper.shua.data.storage.AppStorage.importParsedFile(applicationContext, jobId)
    private val jobWriteLock = Mutex()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val bankName = inputData.getString(KEY_BANK_NAME) ?: ""
        val sourceName = inputData.getString(KEY_SOURCE_NAME) ?: ""
        val parseMode = inputData.getString(KEY_PARSE_MODE) ?: "ai"
        val providerId = inputData.getLong(KEY_PROVIDER_ID, -1L)
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 4).coerceIn(1, 8)
        val ocrEngine = inputData.getString(KEY_OCR_ENGINE) ?: "rapidocr"
        val requestedOcrConcurrency = inputData.getInt(KEY_OCR_CONCURRENCY, 1).coerceIn(1, 4)
        val rapidOcrConcurrency = if (
            ocrEngine != "mlkit" &&
            requestedOcrConcurrency > 1 &&
            com.examhelper.shua.data.ocr.RapidOcrRuntimeGuard
                .consumeInterrupted(applicationContext)
        ) {
            runCatching { container.settings.setRapidOcrConcurrency(1) }
            updateJob(jobId) {
                it.copy(message = "上次多路 RapidOCR 未正常结束，本次已自动切回 1 路")
            }
            1
        } else {
            requestedOcrConcurrency
        }
        val ocrFingerprint = inputData.getString(KEY_OCR_FINGERPRINT).orEmpty()
        val reuseCachedOcr = inputData.getBoolean(KEY_REUSE_CACHED_OCR, false)
        var streamTailScope: CoroutineScope? = null

        try {
            // 首次进入前台服务。失败通常意味着应用已经不在前台、系统拒绝拉起
            // dataSync 服务；这时任务仍会继续跑，但失去保活保护，一旦被冻结就中断。
            // 旧实现把它 runCatching 静默吞了 —— 用户看到的只是「卡住」，没有任何线索。
            val fgOk = runCatching { setForeground(foregroundInfo("准备导入…", 0, 0)) }.isSuccess
            if (!fgOk) {
                updateJob(jobId) { it.copy(message = "后台保活未启动，切出应用可能暂停") }
            }

            // ---------- 阶段 1：提取文本 ----------
            val raw = rawFile(jobId)
            if (!raw.exists()) {
                val uriStr = inputData.getString(KEY_URI)
                    ?: return fail(jobId, "缺少文件来源")
                updateJob(jobId) {
                    it.copy(stage = "INGEST", message = "正在识别文件内容", totalUnits = 0, doneUnits = 0)
                }
                val cachedText = if (reuseCachedOcr && ocrFingerprint.isNotBlank()) {
                    OcrCache.read(applicationContext, ocrFingerprint, ocrEngine)
                } else {
                    null
                }
                val text = if (cachedText != null) {
                    updateJob(jobId) {
                        it.copy(
                            message = "已复用 ${engineLabel(ocrEngine)} OCR 结果，跳过重复识别"
                        )
                    }
                    cachedText
                } else {
                    // 只有显式选了 mlkit 才走 ML Kit；缺省（含旧版本遗留任务）一律 RapidOCR，
                    // 与设置页的默认值保持一致。
                    val ocr = if (ocrEngine == "mlkit") {
                        com.examhelper.shua.data.ocr.MlKitOcrEngine()
                    } else {
                        com.examhelper.shua.data.ocr.RapidOcrEngine(
                            applicationContext,
                            rapidOcrConcurrency
                        )
                    }
                    val guardStarted = ocrEngine != "mlkit" && ocr.concurrency > 1
                    var ocrSucceeded = false
                    if (guardStarted) {
                        com.examhelper.shua.data.ocr.RapidOcrRuntimeGuard
                            .markStarted(applicationContext)
                    }
                    try {
                        // 逐页落盘：47 页扫描件要跑十几分钟，进程被杀时不该整段作废。
                        // 续跑依据是 raw 文件里的「已完成页数」侧车文件（见 ocrProgressFile）。
                        val ingestor = DocumentIngestor(applicationContext, ocr)
                        val progressFile = com.examhelper.shua.data.storage.AppStorage
                            .importOcrProgressFile(applicationContext, jobId)
                        val donePages = runCatching {
                            progressFile.readText().trim().toInt()
                        }.getOrDefault(0)
                        if (donePages > 0 && raw.exists()) {
                            // 已落盘的页直接用，从下一页继续
                            ingestor.resumeFrom = donePages + 1
                            ingestor.resumePrefix = raw.readText()
                        }
                        ingestor.onPageDone = { page, partial ->
                            runCatching {
                                raw.writeText(partial)
                                progressFile.writeText(page.toString())
                            }
                        }
                        val freshText = ingestor.ingest(Uri.parse(uriStr)) { p ->
                            // OCR / 逐页读取阶段也要把进度写回数据库：
                            // 早先只更新前台通知，用户切回应用内看到的进度条一直停在 0
                            // （stage=INGEST 时 totalUnits 恒为 0），这就是「界面内看不到导入进度」的根因。
                            runCatching {
                                updateJob(jobId) {
                                    it.copy(
                                        stage = "INGEST",
                                        message = p.message,
                                        totalUnits = p.total,
                                        doneUnits = p.current
                                    )
                                }
                            }
                            runCatching {
                                setForeground(foregroundInfo(p.message, p.current, p.total))
                            }
                        }
                        if (ingestor.usedOcr && ocrFingerprint.isNotBlank()) {
                            OcrCache.write(applicationContext, ocrFingerprint, ocrEngine, freshText)
                        }
                        ocrSucceeded = true
                        freshText
                    } finally {
                        ocr.close()
                        if (guardStarted) {
                            com.examhelper.shua.data.ocr.RapidOcrRuntimeGuard
                                .markFinished(applicationContext, ocrSucceeded)
                        }
                    }
                }
                if (text.length > MAX_RAW_TEXT_CHARS) {
                    // 500 万字符约 10MB 中文文本，已远超合理题库规模；继续解析只会
                    // 长时间占内存且几乎没有成功收益。明确报错比“永远转圈”好。
                    return fail(jobId, "文本过长（${text.length} 字符），请拆分后再导入")
                }
                raw.writeText(text)
            }

            // ---------- 阶段 2：解析 ----------
            val parsed = parsedFile(jobId)
            val text = raw.readText()

            if (parseMode == "offline") {
                // 离线长文本的安全切块、重叠与去重统一放在 TextQuestionParser.parseLong，
                // 避免 Worker 与解析器各维护一份规则。短文本内部会直接走 parse。
                val offlineChunkList = ChunkPlanner.plan(text, TextQuestionParser.OFFLINE_CHUNK_SIZE, overlapLines = 18)
                val offlineChunks = offlineChunkList.size
                val offlineTotalUnits = offlineChunks + if (offlineChunks > 1) 1 else 0
                updateJob(jobId) {
                    it.copy(
                        stage = "PARSE",
                        message = if (offlineChunks > 1) "离线解析中（$offlineChunks 段）" else "离线解析中",
                        totalUnits = offlineTotalUnits.coerceAtLeast(1),
                        doneUnits = 0
                    )
                }
                val offlineParsed = if (offlineChunks <= 1) {
                    TextQuestionParser.parse(text)
                } else {
                    val merged = mutableListOf<com.examhelper.shua.data.model.ParsedQuestion>()
                    offlineChunkList.forEachIndexed { i, chunk ->
                        if (isStopped) return Result.failure()
                        merged += TextQuestionParser.parseLongChunk(chunk)
                        updateJob(jobId) {
                            it.copy(
                                stage = "PARSE",
                                message = "离线解析：已生成 ${i + 1}/$offlineChunks 段",
                                totalUnits = offlineTotalUnits,
                                doneUnits = i + 1
                            )
                        }
                        kotlinx.coroutines.yield()
                    }
                    updateJob(jobId) {
                        it.copy(
                            stage = "PARSE",
                            message = "离线解析：正在合并、去重并回填答案…",
                            totalUnits = offlineTotalUnits,
                            doneUnits = offlineChunks
                        )
                    }
                    TextQuestionParser.mergeLongResults(merged, text)
                }
                val questions = offlineParsed
                if (questions.isEmpty()) {
                    parsed.writeText("")
                    updateJob(jobId) {
                        it.copy(
                            stage = "DONE",
                            totalUnits = 1,
                            doneUnits = 1,
                            parsedCount = 0,
                            rawTextPath = raw.absolutePath,
                            parsedPath = parsed.absolutePath,
                            message = "未识别到题目，可舍弃或确认导入"
                        )
                    }
                    notifyDone(jobId, bankName, 0)
                    return Result.success()
                }
                kotlinx.coroutines.yield()
                parsed.writeText(
                    questions.mapIndexed { i, q ->
                        JsonCodec.json.encodeToString(
                            ParsedLine(
                                chunkIndex = 0, indexInChunk = i,
                                chapter = q.chapter, type = q.type.name, subtype = q.subtype,
                                number = q.sourceNumber,
                                stem = q.stem, sharedStem = q.sharedStem,
                                sharedOptions = q.sharedOptions, options = q.options,
                                answer = q.answer, analysis = q.analysis, page = q.sourcePage
                            )
                        )
                    }.joinToString("\n")
                )
                updateJob(jobId) {
                    it.copy(
                        stage = "DONE", totalUnits = 1, doneUnits = 1, parsedCount = questions.size,
                        rawTextPath = raw.absolutePath, parsedPath = parsed.absolutePath,
                        message = "离线解析完成"
                    )
                }
                notifyDone(jobId, bankName, questions.size)
                return Result.success()
            }

            // AI 并行分块解析
            val provider = runCatching {
                if (providerId > 0) container.database.providerDao().getById(providerId)
                else container.database.providerDao().getPrimary()
            }.getOrNull()
                ?: return fail(jobId, "未找到可用的大模型配置，请先到设置中添加并启用")

            val chunkList = ChunkPlanner.plan(text, LlmQuestionParser.CHUNK_SIZE)
            val chunks = chunkList.map { it.text }
            val doneChunks = if (parsed.exists()) {
                JsonCodec.readParsedLines(parsed).map { it.chunkIndex }.toSet()
            } else {
                parsed.createNewFile(); emptySet()
            }
            val pending = chunks.indices.filter { it !in doneChunks }

            // 用量基数：断点续传时把**前几轮已经记下的**用量接过来。
            //
            // 旧实现直接在 DONE 时写 snapshotUsage()，而新建的 parser 计数器从 0 开始，
            // 续跑一次就把之前累计的数字整个覆盖掉 —— 用户看到「用量越跑越少」。
            // 现在统一写 base + 本轮增量。
            val existingJob = runCatching { jobDao().getById(jobId) }.getOrNull()
            val baseHit = existingJob?.cacheHitTokens ?: 0L
            val baseMiss = existingJob?.cacheMissTokens ?: 0L
            val baseOut = existingJob?.outputTokens ?: 0L

            // 余额快照：喂给「用量情况」的窗口累计（不再用于任务级「实扣」，
            // 那个数字因为计费延迟恒为 0，已废弃）
            runCatching {
                com.examhelper.shua.data.llm.UsageStats.sample(
                    container.balanceClient,
                    container.database.balanceSnapshotDao(),
                    provider
                )
            }

            updateJob(jobId) {
                it.copy(
                    stage = "PARSE", message = "AI 解析（${provider.name}）",
                    totalUnits = chunks.size, doneUnits = doneChunks.size,
                    rawTextPath = raw.absolutePath, parsedPath = parsed.absolutePath,
                    streamTail = null
                )
            }
            runCatching { setForeground(foregroundInfo("AI 解析中 ${doneChunks.size}/${chunks.size}", doneChunks.size, chunks.size)) }

            val customParsePrompt = container.settings.settings.first().customParsePrompt
            val parser = LlmQuestionParser(
                container.llmClient,
                customParsePrompt.ifBlank { com.examhelper.shua.data.llm.Prompts.PARSE_DEFAULT },
                usageTaskId = "import:$jobId"
            )
            var okCount = doneChunks.size
            var failCount = 0
            var parsedSoFar = JsonCodec.readParsedLines(parsed).size
            val failedChunks = mutableListOf<FailedChunk>()

            // 实时输出按分块分别保存再合成预览。多段并发时不能往同一个 StringBuilder
            // 里乱塞 token，否则第 1、3 段的半句话会交错成不可读的 JSON。
            val tailByChunk = Array(chunks.size) { StringBuilder() }
            val activeChunks = sortedSetOf<Int>()
            val tailLock = Any()
            val tailLastFlush = java.util.concurrent.atomic.AtomicLong(0)
            val tailScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            streamTailScope = tailScope
            val tailWrites = Channel<String?>(Channel.CONFLATED)
            val tailWriter = tailScope.launch {
                for (snapshot in tailWrites) {
                    updateJob(jobId) { it.copy(streamTail = snapshot) }
                }
            }

            fun tailSnapshot(): String? = synchronized(tailLock) {
                val active = tailByChunk.indices.filter { tailByChunk[it].isNotEmpty() }
                if (active.isEmpty()) return@synchronized null
                val perChunk = (STREAM_TAIL_CHARS / active.size).coerceAtLeast(180)
                active.joinToString("\n\n") { index ->
                    "分段 ${index + 1}\n${tailByChunk[index].takeLast(perChunk)}"
                }.takeLast(STREAM_TAIL_CHARS)
            }

            fun pushTail(index: Int, delta: String) {
                val snapshot: String? = synchronized(tailLock) {
                    val buffer = tailByChunk[index]
                    buffer.append(delta)
                    if (buffer.length > STREAM_TAIL_CHARS * 2) {
                        buffer.delete(0, buffer.length - STREAM_TAIL_CHARS)
                    }
                    val now = System.currentTimeMillis()
                    // 节流：模型吐字极快，逐字写 Room 会把 IO 打满
                    if (now - tailLastFlush.get() < STREAM_FLUSH_MS) {
                        null
                    } else {
                        tailLastFlush.set(now)
                        val active = tailByChunk.indices.filter { tailByChunk[it].isNotEmpty() }
                        val perChunk = (STREAM_TAIL_CHARS / active.size.coerceAtLeast(1))
                            .coerceAtLeast(180)
                        active.joinToString("\n\n") { i ->
                            "分段 ${i + 1}\n${tailByChunk[i].takeLast(perChunk)}"
                        }.takeLast(STREAM_TAIL_CHARS)
                    }
                }
                if (snapshot != null) {
                    tailWrites.trySend(snapshot)
                }
            }

            fun clearTail(index: Int) {
                synchronized(tailLock) {
                    tailByChunk[index].clear()
                }
                tailWrites.trySend(tailSnapshot())
            }

            suspend fun drainTail() {
                tailWrites.close()
                tailWriter.join()
                tailScope.cancel()
            }

            fun setChunkActive(index: Int, active: Boolean) {
                val activeLabel = synchronized(tailLock) {
                    if (active) activeChunks += index else activeChunks -= index
                    activeChunks.joinToString("、") { "第 ${it + 1} 段" }
                }
                if (activeLabel.isNotBlank()) {
                    tailScope.launch {
                        updateJob(jobId) {
                            it.copy(message = "AI 解析 ${provider.name}：正在生成 $activeLabel")
                        }
                    }
                }
            }

            val warmupRequired = doneChunks.isEmpty()
            runChunkScheduler(
                indices = pending,
                concurrency = concurrency,
                warmupRequired = warmupRequired,
                parse = { index ->
                    if (isStopped) throw kotlinx.coroutines.CancellationException("导入已取消")
                    setChunkActive(index, true)
                    val parseResult = try {
                        parseChunkWithRetry(
                            parser,
                            provider,
                            chunks[index],
                            onDelta = { delta -> pushTail(index, delta) },
                            onStreamReset = { clearTail(index) }
                        )
                    } finally {
                        setChunkActive(index, false)
                        val snapshot = tailSnapshot()
                        if (snapshot != null) {
                            tailWrites.trySend(snapshot)
                        }
                    }
                    parseResult.fold(
                        onSuccess = { dtoList ->
                            if (dtoList.isEmpty()) {
                                ChunkWorkResult(index, null, "模型未返回题目")
                            } else {
                                val section = chunkList.getOrNull(index)?.section
                                ChunkWorkResult(
                                    index,
                                    dtoList.mapIndexed { i, dto ->
                                        ParsedLine(
                                            chunkIndex = index,
                                            indexInChunk = i,
                                            // 章节名以本块的原文标题为准，防止模型把「第 X 章」前缀吞掉
                                            chapter = ChunkPlanner.resolveChapter(section, dto.chapter),
                                            type = LlmQuestionParser.mapType(dto.type).name,
                                            subtype = dto.subtype,
                                            stem = dto.stem,
                                            // 原文题号：章末集中答案表按题号索引，没有它就只能按顺序猜，
                                            // 遇到答案表跳号会整列错位（答案覆盖率极低的根因之一）
                                            number = dto.number ?: com.examhelper.shua.data.parse.AnswerBinder
                                                .leadingNumberOf(dto.stem),
                                            sharedStem = dto.sharedStem,
                                            sharedOptions = dto.sharedOptions,
                                            options = dto.options,
                                            answer = dto.answer,
                                            analysis = dto.analysis,
                                            page = dto.page
                                        )
                                    }
                                )
                            }
                        },
                        onFailure = { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            if (error is EmptyModelResultException ||
                                error is IncompleteModelResponseException
                            ) {
                                val offline = runCatching {
                                    TextQuestionParser.parse(chunks[index])
                                }.getOrDefault(emptyList())
                                if (offline.isNotEmpty()) {
                                    ChunkWorkResult(index, offlineLines(index, offline))
                                } else {
                                    ChunkWorkResult(
                                        index,
                                        null,
                                        "模型未返回题目，离线兜底也未识别到题目"
                                    )
                                }
                            } else {
                                ChunkWorkResult(index, null, error.message ?: "解析失败")
                            }
                        }
                    )
                },
                onResult = { result ->
                    val lines = result.lines
                    if (lines.isNullOrEmpty()) {
                        val chunk = chunkList.getOrNull(result.index)
                        val pages = chunk?.let { chunkPages(text, it) }.orEmpty()
                        failedChunks += FailedChunk(
                            chunkIndex = result.index,
                            pageFrom = pages.firstOrNull(),
                            pageTo = pages.lastOrNull(),
                            reason = result.error ?: "该段未解析出题目"
                        )
                        failCount++
                    } else {
                        parsed.appendText(JsonCodec.encodeParsedLines(lines))
                        parsedSoFar += lines.size
                        okCount++
                    }
                    val completed = okCount + failCount
                    updateJob(jobId) {
                        val u = parser.snapshotUsage()
                        it.copy(
                            doneUnits = completed,
                            parsedCount = parsedSoFar,
                            // base + 本轮增量：断点续传不会把之前累计的用量冲掉
                            cacheHitTokens = baseHit + u.normalizedCacheHit().toLong(),
                            cacheMissTokens = baseMiss + u.cacheMissTokens.toLong(),
                            outputTokens = baseOut + u.completionTokens.toLong(),
                            message = "AI 解析 ${provider.name}：已处理 $completed/${chunks.size} 段" +
                                " · 成功 $okCount 段 · 失败 $failCount 段"
                        )
                    }
                    runCatching {
                        setForeground(foregroundInfo("AI 解析已处理 $completed/${chunks.size} 段", completed, chunks.size))
                    }
                }
            )

            if (isStopped) {
                drainTail()
                updateJob(jobId) {
                    val u = parser.snapshotUsage()
                    it.copy(
                        stage = "CANCELED",
                        message = "已取消（可继续）",
                        streamTail = null,
                        // 取消也要保住已花掉的用量，否则用户看不到这段消费
                        cacheHitTokens = baseHit + u.normalizedCacheHit().toLong(),
                        cacheMissTokens = baseMiss + u.cacheMissTokens.toLong(),
                        outputTokens = baseOut + u.completionTokens.toLong()
                    )
                }
                return Result.failure()
            }

            // 跨块收口：同一道题被相邻分段重复解析的合并成一条；原文答案集中
            // 给在文末的，按题号回填到前面缺答案的题目上。
            var finalLines = JsonCodec.readParsedLines(parsed)
            var totalParsed = finalLines.size
            var coverage = com.examhelper.shua.data.parse.AnswerCoverage.audit(
                finalLines
            )
            if (okCount > 0) {
                val after = LlmQuestionParser.finalizeLines(finalLines, text)
                if (after.size != finalLines.size || after.indices.any { after[it] != finalLines[it] }) {
                    runCatching {
                        parsed.writeText(after.joinToString("\n") { JsonCodec.json.encodeToString(it) } + "\n")
                    }
                    totalParsed = after.size
                }
                // finalizeLines 会丢掉空题干等无效行。后续校验必须看收口后的结果，
                // 不能再看旧的 JSONL 文件，否则会留下「DONE 0 题」的假成功。
                finalLines = after
                coverage = com.examhelper.shua.data.parse.AnswerCoverage.audit(after)
            }
            if (okCount == 0 && chunks.isNotEmpty()) {
                val report = ParseReportBuilder.build(
                    chunkList,
                    text,
                    JsonCodec.readParsedLines(parsed),
                    failedChunks
                )
                val streamSnapshot = tailSnapshot()
                val detail = failedChunks.firstOrNull()?.reason
                    ?.take(240)
                    ?.takeIf { it.isNotBlank() }
                    ?: "模型返回不可解析内容"
                drainTail()
                updateJob(jobId) {
                    it.copy(
                        stage = "FAILED",
                        errorMessage = "AI 解析全部失败（$detail）",
                        parseReportJson = report.encode(),
                        message = "AI 解析失败：成功 0 段 · 失败 $failCount 段",
                        streamTail = streamSnapshot
                    )
                }
                return fail(jobId, "AI 解析全部失败（$detail）")
            }
            if (finalLines.isEmpty()) {
                runCatching { parsed.writeText("") }
                val report = ParseReportBuilder.build(chunkList, text, emptyList(), failedChunks)
                drainTail()
                updateJob(jobId) {
                    it.copy(
                        stage = "DONE",
                        totalUnits = chunks.size.coerceAtLeast(1),
                        doneUnits = chunks.size.coerceAtLeast(1),
                        parsedCount = 0,
                        rawTextPath = raw.absolutePath,
                        parsedPath = parsed.absolutePath,
                        parseReportJson = report.encode(),
                        message = "未识别到题目，可舍弃或确认导入",
                        streamTail = null
                    )
                }
                notifyDone(jobId, bankName, 0)
                return Result.success()
            }
            val report = ParseReportBuilder.build(chunkList, text, finalLines, failedChunks)
            // 收尾再采一次余额快照：这次采样意味着「任务结束时的余额」，
            // 供「用量情况」的窗口累计使用（任务级「实扣」已废弃，原因见 Cost.spentByBalance）
            runCatching {
                com.examhelper.shua.data.llm.UsageStats.sample(
                    container.balanceClient,
                    container.database.balanceSnapshotDao(),
                    provider,
                    minIntervalMs = 0
                )
            }
            val costYuan = runCatching {
                val u = parser.snapshotUsage()
                Cost.yuan(u.normalizedCacheHit().toLong(), u.cacheMissTokens.toLong(),
                    u.completionTokens.toLong(), provider.model)
            }.getOrNull()
            // 收尾：停掉流式尾巴的落库协程，并把预览框的内容清空
            // （任务已 DONE，预览框要显示的是「已解析题目」，不是模型的原始 JSON）
            drainTail()
            updateJob(jobId) {
                val u = parser.snapshotUsage()
                it.copy(
                    stage = "DONE", parsedCount = totalParsed,
                    streamTail = null,
                    parseReportJson = report.encode(),
                    cacheHitTokens = baseHit + u.normalizedCacheHit().toLong(),
                    cacheMissTokens = baseMiss + u.cacheMissTokens.toLong(),
                    outputTokens = baseOut + u.completionTokens.toLong(),
                    message = "解析完成：成功 $okCount 段 · 失败 $failCount 段 · $totalParsed 题" +
                        " · 答案 ${coverage.answered}/${coverage.total}" +
                        coverage.lowCoverageChapters.take(3).takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " · 整章低覆盖：", separator = "、") { it }
                            .orEmpty() +
                        if (failCount > 0) "（$failCount 段失败，可在预览后重新解析补齐）" else "" +
                        costYuan?.let { " · 参考成本 ${Cost.format(it)} 元" }.orEmpty()
                )
            }
            notifyDone(jobId, bankName, totalParsed)
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            streamTailScope?.cancel()
            updateJob(jobId) { it.copy(stage = "CANCELED", message = "已取消（进度已保存，可继续）") }
            throw e
        } catch (e: Exception) {
            streamTailScope?.cancel()
            return fail(jobId, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(jobId: String, message: String): Result {
        updateJob(jobId) { it.copy(stage = "FAILED", errorMessage = message) }
        return Result.failure()
    }

    /**
     * 单块解析重试。
     *
     * 后台是并发跑多个分块的：网关偶发 5xx / 连接重置 / 读超时都会让某一块抛异常。
     * 这里只对异常做退避重试，配置/鉴权类错误立即放弃。模型正常返回空列表时说明
     * 该块没有可用结构化结果，直接交给调用方做离线兜底，避免重复请求和无效等待。
     */
    private suspend fun parseChunkWithRetry(
        parser: LlmQuestionParser,
        provider: com.examhelper.shua.data.db.LlmProvider,
        chunk: String,
        onDelta: ((String) -> Unit)? = null,
        onStreamReset: (() -> Unit)? = null
    ): kotlin.Result<List<LlmQuestionParser.QuestionDto>> {
        val waits = longArrayOf(0L, 1200L, 3000L)
        var lastError: Throwable? = null
        for (wait in waits) {
            if (isStopped) throw kotlinx.coroutines.CancellationException("导入已取消")
            if (wait > 0) kotlinx.coroutines.delay(wait)
            // 只在第一次尝试时接流式预览：重试会把同样的内容再吐一遍，
            // 预览框里会出现「同一段话重复两次」的观感。
            val r = runCatching {
                parser.parseChunkText(provider, chunk, if (wait == 0L) onDelta else null)
            }
            val list = r.getOrNull()
            if (!list.isNullOrEmpty()) {
                return kotlin.Result.success(list)
            }
            if (list != null) {
                // 部分中转的流式模式只回空数组，给非流式请求一次机会；
                // 非流式仍为空时不再重复烧请求，直接交给离线兜底。
                if (onDelta != null && wait == 0L) {
                    onStreamReset?.invoke()
                    lastError = EmptyModelResultException()
                    continue
                }
                return kotlin.Result.failure(EmptyModelResultException())
            } else {
                val error = r.exceptionOrNull() ?: IllegalStateException("模型未返回题目")
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
            }
            val msg = lastError?.message.orEmpty()
            if (msg.contains("HTTP 401") || msg.contains("HTTP 403") ||
                msg.contains("HTTP 400") || msg.contains("Insufficient Balance") ||
                msg.contains("Unauthorized") || msg.contains("Authentication")
            ) return kotlin.Result.failure(lastError ?: IllegalStateException("模型未返回题目"))
        }
        return kotlin.Result.failure(lastError ?: IllegalStateException("模型未返回题目"))
    }

    private fun offlineLines(
        chunkIndex: Int,
        questions: List<com.examhelper.shua.data.model.ParsedQuestion>
    ): List<ParsedLine> = questions.mapIndexed { i, q ->
        ParsedLine(
            chunkIndex = chunkIndex,
            indexInChunk = i,
            chapter = q.chapter,
            type = q.type.name,
            subtype = q.subtype,
            number = q.sourceNumber,
            stem = q.stem,
            sharedStem = q.sharedStem,
            sharedOptions = q.sharedOptions,
            options = q.options,
            answer = q.answer,
            analysis = q.analysis,
            page = q.sourcePage
        )
    }

    private fun engineLabel(engine: String): String =
        if (engine.equals("mlkit", ignoreCase = true)) "ML Kit" else "RapidOCR"

    private fun chunkPages(rawText: String, chunk: ChunkPlanner.Chunk): List<Int> {
        val rawLines = rawText.lines()
        if (rawLines.isEmpty()) return emptyList()
        val from = chunk.startLine.coerceIn(0, rawLines.lastIndex)
        val to = chunk.endLine.coerceIn(from, rawLines.lastIndex)
        return rawLines.subList(from, to + 1)
            .flatMap { line ->
                PAGE_MARKER.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }
            }
            .distinct()
            .sorted()
    }

    private suspend fun updateJob(jobId: String, mutate: (ImportJob) -> ImportJob) {
        jobWriteLock.withLock {
            runCatching {
                val dao = jobDao()
                dao.getById(jobId)?.let {
                    dao.upsert(mutate(it).copy(updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    private fun foregroundInfo(text: String, progress: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk = 26，通知渠道必然可用，不需要版本判断
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "题库导入", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("刷了个题 · 导入解析")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (total > 0) {
                    setProgress(total, progress.coerceIn(0, total), false)
                } else setProgress(0, 0, true)
            }
            .build()
        // API 29 才有前台服务类型；26~28 仍要走两参构造
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(jobId: String, bankName: String, count: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "题库导入", NotificationManager.IMPORTANCE_LOW)
            )
            nm.notify(
                NOTIFICATION_ID + 1,
                Notification.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle("解析完成：${count}题")
                    .setContentText("《$bankName》已就绪，点击回到应用确认导入")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private val NOTIFICATION_ID = 1001
}
