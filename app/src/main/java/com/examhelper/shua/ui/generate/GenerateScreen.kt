package com.examhelper.shua.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.examhelper.shua.ui.bank.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: GenerateViewModel = viewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 生成答案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.bankName, style = MaterialTheme.typography.titleMedium)
                    val noAnswer = state.items.count { it.question.answer.isNullOrBlank() }
                    Text(
                        "共 ${state.items.size} 题，其中 $noAnswer 题无参考答案",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (state.sanSheng)
                            "三省六部模式：主部 ${state.primaryName} + ${state.providers.size - 1} 部交叉验证"
                        else
                            "单模型模式：${state.primaryName ?: "未配置"}（在设置中启用三省六部可多模型交叉验证）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.sanSheng) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (state.running) {
                LinearProgressIndicator(
                    progress = {
                        val total = state.doneCount + state.items.count {
                            it.status == GenerateViewModel.QStatus.PENDING || it.status == GenerateViewModel.QStatus.RUNNING
                        }
                        if (total == 0) 0f else state.doneCount.toFloat() / total
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已处理 ${state.doneCount} · 成功 ${state.okCount} · 失败 ${state.failCount}")
                    OutlinedButton(onClick = vm::stop) { Text("停止") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.start(false) }, modifier = Modifier.weight(1f)) {
                        Text("生成缺失答案（${
                            state.items.count { it.question.answer.isNullOrBlank() }
                        } 题）")
                    }
                    OutlinedButton(onClick = { vm.start(true) }, modifier = Modifier.weight(1f)) {
                        Text("全部重新生成")
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.finished && !state.running) {
                Text(
                    "完成：成功 ${state.okCount}，失败 ${state.failCount}（失败题目可再次点击生成重试）",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.items, key = { it.question.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${typeLabel(item.question.type)} · ${item.question.stem.take(40)}" +
                                        if (item.question.stem.length > 40) "…" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                item.message?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (item.status) {
                                            GenerateViewModel.QStatus.OK -> MaterialTheme.colorScheme.primary
                                            GenerateViewModel.QStatus.FAILED -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                            }
                            Text(
                                when (item.status) {
                                    GenerateViewModel.QStatus.PENDING -> "待生成"
                                    GenerateViewModel.QStatus.RUNNING -> "生成中…"
                                    GenerateViewModel.QStatus.OK -> "✓"
                                    GenerateViewModel.QStatus.FAILED -> "失败"
                                    GenerateViewModel.QStatus.SKIPPED -> "跳过"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (item.status) {
                                    GenerateViewModel.QStatus.OK -> MaterialTheme.colorScheme.primary
                                    GenerateViewModel.QStatus.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
