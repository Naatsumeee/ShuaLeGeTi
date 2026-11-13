package com.examhelper.shua.data.ingest

/**
 * 纯 JVM 的 DOCX 正文提取器（无 Android Context 依赖）。
 *
 * Word 的正文在 zip 成员 `word/document.xml` 里；段落 `<w:p>`、
 * 文本 `<w:t>`、制表 `<w:tab>`、换行 `<w:br>`。这里只抽正文，
 * 不碰图片/公式（它们会以空内容跳过，但不会破坏段落边界）。
 */
object DocxTextExtractor {

    fun extract(bytes: ByteArray): String {
        if (bytes.size < 4) throw IllegalArgumentException("docx 文件为空或已损坏")
        if (!(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
            throw IllegalArgumentException("这不是有效的 docx 文件（扩展名可能被改过）")
        }
        val xml = readDocumentXml(bytes)
            ?: throw IllegalArgumentException("docx 中没有找到 word/document.xml")
        val text = parseXml(xml)
        if (text.isBlank()) throw IllegalArgumentException("docx 中没有找到文本内容")
        return text
    }

    internal fun readDocumentXml(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) throw IllegalArgumentException("docx 文件为空或已损坏")
        if (!(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
            throw IllegalArgumentException("这不是有效的 docx 文件（扩展名可能被改过）")
        }
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.equals("word/document.xml", ignoreCase = true)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    internal fun parseXml(bytes: ByteArray): String {
        return SimpleWordXmlParser.parse(bytes.toString(Charsets.UTF_8))
    }
}
