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
    private val caseStartRegex = Regex("""^(患者|某男|某女|某患|男[，,].{0,3}\d|女[，,].{0,3}\d|病历|病例|一[名位].{0,6}(岁|男|女))""")
    private val pageMarkerRegex = Regex("""^\[\[PG:(\d+)\]\]$""")
    /** Markdown 标题行 `# xxx` ~ `###### xxx` 当作章节标题；组1=井号、组2=标题（井号后必须有空白，避免误吃题干） */
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$""")

    private val trueWords = setOf("对", "正确", "√", "T", "是", "Y")
    private val falseWords = setOf("错", "错误", "×", "X", "F", "否", "N")

    fun parse(text: String): List<ParsedQuestion> {
        val questions = mutableListOf<ParsedQuestion>()
        var current: ParsedQuestion? = null
        var currentSection: String? = null
        // Markdown 标题层级栈：level → 标题。做题时的「章节」取当前标题链，
        // 这样 `# 第一章` 下的 `## 单选` 里的题会记成「第一章 · 单选」，
        // 既不会丢掉上层信息，也不会把整份文档的题都塞进同一个叫「单选」的章里。
        val headingStack = mutableMapOf<Int, String>()
        var appendingTo = 0 // 0=stem 1=analysis 2=option-last 3=sharedStem 4=sharedOptions

        var sharedStemBuf = StringBuilder()
        var sharedOptionsBuf = mutableListOf<QuestionOption>()
        var currentPage: Int? = null

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
            if (sectionRegex.matches(line)) {
                flush()
                // 纯文本的「第一章 / 一、」是平铺结构，不再继承 Markdown 标题链
                headingStack.clear()
                currentSection = line.trimStart('第', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十', ' ', '、', '.', '（', '）', '(', ')')
                    .ifBlank { line }
                continue
            }

            // 共用题干 / 共用备选答案 提示行
            if (sharedStemHintRegex.containsMatchIn(line) && current == null) {
                flush()
                sharedStemBuf = StringBuilder()
                sharedOptionsBuf = mutableListOf()
                appendingTo = 3
                continue
            }
            if (sharedOptionsHintRegex.containsMatchIn(line) && current == null) {
                flush()
                sharedStemBuf = StringBuilder()
                sharedOptionsBuf = mutableListOf()
                appendingTo = 4
                continue
            }

            val qm = questionStartRegex.find(line)
            if (qm != null && !isLikelyNotQuestion(qm)) {
                flush()
                current = ParsedQuestion(chapter = currentSection).apply {
                    stem = qm.groupValues[2].trim()
                    sourcePage = currentPage
                    if (sharedStemBuf.isNotBlank()) sharedStem = sharedStemBuf.toString().trim()
                    if (sharedOptionsBuf.isNotEmpty()) sharedOptions.addAll(sharedOptionsBuf)
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
        return questions
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

        if (q.type == QuestionType.JUDGE && q.options.isEmpty()) {
            q.options += QuestionOption("A", "对")
            q.options += QuestionOption("B", "错")
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
