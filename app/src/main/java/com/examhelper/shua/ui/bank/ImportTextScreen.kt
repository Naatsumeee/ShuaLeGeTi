package com.examhelper.shua.ui.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedQuestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTextScreen(onDone: () -> Unit) {
    val vm: ImportTextViewModel = viewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("粘贴文本导入") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.parsed == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.text,
                    onValueChange = vm::updateText,
                    label = { Text("粘贴题目文本\n支持格式：1.题干 / 1、题干；A.选项；答案：B；解析：…") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = vm::parse,
                    enabled = state.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("解析题目") }
            }

            else -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "解析出 ${state.parsed!!.size} 道题",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.updateText(state.text) }) { Text("重新编辑") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.parsed!!.indices.toList()) { i ->
                        ParsedQuestionCard(i + 1, state.parsed!![i])
                    }
                }
                if (state.importing) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Spacer(Modifier.padding(4.dp))
                        Text("导入中…")
                    }
                } else {
                    Button(
                        onClick = { vm.confirmImport(onDone) },
                        enabled = state.parsed!!.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("确认导入 ${state.parsed!!.size} 题") }
                }
            }
        }
    }
}

@Composable
private fun ParsedQuestionCard(index: Int, q: ParsedQuestion) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(typeLabel(q.type)) })
                Spacer(Modifier.padding(2.dp))
                if (q.answer != null) {
                    AssistChip(onClick = {}, label = { Text("有答案") })
                } else {
                    AssistChip(onClick = {}, label = { Text("无答案·待AI") })
                }
            }
            Text(
                "$index. ${q.stem.take(60)}${if (q.stem.length > 60) "…" else ""}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (q.options.isNotEmpty()) {
                Text(
                    q.options.joinToString("  ") { "${it.key}.${it.text.take(12)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            q.answer?.let {
                Text("答案：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

fun typeLabel(type: QuestionType): String = when (type) {
    QuestionType.SINGLE -> "单选"
    QuestionType.MULTIPLE -> "多选"
    QuestionType.JUDGE -> "判断"
    QuestionType.FILL -> "填空"
    QuestionType.SHORT_ANSWER -> "简答"
}
