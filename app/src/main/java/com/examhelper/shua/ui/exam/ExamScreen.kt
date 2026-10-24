package com.examhelper.shua.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(
    bankId: Long,
    onSubmitted: (String) -> Unit,
    onBack: () -> Unit
) {
    val vm: ExamViewModel = viewModel()
    val state by vm.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("测试模式", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第 ${state.index + 1}/${state.questions.size} 题 · 已答 ${state.answeredCount} · " +
                                "%02d:%02d".format(state.elapsedSec / 60, state.elapsedSec % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出")
                    }
                },
                actions = {
                    TextButton(onClick = { showSheet = true }) { Text("答题卡") }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            return@Scaffold
        }
        val q = state.current
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            LinearProgressIndicator(
                progress = { if (state.questions.isEmpty()) 0f else (state.index + 1).toFloat() / state.questions.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (q != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChipLite(typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: ""))
                                q.chapter?.let { AssistChipLite(it.take(12)) }
                            }
                            q.sharedStem?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(q.stem, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    q.sharedOptionsJson?.let {
                        SharedOptionsCard(it)
                    }
                    val options = JsonCodec.decodeOptions(q.optionsJson)
                    val selected = state.selected[q.id] ?: emptySet()
                    when (q.type) {
                        QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE -> {
                            options.forEach { opt ->
                                val isSelected = opt.key in selected
                                Card(
                                    onClick = { vm.onOptionTap(opt.key) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(opt.key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                                        Text(opt.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        QuestionType.FILL, QuestionType.SHORT_ANSWER -> {
                            OutlinedTextField(
                                value = state.textInputs[q.id] ?: "",
                                onValueChange = vm::updateText,
                                label = { Text("作答（交卷后对照参考答案）") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = if (q.type == QuestionType.SHORT_ANSWER) 3 else 1
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::prev, enabled = state.index > 0, modifier = Modifier.weight(1f)) { Text("上一题") }
                if (state.index >= state.questions.size - 1) {
                    Button(onClick = { showConfirm = true }, modifier = Modifier.weight(1f)) { Text("交卷") }
                } else {
                    Button(onClick = vm::next, modifier = Modifier.weight(1f)) { Text("下一题") }
                }
            }
            if (state.index < state.questions.size - 1) {
                TextButton(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("直接交卷") }
            }
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("答题卡（已答 ${state.answeredCount}/${state.questions.size}）", style = MaterialTheme.typography.titleMedium)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        items(state.questions.indices.toList()) { i ->
                            val answered = (state.selected[state.questions[i].id])?.isNotEmpty() == true ||
                                !state.textInputs[state.questions[i].id].isNullOrBlank()
                            val isCurrent = i == state.index
                            Surface(
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    answered -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(4.dp).size(40.dp),
                                onClick = {
                                    vm.jumpTo(i)
                                    showSheet = false
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", color = if (isCurrent) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("交卷") },
                text = {
                    Text("共 ${state.questions.size} 题，已答 ${state.answeredCount}，未答 ${state.questions.size - state.answeredCount}。交卷后立即判分并生成报告。")
                },
                confirmButton = {
                    Button(onClick = {
                        showConfirm = false
                        vm.submit { runId -> onSubmitted(runId) }
                    }) { Text("确认交卷") }
                },
                dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("继续作答") } }
            )
        }
    }
}

@Composable
private fun AssistChipLite(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SharedOptionsCard(sharedOptionsJson: String) {
    val shared = JsonCodec.decodeOptions(sharedOptionsJson)
    if (shared.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("共用选项（B 型题）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            shared.forEach {
                Text("${it.key}. ${it.text}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
