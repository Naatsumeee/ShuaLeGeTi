package com.examhelper.shua

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfFontSubsetCoverageTest {

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `PDF fonts cover GB8105 and common Western symbols`() {
        val characters = javaClass.classLoader!!
            .getResourceAsStream("gb8105.txt")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .replace("\r", "")
            .replace("\n", "")
        val required = buildSet {
            characters.codePoints().forEach { add(it) }
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                .codePoints().forEach { add(it) }
            "éÉñÑΩωЖж≤≥±×÷μαβγ°→，。！？；：（）【】“”‘’①"
                .codePoints().forEach { add(it) }
        }

        PDDocument().use { doc ->
            listOf(
                "src/main/assets/pdf/NotoSansSC-Bold.ttf",
                "src/main/assets/pdf/NotoSerifSC-Regular.ttf"
            ).forEach { path ->
                val font = PDType0Font.load(doc, File(path).inputStream())
                val missing = required.filter { codePoint ->
                    runCatching {
                        font.getStringWidth(String(Character.toChars(codePoint)))
                    }.isFailure
                }
                assertTrue(
                    "$path missing ${missing.size}: " +
                        missing.take(30).joinToString("") { String(Character.toChars(it)) },
                    missing.isEmpty()
                )
            }
        }
    }
}
