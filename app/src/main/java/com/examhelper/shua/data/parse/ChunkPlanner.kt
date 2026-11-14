package com.examhelper.shua.data.parse

/**
 * 按「题目边界 + 章节边界」切分 OCR 文本。
 *
 * 两条硬规则（都是用户实测报出来的问题）：
 *
 * 1. **切点不能落在题目中间**。旧实现只按行累加到 size 就切，会让题干留在上一段、
 *    选项落到下一段，结果是两道残缺的「题」——即「切在题目中间导致缺题/不完整」。
 *    这里先吃到超出 size，再向后找到最近的**题目/章节起始行**才下刀。
 *
 * 2. **同一章节的题尽量不拆开，且每块都要带上它所属的章节标题**。
 *    早先的 chunk 只包含 `start..end` 这些行，章节标题常常在上一块里，
 *    于是第二块往后 LLM 完全不知道这些题属于哪一章，`chapter` 被猜错或干脆为 null，
 *    跨段题目也被归到错误的章节里。现在：
 *      - 找到切点时优先选**下一个章节标题**（整章成块，超过 hardCap 才退回按题切）；
 *      - 每个分块都前置它所处章节的标题行（`[章节上下文]`），
 *        保证任意一块单独送进模型都能判出正确章节。
 *
 * 唯一强制硬切的情况：单段连续内容超过 size*3 都没有任何边界，否则永远切不开。
 */
object ChunkPlanner {

    data class Chunk(
        val index: Int,
        val text: String,
        val startLine: Int,
        val endLine: Int,
        /** 该块所属章节标题（已作为前缀写进 [text]），无章节时为 null */
        val section: String? = null
    )

    /** `1.` `2、` `（3）` `4)` 这类题号开头 */
    private val questionStartRe =
        Regex("""^\s*[（(]?\s*(\d{1,4})\s*[）)]?\s*[、.．,，:：)）]\s*\S""")

    /** 章节 / 大题 / Markdown 标题开头 */
    private val sectionStartRe = Regex(
        """^\s*(第\s*[一二三四五六七八九十百零\d]+\s*[章节部分卷篇]""" +
            """|#{1,6}\s+\S""" +
            """|[（(]\s*[一二三四五六七八九十]{1,3}\s*[）)]\s*\S""" +
            """|[一二三四五六七八九十]{1,3}\s*[、.．]\s*\S)"""
    )

    fun isQuestionStart(line: String): Boolean = questionStartRe.containsMatchIn(line)

    fun isSectionStart(line: String): Boolean = sectionStartRe.containsMatchIn(line)

    /** 边界行：可以在它**之前**下刀 */
    fun isBoundary(line: String): Boolean = isQuestionStart(line) || isSectionStart(line)

    /**
     * 章节标题归一键：去掉 Markdown 井号、连续空白、首尾标点，只留下文字。
     * 用来判断「两个标题是不是同一章节」——OCR 出来的 `第 3 章 药理学` 与
     * `## 第三章 药理学` 应当被认为是同一章。
     */
    fun sectionKey(line: String): String =
        line.trim()
            .trimStart('#', ' ', '\t')
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""[：:。、,，.．]+$"""), "")
            .lowercase()

    private fun isSameLongHeading(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // 长短标题互为前缀时也算同一节（`第三章 药理学` vs `第三章 药理`）
        return a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))
    }

    /** 块首衔接窗口的行数上限：把上一块末尾「最后一道题」的完整内容带到本块开头 */
    const val DEFAULT_OVERLAP_LINES = 14

    /**
     * @param size 目标分块字符数（软上限，允许为保住章节/题目完整性而超出）
     * @param overlapLines 块首衔接窗口行数（0 = 关闭重叠）
     */
    fun plan(text: String, size: Int, overlapLines: Int = DEFAULT_OVERLAP_LINES): List<Chunk> {
        if (text.isBlank()) return emptyList()
        if (text.length <= size) return listOf(Chunk(0, text, 0, 0, sectionOf(text.lines())))

        val lines = text.lines()
        val n = lines.size
        val bound = BooleanArray(n) { isBoundary(lines[it]) }
        val sectionLine = BooleanArray(n) { isSectionStart(lines[it]) }

        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0
        val hardCap = size * 3

        while (start < n) {
            var end = start
            var len = 0
            while (end < n) {
                len += lines[end].length + 1
                end++
                if (len >= size) break
            }

            // 单行本身就超长（OCR 没换行的整段文字）：只能按字符硬切
            if (end == start + 1 && lines[start].length > hardCap) {
                val line = lines[start]
                var from = 0
                // 无换行的超长文本也必须重叠，否则切点处的题干/答案无法被下一块抢救。
                val charOverlap = minOf(600, size / 10).coerceAtLeast(80)
                while (from < line.length) {
                    var to = minOf(from + size, line.length)
                    // 切点不能落在代理对中间：BMP 以外的字符（生僻字、emoji）在 Kotlin
                    // 里占两个 Char，硬切会把一个字符劈成两半，两段都拿到孤立代理，
                    // 发给模型或落盘后显示成乱码。往后退一位即可，代价是最多丢一个字符的边界。
                    if (to < line.length && to > from + 1 &&
                        line[to - 1].isHighSurrogate() && line[to].isLowSurrogate()
                    ) {
                        to--
                    }
                    chunks += Chunk(index++, line.substring(from, to), start, start)
                    if (to >= line.length) break
                    // 下一块从上一块尾部回退一小段，重复内容由解析后的题号/题干去重。
                    from = (to - charOverlap).coerceAtLeast(from + 1)
                    // 重叠起点也不能落在代理对中间。
                    if (from < line.length && line[from].isLowSurrogate()) from++
                }
                start++
                continue
            }

            if (end < n) {
                val hardLimit = run {
                    var probe = end
                    var extra = 0
                    while (probe < n && extra < hardCap) {
                        extra += lines[probe].length + 1
                        probe++
                    }
                    probe
                }

                // 先找下一个章节标题：整章成块，避免同一章的题被切到两块里
                val nextSection = (end until hardLimit).firstOrNull { sectionLine[it] }
                end = if (nextSection != null) {
                    nextSection
                } else {
                    // 退化成按题目边界切
                    var probe = end
                    var found = -1
                    while (probe < hardLimit) {
                        if (bound[probe]) {
                            found = probe
                            break
                        }
                        probe++
                    }
                    if (found > start) found else end
                }
            }

            if (end <= start) end = start + 1 // 兜底，防死循环
            val body = lines.subList(start, end).joinToString("\n")
            if (body.isNotBlank()) {
                val sec = sectionOf(lines, start)
                // 章节标题在上一块里 → 补到本块开头，否则这一块的题会丢章节归属。
                // 判断「本块自己就带标题」：起始行是章节行，或首行就是该标题文字。
                val selfTitled = sec == null ||
                    isSectionLineAt(lines, start, sec) ||
                    lines[start].trim().startsWith(sec)
                val full = if (!selfTitled) "[章节上下文] $sec\n$body" else body
                chunks += Chunk(index++, full, start, end - 1, sec)
            }

            // 块首衔接窗口：切点落在题目中间时（`lines[end]` 不是边界行），
            // 把上一块末尾那道**可能被切断的题**整段带到下一块开头。
            // 这样即使某道题的题干留在上一块、选项落到下一块，两块里也各有一份完整副本，
            // 由 finalize 的宽松去重合并（保留最完整的那份 + 合并答案/解析）。
            var next = end
            if (overlapLines > 0 && end < n && !bound[end]) {
                val floorLine = (end - overlapLines).coerceAtLeast(start + 1)
                var probe = end - 1
                while (probe >= floorLine) {
                    if (bound[probe] && probe > start) {
                        next = probe
                        break
                    }
                    probe--
                }
            }
            start = if (next > start) next else end
        }
        return chunks
    }

    /** 当前行是否就是（或已经是）该章节标题 */
    private fun isSectionLineAt(lines: List<String>, idx: Int, section: String): Boolean =
        idx in lines.indices && sectionKey(lines[idx]) == sectionKey(section)

    /**
     * 该行所属的章节标题（向前回溯最近的一行**真**章节标题）。
     *
     * 这里必须跳过题型分组标题（「（一）名词解释」「一、单项选择题」）：
     * AI 解析链路的分块上下文就是用它算出来的，旧实现只认「长得像标题」，
     * 于是题型标题被当成 section 传给模型，模型照着写进 chapter 字段，
     * 落库后章节名里就带着「（一）名词解释」—— 用户报了很多次的那个 bug。
     * 现在与 [TextQuestionParser] 共用 [HeadingClassifier] 的判定。
     */
    private fun sectionOf(lines: List<String>, from: Int = 0): String? {
        var i = from.coerceAtLeast(0)
        var ordinalFallback: String? = null
        while (i >= 0) {
            if (isSectionStart(lines[i]) && !HeadingClassifier.isPureTypeHeading(lines[i])) {
                val title = lines[i].trim()
                if (HeadingClassifier.hasChapterNumber(title)) return title
                if (ordinalFallback == null) ordinalFallback = title
            }
            // 超过 200 行还没找到就当没有（避免整篇回溯）
            if (from - i > 200) return null
            i--
        }
        return ordinalFallback
    }

    /** 去重键：去掉空白与标点后的小写题干（跨块重复的题只保留第一次出现的） */
    fun dedupeKey(stem: String): String =
        stem.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 宽松相似度（0..1）：用于抢救「同一道题在两块里题干被 OCR 截得不一样」的情况。
     *
     * 严格按 [dedupeKey] 全等去重会把残缺版和完整版当成两道题，页面上就出现
     * 一道完整题 + 一道只有半个题干的残题。
     */
    fun similarity(a: String, b: String): Double {
        val x = dedupeKey(a)
        val y = dedupeKey(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val short = if (x.length <= y.length) x else y
        val long = if (x.length <= y.length) y else x
        // 短的是长的前缀 → 同一题干被 OCR 截断。
        // 要求短的长度够（≥8 字）且占比够高（≥0.70），否则「关于阿司匹林」
        // 会把「关于阿司匹林的禁忌证有哪些」这类同开头不同题吃掉。
        if (long.startsWith(short) && short.length >= 8) {
            val ratio = short.length.toDouble() / long.length
            if (ratio >= 0.70) return 0.90
            return ratio
        }
        // 字符集合 Jaccard
        val sx = x.toSet()
        val sy = y.toSet()
        val inter = sx.intersect(sy).size
        val union = sx.union(sy).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** 同一道题的判定阈值 */
    const val SAME_QUESTION = 0.82

    fun isSameQuestion(a: String, b: String): Boolean = similarity(a, b) >= SAME_QUESTION

    /**
     * 校正模型给出的章节名，避免「第 XX 章」前缀被丢掉。
     *
     * 模型经常把分块里 `[章节上下文] 第三章 抗菌药物` 这类提示理解成
     * 「本章叫抗菌药物」，输出的 chapter 就只剩下半截；下一块又把
     * `第三章` 当成另一个章节标题，于是同一章被拆成「第三章 抗菌药物」
     * 和「抗菌药物」两个章节，用户看到的就是「标题前缀丢失、章节错乱」。
     *
     * 规则：
     * - 分块有章节上下文时，以它的标题为准（这是原文里的写法，带前缀）；
     * - 模型给的名字是它的一部分（或被它包含）→ 用分块的完整标题；
     * - 模型给的名字确实是别的一章 → 尊重模型（可能是块里真有跨章内容）。
     */
    /** 「第 X 章 / 第 X 节 / 第 X 部分」前缀 */
    private val chapterPrefixRe =
        Regex("""^\s*第\s*[一二三四五六七八九十百零\d]+\s*[章节部分卷篇]\s*""")

    /** 去掉 Markdown 井号与「第 X 章」前缀，只留章节标题本体，用于比对 */
    private fun chapterCore(s: String): String =
        s.trim().trimStart('#').trim().replace(chapterPrefixRe, "").trim()

    fun resolveChapter(section: String?, parsed: String?): String? {
        // 模型有时候会把「（一）名词解释」这类题型分组标题塞进 chapter，直接丢掉
        val rawSection = section?.let { HeadingClassifier.cleanChapter(it) }
        val parsedClean = parsed?.let { HeadingClassifier.cleanChapter(it) }
        val sec = rawSection?.takeIf { it.isNotEmpty() }
            ?: return parsedClean?.takeIf { it.isNotEmpty() }
        val got = parsedClean?.takeIf { it.isNotEmpty() } ?: return sec
        val ks = sectionKey(sec)
        val kg = sectionKey(got)
        if (ks == kg) return sec
        if (HeadingClassifier.hasChapterNumber(sec) && !HeadingClassifier.hasChapterNumber(got)) return sec
        if (HeadingClassifier.hasChapterNumber(sec) && HeadingClassifier.hasChapterNumber(got) &&
            HeadingClassifier.chapterNumberOf(sec) != HeadingClassifier.chapterNumberOf(got)
        ) return got
        // 章节标题本体相同（`第三章 抗菌药物` vs `第3章 抗菌药物` vs `抗菌药物`）→ 用原文的完整写法
        val cs = sectionKey(chapterCore(sec))
        val cg = sectionKey(chapterCore(got))
        if (cs.isNotEmpty() && cg.isNotEmpty() && (cs == cg || cs.contains(cg) || cg.contains(cs))) return sec
        if (ks.isNotEmpty() && kg.isNotEmpty() && ks.contains(kg)) return sec
        if (ks.isNotEmpty() && kg.isNotEmpty() && kg.contains(ks)) return got
        return got
    }
}
