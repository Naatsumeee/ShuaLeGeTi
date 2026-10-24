package com.examhelper.shua.ui.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.StudyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankDetailScreen(
    bankId: Long,
    onBack: () -> Unit,
    onStartStudy: (StudyMode) -> Unit,
    onGenerate: () -> Unit,
    onEdit: () -> Unit
) {
    val vm: BankDetailViewModel = viewModel()
    var bank by remember { mutableStateOf<com.examhelper.shua.data.db.QuestionBank?>(null) }
    var noAnswer by remember { mutableStateOf(0) }
    var noAnalysis by remember { mutableStateOf(0) }
    val context = LocalContext.current
    var pendingIncludeAi by remember { mutableStateOf(true) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportStats by remember { mutableStateOf<BankDetailViewModel.ExportStats?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportTo(uri, bankId, pendingIncludeAi, context)
    }

    LaunchedEffect(bankId) {
        bank = vm.loadBank(bankId)
        noAnswer = vm.countNoAnswer(bankId)
        noAnalysis = vm.countNoAnalysis(bankId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bank?.name ?: "题库") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            bank?.let { b ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${b.questionCount} 道题", style = MaterialTheme.typography.titleLarge)
                        if (noAnswer > 0) {
                            Text(
                                "其中 $noAnswer 题无参考答案，$noAnalysis 题无解析，可在\"设置\"配置模型后一键生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = { if (b.questionCount == 0) 0f else (b.questionCount - noAnswer).toFloat() / b.questionCount },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "答案覆盖率 ${if (b.questionCount == 0) 0 else (b.questionCount - noAnswer) * 100 / b.questionCount}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = { if (b.questionCount == 0) 0f else (b.questionCount - noAnalysis).toFloat() / b.questionCount },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    "解析覆盖率 ${if (b.questionCount == 0) 0 else (b.questionCount - noAnalysis) * 100 / b.questionCount}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                ModeButton("标准做题", "作答后判对错、看解析，手动下一题") { onStartStudy(StudyMode.SEQUENTIAL) }
                ModeButton("速刷模式", "作答后展示答案，3 秒自动下一题") { onStartStudy(StudyMode.SPEED) }
                ModeButton("背题模式", "直接显示答案与解析，适合快速过题") { onStartStudy(StudyMode.RECITE) }
                ModeButton("随机巩固", "乱序抽题不重复，自动记忆进度，可重置") { onStartStudy(StudyMode.RANDOM) }
                ModeButton("测试模式", "选范围组卷整卷作答，答题卡跳题，交卷出报告") { onStartStudy(StudyMode.EXAM) }

                if (noAnswer > 0) {
                    Card(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "AI 生成答案（$noAnswer 题缺失）",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                "调用已配置大模型补全答案与解析，标注置信度；开启三省六部可多模型交叉验证",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    ModeButton("AI 重新生成答案", "对全部题目重新生成答案与解析") { onGenerate() }
                }

                Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("编辑题目 / 章节划分", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "按页码或题号范围批量分章，逐题修改题干、选项、答案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(Modifier.padding(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            showExportDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("导出题库") }
                    Button(
                        onClick = { vm.deleteBank(bankId, onBack) },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除题库") }
                }
            }
        }
    }

    // 导出选项对话框
    if (showExportDialog) {
        LaunchedEffect(Unit) { exportStats = vm.exportStats(bankId) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出题库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stats = exportStats
                    if (stats != null) {
                        Text(
                            "共 ${stats.total} 题 · 含答案 ${stats.withAnswer} 题 · AI 生成 ${stats.aiCount} 题",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("包含 AI 生成的答案与解析", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭后仅剔除 AI 生成的答案/解析/置信度，题干、原题答案与手动修改全部保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(checked = pendingIncludeAi, onCheckedChange = { pendingIncludeAi = it })
                    }
                    Text(
                        "导出内容：章节/题型/A1A2A3B 子类型/页码/共用题干与选项/手动修改后的题干选项答案解析" +
                            (if (pendingIncludeAi) "/AI 置信度与各部分歧" else "") +
                            "，并附章节分类汇总。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    exportLauncher.launch("${bank?.name ?: "bank"}.json")
                }) { Text("选择位置并导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ModeButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
