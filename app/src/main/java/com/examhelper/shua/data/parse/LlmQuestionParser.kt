package com.examhelper.shua.data.parse

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.LlmClient
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
        val chunks = chunkText(rawText, CHUNK_SIZE)
        val all = mutableListOf<ParsedQuestion>()
        chunks.forEachIndexed { i, chunk ->
            all += parseChunk(provider, chunk).filter { it.stem.isNotBlank() }.map { it.toParsed() }
            onProgress(i + 1, chunks.size)
        }
        return all
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

        fun chunkText(text: String, size: Int): List<String> {
            if (text.length <= size) return listOf(text)
            val chunks = mutableListOf<StringBuilder>()
            var current = StringBuilder()
            for (line in text.lines()) {
                if (current.length + line.length > size && current.isNotBlank()) {
                    chunks += current
                    current = StringBuilder()
                }
                current.appendLine(line)
            }
            if (current.isNotBlank()) chunks += current
            return chunks.map { it.toString() }
        }
    }
}
