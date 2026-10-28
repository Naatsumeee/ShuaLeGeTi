package com.examhelper.shua.ui.bank

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

@Composable
fun BankListScreen(
    onOpenBank: (Long) -> Unit,
    onImportText: () -> Unit,
    onImportFile: (String?) -> Unit,
    onSearch: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val vm: BankListViewModel = viewModel()
    val banks by vm.banks.collectAsState()
    val jobs by vm.jobs.collectAsState()
    var deleting by remember { mutableStateOf<QuestionBank?>(null) }
    var importMenuOpen by remember { mutableStateOf(false) }
    val extra = shuaColors
    val activeJobs = jobs.filter { it.stage != "FAILED" && it.stage != "CANCELED" }

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
            }

            if (banks.isEmpty() && activeJobs.isEmpty()) {
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
                        BankCard(bank = bank, onClick = { onOpenBank(bank.id) }, onDelete = { deleting = bank })
                    }
                }
            }
        }

        // 导入入口：玻璃胶囊，避开 Dock
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = contentPadding.calculateBottomPadding() + 78.dp)
        ) {
            GlassSurface(
                shape = RoundedCornerShape(22.dp),
                elevation = 16.dp,
                onClick = { importMenuOpen = true }
            ) {
                Box(Modifier.background(MaterialTheme.colorScheme.primary)) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "导入题库",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
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
}

@Composable
private fun BankCard(bank: QuestionBank, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val extra = shuaColors
    PaperCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        onClick = onClick
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
                Text(
                    bank.name,
                    style = serifTitle(16.sp, FontWeight.Medium),
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText("${bank.questionCount} 题")
                    Text(" · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        fmt.format(Date(bank.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Box {
                com.examhelper.shua.ui.theme.GlassIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "删除",
                    onClick = { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("删除题库") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}
