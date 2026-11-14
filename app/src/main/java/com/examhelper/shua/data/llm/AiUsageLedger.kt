package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.AiUsageDao
import com.examhelper.shua.data.db.AiUsageRecord
import com.examhelper.shua.data.db.LlmProvider
import java.util.UUID

enum class AiUsageCategory {
    IMPORT_PARSE,
    ANSWER_GENERATE,
    ASK_AI,
    DEEP_SOLVE,
    MINIMAL_SOLVE,
    CONNECTION_TEST,
    OTHER
}

data class AiUsageContext(
    val category: AiUsageCategory = AiUsageCategory.OTHER,
    val taskId: String = UUID.randomUUID().toString()
)

class AiUsageRecorder(private val dao: AiUsageDao) {
    suspend fun record(provider: LlmProvider, context: AiUsageContext, usage: ChatUsage) {
        val hit = usage.normalizedCacheHit().toLong()
        val miss = if (usage.cacheMissTokens > 0) usage.cacheMissTokens.toLong()
        else (usage.promptTokens - hit).coerceAtLeast(0).toLong()
        dao.insert(
            AiUsageRecord(
                callId = UUID.randomUUID().toString(),
                taskId = context.taskId,
                providerId = provider.id,
                providerName = provider.name,
                model = provider.model,
                category = context.category.name,
                cacheHitTokens = hit,
                cacheMissTokens = miss,
                outputTokens = usage.completionTokens.toLong()
            )
        )
    }
}
