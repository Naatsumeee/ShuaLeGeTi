package com.examhelper.shua.ui.bank

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    val extra = shuaColors
    val activeJobs = jobs.filter { it.stage != "FAILED" && it.stage != "CANCELED" }
    // 归档的默认收起来，只在最下面留一个小字按钮
    val banks = allBanks.filter { !it.archived }
    val archivedBanks = allBanks.filter { it.archived }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding() + 4.dp)
        ) {
            CompactTopBar(title = "刷题") {
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    items(banks, key = { it.id }) { bank ->
                        BankCard(
                            bank = bank,
                            onClick = { onOpenBank(bank.id) },
                            onRename = { renaming = bank },
                            onPin = { vm.setPinned(bank.id, !bank.pinned) },
                            onArchive = { vm.setArchived(bank.id, true) },
                            onDelete = { deleting = bank }
                        )
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
            }
        }
    }

    renaming?.let { bank ->
        RenameBankDialog(
            initial = bank.name,
            onDismiss = { renaming = null },
            onConfirm = { vm.renameBank(bank.id, it); renaming = null }
        )
    }

    deleting?.let { bank ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除题库", style = serifTitle(18.sp, FontWeight.Medium)) },
            text = { Text("确定删除\"${bank.name}\"？该题库全部 ${bank.questionCount} 道题、进度与错题记录都会被删除，不可恢复。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBank(bank.id)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BankCard(
    bank: QuestionBank,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val extra = shuaColors
    val shape = RoundedCornerShape(20.dp)
    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            // 长按卡片直接弹二级菜单（右键/长按是移动端的「更多」惯用手势）
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        shape = shape
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 左侧赤陶色书脊
            Box(
                Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(extra.terracotta, extra.terracotta.copy(alpha = 0.30f))
                        )
                    )
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
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
