package com.examhelper.shua.ui.wrongbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.analysis.AnalysisLevel
import com.examhelper.shua.data.model.PdfAnswerMode
import com.examhelper.shua.data.model.PdfAnswerPlacement
import com.examhelper.shua.data.model.PdfExportScope
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors

@Composable
fun WrongBookPdfExportScreen(onBack: () -> Unit) {
    val vm: WrongBookPdfViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val extra = shuaColors
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) vm.exportPdf(context, uri) }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 10.dp)) {
        CompactTopBar(title = "导出错题 PDF", onBack = onBack)
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = extra.terracotta)
                }
            }

            state.questions.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前选项卡没有可导出的题目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .animateContentSize(
                            spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    SourceAndBank(state, vm)
                    ScopeSelector(state, vm)
                    OnlyQuestionsToggle(state, vm)

                    AnimatedVisibility(
                        visible = state.includeAnswers,
                        enter = expandVertically(
                            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(),
                        exit = shrinkVertically(
                            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
                        ) + fadeOut()
                    ) {
                        Column(
                            Modifier.animateContentSize(),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            AnswerPlacement(state, vm)
                            AnswerMode(state, vm)
                        }
                    }

                    MaintenanceCard(state, vm)
                    Feedback(state)
                }

                ExportBar(
                    state = state,
                    onExport = { exportLauncher.launch(vm.suggestedName()) }
                )
            }
        }
    }

    state.maintenancePrompt?.let { prompt ->
        val label = if (prompt.level == AnalysisLevel.DEEP) "深度解析" else "极简解析"
        AlertDialog(
            onDismissRequest = vm::dismissMaintenancePrompt,
            title = { Text("已有题目包含$label") },
            text = {
                Text(
                    "当前选中 ${prompt.questionIds.size} 道题，其中已有 ${prompt.existingCount} 道" +
                        "存在$label。要全部重新生成，还是跳过已有解析？"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmMaintenance(regenerate = true) }) {
                    Text("仍重新生成")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.confirmMaintenance(regenerate = false) }) {
                    Text("跳过已有解析")
                }
            }
        )
    }
}

@Composable
private fun SourceAndBank(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    var menu by remember { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("来源", style = serifTitle(15.sp, FontWeight.Medium))
            Spacer(Modifier.width(8.dp))
            SemanticChip(state.source.label, ChipTone.Brand)
            Spacer(Modifier.weight(1f))
            Box {
                OutlinedButton(
                    onClick = { menu = true },
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Text(
                        state.selectedBankName().ifBlank { "选择题库" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(150.dp)
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.banks.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = if (id == state.selectedBankId) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                menu = false
                                vm.selectBank(id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeSelector(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    var chapterMenu by remember { mutableStateOf(false) }
    SectionLabel("导出范围", trailing = "共 ${state.selectedQuestions().size} 题")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScopeButton(
            label = "整本题库",
            selected = state.scope == PdfExportScope.BANK,
            onClick = { vm.setScope(PdfExportScope.BANK) },
            modifier = Modifier.weight(1f)
        )
        ScopeButton(
            label = "按章节",
            selected = state.scope == PdfExportScope.CHAPTERS,
            onClick = { vm.setScope(PdfExportScope.CHAPTERS) },
            modifier = Modifier.weight(1f)
        )
    }
    AnimatedVisibility(
        visible = state.scope == PdfExportScope.CHAPTERS,
        enter = expandVertically(
            spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)
        ) + fadeIn(),
        exit = shrinkVertically(
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
        ) + fadeOut()
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 1.dp)) {
            OutlinedButton(
                onClick = { chapterMenu = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = shuaColors.terracottaSoft.copy(alpha = 0.45f),
                    contentColor = shuaColors.terracottaDeep
                )
            ) {
                Text(
                    if (state.selectedChapters.isEmpty()) "选择章节" else
                        "已选择 ${state.selectedChapters.size} 个章节",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                if (state.chapters.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("当前题库没有可选择的章节") },
                        onClick = { chapterMenu = false }
                    )
                }
                state.chapters.forEach { chapter ->
                    DropdownMenuItem(
                        text = { Text(chapter, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = if (chapter in state.selectedChapters) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        onClick = { vm.toggleChapter(chapter) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlyQuestionsToggle(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    FilterChip(
        selected = !state.includeAnswers,
        onClick = { vm.setOnlyQuestions(state.includeAnswers) },
        label = {
            Text(
                "仅导出题目（无答案解析）",
                modifier = Modifier.fillMaxWidth(),
                fontWeight = if (!state.includeAnswers) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun AnswerPlacement(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    SectionLabel("答案解析位置")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScopeButton(
            label = "题后解析",
            selected = state.answerPlacement == PdfAnswerPlacement.AFTER_EACH,
            onClick = { vm.setAnswerPlacement(PdfAnswerPlacement.AFTER_EACH) },
            modifier = Modifier.weight(1f)
        )
        ScopeButton(
            label = "文末解析",
            selected = state.answerPlacement == PdfAnswerPlacement.AT_END,
            onClick = { vm.setAnswerPlacement(PdfAnswerPlacement.AT_END) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AnswerMode(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    SectionLabel("答案解析呈现")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModeChip(
            "深度解析",
            state.answerMode == PdfAnswerMode.DEEP,
            { vm.setAnswerMode(PdfAnswerMode.DEEP) },
            Modifier.weight(1f)
        )
        ModeChip(
            "极简解析",
            state.answerMode == PdfAnswerMode.MINIMAL,
            { vm.setAnswerMode(PdfAnswerMode.MINIMAL) },
            Modifier.weight(1f)
        )
        ModeChip(
            "仅答案",
            state.answerMode == PdfAnswerMode.ANSWER_ONLY,
            { vm.setAnswerMode(PdfAnswerMode.ANSWER_ONLY) },
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaintenanceCard(
    state: WrongBookPdfViewModel.UiState,
    vm: WrongBookPdfViewModel
) {
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("维护题目解析", style = serifTitle(15.sp, FontWeight.Medium))
            Text(
                "后台逐题生成，离开页面不会中断；已有解析会先询问是否重新生成。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::startDeepSolve,
                    enabled = !state.maintenanceRunning && state.selectedQuestions().isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = shuaColors.terracotta)
                ) {
                    Text("深度解析", maxLines = 1)
                }
                OutlinedButton(
                    onClick = vm::startMinimalSolve,
                    enabled = !state.maintenanceRunning && state.selectedQuestions().isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = shuaColors.amber)
                ) {
                    Text("极简解析", maxLines = 1)
                }
            }
            AnimatedVisibility(
                visible = state.maintenanceRunning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                OutlinedButton(
                    onClick = vm::cancelMaintenance,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = shuaColors.sage)
                ) {
                    Text("停止当前生成任务")
                }
            }
            AnimatedVisibility(
                visible = state.maintenanceRunning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = shuaColors.terracotta,
                    trackColor = shuaColors.creamLine
                )
            }
            AnimatedVisibility(
                visible = state.maintenanceMessage != null,
                enter = expandVertically(
                    spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    state.maintenanceMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.maintenanceFailed > 0) shuaColors.sage else shuaColors.terracotta
                )
            }
        }
    }
}

@Composable
private fun Feedback(state: WrongBookPdfViewModel.UiState) {
    val message = state.error ?: state.message
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Text(
            message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.error != null) shuaColors.sage else shuaColors.verdant
        )
    }
}

@Composable
private fun ExportBar(
    state: WrongBookPdfViewModel.UiState,
    onExport: () -> Unit
) {
    var showMissing by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Button(
            onClick = {
                if (state.includeAnswers && state.missingAnalysisCount() > 0) {
                    showMissing = true
                } else {
                    onExport()
                }
            },
            enabled = !state.generating && state.selectedQuestions().isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = shuaColors.terracotta)
        ) {
            Text(
                if (state.generating) "生成中…" else "生成 PDF · ${state.selectedQuestions().size} 题",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    if (showMissing) {
        val missing = state.missingAnalysisCount()
        AlertDialog(
            onDismissRequest = { showMissing = false },
            title = { Text("解析有缺失") },
            text = {
                Text("当前范围内有 $missing 道题缺少所选程度的解析。可返回一键生成，或仍要导出（缺失处标注“无”）。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMissing = false
                        onExport()
                    }
                ) { Text("仍要导出") }
            },
            dismissButton = {
                TextButton(onClick = { showMissing = false }) { Text("返回生成") }
            }
        )
    }
}

@Composable
private fun ScopeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 42.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        shape = RoundedCornerShape(11.dp),
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = shuaColors.terracottaSoft,
            selectedLabelColor = shuaColors.terracottaDeep
        )
    )
}
