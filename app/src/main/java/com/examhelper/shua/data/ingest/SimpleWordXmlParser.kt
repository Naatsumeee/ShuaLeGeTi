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
            when {
                tag == "w:p" || tag == "w:p/" -> flushParagraph()
                tag == "w:tab" || tag == "w:tab/" -> paragraph.append('\t')
                tag == "w:br" || tag == "w:br/" -> paragraph.append('\n')
                tag.startsWith("w:t") && !tag.startsWith("w:tbl") -> {
                    val bodyStart = gt + 1
                    val bodyEnd = xml.indexOf("</w:t>", bodyStart).takeIf { it >= 0 } ?: xml.length
                    paragraph.append(xml.substring(bodyStart, bodyEnd))
                    i = bodyEnd + 6
                    continue
                }
            }
            i = gt + 1
        }
        flushParagraph()
        return sb.toString()
    }
}
