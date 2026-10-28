package com.examhelper.shua.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 LaTeX 渲染器（无第三方依赖，纯 Compose 实现）。
 *
 * 覆盖题库/解析里真正会出现的子集：
 * - 上下标 `x^2` `a_{n+1}` `10^{-3}`：用 baselineShift + 缩小字号真实排版
 * - 分式 `\frac{a}{b}`：行内渲染为 `a/b`（必要时加括号），独占一行时渲染为真正的上下堆叠
 * - 根式 `\sqrt{x}` `\sqrt[3]{x}`
 * - 常用运算符/希腊字母/箭头（见 [symbols]）
 * - `\text{}` `\mathrm{}` 直立体，未知命令原样输出（绝不吃字）
 *
 * 用法：把原来直接 `Text(q.stem)` 的地方换成 `LatexText(q.stem)` 即可；
 * 普通文本（不含 `$`）会自动退化为一个普通 Text，零额外开销。
 */
object Latex {

    private val symbols: Map<String, String> = mapOf(
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
        "ge" to "≥", "geq" to "≥", "le" to "≤", "leq" to "≤", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι",
        "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
        "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "leftrightarrow" to "↔", "degree" to "°", "circ" to "°",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "in" to "∈", "notin" to "∉",
        "subset" to "⊂", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
        "sum" to "∑", "int" to "∫", "partial" to "∂", "nabla" to "∇",
        "percent" to "%", "prime" to "′", "cdots" to "⋯", "ldots" to "…", "dots" to "…"
    )

    /** 把一段纯公式源码转成带上下标样式的 AnnotatedString */
    fun annotate(source: String, baseSp: Float, italic: Boolean = true): AnnotatedString =
        buildAnnotatedString { emit(Cursor(source), baseSp, italic) }

    // ---------- 解析 ----------

    private class Cursor(val s: String) {
        var i = 0
        fun eof(): Boolean = i >= s.length
        fun peek(): Char? = if (eof()) null else s[i]
        fun take(): Char = s[i++]
    }

    private fun AnnotatedString.Builder.emit(c: Cursor, baseSp: Float, italic: Boolean) {
        while (!c.eof()) {
            when (val ch = c.peek()!!) {
                '\\' -> {
                    c.take()
                    command(c, baseSp, italic)
                }
                '^' -> {
                    c.take()
                    script(c, up = true, baseSp = baseSp, italic = italic)
                }
                '_' -> {
                    c.take()
                    script(c, up = false, baseSp = baseSp, italic = italic)
                }
                '{' -> {
                    c.take()
                    emit(c, baseSp, italic)
                }
                '}' -> {
                    c.take()
                    return
                }
                else -> {
                    c.take()
                    if (italic && ch.isLetter() && ch.code < 128) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(ch) }
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.command(c: Cursor, baseSp: Float, italic: Boolean) {
        val name = buildString { while (!c.eof() && (c.peek()!!.isLetter())) append(c.take()) }
        if (name.isEmpty()) {
            // \, \; \{ \} \% \_ 之类的单字符转义
            if (c.eof()) return
            val raw = c.take()
            append(
                when (raw) {
                    '{' -> "{"
                    '}' -> "}"
                    ',' -> " "
                    ';' -> " "
                    '!' -> ""
                    '%' -> "%"
                    '_' -> "_"
                    '&' -> " "
                    else -> raw.toString()
                }
            )
            return
        }
        when (name) {
            "frac", "dfrac", "tfrac" -> {
                val a = plain(readGroup(c))
                val b = plain(readGroup(c))
                append(wrapOperand(a))
                append('/')
                append(wrapOperand(b))
            }
            "sqrt" -> {
                var index = ""
                if (c.peek() == '[') {
                    c.take()
                    val sb = StringBuilder()
                    while (!c.eof() && c.peek() != ']') sb.append(c.take())
                    if (!c.eof()) c.take()
                    index = plain(sb.toString())
                }
                val body = plain(readGroup(c))
                if (index.isNotEmpty()) append(index)
                append('√')
                append(if (body.length > 1) "($body)" else body)
            }
            "text", "mathrm", "mbox", "operatorname" -> append(plain(readGroup(c)))
            "left", "right" -> if (!c.eof()) append(c.take())
            else -> append(symbols[name] ?: name.ifEmpty { "" })
        }
    }

    private fun AnnotatedString.Builder.script(
        c: Cursor,
        up: Boolean,
        baseSp: Float,
        italic: Boolean
    ) {
        val body = readGroup(c)
        val small = (baseSp * 0.72f).coerceAtLeast(7f)
        // BaselineShift 的入参是相对字号的倍数，不是 TextUnit
        val shift = if (up) 0.32f else -0.12f
        withStyle(
            SpanStyle(
                fontSize = small.sp,
                baselineShift = androidx.compose.ui.text.style.BaselineShift(shift)
            )
        ) {
            append(annotate(body, small, italic))
        }
    }

    private fun readGroup(c: Cursor): String {
        if (c.peek() != '{') {
            if (c.eof()) return ""
            val ch = c.take()
            return if (ch == '\\') {
                buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
            } else ch.toString()
        }
        c.take()
        val sb = StringBuilder()
        var depth = 1
        while (!c.eof()) {
            val ch = c.take()
            when (ch) {
                '{' -> { depth++; sb.append(ch) }
                '}' -> { depth--; if (depth == 0) break else sb.append(ch) }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 取纯文字形态（用于分式上下两行的排版与括号判断） */
    private fun plain(src: String): String {
        val c = Cursor(src)
        val sb = StringBuilder()
        while (!c.eof()) {
            when (val ch = c.peek()!!) {
                '\\' -> {
                    c.take()
                    sb.append(commandPlain(c))
                }
                '{', '}' -> c.take()
                '^' -> { c.take(); sb.append(supPlain(readGroup(c))) }
                '_' -> { c.take(); sb.append(subPlain(readGroup(c))) }
                else -> { c.take(); sb.append(ch) }
            }
        }
        return sb.toString()
    }

    private fun commandPlain(c: Cursor): String {
        val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
        if (name.isEmpty()) return if (c.eof()) "" else c.take().toString()
        return when (name) {
            "frac", "dfrac", "tfrac" -> {
                val a = plain(readGroup(c))
                val b = plain(readGroup(c))
                "${wrapOperand(a)}/${wrapOperand(b)}"
            }
            "sqrt" -> "√" + plain(readGroup(c))
            "text", "mathrm", "mbox" -> plain(readGroup(c))
            "left", "right" -> if (c.eof()) "" else c.take().toString()
            else -> symbols[name] ?: name
        }
    }

    private val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        'n' to 'ⁿ', 'i' to 'ⁱ', '+' to '⁺', '-' to '⁻', 'x' to 'ˣ'
    )
    private val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', 'n' to 'ₙ', 'i' to 'ᵢ', 'k' to 'ₖ'
    )

    private fun supPlain(t: String): String =
        if (t.all { supMap.containsKey(it) }) t.map { supMap.getValue(it) }.joinToString("")
        else "^$t"

    private fun subPlain(t: String): String =
        if (t.all { subMap.containsKey(it) }) t.map { subMap.getValue(it) }.joinToString("")
        else "_$t"

    private fun wrapOperand(t: String): String =
        if (t.length > 1 && t.any { it == '+' || it == '-' || it == ' ' }) "($t)" else t

    // ---------- 切分 ----------

    /** 文本块：isMath=false 为普通文字，true 为公式（display 表示 `$$..$$`） */
    private data class Block(val text: String, val isMath: Boolean, val display: Boolean)

    private fun splitBlocks(source: String): List<Block> {
        if ('$' !in source) return listOf(Block(source, false, false))
        val out = mutableListOf<Block>()
        var i = 0
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                out += Block(plain.toString(), false, false)
                plain.clear()
            }
        }

        while (i < source.length) {
            val ch = source[i]
            if (ch == '\\' && i + 1 < source.length && source[i + 1] == '$') {
                plain.append('$')
                i += 2
                continue
            }
            if (ch != '$') {
                plain.append(ch)
                i++
                continue
            }
            val display = i + 1 < source.length && source[i + 1] == '$'
            val open = if (display) i + 2 else i + 1
            val closeToken = if (display) "$$" else "$"
            val close = source.indexOf(closeToken, open)
            if (close < 0) {
                // 未闭合：原样当文字，避免吞掉后半段
                plain.append(source.substring(i))
                i = source.length
                continue
            }
            flush()
            out += Block(source.substring(open, close), true, display)
            i = close + closeToken.length
        }
        flush()
        return out
    }

    // ---------- 渲染 ----------

    /**
     * 混排渲染：普通文字按 [style] 输出，行内公式内联，行间公式（`$$..$$`）单独成行居中。
     * 不含 `$` 的文本会退化为单个 Text（不额外测量、不额外重建）。
     */
    @Composable
    fun LatexText(
        text: String,
        modifier: Modifier = Modifier,
        style: TextStyle = MaterialTheme.typography.bodyLarge,
        color: Color = MaterialTheme.colorScheme.onSurface,
        mathFontFamily: FontFamily = FontFamily.Serif,
        maxLines: Int = Int.MAX_VALUE
    ) {
        val blocks = remember(text) { splitBlocks(text) }
        val baseSp = if (style.fontSize.isSp) style.fontSize.value else 15f

        if (blocks.size == 1 && !blocks[0].isMath) {
            Text(
                text = blocks[0].text,
                modifier = modifier,
                style = style,
                color = color,
                maxLines = maxLines
            )
            return
        }

        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { block ->
                if (!block.isMath) {
                    Text(
                        text = block.text,
                        style = style,
                        color = color,
                        maxLines = maxLines
                    )
                } else if (block.display) {
                    val frac = parseStandaloneFrac(block.text)
                    if (frac != null) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            DisplayFraction(
                                numerator = frac.first,
                                denominator = frac.second,
                                baseSp = baseSp,
                                style = style,
                                color = color,
                                fontFamily = mathFontFamily
                            )
                        }
                    } else {
                        Text(
                            text = annotate(block.text, baseSp * 1.05f),
                            style = style.copy(fontFamily = mathFontFamily),
                            color = color,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = annotate(block.text, baseSp),
                        style = style.copy(fontFamily = mathFontFamily),
                        color = color,
                        maxLines = maxLines
                    )
                }
            }
        }
    }

    /** `$$...$$` 恰好是一个 `\frac{..}{..}` 时返回分子分母 */
    private fun parseStandaloneFrac(src: String): Pair<String, String>? {
        val t = src.trim()
        val m = Regex("""^\\[dt]?frac\s*\{""").find(t) ?: return null
        val c = Cursor(t)
        c.i = m.range.last
        val a = readGroup(c)
        if (c.eof()) return null
        val b = readGroup(c)
        if (!c.eof()) return null
        return plain(a) to plain(b)
    }

    @Composable
    private fun DisplayFraction(
        numerator: String,
        denominator: String,
        baseSp: Float,
        style: TextStyle,
        color: Color,
        fontFamily: FontFamily
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 40.dp)
        ) {
            Text(
                text = numerator,
                style = style.copy(fontFamily = fontFamily, fontSize = (baseSp * 1.05f).sp),
                color = color,
                textAlign = TextAlign.Center
            )
            Box(
                Modifier
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color.copy(alpha = 0.55f))
            )
            Text(
                text = denominator,
                style = style.copy(fontFamily = fontFamily, fontSize = (baseSp * 1.05f).sp),
                color = color,
                textAlign = TextAlign.Center
            )
        }
    }
}
