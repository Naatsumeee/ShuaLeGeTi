package com.examhelper.shua.data.generate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.examhelper.shua.R
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.GenerateJob
import com.examhelper.shua.data.model.JsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 生成范围（顶层 object，便于 ViewModel 直接引用 GenerateScope.XXX）
 * - ALL     全部重生成（forceAll）
 * - MISSING 只补缺答案的题（历史默认行为）
 * - MINIMAL 只补缺答案的题，且解析限制 30 字（省钱模式）
 * - BROKEN  错误范围：已有答案但缺解析的题（AI 全部生成后仍覆盖不全的补漏）
 */
object GenerateScope {
    const val ALL = "ALL"
    const val MISSING = "MISSING"
    const val MINIMAL = "MINIMAL"
    const val BROKEN = "BROKEN"
}

/**
 * 后台 AI 生成 Worker：前台服务常驻（与导入同机制），切出/锁屏不中断。
 * 断点续传：每批结果即时写库，重跑自动跳过已有答案的题（forceAll 除外）。
 * 冲突保留：AI 与原题库答案不一致时，原答案存入 originalAnswer 双展示。
 */
class GenerateWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_BANK_ID = "bankId"
        const val KEY_FORCE_ALL = "forceAll"
        const val KEY_CONCURRENCY = "concurrency"
        /** 章节范围（JSON 字符串数组，空 = 全部章节） */
        const val KEY_CHAPTERS = "chapters"
        /** 生成范围：ALL 全部重生成 / MISSING 仅缺答案 / MINIMAL 仅缺答案+极简解析 / BROKEN 错误范围 */
        const val KEY_SCOPE = "scope"
        const val CHANNEL_ID = "generate_pipeline"
        private const val NOTIFICATION_ID = 2001

        fun uniqueName(bankId: Long) = "generate_$bankId"

        fun enqueue(
            context: Context,
            bankId: Long,
            forceAll: Boolean,
            concurrency: Int,
            chapters: List<String> = emptyList(),
            scope: String = if (forceAll) GenerateScope.ALL else GenerateScope.MISSING
        ) {
            val request = OneTimeWorkRequestBuilder<GenerateWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BANK_ID to bankId,
                        KEY_FORCE_ALL to forceAll,
                        KEY_CONCURRENCY to concurrency,
                        KEY_CHAPTERS to (JsonCodec.encodeStringList(chapters) ?: ""),
                        KEY_SCOPE to scope
                    )
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(bankId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, bankId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bankId))
        }
    }

    private val container by lazy { ShuaApp.instance.container }

    override suspend fun doWork(): Result {
        val bankId = inputData.getLong(KEY_BANK_ID, -1L)
        if (bankId <= 0) return Result.failure()
        val forceAll = inputData.getBoolean(KEY_FORCE_ALL, false)
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 3).coerceIn(1, 6)
        val dao = container.database.generateJobDao()
        val bankRepo = container.bankRepo

        try {
            runCatching { setForeground(foregroundInfo("准备生成…", 0, 0)) }

            val providers = container.database.providerDao().observeAll().first().filter { it.enabled }
            if (providers.isEmpty()) {
                return fail(bankId, "未配置可用模型")
            }
            val primary = providers.firstOrNull { it.isPrimary } ?: providers.first()
            val sanSheng = container.settings.settings.first().sanShengLiuBu && providers.size >= 2
            val others = if (sanSheng) providers.filter { it.id != primary.id } else emptyList()

            val customPrompt = container.settings.settings.first().customAnswerPrompt
            // 极简解析模式用独立提示词（用户自定义提示词优先）
            val scope = inputData.getString(KEY_SCOPE)
                ?: if (forceAll) GenerateScope.ALL else GenerateScope.MISSING
            val defaultPrompt =
                if (scope == GenerateScope.MINIMAL) com.examhelper.shua.data.llm.Prompts.ANSWER_MINIMAL
                else com.examhelper.shua.data.llm.Prompts.ANSWER_DEFAULT
            val generator = AnswerGenerator(
                container.llmClient,
                customPrompt.ifBlank { defaultPrompt }
            )

            // 断点续传 + 生成范围：
            // ALL    = 范围内全部重生成
            // MISSING= 补「不完整」的题：缺答案 **或** 缺解析。
            //          早先只按「缺答案」筛选，题库自带答案的题永远不会被送去生成，
            //          于是 AI 全部跑完后解析覆盖率仍不满——这才是覆盖率不全的根因。
            // MINIMAL= 同 MISSING，但提示词把解析压到 30 字
            // BROKEN = 「错误范围」：已有答案却缺解析的题（AI 跑完仍覆盖不全的补漏）
            val chapters = JsonCodec.decodeStringList(inputData.getString(KEY_CHAPTERS))
            val all = bankRepo.getQuestions(bankId)
            val scoped = if (chapters.isEmpty()) all else all.filter { it.chapter in chapters }
            val targets = when (scope) {
                GenerateScope.ALL -> scoped
                GenerateScope.BROKEN -> scoped.filter { !it.answer.isNullOrBlank() && it.analysis.isNullOrBlank() }
                else -> scoped.filter { it.answer.isNullOrBlank() || it.analysis.isNullOrBlank() }
            }
            if (targets.isEmpty()) {
                val msg = when (scope) {
                    GenerateScope.BROKEN -> "错误范围为空：所有题目都已有答案与解析"
                    else -> "没有需要生成的题目"
                }
                dao.upsert(GenerateJob(bankId = bankId, stage = "DONE", total = 0, message = msg))
                return Result.success()
            }

            dao.upsert(
                GenerateJob(
                    bankId = bankId, stage = "RUNNING", forceAll = forceAll,
                    total = targets.size,
                    message = "后台生成中：${generatorLabel(primary.name, others.size)}"
                )
            )

            val batches = targets.chunked(AnswerGenerator.BATCH_SIZE)
            val usage = UsageAcc()
            var done = 0
            var ok = 0
            var fail = 0

            coroutineScope {
                val semaphore = Semaphore(concurrency)
                val deferred = batches.map { batch ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) {
                                Triple(batch, null, null)
                            } else {
                                val outcome = withTimeoutOrNull(300_000) {
                                    runCatching { generator.aggregateBatch(batch, primary, others) }.getOrNull()
                                }
                                Triple<List<com.examhelper.shua.data.db.Question>, List<AggregatedVerdict>?, com.examhelper.shua.data.llm.ChatUsage?>(
                                    batch, outcome?.first, outcome?.second
                                )
                            }
                        }
                    }
                }
                deferred.forEach { d ->
                    val (batch, verdicts, batchUsage) = d.await()
                    batchUsage?.let { usage.add(it) }
                    if (verdicts != null) {
                        batch.forEachIndexed { i, q ->
                            val v = verdicts.getOrNull(i)
                            if (v != null && v.answer != null) {
                                // 题库/OCR 已有参考答案 → 以原答案为准（AI 只作旁证），
                                // 避免「选项识别不清时 AI 编一个」；一致则标记已核对。
                                val bankAnswer = q.answer?.takeIf { it.isNotBlank() }
                                val match = bankAnswer != null &&
                                    AnswerGenerator.answersMatch(bankAnswer, v.answer)
                                bankRepo.updateQuestion(
                                    q.copy(
                                        answer = bankAnswer ?: v.answer,
                                        aiAnswer = v.answer,
                                        answerVerified = match,
                                        analysis = v.analysis ?: q.analysis,
                                        aiGenerated = true,
                                        confidence = v.confidence,
                                        agreement = v.agreement,
                                        originalAnswer = if (bankAnswer != null && !match) bankAnswer else null,
                                        dissentJson = v.dissents.takeIf { it.isNotEmpty() }
                                            ?.let(JsonCodec::encodeDissent)
                                    )
                                )
                                ok++
                            } else {
                                fail++
                            }
                            done++
                        }
                    } else {
                        batch.forEach { fail++; done++ }
                    }
                    dao.upsert(
                        (dao.get(bankId) ?: GenerateJob(bankId = bankId)).copy(
                            stage = "RUNNING",
                            done = done, ok = ok, fail = fail,
                            cacheHitTokens = usage.hit,
                            cacheMissTokens = usage.miss,
                            outputTokens = usage.out,
                            message = "第 ${minOf(done / AnswerGenerator.BATCH_SIZE + 1, batches.size)}/${batches.size} 批 · 完成 $done/${targets.size} · 命中 ${Cost.hitRate(usage.hit, usage.miss)}%",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    runCatching {
                        setForeground(foregroundInfo("AI 生成 $done/${targets.size}", done, targets.size))
                    }
                }
            }

            if (isStopped) {
                dao.upsert(
                    (dao.get(bankId) ?: GenerateJob(bankId = bankId)).copy(
                        stage = "CANCELED", message = "已取消（已生成部分保留，可继续）"
                    )
                )
                return Result.failure()
            }

            val costYuan = Cost.yuan(usage.hit, usage.miss, usage.out, primary.model)
            dao.upsert(
                (dao.get(bankId) ?: GenerateJob(bankId = bankId)).copy(
                    stage = "DONE",
                    message = "完成：成功 $ok，失败 $fail · 缓存命中 ${Cost.hitRate(usage.hit, usage.miss)}%" +
                        costYuan?.let { " · 参考成本 ${Cost.format(it)} 元" }.orEmpty()
                )
            )
            notifyDone(ok)
            return Result.success()
        } catch (e: CancellationException) {
            dao.get(bankId)?.let {
                dao.upsert(it.copy(stage = "CANCELED", message = "已取消（进度已保留）"))
            }
            throw e
        } catch (e: Exception) {
            return fail(bankId, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun generatorLabel(primary: String, others: Int) =
        if (others > 0) "$primary +${others}部交叉验证" else primary

    private suspend fun fail(bankId: Long, message: String): Result {
        container.database.generateJobDao().upsert(
            (container.database.generateJobDao().get(bankId) ?: GenerateJob(bankId = bankId))
                .copy(stage = "FAILED", errorMessage = message)
        )
        return Result.failure()
    }

    private fun foregroundInfo(text: String, progress: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 生成", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(applicationContext, CHANNEL_ID) else
            Notification.Builder(applicationContext)
        val notification = builder
            .setContentTitle("刷了个题 · AI 生成答案")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (total > 0) setProgress(total, progress.coerceIn(0, total), false)
                else setProgress(0, 0, true)
            }
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(ok: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AI 生成", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(applicationContext, CHANNEL_ID) else
                Notification.Builder(applicationContext)
            nm.notify(
                NOTIFICATION_ID + 1,
                builder
                    .setContentTitle("AI 生成完成：$ok 题")
                    .setContentText("回到应用查看答案与置信度")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
