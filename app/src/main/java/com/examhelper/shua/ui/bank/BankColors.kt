package com.examhelper.shua.ui.bank

import androidx.compose.ui.graphics.Color

/** 题库组可选 8 色 + 默认正确绿；刻意避开首页品牌赤陶与黑白灰。 */
object BankColors {
    /** 正确选项绿：题库组默认色 */
    val Default = Color(0xFF3F6B4A)

    val Presets: List<Pair<String, Color>> = listOf(
        "松针绿" to Color(0xFF3F6B4A),
        "湖蓝" to Color(0xFF2F6F8F),
        "靛青" to Color(0xFF465A9A),
        "莓紫" to Color(0xFF8A4F7D),
        "赭石" to Color(0xFF9A5B32),
        "芥末金" to Color(0xFFB38A2E),
        "珊瑚粉" to Color(0xFFC25D6A),
        "青碧" to Color(0xFF2E7D78)
    )

    /** #RRGGBB / #AARRGGBB；拒绝黑白灰与过透明颜色。 */
    fun parseHex(raw: String): Color? {
        val s = raw.trim().removePrefix("#")
        val expanded = when (s.length) {
            3 -> s.map { "$it$it" }.joinToString("")
            6, 8 -> s
            else -> return null
        }
        val value = expanded.toLongOrNull(16) ?: return null
        val argb = if (expanded.length == 6) 0xFF000000L or value else value
        val a = ((argb shr 24) and 0xFF).toInt()
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        if (a < 0x80) return null
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        // 黑白灰/接近无色（饱和度 < 12%）不适合当题库识别色。
        if (max - min < 30) return null
        return Color(argb)
    }

    fun toHex(color: Color): String = String.format(
        "#%02X%02X%02X",
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )

    /** HSL -> RGB，供色盘使用。h 0..360, s/l 0..1 */
    fun hsl(h: Float, s: Float, l: Float): Color {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * ll - 1f)) * ss
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = ll - c / 2f
        return Color(r1 + m, g1 + m, b1 + m)
    }
}
