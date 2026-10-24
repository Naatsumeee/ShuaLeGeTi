package com.examhelper.shua.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.ui.bank.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySetupScreen(
    bankId: Long,
    modeName: String,
    onStart: (filterJson: String?, examCount: Int) -> Unit,
    onBack: () -> Unit
) {
    val vm: StudySetupViewModel = viewModel()
    val state by vm.state.collectAsState()
    val mode = vm.mode

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (mode) {
                                StudyMode.SEQUENTIAL -> "标准做题"
                                StudyMode.SPEED -> "速刷模式"
                                StudyMode.RECITE -> "背题模式"
                                StudyMode.RANDOM -> "随机巩固"
                                StudyMode.EXAM -> "测试模式"
                            } + " · 范围选择",
                            style = MaterialTheme.typography.titleMedium
                        )
                        state.bank?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.chapters.isNotEmpty()) {
                Section("章节（不选=全部）")
                ChipFlow(state.chapters.map { it to it }, state.filter.chapters, vm::toggleChapter)
            }

            Section("题型（不选=全部）")
            ChipFlow(
                state.typesPresent.map { it to typeLabel(runCatching { QuestionType.valueOf(it) }.getOrDefault(QuestionType.SINGLE)) },
                state.filter.types,
                vm::toggleType
            )

            if (state.subtypesPresent.isNotEmpty()) {
                Section("医学题型")
                ChipFlow(
                    state.subtypesPresent.map { it to it },
                    state.filter.subtypes,
                    vm::toggleSubtype
                )
            }

            Section("作答状态")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyFilter.STATUS_OPTIONS.forEach { (key, label) ->
                    FilterChip(
                        selected = state.filter.status == key,
                        onClick = { vm.setStatus(key) },
                        label = { Text(label) }
                    )
                }
            }

            if (mode == StudyMode.EXAM) {
                Section("组卷题数")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30, 50, 100, 0).forEach { n ->
                        FilterChip(
                            selected = state.examCount == n,
                            onClick = { vm.setExamCount(n) },
                            label = { Text(if (n == 0) "全部" else "$n 题") }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val fj = state.filter.takeIf { !it.isDefault }?.fingerprint()
                    onStart(fj, state.examCount)
                },
                enabled = state.matchCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (mode == StudyMode.EXAM) "开始测试（${if (state.examCount == 0) state.matchCount else state.examCount.coerceAtMost(state.matchCount)} 题）"
                    else "开始刷题（${state.matchCount} 题）"
                )
            }
            Text(
                "共 ${state.bank?.questionCount ?: 0} 题，当前筛选命中 ${state.matchCount} 题。切换筛选会重置该模式的进度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun <T> ChipFlow(
    items: List<Pair<T, String>>,
    selected: List<T>,
    onToggle: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (value, label) ->
                    FilterChip(
                        selected = value in selected,
                        onClick = { onToggle(value) },
                        label = { Text(label.take(14), maxLines = 1) }
                    )
                }
            }
        }
    }
}
