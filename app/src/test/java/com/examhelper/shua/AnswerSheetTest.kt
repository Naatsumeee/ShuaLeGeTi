package com.examhelper.shua

import com.examhelper.shua.data.parse.AnswerSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章末集中答案区的识别与解析。
 *
 * 每条用例都对应真实扫描件里踩到的一种排版（见各用例注释）。
 */
class AnswerSheetTest {

    @Test
    fun `答案区起点能被识别`() {
        assertTrue(AnswerSheet.isRegionStart("三、参考答案"))
        assertTrue(AnswerSheet.isRegionStart("参考答案"))
        assertTrue(AnswerSheet.isRegionStart("答案："))
        assertTrue(AnswerSheet.isRegionStart("（一）参考答案"))
        // 题干里的「答案：B」不是区域起点（行太长 / 带内容）
        assertFalse(AnswerSheet.isRegionStart("答案：B"))
        assertFalse(AnswerSheet.isRegionStart("1.下列药物属于β受体阻断药的是"))
    }

    @Test
    fun `整行一对的答案表`() {
        val span = """
            （二）选择题
            【A1型题】
            1. D
            2.B
            3、C
            4.E
        """.trimIndent()
        val t = AnswerSheet.parse(span).toMap()
        assertEquals("D", t[1])
        assertEquals("B", t[2])
        assertEquals("C", t[3])
        assertEquals("E", t[4])
    }

    @Test
    fun `答案字母覆盖到 F 及以后`() {
        val span = """
            1.F
            2.G
            3.H
        """.trimIndent()

        val table = AnswerSheet.parse(span).toMap()

        assertEquals("F", table[1])
        assertEquals("G", table[2])
        assertEquals("H", table[3])
    }

    @Test
    fun `答案被 OCR 挤到下一行也能拼上`() {
        // 实测：p26 里 18./26./28./29./32. 都是题号单独一行，答案在下一行
        val span = """
            17.E
            18.
            A
            19.A
            26.
            B
        """.trimIndent()
        val t = AnswerSheet.parse(span).toMap()
        assertEquals("E", t[17])
        assertEquals("A", t[18])
        assertEquals("A", t[19])
        assertEquals("B", t[26])
    }

    @Test
    fun `题号后粘了脏字符也能认出`() {
        // 实测：p26 出现 `27.1` 后面跟答案 A —— 那个 1 是 OCR 噪声
        val span = """
            26.
            B
            27.1
            A
            28.
            C
        """.trimIndent()
        val t = AnswerSheet.parse(span).toMap()
        assertEquals("B", t[26])
        assertEquals("A", t[27])
        assertEquals("C", t[28])
    }

    @Test
    fun `一行挤了多对答案`() {
        // 实测：`1.E"2.B3.D`、`31.E32.C3`（尾部残留被截断的题号）
        val span = """
            1.E"2.B3.D
            31.E32.C3
        """.trimIndent()
        val t = AnswerSheet.parse(span).toMap()
        assertEquals("E", t[1])
        assertEquals("B", t[2])
        assertEquals("D", t[3])
        assertEquals("E", t[31])
        assertEquals("C", t[32])
    }

    @Test
    fun `一行多对里有一个题号乱码时仍保留其他答案`() {
        val span = """
            1.D�.C3.B4.C5.D
        """.trimIndent()

        val table = AnswerSheet.parse(span).toMap()

        assertEquals("D", table[1])
        assertEquals("B", table[3])
        assertEquals("C", table[4])
        assertEquals("D", table[5])
    }

    @Test
    fun `行首装饰符不影响识别`() {
        // 实测：`¥19.B`
        val span = """
            ¥19.B
            20.C
        """.trimIndent()
        val t = AnswerSheet.parse(span).toMap()
        assertEquals("B", t[19])
        assertEquals("C", t[20])
    }

    @Test
    fun `主观题答案整段正文`() {
        val span = """
            （一）名词解释
            1．纯质性（homoplasmy):是指一个细胞或一个个体的线粒体中只有一种 mtDNA 的现象,相对
            于杂质性。
            2.异质性（heteroplasmy);是指一个细胞或一个个体的线粒体中含有多种 mtDNA 的现象。
            （二）选择题
            1. D
        """.trimIndent()
        val rows = AnswerSheet.parse(span)
        val prose = rows.first { it.first == 1 && it.second.length > 8 }
        assertTrue("名词解释正文应跨行拼起来", prose.second.contains("于杂质性"))
        assertTrue(prose.second.contains("纯质性"))
        // 选择题的 1 也要在（题号重复，不能互相覆盖）
        assertTrue(rows.any { it.first == 1 && it.second == "D" })
    }

    @Test
    fun `同章题号重复时两条都保留`() {
        // 名词解释 1~5 与选择题 1~5 各自从 1 编号，用 Map 会覆盖掉一半
        val span = """
            （一）名词解释
            1．纯质性（homoplasmy):是指一个细胞或一个个体的线粒体中只有一种 mtDNA 的现象,相对于杂质性。
            2.异质性（heteroplasmy);是指一个细胞或一个个体的线粒体中含有多种 mtDNA 的现象。
            （二）选择题
            1. D
            2.B
        """.trimIndent()
        val rows = AnswerSheet.parse(span)
        assertEquals(2, rows.count { it.first == 1 })
        assertEquals(2, rows.count { it.first == 2 })
    }

    @Test
    fun `切段时取最后一个答案区起点`() {
        // 整本书里每章末都有一块答案，取第一个会丢掉后面所有章
        val text = """
            第六章 线粒体遗传
            1.题目一
            三、参考答案
            1.A
            第七章 人类染色体
            1.题目二
            三、参考答案
            1.B
        """.trimIndent()
        val (body, ans) = AnswerSheet.splitRegion(text)
        assertNotNull(ans)
        assertTrue("题目区应保留两章内容", body.contains("第六章") && body.contains("第七章"))
        assertFalse("答案区不应包含题目", ans!!.contains("题目一"))
        assertEquals("B", AnswerSheet.parse(ans).toMap()[1])
    }

    @Test
    fun `同一章有多个答案区起点时全部解析`() {
        val span = """
            参考答案
            1.A
            二、参考答案
            2.B
        """.trimIndent()

        val rows = AnswerSheet.parseAllRegions(span).toMap()

        assertEquals("A", rows[1])
        assertEquals("B", rows[2])
    }

    @Test
    fun `没有答案区时返回原文`() {
        val text = "1.题目一\nA.甲\nB.乙\n答案：A"
        val (body, ans) = AnswerSheet.splitRegion(text)
        assertNull(ans)
        assertEquals(text, body)
    }

    @Test
    fun `正文里的数字不会被当成题号`() {
        // `1.5 毫升` 这种不该被认成「题号 1 的答案 5」
        val span = """
            给药剂量为1.5毫升，每日2次。
            正常值为 3.5 到 5.5 之间。
        """.trimIndent()
        val t = AnswerSheet.parse(span)
        assertTrue("不应解析出任何答案，实际 $t", t.isEmpty())
    }
}
