package com.examhelper.shua.ui.import_

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.llm.Prompts
import com.examhelper.shua.data.model.ParsedLine
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
import java.io.File

@Composable
fun ImportScreen(
    jobId: String? = null,
    onDone: () -> Unit
) {
    val vm: ImportViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val extra = shuaColors
    // 复制提示词是「做完了但界面无变化」的操作，必须有可见反馈
    var promptCopied by remember { mutableStateOf(false) }
    LaunchedEffect(promptCopied) {
        if (promptCopied) {
            kotlinx.coroutines.delay(4000)
            promptCopied = false
        }
    }

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

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(
            title = when (state.stage) {
                ImportViewModel.Stage.IDLE -> "导入题库"
                ImportViewModel.Stage.CHOOSING_PARSE -> "选择解析方式"
                ImportViewModel.Stage.RUNNING -> "解析中"
                else -> "确认导入"
            },
            onBack = onDone
        )

        when (state.stage) {
            ImportViewModel.Stage.IDLE -> Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                PrimaryAction("选择文件 · PDF / docx / Markdown / 图片", extra.terracotta) {
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    filePicker.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            // .md / .txt：不同 ROM 的 DocumentsProvider 给的 MIME 不统一，
                            // 三个都列上，实在没有的靠文件名扩展名兜底（见 DocumentIngestor）
                            "text/markdown",
                            "text/plain",
                            "text/x-markdown",
                            "image/*"
                        )
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("拍照导入", Modifier.weight(1f)) {
                        // 拍照输出统一落到 cacheDir/temp/camera（FileProvider 只暴露这一个子目录）
                        val file = com.examhelper.shua.data.storage.AppStorage.newCameraPhoto(context)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    }
                    SecondaryAction("导入 .json", Modifier.weight(1f)) {
                        jsonPicker.launch(arrayOf("application/json"))
                    }
                }
                // 没有电脑时不至于卡死：把格式规范复制给任意大模型，让它把 OCR 出来的
                // 乱文本整理成 Markdown，再回来用「选择文件」导入 .md
                SecondaryAction(
                    if (promptCopied) "已复制到剪贴板 ✓" else "复制格式化提示词给 AI",
                    Modifier.fillMaxWidth()
                ) {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("题库格式化提示词", Prompts.MARKDOWN_CONVERT)
                    )
                    promptCopied = true
                }
                if (promptCopied) {
                    Text(
                        "已复制。粘给任意 AI（ChatGPT / DeepSeek / Kimi 都行），让它按格式整理题目，" +
                            "把结果存成 .md 再用「选择文件」导入。",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.terracottaDeep
                    )
                }
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), elevation = 8.dp) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                        SemanticChip("提示", ChipTone.Amber)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "扫描 PDF 会逐页离线 OCR；解析在后台运行，切出应用或锁屏都不会中断，完成后状态栏提醒。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.error?.let { Text(it, color = extra.sage, style = MaterialTheme.typography.bodySmall) }
            }

            ImportViewModel.Stage.CHOOSING_PARSE -> Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = vm::updateBankName,
                    label = { Text("题库名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                // Markdown / 纯文本本来就有文字，不需要 OCR —— 这种情况下把引擎选择整块隐藏，
                // 否则用户会以为还要等一次「识别」，白担心一遍进度。
                val lower = state.sourceName.lowercase()
                val plainTextSource = lower.endsWith(".md") || lower.endsWith(".markdown") ||
                    lower.endsWith(".txt") || lower.endsWith(".text")
                if (!plainTextSource) {
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("OCR 引擎", style = serifTitle(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                                SemanticChip(if (state.ocrEngine == "mlkit") "ML Kit" else "RapidOCR", ChipTone.Brand)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EngineTab("ML Kit · 快", state.ocrEngine == "mlkit", Modifier.weight(1f)) { vm.setOcrEngine("mlkit") }
                                EngineTab("RapidOCR · 准", state.ocrEngine == "rapidocr", Modifier.weight(1f)) { vm.setOcrEngine("rapidocr") }
                            }
                            Text(
                                "扫描件质量差、双栏密集或 ML Kit 识别不理想时建议 RapidOCR（PP-OCRv5，离线）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    Text(
                        "文本类文件直接读取，不需要 OCR。Markdown 的 # 标题会作为章节，**粗体**、表格、代码围栏等标记在解析前统一清理；公式 \$…\$ 原样保留。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                PrimaryAction("AI 智能解析（推荐 · 后台运行）", extra.terracotta) { vm.startPipeline("ai") }
                Text(
                    if (state.providersReady)
                        "主模型解析，${state.concurrency} 路并行，可切出应用。支持 A1/A2/A3/B 型题分组识别。"
                    else
                        "尚未配置模型——请先到设置添加，或改用离线解析。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.providersReady) MaterialTheme.colorScheme.outline else extra.sage
                )
                SecondaryAction("离线快解析（规整文本）", Modifier.fillMaxWidth()) { vm.startPipeline("offline") }
                TextButton(onClick = vm::cancelChoosing, modifier = Modifier.fillMaxWidth()) {
                    Text("返回重选", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            ImportViewModel.Stage.RUNNING -> {
                val job = state.job
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 10.dp) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = extra.terracotta
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    when (job?.stage) {
                                        "INGEST" -> "正在识别文件（OCR）…"
                                        "PARSE" -> "AI 后台解析中"
                                        "FAILED" -> "解析失败"
                                        "CANCELED" -> "已取消"
                                        else -> "准备中…"
                                    },
                                    style = serifTitle(15.sp, FontWeight.Medium)
                                )
                            }
                            job?.message?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (job != null && job.totalUnits > 0) {
                                val total = job.totalUnits
                                val done = job.doneUnits
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(done.toFloat() / total)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(extra.terracotta)
                                    )
                                }
                                Text(
                                    "$done/$total 段 · 已解析 ${job.parsedCount} 题",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "后台运行中，可以切出应用或锁屏，回来继续。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (job?.stage == "FAILED") {
                                job.errorMessage?.let {
                                    Text(it, color = extra.sage, style = MaterialTheme.typography.labelSmall)
                                }
                                SecondaryAction("重试", Modifier.fillMaxWidth()) { vm.resumeJob() }
                            } else {
                                SecondaryAction("取消任务", Modifier.fillMaxWidth()) { vm.cancelJob() }
                            }
                        }
                    }
                    if (state.log.isNotEmpty()) {
                        state.log.takeLast(5).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.preview.isNotEmpty()) {
                        SectionLabel("已解析（实时）", trailing = "${state.preview.size} 题")
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)
                        ) {
                            itemsIndexed(state.preview.takeLast(100)) { i, q ->
                                ParsedCard(state.preview.size - (state.preview.takeLast(100)).size + i + 1, q)
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            ImportViewModel.Stage.PREVIEW, ImportViewModel.Stage.SAVING -> Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "解析出 ${state.preview.size} 道题",
                            style = serifTitle(17.sp, FontWeight.Medium)
                        )
                        state.job?.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.job?.stage == "FAILED" || state.job?.stage == "CANCELED") {
                        TextButton(onClick = vm::resumeJob) { Text("补齐失败段", color = extra.terracotta) }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    itemsIndexed(state.preview) { i, q -> ParsedCard(i + 1, q) }
                }
                if (state.stage == ImportViewModel.Stage.SAVING) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = extra.terracotta)
                        Spacer(Modifier.width(8.dp))
                        Text("导入中…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    OutlinedTextField(
                        value = state.bankName,
                        onValueChange = vm::updateBankName,
                        label = { Text("题库名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    PrimaryAction("确认导入 ${state.preview.size} 题", extra.terracotta, enabled = state.preview.isNotEmpty()) {
                        vm.save(onDone)
                    }
                }
                state.error?.let { Text(it, color = extra.sage, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PrimaryAction(text: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SecondaryAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, shuaColors.creamLine),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

@Composable
private fun EngineTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val extra = shuaColors
    Box(
        modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) extra.terracotta else MaterialTheme.colorScheme.surfaceVariant)
            .then(Modifier.padding(0.dp))
            .clickableTab(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun Modifier.clickableTab(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun ParsedCard(index: Int, q: ParsedLine) {
    val extra = shuaColors
    val compact = isCompactWidth()
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(21.dp)
                        .clip(CircleShape)
                        .background(extra.terracottaSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$index", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
                }
                SemanticChip(
                    runCatching { typeLabel(QuestionType.valueOf(q.type)) }.getOrDefault("单选") +
                        (q.subtype?.let { "·$it" } ?: ""),
                    ChipTone.Brand
                )
                if (q.answer != null) SemanticChip("有答案", ChipTone.Neutral)
                else SemanticChip("待AI", ChipTone.Amber)
                if (!compact) {
                    q.chapter?.let {
                        SemanticChip(it.take(8), ChipTone.Neutral)
                    }
                }
            }
            q.sharedStem?.let {
                Text(
                    "共用题干 ${it.take(38)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.amber
                )
            }
            Text(q.stem, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (q.options.isNotEmpty()) {
                Text(
                    q.options.joinToString("  ") { "${it.key}.${it.text.take(10)}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            q.answer?.let {
                Text("答案 $it", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
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

