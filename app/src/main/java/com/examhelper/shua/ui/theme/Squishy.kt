package com.examhelper.shua.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 主按钮的「物理按压」包装：按下 scale 0.96，松开用轻微超调弹簧回到 1。
 *
 * 阴影刻意画在内容内侧（内阴影），不往外扩散：深色外阴影在暖纸上会像脏边，
 * 内阴影更像按钮被按进纸面。
 */
@Composable
fun SquishyButtonSurface(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    modifier: Modifier = Modifier,
    shadowColor: Color = Color(0x55000000),
    content: @Composable BoxScope.() -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            scale.animateTo(0.96f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium))
        } else {
            scale.animateTo(
                1f,
                spring(dampingRatio = 0.46f, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }
    Box(
        modifier = modifier
            .clip(shape)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                // 只做很小的抬升阴影，释放时随 scale 回弹
                shadowElevation = (1f - scale.value) * 8f
                ambientShadowColor = shadowColor
                spotShadowColor = shadowColor
            }
            .drawWithContent {
                drawContent()
                // 顶部/左上内阴影，模拟按下去时边缘陷进纸面
                drawRect(
                    brush = Brush.linearGradient(
                        0f to shadowColor,
                        0.22f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to shadowColor.copy(alpha = shadowColor.alpha * 0.7f),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
            },
        content = content
    )
}
