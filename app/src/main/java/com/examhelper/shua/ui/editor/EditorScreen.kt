package com.examhelper.shua.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.ui.bank.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: EditorViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("编辑 · 章节划分", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.bankName} · ${state.questions.size} 题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = vm::openBatchDialog) { Text("按范围分章") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.loading) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // 章节概况
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("章节概况", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.chapterSummary.joinToString("　") { "${it.first.take(12)}(${it.second})" }
                            .ifBlank { "全部题目未分章" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    state.pageRange?.let {
                        Text(
                            "原文页码范围：P${it.first} – P${it.last}（可用于按页分章）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(state.questions, key = { _, q -> q.id }) { i, q ->
                    Card(onClick = { vm.openEdit(q.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.padding(4.dp))
                            q.sourcePage?.let {
                                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                    Text("P$it", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.padding(4.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    q.stem.take(36) + if (q.stem.length > 36) "…" else "",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1
                                )
                                Text(
                                    listOfNotNull(
                                        typeLabel(q.type) + (q.subtype?.let { "·$it" } ?: ""),
                                        q.chapter?.take(10) ?: "未分章"
                                    ).joinToString(" | "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text("编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (state.showBatchDialog) {
        BatchChapterDialog(
            total = state.questions.size,
            pageRange = state.pageRange,
            firstPages = listOfNotNull(
                state.questions.firstOrNull()?.sourcePage,
                state.questions.getOrNull(state.questions.size / 2)?.sourcePage,
                state.questions.lastOrNull()?.sourcePage
            ),
            onDismiss = vm::closeBatchDialog,
            onByIndex = { f, t, n -> vm.assignByIndex(f, t, n) },
            onByPage = { f, t, n -> vm.assignByPage(f, t, n) }
        )
    }

    state.editing?.let { q ->
        QuestionEditDialog(q = q, onDismiss = vm::closeEdit, onSave = vm::saveQuestion)
    }
}

@Composable
private fun BatchChapterDialog(
    total: Int,
    pageRange: IntRange?,
    firstPages: List<Int>,
    onDismiss: () -> Unit,
    onByIndex: (Int, Int, String) -> Unit,
    onByPage: (Int, Int, String) -> Unit
) {
    var byPage by remember { mutableStateOf(pageRange != null) }
    var name by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(total.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按范围划分章节") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byPage, onClick = { byPage = false }, label = { Text("按题号") })
                    FilterChip(selected = byPage, onClick = { byPage = true }, enabled = pageRange != null, label = { Text("按页码") })
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("章节名（如：第二章 伦理原则）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from, onValueChange = { from = it }, singleLine = true,
                        label = { Text(if (byPage) "起页" else "起题号") }, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = to, onValueChange = { to = it }, singleLine = true,
                        label = { Text(if (byPage) "止页" else "止题号") }, modifier = Modifier.weight(1f)
                    )
                }
                if (!byPage) {
                    Text("共 $total 题，首题 P${firstPages.getOrElse(0) { "-" }} / 中部 P${firstPages.getOrElse(1) { "-" }} / 末题 P${firstPages.getOrElse(2) { "-" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    Text("页码范围 P${pageRange?.first} – P${pageRange?.last}（依据导入时记录的原文页码）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val f = from.toIntOrNull() ?: return@Button
                val t = to.toIntOrNull() ?: return@Button
                if (byPage) onByPage(f, t, name) else onByIndex(f, t, name)
            }, enabled = name.isNotBlank()) { Text("划分") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun QuestionEditDialog(
    q: Question,
    onDismiss: () -> Unit,
    onSave: (String?, QuestionType, String?, String, List<QuestionOption>, String?, String?) -> Unit
) {
    var chapter by remember { mutableStateOf(q.chapter.orEmpty()) }
    var type by remember { mutableStateOf(q.type) }
    var subtype by remember { mutableStateOf(q.subtype.orEmpty()) }
    var stem by remember { mutableStateOf(q.stem) }
    var options by remember { mutableStateOf(JsonCodec.decodeOptions(q.optionsJson)) }
    var answer by remember { mutableStateOf(q.answer.orEmpty()) }
    var analysis by remember { mutableStateOf(q.analysis.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑题目") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = chapter, onValueChange = { chapter = it }, label = { Text("章节") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column {
                        Text("题型", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                QuestionType.SINGLE to "单选", QuestionType.MULTIPLE to "多选",
                                QuestionType.JUDGE to "判断", QuestionType.FILL to "填空",
                                QuestionType.SHORT_ANSWER to "简答"
                            ).forEach { (t, label) ->
                                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("子类型:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                    listOf("", "A1", "A2", "A3", "B").forEach { s ->
                        FilterChip(selected = subtype == s, onClick = { subtype = s }, label = { Text(s.ifBlank { "无" }) })
                    }
                }
                OutlinedTextField(value = stem, onValueChange = { stem = it }, label = { Text("题干") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (options.isNotEmpty()) {
                    Text("选项", style = MaterialTheme.typography.labelSmall)
                    options.forEachIndexed { i, opt ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(opt.key, style = MaterialTheme.typography.labelLarge)
                            OutlinedTextField(
                                value = opt.text, onValueChange = { v ->
                                    options = options.toMutableList().also { l -> l[i] = opt.copy(text = v) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { options = options.filterIndexed { idx, _ -> idx != i } }) { Text("删") }
                        }
                    }
                }
                if (type == QuestionType.SINGLE || type == QuestionType.MULTIPLE || type == QuestionType.JUDGE) {
                    TextButton(onClick = {
                        val nextKey = ('A'..'H').firstOrNull { c -> options.none { it.key == c.toString() } }?.toString() ?: "X"
                        options = options + QuestionOption(nextKey, "")
                    }) { Text("+ 添加选项") }
                }
                OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("答案（如 B / ABD / 对 / 文本）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = analysis, onValueChange = { analysis = it }, label = { Text("解析") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(chapter, type, subtype.ifBlank { null }, stem, options, answer, analysis)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
