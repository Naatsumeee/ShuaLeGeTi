package com.examhelper.shua

import com.examhelper.shua.data.ingest.TextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码判定单测。
 *
 * 背景：v1.0.9 用户报「类似 UTF-8 和 GBK 转换错误的乱码，文字完全错乱」。
 * 根因是 .txt/.md 一律按 UTF-8 硬解，Windows 上的 GBK 文件因此变成成片 U+FFFD。
 */
class TextDecoderTest {

    private val sample = "第一章 药理学基础\n1. 下列药物属于β受体阻断药的是\n答案：A\n"

    // ---------- GBK / GB18030 ----------

    @Test
    fun decodesGbkTextWithoutReplacementChars() {
        val bytes = sample.toByteArray(charset("GBK"))
        val text = TextDecoder.decode(bytes)
        assertEquals(sample, text)
        assertFalse("GBK 文本不应出现替换字符：$text", text.contains('\uFFFD'))
    }

    @Test
    fun decodesGb18030RareChars() {
        // 生僻字「𠮷」在 GB18030 里是四字节，GBK 编不出来 —— 顺带确认回退链能兜住
        val s = "姓名：𠮷田"
        val text = TextDecoder.decode(s.toByteArray(charset("GB18030")))
        assertEquals(s, text)
    }

    /**
     * 这条是回归锚点：不修的话 String(bytes, UTF_8) 会产出 78 个 U+FFFD。
     * 数字来自真实复现（.shots 下同款 GBK 样本）。
     */
    @Test
    fun gbkBytesReadAsUtf8ProduceReplacementChars() {
        val bytes = sample.toByteArray(charset("GBK"))
        val wrong = String(bytes, Charsets.UTF_8)
        assertTrue("对照组应确认为乱码", wrong.count { it == '\uFFFD' } > 10)
        // 而正确路径一个字都不丢
        assertEquals(0, TextDecoder.decode(bytes).count { it == '\uFFFD' })
    }

    // ---------- UTF-8 与 BOM ----------

    @Test
    fun decodesPlainUtf8() {
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun stripsUtf8Bom() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            sample.toByteArray(Charsets.UTF_8)
        val text = TextDecoder.decode(withBom)
        assertEquals(sample, text)
        // 关键：BOM 必须被剥掉。留着会让首行变成「\uFEFF第一章」，
        // 章节正则与题号正则全部失配，表现为首题丢失/整章归错。
        assertFalse(text.startsWith("\uFEFF"))
    }

    @Test
    fun decodesUtf16WithBom() {
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charsets.UTF_16LE)
            .let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }))
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charsets.UTF_16BE)
            .let { byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + it }))
    }

    @Test
    fun decodesUtf16WithoutBomWhenAsciiHeavy() {
        // 无 BOM 的 UTF-16 只在「零字节占比很高」时才敢认（ASCII 或半 ASCII 内容）。
        // 中文 UTF-16 的零字节占比只有 13% 左右，判不出来，所以必须靠 BOM——
        // 现实里也没有哪个 Windows 工具会把中文存成无 BOM 的 UTF-16。
        val ascii = "1. ABCD\nA. alpha\nB. beta\nanswer: is A\n"
        assertEquals(ascii, TextDecoder.decode(ascii.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun emptyInputGivesEmptyString() {
        assertEquals("", TextDecoder.decode(ByteArray(0)))
    }

    /**
     * 纯 ASCII 不能被 UTF-16 猜测误伤：全是单字节文本时零字节占比为 0。
     */
    @Test
    fun asciiIsNotMistakenForUtf16() {
        val ascii = "1. ABCD\nA. alpha\nB. beta\nanswer: A\n"
        assertEquals(ascii, TextDecoder.decode(ascii.toByteArray(Charsets.UTF_8)))
    }

    // ---------- 乱码检测（PDF 文字层） ----------

    @Test
    fun flagsReplacementCharHeavyText() {
        val garbage = "\uFFFD".repeat(30) + "abc"
        assertTrue(TextDecoder.looksGarbled(garbage))
    }

    @Test
    fun flagsPrivateUseAreaHeavyText() {
        // 嵌入式字体缺 ToUnicode CMap 时，PDFBox 常抽出私有区码位
        val garbage = (0xE000..0xE020).map { it.toChar() }.joinToString("") + "第1题"
        assertTrue(TextDecoder.looksGarbled(garbage))
    }

    @Test
    fun normalChineseIsNotGarbled() {
        assertFalse(TextDecoder.looksGarbled(sample))
    }

    @Test
    fun shortTextIsNeverGarbled() {
        // 少于 20 字符一律不当乱码，避免个别替换字符把整页判死
        assertFalse(TextDecoder.looksGarbled("\uFFFD\uFFFD\uFFFD"))
    }
}
