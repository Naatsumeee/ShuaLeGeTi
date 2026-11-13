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
        /** 原文题号（`1.` 里的 1）。章末答案表是按题号索引的，丢了它就无法回填 */
        val number: Int? = null,
        val stem: String = "",
        val sharedStem: String? = null,
        val sharedOptions: List<QuestionOption> = emptyList(),
        val options: List<QuestionOption> = emptyList(),
        val answer: String? = null,
        val analysis: String? = null,
        val chapter: String? = null,
        val page: Int? = null
    )

    /** 超时/连接类错误是否值得把当前块拆小再试。 */
    private fun isTimeoutLike(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val msg = e.message.orEmpty()
            if (e is java.net.SocketTimeoutException ||
                e is java.io.InterruptedIOException ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ||
                msg.contains("Read timed out", ignoreCase = true) ||
                msg.contains("connection reset", ignoreCase = true)
            ) return true
            e = e.cause
        }
        return false
    }

    /** 供后台任务逐块调用 */
    suspend fun parseChunkText(
        provider: LlmProvider,
        chunk: String,
        onDelta: ((String) -> Unit)? = null
    ): List<QuestionDto> = parseChunk(provider, chunk, onDelta = onDelta)

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
        depth: Int = 0,
        onDelta: ((String) -> Unit)? = null
    ): List<QuestionDto> {
        // 有 onDelta 就走流式：导入页要把「模型正在写什么」实时显示出来。
        // 流式与非流式返回同一个 ChatResult，解析逻辑完全一致。
        val result = try {
            if (onDelta != null) {
                llmClient.chatStream(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    userPrompt = chunk,
                    jsonMode = true,
                    temperature = 0.1,
                    maxTokens = PARSE_MAX_TOKENS,
                    reasoningEffort = PARSE_REASONING,
                    onDelta = onDelta
                )
            } else {
                llmClient.chatWithUsage(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    userPrompt = chunk,
                    jsonMode = true,
                    temperature = 0.1,
                    maxTokens = PARSE_MAX_TOKENS,
                    reasoningEffort = PARSE_REASONING
                )
            }
        } catch (e: Exception) {
            // 长块超时不要直接判整块失败：把块对半交给子调用，既缩短单次等待，
            // 也让已成功的半块落入缓存。认证/参数错误仍原样抛出，交给上层停止。
            if (isTimeoutLike(e) && depth < MAX_SPLIT_DEPTH && chunk.length >= MIN_SPLIT_CHARS) {
                val halves = ChunkPlanner.plan(chunk, chunk.length / 2, overlapLines = 8)
                if (halves.size >= 2) {
                    return halves.flatMap { parseChunk(provider, it.text, depth + 1, onDelta) }
                }
            }
            throw e
        }
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
        return halves.flatMap { parseChunk(provider, it.text, depth + 1, onDelta) }
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
        val qType = mapType(type)
        // 判断题：模型经常只回 {"type":"JUDGE","answer":"对"} 而不给 options，
        // 落库后做题页就没有可点的行。这里补齐「正确/错误」并把答案对齐到 A/B。
        val (finalOpts, finalAns) = com.examhelper.shua.data.model.JudgeOptions.normalize(
            qType, opts, ans
        )
        return ParsedQuestion(
            chapter = chapter?.takeIf { it.isNotBlank() },
            type = qType,
            subtype = subtype?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            stem = stem.trim(),
            sourceNumber = number ?: leadingNumber(stem),
            sharedStem = sharedStem?.trim()?.takeIf { it.isNotBlank() },
            sharedOptions = sopts.toMutableList(),
            options = finalOpts.toMutableList(),
            answer = finalAns,
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
         *
         * **现在这个值是按真实素材量出来的，不是拍的**：`.shots/app_raw13.txt`
         * （47 页扫描件 OCR 全文）一段 5613 字的分块，关掉思考后完整解析
         * （`finish_reason = stop`）需要 **6132** 输出 token；8667 字要 9747；
         * 9759 字要 9827。取 12000 对当前所有 chunk 尺寸都有冗余，
         * 以后把 CHUNK_SIZE 放到 12000 也不用再改这里。
         *
         * 之前设 8192 时，因为服务端默认**开着思考模式**（`reasoning_effort`
         * 缺省即 high），8192 会被思考全额吃光、`finish=length`、**一道题都出不来**。
         */
        const val PARSE_MAX_TOKENS = 12000

        /**
         * 导入解析**固定关闭思考**，不受设置页的强度下拉影响。
         *
         * 依据是实测：
         * - 同一段 5613 字文本，不传参数（默认 high）→ 8192 预算全被思考吃掉，**0 题**；
         * - 传 low → 只剩 7 题（思考占 5398 token）；
         * - 传 none → 19 秒、6132 token、**46 题**、`finish=stop`。
         *
         * 解析是结构化抽取，思考不会提高准确率，只会把输出预算烧掉。
         * 所以这一项**不给用户改**，设置页那两个下拉只管深度解题与补全解析。
         */
        val PARSE_REASONING: com.examhelper.shua.data.llm.ReasoningEffort =
            com.examhelper.shua.data.llm.ReasoningEffort.NONE

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

        /** 答案表行：`答案：1.B 2.C`、`参考答案 1、B` */
        private val answerTableRe = Regex(
            """(?:参考)?答案\s*[:：]?\s*(.+)"""
        )
        private val answerPairRe = Regex("""(\d{1,4})\s*[.、．:：)）]?\s*([A-EGH]{1,8}|对|错|正确|错误)""")

        /**
         * 裸答案行：整行只有一个「题号 + 答案」，如 `1.A`、`2. D`、`12.C`。
         *
         * 样例题库（医学遗传学/医学伦理）的答案区就是这种最朴素的形式——
         * 每行一对，前面**没有**「答案：」二字。旧实现只认带前缀的成行表，
         * 于是这类答案区整块被丢弃，用户看到的「章后集中答案大范围识别不出」。
         */
        private val bareAnswerLineRe = Regex(
            """^\s*(\d{1,4})\s*[.、．:：)）]\s*([A-EGH]{1,8}|对|错|正确|错误)\s*$"""
        )

        /**
         * 一行里挤了多对、但没有「答案：」前缀的形式：`1.E"2.B3.D`、`6.A7.B`。
         *
         * OCR 把答案区排版压成一行时会这样，中间的分隔符可能是引号、空格、顿号，
         * 甚至完全没有（`6.A7.B`）。判据是「整行除答案对外不剩任何实义字符」，
         * 这样 `3.1988年首次确定的…` 这种题干不会被误判。
         */
        private val bareAnswerPairRe = Regex(
            """(\d{1,4})\s*[.、．:：)）]\s*([A-EGH]{1,8}|对|错|正确|错误)"""
        )
        /** 判断「整行都是答案对」时允许残留的字符（空白与各类分隔标点） */
        private val answerLineFillerRe = Regex("""[\s“”"'‘’、,，;；.。()（）/|]+""")

        /** 「第X章/节/篇/卷」标题，用于把原文切成章段 */
        private val chapterSplitRe = Regex(
            """(?m)^[ \t]*第\s*([一二三四五六七八九十百零\d]{1,4})\s*[章节篇卷]"""
        )

        /** 中文/阿拉伯数字 → Int，认不出返回 null */
        private fun parseChineseNumber(raw: String): Int? {
            val t = raw.trim()
            t.toIntOrNull()?.let { return it }
            val digits = mapOf(
                '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
                '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
            )
            if (t.isEmpty()) return null
            if (t == "十") return 10
            if (t.length == 2 && t[0] == '十') return 10 + (digits[t[1]] ?: return null)
            if (t.length == 2 && t[1] == '十') return (digits[t[0]] ?: return null) * 10
            if (t.length == 3 && t[1] == '十') {
                return (digits[t[0]] ?: return null) * 10 + (digits[t[2]] ?: return null)
            }
            return if (t.length == 1) digits[t[0]] else null
        }

        /** 从「第六章 线粒体遗传」这类章节名里取章号；取不到返回 null */
        private fun chapterNumberOf(chapter: String?): Int? {
            if (chapter.isNullOrBlank()) return null
            val m = chapterSplitRe.find(chapter) ?: return null
            return parseChineseNumber(m.groupValues[1])
        }

        /**
         * 按「第X章」把原文切成章段，返回 (章号, 段文本)。
         *
         * 为什么必须切：题号在每章内重新从 1 开始计数，而答案区也是每章末尾一块。
         * 全书合并成一张 `题号 → 答案` 的表，第 7 章的 `1.A` 会填到第 6 章第 1 题上。
         */
        private fun splitByChapter(rawText: String): List<Pair<Int?, String>> {
            val marks = chapterSplitRe.findAll(rawText).toList()
            if (marks.isEmpty()) return listOf(null to rawText)
            val out = mutableListOf<Pair<Int?, String>>()
            // 第一个章标题之前的内容（封面/目录）归到 null 段
            if (marks.first().range.first > 0) {
                out += null to rawText.substring(0, marks.first().range.first)
            }
            for ((i, m) in marks.withIndex()) {
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else rawText.length
                out += parseChineseNumber(m.groupValues[1]) to rawText.substring(m.range.first, end)
            }
            return out
        }

        /**
         * 解析一段文本里的答案表。支持两种排版：
         * 1. 带前缀的成行表：`答案：1.B 2.C 3.D`（一行多对）
         * 2. 裸行表：每行 `1.A`（一行一对，样例题库的常见形式）
         */
        private fun parseAnswerTable(span: String): Map<Int, String> {
            val table = mutableMapOf<Int, String>()
            for (line in span.lines()) {
                // 形式一：带「答案」字样的成行表（要求一行至少两对，否则容易把题干里的
                // 「答案：B」误当答案表）
                val hit = answerTableRe.find(line)
                if (hit != null) {
                    val pairs = answerPairRe.findAll(hit.groupValues[1]).toList()
                    if (pairs.size >= 2) {
                        pairs.forEach { p ->
                            val no = p.groupValues[1].toIntOrNull() ?: return@forEach
                            val ans = p.groupValues[2]
                            if (no in 1..9999) table.putIfAbsent(no, ans)
                        }
                    }
                    continue
                }
                // 形式二：整行只有一对（`1.A` / `2. D`）
                val bare = bareAnswerLineRe.find(line)
                if (bare != null) {
                    val no = bare.groupValues[1].toIntOrNull() ?: continue
                    if (no in 1..9999) table.putIfAbsent(no, bare.groupValues[2])
                    continue
                }
                // 形式三：一行挤了多对但没前缀（`1.E"2.B3.D`）。
                // 要求整行去掉答案对后只剩分隔标点——否则会把题干里的「3.14」之类误当答案。
                val pairs = bareAnswerPairRe.findAll(line).toList()
                if (pairs.size >= 2) {
                    val residue = bareAnswerPairRe.replace(line, "").replace(answerLineFillerRe, "")
                    if (residue.isEmpty()) {
                        pairs.forEach { p ->
                            val no = p.groupValues[1].toIntOrNull() ?: return@forEach
                            val ans = p.groupValues[2]
                            if (no in 1..9999) table.putIfAbsent(no, ans)
                        }
                    }
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
            optionsOf: (T) -> List<QuestionOption>,
            sameIdentity: ((T) -> Boolean)? = null
        ): Int {
            var best = -1
            var bestScore = 0.0
            for (i in list.indices) {
                if (sameIdentity != null && !sameIdentity(list[i])) continue
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

        private fun chapterKey(chapter: String?): String =
            HeadingClassifier.cleanChapter(chapter)?.filter { it.isLetterOrDigit() }.orEmpty()

        private fun leadingNumber(stem: String): Int? =
            leadingNumberRe.find(stem)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * 用文末答案表回填缺失答案。
         *
         * 具体判据全部交给 [AnswerBinder]，这里只负责把泛型题目桥接成它的
         * [AnswerBinder.Item]。早期实现自己维护 `题号 → 答案` 的 Map 并退回
         * `table[i + 1]`（`i` 是**全书**下标），而答案表是每章各自从 1 编号的，
         * 第 7 章第 1 题被当成全书第 35 题 —— 整章一条答案都配不上。这就是
         * 「AI 导入答案覆盖率极低（116/347）」的根因。
         */
        private fun <T> applyAnswerTable(
            list: MutableList<T>,
            rawText: String,
            stemOf: (T) -> String,
            optionsOf: (T) -> List<QuestionOption>,
            answerOf: (T) -> String?,
            withAnswer: (T, String) -> T,
            chapterOf: (T) -> String? = { null },
            numberOf: (T) -> Int? = { null },
            sharedOptionsOf: (T) -> List<QuestionOption> = { emptyList() },
            objectiveTypeOf: (T) -> Boolean = { optionsOf(it).size >= 2 }
        ) {
            if (list.none { answerOf(it).isNullOrBlank() }) return
            val items = list.map { it ->
                AnswerBinder.Item(
                    chapter = chapterOf(it),
                    stem = stemOf(it),
                    // 共用备选答案（B1/B2 型）的选项挂在 sharedOptions 上，
                    // 漏了它们这些题会被判成主观题，答案永远配不上。
                    optionKeys = (optionsOf(it) + sharedOptionsOf(it))
                        .map { o -> o.key }.toSet(),
                    number = numberOf(it),
                    // 题型是客观题就占着答案表的题号，哪怕 OCR 把选项整块吃掉 ——
                    // 否则它会从序号里消失，后面所有题集体前移一位。
                    objectiveType = objectiveTypeOf(it)
                )
            }
            val bound = AnswerBinder.bind(rawText, items)
            for ((i, ans) in bound) {
                if (answerOf(list[i]).isNullOrBlank()) list[i] = withAnswer(list[i], ans)
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
                val hit = findSame(list, q.stem, q.options, { it.stem }, { it.options }) { prev ->
                    // 块间重叠去重：题号是强身份。相邻题题干相似但题号不同，
                    // 不能合并；无题号时才退化为「同章节 + 题干相似」。
                    // 同一章节的多种写法（`第六章` / `第六章 线粒体遗传`）要视为同章；
                    // 但要保留题号这个强身份，避免不同章的同号题被误合并。
                    val sameChapter = chapterKey(prev.chapter) == chapterKey(q.chapter) ||
                        ChunkPlanner.resolveChapter(prev.chapter, q.chapter)?.let {
                            chapterKey(it) == chapterKey(prev.chapter)
                        } == true
                    if (prev.sourceNumber != null && q.sourceNumber != null) {
                        sameChapter && prev.sourceNumber == q.sourceNumber
                    } else sameChapter
                }
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
                withAnswer = { q, ans -> q.copy(answer = ans) },
                chapterOf = { it.chapter },
                numberOf = { it.sourceNumber },
                sharedOptionsOf = { it.sharedOptions },
                objectiveTypeOf = {
                    it.type == QuestionType.SINGLE || it.type == QuestionType.MULTIPLE ||
                        it.type == QuestionType.JUDGE
                }
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
                val hit = findSame(list, l.stem, l.options, { it.stem }, { it.options }) { prev ->
                    val sameChapter = chapterKey(prev.chapter) == chapterKey(l.chapter) ||
                        ChunkPlanner.resolveChapter(prev.chapter, l.chapter)?.let {
                            chapterKey(it) == chapterKey(prev.chapter)
                        } == true
                    if (prev.number != null && l.number != null) {
                        sameChapter && prev.number == l.number
                    } else sameChapter
                }
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
                withAnswer = { l, ans -> l.copy(answer = ans) },
                chapterOf = { it.chapter },
                numberOf = { it.number },
                sharedOptionsOf = { it.sharedOptions },
                objectiveTypeOf = {
                    val t = runCatching { QuestionType.valueOf(it.type) }.getOrNull()
                    t == QuestionType.SINGLE || t == QuestionType.MULTIPLE || t == QuestionType.JUDGE
                }
            )
            return list
        }
    }
}
