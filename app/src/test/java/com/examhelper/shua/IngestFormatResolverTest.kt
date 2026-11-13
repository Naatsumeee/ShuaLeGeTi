package com.examhelper.shua

import com.examhelper.shua.data.ingest.IngestFormatResolver
import com.examhelper.shua.data.ingest.IngestFormatResolver.Format
import com.examhelper.shua.data.ingest.TextDecoder
import com.examhelper.shua.data.parse.MarkdownToText
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * .md / .txt 导入链路的格式分流回归。
 *
 * 用户实际遇到的不是“解析器不认 Markdown”，而是文件在选择器里变灰或
 * 被当成图片送 OCR。这里锁住三层判定与最终解析结果。
 */
class IngestFormatResolverTest {

    private val md = """
        # 第一章 药理学基础

        ## 单项选择题

        **1.** 下列药物中属于 β 受体阻断药的是
        - A. 普萘洛尔
        - B. 肾上腺素
        - C. 阿托品
        - D. 麻黄碱

        **参考答案：A**

        **解析：** 普萘洛尔为非选择性 β 受体阻断药。

        ## 名词解释

        **2.** 肝药酶

        **答案：** 肝药酶指肝脏微粒体混合功能氧化酶系。
    """.trimIndent()

    @Test
    fun `md 和 txt 的常见 MIME 都能分流`() {
        assertEquals(Format.MARKDOWN, IngestFormatResolver.fromMetadata("题库.md", "application/octet-stream"))
        assertEquals(Format.MARKDOWN, IngestFormatResolver.fromMetadata("题库.MARKDOWN", null))
        assertEquals(Format.TEXT, IngestFormatResolver.fromMetadata("题库.txt", "application/octet-stream"))
        assertEquals(Format.TEXT, IngestFormatResolver.fromMetadata("noext", "text/plain"))
        assertEquals(Format.TEXT, IngestFormatResolver.fromMetadata("noext", "text/x-text"))
        assertEquals(Format.MARKDOWN, IngestFormatResolver.fromMetadata("noext", "text/markdown"))
        assertEquals(Format.IMAGE, IngestFormatResolver.fromMetadata("IMG_001.JPG", null))
    }

    @Test
    fun `无扩展名 UTF8 Markdown 通过文件头与内容识别`() {
        val bytes = md.toByteArray(Charsets.UTF_8)
        assertEquals(Format.MARKDOWN, IngestFormatResolver.fromBytes(bytes))
    }

    @Test
    fun `无扩展名 GBK 纯文本不会误判成图片`() {
        val plain = """
            第一章 药理学基础
            1. 下列属于 β 受体阻断药的是
            A. 普萘洛尔
            B. 肾上腺素
            答案：A
        """.trimIndent()
        val bytes = plain.toByteArray(charset("GBK"))
        assertEquals(Format.TEXT, IngestFormatResolver.fromBytes(bytes))
        val decoded = TextDecoder.decode(bytes)
        val qs = TextQuestionParser.parse(decoded)
        assertEquals(1, qs.size)
        assertEquals("A", qs[0].answer)
    }

    @Test
    fun `UTF16 无 BOM 文本也能识别为文本`() {
        val text = "1. 题干\nA. 甲\nB. 乙\n答案：A"
        val le = text.toByteArray(Charsets.UTF_16LE)
        assertTrue(IngestFormatResolver.looksLikeText(le))
    }

    @Test
    fun `二进制文件不会被误判为文本`() {
        val binary = ByteArray(256) { (it * 37 % 256).toByte() }
        assertFalse(IngestFormatResolver.looksLikeText(binary))
    }

    @Test
    fun `Markdown 降级后仍能解析题目与行内答案`() {
        val normalized = MarkdownToText.normalize(md)
        val qs = TextQuestionParser.parse(normalized)
        assertEquals(2, qs.size)
        assertEquals("A", qs[0].answer)
        assertTrue(qs[0].stem.contains("β 受体阻断药"))
        assertTrue(qs[1].answer!!.contains("肝药酶"))
    }

    @Test
    fun `普通题库不会被误判为 Markdown`() {
        val plain = """
            1. 题干
            A. 甲
            B. 乙
            答案：A
        """.trimIndent()
        assertFalse(IngestFormatResolver.looksLikeMarkdown(plain))
    }

    @Test
    fun `Markdown 表格分隔线不会被当成题目或分隔线删除`() {
        val tableMd = """
            # 第一章
            1. 题干
            A. 甲
            B. 乙
            答案：A

            | 药物 | 受体 |
            | --- | --- |
            | 普萘洛尔 | β |
        """.trimIndent()
        val normalized = MarkdownToText.normalize(tableMd)
        assertTrue(normalized.contains("| 药物 | 受体 |"))
        assertTrue(normalized.contains("| --- | --- |"))
    }
}
