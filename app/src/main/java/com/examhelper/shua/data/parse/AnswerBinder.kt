package com.examhelper.shua.data.parse

/**
 * 把「答案区」的答案绑定回题目。
 *
 * 与 [AnswerSheet] 的分工：`AnswerSheet` 只负责把一段答案区文本解析成
 * `题号 → 答案` 的有序列表；本类负责判断「这条答案属于哪道题」。
 *
 * ## 为什么不能按全局下标填
 *
 * 真实试卷的答案区是这样的（`.shots/app_raw13.txt` 第六章实测）：
 *
 * ```
 * 三、参考答案
 * （一)名词解释
 * 1．纯质性（homoplasmy ):是指……
 * （二）选择题
 * 【A1型题】
 * 1.D2.B
 * …
 * 10.A
 * 【A2型题】
 * 11.A 12.E …
 * 【B1型题】
 * 21.A 22.D …
 * （三）简答题
 * 1.什么是mtDNA？……
 * ```
 *
 * 三个坑叠在一起：
 * 1. **题号在每章内重新从 1 开始** —— 全书共用一张表会把第 7 章的 `1.A`
 *    填到第 6 章第 1 题上；
 * 2. **同一章里名词解释和选择题各自从 1 编号**，所以 `题号 → 答案` 不能用 Map，
 *    `AnswerSheet` 因此返回 List；
 * 3. **AI 解析产出的题干通常不带题号**（模型把题号剥掉了），所以「从题干里
 *    取题号」这条路在 AI 链路上几乎永远拿不到值。旧实现退回到
 *    `table[i + 1]`，而 `i` 是**全书**下标 —— 第 7 章第 1 题被当成全书第 35 题，
 *    整章一条答案都配不上。这就是「AI 导入答案覆盖率极低」的根因。
 *
 * ## 现在的判据
 *
 * 关键观察：**字母答案表的键恰好覆盖 1..N，其中 N 正好等于本章客观题的数量**
 * （实测第六章 10 道 A1 + 10 道 A2 + 12 道 B1 = 32，答案表正好是 1..32）。
 * 所以只要「本章客观题按出现顺序数下来是第几道」，就能对上答案表的题号。
 * 这个映射不依赖题干里有没有题号，也不依赖模型输出的顺序编号。
 *
 * 三条护栏，宁可留空也不填错：
 * - 字母表必须**完整覆盖** 1..N 且无重号，才按序号填；有跳号/重号时只认
 *   「题干自带题号」（并且题号在表里唯一）；
 * - 填进去的字母必须是本题选项之一；
 * - 主观题**只按内容匹配**（2-gram 命中率），绝不按题号取字母。
 */
object AnswerBinder {

    /**
     * 待绑定的题目。
     *
     * `optionKeys` **必须把共用备选答案（B1/B2 型）一起算进来**：那类题的
     * `options` 是空的，选项挂在 `sharedOptions` 上。漏了这层这些题会被判成
     * 主观题，答案永远配不上（实测第六章 12 道 B1 全部丢失）。
     *
     * `number` 是原文题号，由调用方从结构化字段传进来。AI 解析产出的题干
     * 通常已被剥掉题号，只靠 [stem] 里的前导数字取不到，必须显式给。
     */
    data class Item(
        val chapter: String?,
        val stem: String,
        val optionKeys: Set<String>,
        val number: Int? = null,
        /**
         * 这道题**在原文里属于客观题**（单选/多选/判断）。
         *
         * 不能拿 [optionKeys] 是否为空来判断：扫描件里选项被 OCR 整块吃掉的情况
         * 很常见（实测第八章 40 道客观题只有 29 道识别出了选项），那些题仍然
         * 占着答案表里的一个题号。用选项数当判据会让它们从序号里"消失"，
         * 后面所有题的序号集体前移一位，整章错位。
         */
        val objectiveType: Boolean = optionKeys.size >= 2
    ) {
        /** 结构化题号优先，其次从题干前导数字兜底 */
        val questionNo: Int? get() = number ?: leadingNumber(stem)
    }

    /** 主观题内容匹配阈值：与 [TextQuestionParser] 一致，容忍 OCR 吃掉一两个字 */
    private const val PROSE_MATCH_THRESHOLD = 0.55

    /** `第X章/节/篇/卷`，与 [LlmQuestionParser] 的分章规则保持一致 */
    private val chapterSplitRe = Regex(
        """(?m)^[ \t]*第\s*([一二三四五六七八九十百零\d]{1,4})\s*[章节篇卷]"""
    )

    /** 题干开头的题号（`1.` `2、` `（3）`），没有则 null */
    private val leadingNumberRe = Regex("""^\s*[（(]?\s*(\d{1,4})\s*[）)]?\s*[、.．,，:：)）]""")

    /**
     * 返回 `items 下标 → 答案`。配不上的题不会出现在结果里（留空让人工补，
     * 比填一个错答案安全）。
     */
    fun bind(rawText: String, items: List<Item>): Map<Int, String> {
        if (items.isEmpty()) return emptyMap()

        // 每章各自取自己的答案。两种排版并存，都要收：
        //  ① 独立答案区 —— 一行「参考答案」开头，下面按题型分组列 `1.D 2.B`
        //     （`AnswerSheet` 负责，题号在各题型组内重排，返回 List 不能转 Map）；
        //  ② 行内前缀表 —— `答案：1.B 2.C 3.A` 整行写在正文里，前面没有独立的
        //     「参考答案」行。早期只认 ①，这类表整块被当正文丢掉。
        // 没有答案的章（答案随题标注）整章跳过，AI 本身就能给出答案。
        val rowsByChapter = LinkedHashMap<Int?, List<Pair<Int, String>>>()
        for ((no, span) in splitByChapter(rawText)) {
            val rows = mutableListOf<Pair<Int, String>>()
            AnswerSheet.splitRegion(span).second?.let { rows += AnswerSheet.parse(it) }
            rows += parseInlineTable(span)
            if (rows.isNotEmpty()) rowsByChapter[no] = rows
        }
        if (rowsByChapter.isEmpty()) return emptyMap()

        val out = HashMap<Int, String>()
        for ((chNo, idxs) in groupByChapter(items)) {
            val rows = rowsByChapter[chNo] ?: continue
            bindChapter(items, idxs, rows, out)
        }
        return out
    }

    /** 行内前缀表：`答案：1.B 2.C 3.A`、`参考答案 1、B 2、C` */
    private val inlineTableHeaderRe = Regex("""(?:参考)?答案\s*[:：]?\s*(.+)""")
    private val inlinePairRe = Regex("""(\d{1,4})\s*[.、．:：)）]?\s*([A-EGH]{1,8}|对|错|正确|错误)""")

    /**
     * 抽 `答案：1.B 2.C` 这种行内表。
     *
     * **要求一行至少两对**：题干里也常出现「答案：B」这种，单对一律不当表，
     * 否则会把题干自带答案错认成答案表（`单条答案不算答案表避免误判` 用例守着）。
     */
    private fun parseInlineTable(span: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (line in span.lines()) {
            val hit = inlineTableHeaderRe.find(line) ?: continue
            val pairs = inlinePairRe.findAll(hit.groupValues[1]).toList()
            if (pairs.size < 2) continue
            pairs.forEach { p ->
                val no = p.groupValues[1].toIntOrNull() ?: return@forEach
                if (no in 1..9999) out += no to p.groupValues[2]
            }
        }
        return out
    }

    /** 按章号把题目下标分组，保持原顺序 */
    private fun groupByChapter(items: List<Item>): Map<Int?, List<Int>> {
        val groups = LinkedHashMap<Int?, MutableList<Int>>()
        for (i in items.indices) {
            groups.getOrPut(chapterNumberOf(items[i].chapter)) { mutableListOf() } += i
        }
        return groups
    }

    private fun bindChapter(
        items: List<Item>,
        idxs: List<Int>,
        rows: List<Pair<Int, String>>,
        out: MutableMap<Int, String>
    ) {
        // 字母答案表按题号去重（同题号只认第一次出现）；正文答案单独成一个池
        val letters = LinkedHashMap<Int, String>()
        val prose = mutableListOf<String>()
        for ((no, ans) in rows) {
            val t = ans.trim()
            if (t.isEmpty()) continue
            if (isLetterOnly(t)) letters.putIfAbsent(no, t)
            else if (t.length > 8) prose += t
        }

        val objective = idxs.filter { items[it].objectiveType }

        // 主通道：按题号精确填。题号来自结构化字段（AI 的 `number`）或题干前导数字。
        // 只有题号在表里唯一且答案合规时才采纳。
        val byNo = LinkedHashMap<Int, MutableList<Int>>()
        for (idx in objective) {
            val n = items[idx].questionNo ?: continue
            byNo.getOrPut(n) { mutableListOf() } += idx
        }
        for ((n, targets) in byNo) {
            val ans = letters[n] ?: continue
            // 同一题号对应多道题（模型把题号判错）时，只在该章没有序号线可用时才用
            for (idx in targets) {
                if (accepts(items[idx].optionKeys, ans)) out[idx] = ans
            }
        }

        // 兜底通道：按「本章第几道客观题」对上答案表的题号。
        //
        // 只在整个章**一道题号都取不到**时才启用（AI 解析把题号整段吞掉的场景）。
        // 只要有部分题拿到了题号，就不能再按序号猜 —— 剩下那几道在 objective 里的
        // 位置早已不等于它们真正的题号，`k + 1` 会整片错位（实测错填 8 条）。
        if (byNo.isEmpty() && objective.isNotEmpty()) {
            val maxKey = letters.keys.maxOrNull() ?: 0
            val noDup = letters.keys.size == letters.values.size
            if (noDup && maxKey in 1..objective.size) {
                objective.forEachIndexed { k, idx ->
                    val ans = letters[k + 1] ?: return@forEachIndexed
                    if (accepts(items[idx].optionKeys, ans)) out[idx] = ans
                }
            }
        }

        // 主观题只按内容配：题号在名词解释/简答题里同样从 1 重来，按号取必然错位
        if (prose.isNotEmpty()) {
            for (idx in idxs) {
                if (items[idx].objectiveType || out.containsKey(idx)) continue
                val stem = items[idx].stem
                val hit = prose.firstOrNull { proseScore(stem, it) >= PROSE_MATCH_THRESHOLD }
                if (hit != null) out[idx] = hit
            }
        }
    }

    /** 答案里出现的每个字母都必须是本题选项之一；判断题文字答案放行 */
    private fun accepts(optionKeys: Set<String>, answer: String): Boolean {
        if (optionKeys.isEmpty()) return true
        val letters = optionKeys.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val tokens = answer.split(Regex("""[\s,，、/]+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            val u = token.trim().uppercase()
            u in letters || u in setOf("对", "错", "正确", "错误", "T", "F")
        }
    }

    private fun isLetterOnly(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.length <= 8 && t.all { it in "ABCDEFGH对错正确误TF√×" }
    }

    /**
     * 题干与答案正文的匹配分（0..1），算法与 [TextQuestionParser] 的
     * `answerProbeScore` 一致：题干所有相邻汉字二元组的命中比例。
     *
     * 不用「最长片段完全包含」：OCR 常吃掉题干一个字（「常染**色**质」被识别成
     * 「常染质」），完全包含判据会把整题判成无答案。保留一条快速通道 ——
     * 任一长度 ≥6 的汉字片段被答案原样包含，直接算满命中。
     */
    private fun proseScore(stem: String, answer: String): Double {
        if (answer.isBlank()) return 0.0
        val runs = Regex("""[\u4e00-\u9fa5]{2,}""").findAll(stem).map { it.value }.toList()
        if (runs.isEmpty()) return 0.0
        if (runs.any { it.length >= 6 && answer.contains(it) }) return 1.0
        val grams = mutableSetOf<String>()
        for (run in runs) {
            for (i in 0 until run.length - 1) grams += run.substring(i, i + 2)
        }
        if (grams.isEmpty()) return 0.0
        val hit = grams.count { answer.contains(it) }
        return hit.toDouble() / grams.size
    }

    private fun leadingNumber(stem: String): Int? =
        leadingNumberRe.find(stem)?.groupValues?.get(1)?.toIntOrNull()

    /** 供调用方在题干没带题号时兜底取号（AI 输出的题干通常已被剥掉题号） */
    fun leadingNumberOf(stem: String): Int? = leadingNumber(stem)

    private fun chapterNumberOf(chapter: String?): Int? {
        if (chapter.isNullOrBlank()) return null
        val m = chapterSplitRe.find(chapter) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    private fun splitByChapter(rawText: String): List<Pair<Int?, String>> {
        val marks = chapterSplitRe.findAll(rawText).toList()
        if (marks.isEmpty()) return listOf(null to rawText)
        val out = mutableListOf<Pair<Int?, String>>()
        if (marks.first().range.first > 0) out += null to rawText.substring(0, marks.first().range.first)
        for ((i, m) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else rawText.length
            out += parseChineseNumber(m.groupValues[1]) to rawText.substring(m.range.first, end)
        }
        return out
    }

    /** 中文/阿拉伯数字 → Int；单字「十」必须认，否则「第十章」取不到章号 */
    private fun parseChineseNumber(raw: String): Int? {
        val t = raw.trim()
        t.toIntOrNull()?.let { return it }
        if (t.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (t == "十") return 10
        if (t.length == 2 && t[0] == '十') return 10 + (digits[t[1]] ?: return null)
        if (t.length == 2 && t[1] == '十') return (digits[t[0]] ?: return null) * 10
        if (t.length == 3 && t[1] == '十') {
            return (digits[t[0]] ?: return null) * 10 + (digits[t[2]] ?: return null)
        }
        return if (t.length == 1) digits[t[0]] else null
    }
}
