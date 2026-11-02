package com.examhelper.shua

import com.examhelper.shua.data.ingest.TextDecoder
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GBK 题库文件从字节到题目对象的完整链路回归。
 *
 * 这是用户报的那个 bug 的端到端复现：v1.0.9 的 .txt/.md 一律按 UTF-8 硬解，
 * Windows 上的 GBK 文件在解码阶段就碎成 U+FFFD，后面的解析器自然一题都切不出来。
 */
class GbkImportPipelineTest {

    private val bankText = """
        第一章 药理学基础

        1. 下列药物中属于β受体阻断药的是
        A. 普萘洛尔
        B. 肾上腺素
        C. 阿托品
        D. 麻黄碱
        答案：A
        解析：普萘洛尔为非选择性β受体阻断药。

        2. 下列哪项是肝药酶诱导剂
        A. 西咪替丁
        B. 苯巴比妥
        C. 酮康唑
        D. 红霉素
        答案：B
        解析：苯巴比妥可诱导CYP450酶系。
    """.trimIndent()

    @Test
    fun gbkBytesSurviveWholePipeline() {
        val bytes = bankText.toByteArray(charset("GBK"))
        val text = TextDecoder.decode(bytes)
        val questions = TextQuestionParser.parse(text)

        assertEquals("应解析出 2 道题，实际 ${questions.size}", 2, questions.size)
        assertTrue(
            "题干应完整保留中文，实际：${questions[0].stem}",
            questions[0].stem.contains("β受体阻断药")
        )
        assertEquals("A", questions[0].answer)
        assertEquals("B", questions[1].answer)
        assertTrue(
            "第 2 题题干乱码：${questions[1].stem}",
            questions[1].stem.contains("肝药酶诱导剂")
        )
    }

    /** 对照组：修复前的解码方式在这份字节上一题都切不出来。 */
    @Test
    fun utf8HardDecodeBreaksParsing() {
        val bytes = bankText.toByteArray(charset("GBK"))
        val broken = String(bytes, Charsets.UTF_8)
        assertTrue("对照文本应含大量替换字符", broken.count { it == '\uFFFD' } > 20)
        // 题干关键词已不存在，解析器切不出正常题目
        val questions = TextQuestionParser.parse(broken)
        assertTrue(
            "乱码文本不应产出可用的完整题目，实际 ${questions.size} 题",
            questions.none { it.stem.contains("β受体阻断药") }
        )
    }

    /** 带 UTF-8 BOM 的文本：修复前首行残留 \uFEFF 会让「第一章」不被识别为章节。 */
    @Test
    fun utf8BomDoesNotLeakIntoFirstLine() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            bankText.toByteArray(Charsets.UTF_8)
        val text = TextDecoder.decode(bytes)
        assertTrue("首字符不应是 BOM", text.first() != '\uFEFF')
        val questions = TextQuestionParser.parse(text)
        assertEquals(2, questions.size)
        // 这里只关心 BOM 不泄漏：首行若带 \uFEFF，「第一章 药理学基础」会变成一条
        // 认不出的普通文本行，被当作第 1 题的题干前缀吞掉。
        assertTrue(questions[0].stem.startsWith("下列药物"))
    }
}
