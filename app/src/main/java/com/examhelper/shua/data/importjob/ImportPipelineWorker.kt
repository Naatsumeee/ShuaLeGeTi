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
import com.examhelper.shua.data.ingest.DocumentIngestor
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.parse.TextQuestionParser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
                val ocr = if (inputData.getString(KEY_OCR_ENGINE) == "rapidocr") {
                    com.examhelper.shua.data.ocr.RapidOcrEngine(applicationContext)
                } else {
                    com.examhelper.shua.data.ocr.MlKitOcrEngine()
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

            val chunks = LlmQuestionParser.chunkText(text, LlmQuestionParser.CHUNK_SIZE)
            val doneChunks = if (parsed.exists()) {
                JsonCodec.readParsedLines(parsed).map { it.chunkIndex }.toSet()
            } else {
                parsed.createNewFile(); emptySet()
            }
            val pending = chunks.indices.filter { it !in doneChunks }

            updateJob(jobId) {
                it.copy(
                    stage = "PARSE", message = "AI 解析（${provider.name}）",
                    totalUnits = chunks.size, doneUnits = doneChunks.size,
                    rawTextPath = raw.absolutePath, parsedPath = parsed.absolutePath
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

            coroutineScope {
                val semaphore = Semaphore(concurrency)
                val deferred = pending.map { idx ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) null
                            else runCatching { idx to parser.parseChunkText(provider, chunks[idx]) }.getOrNull()
                        }
                    }
                }
                deferred.forEach { d ->
                    val outcome = d.await() ?: return@forEach
                    val (idx, dtoList) = outcome
                    val lines = dtoList.mapIndexed { i, dto ->
                        JsonCodec.json.encodeToString(
                            ParsedLine(
                                chunkIndex = idx, indexInChunk = i,
                                chapter = dto.chapter, type = LlmQuestionParser.mapType(dto.type).name,
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
                        it.copy(
                            doneUnits = okCount,
                            parsedCount = parsedSoFar,
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
            updateJob(jobId) {
                it.copy(
                    stage = "DONE", parsedCount = totalParsed,
                    message = "解析完成：$totalParsed 题" + if (failCount > 0) "（$failCount 段失败，可在预览后重新解析补齐）" else ""
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

    private suspend fun updateJob(jobId: String, mutate: (ImportJob) -> ImportJob) {
        runCatching {
            val dao = jobDao()
            dao.getById(jobId)?.let { dao.upsert(mutate(it).copy(updatedAt = System.currentTimeMillis())) }
        }
    }

    private fun foregroundInfo(text: String, progress: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "题库导入", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(applicationContext, CHANNEL_ID) else
            Notification.Builder(applicationContext)
        val notification = builder
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
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(jobId: String, bankName: String, count: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "题库导入", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(applicationContext, CHANNEL_ID) else
                Notification.Builder(applicationContext)
            nm.notify(
                NOTIFICATION_ID + 1,
                builder
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
