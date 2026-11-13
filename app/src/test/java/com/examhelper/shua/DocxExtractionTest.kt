package com.examhelper.shua

import com.examhelper.shua.data.ingest.DocxTextExtractor
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxExtractionTest {

    private fun extract(bytes: ByteArray): String = DocxTextExtractor.extract(bytes)

    @Test
    fun `真实 docx 能抽出段落而不是报没有文本`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("docx-sample.docx")!!
            .use { it.readBytes() }
        val text = extract(bytes)
        assertTrue("应包含章标题", text.contains("第一章"))
        assertTrue("应包含多选题干", text.contains("DNA 双螺旋结构模型"))
        val questions = TextQuestionParser.parse(text)
        assertTrue("真实样例题库至少应解析出 50 题，实际=${questions.size}", questions.size >= 50)
    }

    @Test
    fun `document xml 路径前缀和大小写不影响抽取`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>1. 题干</w:t></w:r></w:p>
            <w:p><w:r><w:t>A. 甲</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>B. 乙</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        val bytes = buildDocx("/WORD/DOCUMENT.XML", xml)
        val text = extract(bytes)
        assertTrue(text.contains("1. 题干"))
        assertTrue(text.contains("A. 甲"))
        assertTrue(text.contains("B. 乙"))
    }

    @Test
    fun `非 docx 内容给出可行动的错误`() {
        val ex = runCatching { extract("not a zip".toByteArray()) }
            .exceptionOrNull() as IllegalArgumentException
        assertTrue(ex.message!!.contains("不是有效的 docx"))
    }

    private fun buildDocx(path: String, xml: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
