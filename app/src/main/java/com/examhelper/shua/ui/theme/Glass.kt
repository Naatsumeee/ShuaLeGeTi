package com.examhelper.shua.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/*
 * 苹果 iOS 液态玻璃（Liquid Glass）实现层。
 *
 * Android 没有真正的"背景模糊"API，这里用 4 层叠加还原视觉：
 *  1. 背后的暖色模糊装饰（API 31+ 用 RenderEffect 真模糊，低版本退化为柔和渐变）
 *  2. 半透明白内衬 fill
 *  3. 顶部到中部的内发光（模拟玻璃倒角的高光衰减）
 *  4. 1px 白色高光描边 + 柔和外阴影
 */

private val GlassShape = RoundedCornerShape(22.dp)

/** API 31+ 才有 RenderEffect 真模糊；低版本退化成半透明填充 */
private val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= 31

/**
 * 液态玻璃容器：毛玻璃 + 半透明白内衬 + 白高光边 + 内发光立体倒角。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    elevation: Dp = 10.dp,
    fillAlpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val extra = shuaColors
    val fill = extra.glassFill.copy(alpha = (extra.glassFill.alpha * fillAlpha).coerceIn(0.35f, 1f))
    var base = modifier
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color(0x2A3B2E18),
            spotColor = Color(0x33322A1A)
        )
        .clip(shape)
        .background(fill)
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (extra.isDark) 0.06f else 0.42f),
                0.45f to Color.Transparent,
                1f to Color.White.copy(alpha = if (extra.isDark) 0.02f else 0.10f)
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0f to extra.glassStroke,
                1f to extra.glassStroke.copy(alpha = 0.18f)
            ),
            shape = shape
        )
    if (onClick != null) base = base.clickable(onClick = onClick)
    Column(modifier = base, content = content)
}

/** 实心暖纸卡片（列表里的常规容器，不做玻璃，避免整页过度发白） */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    container: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = shuaColors.creamLine,
    elevation: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var base = modifier
        .shadow(elevation, shape, clip = false, ambientColor = Color(0x1F3B2E18), spotColor = Color(0x1F322A1A))
        .clip(shape)
        .background(container)
        .border(1.dp, borderColor, shape)
    if (onClick != null) base = base.clickable(onClick = onClick)
    Column(modifier = base, content = content)
}

/** 页面级顶部安全区：防止被安卓状态栏 / 水滴屏 / 摄像头遮挡 */
@Composable
fun Modifier.safeTopPadding(minTop: Dp = 16.dp): Modifier {
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return this.padding(top = if (inset > minTop) inset else minTop)
}

/** 底部安全区：适配全面屏手势横条 */
@Composable
fun Modifier.safeBottomPadding(minBottom: Dp = 14.dp): Modifier {
    val pad = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    return this.padding(bottom = if (pad > minBottom) pad else minBottom)
}

/** 历史别名：顶栏已统一为实体圆角纸卡（见 CompactTopBar），此处保留入口便于迁移 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) = CompactTopBar(title = title, onBack = onBack, actions = actions)

/** 小圆角语义标签：正确 / 错误 / 提示 / 标注 */
@Composable
fun SemanticChip(
    text: String,
    tone: ChipTone = ChipTone.Neutral,
    modifier: Modifier = Modifier
) {
    val extra = shuaColors
    val (bg, fg) = when (tone) {
        ChipTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ChipTone.Brand -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.Sage -> extra.sageSoft to extra.sage
        ChipTone.Amber -> extra.amberSoft to extra.amber
        ChipTone.Verdant -> extra.verdantSoft to extra.verdant
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

enum class ChipTone { Neutral, Brand, Sage, Amber, Verdant }

/** 章节 / 分组小标题：衬线 + 细分隔线 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text,
            style = serifTitle(15.sp, FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 圆形玻璃按钮（免填充的图标动作） */
@Composable
fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, shuaColors.creamLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp), tint = tint)
    }
}

/** 暖纸装饰背景：给玻璃层提供可模糊的底纹 */
@Composable
fun ParchmentBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val extra = shuaColors
    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(
                    extra.terracotta.copy(alpha = if (extra.isDark) 0.06f else 0.05f),
                    Color.Transparent,
                    extra.amber.copy(alpha = if (extra.isDark) 0.04f else 0.03f)
                )
            )
        )
    ) {
        content(this)
    }
}

/** 是否窄屏（<380dp），用于按钮组弹性折行 */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 380

/** 等距数据文本（分数、时长、token 数） */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = 13.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    weight: FontWeight = FontWeight.Medium
) {
    Text(text, modifier = modifier, style = monoStyle(size, weight), color = color)
}
