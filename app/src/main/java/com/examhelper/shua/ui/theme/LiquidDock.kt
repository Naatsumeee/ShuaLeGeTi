package com.examhelper.shua.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.examhelper.shua.ui.sound.Haptics
import kotlin.math.roundToInt

/*
 * 液态玻璃 Dock —— 按 Kyant0/AndroidLiquidGlass 的「连续高光 + 光斑跟随」思路重做。
 *
 * 相对旧实现修掉的三件事：
 *  1. **动画卡顿**：旧版每个 tab 各跑 animateDpAsState + animateFloatAsState，页签变化时
 *     8 条动画同时启动、每帧重组 4 个 Column。新版只维护 **一个** 胶囊滑块位置
 *     （单个 Animatable<Float>），页签本身完全静态，动画期间不重组列表。
 *  2. **浅色无高光**：旧版玻璃填充是 0xB8FFFDF8（白底上等于白，看不出边界）。新版叠
 *     四层：暖白半透底 + 顶部镜面白带 + 斜向反射光 + 1dp 渐变白描边，并使用暖赤陶
 *     作为底衬色，白纸上也能读出厚度与高光。
 *  3. **长按滑动切换**：长按 400ms 后胶囊吸附到手指所在格（短促震动），拖动时胶囊连续
 *     跟手、每跨一格给一次轻触感，松手切到该 tab。同一节点上并行挂了 tap 手势，快速
 *     点击仍是普通切换。
 */

data class DockTab(val route: String, val label: String, val icon: ImageVector)

private val DockShape = RoundedCornerShape(26.dp)
private val PillShape = RoundedCornerShape(20.dp)
private val DockHeight = 64.dp

@Composable
fun LiquidDock(
    tabs: List<DockTab>,
    currentRoute: String?,
    bottomInset: Dp,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = shuaColors
    val density = LocalDensity.current
    val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // 手势闭包（pointerInput）只在 key 变化时重建，若直接捕获 currentRoute / onSelect，
    // 拿到的是首次组合时的陈旧值。这里用 rememberUpdatedState 保证读到的永远是最新值。
    val currentRouteState = rememberUpdatedState(currentRoute)
    val onSelectState = rememberUpdatedState(onSelect)

    Box(
        modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                bottom = if (bottomInset > 14.dp) bottomInset else 14.dp
            )
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(DockHeight)
                .shadow(
                    elevation = 16.dp,
                    shape = DockShape,
                    clip = false,
                    ambientColor = Color(0x2B4A3A22),
                    spotColor = Color(0x403A2C18)
                )
                .clip(DockShape)
                // ① 玻璃本体：暖白半透，两端略深形成弧度感
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (extra.isDark) Color(0xFF32322A) else Color(0xFAFFFDF9),
                            if (extra.isDark) Color(0xFF23231E) else Color(0xEAF6F1E5)
                        )
                    )
                )
                // ② 顶部镜面高光带（浅色下这一层才是"玻璃感"的来源）
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (extra.isDark) 0.14f else 0.92f),
                        0.30f to Color.White.copy(alpha = if (extra.isDark) 0.04f else 0.28f),
                        0.62f to Color.Transparent,
                        1f to extra.terracotta.copy(alpha = if (extra.isDark) 0.05f else 0.10f)
                    )
                )
                // ③ 渐变描边：上缘纯白、下缘暖赤陶，模拟凸面折射
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (extra.isDark) 0.26f else 1f),
                            extra.terracotta.copy(alpha = if (extra.isDark) 0.12f else 0.18f),
                            Color.White.copy(alpha = if (extra.isDark) 0.10f else 0.60f)
                        )
                    ),
                    DockShape
                )
        ) {
            val cellW = maxWidth / tabs.size
            val cellPx = with(density) { cellW.toPx() }
            val pillW = cellW - 8.dp

            val pillX = remember { Animatable(0f) }
            // 只维护「当前吸附到第几格」，拖动过程中不再逐帧刷新连续位移：
            // 逐帧刷新会让 4 个 tab 每帧重组，跨多格时肉眼可见掉帧。
            var snapIndex by remember { mutableIntStateOf(-1) }
            val effectiveIndex = if (snapIndex >= 0) snapIndex else selectedIndex
            val target = effectiveIndex * cellPx

            LaunchedEffect(target) {
                // 非线性吸附：带一点回弹的弹簧，跨格时先快后慢地"咬合"到按钮中心
                pillX.animateTo(
                    target,
                    spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
                )
            }

            // 手指当前位置（px），拖动时按"滑到哪一格"离散吸附，而不是连续跟手
            var fingerPx by remember { mutableFloatStateOf(0f) }

            // 胶囊滑块（唯一的动画元素）
            Box(
                Modifier
                    .offset(x = with(density) { pillX.value.toDp() })
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .width(pillW)
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(
                        if (extra.isDark) Color(0xFF3D3D33) else Color(0xFFFFF3EA)
                    )
                    // 斜向镜面反射
                    .background(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = if (extra.isDark) 0.18f else 0.95f),
                            0.45f to Color.White.copy(alpha = if (extra.isDark) 0.04f else 0.25f),
                            1f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(220f, 160f)
                        )
                    )
                    .background(
                        Brush.verticalGradient(
                            0f to extra.terracotta.copy(alpha = 0.22f),
                            1f to extra.terracotta.copy(alpha = 0.05f)
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (extra.isDark) 0.32f else 1f),
                                extra.terracotta.copy(alpha = 0.24f)
                            )
                        ),
                        PillShape
                    )
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DockHeight)
                    // 注意：pointerInput 的 key 必须包含当前路由。否则闭包会一直持有首次
                    // 组合时的 currentRoute（通常是 startDestination="banks"），导致离开题库页
                    // 后点「题库」时 tabs[0].route == 陈旧的 currentRoute 恒为 true，只发震动
                    // 不导航 —— 表现就是「题库 tab 点了没反应」。用 rememberUpdatedState 兜底，
                    // 手势块无需因路由变化而重启，也能读到最新值。
                    .pointerInput(tabs.size, cellPx) {
                        detectTapGestures { offset ->
                            val idx = (offset.x / cellPx).toInt().coerceIn(0, tabs.size - 1)
                            Haptics.tap()
                            if (tabs[idx].route != currentRouteState.value) {
                                onSelectState.value(tabs[idx].route)
                            }
                        }
                    }
                    .pointerInput(tabs.size, cellPx) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                Haptics.longPress()
                                val idx = (offset.x / cellPx).toInt().coerceIn(0, tabs.size - 1)
                                fingerPx = offset.x
                                snapIndex = idx
                                // 长按起手就切到该按钮，符合"滑到哪个就切哪个"的直觉
                                if (tabs[idx].route != currentRouteState.value) {
                                    onSelectState.value(tabs[idx].route)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                fingerPx = (fingerPx + amount.x)
                                    .coerceIn(0f, cellPx * (tabs.size - 1))
                                // 关键：按手指位置四舍五入到最近的按钮格，胶囊离散吸附；
                                // 每跨一格才更新一次状态 + 切页 + 轻触感，避免逐帧重组卡顿。
                                val idx = (fingerPx / cellPx).roundToInt()
                                    .coerceIn(0, tabs.size - 1)
                                if (idx != snapIndex) {
                                    snapIndex = idx
                                    Haptics.tick()
                                    // 需求：滑动到哪个按钮，页面就切到哪个
                                    if (tabs[idx].route != currentRouteState.value) {
                                        onSelectState.value(tabs[idx].route)
                                    }
                                }
                            },
                            onDragEnd = { snapIndex = -1 },
                            onDragCancel = { snapIndex = -1 }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    // 高亮只依赖离散的 effectiveIndex，拖动跨格时才变化一次
                    val highlighted = index == effectiveIndex
                    Column(
                        Modifier
                            .width(cellW)
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(if (highlighted) 21.dp else 19.dp),
                            tint = if (highlighted) extra.terracottaDeep
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (highlighted) extra.terracottaDeep
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
