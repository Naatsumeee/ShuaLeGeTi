package com.examhelper.shua.data.parse

/**
 * 「章末集中答案区」的识别与解析。
 *
 * 扫描版习题集的典型排版是：每章先出全部题目，章末再来一块答案：
 *
 * ```
 * 三、参考答案
 * （一）名词解释
 * 1．纯质性（homoplasmy):是指一个细胞或一个个体的线粒体中只有一种 mtDNA 的现象。
 * （二）选择题
 * 【A1型题】
 * 1. D
 * 2.B
 * 18.          ← OCR 把答案挤到下一行
 * A
 * 27.1         ← OCR 把「27.」后面粘了个脏字符
 * A
 * ```
 *
 * 两个必须宽松处理的地方（都是实测踩出来的）：
 * 1. **答案可能被 OCR 拆到下一行**：`18.` 单独一行、答案在下一行；
 *    也可能粘在一起成 `27.1`。
 * 2. **题号在每章内重新从 1 开始**，全书合并成一张表会串章，所以调用方按章分表。
 *
 * 这个类只做「文本 → 题号到答案的映射」，不关心题目本身。
 */
object AnswerSheet {

    /**
     * 答案区起始行。
     *
     * 宽松匹配：只要一行很短、且主体就是「参考答案 / 答案」，就算答案区起点。
     * 限定长度是为了不把题干里的「答案：B」当区域起点 —— 那种行由各 parser
     * 自己的行内答案规则处理。
     */
    private val regionStartRe = Regex(
        """^\s*(?:[一二三四五六七八九十]+\s*[、.．]|[（(][一二三四五六七八九十]+[)）])?\s*(?:参考答案|答案与解析|答案汇总|答案)\s*[：:]?\s*$"""
    )

    /** 答案区里的小节标题：「（一）名词解释」「【A1型题】」「二、选择题」 */
    private val sectionRe = Regex(
        """^\s*(?:【[^】]{1,12}】|[（(][一二三四五六七八九十]+[)）]\s*.{0,20}|[一二三四五六七八九十]+\s*[、.．]\s*.{0,20})$"""
    )

    /**
     * 整行一对：`1. D` / `2.B` / `12、C`
     *
     * 行首允许少量非字母脏字符：实测有 `¥19.B`、`*20.C` 这种 OCR 把装饰符
     * 一起读出来的行，严格从行首匹配会整条丢掉。
     */
    private val linePairRe = Regex(
        """^\s*[^\p{L}\p{N}]{0,2}\s*(\d{1,4})\s*[.、．:：)）]\s*([A-H]{1,8}|对|错|正确|错误)\s*$"""
    )

    /**
     * 只有题号没有答案：`18.` / `26．` / `27.1`
     *
     * 允许题号后跟最多 3 个非字母脏字符（含数字）：OCR 常把下一题的答案首字符
     * 粘上来（`27.1` 其实是「27 题 + 脏字符 1」）。限定整行长度，避免把
     * 「1.5 毫升」这类正文当题号。
     */
    private val numberOnlyRe = Regex("""^\s*(\d{1,4})\s*[.、．:：)）]\s*[^\p{L}]{0,3}\s*$""")

    /** 一行挤了多对：`1.E"2.B3.D` / `6.A7.B` */
    private val inlinePairRe = Regex(
        """(\d{1,4})\s*[.、．:：)）]\s*([A-H]{1,8}|对|错|正确|错误)"""
    )

    /**
     * 判定「一行多对」时允许残留在行尾的字符。
     *
     * 实测 `31.E32.C3` 这种行：最后那个 `3` 是被 OCR 截断的下一题题号。
     * 若严格要求「除答案对外只剩标点」，整行会被丢弃、31 和 32 全丢 ——
     * 所以允许尾部残留少量数字，已解析出的对仍然采纳。
     *
     * **必须显式带上 `\u3000`（全角空格）**：Java/Kotlin 正则的 `\s` 只覆盖
     * ASCII 空白，不含全角空格。而中文试卷的答案区几乎都用全角空格分隔
     * （`1.B　2.D　3.A`）。漏了它整行被判「有实义残留」→ 落到主观题分支 →
     * 得到一条 `1 → "B　2.D　3.A"` 的伪数据（实测踩过）。
     */
    private val fillerRe = Regex("""[\s\u3000\u2002-\u200b“”"'‘’、,，;；.。()（）/|\d]+""")

    /**
     * 题型分组前缀：`A1型：`、`【A1型题】`、`X型:`、`（一）名词解释`、`一、选择题`。
     *
     * 真实试卷与「把题库整理成文档」的工具都爱按题型分组写答案
     * （`A1型：1.B 2.D 3.A`）。早期实现要求「整行去掉答案对后只剩标点」，
     * 这个前缀会让整行不满足条件、整组答案全丢。
     */
    private val typeLabelRe = Regex(
        """^\s*(?:【[^】]{1,14}】|[（(][^)）]{1,14}[)）]|[一二三四五六七八九十]+\s*[、.．]\s*[^0-9]{0,14}|[A-Za-z]{0,2}\d{0,2}\s*型?题?\s*[：:]|[\u4e00-\u9fa5]{2,8}\s*[：:])\s*"""
    )

    /** 主观题答案正文：`1．纯质性（homoplasmy):是指…`、`2.简述…` */
    private val subjectiveRe = Regex("""^\s*(\d{1,4})\s*[.、．:：)）]\s*(\S.{2,})$""")

    /** 单个答案字符（用于跨行拼接） */
    private val loneAnswerRe = Regex("""^\s*([A-H]{1,8}|对|错|正确|错误)\s*$""")

    /** 该行是否是答案区起点 */
    fun isRegionStart(line: String): Boolean = regionStartRe.matches(line.trim())

    /**
     * 把文本切成「题目区」和「答案区」。
     *
     * 从**最后一个**答案区起点切：整本书里每章末都有一块答案，前面章节的答案区
     * 之后还会出现新章题目，取第一个会丢内容。找不到起点时返回 `(原文, null)`。
     */
    fun splitRegion(text: String): Pair<String, String?> {
        val lines = text.lines()
        val idx = lines.indexOfLast { isRegionStart(it) }
        if (idx < 0) return text to null
        return lines.take(idx).joinToString("\n") to lines.drop(idx + 1).joinToString("\n")
    }

    /** 解析文本里所有答案区，而不是只取最后一个起点。 */
    fun parseAllRegions(text: String): List<Pair<Int, String>> {
        val lines = text.lines()
        val starts = lines.indices.filter { isRegionStart(lines[it]) }
        if (starts.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            out += parse(lines.subList(start + 1, end).joinToString("\n"))
        }
        return out
    }

    /**
     * 解析答案区，返回 `题号 → 答案`（保持出现顺序）。
     *
     * 逐行按下列顺序尝试，命中即消费相应行：
     * 1. 整行一对 `1. D`
     * 2. 只有题号 `18.` → 与下一行的单个答案拼接
     * 3. 一行多对 `1.E"2.B3.D`（整行除答案对外只剩标点才算）
     * 4. 主观题 `1．纯质性（…):是指…` → 吃掉后续正文行直到下一个题号
     *
     * 返回的是 `List<Pair<Int, String>>` 而不是 Map：**同一章里名词解释和选择题
     * 各自从 1 开始编号**，题号会重复，用 Map 会互相覆盖（实测：名词解释 1~5
     * 占掉了选择题 1~5 的位置，那 5 道选择题永远拿不到答案）。
     * 调用方按「答案形态」（字母 vs 正文）区分它们。
     */
    fun parse(span: String): List<Pair<Int, String>> {
        val table = mutableListOf<Pair<Int, String>>()
        val lines = span.lines()
        var i = 0

        fun add(no: Int, ans: String) {
            if (no in 1..9999) table += no to ans
        }

        while (i < lines.size) {
            // 先剥掉题型分组前缀（`A1型：` / `【A1型题】` / `（一）名词解释`）。
            // 只在「剥完之后还能被下面的规则识别」时才用剥过的串，否则保留原行 ——
            // 否则 `（一）名词解释` 这种纯小标题会被剥成空串、连带把下一行也吞掉。
            val rawLine = lines[i].trim()
            val stripped = typeLabelRe.replaceFirst(rawLine, "").trim()
            val line = if (stripped.isNotEmpty() && stripped != rawLine) stripped else rawLine
            if (line.isEmpty() || sectionRe.matches(line) || sectionRe.matches(rawLine)) {
                i++
                continue
            }

            // 1) 整行一对
            val pair = linePairRe.find(line)
            if (pair != null) {
                val no = pair.groupValues[1].toIntOrNull()
                if (no != null) add(no, pair.groupValues[2])
                i++
                continue
            }

            // 2) 只有题号 → 答案在下一行（OCR 拆行）
            val noOnly = numberOnlyRe.find(line)
            if (noOnly != null) {
                val no = noOnly.groupValues[1].toIntOrNull()
                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                val next = if (j < lines.size) lines[j].trim() else ""
                val lone = loneAnswerRe.find(next)
                if (no != null && lone != null) {
                    add(no, lone.groupValues[1])
                    i = j + 1
                } else {
                    i++
                }
                continue
            }

            // 3) 一行多对（要求整行除答案对外只剩分隔标点，否则可能误吃题干）
            val pairs = inlinePairRe.findAll(line).toList()
            if (pairs.size >= 2) {
                val residue = fillerRe.replace(inlinePairRe.replace(line, ""), "")
                // 答案区里 occasionally 有一个题号被 OCR 成乱码；只要已识别出至少 3 对，
                // 且残留很短，就不能因为这一个坏字符丢掉整行。
                if (residue.isEmpty() || (pairs.size >= 3 && residue.length <= 6)) {
                    pairs.forEach { p ->
                        val no = p.groupValues[1].toIntOrNull()
                        if (no != null) add(no, p.groupValues[2])
                    }
                    i++
                    continue
                }
            }

            // 4) 主观题：题号 + 大段正文，正文可能跨多行
            val sub = subjectiveRe.find(line)
            if (sub != null) {
                val no = sub.groupValues[1].toIntOrNull()
                if (no != null && no in 1..9999) {
                    val body = StringBuilder(sub.groupValues[2].trim())
                    var j = i + 1
                    while (j < lines.size) {
                        val nxt = lines[j].trim()
                        if (nxt.isEmpty()) { j++; continue }
                        if (subjectiveRe.containsMatchIn(nxt) ||
                            linePairRe.matches(nxt) ||
                            sectionRe.matches(nxt) ||
                            numberOnlyRe.matches(nxt)
                        ) break
                        body.append(nxt)
                        j++
                    }
                    add(no, body.toString())
                    i = j
                    continue
                }
            }

            i++
        }
        return table
    }
}
