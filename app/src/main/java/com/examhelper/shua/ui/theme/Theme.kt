package com.examhelper.shua.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 纸感阅读风（editorial-reader，取自 huashu-design 风格库样张 token）：
 * 米纸底 + 墨色文字 + 赭红点缀 + 衬线标题，长时间阅读题干优先。
 */

// ---- 纸感色板 ----
private val PaperBg = Color(0xFFF2EEE3)        // 页面底（比样张 E4DDCC 提亮一档，护眼）
private val PaperCard = Color(0xFFE9E3D3)      // 卡片
private val PaperCardDeep = Color(0xFFE0D8C6)  // 次级卡片/选中态
private val Ink = Color(0xFF1C1B19)            // 墨色主文字
private val InkSoft = Color(0xFF4F483B)        // 次级文字
private val InkFaint = Color(0xFF8B8272)       // 弱文字/描边
private val Accent = Color(0xFFB5532A)         // 赭红主色（按钮/进度/强调）
private val AccentDeep = Color(0xFF8E3B1E)     // 赭红深
private val AccentContainer = Color(0xFFE8D2C0) // 赭红浅容器（正确态底）
private val Verdant = Color(0xFF4C6B57)        // 纸感绿：正确/成功
private val VerdantContainer = Color(0xFFD8E0D2)
private val NightBg = Color(0xFF1C1B19)        // 夜间纸墨
private val NightCard = Color(0xFF26221C)
private val NightCardDeep = Color(0xFF332E26)
private val NightText = Color(0xFFDDD6C8)
private val NightTextSoft = Color(0xFFA79E8D)
private val NightAccent = Color(0xFFD9774A)
private val NightAccentContainer = Color(0xFF5C2E1A)
private val NightVerdant = Color(0xFF8FAE97)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFF3D1F0E),
    secondary = InkSoft,
    onSecondary = Color(0xFFF6F2E8),
    secondaryContainer = PaperCardDeep,
    onSecondaryContainer = Ink,
    tertiary = Verdant,
    onTertiary = Color(0xFFF6F2E8),
    tertiaryContainer = VerdantContainer,
    onTertiaryContainer = Color(0xFF1F3328),
    error = Color(0xFF93312A),
    onError = Color(0xFFFFF8F0),
    errorContainer = Color(0xFFE8CFC4),
    onErrorContainer = Color(0xFF3D1208),
    background = PaperBg,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = PaperCardDeep,
    onSurfaceVariant = InkSoft,
    outline = InkFaint,
    outlineVariant = Color(0xFFC9BFA9),
    inverseSurface = Ink,
    inverseOnSurface = PaperBg
)

private val DarkColors = darkColorScheme(
    primary = NightAccent,
    onPrimary = Color(0xFF2B120A),
    primaryContainer = NightAccentContainer,
    onPrimaryContainer = Color(0xFFF0D9C8),
    secondary = NightTextSoft,
    onSecondary = Color(0xFF221F1A),
    secondaryContainer = NightCardDeep,
    onSecondaryContainer = NightText,
    tertiary = NightVerdant,
    onTertiary = Color(0xFF1B2620),
    tertiaryContainer = Color(0xFF33443A),
    onTertiaryContainer = Color(0xFFDCE6DD),
    error = Color(0xFFE08573),
    onError = Color(0xFF2B0E08),
    errorContainer = Color(0xFF5C2318),
    onErrorContainer = Color(0xFFF0D5CC),
    background = NightBg,
    onBackground = NightText,
    surface = NightCard,
    onSurface = NightText,
    surfaceVariant = NightCardDeep,
    onSurfaceVariant = NightTextSoft,
    outline = NightTextSoft,
    outlineVariant = Color(0xFF4A443A),
    inverseSurface = PaperBg,
    inverseOnSurface = Ink
)

// 衬线标题 + 衬线小标注（强调/解释性文字）+ 无衬线正文
private fun editorialTypography(dark: Boolean) = Typography(
    displayLarge = serifStyle(48.sp, dark),
    displayMedium = serifStyle(42.sp, dark),
    headlineLarge = serifStyle(34.sp, dark),
    headlineMedium = serifStyle(28.sp, dark),
    titleMedium = serifStyle(18.sp, dark),
    titleSmall = serifStyle(15.sp, dark),
    labelMedium = serifLabelStyle(13.sp, dark)
)

private fun serifLabelStyle(size: androidx.compose.ui.unit.TextUnit, dark: Boolean) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = size,
        color = if (dark) NightTextSoft else InkSoft
    )

private fun serifStyle(size: androidx.compose.ui.unit.TextUnit, dark: Boolean) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = size,
        color = if (dark) NightText else Ink
    )

private val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun ShuaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = editorialTypography(darkTheme),
        shapes = PaperShapes,
        content = content
    )
}
