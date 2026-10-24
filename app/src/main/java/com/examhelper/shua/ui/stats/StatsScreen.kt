package com.examhelper.shua.ui.stats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val vm: StatsViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val heat by vm.heat.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("刷题统计") }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("今日刷题", "${ui.todayCount}", Modifier.weight(1f))
                    StatCard(
                        "今日正确率",
                        if (ui.todayCount == 0) "—" else "${ui.todayCorrect * 100 / ui.todayCount}%",
                        Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("累计刷题", "${ui.totalCount}", Modifier.weight(1f))
                    StatCard(
                        "累计正确率",
                        if (ui.totalCount == 0) "—" else "${ui.totalCorrect * 100 / ui.totalCount}%",
                        Modifier.weight(1f)
                    )
                }
            }
            item {
                Text("各题库", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Heatmap(heat)
            }
            if (ui.perBank.isEmpty()) {
                item {
                    Text("还没有刷题记录", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                items(ui.perBank) { (name, cnt, ok) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text("$cnt 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(
                                "正确 $ok / $cnt（${if (cnt == 0) 0 else ok * 100 / cnt}%）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 近 16 周刷题热力图（GitHub 格子墙风格） */
@Composable
private fun Heatmap(heat: Map<String, Int>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("刷题热力图（近 16 周）", style = MaterialTheme.typography.titleSmall)
            val startWeek = java.util.Calendar.getInstance()
            startWeek.add(java.util.Calendar.DAY_OF_YEAR, -(16 * 7 - 1))
            while (startWeek.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
                startWeek.add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            val primary = MaterialTheme.colorScheme.primary
            val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            val mid = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            val bg = MaterialTheme.colorScheme.surfaceVariant
            val cellSize = 12.dp
            val gap = 2.dp
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
                                Modifier.size(cellSize).background(
                                    when {
                                        count == 0 -> bg
                                        count < 10 -> light
                                        count < 40 -> mid
                                        else -> primary
                                    },
                                    RoundedCornerShape(2.dp)
                                )
                            )
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                listOf(bg, light, mid, primary).forEach { c ->
                    Box(Modifier.size(cellSize).background(c, RoundedCornerShape(2.dp)))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
