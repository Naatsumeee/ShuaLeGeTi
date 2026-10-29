package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.ChunkPlanner
import com.examhelper.shua.data.parse.LlmQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    private fun question(no: Int, optionCount: Int = 4): String = buildString {
        appendLine("$no. 这是第 $no 道题的题干，内容需要足够长以模拟真实试卷的题目长度。")
        repeat(optionCount) { i ->
            appendLine("- ${'A' + i}. 选项 ${'A' + i} 的内容描述")
        }
        appendLine("【答案】A")
        appendLine("【解析】这是第 $no 题的解析内容。")
    }

    @Test
    fun `短文本不切分`() {
        val text = question(1) + question(2)
        assertEquals(1, ChunkPlanner.plan(text, 6000).size)
    }

    @Test
    fun `切点永远落在题目开始之前`() {
        val text = (1..40).joinToString("") { question(it) }
        val chunks = ChunkPlanner.plan(text, 800)
        assertTrue("应该切出多块", chunks.size > 2)
        // 每块的第一行必须是题号开头，说明没有把题目劈开
        chunks.forEach { c ->
            val firstLine = c.text.lines().first { it.isNotBlank() }
            assertTrue("块首应是题目起始行，实际=$firstLine", ChunkPlanner.isQuestionStart(firstLine))
        }
    }

    @Test
    fun `所有题目都完整保留没有丢内容`() {
        val text = (1..40).joinToString("") { question(it) }
        val chunks = ChunkPlanner.plan(text, 800)
        val joined = chunks.joinToString("\n") { it.text }
        (1..40).forEach { no ->
            assertTrue("第 $no 题题干丢了", joined.contains("这是第 $no 道题的题干"))
            assertTrue("第 $no 题答案丢了", joined.contains("这是第 $no 题的解析内容"))
        }
    }

    @Test
    fun `没有边界的长文本会硬切而不是死循环`() {
        val text = "无边界" + "内容".repeat(5000)
        val chunks = ChunkPlanner.plan(text, 1000)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun `章节标题也算边界`() {
        val text = buildString {
            appendLine("## 第一章 总论")
            repeat(20) { appendLine("这里是一段很长的正文内容，不属于任何题目，需要被正常切分。") }
            appendLine("## 第二章 各论")
            repeat(20) { appendLine("这里又是一段很长的正文内容，同样不属于任何题目。") }
        }
        val chunks = ChunkPlanner.plan(text, 500)
        assertTrue(chunks.size >= 2)
        chunks.forEach { c ->
            val first = c.text.lines().first { it.isNotBlank() }
            assertTrue("块首应是章节或题号：$first",
                ChunkPlanner.isBoundary(first) || c.index == 0)
        }
    }

    // ---- 答案表回填 ----

    private fun line(no: Int, answer: String?) = ParsedLine(
        chunkIndex = 0, indexInChunk = no, type = QuestionType.SINGLE.name,
        stem = "第 $no 道题的题干", answer = answer
    )

    @Test
    fun `文末答案表按题号回填到缺答案的题`() {
        val lines = listOf(line(1, null), line(2, null), line(3, null))
        val raw = buildString {
            repeat(30) { appendLine("题干正文 $it") }
            appendLine("答案：1.B 2.C 3.D")
        }
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("B", out[0].answer)
        assertEquals("C", out[1].answer)
        assertEquals("D", out[2].answer)
    }

    @Test
    fun `已有答案不被答案表覆盖`() {
        val lines = listOf(line(1, "A"), line(2, null))
        val raw = buildString {
            repeat(30) { appendLine("题干正文 $it") }
            appendLine("参考答案：1.B 2.C")
        }
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals("A", out[0].answer)
        assertEquals("C", out[1].answer)
    }

    @Test
    fun `单条答案不算答案表避免误判`() {
        val lines = listOf(line(1, null))
        val raw = buildString {
            repeat(30) { appendLine("题干正文 $it") }
            appendLine("答案：1.B")
        }
        val out = LlmQuestionParser.finalizeLines(lines, raw)
        assertEquals(null, out[0].answer)
    }

    @Test
    fun `重复解析的同一道题被合并`() {
        val dup = ParsedLine(
            chunkIndex = 1, indexInChunk = 0, type = QuestionType.SINGLE.name,
            stem = "第 1 道题的题干", answer = null,
            analysis = null
        )
        val first = ParsedLine(
            chunkIndex = 0, indexInChunk = 0, type = QuestionType.SINGLE.name,
            stem = "第 1 道题的题干", answer = "A", analysis = "解析内容"
        )
        val out = LlmQuestionParser.finalizeLines(listOf(first, dup), "无关文本")
        assertEquals(1, out.size)
        assertEquals("A", out[0].answer)
        assertEquals("解析内容", out[0].analysis)
    }
}
