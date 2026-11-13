package com.examhelper.shua.ui.wrongbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.model.PdfAnswerMode
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

            item { SectionLabel("答案与解析呈现") }
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
                        ChoiceChip("文末集中答案", state.answerMode == PdfAnswerMode.CONCENTRATED) {
                            vm.setAnswerMode(PdfAnswerMode.CONCENTRATED)
                        }
                        ChoiceChip("无答案", state.answerMode == PdfAnswerMode.NONE) {
                            vm.setAnswerMode(PdfAnswerMode.NONE)
                        }
                    }
                }
            }

            item {
                PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("批量维护深度解析", style = serifTitle(16.sp, FontWeight.Medium))
                        Text(
                            "只处理当前导出范围内的未深度解题题；不自动生成 PDF，失败后可再次点击重试。",
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
                onClick = { exportLauncher.launch(vm.suggestedName()) },
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
