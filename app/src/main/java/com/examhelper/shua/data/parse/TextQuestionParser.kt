package com.examhelper.shua.data.parse

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.model.QuestionOption

/**
 * 规整文本题库的离线快解析器（兜底方案）。
 * 支持常见格式：
 *   1、题干 / 1.题干 / (1) 题干
 *   A、选项 / A.选项 / A 选项 /（A）选项
 *   答案：B / 参考答案：B / 【答案】B / 题干末尾"：B" / 题干内嵌"答案：X"
 *   解析：xxx / 【解析】xxx
 *   选项含"√"表示该项为答案
 *   （1~3题共用题干）/ 共用备选答案 → A3 / B 型分组
 * 复杂排版由 LLM 结构化解析处理。
 */
object TextQuestionParser {

    private val questionStartRegex = Regex("""^\s*\(?\s*(\d{1,4})\s*\)?\s*[、.．,，:：)）]\s*(.*)$""")
    private val optionRegex = Regex("""^\s*[(（]?\s*([A-Ea-e])\s*[)）、.．,，:：]?\s+(.*)$""")
    private val optionTightRegex = Regex("""^\s*[(（]?\s*([A-Ea-e])\s*[)）、.．,，:：]\s*(.*)$""")
    private val answerRegex = Regex("""^\s*[\[【(（]?\s*(?:参考)?答案\s*[\]】)）]?\s*[:：is]?\s*(.*)$""")
    private val analysisRegex = Regex("""^\s*[\[【(（]?\s*(?:参考)?解析\s*[\]】)）]?\s*[:：]?\s*(.*)$""")
    private val sectionRegex = Regex("""^\s*(?:第?\s*[一二三四五六七八九十百]+\s*部分|[\(（]\s*[一二三四五六七八九十]+\s*[\)）]).*$|^\s*[一二三四五六七八九十]+\s*[、.．]\s*\S{0,20}$""")
    private val trailingColonAnswerRegex = Regex("""[:：]\s*[(（]?\s*([A-Ea-e])\s*[)）]?\s*$""")
    private val sharedStemHintRegex = Regex("""共用题干|共同题干|共用材料|以下材料""")
    private val sharedOptionsHintRegex = Regex("""共用备选答案|共用选项|备选答案""")
    /** 「(21~25题共用备选答案)」「第1-5题共用备选答案」「（21～25）」里的题号区间 */
    private val sharedRangeRegex = Regex("""(?:第)?\s*(\d{1,4})\s*[~～\-－—至到]\s*(\d{1,4})\s*(?:题)?""")
    private val caseStartRegex = Regex("""^(患者|某男|某女|某患|男[，,].{0,3}\d|女[，,].{0,3}\d|病历|病例|一[名位].{0,6}(岁|男|女))""")
    private val pageMarkerRegex = Regex("""^\[\[PG:(\d+)\]\]$""")

    /**
     * 非题目小节的标题：「学习目标 / 本章要求 / 教学要求 / 重点难点」。
     *
     * 这些小节里是「1.掌握… 2.熟悉… 3.了解…」，格式与题目完全一样，必须整节跳过。
     */
    private val objectiveSectionRegex = Regex("""(学习目标|学习要求|本章要求|教学要求|重点难点|重点掌握)""")
    /**
     * 「第六章」「第 6 章 线粒体遗传」「第三章 抗菌药物」这类章节标题。
     *
     * 扫描件的章标题常被 OCR 拆成两行（「第六章」/「线粒体遗传」），所以组 3
     * 允许为空——为空时把「第X章」暂存为 pending，等下一行标题本体来了再拼起来。
     */
    private val chapterHeadingRegex =
        Regex("""^\s*第\s*([一二三四五六七八九十百零\d]{1,4})\s*([章节篇卷])\s*[、.．:：]?\s*(.*)$""")
    /** Markdown 标题行 `# xxx` ~ `###### xxx` 当作章节标题；组1=井号、组2=标题（井号后必须有空白，避免误吃题干） */
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$""")

    private val trueWords = setOf("对", "正确", "√", "T", "是", "Y")
    private val falseWords = setOf("错", "错误", "×", "X", "F", "否", "N")

    fun parse(text: String): List<ParsedQuestion> {
        val questions = mutableListOf<ParsedQuestion>()

        // 扫描版习题集每章末都有一块「三、参考答案」，里面是「1. D / 2.B」这种
        // 答案清单。这些行长得像题目（有题号），若直接进主循环会被当成题目，
        // 结果整本 684 题、答案 0 条。所以先按章切段，把每段的答案区剥离出来，
        // 只解析题目区，最后再按章回填答案。
        val chapterSegments = splitIntoChapterSegments(text)

        // 逐段判定「题目区」与「答案区」。
        //
        // 两个必须处理的情况（都是实测踩出来的）：
        // 1. 扫描件每页页眉都重复印章名，同一章被切成好几段（第六章切成 4 段），
        //    只有一段带答案区 —— 所以答案表必须用 List 累加，不能 toMap() 覆盖。
        // 2. 答案区可能跨页：起点在前一段，内容延续到下一段（下一页页眉又印章名，
        //    后一段没有「三、参考答案」起点）。这类续段整段都是答案，不能再当题目。
        val answerTables: List<Pair<String?, List<Pair<Int, String>>>> = mutableListOf()
        val questionParts = mutableListOf<String>()

        chapterSegments.forEachIndexed { idx, (chapter, body) ->
            val (questionPart, answerSpan) = AnswerSheet.splitRegion(body)
            if (answerSpan != null) {
                (answerTables as MutableList) += chapter to AnswerSheet.parse(answerSpan)
                questionParts += questionPart
                return@forEachIndexed
            }
            // 本段没有答案区起点：若同章的前一段有，说明本段整段都是答案的续页
            val continuingAnswer = chapterSegments.take(idx).reversed()
                .takeWhile { chapterNumberOf(it.first) == chapterNumberOf(chapter) }
                .any { AnswerSheet.splitRegion(it.second).second != null }
            if (continuingAnswer) {
                (answerTables as MutableList) += chapter to AnswerSheet.parse(body)
            } else {
                questionParts += body
            }
        }

        val questionOnly = questionParts.joinToString("\n")
        parseQuestions(questionOnly, questions)

        val withChapters = fillMissingChapterNumbers(questions)
        applyAnswers(withChapters, answerTables)
        return withChapters
    }

    private fun chapterNumberOf(name: String?): Int? = chapterNumber(name.orEmpty())

    /**
     * 按「第X章」把全文切段，每段含该章题目与其章末答案区。
     *
     * 没有章标题的文件整体算一段。首个章标题之前的内容（封面、目录）单独成段。
     *
     * 注意必须用 **(?m) 多行模式**：主循环里是逐行 find，所以 chapterHeadingRegex
     * 没加多行标志；但这里是对整篇文本 findAll，`^`/`$` 不带 (?m) 只会匹配
     * 整个输入的首尾，结果一个章标题都找不到，切段形同失效。
     */
    private val chapterHeadingMultilineRegex = Regex(
        """(?m)^[ \t]*第\s*([一二三四五六七八九十百零\d]{1,4})\s*([章节篇卷])\s*[、.．:：]?\s*(.*)$"""
    )

    private fun splitIntoChapterSegments(text: String): List<Pair<String?, String>> {
        val marks = chapterHeadingMultilineRegex.findAll(text).map { it.range.first }.toList()
        if (marks.isEmpty()) return listOf(null to text)
        val out = mutableListOf<Pair<String?, String>>()
        if (marks.first() > 0) out += null to text.substring(0, marks.first())
        marks.forEachIndexed { i, start ->
            val end = if (i + 1 < marks.size) marks[i + 1] else text.length
            val body = text.substring(start, end)
            val head = chapterHeadingMultilineRegex.find(body)?.value?.trim()
            out += head to body
        }
        return out
    }

    /**
     * 把答案表回填到题目上。
     *
     * 题号在每章内重新从 1 开始，所以先按章号精确匹配；匹配不到再退回全书合并表
     * （单章文件、或章节标题没识别出来的情况）。
     *
     * 两条护栏，都是为了不把答案填到错的题上：
     * 1. **选择题**：答案必须是本题选项字母之一。
     * 2. **主观题**（名词解释/简答，答案是一大段正文）：只有题干和答案正文对得上
     *    才采纳。章节开头的「学习目标」条目也会被当题目占掉题号 1/2/3，
     *    若无条件按题号填，名词解释的正文就会挂到「掌握 mtDNA 的结构…」这种
     *    目标条目上 —— 实测踩过。
     */
    private fun applyAnswers(
        questions: List<ParsedQuestion>,
        tables: List<Pair<String?, List<Pair<Int, String>>>>
    ) {
        if (tables.all { it.second.isEmpty() }) return

        // 同一章里名词解释和选择题各自从 1 编号，题号会重复，所以答案按「形态」分池：
        //  · 字母池：`1. D` 这种选择题答案
        //  · 正文池：`1．纯质性（…):是指…` 这种名词解释/简答题答案
        // 选择题只在字母池里按题号取，主观题只在正文池里按内容找 —— 两类互不干扰。
        fun isLetterOnly(s: String): Boolean {
            val t = s.trim()
            return t.isNotEmpty() && t.length <= 8 && t.all { it in "ABCDEFGH对错正确误TF√×" }
        }

        val letterByNo = mutableMapOf<Int, String>()   // 章号 → 池
        val proseByNo = mutableMapOf<Int, MutableList<String>>()
        val letterWhole = mutableMapOf<Int, String>()
        val proseWhole = mutableListOf<String>()

        tables.forEach { (name, rows) ->
            val n = chapterNumber(name.orEmpty())
            rows.forEach { (k, v) ->
                if (isLetterOnly(v)) {
                    letterWhole.putIfAbsent(k, v)
                    if (n != null) letterByNo.putIfAbsent(n * 100000 + k, v)
                } else if (v.trim().length > 8) {
                    proseWhole += v
                    if (n != null) proseByNo.getOrPut(n) { mutableListOf() } += v
                }
            }
        }

        questions.forEach { q ->
            if (!q.answer.isNullOrBlank()) return@forEach
            val no = chapterNumber(q.chapter.orEmpty())
            val prosePool = no?.let { proseByNo[it] } ?: proseWhole
            val proseHit = prosePool.firstOrNull { ans -> stemMatchesAnswer(q.stem, ans) }

            // B1 型题（共用备选答案）自己的 options 是空的，选项在 sharedOptions 里。
            // 只按 q.options 判断会把它们误归到主观题分支，按内容匹配必然失败。
            val allOptions = q.options + q.sharedOptions

            // 名词解释的题干就是那个术语，且答案正文必然以它开头 —— 这种必须优先按内容配。
            // 实测坑：答案区里「（一）名词解释」紧跟在上一个题型后面时，会误继承
            // 前一题的 sharedOptions（「分子病」挂了 5 个共用选项），于是被当成选择题，
            // 按题号 1 从字母池取到 D，把名词解释的正文答案覆盖掉了。
            val proseWins = proseHit != null && q.options.isEmpty()
            if (proseWins) {
                q.answer = proseHit
                return@forEach
            }

            if (allOptions.size >= 2) {
                // ---- 选择题：按题号从字母池取 ----
                val guess = q.sourceNumber?.let { num ->
                    (no?.let { letterByNo[it * 100000 + num] }) ?: letterWhole[num]
                } ?: leadingNumber(q.stem)?.let { num ->
                    (no?.let { letterByNo[it * 100000 + num] }) ?: letterWhole[num]
                }
                if (guess.isNullOrBlank()) return@forEach
                val letters = allOptions.map { it.key.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
                val tokens = guess.split(Regex("""[\s,，、/]+""")).filter { it.isNotEmpty() }
                val ok = tokens.isNotEmpty() && tokens.all { t ->
                    val u = t.trim().uppercase()
                    u in letters || u in setOf("对", "错", "正确", "错误", "T", "F")
                }
                if (!ok) return@forEach
                q.answer = guess
            } else {
                // ---- 主观题：按内容配，不按题号 ----
                // 「学习目标」条目虽已被跳过，但名词解释与选择题题号重叠，
                // 按题号取正文必然错位；题干本身就是术语，直接拿它去正文里找更可靠。
                if (proseHit != null) {
                    q.answer = proseHit
                } else {
                    // 兜底：正文池里没有匹配，说明它不是名词解释/简答题，而是
                    // **选项被 OCR 弄丢了的客观题**（实测扫描件里「21．mtDNA含基因数为」
                    // 后面直接跟下一题，A~E 全丢）。这种情况仍可按题号从字母池取。
                    //
                    // 但简答题绝不能吃字母答案：「人类染色体的多态性主要体现在哪些方面？
                    // 它们与表型效应的关系如何？」正文池没匹配到时会拿到一个字母，
                    // 填一个错答案比留空更糟。所以只在题干不像问答句时才兜底。
                    val looksObjective = q.stem.length <= 40 &&
                        '？' !in q.stem && '?' !in q.stem &&
                        !subjectiveHintRegex.containsMatchIn(q.stem)
                    if (looksObjective) {
                        val fallback = q.sourceNumber?.let { num ->
                            (no?.let { letterByNo[it * 100000 + num] }) ?: letterWhole[num]
                        }
                        if (!fallback.isNullOrBlank() && isLetterOnly(fallback)) q.answer = fallback
                    }
                }
            }
        }
    }

    private fun isLetterOnly(s: String): Boolean {
        val t = s.trim()
        return t.length <= 8 && t.all { it in "ABCDEFGH对错正确误TF√×" }
    }

    /** 简答题/论述题的问句特征（选项丢失兜底时用来排除主观题） */
    private val subjectiveHintRegex =
        Regex("""(何谓|简述|试述|论述|阐述|举例说明|有哪些|为什么|为何|如何|怎么|意义|特点是什么|关系如何)""")

    /**
     * 主观题的题干与答案正文是否对得上。
     *
     * 判据：题干里长度 ≥2 的汉字片段出现在答案正文里。名词解释的题干就是那个术语
     * （如「纯质性」），正文必然以它开头；「掌握 mtDNA 的结构和组成…」这类
     * 学习目标条目则不会命中，从而被排除。
     */
    private fun stemMatchesAnswer(stem: String, answer: String): Boolean =
        answerProbeScore(stem, answer) >= PROSE_MATCH_THRESHOLD

    /**
     * 题干与答案正文的匹配分（0..1）。
     *
     * 只用「最长片段完全包含」太严：OCR 常把题干吃掉一个字
     * （实测「常染**色**质与异染色质…有何差异?」在题干侧写成「常染质…」，
     * 最长片段就再也匹配不上，整题被判为无答案）。
     * 这里改成 **2-gram 命中率**：题干的所有相邻汉字二元组，有多少比例出现在答案里。
     * 对同一个术语的两种写法容忍度好得多，同时对「完全不同的题」仍然打低分。
     *
     * 另外保留一条快速通道：题干里任一长度 ≥6 的汉字片段被答案原样包含，直接算满命中。
     */
    private fun answerProbeScore(stem: String, answer: String): Double {
        if (answer.isBlank()) return 0.0
        val runs = Regex("""[\u4e00-\u9fa5]{2,}""").findAll(stem).map { it.value }.toList()
        if (runs.isEmpty()) return 0.0
        // 快速通道：长片段原样命中
        if (runs.any { it.length >= 6 && answer.contains(it) }) return 1.0

        val grams = mutableSetOf<String>()
        for (run in runs) {
            if (run.length < 2) continue
            for (i in 0 until run.length - 1) grams += run.substring(i, i + 2)
        }
        if (grams.isEmpty()) return 0.0
        val hit = grams.count { answer.contains(it) }
        return hit.toDouble() / grams.size
    }

    /** 2-gram 命中率达到这个比例就认为这题在答案区里有对应正文 */
    private val PROSE_MATCH_THRESHOLD = 0.55

    /** 题干开头的题号（`1.` `2、` `（3）`） */
    private val leadingNumberRe = Regex("""^\s*[（(]?\s*(\d{1,4})\s*[）)]?\s*[、.．,，:：)）]""")

    private fun leadingNumber(stem: String): Int? =
        leadingNumberRe.find(stem)?.groupValues?.get(1)?.toIntOrNull()

    /** 真正逐行解析题目的主循环 */
    private fun parseQuestions(text: String, questions: MutableList<ParsedQuestion>) {
        var current: ParsedQuestion? = null
        var currentSection: String? = null
        // Markdown 标题层级栈：level → 标题。做题时的「章节」取当前标题链，
        // 这样 `# 第一章` 下的 `## 单选` 里的题会记成「第一章 · 单选」，
        // 既不会丢掉上层信息，也不会把整份文档的题都塞进同一个叫「单选」的章里。
        val headingStack = mutableMapOf<Int, String>()
        var appendingTo = 0 // 0=stem 1=analysis 2=option-last 3=sharedStem 4=sharedOptions

        var sharedStemBuf = StringBuilder()
        var sharedOptionsBuf = mutableListOf<QuestionOption>()
        // 「(21~25题共用备选答案)」给出的题号范围。**必须用它封住 sharedOptions 的作用域**：
        // 旧实现只设置不清空，于是同一章后面所有题（包括简答题）都挂上这 5 个备选答案，
        // 实测一本样书 262/281 道题都带 sharedOptions，简答题还被错填成字母答案。
        var sharedNoFrom: Int? = null
        var sharedNoTo: Int? = null
        var currentPage: Int? = null
        // OCR 把「第六章」和章名拆成两行时，先把章号暂存，等下一行标题本体拼起来
        var pendingChapterPrefix: String? = null
        // 全文是否出现过「第X章 / 第X节」这类真章节标题。
        // 有 → 题型标签（（一）名词解释）不许顶掉章节名；没有 → 只能拿它当分组。
        // 必须预扫描：题型标签可能出现在第一个章标题之前。
        var hasChapterHeading = text.lineSequence().any { chapterHeadingRegex.containsMatchIn(it) }
        // 是否正处于「学习目标」这种非题目小节里（见下面 objectiveSectionRegex）
        var skipObjectiveSection = false

        fun flush() {
            current?.let { q ->
                normalizeQuestion(q)
                if (q.stem.isNotBlank()) questions += q
            }
            current = null
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 处于「学习目标」小节内：整段丢弃，直到遇到下一个分组/章节标题
            if (skipObjectiveSection) {
                val isNextSection = sectionRegex.matches(line) || chapterHeadingRegex.containsMatchIn(line)
                if (!isNextSection) continue
            }

            // 页码标记
            val pageMarker = pageMarkerRegex.find(line)
            if (pageMarker != null) {
                currentPage = pageMarker.groupValues[1].toIntOrNull()
                continue
            }

            // 章节标题
            val hm = headingRegex.find(line)
            if (hm != null) {
                flush()
                val level = hm.groupValues[1].length
                val title = hm.groupValues[2].trim()
                // 更深或同级标题覆盖，更浅的祖先保留
                headingStack.keys.filter { it >= level }.forEach { headingStack.remove(it) }
                if (title.isNotBlank()) headingStack[level] = title
                // 章节取「最深的、不像题型标签的」标题：
                // Markdown 题库常见 `# 第一章` → `## 单项选择题` → 题目，
                // 若把「单项选择题」当章节，同一章的题会被拆成好几组，按章节刷题就散了。
                val chain = headingStack.toSortedMap().values.filter { it.isNotBlank() }
                currentSection = chain.lastOrNull { !isTypeHeading(it) }
                    ?: chain.lastOrNull()
                appendingTo = 0
                continue
            }
            // 章节标题：「第X章 / 第X节」优先，且**保留完整章号**。
            // 旧代码用 trimStart('第','一'…'十') 削前缀，会把「第六章」削成「章」，
            // 章节序号就此丢失（用户报的「章节名前依然会出现第xx章丢失」）。
            val chm = chapterHeadingRegex.find(line)
            if (chm != null) {
                flush()
                headingStack.clear()
                skipObjectiveSection = false
                val no = chm.groupValues[1]
                val unit = chm.groupValues[2]
                val title = chm.groupValues[3].trim()
                val prefix = "第$no$unit"
                hasChapterHeading = true
                if (title.isBlank()) {
                    // OCR 把章号和章名拆成两行：先记下章号，等下一行拼
                    pendingChapterPrefix = prefix
                    currentSection = prefix
                } else {
                    // 扫描件的每页页眉会重复印章名（「第十章多基因病」），OCR 逐页都会读出来。
                    // 同一章号再次出现时不能当成新章节，也不能拼成「第十章 第十章 多基因病」——
                    // 只保留第一次的完整标题，后续重复行直接忽略。
                    val sameChapter = chapterNumber(currentSection.orEmpty()) == chapterNumber(prefix)
                    if (!sameChapter || currentSection.isNullOrBlank()) {
                        pendingChapterPrefix = null
                        currentSection = "$prefix $title"
                    } else {
                        // 同一章重复出现：若已有标题是残缺的（只有章号），用这行的完整标题补全
                        val existing = currentSection.orEmpty()
                        if (existing.length <= prefix.length + 1) currentSection = "$prefix $title"
                    }
                }
                appendingTo = 0
                continue
            }
            // 上一行只拿到「第六章」，本行就是章名本体
            val pendingPrefix = pendingChapterPrefix
            if (pendingPrefix != null) {
                pendingChapterPrefix = null
                if (!looksLikeQuestionOrSection(line)) {
                    currentSection = "$pendingPrefix $line"
                    appendingTo = 0
                    continue
                }
                // 本行不是章名（直接就是题目/小节），章号单独成立
                currentSection = pendingPrefix
            }
            if (sectionRegex.matches(line)) {
                flush()
                // 「学习目标 / 本章要求 / 教学要求」这一节里是「1.掌握… 2.熟悉…」，
                // 长得和题目一模一样，但根本不是题 —— 留在结果里既污染题库，
                // 又会占掉题号 1/2/3 让章末答案错位。整节跳过直到下一个分组标题。
                val bare = line.trimStart('一', '二', '三', '四', '五', '六', '七', '八', '九', '十', ' ', '、', '.', '（', '）', '(', ')')
                if (objectiveSectionRegex.containsMatchIn(bare) || objectiveSectionRegex.containsMatchIn(line)) {
                    skipObjectiveSection = true
                    currentSection = currentSection   // 章节不变
                    appendingTo = 0
                    continue
                }
                skipObjectiveSection = false
                // 纯文本的「一、」「（一）」是题型/小节分组。
                // 关键区分：
                //  · 文件里**有**「第X章」→ 题型标签不能顶掉章节名（用户报的
                //    「（一）名词解释 被当成章节名」）；
                //  · 文件里**没有**任何章标题 → 只能退而用「一、单项选择题」当分组，
                //    否则所有题都成了无章节。
                headingStack.clear()
                val label = line.trimStart('一', '二', '三', '四', '五', '六', '七', '八', '九', '十', ' ', '、', '.', '（', '）', '(', ')')
                    .ifBlank { line }
                if (isTypeHeading(label) || isTypeHeading(line)) {
                    if (!hasChapterHeading && currentSection.isNullOrBlank()) currentSection = label
                } else if (currentSection.isNullOrBlank() || !hasChapterHeading) {
                    currentSection = label
                }
                appendingTo = 0
                continue
            }

            // 共用题干 / 共用备选答案 提示行
            //
            // 这里刻意**不**要求 `current == null`：真实的扫描习题集里，
            // 【B1型题】/(21~25题共用备选答案）紧跟在上一题的最后一个选项后面，
            // 此时 current 还挂着上一题。旧写法加了这个守卫 → 整块被跳过，
            // 备选答案 A~E 被当成上一题的选项续行吞掉，后续 5 道 B1 题
            // 全都拿不到 sharedOptions（实测一本样书丢了 11 道）。
            // 现在无条件先 flush 上一题，再开始收集。
            if (sharedStemHintRegex.containsMatchIn(line)) {
                flush()
                sharedStemBuf = StringBuilder()
                sharedOptionsBuf = mutableListOf()
                sharedNoFrom = null
                sharedNoTo = null
                appendingTo = 3
                continue
            }
            if (sharedOptionsHintRegex.containsMatchIn(line)) {
                flush()
                sharedStemBuf = StringBuilder()
                sharedOptionsBuf = mutableListOf()
                // 记下共用区间，超界的题就不再继承这组备选答案
                val range = sharedRangeRegex.find(line)
                sharedNoFrom = range?.groupValues?.get(1)?.toIntOrNull()
                sharedNoTo = range?.groupValues?.get(2)?.toIntOrNull()
                appendingTo = 4
                continue
            }

            val qm = questionStartRegex.find(line)
            if (qm != null && !isLikelyNotQuestion(qm)) {
                flush()
                val no = qm.groupValues[1].toIntOrNull()
                // 共用备选答案只对「(21~25题共用备选答案)」声明的区间生效。
                // 没写区间时退化成「一直有效到下一个共用块」，兼容旧行为。
                val inSharedScope = sharedOptionsBuf.isNotEmpty() && when {
                    sharedNoFrom == null || sharedNoTo == null -> true
                    no == null -> true
                    else -> no in sharedNoFrom!!..sharedNoTo!!
                }
                current = ParsedQuestion(chapter = currentSection).apply {
                    stem = qm.groupValues[2].trim()
                    // 记下原题号：stem 里的题号已被剥掉，但章末答案表按题号索引
                    sourceNumber = no
                    sourcePage = currentPage
                    if (sharedStemBuf.isNotBlank()) sharedStem = sharedStemBuf.toString().trim()
                    if (inSharedScope) sharedOptions.addAll(sharedOptionsBuf)
                }
                if (!inSharedScope) {
                    // 超出区间：这组备选答案已经用完，清掉避免继续往下泄漏
                    sharedOptionsBuf = mutableListOf()
                    sharedNoFrom = null
                    sharedNoTo = null
                }
                appendingTo = 0
                continue
            }

            val q = current
            if (q == null) {
                // 题号出现前的材料/共用选项累积
                when (appendingTo) {
                    3 -> sharedStemBuf.appendLine(line)
                    4 -> {
                        val om = optionTightRegex.find(line) ?: optionRegex.find(line)
                        if (om != null && om.groupValues[2].isNotBlank()) {
                            sharedOptionsBuf += QuestionOption(om.groupValues[1].uppercase(), om.groupValues[2].trim())
                        } else if (sharedOptionsBuf.isNotEmpty()) {
                            val last = sharedOptionsBuf.removeAt(sharedOptionsBuf.lastIndex)
                            sharedOptionsBuf += last.copy(text = last.text + line)
                        }
                    }
                }
                continue
            }

            val am = answerRegex.find(line)
            if (am != null && q.answer == null) {
                q.answer = am.groupValues[1].trim()
                appendingTo = 1
                continue
            }

            val an = analysisRegex.find(line)
            if (an != null) {
                q.analysis = (q.analysis ?: "") + an.groupValues[1].trim()
                appendingTo = 1
                continue
            }

            val om = optionTightRegex.find(line) ?: optionRegex.find(line)
            if (om != null && q.stem.isNotBlank()) {
                var optText = om.groupValues[2].trim()
                if (optText.contains("√") || optText.contains("✔") || optText.contains("✓")) {
                    q.answer = om.groupValues[1].uppercase()
                    optText = optText.replace(Regex("""[√✔✓]"""), "").trim()
                }
                q.options += QuestionOption(om.groupValues[1].uppercase(), optText)
                appendingTo = 2
                continue
            }

            when (appendingTo) {
                0 -> q.stem = if (q.stem.endsWith("-")) q.stem.dropLast(1) + line else q.stem + line
                1 -> q.analysis = (q.analysis ?: "").let { if (it.isEmpty()) line else it + "\n" + line }
                2 -> if (q.options.isNotEmpty()) {
                    val last = q.options.removeAt(q.options.lastIndex)
                    q.options += last.copy(text = last.text + line)
                }
                else -> Unit
            }
        }
        flush()
    }

    /**
     * 按出现顺序补齐缺失的章节序号。
     *
     * 场景：OCR 把「第七章」那一行漏掉，或整章标题没被识别出来，结果章节序列
     * 变成 第六章 → 线粒体遗传 / 单基因遗传病 / 多基因遗传病 / 第八章 ……
     * 用户希望「前后数字能连得上」，所以这里把没有章号的章节按序插补成
     * 第七章、第八章……（只在**明确缺失**的位置补，已有的序号一律不动）。
     */
    private fun fillMissingChapterNumbers(questions: List<ParsedQuestion>): List<ParsedQuestion> {
        if (questions.isEmpty()) return questions
        // 按出现顺序收集不重复的章节名
        val order = LinkedHashSet<String>()
        questions.forEach { q -> q.chapter?.takeIf { it.isNotBlank() }?.let { order += it } }
        if (order.size < 2) return questions

        val numbered = order.map { it to chapterNumber(it) }
        val known = numbered.mapNotNull { it.second }
        // 至少要有一个带章号的，否则无从推断
        if (known.isEmpty()) return questions

        val fixed = mutableListOf<String>()
        var expected: Int? = null   // null = 还没遇到第一个带章号的章节，无从推断
        for ((name, no) in numbered) {
            if (no != null) {
                expected = no
                fixed += name
            } else if (expected == null) {
                // 第一个带章号的章节之前，无法推断，原样保留
                fixed += name
            } else {
                expected += 1
                fixed += "第${chineseNumber(expected!!)}章 $name"
            }
        }
        val mapping = order.zip(fixed).toMap()
        questions.forEach { q ->
            q.chapter?.let { c -> mapping[c]?.let { q.chapter = it } }
        }
        return questions
    }

    /** 从章节名里取出章号（阿拉伯或中文数字）；取不到返回 null */
    private fun chapterNumber(name: String): Int? {
        val m = Regex("""第\s*([一二三四五六七八九十百零\d]{1,4})\s*[章节篇卷]""").find(name) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    private fun parseChineseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digit = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        // 单字「十」= 10。漏了这一条会让「第十章」「第十章多基因病」这类最常见的
        // 章号全部取不到（返回 null），进而导致同章重复判断失效、按章回填答案错位。
        if (s == "十") return 10
        if (s.length == 1) return digit[s[0]]
        // 十、十一、二十、二十三 ……
        if (s.startsWith("十")) return 10 + (digit[s.getOrNull(1)] ?: 0)
        val idx = s.indexOf('十')
        if (idx > 0) {
            val tens = digit[s[idx - 1]] ?: return null
            val ones = if (idx + 1 < s.length) digit[s[idx + 1]] ?: 0 else 0
            return tens * 10 + ones
        }
        return null
    }

    private fun chineseNumber(n: Int): String {
        if (n <= 10) return listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")[n - 1]
        if (n < 20) return "十" + chineseNumber(n - 10)
        val tens = n / 10
        val ones = n % 10
        return chineseNumber(tens) + "十" + if (ones == 0) "" else chineseNumber(ones)
    }

    /** 避免把 "3.5厘米"、"2023.1" 之类识别为题号 */
    private fun isLikelyNotQuestion(m: MatchResult): Boolean {
        val number = m.groupValues[1]
        val rest = m.groupValues[2]
        if (number.length > 3) return true
        return rest.isNotEmpty() && rest[0].isDigit()
    }

    private fun normalizeQuestion(q: ParsedQuestion) {
        // 题干内嵌 "答案：B" / "答案：对"
        if (q.answer == null) {
            Regex("""(?:参考)?答案\s*[是为]?\s*[:：]?\s*([A-Ea-e]{1,5}|对|错|正确|错误|√|×)""")
                .find(q.stem)?.let { m ->
                    q.answer = m.groupValues[1]
                    q.stem = q.stem.removeRange(m.range).trim()
                }
        }

        // 题干末尾 "：B" 型答案标注
        if (q.answer == null && q.options.isNotEmpty()) {
            trailingColonAnswerRegex.find(q.stem)?.let {
                q.answer = it.groupValues[1].uppercase()
                q.stem = q.stem.removeSuffix(it.value).trimEnd('：', ':', ' ')
            }
        }

        // 选项规范化：合并重复字母（OCR 拆行）、重排字母并映射答案
        val (opts, ans) = OptionNormalizer.normalize(q.options.toList(), q.answer)
        q.options.clear()
        q.options.addAll(opts)
        q.answer = ans
        if (q.sharedOptions.isNotEmpty()) {
            val (sopts, _) = OptionNormalizer.normalize(q.sharedOptions.toList(), null)
            q.sharedOptions.clear()
            q.sharedOptions.addAll(sopts)
        }

        q.stem = q.stem.trim()
        q.answer = normalizeAnswer(q.answer)
        q.analysis = q.analysis?.trim()?.takeIf { it.isNotBlank() }

        q.type = when {
            q.options.size == 2 && q.options.all { it.text in trueWords || it.text in falseWords } -> QuestionType.JUDGE
            q.options.isEmpty() -> {
                val a = q.answer
                when {
                    a != null && (a == "对" || a == "错") -> QuestionType.JUDGE
                    // 有下划线不一定就是填空：答案是一长句时（概念题常写成「解释××：___」）
                    // 判定为名词解释/简答更贴近实际，否则用户会被要求「填空」答一整段话。
                    q.stem.contains("___") || q.stem.contains("__") ->
                        if (a != null && a.length > 10 && isTermStem(q.stem)) QuestionType.TERM
                        else QuestionType.FILL
                    isTermStem(q.stem) -> QuestionType.TERM
                    else -> QuestionType.SHORT_ANSWER
                }
            }
            q.answer != null && q.answer!!.length >= 2 && q.answer!!.all { it in 'A'..'E' } -> QuestionType.MULTIPLE
            else -> QuestionType.SINGLE
        }

        if (q.type == QuestionType.JUDGE) {
            // 判断题永远给两行固定选项，答案归一成 A/B。
            // 旧实现只在 options.isEmpty() 时补，且补的是「对/错」；
            // 现在统一走 JudgeOptions，选项文案是「正确/错误」，与题库里
            // 常见的「（ ）」式判断题一致。
            val (jopts, jans) = com.examhelper.shua.data.model.JudgeOptions.normalize(
                q.type, q.options.toList(), q.answer
            )
            q.options.clear()
            q.options.addAll(jopts)
            q.answer = jans
        }

        // 医学题型子类型
        q.subtype = when {
            q.sharedStem != null -> "A3"
            q.sharedOptions.isNotEmpty() -> "B"
            q.type == QuestionType.SINGLE -> if (caseStartRegex.containsMatchIn(q.stem.take(20))) "A2" else "A1"
            else -> null
        }
    }

    /** 「单项选择题 / 多选题 / 判断题 / 填空题 / 名词解释」这类标题是题型分组，不是章节 */
    private fun isTypeHeading(title: String): Boolean {
        val t = title.replace(" ", "")
        if (t.length > 14) return false
        return typeHeadingKeywords.any { t.contains(it) }
    }

    /** 该行是否已经是题目/选项/答案等实质内容（用于判断「第六章」下一行到底是不是章名） */
    private fun looksLikeQuestionOrSection(line: String): Boolean {
        if (line.length > 30) return true
        if (questionStartRegex.find(line)?.let { !isLikelyNotQuestion(it) } == true) return true
        if (answerRegex.matches(line) || analysisRegex.matches(line)) return true
        if (optionTightRegex.matches(line)) return true
        return false
    }

    private val typeHeadingKeywords = listOf(
        "选择题", "单选题", "多选题", "判断题", "填空题", "名词解释", "简答题",
        "论述题", "案例分析", "问答", "计算题", "配伍题", "共用题干", "共用备选"
    )

    fun normalizeAnswer(raw: String?): String? {
        var a = raw?.trim()?.uppercase() ?: return null
        when {
            a in listOf("√", "T", "Y", "TRUE", "对", "正确", "是") -> a = "对"
            a in listOf("×", "X", "F", "N", "FALSE", "错", "错误", "否") -> a = "错"
        }
        return a.takeIf { it.isNotBlank() }
    }

    /** 「解释××」「名词解释：××」「简述××的概念/定义/机制」这类纯概念题判为名词解释 */
    private fun isTermStem(stem: String): Boolean {
        val s = stem.trim()
        if (s.length > 60) return false  // 太长的多半是论述题
        return termPrefixRegex.containsMatchIn(s) ||
            (s.contains("概念") || s.contains("定义") || s.contains("名词")) &&
            (s.startsWith("简述") || s.startsWith("什么") || s.startsWith("何谓") || s.startsWith("试述"))
    }

    private val termPrefixRegex =
        Regex("^(名词解释|解释名词|解释|什么是|什么是\"|何谓|试述)")
}
