package com.examhelper.shua.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.repo.StudyRepository
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamReportScreen(
    runId: String,
    onBack: () -> Unit,
    onRedoWrong: ((bankId: Long) -> Unit)? = null
) {
    val vm: ExamReportViewModel = viewModel()
    val report by vm.report.collectAsState()
    var onlyWrong by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测试报告") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        val r = report ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${r.correctCount} / ${r.total}",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "正确率 ${r.correctCount * 100 / r.total.coerceAtLeast(1)}% · 总用时 " +
                                "%d:%02d".format(r.totalDurationMs / 60000, (r.totalDurationMs % 60000) / 1000),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { r.correctCount.toFloat() / r.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (r.chapterStats.size > 1) {
                item { Text("分章节正确率", style = MaterialTheme.typography.titleMedium) }
                items(r.chapterStats) { cs ->
                    Column(Modifier.fillMaxWidth()) {
                        Row {
                            Text(cs.chapter.take(18), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${cs.correct}/${cs.total}", style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { cs.correct.toFloat() / cs.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = onlyWrong,
                        onClick = { onlyWrong = true },
                        label = { Text("错题（${r.items.count { !it.correct }}）") }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = !onlyWrong,
                        onClick = { onlyWrong = false },
                        label = { Text("全部（${r.total}）") }
                    )
                }
            }
            val list = if (onlyWrong) r.items.filter { !it.correct } else r.items
            items(list) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (item.correct) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (item.correct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                item.question.stem.take(50) + if (item.question.stem.length > 50) "…" else "",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "你的答案：${item.userAnswer.ifBlank { "未作答" }}  ·  正确答案：${item.question.answer ?: "无"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.correct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                        item.question.analysis?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回题库") }
                    if (onRedoWrong != null && r.items.any { !it.correct }) {
                        Button(onClick = { onRedoWrong(r.bankId) }, modifier = Modifier.weight(1f)) { Text("错题已入错题本") }
                    }
                }
            }
        }
    }
}
