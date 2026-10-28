package com.examhelper.shua.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

/**
 * 做题范围选择页：暖纸卡片 + 玻璃按钮的自适应筛选面板。
 * 窄屏（<380dp）时每个 chip 行自动折行，杜绝横向滚动与文字竖排挤压。
 */
@Composable
fun StudySetupScreen(
    bankId: Long,
    modeName: String,
    onStart: (filterJson: String?, examCount: Int) -> Unit,
    onBack: () -> Unit,
    /** 从「回到上次进度」跳到另一个模式的做题页 */
    onResumeMode: (StudyMode, String?) -> Unit = { m, f -> onStart(f, 0) }
) {
    val vm: StudySetupViewModel = viewModel()
    val state by vm.state.collectAsState()
    val mode = vm.mode
    val extra = shuaColors

    val modeTitle = when (mode) {
        StudyMode.SEQUENTIAL -> "标准做题"
        StudyMode.SPEED -> "速刷模式"
        StudyMode.RECITE -> "背题模式"
        StudyMode.RANDOM -> "随机巩固"
        StudyMode.EXAM -> "测试模式"
    }

    Column(Modifier.fillMaxSize().safeTopPadding()) {
        CompactTopBar(title = "$modeTitle · 范围", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 「回到上次进度」显著置顶：有未做完的进度才出现
            val savedForThis = state.saved.firstOrNull { it.mode == mode }
            val others = state.saved.filter { it.mode != mode }
            if (state.saved.isNotEmpty()) {
                ResumeCard(
                    primary = savedForThis,
                    others = others,
                    currentMode = mode,
                    onResume = { item ->
                        val fj = item.filterJson?.takeIf {
                            StudyFilter.fromFingerprint(it)?.isDefault == false
                        }
                        if (item.mode == mode) onStart(fj, state.examCount)
                        else onResumeMode(item.mode, fj)
                    },
                    onForget = { vm.forget(it) }
                )
            }

            if (state.chapters.isNotEmpty()) {
                FilterGroup(title = "章节", hint = "不选 = 全部") {
                    ChipFlow(state.chapters.map { it to it }, state.filter.chapters, vm::toggleChapter)
                }
            }

            FilterGroup(title = "题型", hint = "不选 = 全部") {
                ChipFlow(
                    state.typesPresent.map {
                        it to typeLabel(
                            runCatching { QuestionType.valueOf(it) }.getOrDefault(QuestionType.SINGLE)
                        )
                    },
                    state.filter.types,
                    vm::toggleType
                )
            }

            if (state.subtypesPresent.isNotEmpty()) {
                FilterGroup(title = "医学题型", hint = "不选 = 全部") {
                    ChipFlow(state.subtypesPresent.map { it to it }, state.filter.subtypes, vm::toggleSubtype)
                }
            }

            FilterGroup(title = "作答状态") {
                ChipFlow(
                    StudyFilter.STATUS_OPTIONS.map { (key, label) -> key to label },
                    listOf(state.filter.status),
                    { vm.setStatus(it) },
                    singleSelect = true
                )
            }

            if (mode == StudyMode.EXAM) {
                FilterGroup(title = "组卷题数") {
                    ChipFlow(
                        listOf(10, 20, 30, 50, 100, 0).map { n -> n to if (n == 0) "全部" else "$n 题" },
                        listOf(state.examCount),
                        { vm.setExamCount(it) },
                        singleSelect = true
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
        }

        StartBar(
            mode = mode,
            matchCount = state.matchCount,
            total = state.bank?.questionCount ?: 0,
            startCount = if (mode == StudyMode.EXAM) {
                if (state.examCount == 0) state.matchCount
                else state.examCount.coerceAtMost(state.matchCount)
            } else state.matchCount,
            onStart = {
                val fj = state.filter.takeIf { !it.isDefault }?.fingerprint()
                onStart(fj, state.examCount)
            }
        )
    }
}

/** 固定在底部的开始条：筛选再长也不用滚回底部找按钮 */
@Composable
private fun StartBar(
    mode: StudyMode,
    matchCount: Int,
    total: Int,
    startCount: Int,
    onStart: () -> Unit
) {
    val extra = shuaColors
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .safeBottomPadding(minBottom = 10.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = 14.dp
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (matchCount > 0) extra.terracotta else extra.creamLineStrong)
                    .clickable(enabled = matchCount > 0) { onStart() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (mode == StudyMode.EXAM) "开始测试 · $startCount 题" else "开始刷题 · $startCount 题",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (matchCount > 0) androidx.compose.ui.graphics.Color.White else extra.inkFaint
                )
            }
            Text(
                "题库共 $total 题，当前筛选命中 $matchCount 题。切换筛选会重置该模式的进度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/**
 * 「回到上次进度」卡片：显著标出，带时间戳；
 * 多条数据时列出可选项，并标出保存的进度在第几题 · 第几章，用户自己挑。
 */
@Composable
private fun ResumeCard(
    primary: StudyRepository.SavedProgress?,
    others: List<StudyRepository.SavedProgress>,
    currentMode: StudyMode,
    onResume: (StudyRepository.SavedProgress) -> Unit,
    onForget: (StudyMode) -> Unit
) {
    val extra = shuaColors
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    val list = listOfNotNull(primary) + others

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(extra.terracottaSoft)
            .border(1.5.dp, extra.terracotta.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(extra.terracotta)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        "上次进度",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (list.size > 1) "共 ${list.size} 条未做完，选一条继续" else "接着上次的地方做",
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.terracottaDeep
                )
            }

            list.forEach { item ->
                val isCurrent = item.mode == currentMode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (isCurrent) extra.terracotta.copy(alpha = 0.6f) else extra.creamLine,
                            RoundedCornerShape(13.dp)
                        )
                        .clickable { onResume(item) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modeLabel(item.mode),
                                style = serifTitle(14.sp, FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isCurrent) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(extra.terracottaSoft)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "当前模式",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = extra.terracottaDeep
                                    )
                                }
                            }
                        }
                        Text(
                            buildString {
                                append("第 ${item.position} / ${item.total} 题")
                                item.chapter?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            fmt.format(java.util.Date(item.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.inkFaint
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(extra.terracotta)
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                "继续",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                        Text(
                            "忽略",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.inkFaint,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onForget(item.mode) }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: StudyMode): String = when (mode) {
    StudyMode.SEQUENTIAL -> "标准做题"
    StudyMode.SPEED -> "速刷模式"
    StudyMode.RECITE -> "背题模式"
    StudyMode.RANDOM -> "随机巩固"
    StudyMode.EXAM -> "测试模式"
}

@Composable
private fun FilterGroup(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = serifTitle(15.sp, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                if (hint != null) {
                    MonoText(hint, size = 10.sp, color = extra.inkFaint)
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * 弹性 chip 流式布局：按可用宽度折行，不产生横向滚动。
 * 用 Compose 的 FlowRow 语义但手写实现，避免额外实验 API 依赖。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(
    items: List<Pair<T, String>>,
    selected: List<T>,
    onToggle: (T) -> Unit,
    singleSelect: Boolean = false
) {
    if (items.isEmpty()) {
        Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    // 流式折行：按可用宽度自动换行，小屏也不会产生横向滚动或文字挤压
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (value, label) ->
            SelectableChip(label = label, selected = value in selected) { onToggle(value) }
        }
    }
}

@Composable
private fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) extra.terracottaSoft else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (selected) extra.terracotta.copy(alpha = 0.55f) else extra.creamLine,
                RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) extra.terracottaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
