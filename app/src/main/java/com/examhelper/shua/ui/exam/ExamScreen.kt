package com.examhelper.shua.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.monoStyle
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

/**
 * 测试模式：与做题页同构的紧凑布局（玻璃标题栏 + 细进度 + 可滚主体 + 固定玻璃控制条）。
 */
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
    val extra = shuaColors

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeTopPadding()) {
            CompactTopBar(
                title = "测试模式",
                onBack = onBack
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { showSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Apps, "答题卡", modifier = Modifier.size(18.dp), tint = extra.inkFaint)
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val q = state.current

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        "${state.answeredCount}/${state.questions.size}",
                        size = 12.sp,
                        color = extra.terracotta
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    if (state.questions.isEmpty()) 0f
                                    else (state.index + 1).toFloat() / state.questions.size
                                )
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(extra.terracotta)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    MonoText(
                        "%02d:%02d".format(state.elapsedSec / 60, state.elapsedSec % 60),
                        size = 12.sp,
                        color = if (state.elapsedSec >= 60) extra.terracotta else extra.inkFaint
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp)
                        .padding(top = 2.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (q == null) {
                        Text("题目加载中…")
                    } else {
                        PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SemanticChip(
                                        typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: ""),
                                        ChipTone.Brand
                                    )
                                    q.chapter?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(7.dp))
                                q.sharedStem?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(q.stem, style = MaterialTheme.typography.bodyLarge)
                            }
                        }

                        q.sharedOptionsJson?.let { SharedOptionsCard(it) }

                        val options = JsonCodec.decodeOptions(q.optionsJson)
                        val selected = state.selected[q.id] ?: emptySet()
                        when (q.type) {
                            QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE ->
                                options.forEach { opt ->
                                    val isSelected = opt.key in selected
                                    ExamOptionRow(
                                        key = opt.key,
                                        text = opt.text,
                                        selected = isSelected,
                                        onClick = { vm.onOptionTap(opt.key) }
                                    )
                                }

                            QuestionType.FILL, QuestionType.SHORT_ANSWER, QuestionType.TERM -> OutlinedTextField(
                                value = state.textInputs[q.id] ?: "",
                                onValueChange = vm::updateText,
                                label = { Text("作答（交卷后对照参考答案）") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                minLines = if (q.type == QuestionType.FILL) 1 else 3
                            )
                        }
                    }
                }

                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeBottomPadding(minBottom = 10.dp)
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = 12.dp
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(
                                onClick = vm::prev,
                                enabled = state.index > 0,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(13.dp)
                            ) { Text("上一题", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                            if (state.index >= state.questions.size - 1) {
                                Button(
                                    onClick = { showConfirm = true },
                                    modifier = Modifier.weight(1.4f).heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text("交卷", style = MaterialTheme.typography.labelLarge) }
                            } else {
                                Button(
                                    onClick = vm::next,
                                    modifier = Modifier.weight(1.4f).heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text("下一题", style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                        if (state.index < state.questions.size - 1) {
                            TextButton(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "直接交卷",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extra.terracotta
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("答题卡", style = serifTitle(18.sp, FontWeight.Medium))
                    Spacer(Modifier.width(8.dp))
                    MonoText("${state.answeredCount}/${state.questions.size}", color = extra.terracotta)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    items(state.questions.indices.toList()) { i ->
                        val answered = (state.selected[state.questions[i].id])?.isNotEmpty() == true ||
                            !state.textInputs[state.questions[i].id].isNullOrBlank()
                        val isCurrent = i == state.index
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    when {
                                        isCurrent -> MaterialTheme.colorScheme.primary
                                        answered -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .clickable {
                                    vm.jumpTo(i)
                                    showSheet = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${i + 1}",
                                style = monoStyle(12.sp, FontWeight.Medium),
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("确认交卷", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Text(
                    "共 ${state.questions.size} 题，已答 ${state.answeredCount}，未答 ${state.questions.size - state.answeredCount}。交卷后立即判分并生成报告。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        vm.submit { runId -> onSubmitted(runId) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("确认交卷") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("继续作答") }
            }
        )
    }
}

@Composable
private fun ExamOptionRow(key: String, text: String, selected: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) extra.terracotta.copy(alpha = 0.45f) else extra.creamLine,
                RoundedCornerShape(13.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                key,
                style = monoStyle(12.sp, FontWeight.Medium),
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Filled.Check, "已选", modifier = Modifier.size(16.dp), tint = extra.terracotta)
        }
    }
}

@Composable
fun SharedOptionsCard(sharedOptionsJson: String) {
    val shared = JsonCodec.decodeOptions(sharedOptionsJson)
    if (shared.isEmpty()) return
    val extra = shuaColors
    PaperCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        elevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("共用选项 · B 型题", style = MaterialTheme.typography.labelSmall, color = extra.amber)
            shared.forEach {
                Text(
                    "${it.key}. ${it.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
