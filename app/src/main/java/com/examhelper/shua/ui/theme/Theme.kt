package com.examhelper.shua.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Anthropic Claude 暖纸美学（Parchment / Ivory / Terracotta）+ 苹果液态玻璃。
 * 约束：全域暖调中性色，杜绝冷调科技蓝灰；衬线只做大标题与卡片标题，正文无衬线，
 * 数据/公式/代码等宽；悬浮层走毛玻璃 + 半透明白内衬 + 白色高光边 + 内发光倒角。
 */

// ---- 亮色（暖纸 / Ivory 卡面） ----
object PaperPalette {
    /** 核心画布底色：温暖羊皮纸 */
    val Parchment = Color(0xFF141413)
    /** 页面底（App 实际大面积底色，Parchment 之上提亮，避免正文刺眼） */
    val Canvas = Color(0xFFF7F4EC)
    /** 卡片浮层表面：象牙白 */
    val Ivory = Color(0xFF252523)
    /** 卡片实际填充（用 Ivory 会显得过深，这里取接近象牙白的高亮面） */
    val IvorySurface = Color(0xFFFFFDF8)
    /** 次级卡片 / 选中态底 */
    val IvorySunken = Color(0xFFF0ECE1)
    /** 核心品牌主色：Anthropic 赤陶红 */
    val Terracotta = Color(0xFFD97757)
    val TerracottaDeep = Color(0xFFB85C3C)
    val TerracottaSoft = Color(0xFFF6DBD0)
    /** 森林鼠尾草绿：错误 / 避坑语义 */
    val Sage = Color(0xFFB53333)
    val SageSoft = Color(0xFFF2D8D2)
    /**
     * 正确 / 答对语义色：暗绿。
     * 不用高饱和 emerald（会在暖纸底上跳出画面），取冷调被压下来的「墨绿」，
     * 与暖纸底色同属低饱和自然色系，既区分于赤陶主色，又不破坏整体调性。
     */
    val Verdant = Color(0xFF3F6B4A)
    val VerdantSoft = Color(0xFFDCE6DA)
    /** 琥珀黄：其他标注 */
    val Amber = Color(0xFFD97706)
    val AmberSoft = Color(0xFFF8E5C8)
    /** 极细暖奶油边框 */
    val CreamLine = Color(0xFFF0EEE6)
    val CreamLineStrong = Color(0xFFE2DED0)
    /** 正文墨色 */
    val Ink = Color(0xFF1C1B19)
    val InkSoft = Color(0xFF5A5449)
    val InkFaint = Color(0xFF8C8577)
}

// ---- 暗色（橄榄柔光夜读） ----
object OlivePalette {
    val Parchment = Color(0xFF141413)
    val Canvas = Color(0xFF17170F)
    val IvorySurface = Color(0xFF1F1F1A)
    val IvorySunken = Color(0xFF27271F)
    val Terracotta = Color(0xFFD97757)
    val TerracottaDeep = Color(0xFFE68D6F)
    val TerracottaSoft = Color(0xFF4A2C22)
    val Sage = Color(0xFFE08573)
    val SageSoft = Color(0xFF4C241D)
    val Verdant = Color(0xFF8FB894)
    val VerdantSoft = Color(0xFF243528)
    val Amber = Color(0xFFE0A23F)
    val AmberSoft = Color(0xFF43301A)
    val CreamLine = Color(0xFF30302E)
    val CreamLineStrong = Color(0xFF3B3B36)
    val Ink = Color(0xFFE8E4D8)
    val InkSoft = Color(0xFFAFA99B)
    val InkFaint = Color(0xFF8A8579)
}

private val LightColors = lightColorScheme(
    primary = PaperPalette.Terracotta,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = PaperPalette.TerracottaSoft,
    onPrimaryContainer = Color(0xFF4A1D0C),
    secondary = PaperPalette.InkSoft,
    onSecondary = PaperPalette.IvorySurface,
    secondaryContainer = PaperPalette.IvorySunken,
    onSecondaryContainer = PaperPalette.Ink,
    tertiary = PaperPalette.Amber,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = PaperPalette.AmberSoft,
    onTertiaryContainer = Color(0xFF4A2B00),
    error = PaperPalette.Sage,
    onError = Color(0xFFFFFFFF),
    errorContainer = PaperPalette.SageSoft,
    onErrorContainer = Color(0xFF4A1710),
    background = PaperPalette.Canvas,
    onBackground = PaperPalette.Ink,
    surface = PaperPalette.IvorySurface,
    onSurface = PaperPalette.Ink,
    surfaceVariant = PaperPalette.IvorySunken,
    onSurfaceVariant = PaperPalette.InkSoft,
    outline = PaperPalette.InkFaint,
    outlineVariant = PaperPalette.CreamLineStrong,
    inverseSurface = PaperPalette.Parchment,
    inverseOnSurface = PaperPalette.Canvas,
    surfaceTint = PaperPalette.Terracotta,
    surfaceDim = Color(0xFFEDE8DB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF8F1),
    surfaceContainer = Color(0xFFF5F1E6),
    surfaceContainerHigh = Color(0xFFF0ECDF),
    surfaceContainerHighest = Color(0xFFEAE5D6)
)

private val DarkColors = darkColorScheme(
    primary = OlivePalette.Terracotta,
    onPrimary = Color(0xFF2B120A),
    primaryContainer = OlivePalette.TerracottaSoft,
    onPrimaryContainer = Color(0xFFF5D9CB),
    secondary = OlivePalette.InkSoft,
    onSecondary = OlivePalette.Parchment,
    secondaryContainer = OlivePalette.IvorySunken,
    onSecondaryContainer = OlivePalette.Ink,
    tertiary = OlivePalette.Amber,
    onTertiary = Color(0xFF2E1E00),
    tertiaryContainer = OlivePalette.AmberSoft,
    onTertiaryContainer = Color(0xFFF3DFBE),
    error = OlivePalette.Sage,
    onError = Color(0xFF2B0E08),
    errorContainer = OlivePalette.SageSoft,
    onErrorContainer = Color(0xFFF3D6CE),
    background = OlivePalette.Canvas,
    onBackground = OlivePalette.Ink,
    surface = OlivePalette.IvorySurface,
    onSurface = OlivePalette.Ink,
    surfaceVariant = OlivePalette.IvorySunken,
    onSurfaceVariant = OlivePalette.InkSoft,
    outline = OlivePalette.InkFaint,
    outlineVariant = OlivePalette.CreamLineStrong,
    inverseSurface = PaperPalette.Canvas,
    inverseOnSurface = PaperPalette.Ink,
    surfaceTint = OlivePalette.Terracotta,
    surfaceDim = Color(0xFF12120C),
    surfaceBright = Color(0xFF2A2A22),
    surfaceContainerLowest = Color(0xFF131310),
    surfaceContainerLow = Color(0xFF1B1B15),
    surfaceContainer = Color(0xFF1F1F1A),
    surfaceContainerHigh = Color(0xFF27271F),
    surfaceContainerHighest = Color(0xFF31312A)
)

// ---- 语义扩展：玻璃参数、语义色、虚线/成功态等 Material 里没有的角色 ----
data class ShuaExtraColors(
    val parchment: Color,
    val ivory: Color,
    val terracotta: Color,
    val terracottaDeep: Color,
    val terracottaSoft: Color,
    val sage: Color,
    val sageSoft: Color,
    /** 正确/答对：暗绿（与赤陶主色区分，符合暖纸调型） */
    val verdant: Color,
    val verdantSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val creamLine: Color,
    val creamLineStrong: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val glassInnerGlow: Color,
    val isDark: Boolean
)

private val LightExtra = ShuaExtraColors(
    parchment = PaperPalette.Parchment,
    ivory = PaperPalette.IvorySurface,
    terracotta = PaperPalette.Terracotta,
    terracottaDeep = PaperPalette.TerracottaDeep,
    terracottaSoft = PaperPalette.TerracottaSoft,
    sage = PaperPalette.Sage,
    sageSoft = PaperPalette.SageSoft,
    verdant = PaperPalette.Verdant,
    verdantSoft = PaperPalette.VerdantSoft,
    amber = PaperPalette.Amber,
    amberSoft = PaperPalette.AmberSoft,
    creamLine = PaperPalette.CreamLine,
    creamLineStrong = PaperPalette.CreamLineStrong,
    ink = PaperPalette.Ink,
    inkSoft = PaperPalette.InkSoft,
    inkFaint = PaperPalette.InkFaint,
    glassFill = Color(0xB8FFFDF8),        // rgba(255,253,248,0.72)
    glassStroke = Color(0x80FFFFFF),      // rgba(255,255,255,0.5)
    glassInnerGlow = Color(0x66FFFFFF),
    isDark = false
)

private val DarkExtra = ShuaExtraColors(
    parchment = OlivePalette.Parchment,
    ivory = OlivePalette.IvorySurface,
    terracotta = OlivePalette.Terracotta,
    terracottaDeep = OlivePalette.TerracottaDeep,
    terracottaSoft = OlivePalette.TerracottaSoft,
    sage = OlivePalette.Sage,
    sageSoft = OlivePalette.SageSoft,
    verdant = OlivePalette.Verdant,
    verdantSoft = OlivePalette.VerdantSoft,
    amber = OlivePalette.Amber,
    amberSoft = OlivePalette.AmberSoft,
    creamLine = OlivePalette.CreamLine,
    creamLineStrong = OlivePalette.CreamLineStrong,
    ink = OlivePalette.Ink,
    inkSoft = OlivePalette.InkSoft,
    inkFaint = OlivePalette.InkFaint,
    glassFill = Color(0xB3262620),        // 夜间玻璃：橄榄墨半透明
    glassStroke = Color(0x33FFFFFF),
    glassInnerGlow = Color(0x1FFFFFFF),
    isDark = true
)

val LocalShuaColors = staticCompositionLocalOf { LightExtra }

/** 在任意 Composable 里取扩展语义色 */
val shuaColors: ShuaExtraColors
    @Composable get() = LocalShuaColors.current

// ---- 排版：衬线标题 / 无衬线正文 / 等宽数据 ----
private val SerifFamily = FontFamily.Serif
private val SansFamily = FontFamily.SansSerif
private val MonoFamily = FontFamily.Monospace

/** 大标题与卡片标题：Medium Serif，字重 500，书籍扉页般的典雅感 */
fun serifTitle(size: androidx.compose.ui.unit.TextUnit = 22.sp, weight: FontWeight = FontWeight.Medium) =
    TextStyle(
        fontFamily = SerifFamily,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * 1.35f,
        letterSpacing = 0.2.sp
    )

/** 数据、公式与代码 */
fun monoStyle(size: androidx.compose.ui.unit.TextUnit = 13.sp, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = MonoFamily,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * 1.5f,
        letterSpacing = 0.sp
    )

private fun shuaTypography() = Typography(
    displayLarge = serifTitle(46.sp, FontWeight.Medium),
    displayMedium = serifTitle(40.sp, FontWeight.Medium),
    displaySmall = serifTitle(34.sp, FontWeight.Medium),
    headlineLarge = serifTitle(30.sp, FontWeight.Medium),
    headlineMedium = serifTitle(26.sp, FontWeight.Medium),
    headlineSmall = serifTitle(22.sp, FontWeight.Medium),
    titleLarge = serifTitle(20.sp, FontWeight.Medium).copy(fontFamily = SerifFamily),
    titleMedium = serifTitle(16.sp, FontWeight.Medium),
    titleSmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 27.sp,   // 1.68
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,   // 1.64
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 19.sp,   // 1.58
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )
)

/** 液态玻璃需要较大圆角，统一放宽 */
private val LiquidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun ShuaTheme(
    /** null = 跟随系统；true/false = 用户手动指定 */
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalShuaColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = shuaTypography(),
            shapes = LiquidShapes,
            content = content
        )
    }
}
