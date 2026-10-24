package com.examhelper.shua.data.generate

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.model.DissentEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

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
 * AI 答案生成器。
 * 单模型：置信度 = 模型自评。
 * 三省六部（多模型）：以主部为基准，置信度 = 0.6×主部自评 + 0.4×各部一致率；分歧记录各部意见。
 */
class AnswerGenerator(
    private val client: LlmClient = LlmClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class AnswerDto(
        val answer: String? = null,
        val analysis: String? = null,
        val confidence: Int? = null
    )

    suspend fun askOne(provider: LlmProvider, question: Question): SingleVerdict {
        val content = client.chat(
            provider = provider,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildQuestionPrompt(question),
            jsonMode = true,
            temperature = 0.1
        )
        val cleaned = LlmQuestionParser.stripCodeFence(content)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val dto = runCatching { json.decodeFromString<AnswerDto>(cleaned.substring(start, end + 1)) }
                .getOrNull()
            if (dto != null) {
                return SingleVerdict(
                    providerName = provider.name,
                    answer = dto.answer?.trim()?.takeIf { it.isNotBlank() },
                    analysis = dto.analysis?.trim()?.takeIf { it.isNotBlank() },
                    selfConfidence = dto.confidence?.coerceIn(0, 100)
                )
            }
        }
        // JSON 解析失败：把全文当答案文本兜底
        return SingleVerdict(provider.name, cleaned.take(80), null, null)
    }

    /**
     * 三省六部聚合：并行询问 primary + others。
     */
    suspend fun aggregate(
        question: Question,
        primary: LlmProvider,
        others: List<LlmProvider>
    ): AggregatedVerdict {
        val primaryVerdict = askOne(primary, question)
        if (others.isEmpty()) {
            return AggregatedVerdict(
                answer = primaryVerdict.answer,
                analysis = primaryVerdict.analysis,
                confidence = primaryVerdict.selfConfidence,
                agreement = null,
                dissents = emptyList()
            )
        }
        val otherVerdicts = coroutineScope {
            others.map { p ->
                async {
                    runCatching { askOne(p, question) }.getOrNull()
                }
            }.map { it.await() }
        }

        val validOthers = otherVerdicts.filterNotNull()
        val agreeCount = validOthers.count {
            answersMatch(primaryVerdict.answer, it.answer)
        }
        val totalParts = validOthers.size + 1
        val agreeRatio = if (validOthers.isEmpty()) 1.0 else agreeCount.toDouble() / validOthers.size
        val confidence = (
            (primaryVerdict.selfConfidence ?: 75) * 0.6 + agreeRatio * 100.0 * 0.4
            ).roundToInt().coerceIn(0, 100)

        val dissents = validOthers
            .filter { !answersMatch(primaryVerdict.answer, it.answer) }
            .map {
                DissentEntry(
                    provider = it.providerName,
                    answer = it.answer ?: "未给出",
                    analysis = it.analysis,
                    selfConfidence = it.selfConfidence
                )
            }

        return AggregatedVerdict(
            answer = primaryVerdict.answer,
            analysis = primaryVerdict.analysis,
            confidence = confidence,
            agreement = "${agreeCount + 1}/$totalParts 部一致",
            dissents = dissents
        )
    }

    companion object {
        val SYSTEM_PROMPT = """
            你是严谨的学科答题专家。根据题目给出答案与解析。只输出严格 JSON，不要解释和代码块：
            {"answer":"...","analysis":"...","confidence":85}

            规则：
            1. 选择题 answer 用大写字母，多选用字母连写如 "ABD"；判断题用 "对" 或 "错"；填空/简答给参考答案文本
            2. confidence 是你对该答案的把握程度 0-100，诚实评估；不确定就给低分
            3. analysis 简明扼要说明理由（考点、排除法、关键依据），100 字以内
            4. 无法作答时 confidence 给 30 以下并在 analysis 说明原因
        """.trimIndent()

        fun buildQuestionPrompt(q: Question): String = buildString {
            appendLine("【题型】${typeCn(q.type)}")
            if (!q.chapter.isNullOrBlank()) appendLine("【章节】${q.chapter}")
            appendLine("【题干】${q.stem}")
            if (!q.optionsJson.isNullOrBlank()) {
                appendLine("【选项】")
                com.examhelper.shua.data.model.JsonCodec.decodeOptions(q.optionsJson).forEach {
                    appendLine("${it.key}. ${it.text}")
                }
            }
            appendLine("请作答。")
        }

        fun typeCn(t: QuestionType): String = when (t) {
            QuestionType.SINGLE -> "单选题"
            QuestionType.MULTIPLE -> "多选题"
            QuestionType.JUDGE -> "判断题"
            QuestionType.FILL -> "填空题"
            QuestionType.SHORT_ANSWER -> "简答题"
        }

        /** 比较两个答案是否一致（忽略大小写、顺序、对错变体） */
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
