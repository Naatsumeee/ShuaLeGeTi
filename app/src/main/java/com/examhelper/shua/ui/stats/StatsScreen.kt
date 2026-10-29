package com.examhelper.shua.ui.stats

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/** 刷题热力图（暖纸格子墙）：长按/点按格子看当天日期与题数，可切换时间范围 */

@Composable
private fun Heatmap(heat: Map<String, Int>, weeks: Int, onWeeksChange: (Int) -> Unit) {
    val extra = shuaColors
    var picked by remember { mutableStateOf<String?>(null) }

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
            // 图块按可用宽度自适应放大，8 周时铺满整行、32 周时可横向滚动
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val avail = maxWidth
                val fitted = (avail - gap * (totalWeeks - 1)) / totalWeeks
                // 8 周时铺满整行（约 36dp），周数越多越小，最小 11dp 后改为横向滚动
                val cellSize = fitted.coerceIn(11.dp, 36.dp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    val cal = startWeek.clone() as java.util.Calendar
                    repeat(totalWeeks) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            repeat(7) {
                                val key = StatsViewModel.dayKey(cal.timeInMillis)
                                val count = heat[key] ?: 0
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
                                        // 长按与点按都能查看：长按是需求指定的手势，点按顺带可用
                                        .pointerInput(key) {
                                            detectTapGestures(
                                                onTap = { picked = key },
                                                onLongPress = { picked = key }
                                            )
                                        }
                                        .semantics { contentDescription = "$key 刷了 $count 题" }
                                )
                                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }
                        }
                    }
                }
            }
            // 选中日期详情
            val shown = picked
            Text(
                if (shown == null) "长按任意格子查看当天日期与题数" else "$shown · 刷了 ${heat[shown] ?: 0} 题",
                style = MaterialTheme.typography.labelSmall,
                color = if (shown == null) MaterialTheme.colorScheme.outline else extra.terracotta
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
