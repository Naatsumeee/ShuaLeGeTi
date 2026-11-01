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

    /**
     * 本次解析累计的 token 用量。AI 解析同样计费，后台任务要靠它把
     * 「解析花了多少」也记进任务里，否则成本只覆盖生成那一半。
     * 用原子量是因为分块是并发跑的（Semaphore 并发）。
     */
    private val usageHit = java.util.concurrent.atomic.AtomicLong(0)
    private val usageMiss = java.util.concurrent.atomic.AtomicLong(0)
    private val usageOut = java.util.concurrent.atomic.AtomicLong(0)

    fun snapshotUsage(): com.examhelper.shua.data.llm.ChatUsage =
        com.examhelper.shua.data.llm.ChatUsage(
            promptTokens = (usageHit.get() + usageMiss.get()).toInt(),
            completionTokens = usageOut.get().toInt(),
            cacheHitTokens = usageHit.get().toInt(),
            cacheMissTokens = usageMiss.get().toInt()
        )

    private fun recordUsage(u: com.examhelper.shua.data.llm.ChatUsage) {
        val hit = u.normalizedCacheHit().toLong()
        val miss = if (u.cacheMissTokens > 0) u.cacheMissTokens.toLong()
        else (u.promptTokens - hit).coerceAtLeast(0)
        usageHit.addAndGet(hit)
        usageMiss.addAndGet(miss)
        usageOut.addAndGet(u.completionTokens.toLong())
    }

    suspend fun parse(
        provider: LlmProvider,
        rawText: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ParsedQuestion> {
        val chunks = ChunkPlanner.plan(rawText, CHUNK_SIZE)
        val all = mutableListOf<ParsedQuestion>()
        chunks.forEachIndexed { i, chunk ->
            val fixed = parseChunk(provider, chunk.text)
                .filter { it.stem.isNotBlank() }
                .map { dto ->
                    // 章节名以本块原文标题为准：模型常把「第 X 章」前缀吞掉，
                    // 导致同一章被拆成两个章节
                    dto.copy(chapter = ChunkPlanner.resolveChapter(chunk.section, dto.chapter))
                }
                .map { it.toParsed() }
            all += fixed
            onProgress(i + 1, chunks.size)
        }
        return finalize(all, rawText)
    }

    private suspend fun parseChunk(
        provider: LlmProvider,
        chunk: String,
        depth: Int = 0
    ): List<QuestionDto> {
        val result = llmClient.chatWithUsage(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = chunk,
            jsonMode = true,
            temperature = 0.1,
            maxTokens = PARSE_MAX_TOKENS
        )
        val parsed = decodeQuestions(result.content)
        recordUsage(result.usage)
        if (parsed.isNotEmpty()) return parsed
        // 这一块没产出任何题目（多半是输出预算不够、JSON 被从中间截断）。
        // 直接把整块判失败会让这一段的题目全部消失 —— 也就是用户反馈的
        // 「首批次导入极易失败」：第一块题量最大，最容易撞上预算上限。
        // 这里把该块二分后交给下一层分别解析，局部失败不再连坐整块。
        if (depth >= MAX_SPLIT_DEPTH || chunk.length < MIN_SPLIT_CHARS) return emptyList()
        val halves = ChunkPlanner.plan(chunk, chunk.length / 2)
        if (halves.size < 2) return emptyList()
        return halves.flatMap { parseChunk(provider, it.text, depth + 1) }
    }

    private fun decodeQuestions(content: String): List<QuestionDto> {
        val cleaned = stripCodeFence(content)
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))
        if (start < 0 || end <= start) return emptyList()
        val jsonText = cleaned.substring(start, end + 1)
        val dto = runCatching { json.decodeFromString<ResponseDto>(jsonText) }
            .getOrElse {
                runCatching { json.decodeFromString<List<QuestionDto>>(jsonText) }
                    .map { ResponseDto(it) }
                    .getOrElse { return recoverQuestions(jsonText) }
            }
        return dto.questions.filter { it.stem.isNotBlank() }
            .ifEmpty { recoverQuestions(jsonText) }
    }

    /**
     * 抢救被截断的 JSON：按顶层对象逐个解析，能完整解出来的留下。
     *
     * 输出撞上 max_tokens 时 JSON 尾部会断在半个对象上，整体 decode 必然失败；
     * 但断点之前的题目都是完好的。早先直接返回空列表，等于把这一整段题目扔掉。
     */
    internal fun recoverQuestions(jsonText: String): List<QuestionDto> {
        val arr = jsonText.indexOf('[')
        val body = if (arr >= 0) jsonText.substring(arr + 1) else jsonText
        val out = mutableListOf<QuestionDto>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in body.indices) {
            val c = body[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { json.decodeFromString<QuestionDto>(body.substring(start, i + 1)) }
                            .getOrNull()
                            ?.takeIf { it.stem.isNotBlank() }
                            ?.let { out += it }
                        start = -1
                    }
                }
            }
        }
        return out
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

        /**
         * 单块解析的输出预算。
         *
         * 早先这里**完全没传 max_tokens**，用服务端默认值（常见 4096）。
         * 一个 6000 字的中文分块转成「题干 + 选项 + 解析」的 JSON 后经常超过这个数，
         * JSON 被从中间截断 → 整体反序列化失败 → 整块题目全部丢失。
         * 第一块题量最大、最容易撞线，表现就是「首批次 AI 解析极易失败」。
         */
        const val PARSE_MAX_TOKENS = 8192

        /** 分块解析无产出时的二分重试上限（防止病态输入无限递归） */
        private const val MAX_SPLIT_DEPTH = 2
        private const val MIN_SPLIT_CHARS = 800

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
         * 在已收口列表里找「同一道题」，返回下标；找不到返回 -1。
         *
         * 判据两层：
         * 1. 题干宽松相似（[ChunkPlanner.similarity]，能合并被 OCR 截短的副本）；
         * 2. 选项护栏——两边都有 ≥2 个选项时要求 ≥70% 重合，避免把题干雷同、
         *    选项完全不同的两道题误合并成一道（那会丢题）。
         */
        private fun <T> findSame(
            list: List<T>,
            stem: String,
            options: List<QuestionOption>,
            stemOf: (T) -> String,
            optionsOf: (T) -> List<QuestionOption>
        ): Int {
            var best = -1
            var bestScore = 0.0
            for (i in list.indices) {
                val sim = ChunkPlanner.similarity(stemOf(list[i]), stem)
                if (sim < ChunkPlanner.SAME_QUESTION) continue
                val other = optionsOf(list[i])
                if (other.size >= 2 && options.size >= 2) {
                    val ka = other.map { ChunkPlanner.dedupeKey(it.text) }.filter { it.isNotEmpty() }.toSet()
                    val kb = options.map { ChunkPlanner.dedupeKey(it.text) }.filter { it.isNotEmpty() }.toSet()
                    val union = ka.union(kb).size
                    if (union > 0 && ka.intersect(kb).size.toDouble() / union < 0.7) continue
                }
                if (sim > bestScore) {
                    bestScore = sim
                    best = i
                }
            }
            return best
        }

        /** 题干开头的题号（`1.` `2、` `（3）`），没有则 null */
        private val leadingNumberRe = Regex("""^\s*[（(]?\s*(\d{1,4})\s*[）)]?\s*[、.．,，:：)）]""")

        private fun leadingNumber(stem: String): Int? =
            leadingNumberRe.find(stem)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * 用文末答案表回填缺失答案。
         *
         * 旧实现直接 `table[i + 1]`，一旦题号在章节内重新计数、或有题目在收口时被合并，
         * 就会整列错位（1 号题的答案填到 2 号题上）。现在：
         * 1. 题干自带题号的，按题号填；
         * 2. 只有当答案表的键恰好是 `1..n` 时才退回按下标填（此时下标可信）；
         * 3. 填之前校验答案字母确实是本题选项之一，否则跳过（留空让人工补，
         *    总比填一个错答案好）。
         */
        private fun <T> applyAnswerTable(
            list: MutableList<T>,
            rawText: String,
            stemOf: (T) -> String,
            optionsOf: (T) -> List<QuestionOption>,
            answerOf: (T) -> String?,
            withAnswer: (T, String) -> T
        ) {
            val table = parseAnswerTable(rawText)
            if (table.isEmpty()) return
            if (list.none { answerOf(it).isNullOrBlank() }) return
            val keys = table.keys
            val positional = keys.size == list.size && keys.min() == 1 && keys.max() == list.size
            for (i in list.indices) {
                if (!answerOf(list[i]).isNullOrBlank()) continue
                val opts = optionsOf(list[i])
                val byNumber = leadingNumber(stemOf(list[i]))?.let { table[it] }
                val guessed: String = byNumber
                    ?: (if (positional) table[i + 1] else null)
                    ?: continue
                // 选项护栏：答案字母必须是本题选项之一
                if (opts.size >= 2) {
                    val letters = opts.map { it.key.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
                    val ok = guessed.split(Regex("""[\s,，、/]+"""))
                        .filter { it.isNotEmpty() }
                        .all { token ->
                            val u = token.trim().uppercase()
                            u in letters || u in setOf("对", "错", "正确", "错误", "T", "F")
                        }
                    if (!ok) continue
                }
                list[i] = withAnswer(list[i], guessed)
            }
        }

        /**
         * 跨块收口（[parse] 用）：去重 + 文末答案表回填。
         *
         * 分块带块首衔接窗口后，同一道题可能在相邻两块里各出现一次
         * （一块是被截短的残版，一块是完整版），这里按题干相似度 + 选项重合度合并，
         * 保留更完整的一份并补齐答案/解析。
         */
        fun finalize(questions: List<ParsedQuestion>, rawText: String): List<ParsedQuestion> {
            val list = mutableListOf<ParsedQuestion>()
            for (q in questions) {
                if (q.stem.isBlank()) continue
                val hit = findSame(list, q.stem, q.options, { it.stem }, { it.options })
                if (hit < 0) {
                    list += q
                } else {
                    val prev = list[hit]
                    // 题干取更完整的那份：块首衔接窗口带进来的副本可能被 OCR 截短
                    val keepStem = if (q.stem.length > prev.stem.length) q.stem else prev.stem
                    list[hit] = prev.copy(
                        stem = keepStem,
                        answer = prev.answer ?: q.answer,
                        analysis = if ((prev.analysis?.length ?: 0) >= (q.analysis?.length ?: 0)) {
                            prev.analysis ?: q.analysis
                        } else q.analysis,
                        options = if (prev.options.size >= q.options.size) prev.options else q.options,
                        chapter = prev.chapter ?: q.chapter,
                        sharedStem = prev.sharedStem ?: q.sharedStem,
                        sharedOptions = if (prev.sharedOptions.isNotEmpty()) prev.sharedOptions else q.sharedOptions,
                        sourcePage = prev.sourcePage ?: q.sourcePage
                    )
                }
            }
            applyAnswerTable(
                list, rawText,
                stemOf = { it.stem },
                optionsOf = { it.options },
                answerOf = { it.answer },
                withAnswer = { q, ans -> q.copy(answer = ans) }
            )
            return list
        }

        /**
         * 对落盘的 JSONL 行做同样的收口（后台分块并行解析后调用）：
         * 去重 + 文末答案表回填。返回按原文顺序排好的新行。
         */
        fun finalizeLines(lines: List<ParsedLine>, rawText: String): List<ParsedLine> {
            val ordered = lines.sortedBy { it.chunkIndex * 100000 + it.indexInChunk }
            val list = mutableListOf<ParsedLine>()
            for (l in ordered) {
                if (l.stem.isBlank()) continue
                val hit = findSame(list, l.stem, l.options, { it.stem }, { it.options })
                if (hit < 0) {
                    list += l
                } else {
                    val prev = list[hit]
                    val keepStem = if (l.stem.length > prev.stem.length) l.stem else prev.stem
                    list[hit] = prev.copy(
                        stem = keepStem,
                        answer = prev.answer ?: l.answer,
                        analysis = if ((prev.analysis?.length ?: 0) >= (l.analysis?.length ?: 0)) {
                            prev.analysis ?: l.analysis
                        } else l.analysis,
                        options = if (prev.options.size >= l.options.size) prev.options else l.options,
                        chapter = prev.chapter ?: l.chapter,
                        sharedStem = prev.sharedStem ?: l.sharedStem,
                        sharedOptions = if (prev.sharedOptions.isNotEmpty()) prev.sharedOptions else l.sharedOptions,
                        page = prev.page ?: l.page
                    )
                }
            }
            applyAnswerTable(
                list, rawText,
                stemOf = { it.stem },
                optionsOf = { it.options },
                answerOf = { it.answer },
                withAnswer = { l, ans -> l.copy(answer = ans) }
            )
            return list
        }
    }
}
