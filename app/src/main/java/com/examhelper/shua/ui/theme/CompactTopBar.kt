package com.examhelper.shua.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 顶栏统一高度：比旧的 46dp 略高，给放大的衬线标题留出呼吸 */
private val BarHeight = 58.dp

/** 统一圆角矩形（不是胶囊，也不做液态玻璃——实体纸卡） */
private val BarShape = RoundedCornerShape(16.dp)

/**
 * 统一页面顶栏。
 *
 * 视觉规范：
 * - **圆角矩形**（16dp），实心暖纸卡面 + 极细奶油边 + 极轻外阴影；
 * - 与底部 Dock 的液态玻璃刻意区分：这里是"实体纸卡"，不透明、无毛玻璃；
 * - 标题衬线 SemiBold 20sp，**只保留标题本身**，不放副标题说明文字（统计/进度等
 *   信息请用 [BarMeta] 放在栏下的内容区里，保持全站顶栏一致）；
 * - 需配合页面根部的 [safeTopPadding] 使用（本组件只管视觉，不管状态栏内边距）。
 */
@Composable
fun CompactTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val extra = shuaColors
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 3.dp,
                    shape = BarShape,
                    clip = false,
                    ambientColor = Color(0x143B2E18),
                    spotColor = Color(0x1A322A1A)
                )
                .clip(BarShape)
                .background(MaterialTheme.colorScheme.surface)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (extra.isDark) 0.04f else 0.5f),
                        1f to Color.Transparent
                    )
                )
                .border(1.dp, extra.creamLine, BarShape)
                .height(BarHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                Box(Modifier.size(14.dp))
            }
            Text(
                title,
                style = serifTitle(21.sp, FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            actions()
            Box(Modifier.size(10.dp))
        }
    }
}

/**
 * 顶栏下的一行元信息（统计 / 进度 / 计时）：从旧副标题里搬出来的必要信息，
 * 放在内容区首行，左右内边距与卡片对齐。
 */
@Composable
fun BarMeta(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp).then(modifier)) { content() }
}

/** 常用形态：一行等宽小字 */
@Composable
fun BarMetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = monoStyle(12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
