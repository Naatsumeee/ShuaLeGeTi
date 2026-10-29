package com.examhelper.shua.data.parse

/**
 * Markdown 题库 → 解析器友好的准纯文本。
 *
 * 定位：本 App 的「文档 → 题目」链路由 TextQuestionParser（离线逐行状态机）与
 * LlmQuestionParser（AI 分块）承担，两者都不认识 Markdown 标记。导入 .md 时先做
 * 一次结构降级，保留题库真正需要的信息：题号、选项字母、答案/解析标签、章节层级。
 *
 * 硬约束：**绝不动数学区**。`$x^2$` / `$$\frac{a}{b}$$` 里的 `*`、`_`、`\`、`|`
 * 全是 LaTeX 语义，一旦被 Markdown 规则吃掉，v1.0.6 修好的公式渲染会重新退化。
 * 因此所有行内替换都在 splitParts() 切出的「非数学段」里执行。
 *
 * 有意保留：`#` 标题行原样透传（TextQuestionParser.headingRegex 认它当章节，
 * AI 解析也认它当层级），列表标记 `- ` / `1. ` 原样透传。
 */
object MarkdownToText {

    private val fenceRe = Regex("""^\s{0,3}(`{3,}|~{3,})""")
    private val tableRowRe = Regex("""^\s*\|.*\|?\s*$""")
    private val tableSepRe = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
    private val ruleRe = Regex("""^\s*([-*_])\s*(?:\1\s*){2,}$""")
    /** 行首无序列表标记；只吃掉第一个圆点，后面内容整体左移一级 */
    private val bulletRe = Regex("""^\s*[-*+]\s+(?=[^\s-])""")
    /** 行首引用块标记 `> `，多级引用逐层剥 */
    private val blockquoteRe = Regex("""^\s{0,3}>\s?""")
    private val subRe = Regex("""(?i)<sub>(.*?)</sub>""")
    private val supRe = Regex("""(?i)<sup>(.*?)</sup>""")
    private val brRe = Regex("""(?i)<br\s*/?>""")
    private val tagRe = Regex(
        """(?i)</?(p|div|span|b|i|u|em|strong|font|table|thead|tbody|tr|td|th|ul|ol|li|hr|img|a|h[1-6]|pre|code|blockquote)\b[^>]*>"""
    )

    /** 整篇归一化：结构降级（按行）+ 行内清理（数学区感知）。 */
    fun normalize(source: String): String {
        if (source.isBlank()) return source
        val crlfFree = source.replace("\r\n", "\n").replace('\r', '\n')
        // 代码围栏整块先摘出来存好，用一行不含任何 Markdown 语义的哨兵占位；
        // 否则行内清理会把围栏里的 **x** / `x` / ~x~ 一并吃掉，等于篡改用户原文。
        val opaque = mutableListOf<String>()
        val skeleton = structuralPass(crlfFree, opaque)
        var out = inlineClean(skeleton)
        opaque.forEachIndexed { i, body -> out = out.replace(fenceSentinel(i), body) }
        return out
    }

    private fun fenceSentinel(i: Int) = "\u0000MD_FENCE_$i\u0000"

    // ---------- 结构降级（逐行，不改变行内数学内容的顺序） ----------

    private fun structuralPass(text: String, opaque: MutableList<String>): String {
        val sb = StringBuilder()
        var inFence = false
        var fenceBody: StringBuilder? = null
        for (raw in text.split('\n')) {
            val line = raw.trimEnd()

            // 围栏代码块：丢掉 ``` 本身，内容整块存进 opaque 后以哨兵行回填
            if (fenceRe.find(line.trimStart()) != null) {
                if (!inFence) {
                    inFence = true
                    fenceBody = StringBuilder()
                } else {
                    inFence = false
                    val body = fenceBody?.toString()?.trimEnd() ?: ""
                    sb.append(fenceSentinel(opaque.size)).append('\n')
                    opaque += body
                    fenceBody = null
                }
                continue
            }
            if (inFence) {
                fenceBody?.append(line)?.append('\n')
                continue
            }

            // 分隔线 --- *** ___ 无语义，丢掉
            if (ruleRe.matches(line)) continue

            // 无序列表标记 `- ` / `* ` / `+ `：下游解析器靠行首字母识别选项，
            // 圆点必须摘掉；有序列表 `1. ` 保留（那是题号，删了就找不到题了）。
            val flat = blockquoteRe.replace(bulletRe.replace(line, ""), "")

            // 表格原样保留（含 `|---|` 分隔行）：解析区的 Markdown 渲染器靠它出真表格，
            // 摊平成空格会把「药物|半衰期」粘成一句读不出结构的话。
            if (tableRowRe.matches(flat)) {
                if (flat.isNotBlank()) sb.append(flat).append('\n')
                continue
            }

            sb.append(flat).append('\n')
        }
        return sb.toString()
    }

    // ---------- 行内清理 ----------

    private fun inlineClean(text: String): String {
        val sb = StringBuilder()
        for (part in splitParts(text)) {
            sb.append(if (part.math) part.text else cleanPlain(part.text))
        }
        return sb.toString()
    }

    private fun cleanPlain(s: String): String {
        var out = s
        out = brRe.replace(out, "\n")
        out = subRe.replace(out) { it.groupValues[1].let { v -> "_$v" } }
        out = supRe.replace(out) { it.groupValues[1].let { v -> "^$v" } }
        out = tagRe.replace(out, "")
        out = imageRe.replace(out, "")
        out = linkRe.replace(out) { it.groupValues[1] }
        out = autoLinkRe.replace(out) { it.groupValues[1] }
        out = codeRe.replace(out) { it.groupValues[1] }
        // 组号陷阱：boldRe 的组1是标记符号 `**`/`__`，组2才是正文；取错组会把 `**1.**` 变成 `**`
        out = boldRe.replace(out) { it.groupValues[2] }
        out = strikeRe.replace(out) { it.groupValues[1] }
        out = escapeRe.replace(out) { it.groupValues[1] }
        return out
    }

    private val imageRe = Regex("""!\[[^\]\n]*\]([^()\n]*)""")
    private val linkRe = Regex("""\[([^\]\n]*)\]\([^()\n]*\)""")
    private val autoLinkRe = Regex("""<((?:https?|ftp)://[^>\s]+)>""")
    private val codeRe = Regex("""`+([^`\n]+)`+""")
    // 粗体先于斜体处理；单字符斜体刻意不处理（见文件头说明）
    private val boldRe = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val strikeRe = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val escapeRe = Regex("""\\([*_`\[\]#>~+|.!(){}-])""")

    // ---------- 数学区切分 ----------

    private data class Part(val math: Boolean, val text: String)

    /**
     * 切出 `$...$` 与 `$$...$$` 区间并标记为不可改写。
     * 行内 `$` 遇到换行仍未闭合则当普通字符（例如「价格 $5 和 $8」不能把全文吞掉）；
     * `$$` 允许多行（aligned / cases 环境常见）。
     */
    private fun splitParts(s: String): List<Part> {
        val parts = mutableListOf<Part>()
        val buf = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '$') {
                buf.append("\\$")
                i += 2
                continue
            }
            if (c == '$') {
                val display = i + 1 < s.length && s[i + 1] == '$'
                val marker = if (display) "$$" else "$"
                val from = i + marker.length
                val close = s.indexOf(marker, from)
                val nl = if (display) -1 else s.indexOf('\n', from)
                if (close >= 0 && (nl < 0 || close <= nl)) {
                    if (buf.isNotEmpty()) {
                        parts += Part(false, buf.toString())
                        buf.setLength(0)
                    }
                    parts += Part(true, s.substring(i, close + marker.length))
                    i = close + marker.length
                    continue
                }
            }
            buf.append(c)
            i++
        }
        if (buf.isNotEmpty()) parts += Part(false, buf.toString())
        return parts
    }
}
