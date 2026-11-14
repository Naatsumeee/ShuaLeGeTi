package com.examhelper.shua.ui.wrongbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfAnswerPlacement
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors

@Composable
fun WrongBookPdfExportScreen(onBack: () -> Unit) {
    val vm: WrongBookPdfViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val extra = shuaColors
    var showMissingDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) vm.exportPdf(context, uri) }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 10.dp)) {
        CompactTopBar(title = "导出错题 PDF", onBack = onBack)
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = extra.terracotta)
            }
            return@Column
        }
        if (state.questions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前选项卡没有可导出的题目", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("来源", style = serifTitle(16.sp, FontWeight.Medium))
                            Spacer(Modifier.width(10.dp))
                            SemanticChip(state.source.label, ChipTone.Brand)
                        }
                        Text(
                            "继承错题本当前选项卡；导出的题目顺序与错题本一致。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionLabel("选择题库", trailing = "${state.banks.size} 个题库") }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    state.banks.forEach { (id, name) ->
                        ChoiceChip(name, state.selectedBankId == id) { vm.selectBank(id) }
                    }
                }
            }

            item { SectionLabel("导出范围", trailing = "共 ${state.selectedQuestions().size} 题") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("整本题库", state.scope == PdfExportScope.BANK) {
                        vm.setScope(PdfExportScope.BANK)
                    }
                    ChoiceChip("按章节", state.scope == PdfExportScope.CHAPTERS) {
                        vm.setScope(PdfExportScope.CHAPTERS)
                    }
                }
            }
            if (state.scope == PdfExportScope.CHAPTERS) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        state.chapters.forEach { chapter ->
                            ChoiceChip(chapter, chapter in state.selectedChapters) {
                                vm.toggleChapter(chapter)
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { vm.setOnlyQuestions(state.includeAnswers) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                ) {
                    Text(if (!state.includeAnswers) "✓ 仅导出题目（无答案解析）" else "仅导出题目（无答案解析）")
                }
            }

            if (state.includeAnswers) {
            item { SectionLabel("答案解析位置") }
            item {
                var placementMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { placementMenu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (state.answerPlacement == PdfAnswerPlacement.AFTER_EACH) "题后解析" else "文末解析"
                        )
                    }
                    DropdownMenu(expanded = placementMenu, onDismissRequest = { placementMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("题后解析") },
                            onClick = { placementMenu = false; vm.setAnswerPlacement(PdfAnswerPlacement.AFTER_EACH) }
                        )
                        DropdownMenuItem(
                            text = { Text("文末解析") },
                            onClick = { placementMenu = false; vm.setAnswerPlacement(PdfAnswerPlacement.AT_END) }
                        )
                    }
                }
            }

            item { SectionLabel("答案解析呈现") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChoiceChip("深度解题解析", state.answerMode == PdfAnswerMode.DEEP) {
                            vm.setAnswerMode(PdfAnswerMode.DEEP)
                        }
                        ChoiceChip("极简解析", state.answerMode == PdfAnswerMode.MINIMAL) {
                            vm.setAnswerMode(PdfAnswerMode.MINIMAL)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChoiceChip("仅答案", state.answerMode == PdfAnswerMode.ANSWER_ONLY) {
                            vm.setAnswerMode(PdfAnswerMode.ANSWER_ONLY)
                        }
                    }
                }
            }
            }

            item {
                PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("批量维护解析", style = serifTitle(16.sp, FontWeight.Medium))
                        Text(
                            "只处理当前导出范围内缺少对应解析的题；深度与极简解析互不替代，失败后可再次点击重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = vm::startDeepSolve,
                            enabled = !state.deepRunning && state.selectedQuestions().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                        ) {
                            Text(if (state.deepRunning) "深度解析生成中…" else "一键添加深度解析")
                        }
                        state.deepMessage?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
                        }
                        OutlinedButton(
                            onClick = vm::startMinimalSolve,
                            enabled = !state.minimalRunning && state.selectedQuestions().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.amber)
                        ) {
                            Text(if (state.minimalRunning) "极简解析生成中…" else "一键添加极简解析")
                        }
                        state.minimalMessage?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = extra.amber)
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.sage) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.verdant) }
            Button(
                onClick = {
                    if (state.includeAnswers && state.missingAnalysisCount() > 0) {
                        showMissingDialog = true
                    } else {
                        exportLauncher.launch(vm.suggestedName())
                    }
                },
                enabled = !state.generating && state.selectedQuestions().isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
            ) {
                Text(
                    if (state.generating) "生成中…" else "生成 PDF · ${state.selectedQuestions().size} 题",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (showMissingDialog) {
        val missing = state.missingAnalysisCount()
        AlertDialog(
            onDismissRequest = { showMissingDialog = false },
            title = { Text("解析有缺失") },
            text = {
                Text("当前范围内有 $missing 道题缺少所选程度的解析。可返回一键生成，或仍要导出（缺失处标注“无”）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMissingDialog = false
                    exportLauncher.launch(vm.suggestedName())
                }) { Text("仍要导出") }
            },
            dismissButton = {
                TextButton(onClick = { showMissingDialog = false }) { Text("返回生成") }
            }
        )
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = RoundedCornerShape(11.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = shuaColors.terracottaSoft,
            selectedLabelColor = shuaColors.terracottaDeep
        )
    )
}
