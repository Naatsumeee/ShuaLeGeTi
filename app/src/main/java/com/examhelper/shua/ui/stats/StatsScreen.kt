package com.examhelper.shua.ui.stats

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ShuaApp).container
    private val recordDao = container.database.recordDao()
    private val questionDao = container.database.questionDao()
    private val bankDao = container.database.bankDao()

    data class StatsUi(
        val todayCount: Int = 0,
        val totalCount: Int = 0,
        val todayDurationMs: Long = 0L,
        val totalDurationMs: Long = 0L,
        val perBank: List<Triple<String, Int, Int>> = emptyList()
    )

    data class DailyWrongItem(
        val question: Question,
        val bankName: String
    )

    data class DailyStatsUi(
        val dayKey: String = dayKey(System.currentTimeMillis()),
        val isToday: Boolean = true,
        val attemptCount: Int = 0,
        val knownCount: Int = 0,
        val correctCount: Int = 0,
        val wrongQuestions: List<DailyWrongItem> = emptyList()
    )

    private val _selectedDay = MutableStateFlow(dayKey(System.currentTimeMillis()))
    val selectedDay: StateFlow<String> = _selectedDay

    fun selectDay(dayKey: String) {
        _selectedDay.value = dayKey
    }

    val heat: StateFlow<Map<String, Int>> = recordDao
        .observeTimestampsSince(System.currentTimeMillis() - 224L * 24 * 3600 * 1000)
        .map { list -> list.groupBy { dayKey(it) }.mapValues { it.value.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val perBank = combine(recordDao.observeBankStats(), bankDao.observeAll()) { stats, banks ->
        val nameById = banks.associate { it.id to it.name }
        stats.map { Triple(nameById[it.bankId] ?: "已删除题库", it.cnt, it.ok) }
            .sortedByDescending { it.second }
    }

    val ui: StateFlow<StatsUi> = combine(
        recordDao.observeAttemptCountSince(startOfToday()),
        recordDao.observeTotalAttempts(),
        recordDao.observeDayDurationMs(dayKey(System.currentTimeMillis())),
        recordDao.observeTotalDurationMs(),
        perBank
    ) { today, total, todayDur, totalDur, banks ->
        StatsUi(
            todayCount = today,
            totalCount = total,
            todayDurationMs = todayDur,
            totalDurationMs = totalDur,
            perBank = banks
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUi())

    val daily: StateFlow<DailyStatsUi> = _selectedDay
        .flatMapLatest { key ->
            val (startMs, endMs) = dayRange(key)
            combine(
                recordDao.observeAttemptsInRange(startMs, endMs),
                bankDao.observeAll()
            ) { attempts, banks ->
                val bankNames = banks.associate { it.id to it.name }
                val known = attempts.filter { it.correctKnown }
                val wrongIds = known.filter { !it.correct }.map { it.questionId }.distinct()
                val questionsById = if (wrongIds.isEmpty()) {
                    emptyMap()
                } else {
                    questionDao.getByIds(wrongIds).associateBy { it.id }
                }
                val wrongItems = wrongIds.mapNotNull { qid ->
                    questionsById[qid]?.let { q ->
                        DailyWrongItem(
                            question = q,
                            bankName = bankNames[q.bankId] ?: "已删除题库"
                        )
                    }
                }
                DailyStatsUi(
                    dayKey = key,
                    isToday = key == dayKey(System.currentTimeMillis()),
                    attemptCount = attempts.size,
                    knownCount = known.size,
                    correctCount = known.count { it.correct },
                    wrongQuestions = wrongItems
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyStatsUi())

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

        fun dayRange(key: String): Pair<Long, Long> {
            val parts = key.split('-')
            if (parts.size != 3) {
                val start = startOfToday()
                return start to (start + 24L * 3600 * 1000)
            }
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, parts[0].toIntOrNull() ?: get(java.util.Calendar.YEAR))
                set(java.util.Calendar.MONTH, (parts[1].toIntOrNull() ?: 1) - 1)
                set(java.util.Calendar.DAY_OF_MONTH, parts[2].toIntOrNull() ?: 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return start to cal.timeInMillis
        }

        fun formatDurationMinutes(ms: Long): String {
            if (ms <= 0L) return "0"
            return ((ms + 30_000L) / 60_000L).coerceAtLeast(1L).toString()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onStartStudy: (bankId: Long, mode: StudyMode, filterJson: String) -> Unit = { _, _, _ -> },
    onOpenQuestion: (Long) -> Unit = {}
) {
    val vm: StatsViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val heat by vm.heat.collectAsState()
    val selectedDay by vm.selectedDay.collectAsState()
    val daily by vm.daily.collectAsState()
    var heatWeeks by remember { mutableIntStateOf(8) }
    var showDailyWrongSheet by remember { mutableStateOf(false) }

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
                    StatCard("今日刷题数", "${ui.todayCount}", "题", Modifier.weight(1f))
                    StatCard("累计刷题数", "${ui.totalCount}", "题", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        "今日时长",
                        StatsViewModel.formatDurationMinutes(ui.todayDurationMs),
                        "分钟",
                        Modifier.weight(1f)
                    )
                    StatCard(
                        "累计时长",
                        StatsViewModel.formatDurationMinutes(ui.totalDurationMs),
                        "分钟",
                        Modifier.weight(1f),
                        accent = true
                    )
                }
            }
            item { SectionLabel("刷题热力图", trailing = "近 $heatWeeks 周") }
            item {
                Heatmap(
                    heat = heat,
                    weeks = heatWeeks,
                    selectedDay = selectedDay,
                    onWeeksChange = { heatWeeks = it },
                    onSelectDay = vm::selectDay
                )
            }
            item {
                DailyStatsCard(
                    daily = daily,
                    onViewWrong = { showDailyWrongSheet = true }
                )
            }
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

    if (showDailyWrongSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDailyWrongSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            DailyWrongSheetContent(
                daily = daily,
                onOpenQuestion = { qid ->
                    showDailyWrongSheet = false
                    onOpenQuestion(qid)
                },
                onRebrushAll = {
                    val ids = daily.wrongQuestions.map { it.question.id }
                    if (ids.isNotEmpty()) {
                        showDailyWrongSheet = false
                        val filterJson = StudyFilter(questionIds = ids, freshAnswer = true).fingerprint()
                        onStartStudy(0L, StudyMode.SEQUENTIAL, filterJson)
                    }
                }
            )
        }
    }
}

@Composable
private fun DailyStatsCard(
    daily: StatsViewModel.DailyStatsUi,
    onViewWrong: () -> Unit
) {
    val extra = shuaColors
    val rateText = if (daily.knownCount == 0) "—" else "${daily.correctCount * 100 / daily.knownCount}%"
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "每日数据",
                    style = serifTitle(15.sp, FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                MonoText(
                    if (daily.isToday) "${daily.dayKey} · 今天" else daily.dayKey,
                    size = 12.sp,
                    color = extra.terracotta
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "做题数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${daily.attemptCount} 题",
                        style = serifTitle(20.sp, FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "正确率",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        rateText,
                        style = serifTitle(20.sp, FontWeight.Medium),
                        color = if (daily.knownCount > 0) extra.terracotta else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            OutlinedButton(
                onClick = onViewWrong,
                enabled = daily.wrongQuestions.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (daily.wrongQuestions.isEmpty()) "当日无错题"
                    else "查看当日错题（${daily.wrongQuestions.size} 题）"
                )
            }
        }
    }
}

@Composable
private fun DailyWrongSheetContent(
    daily: StatsViewModel.DailyStatsUi,
    onOpenQuestion: (Long) -> Unit,
    onRebrushAll: () -> Unit
) {
    val extra = shuaColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "当日错题（${daily.wrongQuestions.size} 题）",
                    style = serifTitle(18.sp, FontWeight.Medium)
                )
                Text(
                    daily.dayKey,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onRebrushAll,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("一键重刷")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(daily.wrongQuestions, key = { it.question.id }) { item ->
                PaperCard(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenQuestion(item.question.id) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SemanticChip(typeLabel(item.question.type), ChipTone.Brand)
                            Text(
                                item.bankName,
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.terracotta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Latex.LatexText(
                            text = item.question.stem,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            allowDisplay = false
                        )
                    }
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

/** 一次选中：日期、题数、以及该格在热力图容器坐标系中的中心位置 */
private data class HeatPick(val key: String, val count: Int, val centerX: Float, val centerY: Float)

@Composable
private fun Heatmap(
    heat: Map<String, Int>,
    weeks: Int,
    selectedDay: String,
    onWeeksChange: (Int) -> Unit,
    onSelectDay: (String) -> Unit
) {
    val extra = shuaColors
    var activePick by remember { mutableStateOf<HeatPick?>(null) }
    var lastPick by remember { mutableStateOf<HeatPick?>(null) }
    val scope = rememberCoroutineScope()
    var hideJob by remember { mutableStateOf<Job?>(null) }

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
                            .clickable {
                                onWeeksChange(w)
                                hideJob?.cancel()
                                activePick = null
                            }
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
                // 8 周缩小约 28%（26dp 上限），避免切换 16/32 周时视觉落差过大
                val maxCell = when (totalWeeks) {
                    8 -> 26.dp
                    16 -> 20.dp
                    else -> 16.dp
                }
                val cellSize = fitted.coerceIn(11.dp, maxCell)
                val gridTotalW = cellSize * totalWeeks + gap * (totalWeeks - 1)
                val density = LocalDensity.current
                val stepPx = with(density) { (cellSize + gap).toPx() }
                val cellPx = with(density) { cellSize.toPx() }
                val hostW = with(density) { avail.toPx() }
                val startPadPx = if (gridTotalW < avail) with(density) { ((avail - gridTotalW) / 2f).toPx() } else 0f
                val diagonalShiftPx = with(density) { 26.dp.toPx() }
                val gapPx = with(density) { 10.dp.toPx() }
                val scroll = rememberScrollState()

                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(stepPx, cellPx, startPadPx, grid) {
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downPos = down.position
                                val downTime = System.currentTimeMillis()
                                var draggingLongPress = false
                                var lastHitKey: String? = null

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                    if (!draggingLongPress) {
                                        if (!change.pressed) {
                                            // 短按释放：切换下方每日数据日期
                                            if ((change.position - downPos).getDistance() <= touchSlop) {
                                                pick(change.position, stepPx, cellPx, scroll.value, startPadPx, grid)?.let { hit ->
                                                    Haptics.tick()
                                                    onSelectDay(hit.key)
                                                }
                                            }
                                            break
                                        }
                                        if (change.isConsumed || (change.position - downPos).getDistance() > touchSlop) {
                                            // 交给横向滚动
                                            break
                                        }
                                        if (System.currentTimeMillis() - downTime >= longPressTimeout) {
                                            draggingLongPress = true
                                            hideJob?.cancel()
                                            change.consume()
                                            Haptics.longPress()
                                            pick(change.position, stepPx, cellPx, scroll.value, startPadPx, grid)?.let { hit ->
                                                lastHitKey = hit.key
                                                activePick = hit
                                                lastPick = hit
                                                onSelectDay(hit.key)
                                            }
                                        }
                                    } else {
                                        if (!change.pressed) {
                                            // 松手延迟 0.5s 消失
                                            hideJob?.cancel()
                                            hideJob = scope.launch {
                                                delay(500)
                                                activePick = null
                                            }
                                            break
                                        }
                                        change.consume()
                                        pick(change.position, stepPx, cellPx, scroll.value, startPadPx, grid)?.let { hit ->
                                            if (hit.key != lastHitKey) {
                                                lastHitKey = hit.key
                                                Haptics.tick()
                                                onSelectDay(hit.key)
                                            }
                                            activePick = hit
                                            lastPick = hit
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scroll),
                        contentAlignment = if (gridTotalW < avail) Alignment.Center else Alignment.CenterStart
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
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
                                                    if (key == selectedDay)
                                                        Modifier.border(1.5.dp, extra.terracottaDeep, RoundedCornerShape(4.dp))
                                                    else Modifier
                                                )
                                                .semantics { contentDescription = "$key 刷了 $count 题" }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val displayPick = activePick ?: lastPick
                    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
                    val targetOffset = if (displayPick != null) {
                        val bubbleH = bubbleSize.height
                        val bubbleW = bubbleSize.width
                        // 斜向错开避让手指：靠左往右上偏，靠右往左上偏
                        val shiftX = if (displayPick.centerX < hostW / 2f) diagonalShiftPx else -diagonalShiftPx
                        val bx = (displayPick.centerX + shiftX - bubbleW / 2f)
                            .coerceIn(0f, (hostW - bubbleW).coerceAtLeast(0f))
                        val aboveY = displayPick.centerY - cellPx * 0.5f - bubbleH - gapPx
                        val by = if (aboveY >= 0f) aboveY else displayPick.centerY + cellPx * 0.5f + gapPx
                        IntOffset(bx.toInt(), by.toInt())
                    } else {
                        IntOffset.Zero
                    }
                    val animatedOffset by animateIntOffsetAsState(
                        targetValue = targetOffset,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
                        label = "heatBubbleMagnet"
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = activePick != null,
                        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.88f),
                        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { animatedOffset }
                    ) {
                        displayPick?.let { p ->
                            HeatBubble(
                                key = p.key,
                                count = p.count,
                                modifier = Modifier.onSizeChanged { bubbleSize = it }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "单击选日期 · 长按滑动预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                listOf(bg, light, mid, primary).forEach { c ->
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 按下点反查命中的格子，并返回该格中心在容器内的坐标（磁吸锚点） */
private fun pick(
    off: Offset,
    stepPx: Float,
    cellPx: Float,
    scrollX: Int,
    startPadPx: Float,
    grid: List<List<Pair<String, Int>>>
): HeatPick? {
    if (stepPx <= 0f) return null
    val localX = off.x + scrollX - startPadPx
    if (localX < 0f || off.y < 0f) return null
    val ci = (localX / stepPx).toInt()
    val ri = (off.y / stepPx).toInt()
    if (ci !in grid.indices || ri !in 0..6) return null
    val (key, count) = grid[ci][ri]
    val centerX = startPadPx - scrollX + ci * stepPx + cellPx / 2f
    val centerY = ri * stepPx + cellPx / 2f
    return HeatPick(key, count, centerX, centerY)
}

/** 跟随手指的磁吸日期气泡：赤陶深底白字 */
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
                if (count == 0) "未刷题" else "刷了 $count 道题目",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
