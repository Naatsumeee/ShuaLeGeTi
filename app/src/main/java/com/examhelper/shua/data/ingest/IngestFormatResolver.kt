package com.examhelper.shua.data.ingest

/**
 * 文档格式判定（纯 JVM，可单测）。
 *
 * Android 文件管理器对 MIME 的报法极不统一：同一个 Markdown 文件可能是
 * `text/markdown`、`text/x-markdown`、`text/plain`、`application/octet-stream`，
 * 云盘甚至可能返回空 display name。单看扩展名/MIME 会把文本误送进图片 OCR，
 * 最终报「无法解码图片」。这里把判定拆成三层：
 * 1. 明确的扩展名（最可靠，优先）
 * 2. 标准 MIME
 * 3. 文件头 + 文本启发式（兜底无扩展名/通用 MIME）
 */
object IngestFormatResolver {

    enum class Format {
        DOCX,
        PDF,
        LEGACY_DOC,
        MARKDOWN,
        TEXT,
        IMAGE,
        ZIP_UNSUPPORTED,
        UNSUPPORTED
    }

    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd", "mdtext", "mdtxt")
    private val textExtensions = setOf("txt", "text", "log", "csv", "tsv")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")

    fun fromMetadata(displayName: String?, mime: String?): Format? {
        val name = displayName.orEmpty().trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        when (ext) {
            "docx" -> return Format.DOCX
            "pdf" -> return Format.PDF
            "doc" -> return Format.LEGACY_DOC
            in markdownExtensions -> return Format.MARKDOWN
            in textExtensions -> return Format.TEXT
            in imageExtensions -> return Format.IMAGE
        }

        val m = mime.orEmpty().lowercase().substringBefore(';').trim()
        return when {
            m.contains("openxmlformats-officedocument.wordprocessingml") -> Format.DOCX
            m == "application/pdf" -> Format.PDF
            m == "application/msword" -> Format.LEGACY_DOC
            m.contains("markdown") -> Format.MARKDOWN
            m == "text/plain" || m == "text/x-text" || m == "application/txt" -> Format.TEXT
            m.startsWith("text/") && !m.contains("html") && !m.contains("xml") -> Format.TEXT
            m.startsWith("image/") -> Format.IMAGE
            else -> null
        }
    }

    /**
     * 从文件头判断强特征；不读取整个文件。
     * 返回 null 表示要继续用 MIME/全文文本启发式判断。
     */
    fun fromMagic(prefix: ByteArray): Format? {
        if (prefix.isEmpty()) return Format.UNSUPPORTED
        if (prefix.startsWithAscii("%PDF-")) return Format.PDF
        if (prefix.startsWithAscii("{\\rtf")) return Format.UNSUPPORTED
        if (prefix.startsWithAscii("PK\u0003\u0004")) {
            // ZIP 可能是 docx，也可能是 xlsx/epub；由调用方读取全文后再确认。
            return Format.ZIP_UNSUPPORTED
        }
        if (isImage(prefix)) return Format.IMAGE
        return null
    }

    /** 全文兜底：识别 docx zip、文本启发式，并区分 Markdown / 纯文本。 */
    fun fromBytes(bytes: ByteArray): Format {
        if (bytes.isEmpty()) return Format.TEXT
        if (bytes.startsWithAscii("PK\u0003\u0004")) {
            return if (DocxTextExtractor.readDocumentXml(bytes) != null) Format.DOCX
            else Format.ZIP_UNSUPPORTED
        }
        if (bytes.startsWithAscii("%PDF-")) return Format.PDF
        if (isImage(bytes)) return Format.IMAGE
        if (!looksLikeText(bytes)) return Format.UNSUPPORTED
        val decoded = TextDecoder.decode(bytes)
        return if (looksLikeMarkdown(decoded)) Format.MARKDOWN else Format.TEXT
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        // 带 BOM 的文本直接通过；后续 TextDecoder 会正确解码。
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return true
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return true
        }
        val sample = TextDecoder.decode(bytes.copyOf(minOf(bytes.size, 64 * 1024)))
        if (sample.isEmpty()) return false
        if (sample.indexOf('\u0000') >= 0) return false
        var bad = 0
        var printable = 0
        for (ch in sample) {
            when {
                ch == '\uFFFD' -> bad += 2
                ch == '\n' || ch == '\r' || ch == '\t' -> printable++
                ch.code < 0x20 || ch.code == 0x7F -> bad += 2
                else -> printable++
            }
        }
        if (printable == 0) return false
        // 二进制误判的典型特征：控制字符/U+FFFD 占比高。
        return bad * 100 <= (printable + bad) * 4
    }

    /** 强 Markdown 特征；普通题库里的 A. B. 选项不会被误判。 */
    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(300).toList()
        if (lines.isEmpty()) return false
        var strong = 0
        for (line in lines) {
            if (Regex("""^#{1,6}\s+\S""").containsMatchIn(line)) strong += 2
            if (Regex("""^(```|~~~)""").containsMatchIn(line)) strong += 2
            if (Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$""").containsMatchIn(line)) strong += 2
            if (Regex("""^[-*+]\s+[A-Za-z]\s*[.、．]\s*""").containsMatchIn(line)) strong += 1
            if (Regex("""^(?:[-*+]\s+)?[A-Za-z]\s*[.、．]\s+.*\*\*.+\*\*""").containsMatchIn(line)) strong += 1
            if (Regex("""\[[^\]]+]\([^)]+\)""").containsMatchIn(line)) strong += 1
        }
        return strong >= 2
    }

    fun unsupportedMessage(displayName: String?): String {
        val name = displayName?.takeIf { it.isNotBlank() } ?: "该文件"
        return "暂不支持「$name」的格式。请导入 PDF、docx、Markdown（.md）、纯文本（.txt）或图片。"
    }

    private fun isImage(b: ByteArray): Boolean {
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 &&
            (b[2].toInt() and 0xFF) == 0xFF
        ) return true
        if (b.size >= 8 && b.startsWithAscii("\u0089PNG\r\n\u001A\n")) return true
        if (b.size >= 4 && b.startsWithAscii("GIF8")) return true
        if (b.size >= 2 && b.startsWithAscii("BM")) return true
        if (b.size >= 12 && b.startsWithAscii("RIFF") &&
            String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return true
        if (b.size >= 12 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp") {
            val brand = String(b, 8, 4, Charsets.US_ASCII).lowercase()
            return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
        }
        return false
    }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean {
        val p = prefix.toByteArray(Charsets.ISO_8859_1)
        if (size < p.size) return false
        return p.indices.all { this[it] == p[it] }
    }
}
