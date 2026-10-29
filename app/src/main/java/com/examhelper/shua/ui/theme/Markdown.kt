package com.examhelper.shua.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染器（零第三方依赖，纯 Compose 实现），与 [Latex] 协同工作。
 *
 * 为什么自研：模型（尤其「问 AI」与「深度解题」）输出的回答里普遍带 Markdown，
 * 直接当纯文本显示会露出 `**粗体**`、`### 标题`、`- 列表` 这些源码标记，可读性很差。
 *
 * 支持的语法子集（覆盖模型实际会输出的部分）：
 * - 标题 `#`~`######`
 * - 粗体 `**x**` / `__x__`、斜体 `*x*` / `_x_`、删除线 `~~x~~`、行内代码 `` `x` ``
 * - 无序列表 `-` `*` `+`（按前导空格算层级）、有序列表 `1.` `1)`
 * - 引用 `> x`、分隔线 `---` `***` `___`
 * - 围栏代码块 ``` 与 ```lang
 * - 表格 `| a | b |`（含分隔行）
 * - **行内公式 `$..$` 与行间公式 `$$..$$` 交给 [Latex] 渲染**，两者可任意混排
 *
 * 不含任何 Markdown 标记的纯文本会退化为单个 Text，不额外测量。
 */
object Markdown {

    // ---------------- 块级模型 ----------------

    private sealed interface MdBlock {
        data class Heading(val level: Int, val text: String) : MdBlock
        /** 普通段落；[displayMath] 表示整段就是一个 `$$..$$` 公式 */
        data class Para(val text: String, val displayMath: Boolean = false) : MdBlock
        data class Bullet(
            val indent: Int,
            val ordered: Boolean,
            val number: Int,
            val text: String
        ) : MdBlock
        data class Quote(val text: String) : MdBlock
        data class Code(val lang: String, val code: String) : MdBlock
        data object Rule : MdBlock
        data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    }

    // ---------------- 解析 ----------------

    private val bulletRe = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val orderedRe = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
    private val ruleRe = Regex("""^\s*([-*_])\s*(?:\1\s*){2,}$""")
    private val tableSepRe = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")

    private val lineHeadingRe = Regex("""(^|\n)[ \t]*#{1,6}[ \t]""")

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 文本里是否存在**成对**的行内标记。
     *
     * 扫描时整体跳过 `$…$` / `$$…$$` 公式段与反引号代码段：LaTeX 里满是下标（`x_1`、`a_{n+1}`），
     * 不跳的话纯公式文本会被误判成 Markdown，进而被行内解析器把下划线吃掉当斜体渲染。
     *
     * @param mark `*` / `_` / `~~`
     * @param edgeGuard true 时要求标记外侧不紧邻字母数字（CommonMark 对 `_` 的规则，
     *                  这样 `snake_case_name` 不会被拆成斜体）
     */
    private fun hasInlinePair(s: String, mark: String, edgeGuard: Boolean): Boolean {
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            // 反斜杠转义：下一个字符是字面量
            if (c == '\\' && i + 1 < n) {
                i += 2
                continue
            }
            if (c == '$') {
                val dbl = i + 1 < n && s[i + 1] == '$'
                val tok = if (dbl) "$$" else "$"
                val close = s.indexOf(tok, i + tok.length)
                if (close > i + tok.length) {
                    i = close + tok.length
                    continue
                }
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    i = close + 1
                    continue
                }
            }
            if (s.startsWith(mark, i)) {
                // 单字符标记要让位给双字符标记（** 与 __ 归粗体分支处理）
                val doubled = mark.length == 1 && i + 1 < n && s[i + 1] == mark[0]
                val prev = if (i == 0) ' ' else s[i - 1]
                if (!doubled && (!edgeGuard || !(isWordChar(prev) || prev == mark[0]))) {
                    val open = i + mark.length
                    val close = s.indexOf(mark, open)
                    if (close > open) {
                        val body = s.substring(open, close)
                        val next = if (close + mark.length < n) s[close + mark.length] else ' '
                        if (body.none { it == '\n' } &&
                            !body.first().isWhitespace() &&
                            !body.last().isWhitespace() &&
                            (!edgeGuard || !(isWordChar(next) || next == mark[0]))
                        ) return true
                    }
                }
            }
            i++
        }
        return false
    }

    /** 判断文本是否含任何 Markdown 标记（不含则整体退化为纯 Text） */
    fun looksLikeMarkdown(s: String): Boolean {
        if (s.isEmpty()) return false
        if ('\n' in s) return true
        if ("**" in s || "__" in s || "`" in s) return true
        if (hasInlinePair(s, "~~", edgeGuard = false)) return true
        // 单行斜体：*重点* / _重点_（以前只认 **，导致单个星号原样显示给用户）
        if (hasInlinePair(s, "*", edgeGuard = false)) return true
        if (hasInlinePair(s, "_", edgeGuard = true)) return true
        // 行首标题 / 引用 / 列表
        if (lineHeadingRe.containsMatchIn(s)) return true
        val first = s.first()
        if (first == '>' || first == '#') return true
        if (bulletRe.containsMatchIn(s) || orderedRe.containsMatchIn(s)) return true
        return false
    }

    /** 仅用于单测：返回段落块序列（文本, 是否行间公式），用于验证 `$$` 拆分与定界符归一 */
    internal fun debugParaBlocks(text: String): List<Pair<String, Boolean>> =
        parse(text).filterIsInstance<MdBlock.Para>().map { it.text to it.displayMath }

    private fun splitCells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        return t.split('|').map { it.trim() }
    }

    /**
     * 统一公式定界符。
     *
     * 模型输出的 LaTeX 定界符非常随意：除了标准 `$..$` / `$$..$$`，还常见
     * `\(..\)` 与 `\[..\]`（LaTeX 原生写法）。这些写法若不归一，`\[` 会被当成
     * 普通字符，公式体就原样打在屏幕上——用户反馈的「`$$` 之间的内容被直接输出」
     * 里有相当一部分其实是这种定界符混淆。
     */
    private fun normalizeMath(s: String): String {
        if ('\\' !in s) return s
        return s
            .replace("\\[", "$$").replace("\\]", "$$")
            .replace("\\(", "$").replace("\\)", "$")
    }

    private fun parse(source: String): List<MdBlock> {
        val lines = normalizeMath(source).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<MdBlock>()
        var i = 0
        val para = StringBuilder()

        /**
         * 把一段正文按 `$$..$$` 边界拆开：行间公式单独成为 [MdBlock.Para]（displayMath = true），
         * 其余文字仍是普通段落。
         *
         * 之前只在「整段正好以 $$ 开头、以 $$ 结尾」时才认行间公式，于是
         * 「解析：\n$$\nx^2\n$$\n所以选 A」这类常见输出会把公式当普通文字交给行内渲染器，
         * 公式源码（含 `\frac` 之类）就直接露在屏幕上——这正是用户反馈的 bug。
         */
        fun emitPara(text: String) {
            var rest = text
            while (rest.isNotEmpty()) {
                val open = rest.indexOf("$$")
                if (open < 0) {
                    val t = rest.trim()
                    if (t.isNotEmpty()) out += MdBlock.Para(t, false)
                    return
                }
                val before = rest.substring(0, open).trim()
                if (before.isNotEmpty()) out += MdBlock.Para(before, false)
                val close = rest.indexOf("$$", open + 2)
                if (close < 0) {
                    // 未闭合：原样保留，交给行内解析器（避免吞掉后半段）
                    val t = rest.trim()
                    if (t.isNotEmpty()) out += MdBlock.Para(t, false)
                    return
                }
                val math = rest.substring(open + 2, close).trim()
                if (math.isNotEmpty()) out += MdBlock.Para(math, true)
                rest = rest.substring(close + 2).trimStart()
            }
        }

        fun flushPara() {
            if (para.isEmpty()) return
            val t = para.toString().trim()
            para.clear()
            if (t.isEmpty()) return
            emitPara(t)
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // 围栏代码块
            if (line.trimStart().startsWith("```")) {
                flushPara()
                val lang = line.trimStart().removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i].trimEnd())
                    i++
                }
                i++ // 跳过结束围栏
                out += MdBlock.Code(lang, body.toString().trimEnd())
                continue
            }

            if (line.isBlank()) {
                flushPara()
                i++
                continue
            }

            // 分隔线（必须在列表之前判断，否则 --- 会被当成列表项）
            if (ruleRe.matches(line)) {
                flushPara()
                out += MdBlock.Rule
                i++
                continue
            }

            // 标题
            val head = headingRe.find(line)
            if (head != null) {
                flushPara()
                out += MdBlock.Heading(head.groupValues[1].length, head.groupValues[2].trim())
                i++
                continue
            }

            // 引用
            if (line.trimStart().startsWith(">")) {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (q.isNotEmpty()) q.append('\n')
                    q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ").trimEnd())
                    i++
                }
                out += MdBlock.Quote(q.toString())
                continue
            }

            // 表格：当前行含 | 且下一行是分隔行
            if ('|' in line && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) &&
                lines[i + 1].contains('-')
            ) {
                flushPara()
                val header = splitCells(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) {
                    rows += splitCells(lines[i])
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            // 无序列表
            val bullet = bulletRe.find(line)
            if (bullet != null) {
                flushPara()
                val indent = bullet.groupValues[1].length
                out += MdBlock.Bullet(indent / 2, false, 0, bullet.groupValues[3].trim())
                i++
                continue
            }

            // 有序列表
            val ordered = orderedRe.find(line)
            if (ordered != null) {
                flushPara()
                val indent = ordered.groupValues[1].length
                out += MdBlock.Bullet(
                    indent / 2,
                    true,
                    ordered.groupValues[2].toIntOrNull() ?: 1,
                    ordered.groupValues[3].trim()
                )
                i++
                continue
            }

            // 普通段落行（连续行合并）
            if (para.isNotEmpty()) para.append('\n')
            para.append(line.trim())
            i++
        }
        flushPara()
        return out
    }

    // ---------------- 行内解析 ----------------

    private fun AnnotatedString.Builder.inline(
        s: String,
        baseSp: Float,
        bold: Boolean = false,
        italic: Boolean = false,
        codeBg: Color = Color.Unspecified,
        codeFg: Color = Color.Unspecified
    ) {
        var i = 0
        val buf = StringBuilder()

        fun flush() {
            if (buf.isEmpty()) return
            val t = buf.toString()
            buf.clear()
            if (bold || italic) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (bold) FontWeight.SemiBold else null,
                        fontStyle = if (italic) FontStyle.Italic else null
                    )
                ) { append(t) }
            } else {
                append(t)
            }
        }

        while (i < s.length) {
            val c = s[i]

            // 反斜杠转义
            if (c == '\\' && i + 1 < s.length) {
                buf.append(s[i + 1])
                i += 2
                continue
            }

            // LaTeX 公式
            if (c == '$') {
                val display = i + 1 < s.length && s[i + 1] == '$'
                val open = if (display) i + 2 else i + 1
                val closeTok = if (display) "$$" else "$"
                val close = s.indexOf(closeTok, open)
                if (close > open) {
                    flush()
                    append(Latex.annotate(s.substring(open, close), baseSp * (if (display) 1.06f else 1f)))
                    i = close + closeTok.length
                    continue
                }
            }

            // 行内代码
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    val body = s.substring(i + 1, close)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseSp * 0.94f).sp,
                            background = if (codeBg == Color.Unspecified) Color.Unspecified else codeBg,
                            color = if (codeFg == Color.Unspecified) Color.Unspecified else codeFg
                        )
                    )
                    append(body)
                    pop()
                    i = close + 1
                    continue
                }
            }

            // 粗体
            if (s.startsWith("**", i) || s.startsWith("__", i)) {
                val tok = s.substring(i, i + 2)
                val close = s.indexOf(tok, i + 2)
                if (close > i + 2) {
                    flush()
                    inline(s.substring(i + 2, close), baseSp, true, italic, codeBg, codeFg)
                    i = close + 2
                    continue
                }
            }

            // 删除线
            if (s.startsWith("~~", i)) {
                val close = s.indexOf("~~", i + 2)
                if (close > i + 2) {
                    flush()
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    inline(s.substring(i + 2, close), baseSp, bold, italic, codeBg, codeFg)
                    pop()
                    i = close + 2
                    continue
                }
            }

            // 斜体（避开 ** / __）。`_` 只在两侧不紧邻字母数字时算斜体，
            // 否则 snake_case_name 与裸写的 a_b_c 会被拆成斜体吃掉下划线。
            if ((c == '*' || c == '_') && !(i + 1 < s.length && s[i + 1] == c)) {
                val prev = if (i == 0) ' ' else s[i - 1]
                val close = s.indexOf(c, i + 1)
                val next = if (close + 1 < s.length) s[close + 1] else ' '
                val guardOk = c != '_' || !(isWordChar(prev) || isWordChar(next))
                if (close > i + 1 && guardOk) {
                    val body = s.substring(i + 1, close)
                    if (body.none { it == '\n' } && !body.first().isWhitespace() && !body.last().isWhitespace()) {
                        flush()
                        inline(body, baseSp, bold, true, codeBg, codeFg)
                        i = close + 1
                        continue
                    }
                }
            }

            buf.append(c)
            i++
        }
        flush()
    }

    private fun inlineString(
        s: String,
        baseSp: Float,
        codeBg: Color,
        codeFg: Color
    ): AnnotatedString = buildAnnotatedString { inline(s, baseSp, codeBg = codeBg, codeFg = codeFg) }

    // ---------------- 渲染 ----------------

    /**
     * 渲染 Markdown 文本。
     *
     * @param text 原始文本（可能含 Markdown 与 `$公式$`）
     * @param style 正文基础样式，标题/列表都在它基础上派生
     * @param color 正文颜色
     */
    @Composable
    fun MarkdownText(
        text: String,
        modifier: Modifier = Modifier,
        style: TextStyle = MaterialTheme.typography.bodyMedium,
        color: Color = MaterialTheme.colorScheme.onSurface,
        tight: Boolean = false
    ) {
        val blocks = remember(text) { if (looksLikeMarkdown(text)) parse(text) else emptyList() }
        val baseSp = if (style.fontSize.isSp) style.fontSize.value else 14f

        // 无任何 Markdown 标记 → 退化处理，避免不必要的布局层级
        if (blocks.isEmpty()) {
            Latex.LatexText(text = text, modifier = modifier, style = style, color = color)
            return
        }

        val extra = shuaColors
        val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        val codeFg = extra.terracottaDeep
        val gap = if (tight) 5.dp else 8.dp

        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> baseSp * 1.34f
                            2 -> baseSp * 1.22f
                            3 -> baseSp * 1.12f
                            else -> baseSp * 1.04f
                        }
                        Text(
                            text = inlineString(block.text, size, codeBg, codeFg),
                            style = style.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Medium,
                                fontSize = size.sp,
                                lineHeight = (size * 1.45f).sp
                            ),
                            color = color
                        )
                    }

                    is MdBlock.Para -> {
                        if (block.displayMath) {
                            // block.text 是已经剥掉 `$$` 的公式体，直接交给行间渲染器
                            // （LatexText 只认带定界符的输入，传裸公式会被当普通文字打出来）
                            Latex.LatexView(
                                source = block.text,
                                modifier = Modifier.fillMaxWidth(),
                                style = style,
                                color = color
                            )
                        } else {
                            Text(
                                text = inlineString(block.text, baseSp, codeBg, codeFg),
                                style = style,
                                color = color
                            )
                        }
                    }

                    is MdBlock.Bullet -> {
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .widthIn(min = (16 + block.indent * 12).dp)
                                    .padding(top = (baseSp * 0.55f).dp)
                            ) {
                                if (block.ordered) {
                                    Text(
                                        "${block.number}.",
                                        style = style.copy(
                                            fontSize = (baseSp * 0.94f).sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = extra.terracottaDeep,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .padding(start = (6 + block.indent * 12).dp, end = 8.dp)
                                            .width(4.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(extra.terracotta)
                                    )
                                }
                            }
                            if (block.ordered) {
                                Spacer(Modifier.width((block.indent * 10).dp))
                            }
                            Text(
                                text = inlineString(block.text, baseSp, codeBg, codeFg),
                                style = style,
                                color = color,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.Quote -> {
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .padding(top = 2.dp, bottom = 2.dp)
                                    .width(3.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(extra.terracotta.copy(alpha = 0.45f))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = inlineString(block.text, baseSp, codeBg, codeFg),
                                style = style.copy(fontStyle = FontStyle.Italic),
                                color = extra.inkSoft,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.Code -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(codeBg)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = block.code,
                                style = style.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = (baseSp * 0.92f).sp,
                                    lineHeight = (baseSp * 1.5f).sp
                                ),
                                color = color,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                    }

                    MdBlock.Rule -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            thickness = 1.dp,
                            color = extra.creamLineStrong
                        )
                    }

                    is MdBlock.Table -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(codeBg.copy(alpha = 0.5f))
                                .padding(vertical = 4.dp)
                        ) {
                            TableRow(block.header, style, color, baseSp, header = true, codeBg = codeBg, codeFg = codeFg)
                            block.rows.forEachIndexed { idx, row ->
                                if (idx < block.rows.size) {
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = extra.creamLine.copy(alpha = 0.7f)
                                    )
                                }
                                TableRow(row, style, color, baseSp, header = false, codeBg = codeBg, codeFg = codeFg)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TableRow(
        cells: List<String>,
        style: TextStyle,
        color: Color,
        baseSp: Float,
        header: Boolean,
        codeBg: Color,
        codeFg: Color
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            cells.forEachIndexed { idx, cell ->
                if (idx > 0) Spacer(Modifier.width(10.dp))
                Text(
                    text = inlineString(cell, baseSp, codeBg, codeFg),
                    style = style.copy(fontSize = (baseSp * 0.94f).sp),
                    color = if (header) shuaColors.terracottaDeep else color,
                    fontWeight = if (header) FontWeight.SemiBold else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
