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
    private val llmClient: LlmClient = LlmClient()
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
        val chapter: String? = null
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
            systemPrompt = SYSTEM_PROMPT,
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
            analysis = analysis?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        )
    }

    companion object {
        const val CHUNK_SIZE = 6000

        val SYSTEM_PROMPT = """
            你是医学题库结构化引擎。输入是从试卷/习题册文档中 OCR 识别或复制出来的文本，可能存在乱序、错字、页眉页脚干扰。请把它解析为结构化题目。

            只输出严格的 JSON，不要任何解释或 markdown 代码块，格式：
            {"questions":[{"type":"single","subtype":"A1","stem":"题干","sharedStem":null,"sharedOptions":[],"options":[{"key":"A","text":"选项内容"}],"answer":"B","analysis":"解析","chapter":"章节名"}]}

            规则：
            1. type 取值：single(单选) multiple(多选) judge(判断) fill(填空) short_answer(简答/论述)
            2. 医学题型子类型 subtype：A1=单句型单选题；A2=病例摘要型单选题；A3=一组子题共用同一段病例材料（共用题干）；B=一组题干共用同一组备选选项（共用备选答案的配伍题）
            3. A3 型：组内每个子题都带相同的 sharedStem（病例材料全文），type 为 single，各自有自己的 options 和 answer
            4. B 型：组内每题都带相同的 sharedOptions（共用选项数组），题目自身 options 为空数组，answer 用共用选项的字母
            5. 选项打"√"或"✓"的为正确答案；题干末尾形如"：B"的字母是答案；单独的"答案：X"行是答案；判断题答案用"对"或"错"
            6. 选项 key 必须从 A 开始连续编号，同一字母只能出现一次；若 OCR 把同一选项拆成多行或重复识别，合并为一条选项
            7. 原文没有给出答案或解析时，answer/analysis 填 null，不要自己编造
            8. 纠正明显的 OCR 错字（形近字混淆），保持题意不变；无法确认的保留原样
            9. 页眉页脚（页码、印刷店名、学校名重复出现的）忽略
            10. 章节标题放入 chapter 字段（题型分组标题如"一、单项选择题"不要放入 chapter）
            11. 按原文顺序输出所有题目，不要遗漏、不要合并
            12. 多选题 answer 用大写字母连写如 "ABD"
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
