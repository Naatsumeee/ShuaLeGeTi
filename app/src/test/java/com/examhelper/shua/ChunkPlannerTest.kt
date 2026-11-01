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

    // ---- 块首衔接窗口（跨段题目不再断成两半）----

    @Test
    fun `切点落在题目中间时下一块会带回上一块末尾的题`() {
        // 每道题都很长，size 设得刚好落在题目中间
        val text = (1..6).joinToString("") { r ->
            buildString {
                appendLine("$r. 这是一道题干特别长的题目，用来强迫切点落在题目内部，")
                appendLine("继续描述题干第二行内容，让它足够长。")
                repeat(4) { i -> appendLine("- ${'A' + i}. 选项 ${'A' + i} 的描述内容也写长一些以凑字符数") }
                appendLine("【答案】A")
            }
        }
        val chunks = ChunkPlanner.plan(text, 300)
        assertTrue("应该切出多块", chunks.size > 1)
        // 相邻两块的行区间必须重叠（有回带），否则切点处的题就断了
        var overlapped = false
        for (i in 0 until chunks.size - 1) {
            if (chunks[i].endLine >= chunks[i + 1].startLine) overlapped = true
        }
        assertTrue("相邻块之间应有重叠窗口", overlapped)
        // 每道题的题干与答案都至少完整出现在某一块里
        (1..6).forEach { no ->
            val full = chunks.any { c ->
                c.text.contains("$no. 这是一道题干特别长的题目") && c.text.contains("【答案】A")
            }
            assertTrue("第 $no 题在某一块里应完整（题干+选项+答案）", full)
        }
    }

    @Test
    fun `关闭重叠窗口时行为与旧版一致`() {
        val text = (1..40).joinToString("") { question(it) }
        val chunks = ChunkPlanner.plan(text, 800, overlapLines = 0)
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunks[i].endLine + 1, chunks[i + 1].startLine)
        }
    }

    @Test
    fun `块首带章节上下文前缀`() {
        val text = buildString {
            appendLine("## 第三章 抗菌药物")
            repeat(30) { appendLine("${it + 1}. 关于抗菌药物的叙述，正确的是……选项内容写长一点凑字符数。") }
        }
        val chunks = ChunkPlanner.plan(text, 400)
        assertTrue(chunks.size > 1)
        chunks.forEach { c ->
            assertTrue("块 ${c.index} 应带章节归属", c.text.contains("抗菌药物") || c.section != null)
        }
    }

    @Test
    fun `题干被截短的重复题合并后保留完整版`() {
        val full = ParsedLine(
            chunkIndex = 1, indexInChunk = 0, type = QuestionType.SINGLE.name,
            stem = "关于青霉素G的抗菌谱下列叙述正确的是",
            options = listOf(
                com.examhelper.shua.data.model.QuestionOption("A", "对革兰阳性菌有效"),
                com.examhelper.shua.data.model.QuestionOption("B", "对革兰阴性菌有效")
            ),
            answer = "A", analysis = "完整解析"
        )
        val truncated = ParsedLine(
            chunkIndex = 0, indexInChunk = 3, type = QuestionType.SINGLE.name,
            stem = "关于青霉素G的抗菌谱下列叙述",
            options = emptyList(), answer = null, analysis = null
        )
        val out = LlmQuestionParser.finalizeLines(listOf(truncated, full), "无关文本")
        assertEquals(1, out.size)
        assertEquals("关于青霉素G的抗菌谱下列叙述正确的是", out[0].stem)
        assertEquals("A", out[0].answer)
        assertEquals(2, out[0].options.size)
    }

    @Test
    fun `题干相似但选项完全不同的两道题不会被合并`() {
        val a = ParsedLine(
            chunkIndex = 0, indexInChunk = 0, type = QuestionType.SINGLE.name,
            stem = "下列关于药物半衰期的说法正确的是",
            options = listOf(
                com.examhelper.shua.data.model.QuestionOption("A", "半衰期越长给药间隔越短"),
                com.examhelper.shua.data.model.QuestionOption("B", "半衰期与清除率无关")
            )
        )
        val b = ParsedLine(
            chunkIndex = 0, indexInChunk = 1, type = QuestionType.SINGLE.name,
            stem = "下列关于药物半衰期的说法正确的是",
            options = listOf(
                com.examhelper.shua.data.model.QuestionOption("A", "一级动力学药物半衰期恒定"),
                com.examhelper.shua.data.model.QuestionOption("B", "零级动力学半衰期随浓度变化")
            )
        )
        val out = LlmQuestionParser.finalizeLines(listOf(a, b), "无关文本")
        assertEquals(2, out.size)
    }

    @Test
    fun `答案表按题号回填而不是盲目下标`() {
        // 题干自带题号 7、8，答案表键也是 7、8，列表只有两条 → 旧实现会填错
        val l7 = ParsedLine(
            chunkIndex = 0, indexInChunk = 0, type = QuestionType.SINGLE.name,
            stem = "7. 第七题的题干内容描述", answer = null,
            options = listOf(
                com.examhelper.shua.data.model.QuestionOption("A", "甲"),
                com.examhelper.shua.data.model.QuestionOption("B", "乙")
            )
        )
        val l8 = ParsedLine(
            chunkIndex = 0, indexInChunk = 1, type = QuestionType.SINGLE.name,
            stem = "8. 第八题的题干内容描述", answer = null,
            options = listOf(
                com.examhelper.shua.data.model.QuestionOption("A", "甲"),
                com.examhelper.shua.data.model.QuestionOption("B", "乙")
            )
        )
        val raw = buildString {
            repeat(30) { appendLine("正文 $it") }
            appendLine("答案：7.B 8.A")
        }
        val out = LlmQuestionParser.finalizeLines(listOf(l7, l8), raw)
        assertEquals("B", out[0].answer)
        assertEquals("A", out[1].answer)
    }

    @Test
    fun `章节名以原文标题为准不会丢掉第X章前缀`() {
        // 模型只回了半截章节名 → 用原文的完整标题
        assertEquals(
            "第三章 抗菌药物",
            ChunkPlanner.resolveChapter("第三章 抗菌药物", "抗菌药物")
        )
        // 模型回的比原文长（含了章节编号写法差异）→ 也归一到原文标题
        assertEquals(
            "第三章 抗菌药物",
            ChunkPlanner.resolveChapter("## 第三章 抗菌药物", "第3章 抗菌药物")
        )
        // 分块没有章节上下文 → 尊重模型
        assertEquals("第四章 心血管", ChunkPlanner.resolveChapter(null, "第四章 心血管"))
        // 模型没给 → 用原文
        assertEquals("第二章 药效学", ChunkPlanner.resolveChapter("第二章 药效学", null))
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
