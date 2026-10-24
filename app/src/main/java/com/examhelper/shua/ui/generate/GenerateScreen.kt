package com.examhelper.shua.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.examhelper.shua.data.db.GenerateJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: GenerateViewModel = viewModel()
    val state by vm.state.collectAsState()
    val job = state.job

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
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.bankName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "答案覆盖率 ${state.answerCoverage}%（缺 ${state.noAnswer} 题） · 解析覆盖率 ${state.analysisCoverage}%（缺 ${state.noAnalysis} 题）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (state.sanSheng)
                            "三省六部：主部 ${state.primaryName} + ${state.providers - 1} 部交叉验证"
                        else
                            "单模型：${state.primaryName ?: "未配置"} · 每批 ${com.examhelper.shua.data.generate.AnswerGenerator.BATCH_SIZE} 题 × ${state.concurrency} 批并发",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.sanSheng) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 任务状态
            if (job != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (job.stage) {
                                    "RUNNING" -> "后台生成中（可切出应用，不会中断）"
                                    "DONE" -> "已完成"
                                    "FAILED" -> "生成失败"
                                    "CANCELED" -> "已取消（进度保留）"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (job.stage == "RUNNING") {
                                OutlinedButton(onClick = vm::cancel) { Text("停止") }
                            }
                        }
                        if (job.total > 0) {
                            LinearProgressIndicator(
                                progress = { job.done.toFloat() / job.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            "进度 ${job.done}/${job.total} · 成功 ${job.ok} · 失败 ${job.fail}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        job.message?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        if (job.cacheHitTokens + job.cacheMissTokens > 0) {
                            val total = job.cacheHitTokens + job.cacheMissTokens
                            Text(
                                "token 用量：输入命中 ${job.cacheHitTokens}（${job.cacheHitTokens * 100 / total}%）· 输入未命中 ${job.cacheMissTokens} · 输出 ${job.outputTokens}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        job.errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        // 生成过程日志
                        if (state.log.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("生成过程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                state.log.takeLast(6).forEach { line ->
                                    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.start(false) },
                    enabled = !state.running && state.noAnswer > 0 && state.providers > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("生成缺失（${state.noAnswer} 题）") }
                OutlinedButton(
                    onClick = { vm.start(true) },
                    enabled = !state.running && state.providers > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("全部重新生成") }
            }
            Text(
                "已生成过答案的题目会自动跳过，中断后点\"生成缺失\"从断点继续，不重复计费。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
