package com.examhelper.shua

import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.data.parse.LlmQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 章后集中答案识别（m27404 bug3）的回归测试。
 *
 * 根因：样例题库（医学遗传学/医学伦理）的答案区是最朴素的「每行一对」形式——
 * `1.A`、`2. D`、`12.C`，前面**没有**「答案：」二字。旧实现只认带前缀的
 * 一行多对表（`答案：1.B 2.C`），于是这类答案区整块被丢弃，用户看到
 * 「章后集中出现的答案大范围无法识别」。
 *
 * 第二个坑：题号在每章内重新从 1 开始计数，全书合并成一张表会让第 7 章的
 * `1.A` 填到第 6 章第 1 题上——必须按「第X章」切段各自建表。
 */
class AnswerTableTest {

    private fun line(
        stem: String,
        options: List<Pair<String, String>>,
        answer: String? = null,
        chapter: String? = null,
        index: Int = 0
    ) = ParsedLine(
        chunkIndex = 0,
        indexInChunk = index,
        chapter = chapter,
        stem = stem,
        options = options.map { QuestionOption(it.first, it.second) },
        answer = answer
    )

    private val four = listOf(
        "A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"
    )

    @Test
    fun `裸行答案表能被识别`() {
        // 真实 OCR 形态：答案区每行只有「题号.答案」
        val raw = """
            第六章 线粒体遗传
            （二）选择题
            1.mtDNA 指
            A.突变的 DNA
            B.线粒体 DNA
            2.线粒体遗传的特点是
            A.母系遗传
            B.父系遗传
            三、参考答案
            （二）选择题
            【A1型题】
            1.B
            2. A
        """.trimIndent()
        val lines = listOf(
            line("1.mtDNA 指", four, chapter = "第六章 线粒体遗传", index = 0),
            line("2.线粒体遗传的特点是", four, chapter = "第六章 线粒体遗传", index = 1)
        )
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("B", out[0].answer)
        assertEquals("A", out[1].answer)
    }

    @Test
    fun `带答案前缀的一行多对表仍能识别`() {
        val raw = """
            第一章 绪论
            1.第一题
            A.甲
            B.乙
            2.第二题
            A.甲
            B.乙
            参考答案
            答案：1.B 2.A
        """.trimIndent()
        val lines = listOf(
            line("1.第一题", four, chapter = "第一章 绪论", index = 0),
            line("2.第二题", four, chapter = "第一章 绪论", index = 1)
        )
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("B", out[0].answer)
        assertEquals("A", out[1].answer)
    }

    @Test
    fun `不同章节的题号不会串章`() {
        // 两个章各有 1、2 号题，答案不同 —— 旧实现全书一张表必然串章
        val raw = """
            第六章 线粒体遗传
            1.第六章第一题
            A.甲
            B.乙
            2.第六章第二题
            A.甲
            B.乙
            三、参考答案
            1.B
            2.A
            第七章 单基因遗传病
            1.第七章第一题
            A.甲
            B.乙
            2.第七章第二题
            A.甲
            B.乙
            三、参考答案
            1.A
            2.B
        """.trimIndent()
        val lines = listOf(
            line("1.第六章第一题", four, chapter = "第六章 线粒体遗传", index = 0),
            line("2.第六章第二题", four, chapter = "第六章 线粒体遗传", index = 1),
            line("1.第七章第一题", four, chapter = "第七章 单基因遗传病", index = 2),
            line("2.第七章第二题", four, chapter = "第七章 单基因遗传病", index = 3)
        )
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("B", out[0].answer)
        assertEquals("A", out[1].answer)
        assertEquals("A", out[2].answer)
        assertEquals("B", out[3].answer)
    }

    @Test
    fun `答案不在选项里时不回填`() {
        // 答案字母 E 不在这道题的 A~D 选项里 → 宁可留空也不填错
        val raw = """
            第一章 绪论
            1.第一题
            A.甲
            B.乙
            三、参考答案
            1.E
        """.trimIndent()
        val lines = listOf(line("1.第一题", four, chapter = "第一章 绪论", index = 0))
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertNull(out[0].answer)
    }

    @Test
    fun `已有答案不被覆盖`() {
        val raw = """
            第一章 绪论
            1.第一题
            A.甲
            B.乙
            答案：1.A
        """.trimIndent()
        val lines = listOf(line("1.第一题", four, answer = "B", chapter = "第一章 绪论", index = 0))
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("B", out[0].answer)
    }
}
