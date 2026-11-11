package com.examhelper.shua

import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.data.parse.AnswerBinder
import com.examhelper.shua.data.parse.AnswerSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 答案回填的回归护栏。
 *
 * 守护的核心事实：**答案表按「每章、每题型分组」各自从 1 重新编号**，
 * 而 AI 解析产出的题干通常不带题号 —— 任何「按全书下标猜题号」的实现都会
 * 整列错位。这里同时锁住三条边界：
 * 1. 有结构化题号时精确填，不猜；
 * 2. 题号全缺时才允许按章内序号兜底，且要求答案表编号体系对得上；
 * 3. 任何时候都不把错答案填进去（宁可留空）。
 */
class AnswerBinderTest {

    private fun obj(chapter: String?, no: Int?, keys: String = "ABCDE") = AnswerBinder.Item(
        chapter = chapter,
        stem = "题干内容 $no",
        optionKeys = keys.map { it.toString() }.toSet(),
        number = no,
        objectiveType = true
    )

    private fun subj(chapter: String?, stem: String) = AnswerBinder.Item(
        chapter = chapter, stem = stem, optionKeys = emptySet(),
        number = null, objectiveType = false
    )

    // ---------- AnswerSheet 的排版兼容 ----------

    @Test
    fun `一行多对支持全角空格分隔`() {
        // 中文试卷答案区几乎都用全角空格。Java 正则的 \s 不含 U+3000，
        // 漏了它整行会被判「有实义残留」→ 落到主观题分支 → 得到一条伪数据。
        assertEquals(
            listOf(1 to "B", 2 to "D", 3 to "A"),
            AnswerSheet.parse("1.B\u30002.D\u30003.A")
        )
    }

    @Test
    fun `一行多对支持题型分组前缀`() {
        // 「A1型：1.B 2.D 3.A」是很常见的写法，早期整组答案全丢
        assertEquals(
            listOf(1 to "B", 2 to "D", 3 to "A"),
            AnswerSheet.parse("A1型：1.B 2.D 3.A")
        )
    }

    @Test
    fun `一行一对仍然正常`() {
        assertEquals(listOf(1 to "B", 2 to "D"), AnswerSheet.parse("1. B\n2. D"))
    }

    @Test
    fun `题号与答案被 OCR 拆成两行仍能拼接`() {
        assertEquals(listOf(18 to "A"), AnswerSheet.parse("18.\nA"))
    }

    // ---------- 按结构化题号回填 ----------

    @Test
    fun `题干不带题号时用结构化 number 精确回填`() {
        // AI 的常态：stem 里没有题号，只有 number 字段
        val raw = """
            第六章 线粒体遗传
            一、A1型题
            1. 题干一
            2. 题干二
            三、参考答案
            【A1型题】
            1.B 2.C
        """.trimIndent()
        val items = listOf(obj("第六章 线粒体遗传", 1), obj("第六章 线粒体遗传", 2))
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("B", bound[0])
        assertEquals("C", bound[1])
    }

    @Test
    fun `同章不同题型各自从 1 编号时按 number 各归各位`() {
        // 名词解释 1~2、A1 型 1~2、A2 型 3~4 —— 题号在同一章里重复出现
        val raw = """
            第七章 人类染色体
            三、参考答案
            （一）名词解释
            1.兼性异染色质（facultative heterochromatin):是指一类在特定细胞
            2.同源染色体（homologous chromosome):即在人类正常核型中，染色体是成对存在
            （二）选择题
            【A1型题】
            1.A 2.D
            【A2型题】
            3.C 4.D
        """.trimIndent()
        val ch = "第七章 人类染色体"
        val items = listOf(
            subj(ch, "兼性异染色质"),
            subj(ch, "同源染色体"),
            obj(ch, 1), obj(ch, 2), obj(ch, 3), obj(ch, 4)
        )
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("A", bound[2])
        assertEquals("D", bound[3])
        assertEquals("C", bound[4])
        assertEquals("D", bound[5])
        // 主观题按内容配，两条都要拿到正文
        assertTrue(bound[0]!!.startsWith("兼性异染色质"))
        assertTrue(bound[1]!!.startsWith("同源染色体"))
    }

    @Test
    fun `跨章不会串答案`() {
        val raw = """
            第六章 线粒体遗传
            三、参考答案
            1.A
            第七章 人类染色体
            三、参考答案
            1.B
        """.trimIndent()
        val items = listOf(obj("第六章 线粒体遗传", 1), obj("第七章 人类染色体", 1))
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("A", bound[0])
        assertEquals("B", bound[1])
    }

    // ---------- 护栏：宁可留空也不填错 ----------

    @Test
    fun `答案字母不在本题选项里就不填`() {
        val raw = """
            第一章 绪论
            三、参考答案
            1.E
        """.trimIndent()
        // 本题只有 A/B 两个选项，答案表给了 E → 不采纳
        val items = listOf(obj("第一章 绪论", 1, keys = "AB"))
        val bound = AnswerBinder.bind(raw, items)
        assertEquals(null, bound[0])
    }

    @Test
    fun `题号全缺且答案表编号体系对不上时不按序号猜`() {
        // 答案表键最大到 40，本章只有 3 道客观题 → 编号体系不一致，不能猜
        val raw = """
            第八章 染色体畸变
            三、参考答案
            38.A 39.B 40.C
        """.trimIndent()
        val items = listOf(obj("第八章 染色体畸变", null), obj("第八章 染色体畸变", null))
        val bound = AnswerBinder.bind(raw, items)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `题号全缺但答案表刚好覆盖时按章内序号兜底`() {
        val raw = """
            第一章 绪论
            三、参考答案
            1.B 2.C
        """.trimIndent()
        val items = listOf(obj("第一章 绪论", null), obj("第一章 绪论", null))
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("B", bound[0])
        assertEquals("C", bound[1])
    }

    @Test
    fun `行内前缀表也能回填`() {
        // 「答案：1.B 2.C 3.A」写在正文里，前面没有独立的「参考答案」行
        val raw = """
            第一章 绪论
            1. 题干一
            2. 题干二
            答案：1.B 2.C
        """.trimIndent()
        val items = listOf(obj("第一章 绪论", 1), obj("第一章 绪论", 2))
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("B", bound[0])
        assertEquals("C", bound[1])
    }

    @Test
    fun `单条答案不算答案表`() {
        // 题干里「答案：B」这种单对不能当答案表，否则会误判
        val raw = """
            第一章 绪论
            1. 题干一 答案：B
        """.trimIndent()
        val items = listOf(obj("第一章 绪论", 1))
        val bound = AnswerBinder.bind(raw, items)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `共用备选答案的题也能回填`() {
        // B1 型题 options 为空、选项挂在 sharedOptions 上；
        // 用 options 判客观题会让它们被判成主观题，答案永远配不上
        val raw = """
            第九章 单基因病
            三、参考答案
            1.A 2.B
        """.trimIndent()
        val items = listOf(
            AnswerBinder.Item(
                chapter = "第九章 单基因病", stem = "患者双亲均正常",
                optionKeys = setOf("A", "B", "C", "D", "E"),
                number = 1, objectiveType = true
            ),
            AnswerBinder.Item(
                chapter = "第九章 单基因病", stem = "男性患者明显多于女性",
                optionKeys = setOf("A", "B", "C", "D", "E"),
                number = 2, objectiveType = true
            )
        )
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("A", bound[0])
        assertEquals("B", bound[1])
    }

    @Test
    fun `选项被 OCR 整块吃掉的客观题仍占答案表题号`() {
        // 第八章实测：40 道客观题只有 29 道识别出选项。用选项数判断是否客观题，
        // 会让缺选项的题从序号里消失，后面所有题集体前移一位。
        val raw = """
            第八章 染色体畸变
            三、参考答案
            1.A 2.B 3.C
        """.trimIndent()
        val items = listOf(
            obj("第八章 染色体畸变", 1),
            AnswerBinder.Item(
                chapter = "第八章 染色体畸变", stem = "选项丢了的那道题",
                optionKeys = emptySet(), number = 2, objectiveType = true
            ),
            obj("第八章 染色体畸变", 3)
        )
        val bound = AnswerBinder.bind(raw, items)
        assertEquals("A", bound[0])
        assertEquals("B", bound[1])
        assertEquals("C", bound[2])
    }
}
