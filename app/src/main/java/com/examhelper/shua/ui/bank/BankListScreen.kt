package com.examhelper.shua.ui.bank

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import com.examhelper.shua.ui.theme.safeBottomPadding
import androidx.compose.animation.core.spring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BankListScreen(
    onOpenBank: (Long) -> Unit,
    onImportText: () -> Unit,
    onImportFile: (String?) -> Unit,
    onSearch: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val vm: BankListViewModel = viewModel()
    val askVm: HomeAskViewModel = viewModel()
    val allBanks by vm.banks.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val askOpen by askVm.open.collectAsState()
    val askState by askVm.state.collectAsState()
    var deleting by remember { mutableStateOf<QuestionBank?>(null) }
    var renaming by remember { mutableStateOf<QuestionBank?>(null) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var emptyImportOpen by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var groupDialog by remember { mutableStateOf<BankAction?>(null) }
    var deleteBatch by remember { mutableStateOf(false) }
    var confirmUndoMerge by remember { mutableStateOf<QuestionBank?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val extra = shuaColors
    val activeJobs = jobs.filter { it.stage != "FAILED" && it.stage != "CANCELED" }
    // 归档的默认收起来，只在最下面留一个小字按钮
    // 已归组的子题库只从对应题库组进入，不再在首页顶层重复出现。
    val topLevelBanks = allBanks.filter { it.groupId == null }
    val banks = topLevelBanks.filter { !it.archived }
    val archivedBanks = topLevelBanks.filter { it.archived }
    val listState = rememberLazyListState()
    val reorderScope = rememberCoroutineScope()
    var orderedBanks by remember { mutableStateOf(banks) }
    var draggingBankId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var suppressClickFor by remember { mutableStateOf<Long?>(null) }
    val itemGapPx = with(LocalDensity.current) { 12.dp.toPx() }

    LaunchedEffect(banks) {
        if (draggingBankId == null) orderedBanks = banks
    }

        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding() + 4.dp)
        ) {
            CompactTopBar(title = "刷题") {
                com.examhelper.shua.ui.theme.GlassIconButton(
                    icon = Icons.Filled.Edit,
                    contentDescription = if (editMode) "退出编辑" else "编辑题库",
                    onClick = {
                        Haptics.tap()
                        editMode = !editMode
                        if (!editMode) selected.clear()
                    }
                )
                Spacer(Modifier.width(6.dp))
                com.examhelper.shua.ui.theme.GlassIconButton(
                    icon = Icons.Filled.Search,
                    contentDescription = "搜索",
                    onClick = onSearch
                )
                Spacer(Modifier.width(6.dp))
                // 问 AI：与刷题页同一套按钮与面板，只是没有「当前题目」上下文
                com.examhelper.shua.ui.study.AskAiButton(
                    compact = true,
                    active = askOpen,
                    onClick = { askVm.toggle() }
                )
                Spacer(Modifier.width(6.dp))
                // 导入入口放标题栏，紧挨搜索键右边 —— 原来右下角的悬浮按钮
                // 会挡住列表最后一张卡片，而且不够显眼。
                Box {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { importMenuOpen = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "导入",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("文件 / 拍照 / 扫描 PDF（OCR）") },
                            onClick = { importMenuOpen = false; onImportFile(null) }
                        )
                        DropdownMenuItem(
                            text = { Text("粘贴题目文本") },
                            onClick = { importMenuOpen = false; onImportText() }
                        )
                    }
                }
            }

            if (allBanks.isEmpty() && activeJobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape)
                                .background(extra.terracotta.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = extra.terracotta,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text("还没有题库", style = serifTitle(20.sp, FontWeight.Medium))
                        Text(
                            "支持 PDF / docx / 拍照 / 扫描件（OCR）与文本粘贴导入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        // 空状态时把导入按钮放到页面正中，作为唯一显眼的入口
                        Box {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { emptyImportOpen = true }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "导入题库",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            DropdownMenu(expanded = emptyImportOpen, onDismissRequest = { emptyImportOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("文件 / 拍照 / 扫描 PDF（OCR）") },
                                    onClick = { emptyImportOpen = false; onImportFile(null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("粘贴题目文本") },
                                    onClick = { emptyImportOpen = false; onImportText() }
                                )
                            }
                        }
                    }
                }
        } else {
            LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    state = listState
                ) {
                    activeJobs.forEach { job ->
                        item(key = "job_${job.id}") {
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                elevation = 6.dp,
                                onClick = { onImportFile(job.id) }
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SemanticChip(
                                            if (job.stage == "DONE") "待确认" else "进行中",
                                            if (job.stage == "DONE") ChipTone.Brand else ChipTone.Amber
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "《${job.bankName}》",
                                            style = serifTitle(15.sp, FontWeight.Medium),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        when (job.stage) {
                                            "DONE" -> "解析完成，待确认导入 · 已得 ${job.parsedCount} 题"
                                            "PARSE" -> "后台解析中 ${job.doneUnits}/${job.totalUnits} 段 · 已得 ${job.parsedCount} 题"
                                            else -> "正在识别文件…"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(orderedBanks, key = { _, it -> it.id }) { _, bank ->
                        val dragging = draggingBankId == bank.id
                        val visualOffset by animateFloatAsState(
                            targetValue = if (dragging) dragOffsetY else 0f,
                            animationSpec = if (dragging) {
                                snap()
                            } else {
                                spring(
                                    dampingRatio = 0.76f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            },
                            label = "bankDragOffset"
                        )
                        Box(
                            Modifier
                                .zIndex(if (dragging) 2f else 0f)
                                .graphicsLayer {
                                    translationY = visualOffset
                                    val scale = if (dragging) 1.012f else 1f
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = if (dragging) 16f else 0f
                                }
                                .then(
                                    if (dragging) {
                                        Modifier
                                    } else {
                                        Modifier.animateItemPlacement(
                                            spring(
                                                dampingRatio = 0.72f,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                )
                        ) {
                            BankCard(
                                bank = bank,
                                onClick = {
                                    if (suppressClickFor != bank.id) {
                                        if (editMode) toggled(selected, bank.id) else onOpenBank(bank.id)
                                    }
                                },
                                onRename = { renaming = bank },
                                onPin = { vm.setPinned(bank.id, !bank.pinned) },
                                onArchive = { vm.setArchived(bank.id, true) },
                                onDelete = { deleting = bank },
                                onUndoMerge = { confirmUndoMerge = bank },
                                editMode = editMode,
                                checked = bank.id in selected,
                                color = BankColors.fromStorage(bank.accentColor)
                                    ?: if (bank.isGroup) BankColors.Default else extra.terracotta,
                                onToggle = { toggled(selected, bank.id) },
                                dragging = dragging,
                                dragModifier = Modifier.pointerInput(bank.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingBankId = bank.id
                                            suppressClickFor = bank.id
                                            dragOffsetY = 0f
                                            Haptics.longPress()
                                        },
                                        onDrag = { change, drag ->
                                            if (draggingBankId != bank.id) return@detectDragGesturesAfterLongPress
                                            change.consume()
                                            dragOffsetY += drag.y

                                            // 长按后按真实卡片高度找相邻项；每跨过一张卡，
                                            // 立即交换本地顺序。被拖卡片此时关闭位移动画，
                                            // 其余卡片继续用 animateItemPlacement 弹簧让位。
                                            var guard = 0
                                            while (guard++ < orderedBanks.size) {
                                                val currentIndex = orderedBanks.indexOfFirst { it.id == bank.id }
                                                if (currentIndex < 0) break
                                                val info = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == bank.id } ?: break
                                                val next = orderedBanks.getOrNull(currentIndex + 1)
                                                val previous = orderedBanks.getOrNull(currentIndex - 1)
                                                val nextInfo = next?.let { candidate ->
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == candidate.id }
                                                }
                                                val previousInfo = previous?.let { candidate ->
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == candidate.id }
                                                }
                                                when {
                                                    next != null && next.pinned == bank.pinned && nextInfo != null &&
                                                        dragOffsetY >= (info.size + nextInfo.size) / 2f + itemGapPx -> {
                                                        val movedBy = nextInfo.size + itemGapPx
                                                        orderedBanks = orderedBanks.toMutableList().apply {
                                                            add(currentIndex + 1, removeAt(currentIndex))
                                                        }
                                                        dragOffsetY -= movedBy
                                                    }
                                                    previous != null && previous.pinned == bank.pinned && previousInfo != null &&
                                                        dragOffsetY <= -((info.size + previousInfo.size) / 2f + itemGapPx) -> {
                                                        val movedBy = previousInfo.size + itemGapPx
                                                        orderedBanks = orderedBanks.toMutableList().apply {
                                                            add(currentIndex - 1, removeAt(currentIndex))
                                                        }
                                                        dragOffsetY += movedBy
                                                    }
                                                    else -> break
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val finalOrder = orderedBanks.map { it.id }
                                            dragOffsetY = 0f
                                            draggingBankId = null
                                            vm.reorderBanks(finalOrder)
                                            reorderScope.launch {
                                                delay(120)
                                                if (suppressClickFor == bank.id) suppressClickFor = null
                                            }
                                        },
                                        onDragCancel = {
                                            dragOffsetY = 0f
                                            draggingBankId = null
                                            reorderScope.launch {
                                                delay(120)
                                                if (suppressClickFor == bank.id) suppressClickFor = null
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                    // 归档区：默认完全收起，只在最下面一行小字提示
                    if (archivedBanks.isNotEmpty()) {
                        item(key = "archived_toggle") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (showArchived) "收起已归档题库" else "显示已归档题库（${archivedBanks.size}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showArchived = !showArchived }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                        if (showArchived) {
                            items(archivedBanks, key = { "arch_${it.id}" }) { bank ->
                                BankCard(
                                    bank = bank,
                                    onClick = { onOpenBank(bank.id) },
                                    onRename = { renaming = bank },
                                    onPin = { vm.setPinned(bank.id, !bank.pinned) },
                                    onArchive = { vm.setArchived(bank.id, false) },
                                    onDelete = { deleting = bank }
                                )
                            }
                        }
                    }
            }
            if (editMode && selected.isNotEmpty()) {
                BatchActionBar(
                    selected = selected.toList(),
                    onMerge = {
                        val picked = selected.mapNotNull { id -> allBanks.firstOrNull { it.id == id } }
                        when {
                            picked.size < 2 -> actionError = "至少选择两个普通题库才能合并"
                            picked.any { it.isGroup } -> actionError = "题库组暂不支持直接合并，请选择组内题库"
                            else -> groupDialog = BankAction.MERGE
                        }
                    },
                    onGroup = {
                        val picked = selected.mapNotNull { id -> allBanks.firstOrNull { it.id == id } }
                        if (picked.any { it.isGroup }) {
                            actionError = "题库组暂不支持嵌套，请选择组内题库"
                        } else {
                            groupDialog = BankAction.GROUP
                        }
                    },
                    onArchive = {
                        vm.batchArchive(selected.toList(), true)
                        selected.clear()
                    },
                    onDelete = { deleteBatch = true }
                )
            }
        }
    }

    renaming?.let { bank ->
        BankEditDialog(
            initialName = bank.name,
            initialNote = bank.note,
            initialColor = bank.accentColor,
            isGroup = bank.isGroup,
            onDismiss = { renaming = null },
            onConfirm = { name, note, color ->
                vm.renameAndNote(bank.id, name, note, color)
                renaming = null
            }
        )
    }

    deleting?.let { bank ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除题库", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Text(
                    if (bank.isGroup) {
                        "确定删除题库组\"${bank.name}\"？组内题库不会删除，会回到首页普通列表。"
                    } else {
                        "确定删除\"${bank.name}\"？该题库全部 ${bank.questionCount} 道题、进度与错题记录都会被删除，不可恢复。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBank(bank.id)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }

    groupDialog?.let { action ->
        val picked = selected.mapNotNull { id -> allBanks.firstOrNull { it.id == id } }
        CreateBankDialog(
            title = when (action) {
                BankAction.MERGE -> "合并题库"
                BankAction.GROUP -> "新建题库组"
            },
            confirmText = when (action) {
                BankAction.MERGE -> "合并"
                BankAction.GROUP -> "创建"
            },
            defaultName = picked.firstOrNull()?.name ?: "",
            allowColor = action == BankAction.GROUP,
            hint = when (action) {
                BankAction.MERGE -> "将 ${picked.size} 个题库的题目与学习记录合并成一个新题库。原题库会删除，可用「撤销合并」恢复。"
                BankAction.GROUP -> "把 ${picked.size} 个题库归入一个题库组，题目本身不移动。"
            },
            onDismiss = { groupDialog = null },
            onConfirm = { name, note, color ->
                val ids = selected.toList()
                groupDialog = null
                if (action == BankAction.MERGE) {
                    vm.merge(name, note, null, ids) { selected.clear() }
                } else {
                    vm.createGroup(name, note, color, ids) { selected.clear() }
                }
            }
        )
    }

    if (deleteBatch) {
        val includesGroup = selected.any { id -> allBanks.firstOrNull { it.id == id }?.isGroup == true }
        AlertDialog(
            onDismissRequest = { deleteBatch = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("批量删除题库", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Text(
                    buildString {
                        append("确定删除选中的 ${selected.size} 个题库？普通题库的题目、进度与错题记录会被删除且不可恢复。")
                        if (includesGroup) append("其中题库组只删除分组，组内题库会保留。")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.batchDelete(selected.toList())
                    selected.clear()
                    deleteBatch = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteBatch = false }) { Text("取消") } }
        )
    }

    confirmUndoMerge?.let { bank ->
        AlertDialog(
            onDismissRequest = { confirmUndoMerge = null },
            title = { Text("撤销合并？", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = { Text("将删除合并后的「${bank.name}」，按合并前快照恢复原题库、题目顺序与学习记录。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    vm.undoMerge(bank.id)
                    confirmUndoMerge = null
                }) { Text("撤销", color = extra.sage) }
            },
            dismissButton = { TextButton(onClick = { confirmUndoMerge = null }) { Text("取消") } }
        )
    }

    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text("无法执行", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { actionError = null }) { Text("知道了") }
            }
        )
    }

    // 首页问 AI 面板：与刷题页同一个组件，样式与交互完全一致
    if (askOpen) {
        ModalBottomSheet(
            onDismissRequest = { askVm.toggle() },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            com.examhelper.shua.ui.study.AskAiPanel(
                state = askState.copy(
                    hint = "想问什么就问什么：概念、考点、复习方法都可以"
                ),
                onAsk = askVm::ask,
                onClear = askVm::clear,
                onDismiss = { askVm.toggle() }
            )
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BankCard(
    bank: QuestionBank,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onUndoMerge: () -> Unit = {},
    editMode: Boolean = false,
    checked: Boolean = false,
    color: Color = shuaColors.terracotta,
    onToggle: () -> Unit = {},
    dragging: Boolean = false,
    dragModifier: Modifier = Modifier
) {
    // 菜单状态提到外层：编辑模式下拖拽/勾选会重绘卡片，不能让右键菜单
    // 因 pointerInput 或列表 key 变化意外关闭。
    var menuOpen by remember(bank.id) { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val extra = shuaColors
    val shape = RoundedCornerShape(20.dp)
    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            // 长按整张卡片进入拖拽；普通点击仍是进入题库，编辑模式下点击 = 勾选。
            .clickable(onClick = onClick),
        shape = shape
    ) {
        Row(Modifier.padding(16.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (editMode) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (checked) extra.terracotta else Color.Transparent)
                        .border(1.5.dp, if (checked) extra.terracotta else extra.inkFaint, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            // 左侧书脊：宽度从 3dp 加到 5dp；整行高度撑开，名称换行时自动拉长
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.25f))))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bank.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            tint = extra.terracotta,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        bank.name,
                        style = serifTitle(16.sp, FontWeight.Medium),
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (bank.isGroup) {
                        Spacer(Modifier.width(6.dp))
                        SemanticChip("题库组", ChipTone.Verdant)
                    }
                }
                bank.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText("${bank.questionCount} 题")
                    Text(" · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        fmt.format(Date(bank.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (bank.archived) {
                        Spacer(Modifier.width(6.dp))
                        SemanticChip("已归档", ChipTone.Amber)
                    }
                }
            }
            Box {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "长按卡片拖动排序",
                    tint = if (dragging) shuaColors.terracotta else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (dragging) shuaColors.terracottaSoft else Color.Transparent)
                )
                com.examhelper.shua.ui.theme.GlassIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    onClick = { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (bank.pinned) "取消置顶" else "置顶") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                        onClick = { menuOpen = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑题库名称") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    if (bank.mergedFromJson != null) {
                        DropdownMenuItem(
                            text = { Text("撤销合并") },
                            leadingIcon = { Icon(Icons.Filled.Undo, null) },
                            onClick = { menuOpen = false; onUndoMerge() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (bank.archived) "取消归档" else "归档") },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                        onClick = { menuOpen = false; onArchive() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除题库", color = extra.sage) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = extra.sage) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

enum class BankAction { MERGE, GROUP }

private fun toggled(ids: MutableList<Long>, id: Long) {
    if (id in ids) ids.remove(id) else ids.add(id)
}

/**
 * 编辑模式左下的批量操作条。只有选中后才出现，避免平时占高度。
 */
@Composable
private fun BatchActionBar(
    selected: List<Long>,
    onMerge: () -> Unit,
    onGroup: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    if (selected.isEmpty()) return
    val extra = shuaColors
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .safeBottomPadding(minBottom = 8.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = 14.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已选择 ${selected.size} 个题库", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BatchChip("合并题库", Icons.Filled.MergeType, extra.terracotta, Modifier.weight(1f), onMerge)
                BatchChip("新建题库组", Icons.Filled.FolderCopy, extra.verdant, Modifier.weight(1f), onGroup)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BatchChip("批量归档", Icons.Filled.Archive, extra.amber, Modifier.weight(1f), onArchive)
                BatchChip("批量删除", Icons.Filled.Delete, extra.sage, Modifier.weight(1f), onDelete)
            }
        }
    }
}

@Composable
private fun BatchChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 重命名题库：只有名字一个字段，空名直接忽略 */
@Composable
private fun RenameBankDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑题库名称", style = serifTitle(17.sp, FontWeight.Medium)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("题库名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 新建题库组 / 合并题库：名称、备注、颜色（组） */
@Composable
private fun CreateBankDialog(
    title: String,
    confirmText: String,
    defaultName: String,
    allowColor: Boolean,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, Long?) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(BankColors.Default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = serifTitle(18.sp, FontWeight.Medium)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                if (allowColor) {
                    BankColorEditor(initialColor = color) { color = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        note.trim().takeIf { it.isNotEmpty() },
                        BankColors.toStorage(color)
                    )
                }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 题库组/合并题库编辑：名称、备注、颜色 */
@Composable
private fun BankEditDialog(
    initialName: String,
    initialNote: String?,
    initialColor: Long?,
    isGroup: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, Long?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
    var color by remember {
        mutableStateOf(BankColors.fromStorage(initialColor) ?: BankColors.Default)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isGroup) "编辑题库组" else "编辑题库", style = serifTitle(18.sp, FontWeight.Medium)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("备注") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                if (isGroup) {
                    BankColorEditor(initialColor = color) { color = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onConfirm(name.trim(), note.trim().takeIf { it.isNotEmpty() }, BankColors.toStorage(color))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
