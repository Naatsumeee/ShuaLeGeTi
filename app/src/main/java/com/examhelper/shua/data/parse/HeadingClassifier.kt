package com.examhelper.shua.data.parse

/**
 * 区分「真章节标题」与「题型分组标题」。
 *
 * 用户反复报的 bug：导入题库后，章节名里出现「（一）名词解释」「一、单项选择题」
 * 这类**题型分组标题**。它们长得像标题，但只是一章内部按题型切的段，
 * 把它当章节名，同一章的题就被拆成「名词解释 / 选择题 / 判断题」好几组，
 * 按章节刷题直接散架。
 *
 * 之前这个判定只在 [TextQuestionParser] 里有一份 private 实现，
 * 于是 AI 解析链路（[ChunkPlanner.sectionOf] 向前回溯最近标题行）完全没有过滤，
 * 题型标题就这样变成了 chapter —— 这才是「这个 bug 就没修好过」的真正原因。
 *
 * 现在抽到这里，正则解析 / AI 解析 / 落库兜底三处共用同一份判定。
 */
object HeadingClassifier {

    /** 题型关键词。注意顺序无关，命中任一即算题型标题 */
    private val typeKeywords = listOf(
        "选择题", "单选题", "多选题", "判断题", "填空题", "名词解释", "简答题",
        "论述题", "案例分析", "问答", "计算题", "配伍题", "共用题干", "共用备选",
        // 英文/缩写卷常见写法
        "A1型题", "A2型题", "A3型题", "B1型题", "X型题"
    )

    /** 「第X章 / 第X节 / 第X部分 / 第X篇 / 第X卷」前缀 */
    private val chapterPrefixRe =
        Regex("""^\s*第\s*([一二三四五六七八九十百零\d]{1,4})\s*([章节部分篇卷])\s*""")

    /**
     * 章内小节名，但**不是章节**。
     *
     * 教辅书的每章正文都是「一、学习目标 / 二、习题 / 三、参考答案」这套骨架。
     * 它们长得完全像一级标题，一旦整本书没有「第X章」标题，这些字就会被当成章节名，
     * 于是题库列表里出现「学习目标」「习题」这种根本不存在的章。
     * 与题型标签同理：宁可不分章，也不能拿它们当章节。
     */
    private val sectionKeywords = listOf(
        "学习目标", "学习要求", "学习重点", "重点难点", "本章小结", "本章要点",
        "习题", "练习", "参考答案", "答案", "解析", "思考题", "复习题", "自测题",
        "考点精要", "常见考点"
    )

    /** 该标题是不是题型分组（如「（一）名词解释」「一、单项选择题」） */
    fun isTypeHeading(title: String?): Boolean {
        val t = title?.replace(" ", "")?.replace("\u3000", "") ?: return false
        if (t.isEmpty()) return false
        // 太长的多半是一整句题干/说明，不是标题
        if (t.length > 14) return false
        return typeKeywords.any { t.contains(it) }
    }

    /** 该标题是不是章内小节（「一、学习目标」「三、参考答案」） */
    fun isSectionHeading(title: String?): Boolean {
        val t = title?.replace(" ", "")?.replace("\u3000", "") ?: return false
        if (t.isEmpty() || t.length > 14) return false
        // 用「整串等于关键词」而不是 contains：「习题课」「答案解析」这类真章节名
        // 不该因为含了一个词就被判成小节
        return sectionKeywords.any { t == it }
    }

    /** 去掉「（一）」「一、」「1.」这类序号前缀，只留标题本体 */
    fun stripOrdinal(title: String): String {
        val t = title.trim()
            .trimStart('一', '二', '三', '四', '五', '六', '七', '八', '九', '十',
                ' ', '\u3000', '、', '.', '．', '（', '）', '(', ')')
            .trim()
        // 「（一）」被全角括号包住时 trimStart 会把它削干净；此处再兜一次纯数字序号
        val numbered = Regex("""^\d{1,2}\s*[、.．)）]\s*""").replace(t, "")
        return numbered.ifBlank { title.trim() }
    }

    /** 是否带「第X章」这种真章号 */
    fun hasChapterNumber(title: String?): Boolean =
        title != null && chapterPrefixRe.containsMatchIn(title)

    /** 取章号（阿拉伯或中文数字）；取不到返回 null */
    fun chapterNumberOf(title: String?): Int? {
        if (title == null) return null
        val m = chapterPrefixRe.find(title) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    /** 去掉章号前缀后的章名本体（用于判断「线粒体遗传」和「第六章 线粒体遗传」是不是同一章） */
    fun chapterBodyOf(title: String?): String {
        if (title == null) return ""
        val m = chapterPrefixRe.find(title.trim()) ?: return title.trim()
        return title.trim().substring(m.range.last + 1).trim()
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

    /**
     * 把同一章的多种写法归一到同一个名字。
     *
     * 用户报的四种形态其实是一件事的三个侧面 —— **同一章在不同位置被解析成了不同字符串**，
     * 于是章节列表里同时出现：
     *
     * | 形态 | 来源 |
     * |---|---|
     * | `第X章 AAAA` | 章标题完整读到（正常） |
     * | `第X章` | 章名那行没读到 / 被题型标签污染后剥成空 |
     * | `AAAA` | 章号那句和章名分在两处，只有名字带过来 |
     * | `第X章` + `第X章 AAAA` 并存 | 页眉重复出现，前一处只读到章号、后一处读到全名 |
     *
     * 归一规则（只用章号 + 章名本体做判据，不猜语义）：
     * 1. 每个章号选一个「最完整」的写法（带章号、章名本体最长）作 canonical；
     * 2. 只有章号的 `第X章` → 归到该章号的 canonical；
     * 3. 没有章号的 `AAAA`，如果章名本体等于/被包含于某个 canonical 的本体 → 归过去；
     * 4. 认不出的原样保留（宁可不合并，也不能把两章并成一章）。
     *
     * 返回 `旧名 → 新名` 的映射，只包含真正需要改的项。
     */
    fun unifyChapterNames(names: List<String>): Map<String, String> {
        val cleaned = names.mapNotNull { it.takeIf { n -> n.isNotBlank() } }.distinct()
        if (cleaned.size <= 1) return emptyMap()

        // ① 每个章号选 canonical：带章号、本体最长
        val canonicalByNo = HashMap<Int, String>()
        for (n in cleaned) {
            val no = chapterNumberOf(n) ?: continue
            val body = chapterBodyOf(n)
            val cur = canonicalByNo[no]
            if (cur == null || body.length > chapterBodyOf(cur).length) canonicalByNo[no] = n
        }
        // ② 本体 → canonical（用于接住没有章号的写法）
        val byBody = HashMap<String, String>()
        for ((_, canon) in canonicalByNo) {
            val body = bodyKey(chapterBodyOf(canon))
            if (body.length >= 2) byBody.putIfAbsent(body, canon)
        }

        val out = HashMap<String, String>()
        for (n in cleaned) {
            val no = chapterNumberOf(n)
            if (no != null) {
                val canon = canonicalByNo[no] ?: continue
                if (canon != n) out[n] = canon
                continue
            }
            // 没有章号：按章名本体找同一章
            val key = bodyKey(n)
            if (key.length < 2) continue
            val hit = byBody[key]
                ?: byBody.entries.firstOrNull { (b, _) ->
                    b.length >= 2 && key.length >= 2 && (b.contains(key) || key.contains(b))
                }?.value
            if (hit != null && hit != n) out[n] = hit
        }
        return out
    }

    /** 比对用的章名键：去空白、标点、全角空格，统一小写 */
    private fun bodyKey(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase()

    /** 序号片段：「（一）」「一、」「1.」——用来把「绪论（一）名词解释」切成两截 */
    private val ordinalCutRe = Regex(
        """[（(]\s*[一二三四五六七八九十百\d]{1,3}\s*[)）]""" +
            """|[一二三四五六七八九十百]{1,3}\s*[、.．]""" +
            """|\d{1,2}\s*[、.．)）]"""
    )

    /** 方括号包住的题型标签：`【A1型题】`、`［B1型题］` */
    private val bracketTypeRe = Regex("""[【\[［]\s*([^】\]］]{1,14})\s*[】\]］]""")

    /**
     * 把标题尾部/头部的题型标签剥掉，**保留章名本体**。
     *
     * 与 [stripTypeTail] 的分工：那个只管「序号 + 题型」这种分段式尾巴
     * （`第一章 绪论（一）名词解释`）；这里处理的是**紧贴在章名后面、没有序号**的
     * 形式（`第六章 线粒体遗传【A1型题】`、`第六章 线粒体遗传 A1型题`、
     * `第六章 线粒体遗传选择题`）。
     *
     * 老实现只按 `ordinalCutRe` 找切点，切不掉无序号尾巴，于是掉进
     * `cleanChapter` 的「章号 + 题型 → 只留章号」分支，把「线粒体遗传」整个章名
     * 丢了 —— 用户看到的「第X章 AAAA 变成只有第X章」就是这么来的。
     * 反过来，这类尾巴在 parser 里没被剥掉时，又会出现
     * 「第X章 AAAA【A1型题】」原样落库。
     */
    fun stripTypeLabels(raw: String): String {
        var s = raw.trim()
        // 整串本身就是题型分组（「一、单项选择题」「（一）名词解释」「名词解释」）时
        // 一个字都不能剥：剥完剩下的「单项」「（一）」既不是章名，又会让下游的
        // 「纯题型 → null」判定失效。这类标题该原样交给 isTypeHeading 去判。
        if (chapterPrefixRe.find(s) == null && isTypeHeading(s)) return s
        // ① 方括号里的题型标签，任意位置
        s = bracketTypeRe.replace(s) { m ->
            if (isTypeHeading(m.groupValues[1])) " " else m.value
        }
        // ② 尾部无序号、紧贴章名的题型词（`线粒体遗传选择题` / `线粒体遗传 A1型题`）
        //    从最长关键词开始试，避免「选择题」先命中把「多项选择题」切成「多项」
        for (kw in typeKeywords.sortedByDescending { it.length }) {
            if (!s.endsWith(kw)) continue
            val head = s.dropLast(kw.length).trim()
            // 剥完必须还剩至少 2 个实义字符，否则「名词解释」这类会被剥成空串
            if (bodyKey(stripOrdinal(head)).length >= 2) {
                s = head
                break
            }
        }
        return s.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /**
     * 找到「真标题 + 题型尾巴」的分界位置；找不到返回 -1。
     *
     * 从所有序号片段里挑最靠右、且其后整段都是题型标题的那一个 —— 逗号包住的
     * 「第一章 绪论（一）名词解释（二）简答题」会先切掉「（二）简答题」，
     * 下一轮再切掉「（一）名词解释」，所以 [stripTypeTail] 要循环调用。
     */
    private fun typeTailCut(s: String): Int {
        var best = -1
        for (m in ordinalCutRe.findAll(s)) {
            val head = s.substring(0, m.range.first).trim()
            val tail = s.substring(m.range.last + 1).trim()
            if (head.isNotEmpty() && tail.isNotEmpty() && isTypeHeading(tail)) best = m.range.first
        }
        return best
    }

    /**
     * 去掉标题尾部的题型分组，如「第一章 绪论（一）名词解释」→「第一章 绪论」。
     *
     * 这是用户报的「章节名带（一）名词解释」最常见的真实形态：
     * 正文里章标题和题型分组挨得很近，解析时把整行当成了章节名。
     */
    fun stripTypeTail(raw: String): String {
        var s = raw.trim()
        var guard = 0
        while (guard++ < 4) {
            val cut = typeTailCut(s)
            if (cut <= 0) break
            s = s.substring(0, cut).trim()
        }
        // 分段式尾巴（`（一）名词解释`）切完，再处理紧贴章名、没序号的形式
        // （`第六章 线粒体遗传【A1型题】` / `…线粒体遗传 A1型题`）。
        return stripTypeLabels(s)
    }

    /**
     * 不能当章节名的标题：题型分组 + 章内小节，且不带章号。
     *
     * 两类都要挡：
     * - 「（一）名词解释」这类题型分组 —— 同一章的题会被切成好几组，按章节刷题直接散架；
     * - 「一、学习目标」「三、参考答案」这类章内骨架 —— 整本没有「第X章」时它们会顶上来，
     *   题库里就凭空多出「学习目标」「习题」这种章。
     *
     * 带章号的一律放过（「第十二章 课后习题」是真章节）。
     */
    fun isNonChapterHeading(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        if (hasChapterNumber(title)) return false
        val s = stripOrdinal(title)
        return isTypeHeading(title) || isTypeHeading(s) ||
            isSectionHeading(title) || isSectionHeading(s)
    }

    /** 纯题型标题：是题型分组、且不带章号。保留旧名以兼容既有调用 */
    fun isPureTypeHeading(title: String?): Boolean = isNonChapterHeading(title)

    /**
     * 章节名清洗（落库前的最后一道闸）。
     *
     * 顺序很重要，每一步都有实测踩过的坑：
     * 1. 先削掉尾部拼进来的题型分组/题型标签 →「第一章 绪论（一）名词解释」、
     *    「第六章 线粒体遗传【A1型题】」、「第六章 线粒体遗传 A1型题」都归一到章名本体；
     * 2. 剩下的是纯题型标题 → null（宁可「未分章」，也不能让「（一）名词解释」当章节）；
     * 3. 「第三章 名词解释」这种**章名本体就是题型词**的 → 只留「第三章」；
     * 4. 其余原样（顺带去掉 Markdown 井号）。
     *
     * 第 1 步与第 3 步的边界是这个方法的全部难点：**必须先剥尾巴，再判断章名本体**。
     * 反过来的话「第六章 线粒体遗传【A1型题】」会被判成「章名本体是题型词」，
     * 输出「第六章」—— 章名整个丢掉。用户报的「第X章 AAAA 变成只有第X章」就是它。
     */
    fun cleanChapter(raw: String?): String? {
        val base = raw?.trim()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val s = stripTypeTail(base).ifEmpty { base }
        val prefix = chapterPrefixRe.find(s)
        if (prefix != null) {
            // 剥完只剩章号本身（原本就是个「第三章」标题，或者章名确实就是题型词）
            val rest = s.substring(prefix.range.last + 1).trim()
            if (rest.isEmpty()) return prefix.value.trim()
            // 兜底：万一还有题型词残留（长度不足 2 的畸形输入），只留章号
            if (isTypeHeading(rest) && rest.length <= 4) return prefix.value.trim()
            return s
        }
        if (isNonChapterHeading(s)) return null
        return s
    }
}
