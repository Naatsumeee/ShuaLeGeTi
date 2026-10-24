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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.ui.bank.typeLabel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val vm: ImportViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, 0)
                .let { /* 非持久权限也不影响本次读取 */ }
            val name = queryName(context, uri)
            vm.setSourceName(name)
            vm.ingest(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            vm.setSourceName("拍照导入")
            vm.ingest(uri)
        }
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
                    "扫描版 PDF 会自动逐页 OCR（离线）；文字版直接抽取。识别后可用离线快解析或 AI 智能解析（双栏/复杂排版推荐 AI）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            ImportViewModel.Stage.INGESTING -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                state.progress?.let { p ->
                    Text(p.message, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else p.current.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } ?: Text("正在读取…")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            ImportViewModel.Stage.TEXT_READY, ImportViewModel.Stage.PARSING -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.rawText,
                    onValueChange = vm::updateRawText,
                    label = { Text("识别文本（可手动修正）") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                if (state.stage == ImportViewModel.Stage.PARSING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(state.progress?.message ?: "AI 解析中…")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = vm::parseOffline, modifier = Modifier.weight(1f)) {
                            Text("离线快解析")
                        }
                        Button(onClick = vm::parseAi, modifier = Modifier.weight(1f)) {
                            Text("AI 智能解析")
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            ImportViewModel.Stage.PREVIEW, ImportViewModel.Stage.SAVING -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("解析出 ${state.parsed?.size ?: 0} 道题", style = MaterialTheme.typography.titleMedium)
                        state.aiInfo?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    OutlinedButton(onClick = vm::backToText) { Text("重新解析") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.parsed.orEmpty()) { i, q ->
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
                    Button(
                        onClick = { vm.save(onDone) },
                        enabled = !state.parsed.isNullOrEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("确认导入 ${state.parsed?.size ?: 0} 题") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ParsedCard(index: Int, q: ParsedQuestion) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                AssistChip(onClick = {}, label = { Text(typeLabel(q.type)) })
                Spacer(Modifier.padding(2.dp))
                if (q.answer != null) AssistChip(onClick = {}, label = { Text("有答案") })
                else AssistChip(onClick = {}, label = { Text("无答案·待AI") })
                q.chapter?.let {
                    Spacer(Modifier.padding(2.dp))
                    AssistChip(onClick = {}, label = { Text(it.take(10)) })
                }
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
