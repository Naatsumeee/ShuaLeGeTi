package com.examhelper.shua.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.settings.OcrEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val ui by vm.ui.collectAsState()
    val test by vm.test.collectAsState()
    var editing by remember { mutableStateOf<LlmProvider?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加模型") }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- 模型管理（三省六部） ----
            SectionTitle("大模型配置（三省六部）", "添加多个 OpenAI 兼容模型；选一个作主部（高权重参考）")
            if (providers.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "还没有配置模型。添加后即可使用 AI 解析题目、生成答案与解析。\n支持 DeepSeek / GLM / Kimi / Qwen / OpenAI / Ollama 等任何 OpenAI 兼容接口。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                providers.forEach { p ->
                    ProviderCard(
                        provider = p,
                        testResult = test?.takeIf { it.providerId == p.id },
                        onEdit = { editing = p },
                        onDelete = { vm.deleteProvider(p.id) },
                        onSetPrimary = { vm.setPrimary(p.id) },
                        onToggleEnabled = { vm.toggleEnabled(p) },
                        onTest = { vm.testConnection(p) }
                    )
                }
            }

            // ---- 三省六部开关 ----
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("三省六部模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "生成答案时多模型交叉验证，标注一致率与分歧",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = ui.sanShengLiuBu,
                        onCheckedChange = vm::setSanShengLiuBu,
                        enabled = providers.count { it.enabled } >= 2
                    )
                }
                if (providers.count { it.enabled } < 2) {
                    Text(
                        "需启用至少 2 个模型",
                        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // ---- OCR 引擎 ----
            SectionTitle("OCR 引擎", "扫描件/照片导入时的文字识别引擎")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.ocrEngine == OcrEngine.ML_KIT, onClick = { vm.setOcrEngine(OcrEngine.ML_KIT) })
                        Column {
                            Text("ML Kit（默认 · 轻量）")
                            Text("离线免费，速度快，印刷体效果好", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.ocrEngine == OcrEngine.RAPID_OCR, onClick = null, enabled = false)
                        Column {
                            Text("RapidOCR 高精度（即将上线）")
                            Text("PP-OCR 模型，复杂扫描件准确率更高", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            // ---- 速刷秒数 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("速刷自动切换：${ui.speedSeconds} 秒", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = ui.speedSeconds.toFloat(),
                        onValueChange = { vm.setSpeedSeconds(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
            }

            Spacer(Modifier.padding(48.dp))
        }
    }

    if (showAdd) {
        ProviderEditDialog(
            initial = LlmProvider(name = "", baseUrl = "https://api.deepseek.com/v1", apiKey = "", model = "deepseek-chat"),
            onDismiss = { showAdd = false },
            onSave = {
                vm.saveProvider(it)
                showAdd = false
            }
        )
    }
    editing?.let { p ->
        ProviderEditDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = {
                vm.saveProvider(it)
                editing = null
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ProviderCard(
    provider: LlmProvider,
    testResult: SettingsViewModel.TestResult?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit,
    onToggleEnabled: () -> Unit,
    onTest: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleSmall)
                        if (provider.isPrimary) {
                            Spacer(Modifier.padding(2.dp))
                            Text(
                                "主部",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        "${provider.model} @ ${provider.baseUrl.removePrefix("https://").take(30)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = onTest) {
                    Text(when {
                        testResult?.testing == true -> "测试中…"
                        else -> "测试"
                    })
                }
                IconButton(onClick = onToggleEnabled) {
                    Switch(checked = provider.enabled, onCheckedChange = null)
                }
            }
            testResult?.let { tr ->
                if (!tr.testing) {
                    Text(
                        tr.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tr.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (!provider.isPrimary) {
                    TextButton(onClick = onSetPrimary) { Text("设为主部") }
                }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: LlmProvider,
    onDismiss: () -> Unit,
    onSave: (LlmProvider) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "添加模型" else "编辑模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（如：吏部·DeepSeek）") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL（如 https://api.deepseek.com/v1）") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    }
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名（如 deepseek-chat）") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(initial.copy(name = name.trim(), baseUrl = baseUrl.trim().removeSuffix("/"), apiKey = apiKey.trim(), model = model.trim()))
            }, enabled = name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
