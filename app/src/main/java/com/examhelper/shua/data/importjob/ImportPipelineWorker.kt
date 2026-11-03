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
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.parse.TextQuestionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

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
        const val CHANNEL_ID = "import_pipeline"

        /** 预览框里保留的模型输出尾巴长度（字符） */
        private const val STREAM_TAIL_CHARS = 1200
        /** 流式尾巴落库的最小间隔：模型吐字极快，逐字写 Room 会把 IO 打满 */
        private const val STREAM_FLUSH_MS = 350L

        fun enqueue(
            context: Context,
            jobId: String,
            uri: Uri?,
            bankName: String,
            sourceName: String,
            parseMode: String,
            providerId: Long?,
            concurrency: Int,
            ocrEngine: String = "mlkit"
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
                        KEY_OCR_ENGINE to ocrEngine
                    )
                )
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(jobId)
        }
    }

    private val container by lazy { (applicationContext as? ShuaApp)?.container ?: ShuaApp.instance.container }

    private fun jobDao() = container.database.importJobDao()

    private fun rawFile(jobId: String) = com.examhelper.shua.data.storage.AppStorage.importRawFile(applicationContext, jobId)
    private fun parsedFile(jobId: String) = com.examhelper.shua.data.storage.AppStorage.importParsedFile(applicationContext, jobId)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val bankName = inputData.getString(KEY_BANK_NAME) ?: ""
        val sourceName = inputData.getString(KEY_SOURCE_NAME) ?: ""
        val parseMode = inputData.getString(KEY_PARSE_MODE) ?: "ai"
        val providerId = inputData.getLong(KEY_PROVIDER_ID, -1L)
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 4).coerceIn(1, 8)

        try {
            runCatching { setForeground(foregroundInfo("准备导入…", 0, 0)) }

            // ---------- 阶段 1：提取文本 ----------
            val raw = rawFile(jobId)
            if (!raw.exists()) {
                val uriStr = inputData.getString(KEY_URI)
                    ?: return fail(jobId, "缺少文件来源")
                updateJob(jobId) {
                    it.copy(stage = "INGEST", message = "正在识别文件内容", totalUnits = 0, doneUnits = 0)
                }
                // 只有显式选了 mlkit 才走 ML Kit；缺省（含旧版本遗留任务）一律 RapidOCR，
                // 与设置页的默认值保持一致。
                val ocr = if (inputData.getString(KEY_OCR_ENGINE) == "mlkit") {
                    com.examhelper.shua.data.ocr.MlKitOcrEngine()
                } else {
                    com.examhelper.shua.data.ocr.RapidOcrEngine(applicationContext)
                }
                val text = DocumentIngestor(applicationContext, ocr).ingest(Uri.parse(uriStr)) { p ->
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
                raw.writeText(text)
            }

            // ---------- 阶段 2：解析 ----------
            val parsed = parsedFile(jobId)
            val text = raw.readText()

            if (parseMode == "offline") {
                updateJob(jobId) {
                    it.copy(stage = "PARSE", message = "离线解析中", totalUnits = 1, doneUnits = 0)
                }
                val questions = TextQuestionParser.parse(text)
                parsed.writeText(
                    questions.mapIndexed { i, q ->
                        JsonCodec.json.encodeToString(
                            ParsedLine(
                                chunkIndex = 0, indexInChunk = i,
                                chapter = q.chapter, type = q.type.name, subtype = q.subtype,
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

            // 官方余额快照：开工前读一次。断点续传时沿用上一轮记录的开跑余额，
            // 这样「本轮消耗」算的是整次导入，而不是最后这一段。
            val existingJob = runCatching { jobDao().getById(jobId) }.getOrNull()
            val balanceBefore = existingJob?.balanceBefore?.let {
                com.examhelper.shua.data.llm.BalanceLedger.Snapshot(
                    it, existingJob.balanceCurrency ?: "CNY"
                )
            } ?: runCatching {
                com.examhelper.shua.data.llm.BalanceLedger.read(container.balanceClient, provider)
            }.getOrNull()

            updateJob(jobId) {
                it.copy(
                    stage = "PARSE", message = "AI 解析（${provider.name}）",
                    totalUnits = chunks.size, doneUnits = doneChunks.size,
                    rawTextPath = raw.absolutePath, parsedPath = parsed.absolutePath,
                    balanceBefore = balanceBefore?.amount,
                    balanceCurrency = balanceBefore?.currency
                )
            }
            runCatching { setForeground(foregroundInfo("AI 解析中 ${doneChunks.size}/${chunks.size}", doneChunks.size, chunks.size)) }

            val customParsePrompt = container.settings.settings.first().customParsePrompt
            val parser = LlmQuestionParser(
                container.llmClient,
                customParsePrompt.ifBlank { com.examhelper.shua.data.llm.Prompts.PARSE_DEFAULT }
            )
            var okCount = doneChunks.size
            var failCount = 0
            var parsedSoFar = JsonCodec.readParsedLines(parsed).size

            // 实时输出尾巴：多段并发同时吐字，这里按「最后写入者」拼接展示，
            // 只保留末尾 STREAM_TAIL_CHARS 字符（数据库字段不该被几十万字撑爆）。
            // 回调是同步的（流式 onDelta 在 IO 线程里调用），且 doWork 是 suspend
            // 但没有可直接 launch 的作用域，所以用独立 scope + 时间戳节流落库。
            val tailBuffer = StringBuilder()
            val tailLock = Any()
            val tailLastFlush = java.util.concurrent.atomic.AtomicLong(0)
            val tailScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val pushTail: (String) -> Unit = { delta ->
                val snapshot: String? = synchronized(tailLock) {
                    tailBuffer.append(delta)
                    if (tailBuffer.length > STREAM_TAIL_CHARS * 2) {
                        tailBuffer.delete(0, tailBuffer.length - STREAM_TAIL_CHARS)
                    }
                    val now = System.currentTimeMillis()
                    // 节流：模型吐字极快，逐字写 Room 会把 IO 打满
                    if (now - tailLastFlush.get() < STREAM_FLUSH_MS) {
                        null
                    } else {
                        tailLastFlush.set(now)
                        tailBuffer.takeLast(STREAM_TAIL_CHARS).toString()
                    }
                }
                if (snapshot != null) {
                    tailScope.launch { updateJob(jobId) { it.copy(streamTail = snapshot) } }
                }
            }

            coroutineScope {
                val semaphore = Semaphore(concurrency)
                val deferred = pending.map { idx ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) null
                            else idx to parseChunkWithRetry(parser, provider, chunks[idx], pushTail)
                        }
                    }
                }
                deferred.forEach { d ->
                    val outcome = d.await() ?: return@forEach
                    val (idx, dtoList) = outcome
                    val section = chunkList.getOrNull(idx)?.section
                    val lines = dtoList.mapIndexed { i, dto ->
                        JsonCodec.json.encodeToString(
                            ParsedLine(
                                chunkIndex = idx, indexInChunk = i,
                                // 章节名以本块的原文标题为准，防止模型把「第 X 章」前缀吞掉
                                chapter = ChunkPlanner.resolveChapter(section, dto.chapter),
                                type = LlmQuestionParser.mapType(dto.type).name,
                                subtype = dto.subtype, stem = dto.stem,
                                sharedStem = dto.sharedStem, sharedOptions = dto.sharedOptions,
                                options = dto.options, answer = dto.answer, analysis = dto.analysis,
                                page = dto.page
                            )
                        )
                    }
                    if (lines.isNotEmpty()) {
                        parsed.appendText(lines.joinToString("\n", postfix = "\n"))
                        parsedSoFar += lines.size
                        okCount++
                    } else {
                        failCount++
                    }
                    updateJob(jobId) {
                        val u = parser.snapshotUsage()
                        it.copy(
                            doneUnits = okCount,
                            parsedCount = parsedSoFar,
                            cacheHitTokens = u.normalizedCacheHit().toLong(),
                            cacheMissTokens = u.cacheMissTokens.toLong(),
                            outputTokens = u.completionTokens.toLong(),
                            message = "AI 解析 ${provider.name}：完成 $okCount/${chunks.size} 段" +
                                if (failCount > 0) "（失败 $failCount 段）" else ""
                        )
                    }
                    runCatching {
                        setForeground(foregroundInfo("AI 解析 $okCount/${chunks.size} 段", okCount, chunks.size))
                    }
                }
            }

            if (isStopped) {
                updateJob(jobId) { it.copy(stage = "CANCELED", message = "已取消（可继续）") }
                return Result.failure()
            }

            // 跨块收口：同一道题被相邻分段重复解析的合并成一条；原文答案集中
            // 给在文末的，按题号回填到前面缺答案的题目上。
            var totalParsed = JsonCodec.readParsedLines(parsed).size
            if (okCount > 0) {
                val before = JsonCodec.readParsedLines(parsed)
                val after = LlmQuestionParser.finalizeLines(before, text)
                if (after.size != before.size || after.indices.any { after[it] != before[it] }) {
                    runCatching {
                        parsed.writeText(after.joinToString("\n") { JsonCodec.json.encodeToString(it) } + "\n")
                    }
                    totalParsed = after.size
                }
            }
            if (okCount == 0 && chunks.isNotEmpty()) {
                return fail(jobId, "AI 解析全部失败：${provider.name} 连接异常或返回不可解析")
            }
            val balanceAfter = runCatching {
                com.examhelper.shua.data.llm.BalanceLedger.readSettled(container.balanceClient, provider)
            }.getOrNull()
            val spent = Cost.spentByBalance(balanceBefore?.amount, balanceAfter?.amount)
            val costYuan = runCatching {
                val u = parser.snapshotUsage()
                Cost.yuan(u.normalizedCacheHit().toLong(), u.cacheMissTokens.toLong(),
                    u.completionTokens.toLong(), provider.model)
            }.getOrNull()
            // 收尾：停掉流式尾巴的落库协程，并把预览框的内容清空
            // （任务已 DONE，预览框要显示的是「已解析题目」，不是模型的原始 JSON）
            tailScope.cancel()
            updateJob(jobId) {
                val u = parser.snapshotUsage()
                it.copy(
                    stage = "DONE", parsedCount = totalParsed,
                    streamTail = null,
                    cacheHitTokens = u.normalizedCacheHit().toLong(),
                    cacheMissTokens = u.cacheMissTokens.toLong(),
                    outputTokens = u.completionTokens.toLong(),
                    balanceBefore = balanceBefore?.amount,
                    balanceAfter = balanceAfter?.amount,
                    balanceCurrency = balanceAfter?.currency ?: balanceBefore?.currency,
                    message = "解析完成：$totalParsed 题" + if (failCount > 0) "（$failCount 段失败，可在预览后重新解析补齐）" else "" +
                        costYuan?.let { " · 参考成本 ${Cost.format(it)} 元" }.orEmpty() +
                        spent?.let { " · 实扣 ${Cost.format(it)} 元" }.orEmpty() +
                        balanceAfter?.let { " · 余额 ${Cost.money(it.amount)} ${it.currency}" }.orEmpty()
                )
            }
            notifyDone(jobId, bankName, totalParsed)
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateJob(jobId) { it.copy(stage = "CANCELED", message = "已取消（进度已保存，可继续）") }
            throw e
        } catch (e: Exception) {
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
     * 后台是并发跑多个分块的：网关偶发 5xx / 连接重置 / 读超时都会让某一块返回空，
     * 早先一次失败这块的题目就永久丢了（重跑也得等整批结束再手动续传）。
     * 这里对**空结果和异常**都做退避重试，只对配置/鉴权类错误立即放弃。
     */
    private suspend fun parseChunkWithRetry(
        parser: LlmQuestionParser,
        provider: com.examhelper.shua.data.db.LlmProvider,
        chunk: String,
        onDelta: ((String) -> Unit)? = null
    ): List<LlmQuestionParser.QuestionDto> {
        val waits = longArrayOf(0L, 1200L, 3000L)
        var last: List<LlmQuestionParser.QuestionDto> = emptyList()
        for (wait in waits) {
            if (isStopped) return last
            if (wait > 0) kotlinx.coroutines.delay(wait)
            // 只在第一次尝试时接流式预览：重试会把同样的内容再吐一遍，
            // 预览框里会出现「同一段话重复两次」的观感。
            val r = runCatching {
                parser.parseChunkText(provider, chunk, if (wait == 0L) onDelta else null)
            }
            val list = r.getOrNull()
            if (!list.isNullOrEmpty()) return list
            val msg = r.exceptionOrNull()?.message.orEmpty()
            if (msg.contains("HTTP 401") || msg.contains("HTTP 403") ||
                msg.contains("HTTP 400") || msg.contains("Insufficient Balance") ||
                msg.contains("Unauthorized") || msg.contains("Authentication")
            ) return last
            last = list.orEmpty()
        }
        return last
    }

    private suspend fun updateJob(jobId: String, mutate: (ImportJob) -> ImportJob) {
        runCatching {
            val dao = jobDao()
            dao.getById(jobId)?.let { dao.upsert(mutate(it).copy(updatedAt = System.currentTimeMillis())) }
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
