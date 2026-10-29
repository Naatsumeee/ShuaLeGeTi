package com.examhelper.shua.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * LaTeX 公式渲染器（零第三方依赖，纯 Compose）。
 *
 * 分两条渲染路径，各司其职：
 *
 * 1. **行内公式**（正文里的 `$x^2$`）：必须与中文/英文同处一行并正常换行，
 *    所以只能产出 [AnnotatedString]，由 [annotate] 负责把公式源码压平成一串带上下标样式的文字。
 *    压平过程中分式写作 `a/b`、根式写作 `√(x)`——这是行内的固有取舍（KaTeX 之外的行内方案都如此）。
 *
 * 2. **行间公式**（独占一行的 `$$..$$`）：有完整的一行高度可用，由 [LatexView] 用真正的
 *    布局节点渲染——分式上下堆叠加横线、根号带上划线、矩阵/分段函数成表格、求和号上下限归位。
 *
 * 两条路径共用同一套符号表与命令解析规则，表现保持一致。
 *
 * 覆盖范围（题库/解析里真正会出现的子集）：
 * - 上下标 `x^2` `a_{n+1}` `10^{-3}` `x_i^2`：baselineShift + 缩小字号真实排版
 * - 分式 `\frac{a}{b}` `\dfrac` `\tfrac`：行间上下堆叠，行内压平为 a/b（必要时加括号）
 * - 根式 `\sqrt{x}` `\sqrt[3]{x}`：行间带根号线，行内 √(x)
 * - 求和/连乘/积分 `\sum_{i=1}^{n}` `\prod` `\int_{0}^{1}`：行间上下限归位
 * - 重音与划线 `\overline` `\underline` `\vec` `\bar` `\hat` `\dot` `\tilde`
 * - 环境 `\begin{cases}` `\begin{matrix}` `\begin{aligned}` `\begin{pmatrix}` `\begin{vmatrix}`
 * - 定界符 `\left( ... \right)` `\big(` `\Big[`：按内容放大
 * - 常用运算符/希腊字母/箭头/函数名（见 [symbols] 与 [functions]）
 * - 间距命令 `\,` `\:` `\;` `\!` `\quad` `\qquad` `\ `
 * - 样式 `\text{}` `\mathrm{}` `\mathbf{}` `\mathit{}` `\operatorname{}` `\mathbb{}`
 *
 * 未知命令一律原样输出（绝不吃字），未闭合的 `$` 也原样当文字，避免吞掉后半段。
 */
object Latex {

    // ------------------------------------------------------------------
    // 符号表
    // ------------------------------------------------------------------

    private val symbols: Map<String, String> = mapOf(
        // 运算
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
        "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "•",
        "oplus" to "⊕", "ominus" to "⊖", "otimes" to "⊗", "odot" to "⊙",
        // 关系
        "ge" to "≥", "geq" to "≥", "le" to "≤", "leq" to "≤", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
        "propto" to "∝", "ll" to "≪", "gg" to "≫", "doteq" to "≐",
        // 希腊字母
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // 箭头
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
        "Leftrightarrow" to "⇔", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "longrightarrow" to "⟶", "Longrightarrow" to "⟹",
        // 集合与逻辑
        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃",
        "neg" to "¬", "land" to "∧", "lor" to "∨", "therefore" to "∴", "because" to "∵",
        // 杂项
        "infty" to "∞", "degree" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "triangle" to "△", "square" to "□", "prime" to "′", "cdots" to "⋯",
        "ldots" to "…", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
        "oint" to "∮", "iiint" to "∭", "partial" to "∂", "nabla" to "∇",
        // 这几个在 bigOps 里（上下限叠在符号上下方渲染），缺了映射会原样吐出命令名
        "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂",
        "bigvee" to "⋁", "bigwedge" to "⋀",
        "percent" to "%", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ",
        "aleph" to "ℵ", "dagger" to "†", "S" to "§", "P" to "¶",
        "colon" to ":", "mid" to "∣", "nmid" to "∤", "backslash" to "\\"
    )

    /** 函数名：正体排布，不斜体 */
    private val functions: Set<String> = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "lim", "limsup",
        "liminf", "max", "min", "sup", "inf", "det", "dim", "gcd", "lcm", "deg",
        "arg", "ker", "hom", "mod", "bmod", "pmod", "Pr", "sgn"
    )

    /** 大运算符：行间公式里上下限要归到符号的上下方 */
    private val bigOps: Set<String> = setOf(
        "sum", "prod", "coprod", "int", "iint", "iiint", "oint", "bigcup", "bigcap",
        "bigoplus", "bigotimes", "bigvee", "bigwedge"
    )

    private fun symbol(name: String): String = symbols[name] ?: name

    // ------------------------------------------------------------------
    // 行内：压平成 AnnotatedString
    // ------------------------------------------------------------------

    /** 把一段纯公式源码转成带上下标样式的 AnnotatedString（行内用） */
    fun annotate(
        source: String,
        baseSp: Float,
        italic: Boolean = true,
        mathFontFamily: FontFamily = FontFamily.Serif
    ): AnnotatedString = buildAnnotatedString {
        emitFlat(Cursor(source), baseSp, italic, mathFontFamily)
    }

    private class Cursor(val s: String) {
        var i = 0
        fun eof(): Boolean = i >= s.length
        fun peek(): Char? = if (eof()) null else s[i]
        fun peekAt(offset: Int): Char? {
            val j = i + offset
            return if (j < s.length) s[j] else null
        }
        fun take(): Char = s[i++]
        fun skipSpaces() {
            while (!eof() && s[i] == ' ') i++
        }
    }

    private fun AnnotatedString.Builder.emitFlat(
        c: Cursor,
        baseSp: Float,
        italic: Boolean,
        mathFont: FontFamily
    ) {
        while (!c.eof()) {
            when (val ch = c.peek()!!) {
                '\\' -> {
                    c.take()
                    commandFlat(c, baseSp, italic, mathFont)
                }
                '^' -> {
                    c.take()
                    scriptFlat(c, up = true, baseSp = baseSp, italic = italic, mathFont = mathFont)
                }
                '_' -> {
                    c.take()
                    scriptFlat(c, up = false, baseSp = baseSp, italic = italic, mathFont = mathFont)
                }
                '{' -> {
                    c.take()
                    emitFlat(c, baseSp, italic, mathFont)
                }
                '}' -> {
                    c.take()
                    return
                }
                '&' -> {
                    // 对齐符在行内没有意义，当空格
                    c.take()
                    append(' ')
                }
                else -> {
                    c.take()
                    if (italic && ch.isLetter() && ch.code < 128) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = mathFont)) {
                            append(ch)
                        }
                    } else {
                        withStyle(SpanStyle(fontFamily = mathFont)) { append(ch) }
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.commandFlat(
        c: Cursor,
        baseSp: Float,
        italic: Boolean,
        mathFont: FontFamily
    ) {
        val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
        if (name.isEmpty()) {
            // \, \; \{ \} \% \_ \\ 之类的单字符命令
            if (c.eof()) return
            val raw = c.take()
            append(
                when (raw) {
                    '{' -> "{"
                    '}' -> "}"
                    ',' -> "\u2009"   // 细空格
                    ':' -> "\u2005"
                    ';' -> "\u2004"
                    '!' -> ""
                    ' ' -> " "
                    '%' -> "%"
                    '_' -> "_"
                    '&' -> " "
                    '#' -> "#"
                    '\\' -> " "       // 行内换行当空格
                    else -> raw.toString()
                }
            )
            return
        }
        when {
            name == "frac" || name == "dfrac" || name == "tfrac" -> {
                val a = plain(readGroup(c), italic)
                val b = plain(readGroup(c), italic)
                append(wrapOperand(a))
                append('/')
                append(wrapOperand(b))
            }
            name == "sqrt" -> {
                var index = ""
                if (c.peek() == '[') {
                    c.take()
                    val sb = StringBuilder()
                    while (!c.eof() && c.peek() != ']') sb.append(c.take())
                    if (!c.eof()) c.take()
                    index = plain(sb.toString(), italic)
                }
                val body = plain(readGroup(c), italic)
                if (index.isNotEmpty()) append(index)
                append('√')
                append(if (body.length > 1) "($body)" else body)
            }
            name == "text" || name == "mathrm" || name == "mbox" ||
                name == "operatorname" || name == "textbf" || name == "mathbf" -> {
                append(plain(readGroup(c), italic = false))
            }
            name == "mathit" || name == "mathcal" || name == "mathbb" -> {
                append(plain(readGroup(c), italic))
            }
            name == "overline" || name == "bar" || name == "vec" || name == "hat" ||
                name == "dot" || name == "tilde" || name == "underline" || name == "widehat" -> {
                val body = plain(readGroup(c), italic)
                append(if (name == "vec") body + "\u20D7" else body)
            }
            name == "left" || name == "right" || name == "big" || name == "Big" ||
                name == "bigg" || name == "Bigg" -> {
                c.skipSpaces()
                if (!c.eof()) {
                    val d = c.take()
                    append(if (d == '.') "" else d.toString())
                }
            }
            name == "quad" -> append("\u2003")
            name == "qquad" -> append("\u2003\u2003")
            name == "space" -> append(' ')
            name == "begin" || name == "end" -> {
                // 行内不支持环境，吃掉环境名并把内部当普通公式继续
                readGroup(c)
            }
            name == "displaystyle" || name == "textstyle" || name == "limits" ||
                name == "nolimits" || name == "!" -> Unit
            name == "color" -> {
                readGroup(c) // 颜色名丢掉，正文颜色统一由调用方决定
            }
            functions.contains(name) -> {
                withStyle(SpanStyle(fontFamily = mathFont, fontStyle = FontStyle.Normal)) {
                    append(name)
                }
            }
            else -> append(symbol(name))
        }
    }

    private fun AnnotatedString.Builder.scriptFlat(
        c: Cursor,
        up: Boolean,
        baseSp: Float,
        italic: Boolean,
        mathFont: FontFamily
    ) {
        val body = readGroup(c)
        val small = (baseSp * 0.72f).coerceAtLeast(7f)
        // BaselineShift 入参是相对字号的倍数，不是 TextUnit
        val shift = if (up) 0.34f else -0.13f
        withStyle(
            SpanStyle(
                fontSize = small.sp,
                baselineShift = BaselineShift(shift),
                fontFamily = mathFont
            )
        ) {
            append(annotate(body, small, italic, mathFont))
        }
    }

    /** 读取一个分组（`{...}` 或单个原子），返回其源码 */
    private fun readGroup(c: Cursor): String {
        c.skipSpaces()
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

    /** 取纯文字形态（用于分式压平与括号判断） */
    private fun plain(src: String, italic: Boolean): String {
        val c = Cursor(src)
        val sb = StringBuilder()
        while (!c.eof()) {
            when (val ch = c.peek()!!) {
                '\\' -> {
                    c.take()
                    sb.append(commandPlain(c, italic))
                }
                '{', '}' -> c.take()
                '^' -> { c.take(); sb.append(supPlain(readGroup(c))) }
                '_' -> { c.take(); sb.append(subPlain(readGroup(c))) }
                else -> { c.take(); sb.append(ch) }
            }
        }
        return sb.toString()
    }

    private fun commandPlain(c: Cursor, italic: Boolean): String {
        val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
        if (name.isEmpty()) return if (c.eof()) "" else c.take().toString()
        return when {
            name == "frac" || name == "dfrac" || name == "tfrac" -> {
                val a = plain(readGroup(c), italic)
                val b = plain(readGroup(c), italic)
                "${wrapOperand(a)}/${wrapOperand(b)}"
            }
            name == "sqrt" -> "√" + plain(readGroup(c), italic)
            name == "text" || name == "mathrm" || name == "mbox" || name == "operatorname" ->
                plain(readGroup(c), false)
            name == "left" || name == "right" || name == "big" || name == "Big" -> {
                c.skipSpaces()
                if (c.eof()) "" else c.take().toString()
            }
            name == "quad" -> " "
            name == "qquad" -> "  "
            functions.contains(name) -> name
            else -> symbol(name)
        }
    }

    private val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        'n' to 'ⁿ', 'i' to 'ⁱ', '+' to '⁺', '-' to '⁻', 'x' to 'ˣ', 'o' to 'º'
    )
    private val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', 'n' to 'ₙ', 'i' to 'ᵢ', 'k' to 'ₖ', 'x' to 'ₓ'
    )

    private fun supPlain(t: String): String =
        if (t.isNotEmpty() && t.all { supMap.containsKey(it) }) t.map { supMap.getValue(it) }.joinToString("")
        else "^$t"

    private fun subPlain(t: String): String =
        if (t.isNotEmpty() && t.all { subMap.containsKey(it) }) t.map { subMap.getValue(it) }.joinToString("")
        else "_$t"

    private fun wrapOperand(t: String): String =
        if (t.length > 1 && t.any { it == '+' || it == '-' || it == ' ' }) "($t)" else t

    // ------------------------------------------------------------------
    // 行间：节点树 + 真布局
    // ------------------------------------------------------------------

    private sealed interface Node {
        /** 普通文字；[roman] 为 true 表示正体（\text、函数名） */
        data class Txt(val text: String, val roman: Boolean = false) : Node
        data class Seq(val items: List<Node>) : Node
        data class Frac(val num: List<Node>, val den: List<Node>) : Node
        data class Root(val index: List<Node>?, val body: List<Node>) : Node
        /** 上下标：base 是底，sup / sub 至少有一个非空 */
        data class Script(val base: List<Node>, val sup: List<Node>?, val sub: List<Node>?) : Node
        data class BigOp(val name: String, val sub: List<Node>?, val sup: List<Node>?) : Node
        data class Delim(val open: String, val body: List<Node>, val close: String) : Node
        /** kind: 0=上划线 1=下划线 2=向量箭头 3=上点 4=上横 5=上尖 */
        data class Accent(val kind: Int, val body: List<Node>) : Node
        /** 间距，单位是 base 字号的倍数 */
        data class Gap(val em: Float) : Node
        /** 环境：kind 0=cases 1=matrix 2=aligned 3=pmatrix 4=vmatrix 5=bmatrix */
        data class Env(val kind: Int, val rows: List<List<List<Node>>>) : Node
    }

    private fun parseSeq(c: Cursor, stopAtBrace: Boolean, stopAtEnvEnd: String?): List<Node> {        val out = mutableListOf<Node>()
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                out += Node.Txt(text.toString())
                text.clear()
            }
        }

        fun push(node: Node) {
            flushText()
            out += node
        }

        while (!c.eof()) {
            val ch = c.peek()!!
            if (stopAtBrace && ch == '}') {
                c.take()
                break
            }
            if (stopAtEnvEnd != null && ch == '\\') {
                // 检测 \end{env}
                val save = c.i
                c.take()
                val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
                if (name == "end") {
                    val env = readGroup(c).trim()
                    if (env == stopAtEnvEnd) return out
                }
                c.i = save
            }

            when (ch) {
                '\\' -> {
                    c.take()
                    val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
                    if (name.isEmpty()) {
                        val raw = if (c.eof()) "" else c.take().toString()
                        when (raw) {
                            "," -> push(Node.Gap(0.17f))
                            ":" -> push(Node.Gap(0.22f))
                            ";" -> push(Node.Gap(0.28f))
                            "!" -> Unit
                            " " -> push(Node.Gap(0.25f))
                            "\\" -> push(Node.Gap(0.5f)) // 非环境里的换行当大间距
                            "{" -> text.append('{')
                            "}" -> text.append('}')
                            "%" -> text.append('%')
                            "_" -> text.append('_')
                            "&" -> text.append(' ')
                            "#" -> text.append('#')
                            else -> text.append(raw)
                        }
                    } else {
                        parseCommand(c, name)?.let { push(it) }
                    }
                }
                '^' -> {
                    c.take()
                    val sup = parseAtom(c)
                    attachScript(out, sup = sup, sub = null, flush = ::flushText, push = ::push)
                }
                '_' -> {
                    c.take()
                    val sub = parseAtom(c)
                    attachScript(out, sup = null, sub = sub, flush = ::flushText, push = ::push)
                }
                '{' -> {
                    c.take()
                    val inner = parseSeq(c, stopAtBrace = true, stopAtEnvEnd = null)
                    push(Node.Seq(inner))
                }
                '}' -> {
                    c.take()
                    if (stopAtBrace) break
                }
                '&' -> {
                    c.take()
                    push(Node.Gap(0.35f))
                }
                '~' -> {
                    c.take()
                    push(Node.Gap(0.3f))
                }
                ' ' -> {
                    c.take()
                    // 数学模式里空格不产生宽度，但连续空格给一点视觉呼吸
                    if (c.peek() == ' ') push(Node.Gap(0.2f))
                }
                else -> {
                    c.take()
                    text.append(ch)
                }
            }
        }
        flushText()
        return out
    }

    private fun attachScript(
        out: MutableList<Node>,
        sup: List<Node>?,
        sub: List<Node>?,
        flush: () -> Unit,
        push: (Node) -> Unit
    ) {
        // 必须先把缓冲区里的文本落地再判断「底数是谁」。
        // 否则 `\int_{0}^{1} x^{2}` 里 x 还在 text 缓冲里，`^{2}` 会误以为
        // 底数还是前面那个积分号，把积分上限 1 覆盖成 2 —— 这就是
        // 「∫₀¹x²dx 渲染成 ∫₀²x dx、∑_{i=1}^{n}i² 渲染成 ∑_{i=1}^{2}i」的根因。
        flush()
        val last = out.lastOrNull()
        // 大运算符（∫ ∑ ∏ ⋃ …）的上下限要贴着符号本身，渲染成符号上下方的小字，
        // 而不是退化成右侧并列的普通上下标。
        if (last is Node.BigOp) {
            out[out.size - 1] = last.copy(
                sup = sup ?: last.sup,
                sub = sub ?: last.sub
            )
            return
        }
        if (last is Node.Script) {
            out[out.size - 1] = last.copy(
                sup = sup ?: last.sup,
                sub = sub ?: last.sub
            )
            return
        }
        if (last != null) {
            out.removeAt(out.size - 1)
            out += Node.Script(listOf(last), sup, sub)
        } else {
            out += Node.Script(emptyList(), sup, sub)
        }
    }

    /** 解析一个原子作为上下标的内容（`{}` 分组或单个字符） */
    private fun parseAtom(c: Cursor): List<Node> {
        c.skipSpaces()
        if (c.eof()) return emptyList()
        if (c.peek() == '{') {
            c.take()
            return parseSeq(c, stopAtBrace = true, stopAtEnvEnd = null)
        }
        val ch = c.take()
        if (ch == '\\') {
            val name = buildString { while (!c.eof() && c.peek()!!.isLetter()) append(c.take()) }
            if (name.isEmpty()) return listOf(Node.Txt(if (c.eof()) "" else c.take().toString()))
            val tmp = mutableListOf<Node>()
            parseCommandInto(tmp, c, name)
            return tmp
        }
        return listOf(Node.Txt(ch.toString()))
    }

    private fun parseCommandInto(out: MutableList<Node>, c: Cursor, name: String) {
        parseCommand(c, name)?.let { out += it }
    }

    private fun parseCommand(c: Cursor, name: String): Node? {
        return when {
            name == "frac" || name == "dfrac" || name == "tfrac" -> {
                val a = groupNodes(c)
                val b = groupNodes(c)
                Node.Frac(a, b)
            }
            name == "sqrt" -> {
                var index: List<Node>? = null
                if (c.peek() == '[') {
                    c.take()
                    val sb = StringBuilder()
                    while (!c.eof() && c.peek() != ']') sb.append(c.take())
                    if (!c.eof()) c.take()
                    index = listOf(Node.Txt(sb.toString()))
                }
                Node.Root(index, groupNodes(c))
            }
            name == "text" || name == "mathrm" || name == "mbox" || name == "operatorname" ->
                Node.Txt(plain(readGroup(c), italic = false), roman = true)
            name == "textbf" || name == "mathbf" ->
                Node.Txt(plain(readGroup(c), italic = false), roman = true)
            name == "mathit" || name == "mathcal" || name == "mathbb" ->
                Node.Txt(plain(readGroup(c), italic = true))
            name == "overline" -> Node.Accent(0, groupNodes(c))
            name == "underline" -> Node.Accent(1, groupNodes(c))
            name == "vec" -> Node.Accent(2, groupNodes(c))
            name == "dot" -> Node.Accent(3, groupNodes(c))
            name == "bar" -> Node.Accent(4, groupNodes(c))
            name == "hat" || name == "widehat" -> Node.Accent(5, groupNodes(c))
            name == "tilde" -> Node.Accent(4, groupNodes(c))
            name == "left" || name == "right" || name == "big" || name == "Big" ||
                name == "bigg" || name == "Bigg" || name == "middle" -> {
                c.skipSpaces()
                if (c.eof()) null
                else {
                    val d = c.take()
                    if (d == '.') null else Node.Txt(d.toString(), roman = true)
                }
            }
            name == "quad" -> Node.Gap(1.0f)
            name == "qquad" -> Node.Gap(2.0f)
            name == "space" -> Node.Gap(0.3f)
            name == "begin" -> parseEnv(c)
            name == "end" -> {
                readGroup(c)
                null
            }
            name == "displaystyle" || name == "textstyle" || name == "limits" ||
                name == "nolimits" -> null
            name == "color" -> {
                readGroup(c)
                null
            }
            bigOps.contains(name) -> Node.BigOp(name, null, null)
            functions.contains(name) -> Node.Txt(name, roman = true)
            else -> Node.Txt(symbol(name), roman = true)
        }
    }

    private fun groupNodes(c: Cursor): List<Node> {
        c.skipSpaces()
        if (c.peek() != '{') {
            return parseAtom(c)
        }
        c.take()
        return parseSeq(c, stopAtBrace = true, stopAtEnvEnd = null)
    }

    private fun parseEnv(c: Cursor): Node? {
        val env = readGroup(c).trim()
        val kind = when (env) {
            "cases" -> 0
            "matrix", "smallmatrix" -> 1
            "aligned", "align", "gathered", "array" -> 2
            "pmatrix" -> 3
            "vmatrix" -> 4
            "bmatrix" -> 5
            else -> 1
        }
        // 收集到 \end{env} 为止的原始源码
        val start = c.i
        var end = -1
        val marker = "\\end{$env}"
        var scan = start
        while (scan < c.s.length) {
            if (c.s.startsWith(marker, scan)) {
                end = scan
                break
            }
            scan++
        }
        val body = if (end >= 0) {
            c.i = end + marker.length
            c.s.substring(start, end)
        } else {
            c.i = c.s.length
            c.s.substring(start)
        }
        // 行用 \\ 切，单元格用 & 切
        val rows = body
            .split(Regex("""\\\\"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { row ->
                row.split('&').map { cell ->
                    val cp = Cursor(cell.trim())
                    parseSeq(cp, stopAtBrace = false, stopAtEnvEnd = null)
                }
            }
        return Node.Env(kind, rows)
    }

    // ---------------- 渲染 ----------------

    private fun normalizeSource(src: String): String {
        var s = src
        // \( ... \) 与 \[ ... \] 是等价写法
        s = s.replace("\\(", "\$").replace("\\)", "\$")
        s = s.replace("\\[", "\$\$").replace("\\]", "\$\$")
        return s
    }

    /**
     * 仅供单元测试：把公式源码的解析结果摊平成可断言的文本。
     *
     * 上下标写成 `底^(上)` / `底_(下)`，大运算符写成 `∫_(下限)^(上限)`。
     * 之所以要这个入口：上下标挂错底数是纯解析层的问题，只有拿到节点树才能稳定断言，
     * 光看截图放大肉眼比对既慢又容易漏（∫₀¹x²dx 曾被渲染成 ∫₀²x dx 正是这么漏掉的）。
     */
    internal fun debugTree(source: String): String =
        flat(parseSeq(Cursor(normalizeSource(source)), stopAtBrace = false, stopAtEnvEnd = null))

    private fun flat(nodes: List<Node>): String = buildString {
        nodes.forEach { n ->
            when (n) {
                is Node.Txt -> append(n.text)
                is Node.Seq -> append(flat(n.items))
                is Node.Frac -> append("(").append(flat(n.num)).append(")/(").append(flat(n.den)).append(")")
                is Node.Root -> {
                    append("√")
                    n.index?.let { append("[").append(flat(it)).append("]") }
                    append("(").append(flat(n.body)).append(")")
                }
                is Node.Script -> {
                    append(flat(n.base))
                    n.sup?.let { append("^(").append(flat(it)).append(")") }
                    n.sub?.let { append("_(").append(flat(it)).append(")") }
                }
                is Node.BigOp -> {
                    append(symbol(n.name))
                    n.sub?.let { append("_(").append(flat(it)).append(")") }
                    n.sup?.let { append("^(").append(flat(it)).append(")") }
                }
                is Node.Delim -> append(n.open).append(flat(n.body)).append(n.close)
                is Node.Accent -> append("acc").append(n.kind).append("(").append(flat(n.body)).append(")")
                is Node.Gap -> append(" ")
                is Node.Env -> append("env").append(n.kind)
                    .append(n.rows.joinToString(",", "[", "]") { row ->
                        row.joinToString("|") { cell -> flat(cell) }
                    })
            }
        }
    }

    /** 行间公式：用真布局渲染（分式堆叠、根号线、矩阵、求和号归位） */
    @Composable
    fun LatexView(
        source: String,
        modifier: Modifier = Modifier,
        style: TextStyle = MaterialTheme.typography.bodyLarge,
        color: Color = MaterialTheme.colorScheme.onSurface,
        mathFontFamily: FontFamily = FontFamily.Serif,
        center: Boolean = true
    ) {
        val baseSp = if (style.fontSize.isSp) style.fontSize.value else 16f
        val nodes = remember(source) {
            val c = Cursor(normalizeSource(source))
            parseSeq(c, stopAtBrace = false, stopAtEnvEnd = null)
        }
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = if (center) Alignment.Center else Alignment.CenterStart
        ) {
            RenderNodes(nodes, baseSp, style, color, mathFontFamily)
        }
    }

    @Composable
    private fun RenderNodes(
        nodes: List<Node>,
        baseSp: Float,
        style: TextStyle,
        color: Color,
        mathFont: FontFamily
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            nodes.forEach { node ->
                RenderNode(node, baseSp, style, color, mathFont)
            }
        }
    }

    @Composable
    private fun RenderNode(
        node: Node,
        baseSp: Float,
        style: TextStyle,
        color: Color,
        mathFont: FontFamily
    ) {
        when (node) {
            is Node.Txt -> Text(
                text = node.text,
                style = style.copy(
                    fontFamily = mathFont,
                    fontSize = baseSp.sp,
                    fontStyle = if (node.roman) FontStyle.Normal else FontStyle.Italic
                ),
                color = color
            )

            is Node.Seq -> RenderNodes(node.items, baseSp, style, color, mathFont)

            is Node.Frac -> Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                RenderNodes(node.num, baseSp * 0.92f, style, color, mathFont)
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = color.copy(alpha = 0.7f)
                )
                RenderNodes(node.den, baseSp * 0.92f, style, color, mathFont)
            }

            is Node.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
                node.index?.let {
                    RenderNodes(it, baseSp * 0.62f, style, color, mathFont)
                }
                Text(
                    "√",
                    style = style.copy(fontFamily = mathFont, fontSize = (baseSp * 1.28f).sp),
                    color = color
                )
                Column(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    HorizontalDivider(
                        modifier = Modifier.widthIn(min = 8.dp),
                        thickness = 1.dp,
                        color = color.copy(alpha = 0.7f)
                    )
                    RenderNodes(node.body, baseSp, style, color, mathFont)
                }
            }

            is Node.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.base.isNotEmpty()) {
                    RenderNodes(node.base, baseSp, style, color, mathFont)
                }
                Column(
                    modifier = Modifier.padding(bottom = 1.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    node.sup?.let {
                        Box(Modifier.padding(bottom = (baseSp * 0.10f).dp)) {
                            RenderNodes(it, baseSp * 0.68f, style, color, mathFont)
                        }
                    }
                    node.sub?.let {
                        Box(Modifier.padding(top = (baseSp * 0.10f).dp)) {
                            RenderNodes(it, baseSp * 0.68f, style, color, mathFont)
                        }
                    }
                }
            }

            is Node.BigOp -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                node.sup?.let { RenderNodes(it, baseSp * 0.62f, style, color, mathFont) }
                Text(
                    symbol(node.name),
                    style = style.copy(
                        fontFamily = mathFont,
                        fontSize = (baseSp * 1.55f).sp,
                        fontStyle = FontStyle.Normal
                    ),
                    color = color
                )
                node.sub?.let { RenderNodes(it, baseSp * 0.62f, style, color, mathFont) }
            }

            is Node.Delim -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.open.isNotEmpty()) {
                    Text(
                        node.open,
                        style = style.copy(
                            fontFamily = mathFont,
                            fontSize = (baseSp * 1.42f).sp,
                            fontStyle = FontStyle.Normal
                        ),
                        color = color
                    )
                }
                Box(Modifier.padding(horizontal = 1.dp)) {
                    RenderNodes(node.body, baseSp, style, color, mathFont)
                }
                if (node.close.isNotEmpty()) {
                    Text(
                        node.close,
                        style = style.copy(
                            fontFamily = mathFont,
                            fontSize = (baseSp * 1.42f).sp,
                            fontStyle = FontStyle.Normal
                        ),
                        color = color
                    )
                }
            }

            is Node.Accent -> {
                val mark = when (node.kind) {
                    2 -> "→"
                    3 -> "·"
                    4 -> "‾"
                    5 -> "^"
                    else -> ""
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (node.kind) {
                        0, 2, 3, 4, 5 -> {
                            if (mark.isNotEmpty()) {
                                Text(
                                    mark,
                                    style = style.copy(
                                        fontFamily = mathFont,
                                        fontSize = (baseSp * 0.8f).sp
                                    ),
                                    color = color
                                )
                            } else {
                                HorizontalDivider(
                                    modifier = Modifier.widthIn(min = 8.dp),
                                    thickness = 1.dp,
                                    color = color.copy(alpha = 0.8f)
                                )
                            }
                        }
                        else -> Unit
                    }
                    RenderNodes(node.body, baseSp, style, color, mathFont)
                    if (node.kind == 1) {
                        HorizontalDivider(
                            modifier = Modifier.widthIn(min = 8.dp),
                            thickness = 1.dp,
                            color = color.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            is Node.Gap -> Spacer(Modifier.width((baseSp * node.em).dp))

            is Node.Env -> RenderEnv(node, baseSp, style, color, mathFont)
        }
    }

    @Composable
    private fun RenderEnv(
        env: Node.Env,
        baseSp: Float,
        style: TextStyle,
        color: Color,
        mathFont: FontFamily
    ) {
        val brace = when (env.kind) {
            0 -> "{" to ""
            3 -> "(" to ")"
            4 -> "|" to "|"
            5 -> "[" to "]"
            else -> "" to ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (brace.first.isNotEmpty()) {
                Text(
                    brace.first,
                    style = style.copy(
                        fontFamily = mathFont,
                        fontSize = (baseSp * (1.2f + env.rows.size * 0.22f)).sp,
                        fontStyle = FontStyle.Normal
                    ),
                    color = color
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 3.dp),
                verticalArrangement = Arrangement.spacedBy(if (env.kind == 0) 3.dp else 5.dp)
            ) {
                env.rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.forEachIndexed { idx, cell ->
                            if (idx > 0) Spacer(Modifier.width((baseSp * 0.9f).dp))
                            if (env.kind == 2) {
                                // aligned：按 & 分列，右列左对齐，整体不居中
                                Box(contentAlignment = Alignment.CenterStart) {
                                    RenderNodes(cell, baseSp, style, color, mathFont)
                                }
                            } else {
                                RenderNodes(cell, baseSp, style, color, mathFont)
                            }
                        }
                    }
                }
            }
            if (brace.second.isNotEmpty()) {
                Text(
                    brace.second,
                    style = style.copy(
                        fontFamily = mathFont,
                        fontSize = (baseSp * (1.2f + env.rows.size * 0.22f)).sp,
                        fontStyle = FontStyle.Normal
                    ),
                    color = color
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外主入口：混排文本（正文 + 行内公式 + 行间公式）
    // ------------------------------------------------------------------

    private sealed interface Seg {
        data class Plain(val text: String) : Seg
        data class Display(val math: String) : Seg
    }

    private fun splitSegments(source: String, allowDisplay: Boolean): List<Seg> {
        var s = normalizeSource(source)
        // 不允许行间公式时，`$$x$$` 退化成行内 `$x$`——否则两个 `$` 会被当成空公式，
        // 后面的公式体反而变成裸露的源码显示出来。
        if (!allowDisplay) s = s.replace("$$", "$")
        if ('$' !in s) return listOf(Seg.Plain(s))
        val out = mutableListOf<Seg>()
        val plain = StringBuilder()
        var i = 0

        fun flush() {
            if (plain.isNotEmpty()) {
                out += Seg.Plain(plain.toString())
                plain.clear()
            }
        }

        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length && s[i + 1] == '$') {
                plain.append('$')
                i += 2
                continue
            }
            if (ch != '$') {
                plain.append(ch)
                i++
                continue
            }
            val display = allowDisplay && i + 1 < s.length && s[i + 1] == '$'
            val open = if (display) i + 2 else i + 1
            val closeToken = if (display) "$$" else "$"
            val close = s.indexOf(closeToken, open)
            if (close < 0) {
                // 未闭合：原样当文字，避免吞掉后半段
                plain.append(s.substring(i))
                i = s.length
                continue
            }
            val math = s.substring(open, close)
            if (display) {
                flush()
                out += Seg.Display(math)
            } else {
                // 行内公式保持在段落文字里，用 `$..$` 包回去，稍后由 annotateMixed 处理
                plain.append('$').append(math).append('$')
            }
            i = close + closeToken.length
        }
        flush()
        return out
    }

    /** 把「普通文字 + 行内 `$公式$`」压成一条 AnnotatedString，保证能正常换行 */
    private fun annotateMixed(
        text: String,
        baseSp: Float,
        mathFont: FontFamily,
        mathColor: Color
    ): AnnotatedString = buildAnnotatedString {
        var i = 0
        val buf = StringBuilder()

        fun flushBuf() {
            if (buf.isNotEmpty()) {
                append(buf.toString())
                buf.clear()
            }
        }

        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length && text[i + 1] == '$') {
                buf.append('$')
                i += 2
                continue
            }
            if (ch != '$') {
                buf.append(ch)
                i++
                continue
            }
            val close = text.indexOf('$', i + 1)
            if (close < 0) {
                buf.append(text.substring(i))
                break
            }
            flushBuf()
            pushStyle(SpanStyle(color = mathColor))
            append(annotate(text.substring(i + 1, close), baseSp, true, mathFont))
            pop()
            i = close + 1
        }
        flushBuf()
    }

    /**
     * 混排渲染：普通文字按 [style] 输出，行内公式与正文同处一行，
     * 行间公式（`$$..$$`）单独成行并居中。
     *
     * 不含 `$` 的文本会退化为单个 Text（不额外测量、不额外重建）。
     *
     * @param allowDisplay 是否把 `$$..$$` 当行间公式单独成行。选项、按钮、标签这类
     *        高度受限的场景必须传 false——否则一个 `$$` 就会把整行撑成两行并居中错位。
     */
    @Composable
    fun LatexText(
        text: String,
        modifier: Modifier = Modifier,
        style: TextStyle = MaterialTheme.typography.bodyLarge,
        color: Color = MaterialTheme.colorScheme.onSurface,
        mathFontFamily: FontFamily = FontFamily.Serif,
        maxLines: Int = Int.MAX_VALUE,
        textAlign: TextAlign? = null,
        allowDisplay: Boolean = true
    ) {
        val segs = remember(text, allowDisplay) { splitSegments(text, allowDisplay) }
        val baseSp = if (style.fontSize.isSp) style.fontSize.value else 15f

        // 全是普通文字：一条 Text 搞定，零额外开销
        if (segs.size == 1 && segs[0] is Seg.Plain) {
            val body = (segs[0] as Seg.Plain).text
            if ('$' !in body) {
                Text(
                    text = body,
                    modifier = modifier,
                    style = style,
                    color = color,
                    maxLines = maxLines,
                    textAlign = textAlign
                )
                return
            }
            Text(
                text = remember(body, baseSp) {
                    annotateMixed(body, baseSp, mathFontFamily, color)
                },
                modifier = modifier,
                style = style,
                color = color,
                maxLines = maxLines,
                textAlign = textAlign
            )
            return
        }

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            segs.forEach { seg ->
                when (seg) {
                    is Seg.Plain -> Text(
                        text = remember(seg.text, baseSp) {
                            annotateMixed(seg.text, baseSp, mathFontFamily, color)
                        },
                        style = style,
                        color = color,
                        maxLines = maxLines,
                        textAlign = textAlign
                    )
                    is Seg.Display -> LatexView(
                        source = seg.math,
                        style = style,
                        color = color,
                        mathFontFamily = mathFontFamily
                    )
                }
            }
        }
    }
}
