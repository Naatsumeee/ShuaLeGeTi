package com.examhelper.shua.data.generate

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.llm.Prompts
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.model.DissentEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** token 用量累加器 */
class UsageAcc(
    var hit: Long = 0,
    var miss: Long = 0,
    var out: Long = 0
) {
    fun add(u: com.examhelper.shua.data.llm.ChatUsage) {
        val hitTokens = u.normalizedCacheHit()
        hit += hitTokens
        // 未命中 = 总输入 - 命中；原生 miss 字段优先
        miss += if (u.cacheMissTokens > 0) u.cacheMissTokens
        else (u.promptTokens - hitTokens).coerceAtLeast(0)
        out += u.completionTokens
    }

    fun toUsage() = com.examhelper.shua.data.llm.ChatUsage(
        promptTokens = (hit + miss).toInt(),
        completionTokens = out.toInt(),
        cacheHitTokens = hit.toInt(),
        cacheMissTokens = miss.toInt()
    )

    companion object {
        /**
         * 从单次 usage 构造累加器（务必走 add，而不是直接读原生字段）。
         *
         * 中转/兼容端点常把命中数放在 OpenAI 的 prompt_tokens_details.cached_tokens，
         * 而 DeepSeek 原生 prompt_cache_hit_tokens 缺失；直接读原生字段会得到
         * hit=0、miss=0、prompt=0，命中率于是永远显示 0%。这是该 bug 的根因。
         */
        fun of(u: com.examhelper.shua.data.llm.ChatUsage): UsageAcc =
            UsageAcc().apply { add(u) }
    }
}

/** 单个模型对一题的裁决 */
data class SingleVerdict(
    val providerName: String,
    val answer: String?,
    val analysis: String?,
    val selfConfidence: Int?
)

/** 多模型聚合后的结果 */
data class AggregatedVerdict(
    val answer: String?,
    val analysis: String?,
    val confidence: Int?,
    val agreement: String?,
    val dissents: List<DissentEntry>
)

/**
 * AI 答案生成器（批量模式）。
 * 单模型：按批请求（每批多题），共享稳定系统提示词 → 提升前缀缓存命中、大幅减少请求数与费用。
 * 三省六部：每个部按批作答，再逐题聚合（主部高权重 + 一致率 + 分歧记录）。
 */
class AnswerGenerator(
    private val client: LlmClient = LlmClient(),
    private val systemPrompt: String = Prompts.ANSWER_DEFAULT
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class AnswerDto(
        val i: Int = 0,
        val answer: String? = null,
        val analysis: String? = null,
        val confidence: Int? = null
    )

    @Serializable
    data class BatchResponseDto(val results: List<AnswerDto> = emptyList())

    /** 一次请求批量作答；返回按输入顺序的裁决（失败题为 null）+ 本次 token 用量 */
    suspend fun askBatch(provider: LlmProvider, questions: List<Question>): BatchOutcome {
        if (questions.isEmpty()) return BatchOutcome(emptyList(), com.examhelper.shua.data.llm.ChatUsage())
        val result = client.chatWithUsage(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = buildBatchPrompt(questions),
            jsonMode = true,
            temperature = 0.1,
            maxTokens = 3000
        )
        val content = result.content
        val cleaned = LlmQuestionParser.stripCodeFence(content)
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))
        val verdicts: List<SingleVerdict?> = if (start < 0 || end <= start) {
            questions.map { null }
        } else {
            val dto = runCatching { json.decodeFromString<BatchResponseDto>(cleaned.substring(start, end + 1)) }
                .getOrElse {
                    runCatching { json.decodeFromString<List<AnswerDto>>(cleaned.substring(start, end + 1)) }
                        .map { BatchResponseDto(it) }
                        .getOrDefault(BatchResponseDto())
                }
            val byIndex = dto.results.associateBy { it.i }
            questions.indices.map { idx ->
                val d = byIndex[idx + 1] ?: return@map null
                SingleVerdict(
                    providerName = provider.name,
                    answer = d.answer?.trim()?.takeIf { it.isNotBlank() },
                    analysis = d.analysis?.trim()?.takeIf { it.isNotBlank() },
                    selfConfidence = d.confidence?.coerceIn(0, 100)
                )
            }
        }
        return BatchOutcome(verdicts, result.usage)
    }

    data class BatchOutcome(val verdicts: List<SingleVerdict?>, val usage: com.examhelper.shua.data.llm.ChatUsage)

    /** 三省六部批量聚合：主部 + 各部各自批量作答，逐题聚合 */
    suspend fun aggregateBatch(
        questions: List<Question>,
        primary: LlmProvider,
        others: List<LlmProvider>
    ): Pair<List<AggregatedVerdict>, com.examhelper.shua.data.llm.ChatUsage> {
        val primaryOutcome = askBatch(primary, questions)
        val primaryVerdicts = primaryOutcome.verdicts
        val totalUsage = UsageAcc.of(primaryOutcome.usage)
        if (others.isEmpty()) {
            return questions.indices.map { i ->
                AggregatedVerdict(
                    answer = primaryVerdicts[i]?.answer,
                    analysis = primaryVerdicts[i]?.analysis,
                    confidence = primaryVerdicts[i]?.selfConfidence,
                    agreement = null,
                    dissents = emptyList()
                )
            } to totalUsage.toUsage()
        }
        val otherOutcomes: List<BatchOutcome> = kotlinx.coroutines.coroutineScope {
            others.map { p ->
                async { askBatch(p, questions) }
            }.map { it.await() }
        }
        otherOutcomes.forEach { o ->
            totalUsage.add(o.usage)
        }
        val aggregated = questions.indices.map { i ->
            val pv = primaryVerdicts[i]
            val ovs = otherOutcomes.mapNotNull { it.verdicts.getOrNull(i) }
            val agreeCount = ovs.count { answersMatch(pv?.answer, it.answer) }
            val totalParts = ovs.size + 1
            val agreeRatio = if (ovs.isEmpty()) 1.0 else agreeCount.toDouble() / ovs.size
            val confidence = (
                (pv?.selfConfidence ?: 75) * 0.6 + agreeRatio * 100.0 * 0.4
                ).roundToInt().coerceIn(0, 100)
            AggregatedVerdict(
                answer = pv?.answer,
                analysis = pv?.analysis,
                confidence = confidence,
                agreement = "${agreeCount + 1}/$totalParts 部一致",
                dissents = ovs.filter { !answersMatch(pv?.answer, it.answer) }.map {
                    DissentEntry(it.providerName, it.answer ?: "未给出", it.analysis, it.selfConfidence)
                }
            )
        }
        return aggregated to totalUsage.toUsage()
    }

    companion object {
        const val BATCH_SIZE = 12

        /** 紧凑序列化：同章只报一次、共用题干/选项组内去重，压缩输入 token */
        fun buildBatchPrompt(questions: List<Question>): String = buildString {
            appendLine("共 ${questions.size} 题，逐题作答：")
            var lastChapter: String? = null
            var lastSharedKey: String? = null
            questions.forEachIndexed { idx, q ->
                val chapter = q.chapter?.takeIf { it.isNotBlank() }
                if (chapter != lastChapter && chapter != null) {
                    appendLine("【章节】$chapter")
                    lastChapter = chapter
                }
                val sharedKey = q.sharedStem?.take(30) ?: q.sharedOptionsJson?.take(30)
                if (sharedKey != null && sharedKey != lastSharedKey) {
                    q.sharedStem?.let { appendLine("【共用题干】$it") }
                    q.sharedOptionsJson?.let {
                        val opts = com.examhelper.shua.data.model.JsonCodec.decodeOptions(it)
                        if (opts.isNotEmpty()) {
                            appendLine("【共用选项】" + opts.joinToString("；") { "${it.key}.${it.text}" })
                        }
                    }
                    lastSharedKey = sharedKey
                }
                append("${idx + 1}.(${typeCn(q.type)})${q.stem}")
                val opts = com.examhelper.shua.data.model.JsonCodec.decodeOptions(q.optionsJson)
                if (opts.isNotEmpty()) {
                    append(" " + opts.joinToString(" ") { "${it.key}.${it.text}" })
                }
                appendLine()
            }
        }

        fun typeCn(t: QuestionType): String = when (t) {
            QuestionType.SINGLE -> "单选题"
            QuestionType.MULTIPLE -> "多选题"
            QuestionType.JUDGE -> "判断题"
            QuestionType.FILL -> "填空题"
            QuestionType.SHORT_ANSWER -> "简答题"
            QuestionType.TERM -> "名词解释题"
        }

        fun answersMatch(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            val na = normalize(a)
            val nb = normalize(b)
            if (na.length <= 3 && nb.length <= 3) {
                return na.toCharArray().sorted() == nb.toCharArray().sorted()
            }
            return na == nb
        }

        private fun normalize(s: String): String {
            var t = s.trim().uppercase()
            when (t) {
                "√", "T", "Y", "TRUE", "对", "正确", "是" -> t = "对"
                "×", "X", "F", "N", "FALSE", "错", "错误", "否" -> t = "错"
            }
            return t.replace(" ", "")
        }
    }
}
