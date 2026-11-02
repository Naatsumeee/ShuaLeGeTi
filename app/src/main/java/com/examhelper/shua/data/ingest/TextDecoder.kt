package com.examhelper.shua.data.ingest

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本字节 → String 的编码判定。
 *
 * 为什么必须有这一层：Windows 记事本、Excel 导出、大量教务系统下的 .txt/.md
 * 默认是 **GBK**（ANSI/GB2312 家族），而 Android 的 [String] 构造只有显式指定
 * UTF-8 才安全。直接用 `String(bytes, Charsets.UTF_8)` 读 GBK 文件时，
 * 每个汉字的双字节会被拆成两三个非法 UTF-8 序列，产出成片的 U+FFFD（`�`）——
 * 用户看到的就是「类似 UTF-8 和 GBK 转换错误、文字完全错乱」的乱码。
 *
 * 判定顺序（先严格后宽容，宁可失败也不猜错）：
 * 1. BOM（UTF-8 / UTF-16LE / UTF-16BE）—— 有 BOM 就听 BOM 的，且 BOM 必须剥掉，
 *    否则首行会变成「\uFEFF第一章」，题号/章节正则全部失配，表现为整章归错或首题丢失。
 * 2. 严格 UTF-8（CodingErrorAction.REPORT）—— 合法就按 UTF-8，覆盖绝大多数文本。
 * 3. UTF-16 无 BOM 的猜测 —— 靠 0x00 字节的分布判断（UTF-16 的 ASCII 高位恒为 0）。
 * 4. 严格 GB18030 —— GBK/GB2312 的超集，GBK 文件在这里必然成功。
 * 5. 全部失败 → UTF-8 宽容解码，让用户还能看到部分内容，而不是直接报错。
 */
object TextDecoder {

    private val GB18030: Charset = Charset.forName("GB18030")

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        decodeByBom(bytes)?.let { return it }
        // 先试严格 UTF-8。注意 NUL 在 UTF-8 里是合法字节，UTF-16LE 的 ASCII 文本
        // （31 00 2E 00 …）会被"成功"解成「1\0.\0」，所以解出 NUL 时要让位给 UTF-16 猜测。
        val utf8 = strictDecode(bytes, Charsets.UTF_8)
        if (utf8 != null && utf8.indexOf('\u0000') < 0) return utf8
        guessUtf16(bytes)?.let { return it }
        if (utf8 != null) return utf8
        strictDecode(bytes, GB18030)?.let { return it }
        return String(bytes, Charsets.UTF_8)
    }

    /** 按 BOM 解码并剥掉 BOM；没有 BOM 返回 null。 */
    private fun decodeByBom(b: ByteArray): String? {
        fun has(vararg prefix: Int) =
            b.size >= prefix.size && prefix.indices.all { (b[it].toInt() and 0xFF) == prefix[it] }

        return when {
            has(0xEF, 0xBB, 0xBF) -> String(b, 3, b.size - 3, Charsets.UTF_8)
            has(0xFF, 0xFE) -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            has(0xFE, 0xFF) -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> null
        }
    }

    /**
     * 无 BOM 的 UTF-16 猜测。
     *
     * 只在「零字节占比很高」时才认——纯 ASCII 的 UTF-16 文本每隔一个字节就是 0x00，
     * 占比 50%；带中文的也通常在 30% 以上。真实的 GBK/UTF-8 文本几乎不含 0x00，
     * 所以这个判据不会被误触发。
     */
    private fun guessUtf16(b: ByteArray): String? {
        if (b.size < 8 || b.size % 2 != 0) return null
        var zeros = 0
        var evenZeros = 0
        var oddZeros = 0
        for (i in b.indices) {
            if (b[i] == 0.toByte()) {
                zeros++
                if (i % 2 == 0) evenZeros++ else oddZeros++
            }
        }
        if (zeros * 100 / b.size < 30) return null
        // 小端时低字节在前（'1' = 31 00），零字节落在**奇数**下标；大端相反。
        val charset = if (oddZeros >= evenZeros) Charsets.UTF_16LE else Charsets.UTF_16BE
        return strictDecode(b, charset)
    }

    /** 严格解码：任何一个字节序列非法就返回 null，绝不静默替换成 U+FFFD。 */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * 判断一段文本是不是「已经乱掉」的产物。
     *
     * 用于 PDF 文字层：嵌入式字体缺 ToUnicode CMap 时，PDFBox 会抽出大量
     * U+FFFD 或私有区字符，长度够但内容全是垃圾。这种页应当判定为「文字层不可用」，
     * 转而走 OCR，而不是把垃圾喂给解析器。
     */
    fun looksGarbled(text: String): Boolean {
        if (text.length < 20) return false
        var bad = 0
        for (ch in text) {
            if (ch == '\uFFFD' || ch.code in 0xE000..0xF8FF) bad++
        }
        return bad * 100 / text.length >= 8
    }
}
