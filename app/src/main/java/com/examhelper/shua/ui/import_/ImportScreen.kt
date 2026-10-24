package com.examhelper.shua.ui.import_

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.ui.bank.typeLabel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    jobId: String? = null,
    onDone: () -> Unit
) {
    val vm: ImportViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(jobId) {
        if (jobId != null) vm.resumeJob(jobId)
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, 0) }
            val name = queryName(context, uri)
            vm.filePicked(uri, name)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.filePicked(uri, "拍照导入.jpg")
    }

    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBankFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入题库") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when (state.stage) {
            ImportViewModel.Stage.IDLE -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("选择题库来源", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        filePicker.launch(arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "image/*"
                        ))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择文件（PDF / docx / 图片）") }
                OutlinedButton(
                    onClick = {
                        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", file
                        )
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("拍照导入") }
                OutlinedButton(
                    onClick = { jsonPicker.launch(arrayOf("application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导入标准题库文件（.json，本 App 导出格式）") }
                Text(
                    "扫描 PDF 自动逐页离线 OCR；解析在后台进行，切出应用/锁屏不会中断（状态栏有进度通知），完成后回来确认入库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            ImportViewModel.Stage.CHOOSING_PARSE -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("已选择：${state.sourceName}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.startPipeline("ai") }, modifier = Modifier.fillMaxWidth()) {
                            Text("AI 智能解析（推荐 · 后台运行）")
                        }
                        Text(
                            if (state.providersReady)
                                "主模型解析，${state.concurrency} 路并行，可切出应用。支持 A1/A2/A3/B 型题分组识别。"
                            else
                                "尚未配置模型——请先到\"设置\"添加，或改用离线解析。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.providersReady) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { vm.startPipeline("offline") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("离线快解析（规整文本）") }
                    }
                }
                OutlinedButton(onClick = vm::cancelChoosing, modifier = Modifier.fillMaxWidth()) {
                    Text("返回重选")
                }
            }

            ImportViewModel.Stage.RUNNING -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val job = state.job
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (job?.stage) {
                                        "INGEST" -> "正在识别文件（OCR）…"
                                        "PARSE" -> "AI 后台解析中"
                                        "FAILED" -> "解析失败"
                                        "CANCELED" -> "已取消"
                                        else -> "准备中…"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                job?.message?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        if ((job?.totalUnits ?: 0) > 0) {
                            LinearProgressIndicator(
                                progress = { job!!.doneUnits.toFloat() / job.totalUnits },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${job!!.doneUnits}/${job.totalUnits} 段 · 已解析 ${job.parsedCount} 题",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "任务在后台运行：可以切出应用、锁屏甚至杀掉界面，回到这里继续。完成后通知栏会提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (job?.stage == "FAILED") {
                            job.errorMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = vm::resumeJob) { Text("重试") }
                        } else {
                            OutlinedButton(onClick = vm::cancelJob) { Text("取消任务") }
                        }
                        // 分析过程日志
                        if (state.log.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("分析过程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                state.log.takeLast(6).forEach { line ->
                                    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
                if (state.preview.isNotEmpty()) {
                    Text("已解析（实时）", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(state.preview.takeLast(100)) { i, q ->
                            ParsedCard(state.preview.size - (state.preview.takeLast(100)).size + i + 1, q)
                        }
                    }
                }
            }

            ImportViewModel.Stage.PREVIEW, ImportViewModel.Stage.SAVING -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("解析出 ${state.preview.size} 道题", style = MaterialTheme.typography.titleMedium)
                        state.job?.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (state.job?.stage == "FAILED" || state.job?.stage == "CANCELED") {
                        TextButton(onClick = vm::resumeJob) { Text("补齐失败段") }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.preview) { i, q ->
                        ParsedCard(i + 1, q)
                    }
                }
                if (state.stage == ImportViewModel.Stage.SAVING) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp)); Text("导入中…")
                    }
                } else {
                    OutlinedTextField(
                        value = state.bankName,
                        onValueChange = vm::updateBankName,
                        label = { Text("题库名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = { vm.save(onDone) },
                        enabled = state.preview.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("确认导入 ${state.preview.size} 题") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ParsedCard(index: Int, q: ParsedLine) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                AssistChip(onClick = {}, label = {
                    Text(
                        runCatching { typeLabel(QuestionType.valueOf(q.type)) }.getOrDefault("单选") +
                            (q.subtype?.let { " · $it" } ?: "")
                    )
                })
                Spacer(Modifier.padding(2.dp))
                if (q.answer != null) AssistChip(onClick = {}, label = { Text("有答案") })
                else AssistChip(onClick = {}, label = { Text("无答案·待AI") })
                q.chapter?.let {
                    Spacer(Modifier.padding(2.dp))
                    AssistChip(onClick = {}, label = { Text(it.take(10)) })
                }
            }
            q.sharedStem?.let {
                Text(
                    "[共用题干] ${it.take(40)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (q.sharedOptions.isNotEmpty()) {
                Text(
                    "[共用选项] " + q.sharedOptions.joinToString("  ") { "${it.key}.${it.text.take(10)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(
                "$index. ${q.stem.take(60)}${if (q.stem.length > 60) "…" else ""}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (q.options.isNotEmpty()) {
                Text(
                    q.options.joinToString("  ") { "${it.key}.${it.text.take(12)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            q.answer?.let {
                Text("答案：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun queryName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: ""
    } ?: ""
}.getOrDefault("")
