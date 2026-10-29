package com.examhelper.shua

import com.examhelper.shua.ui.theme.Latex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下标挂载的回归测试。
 *
 * 真实 bug（m11304 后续实测发现）：`\int_{0}^{1} x^{2} dx` 被渲染成 `∫₀²x dx`——
 * `^{2}` 在 `x` 还留在文本缓冲区时被算到了积分号头上，把上限 1 覆盖成 2，
 * 同时 `x` 的平方丢失。`\sum_{i=1}^{n} i^{2}` 同理变成 `∑_{i=1}^{2} i`。
 * 这类错误在截图上不放大到 4 倍很难发现，所以用节点树断言锁死。
 */
class LatexScriptTest {

    @Test
    fun integralKeepsOwnLimitsAndPowerStaysOnVariable() {
        // 数学模式里空格不产生宽度（解析层会丢弃），断言前统一去空格
        val tree = Latex.debugTree("""\int_{0}^{1} x^{2} dx""").replace(" ", "")
        // 积分号自带上下限，且上限必须是 1（不是被 x 的 2 覆盖）
        assertEquals("∫_(0)^(1)", tree.substringBefore("x"))
        // x 的平方挂在 x 上，尾部 dx 仍是普通文本
        assertEquals("x^(2)dx", tree.substringAfter("^(1)"))
    }

    @Test
    fun sumKeepsOwnLimitsAndPowerStaysOnVariable() {
        val tree = Latex.debugTree("""\sum_{i=1}^{n} i^{2}""")
        assertEquals("∑_(i=1)^(n)", tree.substringBefore("i^(2)").trim())
        assertEquals("i^(2)", tree.substringAfter("^(n)").trim())
    }

    @Test
    fun consecutiveScriptsOnSameBaseAccumulate() {
        // x_1^2 这种同一底数的上下标都应挂到 x 上
        assertEquals("x^(2)_(1)", Latex.debugTree("x_1^2"))
    }

    @Test
    fun bigOperatorSymbolsAreNotEmittedVerbatim() {
        // bigcup / bigcap 曾只进 bigOps 却没进 symbols → 原样吐出 "bigcup"
        val tree = Latex.debugTree("""\bigcup_{i=1}^{n} A_i""")
        assertEquals(false, tree.contains("bigcup"))
        assertEquals(true, tree.startsWith("⋃"))
    }

    @Test
    fun plainSubscriptStillWorks() {
        assertEquals("a_(n+1)", Latex.debugTree("a_{n+1}").replace(" ", ""))
    }
}
