package com.examhelper.shua.ui.stats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ShuaApp).container
    private val recordDao = container.database.recordDao()
    private val bankDao = container.database.bankDao()

    data class StatsUi(
        val todayCount: Int = 0,
        val todayCorrect: Int = 0,
        val totalCount: Int = 0,
        val totalCorrect: Int = 0,
        val perBank: List<Triple<String, Int, Int>> = emptyList()
    )

    private val _heat = MutableStateFlow<Map<String, Int>>(emptyMap())
    val heat: StateFlow<Map<String, Int>> = _heat

    val ui = combine(
        recordDao.observeAttemptCountSince(startOfToday()),
        recordDao.observeCorrectCountSince(startOfToday()),
        recordDao.observeTotalAttempts(),
        recordDao.observeTotalCorrect(),
        combine(recordDao.observeBankStats(), bankDao.observeAll()) { stats, banks ->
            val nameById = banks.associate { it.id to it.name }
            stats.map { Triple(nameById[it.bankId] ?: "已删除题库", it.cnt, it.ok) }
                .sortedByDescending { it.second }
        }
    ) { today, todayOk, total, totalOk, perBank ->
        StatsUi(today, todayOk, total, totalOk, perBank)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUi())

    init {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 112L * 24 * 3600 * 1000
            _heat.value = recordDao.timestampsSince(since)
                .groupBy { dayKey(it) }
                .mapValues { it.value.size }
        }
    }

    companion object {
        private fun startOfToday(): Long {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun dayKey(millis: Long): String {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = millis
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}

@Composable
fun StatsScreen(contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val vm: StatsViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val heat by vm.heat.collectAsState()
    var heatWeeks by remember { mutableIntStateOf(8) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding() + 4.dp)
    ) {
        CompactTopBar(title = "刷题统计")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("今日刷题", "${ui.todayCount}", "题", Modifier.weight(1f))
                    StatCard(
                        "今日正确率",
                        if (ui.todayCount == 0) "—" else "${ui.todayCorrect * 100 / ui.todayCount}",
                        "%",
                        Modifier.weight(1f),
                        accent = true
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("累计刷题", "${ui.totalCount}", "题", Modifier.weight(1f))
                    StatCard(
                        "累计正确率",
                        if (ui.totalCount == 0) "—" else "${ui.totalCorrect * 100 / ui.totalCount}",
                        "%",
                        Modifier.weight(1f),
                        accent = true
                    )
                }
            }
            item { SectionLabel("刷题热力图", trailing = "近 $heatWeeks 周") }
            item { Heatmap(heat, heatWeeks) { heatWeeks = it } }
            item { SectionLabel("各题库", trailing = "${ui.perBank.size} 个") }
            if (ui.perBank.isEmpty()) {
                item {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Text(
                            "还没有刷题记录",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(ui.perBank) { (name, cnt, ok) ->
                    BankStatRow(name, cnt, ok)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false
) {
    val extra = shuaColors
    PaperCard(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = serifTitle(30.sp, FontWeight.Medium),
                    color = if (accent) extra.terracotta else MaterialTheme.colorScheme.onSurface
                )
                if (value != "—") {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (accent) extra.terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BankStatRow(name: String, cnt: Int, ok: Int) {
    val extra = shuaColors
    val rate = if (cnt == 0) 0 else ok * 100 / cnt
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    Modifier.weight(1f),
                    style = serifTitle(14.sp, FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                MonoText("$cnt 题")
            }
            // 正确率进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(rate / 100f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(extra.terracotta)
                )
            }
            Row {
                Text(
                    "正确 $ok / $cnt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                MonoText("$rate%", color = extra.terracotta)
            }
        }
    }
}

/** 热力图时间范围（周） */
private val HEAT_RANGES = listOf(8 to "近 8 周", 16 to "近 16 周", 32 to "近 32 周")

/** 刷题热力图（暖纸格子墙）：长按/点按格子在手指上方弹出悬浮气泡，可切换时间范围 */

/** 一次选中：日期、题数、以及按下点在热力图容器坐标系中的位置 */
private class HeatPick(val key: String, val count: Int, val x: Float, val y: Float)

@Composable
private fun Heatmap(heat: Map<String, Int>, weeks: Int, onWeeksChange: (Int) -> Unit) {
    val extra = shuaColors
    var picked by remember { mutableStateOf<HeatPick?>(null) }

    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HEAT_RANGES.forEach { (w, label) ->
                    val selected = weeks == w
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (selected) extra.terracotta.copy(alpha = 0.14f) else Color.Transparent
                            )
                            .clickable { onWeeksChange(w); picked = null }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) extra.terracotta else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // 以本周为最后一列，往前推 (weeks-1) 周，保证包含今天
            val startWeek = java.util.Calendar.getInstance()
            while (startWeek.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
                startWeek.add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            startWeek.add(java.util.Calendar.DAY_OF_YEAR, -((weeks - 1) * 7))
            val primary = extra.terracotta
            val light = primary.copy(alpha = 0.30f)
            val mid = primary.copy(alpha = 0.62f)
            val bg = MaterialTheme.colorScheme.surfaceVariant
            val gap = 3.dp
            val totalWeeks = weeks

            // 先把 7×weeks 的日期与题数算好：手势命中测试要靠下标反查，不能依赖逐格回调
            val grid: List<List<Pair<String, Int>>> = remember(weeks, heat) {
                val c = startWeek.clone() as java.util.Calendar
                List(totalWeeks) {
                    List(7) {
                        val k = StatsViewModel.dayKey(c.timeInMillis)
                        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        k to (heat[k] ?: 0)
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val avail = maxWidth
                val fitted = (avail - gap * (totalWeeks - 1)) / totalWeeks
                // 8 周时铺满整行（约 36dp），周数越多越小，最小 11dp 后改为横向滚动
                val cellSize = fitted.coerceIn(11.dp, 36.dp)
                val density = LocalDensity.current
                val stepPx = with(density) { (cellSize + gap).toPx() }
                val cellPx = with(density) { cellSize.toPx() }
                val hostW = with(density) { avail.toPx() }
                val gapPx = with(density) { 12.dp.toPx() }
                val scroll = rememberScrollState()
                val pickedKey = picked?.key

                // 手势挂在容器上而不是每格上：这样横向滚动照常工作，
                // 又能直接拿到按下点在容器内的坐标，气泡才能贴着手指显示
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(stepPx, grid) {
                            detectTapGestures(
                                onTap = { off -> pick(off, stepPx, scroll.value, grid)?.let { picked = it } },
                                onLongPress = { off -> pick(off, stepPx, scroll.value, grid)?.let { picked = it } }
                            )
                        }
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(scroll),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        grid.forEach { column ->
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                column.forEach { (key, count) ->
                                    Box(
                                        Modifier
                                            .size(cellSize)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                when {
                                                    count == 0 -> bg
                                                    count < 10 -> light
                                                    count < 40 -> mid
                                                    else -> primary
                                                }
                                            )
                                            .then(
                                                if (key == pickedKey)
                                                    Modifier.border(1.5.dp, extra.terracottaDeep, RoundedCornerShape(4.dp))
                                                else Modifier
                                            )
                                            .semantics { contentDescription = "$key 刷了 $count 题" }
                                    )
                                }
                            }
                        }
                    }

                    val p = picked
                    if (p != null) {
                        // 气泡自动收起，避免用户要点别的格子时还得先关一次
                        LaunchedEffect(p.key, p.x, p.y) {
                            kotlinx.coroutines.delay(2600)
                            picked = null
                        }
                        var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
                        val bubbleH = bubbleSize.height
                        val bubbleW = bubbleSize.width
                        val above = p.y - bubbleH - gapPx > 0f
                        val bx = (p.x - bubbleW / 2f).coerceIn(0f, (hostW - bubbleW).coerceAtLeast(0f))
                        val by = if (above) p.y - bubbleH - gapPx else p.y + cellPx + gapPx
                        HeatBubble(
                            key = p.key,
                            count = p.count,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .onSizeChanged { bubbleSize = it }
                                .offset { IntOffset(bx.toInt(), by.toInt()) }
                        )
                    }
                }
            }
            // 气泡会自动消失，这里只留一句常驻提示
            Text(
                "长按任意格子查看当天日期与题数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                listOf(bg, light, mid, primary).forEach { c ->
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(c))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 按下点反查命中的格子；横向滚动过的列要加回 scrollX */
private fun pick(
    off: Offset,
    stepPx: Float,
    scrollX: Int,
    grid: List<List<Pair<String, Int>>>
): HeatPick? {
    if (stepPx <= 0f) return null
    val ci = ((off.x + scrollX) / stepPx).toInt()
    val ri = (off.y / stepPx).toInt()
    if (ci !in grid.indices || ri !in 0..6) return null
    val (key, count) = grid[ci][ri]
    return HeatPick(key, count, off.x, off.y)
}

/** 跟随手指的日期气泡：赤陶深底白字，自动在 2.6 秒后收起 */
@Composable
private fun HeatBubble(key: String, count: Int, modifier: Modifier = Modifier) {
    val extra = shuaColors
    val shadowC = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Box(
        modifier
            .shadow(8.dp, RoundedCornerShape(10.dp), ambientColor = shadowC, spotColor = shadowC)
            .clip(RoundedCornerShape(10.dp))
            .background(extra.terracottaDeep)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                key,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = Color.White
            )
            Text(
                if (count == 0) "未刷题" else "刷了 $count 题",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

