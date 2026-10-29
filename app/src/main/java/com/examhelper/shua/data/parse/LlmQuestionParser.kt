package com.examhelper.shua.data.parse

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM 题库结构化解析器：
 * 输入 OCR / 复制得到的杂乱文本，输出结构化题目列表。
 * 支持分块并行（后台任务调度）、剥离 markdown 围栏、容错 JSON。
 */
class LlmQuestionParser(
    private val llmClient: LlmClient = LlmClient(),
    private val systemPrompt: String = com.examhelper.shua.data.llm.Prompts.PARSE_DEFAULT
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class ResponseDto(val questions: List<QuestionDto> = emptyList())

    @Serializable
    data class QuestionDto(
        val type: String = "single",
        val subtype: String? = null,
        val stem: String = "",
        val sharedStem: String? = null,
        val sharedOptions: List<QuestionOption> = emptyList(),
        val options: List<QuestionOption> = emptyList(),
        val answer: String? = null,
        val analysis: String? = null,
        val chapter: String? = null,
        val page: Int? = null
    )

    /** 供后台任务逐块调用 */
    suspend fun parseChunkText(provider: LlmProvider, chunk: String): List<QuestionDto> =
        parseChunk(provider, chunk)

    suspend fun parse(
        provider: LlmProvider,
        rawText: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ParsedQuestion> {
        val chunks = ChunkPlanner.plan(rawText, CHUNK_SIZE)
        val all = mutableListOf<ParsedQuestion>()
        chunks.forEachIndexed { i, chunk ->
            all += parseChunk(provider, chunk.text).filter { it.stem.isNotBlank() }.map { it.toParsed() }
            onProgress(i + 1, chunks.size)
        }
        return finalize(all, rawText)
    }

    private suspend fun parseChunk(provider: LlmProvider, chunk: String): List<QuestionDto> {
        val content = llmClient.chat(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = chunk,
            jsonMode = true,
            temperature = 0.1
        )
        val cleaned = stripCodeFence(content)
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))
        if (start < 0 || end <= start) return emptyList()
        val jsonText = cleaned.substring(start, end + 1)
        val dto = runCatching { json.decodeFromString<ResponseDto>(jsonText) }
            .getOrElse {
                runCatching { json.decodeFromString<List<QuestionDto>>(jsonText) }
                    .map { ResponseDto(it) }
                    .getOrElse { return emptyList() }
            }
        return dto.questions.filter { it.stem.isNotBlank() }
    }

    fun QuestionDto.toParsed(): ParsedQuestion {
        val (opts, ans) = OptionNormalizer.normalize(options, answer)
        val (sopts, _) = OptionNormalizer.normalize(sharedOptions, null)
        return ParsedQuestion(
            chapter = chapter?.takeIf { it.isNotBlank() },
            type = mapType(type),
            subtype = subtype?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            stem = stem.trim(),
            sharedStem = sharedStem?.trim()?.takeIf { it.isNotBlank() },
            sharedOptions = sopts.toMutableList(),
            options = opts.toMutableList(),
            answer = ans,
            analysis = analysis?.trim()?.takeIf { it.isNotBlank() && it != "null" },
            sourcePage = page
        )
    }

    companion object {
        const val CHUNK_SIZE = 6000

        fun mapType(raw: String): QuestionType = when (raw.trim().lowercase()) {
            "multiple", "多选", "多选题" -> QuestionType.MULTIPLE
            "judge", "判断", "判断题", "bool", "boolean" -> QuestionType.JUDGE
            "fill", "填空", "填空题", "blank" -> QuestionType.FILL
            "short_answer", "简答", "简答题", "论述", "论述题", "essay", "short" -> QuestionType.SHORT_ANSWER
            "term", "名词解释", "名词解释题", "解释名词", "名解", "term_explanation" -> QuestionType.TERM
            else -> QuestionType.SINGLE
        }

        fun stripCodeFence(text: String): String {
            var t = text.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                if (t.endsWith("```")) t = t.removeSuffix("```")
            }
            return t.trim()
        }

        /**
         * 按题目边界切分（切点永远落在一道题开始之前，不会把题目劈成两半）。
         * 见 [ChunkPlanner]。
         */
        fun chunkText(text: String, size: Int): List<String> =
            ChunkPlanner.plan(text, size).map { it.text }

        /** 答案表行：`答案：1.B 2.C`、`参考答案 1、B`、`1.A 2.B 3.C 4.D` */
        private val answerTableRe = Regex(
            """(?:参考)?答案\s*[:：]?\s*(.+)"""
        )
        private val answerPairRe = Regex("""(\d{1,4})\s*[.、．:：)）]?\s*([A-EGH]{1,8}|对|错|正确|错误)""")

        private fun parseAnswerTable(rawText: String): Map<Int, String> {
            // 只在文末 40% 里找，避免把题干中的「答案」字样误当答案表
            val tailStart = (rawText.length * 0.6).toInt().coerceAtLeast(0)
            val tail = rawText.substring(tailStart)
            val table = mutableMapOf<Int, String>()
            for (line in tail.lines()) {
                val hit = answerTableRe.find(line) ?: continue
                val body = hit.groupValues[1]
                val pairs = answerPairRe.findAll(body).toList()
                if (pairs.size < 2) continue
                pairs.forEach { p ->
                    val no = p.groupValues[1].toIntOrNull() ?: return@forEach
                    val ans = p.groupValues[2]
                    if (no in 1..9999) table.putIfAbsent(no, ans)
                }
            }
            return table
        }

        /**
         * 跨块收口（[parse] 用）：去重 + 文末答案表回填。
         */
        fun finalize(questions: List<ParsedQuestion>, rawText: String): List<ParsedQuestion> {
            val merged = linkedMapOf<String, ParsedQuestion>()
            for (q in questions) {
                val key = ChunkPlanner.dedupeKey(q.stem)
                if (key.isEmpty()) continue
                val prev = merged[key]
                merged[key] = if (prev == null) q else prev.copy(
                    answer = prev.answer ?: q.answer,
                    analysis = prev.analysis ?: q.analysis,
                    options = if (prev.options.isNotEmpty()) prev.options else q.options,
                    chapter = prev.chapter ?: q.chapter,
                    sharedStem = prev.sharedStem ?: q.sharedStem,
                    sharedOptions = if (prev.sharedOptions.isNotEmpty()) prev.sharedOptions else q.sharedOptions,
                    sourcePage = prev.sourcePage ?: q.sourcePage
                )
            }
            val list = merged.values.toMutableList()
            val table = parseAnswerTable(rawText)
            if (table.isNotEmpty() && list.any { it.answer.isNullOrBlank() }) {
                for (i in list.indices) {
                    if (!list[i].answer.isNullOrBlank()) continue
                    val ans = table[i + 1] ?: continue
                    list[i] = list[i].copy(answer = ans)
                }
            }
            return list
        }

        /**
         * 对落盘的 JSONL 行做同样的收口（后台分块并行解析后调用）：
         * 去重 + 文末答案表回填。返回按原文顺序排好的新行。
         */
        fun finalizeLines(lines: List<ParsedLine>, rawText: String): List<ParsedLine> {
            val ordered = lines.sortedBy { it.chunkIndex * 100000 + it.indexInChunk }
            val merged = linkedMapOf<String, ParsedLine>()
            for (l in ordered) {
                val key = ChunkPlanner.dedupeKey(l.stem)
                if (key.isEmpty()) continue
                val prev = merged[key]
                merged[key] = if (prev == null) l else prev.copy(
                    answer = prev.answer ?: l.answer,
                    analysis = prev.analysis ?: l.analysis,
                    options = if (prev.options.isNotEmpty()) prev.options else l.options,
                    chapter = prev.chapter ?: l.chapter,
                    sharedStem = prev.sharedStem ?: l.sharedStem,
                    sharedOptions = if (prev.sharedOptions.isNotEmpty()) prev.sharedOptions else l.sharedOptions,
                    page = prev.page ?: l.page
                )
            }
            val list = merged.values.toMutableList()
            val table = parseAnswerTable(rawText)
            if (table.isNotEmpty() && list.any { it.answer.isNullOrBlank() }) {
                for (i in list.indices) {
                    if (!list[i].answer.isNullOrBlank()) continue
                    val ans = table[i + 1] ?: continue
                    list[i] = list[i].copy(answer = ans)
                }
            }
            return list
        }
    }
}
