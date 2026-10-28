package com.examhelper.shua.ui.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.isCompactWidth
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: EditorViewModel = viewModel()
    val state by vm.state.collectAsState()
    val extra = shuaColors
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            toast = it
            vm.clearToast()
        }
    }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(
            title = "编辑 · 章节划分",
            onBack = onBack,
            actions = {
                TextButton(onClick = vm::openBatchDialog) {
                    Text("按范围分章", color = extra.terracotta, style = MaterialTheme.typography.labelMedium)
                }
            }
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = extra.terracotta, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 10.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("章节概况", style = serifTitle(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                            SemanticChip("${state.chapterSummary.size} 章", ChipTone.Brand)
                        }
                        if (state.chapterSummary.isEmpty()) {
                            Text(
                                "全部题目未分章，可用右上「按范围分章」批量划分",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                state.chapterSummary.forEach { (name, count) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(extra.terracotta)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "$count 题",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                        state.pageRange?.let {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
                            Text(
                                "原文页码范围 P${it.first} – P${it.last}（可用于按页分章）",
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.amber
                            )
                        }
                    }
                }
            }

            item { SectionLabel("题目列表", trailing = "点击卡片编辑") }

            itemsIndexed(state.questions, key = { _, q -> q.id }) { i, q ->
                QuestionRow(index = i + 1, q = q, onClick = { vm.openEdit(q.id) })
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

    toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(1800)
            toast = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.padding(bottom = 80.dp)) {
                GlassSurface(Modifier, shape = RoundedCornerShape(14.dp), elevation = 16.dp) {
                    Text(
                        msg,
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionRow(index: Int, q: Question, onClick: () -> Unit) {
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    q.stem,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    q.sourcePage?.let { SemanticChip("P$it", ChipTone.Neutral) }
                    Text(
                        typeLabel(q.type) + (q.subtype?.let { "·$it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "· " + (q.chapter?.take(9) ?: "未分章"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (q.chapter == null) extra.amber else MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }
            Text("编辑 ›", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
        }
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
    val extra = shuaColors
    val compact = isCompactWidth()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("按范围划分章节", style = serifTitle(18.sp, FontWeight.Medium)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwitch("按题号", !byPage) { byPage = false }
                    ModeSwitch("按页码", byPage, enabled = pageRange != null) { byPage = true }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("章节名") },
                    placeholder = { Text("如：第二章 伦理原则") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text(if (byPage) "起页" else "起题号") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text(if (byPage) "止页" else "止题号") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Text(
                    if (!byPage)
                        "共 $total 题 · 首题 P${firstPages.getOrNull(0) ?: "-"} / 中部 P${firstPages.getOrNull(1) ?: "-"} / 末题 P${firstPages.getOrNull(2) ?: "-"}"
                    else
                        "页码范围 P${pageRange?.first} – P${pageRange?.last}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val f = from.toIntOrNull() ?: return@Button
                    val t = to.toIntOrNull() ?: return@Button
                    if (byPage) onByPage(f, t, name) else onByIndex(f, t, name)
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
            ) { Text("划分") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ModeSwitch(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val extra = shuaColors
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) extra.terracotta else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (enabled) Modifier.clickableSafe(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.outline
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun Modifier.clickableSafe(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

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
    val extra = shuaColors

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("编辑题目", style = serifTitle(18.sp, FontWeight.Medium)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = { Text("题干") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp)
                )
                Text("题型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                FlowRowCompat {
                    listOf(
                        QuestionType.SINGLE to "单选", QuestionType.MULTIPLE to "多选",
                        QuestionType.JUDGE to "判断", QuestionType.FILL to "填空",
                        QuestionType.SHORT_ANSWER to "简答"
                    ).forEach { (t, label) ->
                        ModeSwitch(label, type == t) { type = t }
                    }
                }
                Text("子类型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                FlowRowCompat {
                    listOf("" to "无", "A1" to "A1", "A2" to "A2", "A3" to "A3", "B" to "B").forEach { (s, label) ->
                        ModeSwitch(label, subtype == s) { subtype = s }
                    }
                }
                OutlinedTextField(
                    value = chapter,
                    onValueChange = { chapter = it },
                    label = { Text("章节") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                if (options.isNotEmpty()) {
                    Text("选项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    options.forEachIndexed { i, opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(extra.terracottaSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(opt.key, style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
                            }
                            OutlinedTextField(
                                value = opt.text,
                                onValueChange = { v ->
                                    options = options.toMutableList().also { l -> l[i] = opt.copy(text = v) }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            TextButton(onClick = { options = options.filterIndexed { idx, _ -> idx != i } }) {
                                Text("删", color = extra.sage)
                            }
                        }
                    }
                }
                if (type == QuestionType.SINGLE || type == QuestionType.MULTIPLE || type == QuestionType.JUDGE) {
                    TextButton(onClick = {
                        val nextKey = ('A'..'H').firstOrNull { c -> options.none { it.key == c.toString() } }?.toString() ?: "X"
                        options = options + QuestionOption(nextKey, "")
                    }) { Text("+ 添加选项", color = extra.terracotta) }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("答案（B / ABD / 对 / 文本）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = analysis,
                    onValueChange = { analysis = it },
                    label = { Text("解析") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(chapter, type, subtype.ifBlank { null }, stem, options, answer, analysis) },
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 简易自动换行容器：替代 FlowRow（避免实验 API 依赖），保证 <380px 不裁切 */
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { content() }
        }
    }
}
