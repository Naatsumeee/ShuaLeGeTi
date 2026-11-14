package com.examhelper.shua.data.analysis

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
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.examhelper.shua.R
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.llm.AiUsageCategory
import com.examhelper.shua.data.llm.AiUsageContext
import com.examhelper.shua.data.llm.ChatMessage
import com.examhelper.shua.data.llm.Prompts
import com.examhelper.shua.data.llm.ReasoningEffort
import com.examhelper.shua.data.model.JsonCodec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

class PdfAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_QUESTION_IDS = "questionIds"
        const val KEY_LEVEL = "level"
        const val KEY_FORCE_ALL = "forceAll"
        const val KEY_TOTAL = "total"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_MESSAGE = "message"
        const val MINIMAL_MAX_TOKENS = 512
        const val DEEP_MAX_TOKENS = 4096
        private const val CHANNEL_ID = "pdf_analysis"
        private const val NOTIFICATION_ID = 2101

        fun uniqueName(source: String, bankId: Long) = "pdf_analysis_${source}_$bankId"

        fun enqueue(
            context: Context,
            source: String,
            bankId: Long,
            questionIds: List<Long>,
            level: AnalysisLevel,
            forceAll: Boolean
        ): String {
            val name = uniqueName(source, bankId)
            val request = OneTimeWorkRequestBuilder<PdfAnalysisWorker>()
                .setInputData(
                    workDataOf(
                        KEY_QUESTION_IDS to JsonCodec.encodeIdList(questionIds.distinct()),
                        KEY_LEVEL to level.name,
                        KEY_FORCE_ALL to forceAll
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return name
        }
    }

    private val container by lazy { ShuaApp.instance.container }

    override suspend fun doWork(): Result {
        val ids = JsonCodec.decodeIdList(inputData.getString(KEY_QUESTION_IDS))
        val level = runCatching {
            AnalysisLevel.valueOf(inputData.getString(KEY_LEVEL).orEmpty())
        }.getOrNull() ?: return Result.failure()
        val forceAll = inputData.getBoolean(KEY_FORCE_ALL, false)
        val byId = container.database.questionDao().getByIds(ids).associateBy { it.id }
        val questions = ids.mapNotNull(byId::get)
        val plan = PdfAnalysisPlan.create(questions, level, skipExisting = !forceAll)
        if (plan.targets.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo("准备维护题目解析…", 0, plan.targets.size)) }
        val provider = runCatching {
            val all = container.database.providerDao().observeAll().first().filter { it.enabled }
            all.firstOrNull { it.isPrimary } ?: all.first()
        }.getOrElse {
            setProgress(failureProgress(level, 0, plan.targets.size, it.message ?: "未配置可用模型"))
            return Result.failure()
        }
        val reasoning = container.settings.settings.first().reasoningEffort
        var done = 0
        var failed = 0

        for (question in plan.targets) {
            if (isStopped) return Result.failure()
            runCatching {
                val result = container.llmClient.chatWithMessages(
                    provider = provider,
                    messages = listOf(
                        ChatMessage(
                            "system",
                            if (level == AnalysisLevel.DEEP) Prompts.DEEP_SOLVE_DEFAULT
                            else Prompts.MINIMAL_SOLVE_DEFAULT
                        ),
                        ChatMessage("user", buildPrompt(question))
                    ),
                    maxTokens = if (level == AnalysisLevel.DEEP) DEEP_MAX_TOKENS else MINIMAL_MAX_TOKENS,
                    reasoningEffort = if (level == AnalysisLevel.DEEP) reasoning else ReasoningEffort.NONE,
                    usageContext = AiUsageContext(
                        if (level == AnalysisLevel.DEEP) AiUsageCategory.DEEP_SOLVE
                        else AiUsageCategory.MINIMAL_SOLVE,
                        "pdf-${level.name.lowercase()}:${question.id}:${System.currentTimeMillis()}"
                    )
                )
                if (result.truncated) error("模型输出被长度限制截断")
                val reply = parseReply(result.content)
                val analysis = reply.analysis?.takeIf { it.isNotBlank() }
                    ?: error("模型未返回解析")
                if (level == AnalysisLevel.DEEP) {
                    container.studyRepo.applyDeepSolve(question, analysis, reply.answer)
                } else {
                    container.studyRepo.applyMinimalSolve(question, analysis)
                }
            }.onFailure { failed++ }

            done++
            val message = "${levelLabel(level)}：$done/${plan.targets.size}，失败 $failed"
            setProgress(
                workDataOf(
                    KEY_LEVEL to level.name,
                    KEY_TOTAL to plan.targets.size,
                    KEY_DONE to done,
                    KEY_FAILED to failed,
                    KEY_MESSAGE to message
                )
            )
            runCatching {
                setForeground(foregroundInfo(message, done, plan.targets.size))
            }
        }

        val message = "${levelLabel(level)}完成：成功 ${plan.targets.size - failed}，失败 $failed"
        setProgress(
            workDataOf(
                KEY_LEVEL to level.name,
                KEY_TOTAL to plan.targets.size,
                KEY_DONE to done,
                KEY_FAILED to failed,
                KEY_MESSAGE to message
            )
        )
        notifyDone(message)
        return Result.success()
    }

    private fun buildPrompt(question: Question): String = buildString {
        appendLine("题型：${question.type.name}")
        question.stem.takeIf { it.isNotBlank() }?.let { appendLine("题干：$it") }
        JsonCodec.decodeOptions(question.optionsJson)
            .ifEmpty { JsonCodec.decodeOptions(question.sharedOptionsJson) }
            .takeIf { it.isNotEmpty() }
            ?.let { opts -> appendLine("选项：" + opts.joinToString("；") { "${it.key}.${it.text}" }) }
        question.answer?.takeIf { it.isNotBlank() }?.let { appendLine("题库参考答案：$it") }
    }

    private fun parseReply(raw: String): AnalysisReply {
        val text = raw.trim()
            .removePrefix("```markdown")
            .removePrefix("```md")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (text.isBlank()) return AnalysisReply()
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
        if (start >= 0 && end > start) {
            runCatching { JsonCodec.json.decodeFromString<AnalysisReply>(text.substring(start, end + 1)) }
                .getOrNull()?.let { if (!it.analysis.isNullOrBlank()) return it }
        }
        val lines = text.lines()
        val answer = lines.firstOrNull()
            ?.let { Regex("""答案\s*[:：]\s*(.+)""").find(it)?.groupValues?.get(1)?.trim()?.trim('*', '`') }
        return AnalysisReply(
            answer = answer,
            analysis = lines.drop(1).joinToString("\n").trim().ifBlank { text }
        )
    }

    private fun failureProgress(level: AnalysisLevel, done: Int, total: Int, message: String) =
        workDataOf(
            KEY_LEVEL to level.name,
            KEY_TOTAL to total,
            KEY_DONE to done,
            KEY_FAILED to (total - done),
            KEY_MESSAGE to message
        )

    private fun levelLabel(level: AnalysisLevel) =
        if (level == AnalysisLevel.DEEP) "深度解析" else "极简解析"

    private fun foregroundInfo(text: String, progress: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "题目解析维护", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("刷了个题 · 题目解析维护")
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

    private fun notifyDone(message: String) {
        runCatching {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "题目解析维护", NotificationManager.IMPORTANCE_LOW)
            )
            manager.notify(
                NOTIFICATION_ID + 1,
                Notification.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle("题目解析维护完成")
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    @Serializable
    private data class AnalysisReply(
        val answer: String? = null,
        val analysis: String? = null
    )
}
