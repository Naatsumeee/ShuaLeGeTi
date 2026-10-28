package com.examhelper.shua.ui.bank

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

/**
 * 题库详情：玻璃封面卡（大数字 + 双覆盖率）+ 模式入口列表 + 底部操作。
 */
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val extra = shuaColors

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

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(title = bank?.name ?: "题库", onBack = onBack)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            bank?.let { b ->
                item {
                    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), elevation = 12.dp) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${b.questionCount}",
                                    style = serifTitle(42.sp, FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    " 道题",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 7.dp, start = 3.dp)
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                CoverageBar(
                                    "答案覆盖率",
                                    if (b.questionCount == 0) 0 else (b.questionCount - noAnswer) * 100 / b.questionCount,
                                    "${b.questionCount - noAnswer}/${b.questionCount}",
                                    Modifier.weight(1f),
                                    extra.terracotta
                                )
                                CoverageBar(
                                    "解析覆盖率",
                                    if (b.questionCount == 0) 0 else (b.questionCount - noAnalysis) * 100 / b.questionCount,
                                    "${b.questionCount - noAnalysis}/${b.questionCount}",
                                    Modifier.weight(1f),
                                    extra.amber
                                )
                            }
                            if (noAnswer > 0 || noAnalysis > 0) {
                                Text(
                                    "缺 $noAnswer 题答案 · 缺 $noAnalysis 题解析，可在下方一键生成",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item { SectionLabel("刷题模式", trailing = "选范围后再开始") }

                item {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Column {
                            ModeRow("标准做题", "作答即判对错，看解析再下一题", StudyMode.SEQUENTIAL, onStartStudy)
                            Divider()
                            ModeRow("速刷模式", "作答后直出答案，3 秒自动跳题", StudyMode.SPEED, onStartStudy)
                            Divider()
                            ModeRow("背题模式", "直接展开答案与解析，快速过题", StudyMode.RECITE, onStartStudy)
                            Divider()
                            ModeRow("随机巩固", "乱序抽题不重复，进度可重置", StudyMode.RANDOM, onStartStudy)
                            Divider()
                            ModeRow("测试模式", "选范围组卷，答题卡跳题，交卷出报告", StudyMode.EXAM, onStartStudy)
                        }
                    }
                }

                item { SectionLabel("题库维护") }

                item {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Column {
                            ActionRow(
                                title = if (noAnswer > 0) "AI 生成答案 · 缺 $noAnswer 题" else "AI 重新生成答案",
                                subtitle = "调用已配置模型补全答案与解析，标注置信度；三省六部可交叉验证",
                                onClick = onGenerate
                            )
                            Divider()
                            ActionRow(
                                title = "编辑题目 / 章节划分",
                                subtitle = "按页码或题号批量分章，逐题修改题干选项答案解析",
                                onClick = onEdit
                            )
                            Divider()
                            ActionRow(
                                title = "导出题库",
                                subtitle = "导出为 App 通用 .json，可再次导入",
                                onClick = {
                                    exportStats = null
                                    showExportDialog = true
                                }
                            )
                        }
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("返回", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = extra.sageSoft)
                        ) { Text("删除题库", color = extra.sage) }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        LaunchedEffect(Unit) { exportStats = vm.exportStats(bankId) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("导出题库", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    exportStats?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SemanticChip("共 ${it.total} 题", ChipTone.Neutral)
                            SemanticChip("有答案 ${it.withAnswer}", ChipTone.Brand)
                            SemanticChip("AI 生成 ${it.aiCount}", ChipTone.Amber)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("包含 AI 生成的答案与解析", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭后仅剔除 AI 生成的答案/解析/置信度，题干、原题答案与手动修改全部保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = pendingIncludeAi,
                            onCheckedChange = { pendingIncludeAi = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        "导出内容：章节/题型/A1A2A3B 子类型/页码/共用题干与选项/手动修改后的题干选项答案解析" +
                            (if (pendingIncludeAi) "/AI 置信度与分歧" else "") + "，并附章节汇总。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("删除题库", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = { Text("将删除「${bank?.name ?: ""}」及其全部题目、答题记录与进度，无法恢复。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteBank(bankId, onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = extra.sage)
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CoverageBar(label: String, percent: Int, ratio: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            MonoText("$percent%", size = 11.sp, color = color)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(ratio, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, mode: StudyMode, onStart: (StudyMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onStart(mode) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = serifTitle(15.sp, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(1.dp)
            .background(shuaColors.creamLine)
    )
}
