package com.examhelper.shua.ui.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionBank
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankListScreen(
    onOpenBank: (Long) -> Unit,
    onImportText: () -> Unit,
    onImportFile: (String?) -> Unit,
    onSearch: () -> Unit
) {
    val vm: BankListViewModel = viewModel()
    val banks by vm.banks.collectAsState()
    val jobs by vm.jobs.collectAsState()
    var deleting by remember { mutableStateOf<QuestionBank?>(null) }
    var importMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刷了个题") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { importMenuOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("导入题库") }
                )
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
    ) { padding ->
        if (banks.isEmpty() && jobs.none { it.stage != "FAILED" && it.stage != "CANCELED" }) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    Text("还没有题库", style = MaterialTheme.typography.titleMedium)
                    Text("点击右下角\"导入题库\"开始：支持 PDF / docx / 拍照 / 扫描件（OCR）与文本粘贴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                jobs.filter { it.stage != "FAILED" && it.stage != "CANCELED" }.forEach { job ->
                    item(key = "job_${job.id}") {
                        Card(
                            onClick = { onImportFile(job.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    when (job.stage) {
                                        "DONE" -> "《${job.bankName}》解析完成，待确认导入（${job.parsedCount} 题）"
                                        "PARSE" -> "《${job.bankName}》后台解析中 ${job.doneUnits}/${job.totalUnits} 段 · 已得 ${job.parsedCount} 题"
                                        else -> "《${job.bankName}》正在识别文件…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
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

    deleting?.let { bank ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除题库") },
            text = { Text("确定删除\"${bank.name}\"？该题库全部 ${bank.questionCount} 道题、进度与错题记录都会被删除，不可恢复。") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankCard(bank: QuestionBank, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bank.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${bank.questionCount} 题 · ${fmt.format(Date(bank.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
                }
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
