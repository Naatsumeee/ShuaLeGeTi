package com.examhelper.shua.data.parse

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LLM 题库结构化解析器：
 * 输入 OCR / 复制得到的杂乱文本，输出结构化题目列表。
 * 分块调用，自动剥离 markdown 代码围栏，容错 JSON。
 */
class LlmQuestionParser(
    private val llmClient: LlmClient = LlmClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class ResponseDto(val questions: List<QuestionDto> = emptyList())

    @Serializable
    data class QuestionDto(
        val type: String = "single",
        val stem: String = "",
        val options: List<QuestionOption> = emptyList(),
        val answer: String? = null,
        val analysis: String? = null,
        val chapter: String? = null
    )

    suspend fun parse(
        provider: LlmProvider,
        rawText: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ParsedQuestion> {
        val chunks = chunkText(rawText, CHUNK_SIZE)
        val all = mutableListOf<ParsedQuestion>()
        chunks.forEachIndexed { i, chunk ->
            val questions = parseChunk(provider, chunk)
            all += questions
            onProgress(i + 1, chunks.size)
        }
        return all
    }

    private suspend fun parseChunk(provider: LlmProvider, chunk: String): List<ParsedQuestion> {
        val content = llmClient.chat(
            provider = provider,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = chunk,
            jsonMode = true,
            temperature = 0.1
        )
        val cleaned = stripCodeFence(content)
        // 容错：找第一个 { 或 [
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val endBrace = cleaned.lastIndexOf('}')
        val endBracket = cleaned.lastIndexOf(']')
        val end = maxOf(endBrace, endBracket)
        if (start < 0 || end <= start) return emptyList()
        val jsonText = cleaned.substring(start, end + 1)
        val dto = runCatching { json.decodeFromString<ResponseDto>(jsonText) }
            .getOrElse {
                // 直接是数组的情况
                runCatching { json.decodeFromString<List<QuestionDto>>(jsonText) }
                    .map { ResponseDto(it) }
                    .getOrElse { return emptyList() }
            }
        return dto.questions.filter { it.stem.isNotBlank() }.map { it.toParsed() }
    }

    private fun QuestionDto.toParsed(): ParsedQuestion {
        val (opts, ans) = OptionNormalizer.normalize(options, answer)
        return ParsedQuestion(
            chapter = chapter?.takeIf { it.isNotBlank() },
            type = mapType(type),
            stem = stem.trim(),
            options = opts.toMutableList(),
            answer = ans,
            analysis = analysis?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        )
    }

    companion object {
        private const val CHUNK_SIZE = 2800

        val SYSTEM_PROMPT = """
            你是题库结构化引擎。输入是从试卷/习题册文档中 OCR 识别或复制出来的文本，可能存在乱序、错字、页眉页脚干扰。请把它解析为结构化题目。

            只输出严格的 JSON，不要任何解释或 markdown 代码块，格式：
            {"questions":[{"type":"single","stem":"题干","options":[{"key":"A","text":"选项内容"}],"answer":"B","analysis":"解析","chapter":"章节名"}]}

            规则：
            1. type 取值：single(单选) multiple(多选) judge(判断) fill(填空) short_answer(简答/论述)
            2. 选项打"√"或"✓"的为正确答案；题干末尾形如"：B"的字母是答案；单独的"答案：X"行是答案；判断题答案用"对"或"错"
            3. 选项 key 必须从 A 开始连续编号，同一字母只能出现一次；若 OCR 把同一选项拆成多行或重复识别，合并为一条选项
            4. 原文没有给出答案或解析时，answer/analysis 填 null，不要自己编造
            5. 纠正明显的 OCR 错字（形近字混淆），保持题意不变；无法确认的保留原样
            6. 页眉页脚（页码、印刷店名、学校名重复出现的）忽略
            7. 章节标题放入 chapter 字段（如"第一章 XX"或"一、单项选择题"中的题型部分不要放）
            8. 按原文顺序输出所有题目，不要遗漏、不要合并
            9. 多选题 answer 用大写字母连写如 "ABD"
        """.trimIndent()

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
