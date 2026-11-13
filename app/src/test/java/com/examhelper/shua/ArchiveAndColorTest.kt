package com.examhelper.shua

import androidx.compose.ui.graphics.toArgb
import com.examhelper.shua.data.export.ShuaArchive
import com.examhelper.shua.data.model.ShuaBankFile
import com.examhelper.shua.ui.bank.BankColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ArchiveAndColorTest {

    @Test
    fun `shua 是小写扩展名的 zip 容器`() {
        val file = ShuaBankFile(name = "测试题库", exportedAt = 1L, questions = emptyList())
        val bytes = ShuaArchive.build(1L, listOf(file to "{}".toByteArray()), "[]".toByteArray())
        assertTrue(bytes.size > 20)
        assertNotNull(ShuaArchive.read(bytes)[ShuaArchive.MANIFEST])
        val names = ShuaArchive.read(bytes).keys
        assertTrue(names.any { it.startsWith("banks/") })
        assertTrue(names.contains(ShuaArchive.RECORDS))
        // 扩展名是 .shua，不是 .zip
        assertTrue(ShuaArchive.suggestedFileName("20261113").endsWith(".shua"))
        // 仍是标准 zip，可直接用 ZipInputStream 打开
        ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry }
    }

    @Test
    fun `颜色校验允许自定义黑白但预设仍不含黑白灰`() {
        assertNotNull(BankColors.parseHex("#3F6B4A"))
        assertNotNull(BankColors.parseHex("D97757"))
        assertNotNull(BankColors.parseHex("#8A4F7D"))
        assertNotNull(BankColors.parseHex("#000000"))
        assertNotNull(BankColors.parseHex("#FFFFFF"))
        assertNotNull(BankColors.parseHex("#808080"))
        assertNull(BankColors.parseHex("not-a-color"))
        assertNull(BankColors.parseHex("#00FFFFFF")) // 完全透明
    }

    @Test
    fun `颜色存入 Room 后再读出不发生通道错乱`() {
        val original = BankColors.parseHex("#D97757")!!
        val restored = BankColors.fromStorage(BankColors.toStorage(original))!!
        assertEquals(original.toArgb(), restored.toArgb())
    }

    @Test
    fun `HSL 色盘能生成有区分度的颜色`() {
        val colors = (0 until 8).map { i -> BankColors.hsl(i * 45f, 0.55f, 0.42f) }
        assertEquals(8, colors.size)
        assertTrue(colors.toSet().size == 8)
    }

    @Test
    fun `已有颜色能准确转换回 HSL 色盘坐标`() {
        val color = BankColors.parseHex("#3F6B4A")!!
        val hsl = BankColors.toHsl(color)
        val roundTrip = BankColors.hsl(hsl.hue, hsl.saturation, hsl.lightness)
        assertEquals(color.toArgb(), roundTrip.toArgb())

        val gray = BankColors.toHsl(BankColors.parseHex("#808080")!!)
        assertEquals(0f, gray.saturation, 0.001f)
    }
}
