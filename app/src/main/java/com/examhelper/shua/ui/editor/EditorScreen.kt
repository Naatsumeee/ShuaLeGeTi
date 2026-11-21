package com.examhelper.shua.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.QuestionNumberBadge
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.isCompactWidth
import com.examhelper.shua.ui.theme.monoStyle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EditorScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
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

        val listState = rememberLazyListState()
        val blockHeaderListIndices = remember(state.chapterBlocks) {
            val map = mutableMapOf<Int, Int>()
            // 0 = 章节概况卡片；1 = SectionLabel
            var itemIdx = 2
            state.chapterBlocks.forEach { block ->
                map[block.blockIndex] = itemIdx
                itemIdx += 1 + block.questions.size
            }
            map
        }
        val questionToListIndex = remember(state.chapterBlocks) {
            val arr = IntArray(state.questions.size)
            var itemIdx = 2
            state.chapterBlocks.forEach { block ->
                itemIdx += 1 // 跳过 ChapterBlockHeader
                block.questions.indices.forEach { local ->
                    arr[block.startIndex + local] = itemIdx++
                }
            }
            arr
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 2.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "overview") {
                    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 10.dp) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("章节分块", style = serifTitle(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                                SemanticChip("${state.chapterBlocks.size} 段 · ${state.questions.size} 题", ChipTone.Brand)
                            }
                            if (state.chapterBlocks.isEmpty()) {
                                Text(
                                    "当前题库暂无题目",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    state.chapterBlocks.forEach { block ->
                                        val label = block.chapter ?: "未分章"
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    val target = blockHeaderListIndices[block.blockIndex] ?: return@clickable
                                                    scope.launch { listState.animateScrollToItem(target) }
                                                }
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(if (block.chapter == null) extra.amber else extra.terracotta)
                                            )
                                            Spacer(Modifier.width(7.dp))
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "#${block.startIndex + 1}–${block.endIndexInclusive + 1} (${block.questions.size})",
                                                style = monoStyle(11.sp),
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }
                            state.pageRange?.let {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
                                Text(
                                    "原文页码 P${it.first} – P${it.last}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extra.amber
                                )
                            }
                        }
                    }
                }

                item(key = "list_label") {
                    SectionLabel("题目与分章", trailing = "点题卡编辑 · 题间可新建分章")
                }

                state.chapterBlocks.forEach { block ->
                    item(key = "block_${block.blockIndex}_${block.startIndex}") {
                        ChapterBlockHeader(
                            block = block,
                            canRemoveBreak = block.startIndex > 0 || block.chapter != null,
                            onRename = { vm.openRenameBlock(block) },
                            onRemoveBreak = { vm.openRemoveBreak(block) }
                        )
                    }
                    itemsIndexed(
                        items = block.questions,
                        key = { _, q -> "q_${q.id}" }
                    ) { localIdx, q ->
                        val globalIdx = block.startIndex + localIdx
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            QuestionRow(
                                index = globalIdx + 1,
                                q = q,
                                onClick = { vm.openEdit(q.id) }
                            )
                            if (localIdx < block.questions.lastIndex) {
                                BetweenQuestionBreakBar(
                                    nextQuestionNumber = globalIdx + 2,
                                    onClick = { vm.openInsertBreak(globalIdx + 1) }
                                )
                            }
                        }
                    }
                }
            }

            if (state.questions.size > 4) {
                EditorFastScrollbar(
                    listState = listState,
                    questions = state.questions,
                    questionToListIndex = questionToListIndex,
                    onScrollToItem = { target -> scope.launch { listState.scrollToItem(target) } },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 6.dp, bottom = 12.dp, end = 2.dp)
                )
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

    state.insertBreakAtIndex?.let { atIdx ->
        val block = state.chapterBlocks.firstOrNull { atIdx in it.startIndex..it.endIndexInclusive }
        InsertChapterBreakDialog(
            fromQuestion = atIdx + 1,
            untilQuestion = (block?.endIndexInclusive ?: atIdx) + 1,
            onDismiss = vm::closeInsertBreak,
            onConfirm = { name -> vm.confirmInsertBreak(atIdx, name) }
        )
    }

    state.renamingBlock?.let { block ->
        RenameChapterDialog(
            block = block,
            onDismiss = vm::closeRenameBlock,
            onConfirm = { newName -> vm.confirmRenameBlock(block, newName) }
        )
    }

    state.removingBlock?.let { block ->
        val prevChapter = if (block.startIndex > 0) {
            normalizeChapterLabel(state.questions[block.startIndex - 1].chapter)
        } else {
            null
        }
        RemoveChapterBreakDialog(
            block = block,
            prevChapter = prevChapter,
            onDismiss = vm::closeRemoveBreak,
            onConfirm = { vm.confirmRemoveBreak(block) }
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
private fun ChapterBlockHeader(
    block: ChapterBlock,
    canRemoveBreak: Boolean,
    onRename: () -> Unit,
    onRemoveBreak: () -> Unit
) {
    val extra = shuaColors
    val title = block.chapter ?: "未分章"
    val pages = block.pageRange
    PaperCard(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(14.dp),
        container = if (block.chapter == null) extra.amberSoft.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        borderColor = if (block.chapter == null) extra.amber.copy(alpha = 0.35f)
        else extra.terracotta.copy(alpha = 0.28f),
        elevation = 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(if (block.chapter == null) extra.amber else extra.terracotta)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = serifTitle(14.sp, FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(
                        "#${block.startIndex + 1}–#${block.endIndexInclusive + 1} · ${block.questions.size} 题",
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pages != null) {
                        Text(
                            "P${pages.first}–P${pages.last}",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.amber
                        )
                    }
                }
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRename)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = extra.terracotta)
                Text(
                    if (block.chapter == null) "设章" else "改名",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.terracotta
                )
            }
            if (canRemoveBreak) {
                Spacer(Modifier.width(2.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRemoveBreak)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(13.dp), tint = extra.sage)
                    Text("删分章", style = MaterialTheme.typography.labelSmall, color = extra.sage)
                }
            }
        }
    }
}

@Composable
private fun BetweenQuestionBreakBar(
    nextQuestionNumber: Int,
    onClick: () -> Unit
) {
    val extra = shuaColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(extra.creamLine))
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(11.dp), tint = extra.terracotta)
            Text(
                "第 $nextQuestionNumber 题起新建分章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
        Box(Modifier.weight(1f).height(1.dp).background(extra.creamLine))
    }
}

@Composable
private fun EditorFastScrollbar(
    listState: LazyListState,
    questions: List<Question>,
    questionToListIndex: IntArray,
    onScrollToItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = shuaColors
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val listFraction by remember(listState, questionToListIndex) {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount.coerceAtLeast(1)
            val first = listState.firstVisibleItemIndex
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val maxFirst = (totalItems - visibleCount).coerceAtLeast(1)
            (first.toFloat() / maxFirst).coerceIn(0f, 1f)
        }
    }
    val activeFraction = if (dragging) dragFraction else listFraction
    val currentQuestionIndex = remember(activeFraction, questions.size) {
        ((questions.size - 1) * activeFraction).roundToInt().coerceIn(0, (questions.size - 1).coerceAtLeast(0))
    }

    fun jumpByFraction(frac: Float) {
        val clamped = frac.coerceIn(0f, 1f)
        dragFraction = clamped
        if (questionToListIndex.isEmpty()) return
        val qIdx = ((questionToListIndex.size - 1) * clamped).roundToInt()
            .coerceIn(0, questionToListIndex.lastIndex)
        onScrollToItem(questionToListIndex[qIdx])
    }

    val thumbHeightDp = 44.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val usablePx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbOffsetY = (usablePx * activeFraction).roundToInt()

    Box(modifier.width(22.dp)) {
        if (dragging && questions.isNotEmpty()) {
            val previewQ = questions[currentQuestionIndex]
            val chap = normalizeChapterLabel(previewQ.chapter) ?: "未分章"
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(with(density) { -30.dp.roundToPx() }, thumbOffsetY) }
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    elevation = 10.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MonoText("#${currentQuestionIndex + 1}", size = 12.sp, color = extra.terracotta, weight = FontWeight.SemiBold)
                        Text(
                            chap,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(120.dp)
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(22.dp)
                .onSizeChanged { trackHeightPx = it.height }
                .pointerInput(questionToListIndex) {
                    detectTapGestures { offset ->
                        val frac = ((offset.y - thumbHeightPx / 2f) / usablePx).coerceIn(0f, 1f)
                        jumpByFraction(frac)
                    }
                }
                .pointerInput(questionToListIndex) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val frac = ((offset.y - thumbHeightPx / 2f) / usablePx).coerceIn(0f, 1f)
                            jumpByFraction(frac)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val frac = ((change.position.y - thumbHeightPx / 2f) / usablePx).coerceIn(0f, 1f)
                            jumpByFraction(frac)
                        }
                    )
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(extra.creamLineStrong.copy(alpha = 0.45f))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetY) }
                    .padding(end = 2.dp)
                    .width(if (dragging) 9.dp else 6.dp)
                    .height(thumbHeightDp)
                    .clip(CircleShape)
                    .background(if (dragging) extra.terracotta else extra.terracotta.copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
private fun InsertChapterBreakDialog(
    fromQuestion: Int,
    untilQuestion: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val extra = shuaColors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("新建分章标记", style = serifTitle(17.sp, FontWeight.Medium)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将第 $fromQuestion – $untilQuestion 题划入新章节（至下一个分章边界前）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新章节名") },
                    placeholder = { Text("如：第三章 遗传病诊断") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenameChapterDialog(
    block: ChapterBlock,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(block.startIndex) { mutableStateOf(block.chapter.orEmpty()) }
    val extra = shuaColors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (block.chapter == null) "设置章节名称" else "修改章节名称", style = serifTitle(17.sp, FontWeight.Medium)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "作用于第 ${block.startIndex + 1} – ${block.endIndexInclusive + 1} 题（共 ${block.questions.size} 题）；留空则设为未分章。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("章节名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RemoveChapterBreakDialog(
    block: ChapterBlock,
    prevChapter: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val extra = shuaColors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("删除分章标记", style = serifTitle(17.sp, FontWeight.Medium)) },
        text = {
            Text(
                if (block.startIndex == 0) {
                    "确认取消第 1 – ${block.endIndexInclusive + 1} 题的分章标记（将归为未分章）？"
                } else {
                    "确认删除此分章标记，并将第 ${block.startIndex + 1} – ${block.endIndexInclusive + 1} 题合并至上一章节「${prevChapter ?: "未分章"}」？"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = extra.sage)
            ) { Text("删除分章") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun QuestionRow(index: Int, q: Question, onClick: () -> Unit) {
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            QuestionNumberBadge(index)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Latex.LatexText(
                    text = q.stem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
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
fun QuestionEditDialog(
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
                        QuestionType.SHORT_ANSWER to "简答", QuestionType.TERM to "名词解释"
                    ).forEach { (t, label) ->
                        ModeSwitch(label, type == t) { type = t }
                    }
                }
                Text("子类型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                FlowRowCompat {
                    listOf(
                        "" to "无", "A1" to "A1", "A2" to "A2", "A3" to "A3",
                        "B1" to "B1", "B2" to "B2", "B" to "旧B"
                    ).forEach { (s, label) ->
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

/** 真正的流式折行：小屏放不下就换行，绝不把文字压成竖排或被右缘裁切 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) { content() }
}
