package com.examhelper.shua

import com.examhelper.shua.data.parse.ChunkPlanner
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长 md / txt 的分块、重叠与去重回归。 */
class LongTextImportTest {

    private fun buildLongBank(chapters: Int, perChapter: Int): String {
        val sb = StringBuilder()
        repeat(chapters) { c ->
            sb.appendLine("第${c + 1}章 测试章节")
            sb.appendLine("一、单项选择题")
            repeat(perChapter) { i ->
                sb.appendLine("${i + 1}. 第${c + 1}章第${i + 1}题的题干内容是什么？")
                sb.appendLine("A. 选项甲${c}_${i}")
                sb.appendLine("B. 选项乙${c}_${i}")
                sb.appendLine("C. 选项丙${c}_${i}")
                sb.appendLine("D. 选项丁${c}_${i}")
                sb.appendLine("答案：A")
            }
            sb.appendLine("三、参考答案")
            repeat(perChapter) { i -> sb.appendLine("${i + 1}. A") }
        }
        return sb.toString()
    }

    @Test
    fun `长文本离线解析不会重复也不会漏题`() {
        val text = buildLongBank(chapters = 12, perChapter = 40)
        assertTrue("测试文本应超过单块阈值", text.length > TextQuestionParser.OFFLINE_CHUNK_SIZE)
        val qs = TextQuestionParser.parseLong(text)
        assertEquals(12 * 40, qs.size)
        assertEquals((12 * 40), qs.map { it.stem }.distinct().size)
        assertTrue(qs.all { it.answer == "A" })
    }

    @Test
    fun `逐块进度回调覆盖所有分块`() {
        val text = buildLongBank(chapters = 12, perChapter = 40)
        val progress = mutableListOf<Pair<Int, Int>>()
        TextQuestionParser.parseLong(text) { done, total -> progress += done to total }
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.last().second, progress.last().first)
        assertEquals(progress.indices.map { it + 1 }, progress.map { it.first })
    }

    @Test
    fun `分块切点只落在题目或章节边界`() {
        val text = buildLongBank(chapters = 8, perChapter = 30)
        val chunks = ChunkPlanner.plan(text, 800, overlapLines = 12)
        assertTrue(chunks.size > 2)
        chunks.forEach { chunk ->
            val firstBody = chunk.text.lineSequence()
                .dropWhile { it.trim().startsWith("[章节上下文]") }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            assertTrue(
                "块首必须是题号/章节边界：$firstBody",
                ChunkPlanner.isBoundary(firstBody) || firstBody.startsWith("第")
            )
        }
    }

    @Test
    fun `答案表在长文本分块后仍能按题号回填`() {
        val text = buildLongBank(chapters = 10, perChapter = 25)
        val qs = TextQuestionParser.parseLong(text)
        assertEquals(250, qs.size)
        assertEquals(250, qs.count { it.answer == "A" })
    }
}
