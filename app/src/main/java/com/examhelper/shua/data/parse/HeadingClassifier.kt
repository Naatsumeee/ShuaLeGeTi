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

    /** 序号片段：「（一）」「一、」「1.」——用来把「绪论（一）名词解释」切成两截 */
    private val ordinalCutRe = Regex(
        """[（(]\s*[一二三四五六七八九十百\d]{1,3}\s*[)）]""" +
            """|[一二三四五六七八九十百]{1,3}\s*[、.．]""" +
            """|\d{1,2}\s*[、.．)）]"""
    )

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
        return s
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
     * - 先削掉尾部拼进来的题型分组：「第一章 绪论（一）名词解释」→「第一章 绪论」
     * - 纯题型标题 → null（宁可显示「未分章」，也不能让「（一）名词解释」当章节）
     * - 「第三章 名词解释」这种「章号 + 题型尾巴」→ 保留「第三章」
     * - 其余原样（顺带去掉 Markdown 井号）
     */
    fun cleanChapter(raw: String?): String? {
        val base = raw?.trim()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val s = stripTypeTail(base).ifEmpty { base }
        val prefix = chapterPrefixRe.find(s)
        if (prefix != null) {
            // 「第三章 名词解释」：只剩章号，说明章名本体其实就是题型标签 → 只留「第三章」
            val rest = s.substring(prefix.range.last + 1).trim()
            if (rest.isEmpty()) return s
            if (isTypeHeading(rest)) return prefix.value.trim()
            return s
        }
        if (isNonChapterHeading(s)) return null
        return s
    }
}
