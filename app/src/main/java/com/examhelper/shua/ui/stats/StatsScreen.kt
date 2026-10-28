package com.examhelper.shua.ui.stats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            item { SectionLabel("刷题热力图", trailing = "近 16 周") }
            item { Heatmap(heat) }
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

/** 近 16 周刷题热力图（暖纸格子墙） */
@Composable
private fun Heatmap(heat: Map<String, Int>) {
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val startWeek = java.util.Calendar.getInstance()
            startWeek.add(java.util.Calendar.DAY_OF_YEAR, -(16 * 7 - 1))
            while (startWeek.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
                startWeek.add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            val primary = extra.terracotta
            val light = primary.copy(alpha = 0.30f)
            val mid = primary.copy(alpha = 0.62f)
            val bg = MaterialTheme.colorScheme.surfaceVariant
            val cellSize = 12.dp
            val gap = 3.dp
            val weeks = 17
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                val cal = startWeek.clone() as java.util.Calendar
                repeat(weeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(7) {
                            val count = heat[StatsViewModel.dayKey(cal.timeInMillis)] ?: 0
                            Box(
                                Modifier.size(cellSize).clip(RoundedCornerShape(3.dp)).background(
                                    when {
                                        count == 0 -> bg
                                        count < 10 -> light
                                        count < 40 -> mid
                                        else -> primary
                                    }
                                )
                            )
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                listOf(bg, light, mid, primary).forEach { c ->
                    Box(Modifier.size(cellSize).clip(RoundedCornerShape(3.dp)).background(c))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
