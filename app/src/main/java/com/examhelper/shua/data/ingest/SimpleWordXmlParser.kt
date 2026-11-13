package com.examhelper.shua.data.ingest

/**
 * 不依赖 XmlPullParser 的最小 WordprocessingML 文本提取器。
 *
 * Android 的 `XmlPullParserFactory.newInstance()` 在纯 JVM 单测里是
 * “not mocked” 的 Android stub，无法直接用于回归测试；而 docx 正文的
 * WordprocessingML 结构非常稳定（`w:p` / `w:t` / `w:tab` / `w:br`）。
 * 这里用轻量标签扫描，既能在 JVM 单测里跑真实 docx，也不引入第三方解析器。
 */
object SimpleWordXmlParser {
    fun parse(xml: String): String {
        val sb = StringBuilder()
        val paragraph = StringBuilder()
        var i = 0
        fun flushParagraph() {
            if (paragraph.isNotBlank()) sb.appendLine(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                paragraph.append(xml.substring(i))
                break
            }
            if (lt > i) paragraph.append(xml.substring(i, lt))
            val gt = xml.indexOf('>', lt + 1)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt).trim()
            val closing = tag.startsWith("/")
            val rawName = tag.trimStart('/').trimEnd('/')
                .substringBefore(' ')
                .substringBefore('\t')
            // 不依赖命名空间前缀：w:t / strict:t 都取最后一段。
            val local = rawName.substringAfter(':').lowercase()
            val selfClosing = tag.endsWith("/")
            when {
                local == "p" -> flushParagraph()
                local == "tab" -> paragraph.append('\t')
                local == "br" || local == "cr" -> paragraph.append('\n')
                local == "t" && !closing && !selfClosing -> {
                    val bodyStart = gt + 1
                    val close = Regex("""</(?:[A-Za-z0-9_]+:)?t\s*>""").find(xml, bodyStart)
                    val bodyEnd = close?.range?.first ?: xml.length
                    paragraph.append(decodeXmlEntities(xml.substring(bodyStart, bodyEnd)))
                    i = close?.range?.last?.plus(1) ?: xml.length
                    continue
                }
            }
            i = gt + 1
        }
        flushParagraph()
        return sb.toString()
    }

    private fun decodeXmlEntities(raw: String): String {
        if ('&' !in raw) return raw
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
        return Regex("""&(#x[0-9A-Fa-f]+|#\d+|[A-Za-z]+);""").replace(raw) { m ->
            val token = m.groupValues[1]
            when {
                token.startsWith("#x") -> token.drop(2).toIntOrNull(16)
                    ?.let { String(Character.toChars(it)) } ?: m.value
                token.startsWith("#") -> token.drop(1).toIntOrNull()
                    ?.let { String(Character.toChars(it)) } ?: m.value
                else -> named[token] ?: m.value
            }
        }
    }
}
