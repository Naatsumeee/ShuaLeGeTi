package com.examhelper.shua.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ExamReportViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val runId: String = savedStateHandle.get<String>("runId").orEmpty()

    private val _report = MutableStateFlow<StudyRepository.ExamReport?>(null)
    val report: StateFlow<StudyRepository.ExamReport?> = _report

    init {
        viewModelScope.launch {
            _report.value = ShuaApp.instance.container.studyRepo.examReport(runId)
        }
    }
}

@Composable
fun ExamReportScreen(
    runId: String,
    onBack: () -> Unit,
    onRedoWrong: ((bankId: Long) -> Unit)? = null
) {
    val vm: ExamReportViewModel = viewModel()
    val report by vm.report.collectAsState()
    var onlyWrong by remember { mutableStateOf(true) }
    val extra = shuaColors

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(title = "测试报告", onBack = onBack)

        val r = report
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("报告生成中…") }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), elevation = 10.dp) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${r.correctCount}",
                                style = serifTitle(40.sp, FontWeight.Medium),
                                color = extra.terracotta
                            )
                            Text(
                                " / ${r.total}",
                                style = serifTitle(20.sp, FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            MonoText(
                                "${r.correctCount * 100 / r.total.coerceAtLeast(1)}%",
                                size = 20.sp,
                                color = extra.terracotta
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(r.correctCount.toFloat() / r.total.coerceAtLeast(1))
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(extra.terracotta)
                            )
                        }
                        Text(
                            "总用时 %d:%02d".format(r.totalDurationMs / 60000, (r.totalDurationMs % 60000) / 1000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (r.chapterStats.size > 1) {
                item {
                    Text(
                        "分章节正确率",
                        style = serifTitle(15.sp, FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                item {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            r.chapterStats.forEach { cs ->
                                Column {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            cs.chapter.take(18),
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        MonoText("${cs.correct}/${cs.total}", size = 11.sp, color = extra.inkFaint)
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(cs.correct.toFloat() / cs.total.coerceAtLeast(1))
                                                .height(5.dp)
                                                .clip(CircleShape)
                                                .background(extra.terracotta)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportTab("错题（${r.items.count { !it.correct }}）", onlyWrong) { onlyWrong = true }
                    ReportTab("全部（${r.total}）", !onlyWrong) { onlyWrong = false }
                }
            }

            val list = if (onlyWrong) r.items.filter { !it.correct } else r.items
            if (list.isEmpty()) {
                item {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Text(
                            "没有错题，全部答对了。",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(list) { item ->
                PaperCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(if (item.correct) extra.terracottaSoft else extra.sageSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (item.correct) Icons.Filled.Check else Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = if (item.correct) extra.terracotta else extra.sage
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.question.stem.take(42) + if (item.question.stem.length > 42) "…" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SemanticChip(
                                "你的答案 ${item.userAnswer.ifBlank { "未作答" }}",
                                if (item.correct) ChipTone.Brand else ChipTone.Sage
                            )
                            SemanticChip("正确 ${item.question.answer ?: "无"}", ChipTone.Neutral)
                        }
                        item.question.analysis?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("返回题库", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (onRedoWrong != null && r.items.any { !it.correct }) {
                        Button(
                            onClick = { onRedoWrong(r.bankId) },
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("错题已入错题本") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) extra.terracottaSoft else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) extra.terracotta.copy(alpha = 0.45f) else extra.creamLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) extra.terracottaDeep else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
