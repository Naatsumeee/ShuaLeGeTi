package com.examhelper.shua.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.BuildConfig
import com.examhelper.shua.R
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.settings.OcrEngine
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import com.examhelper.shua.ui.sound.Haptics
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private const val AUTHOR = "@Natsume"
private const val CONTACT_EMAIL = "xu.tianhao@outlook.com"
private const val PROJECT_URL = "https://github.com/Naatsumeee/ShuaLeGeTi"
internal val ABOUT_ICON_RES = R.drawable.ic_launcher_foreground

@Composable
fun SettingsScreen(contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val vm: SettingsViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val ui by vm.ui.collectAsState()
    val test by vm.test.collectAsState()
    val bal by vm.balance.collectAsState()
    val usage by vm.usage.collectAsState()
    val update by vm.update.collectAsState()
    val message by vm.message.collectAsState()
    var editing by remember { mutableStateOf<LlmProvider?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmClearFav by remember { mutableStateOf(false) }
    var confirmResetProgress by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    /** 用量情况的时间窗口：7 / 30 天 */
    var usageDays by remember { mutableIntStateOf(7) }
    var fontDraft by remember { mutableFloatStateOf(ui.fontScale) }
    var lastFontStep by remember { mutableIntStateOf((ui.fontScale * 20).roundToInt()) }
    LaunchedEffect(ui.fontScale) {
        fontDraft = ui.fontScale
        lastFontStep = (ui.fontScale * 20).roundToInt()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 学习记录导出：系统文件选择器
    val exportLauncher = rememberLauncherForActivityResult(
        // Creator 的 MIME 用 octet-stream，避免部分 ROM 强制给文件名补 .json，
        // 破坏用户要求的 *.shua 单文件观感。
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> vm.exportShuaPackage(uri) }

    val extra = shuaColors

    Box(Modifier.fillMaxSize()) {
        // 顶栏放在 LazyColumn 之外：LazyColumn 的 contentPadding 会把顶栏一起向内挤，
        // 导致这一页的标题卡比题库/错题本/统计窄一圈（左 28dp vs 12dp），必须拆开。
        Column(
            Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding() + 4.dp)
        ) {
            CompactTopBar(title = "设置")

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 2.dp, bottom = 110.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // ================= 显示与反馈 =================
            item { SectionLabel("显示与反馈") }
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingSwitchRow(
                            title = "震动反馈",
                            subtitle = "点选项、切题、判分时短促触感",
                            checked = ui.hapticEnabled,
                            onCheckedChange = vm::setHapticEnabled
                        )
                        if (ui.hapticEnabled) {
                            DividerLine()
                            Column(Modifier.padding(14.dp)) {
                                // 转子马达（或系统禁用了振幅控制）时，amplitude 会被系统忽略，
                                // 「强度」滑块怎么拖都是同一个手感。与其让用户反复试，
                                // 不如在这里直说 —— 这正是以前「强度调节失效」最容易被误判的来源。
                                HapticSliderRow(
                                    title = "震动强度",
                                    hint = "振幅 + 脉冲密度（震得多重）",
                                    value = ui.hapticStrength,
                                    onChange = vm::setHapticStrength
                                )
                                Spacer(Modifier.height(14.dp))
                                HapticSliderRow(
                                    title = "震动时长",
                                    hint = "只影响毫秒（震得多长）",
                                    value = ui.hapticDuration,
                                    onChange = vm::setHapticDuration
                                )
                            }
                        }
                        DividerLine()
                        SettingSwitchRow(
                            title = "暗黑护眼（橄榄柔光夜读）",
                            subtitle = if (ui.darkMode == null) "当前跟随系统 · 打开后强制橄榄柔光夜间配色"
                            else if (ui.darkMode == true) "已启用夜间配色" else "已锁定暖纸日间配色",
                            checked = ui.darkMode == true,
                            onCheckedChange = { vm.setDarkMode(if (it) true else false) }
                        )
                        if (ui.darkMode != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "改回跟随系统",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { vm.setDarkMode(null) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                        DividerLine()
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "字体大小",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                MonoText("${(fontDraft * 100).toInt()}%", color = extra.terracotta)
                            }
                            Slider(
                                value = fontDraft,
                                onValueChange = {
                                    val stepped = (it * 20).roundToInt() / 20f
                                    if (stepped != fontDraft) {
                                        fontDraft = stepped
                                        val step = (stepped * 20).roundToInt()
                                        if (step != lastFontStep) {
                                            lastFontStep = step
                                            Haptics.tick()
                                        }
                                    }
                                },
                                onValueChangeFinished = { vm.setFontScale(fontDraft) },
                                valueRange = 0.8f..1.5f
                            )
                        }
                    }
                }
            }

            // ================= 本地学习数据 =================
            item { SectionLabel("本地学习数据") }
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingActionRow(
                            title = "导出学习数据",
                            subtitle = "题库 + 作答历史 + 错题本 + 收藏 + 斩题，整合为一个 .shua 文件",
                            actionLabel = "导出",
                            icon = Icons.Filled.Save,
                            onClick = { exportLauncher.launch(vm.suggestedFileName()) }
                        )
                        DividerLine()
                        SettingActionRow(
                            title = "清空收藏夹",
                            subtitle = "只删除收藏标记，题目与作答记录不受影响",
                            actionLabel = "清空",
                            icon = Icons.Filled.Delete,
                            danger = true,
                            onClick = { confirmClearFav = true }
                        )
                        DividerLine()
                        SettingActionRow(
                            title = "重置刷题进度",
                            subtitle = "把四种模式的游标与随机队列归零，题目与错题保留",
                            actionLabel = "重置",
                            icon = Icons.Filled.Refresh,
                            danger = true,
                            onClick = { confirmResetProgress = true }
                        )
                    }
                }
            }

            // ================= 大模型配置 =================
            item { SectionLabel("大模型配置（三省六部）", trailing = "${providers.size} 个") }
            item {
                if (providers.isEmpty()) {
                    PaperCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("还没有配置模型", style = serifTitle(15.sp, FontWeight.Medium))
                            Text(
                                "添加后即可使用 AI 解析题目、生成答案与解析。支持 DeepSeek / GLM / Kimi / Qwen / OpenAI / Ollama 等任何 OpenAI 兼容接口。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { showAdd = true }, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("添加模型")
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        TextButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("再添加一个模型")
                        }
                    }
                }
            }

            // ================= 账户余额（DeepSeek 官方） =================
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "账户余额",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (bal.loading) {
                                Text(
                                    "查询中…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "查询",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { vm.refreshBalance() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            bal.text ?: "点「查询」读取 DeepSeek 官方余额（仅 api.deepseek.com 支持）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (bal.ok) {
                                true -> extra.terracotta
                                false -> extra.sage
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            // ================= 用量情况（近 7 / 30 天） =================
            //
            // DeepSeek 没有用量/账单查询接口（/user/usage、/user/bills、/billing 等实测全 404，
            // 只有 /user/balance），所以「近 N 天消费」只能自己算，两条独立算法并列展示：
            //   · 实际：窗口内余额下降量累加（充值回升跳过）—— 能对上官方账单
            //   · 估算：本地记录的 token 按峰谷价累加 —— 立刻有数、可拆构成
            // 两者差得远，本身就说明「端点没透传缓存字段」或「价目变了」。
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "用量情况",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (usage.loading) {
                                Text(
                                    "统计中…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "统计",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { vm.loadUsage(usageDays) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        // 窗口切换：7 天 / 30 天
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.examhelper.shua.data.llm.UsageStats.WINDOWS.forEach { d ->
                                val on = d == usageDays
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (on) extra.terracotta.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        )
                                        .border(
                                            1.dp,
                                            if (on) extra.terracotta.copy(alpha = 0.5f) else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            usageDays = d
                                            vm.loadUsage(d)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        "近 $d 天",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (on) extra.terracottaDeep
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        val w = usage.window
                        when {
                            usage.error != null -> Text(
                                usage.error!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.sage
                            )
                            w == null -> Text(
                                "选一个窗口后点「统计」。首次使用需要积累几天余额快照才能算出实际消费；" +
                                    "应用每次打开会采一次（每天最多一次），任务前后也会采。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    w.actualSpent?.let {
                                        "实际消费 ${com.examhelper.shua.data.generate.Cost.format(it)} ${w.currency}"
                                    } ?: "实际消费 —（余额快照不足，需至少 2 次采样）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (w.hasActual) extra.terracottaDeep
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    w.estimateSpent?.let {
                                        "本地估算 ${com.examhelper.shua.data.generate.Cost.format(it)} 元"
                                    } ?: "本地估算 —（该窗口内没有记录到用量的任务）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (w.hasEstimate) extra.amber
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (w.taskCount > 0) {
                                    Text(
                                        "共 ${w.taskCount} 个任务 · ${com.examhelper.shua.data.generate.Cost.tokens(w.totalTokens)} token" +
                                            "（命中 ${com.examhelper.shua.data.generate.Cost.tokens(w.hitTokens)} / " +
                                            "未命中 ${com.examhelper.shua.data.generate.Cost.tokens(w.missTokens)} / " +
                                            "输出 ${com.examhelper.shua.data.generate.Cost.tokens(w.outTokens)}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val hr = com.examhelper.shua.data.generate.Cost.hitRateOrNull(
                                        w.hitTokens, w.missTokens
                                    )
                                    Text(
                                        "缓存命中 ${hr?.let { "$it%" } ?: "—"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "「实际消费」= 当前主模型账号的余额下降量，DeepSeek 目前不提供按 API Key 单独查询消费的接口；" +
                                        "「本地估算」汇总本机所有 AI 调用（导入解析、答案生成、问 AI、深度/极简解析），按每次调用的模型、时间和 token 计算。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // ================= 三省六部开关 =================
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingSwitchRow(
                            title = "三省六部模式",
                            subtitle = "生成答案时多模型交叉验证，标注一致率与分歧",
                            checked = ui.sanShengLiuBu,
                            enabled = providers.count { it.enabled } >= 2,
                            onCheckedChange = vm::setSanShengLiuBu
                        )
                        if (providers.count { it.enabled } < 2) {
                            Text(
                                "需启用至少 2 个模型",
                                Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ================= OCR 与性能 =================
            item { SectionLabel("识别与性能") }
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("OCR 引擎", style = serifTitle(15.sp, FontWeight.Medium))
                        OcrOption(
                            selected = ui.ocrEngine == OcrEngine.ML_KIT,
                            title = "ML Kit（默认 · 轻量）",
                            desc = "离线免费，速度快，印刷体效果好",
                            onClick = { vm.setOcrEngine(OcrEngine.ML_KIT) }
                        )
                        OcrOption(
                            selected = ui.ocrEngine == OcrEngine.RAPID_OCR,
                            title = "RapidOCR 高精度（已内置）",
                            desc = "PP-OCRv5 离线模型，扫描件/双栏识别更准",
                            onClick = { vm.setOcrEngine(OcrEngine.RAPID_OCR) }
                        )
                    }
                }
            }
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Column {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "速刷自动切换",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                MonoText("${ui.speedSeconds} 秒", color = extra.terracotta)
                            }
                            Slider(
                                value = ui.speedSeconds.toFloat(),
                                onValueChange = { vm.setSpeedSeconds(it.toInt()) },
                                valueRange = 1f..10f,
                                steps = 8
                            )
                        }
                        DividerLine()
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "AI 解析并发",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                MonoText("${ui.parseConcurrency} 路", color = extra.terracotta)
                            }
                            Text(
                                "分块并行请求数。提速但更吃模型限流，报 429 时调低",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = ui.parseConcurrency.toFloat(),
                                onValueChange = { vm.setParseConcurrency(it.toInt()) },
                                valueRange = 1f..8f,
                                steps = 6
                            )
                        }
                        DividerLine()
                        // 思考强度：只影响「深度解题」「补全解析」，导入解析固定关。
                        //
                        // 档位按 DeepSeek 官方口径给 none/low/high/max —— 没有 medium，
                        // 官方把 medium 映射成 high，给了反而让人误以为选了更省的档。
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "深度解题 / 补全解析 · 思考强度",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                MonoText(ui.reasoningEffort.label, color = extra.terracotta)
                            }
                            Text(
                                "越高越会推演但也越慢越贵。导入解析固定关闭思考（结构化抽取用不上，白烧输出预算）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ReasonEffortSelector(
                                current = ui.reasoningEffort,
                                onSelect = vm::setReasoningEffort
                            )
                        }
                    }
                }
            }

            // ================= 高级：提示词 =================
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    SettingActionRow(
                        title = "自定义系统提示词",
                        subtitle = "覆盖内置的解析/解题提示词，善用变量占位符",
                        actionLabel = if (showPrompts) "收起" else "展开",
                        icon = Icons.Filled.Add,
                        onClick = { showPrompts = !showPrompts }
                    )
                }
            }
            if (showPrompts) {
                item {
                    PromptEditor(
                        title = "题库解析提示词",
                        default = com.examhelper.shua.data.llm.Prompts.PARSE_DEFAULT,
                        load = { it.customParsePrompt },
                        save = vm::setCustomParsePrompt
                    )
                }
                item {
                    PromptEditor(
                        title = "答题生成提示词",
                        default = com.examhelper.shua.data.llm.Prompts.ANSWER_DEFAULT,
                        load = { it.customAnswerPrompt },
                        save = vm::setCustomAnswerPrompt
                    )
                }
            }

            // ================= 关于 =================
            item { SectionLabel("关于") }
            item {
                AboutCard(
                    onOpen = vm::openUrl,
                    checkingUpdate = update.checking,
                    updateInfo = update.info,
                    onCheckUpdate = vm::checkUpdate,
                    onDismissUpdate = vm::dismissUpdate
                )
            }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
        )
    }

    if (showAdd) {
        ProviderEditDialog(
            initial = LlmProvider(
                name = "",
                // DeepSeek 官方端点：不带 /v1 的短形式，客户端拼 /chat/completions 即为官方地址
                baseUrl = "https://api.deepseek.com",
                apiKey = "",
                // deepseek-chat 已下线，当前可用的是 deepseek-flash / deepseek-v4-pro
                model = "deepseek-flash"
            ),
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
    if (confirmClearFav) {
        ConfirmDialog(
            title = "清空收藏夹",
            text = "将删除全部收藏标记，题目和作答记录不受影响。不可撤销，确定继续？",
            confirmText = "清空",
            onDismiss = { confirmClearFav = false },
            onConfirm = {
                confirmClearFav = false
                vm.clearFavorites()
            }
        )
    }
    if (confirmResetProgress) {
        ConfirmDialog(
            title = "重置刷题进度",
            text = "四种模式的当前题号和随机队列都会归零，下次进入将从第一题开始。题目、错题与统计保留。",
            confirmText = "重置",
            onDismiss = { confirmResetProgress = false },
            onConfirm = {
                confirmResetProgress = false
                vm.resetStudyProgress()
            }
        )
    }
}

// ---------------- 组件 ----------------

@Composable
private fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(shuaColors.creamLine)
    )
}

/**
 * 1–10 档震动滑块。
 *
 * 为什么用滑块而不是三档 chip：用户明确要求「改成 1-10 档滑动条，划到哪就按
 * 设置后的情况震一下作预览」。拖动过程中每跨一档就震一次（值真的变了才震），
 * 这样调的时候手里能立刻感觉到这一档是什么手感。
 */
@Composable
private fun HapticSliderRow(
    title: String,
    hint: String,
    value: Int,
    onChange: (Int) -> Unit
) {
    val extra = shuaColors
    // 用 float 承载滑块位置，松手/跨档时再取整，避免 Slider 抖动
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var lastPreview by remember { mutableIntStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            MonoText("$value / 10", color = extra.terracotta)
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Slider(
            value = pos,
            onValueChange = { f ->
                pos = f
                val v = f.roundToInt().coerceIn(1, 10)
                if (v != value) {
                    onChange(v)
                    // 预览：每跨一档震一下，跟着手指走
                    if (v != lastPreview) {
                        lastPreview = v
                        Haptics.preview()
                    }
                }
            },
            onValueChangeFinished = { onChange(pos.roundToInt().coerceIn(1, 10)) },
            valueRange = 1f..10f,
            steps = 8,   // 10 个整点之间的 8 个刻度
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            colors = SliderDefaults.colors(
                thumbColor = extra.terracotta,
                activeTrackColor = extra.terracotta,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * 思考强度选择器：四档横排 chip。
 *
 * 只给 DeepSeek 真实支持的 none/low/high/max 四档 —— 官方文档明确
 * `medium` 会被映射成 `high`，多给一档等于让用户以为选了个更省的选项。
 */
@Composable
private fun ReasonEffortSelector(
    current: com.examhelper.shua.data.llm.ReasoningEffort,
    onSelect: (com.examhelper.shua.data.llm.ReasoningEffort) -> Unit
) {
    val extra = shuaColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.examhelper.shua.data.llm.ReasoningEffort.entries.forEach { e ->
            val on = e == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (on) extra.terracotta.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .border(
                        1.dp,
                        if (on) extra.terracotta.copy(alpha = 0.55f) else Color.Transparent,
                        RoundedCornerShape(11.dp)
                    )
                    .clickable {
                        com.examhelper.shua.ui.sound.Haptics.tap()
                        onSelect(e)
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    e.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) extra.terracottaDeep else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Text(
        "Off 最快最省；High/Max 适合病例分析类深解题，但输出预算有限时容易写不全",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val extra = shuaColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (danger) extra.sageSoft else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (danger) extra.sage else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (danger) extra.sage else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (danger) extra.sage else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun OcrOption(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PromptEditor(
    title: String,
    default: String,
    load: (com.examhelper.shua.data.settings.AppSettings) -> String,
    save: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = load(
            com.examhelper.shua.ShuaApp.instance.container.settings.settings.first()
        ).ifBlank { default }
    }
    PaperCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = serifTitle(14.sp, FontWeight.Medium))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
                shape = RoundedCornerShape(12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(text) }, shape = RoundedCornerShape(10.dp)) { Text("保存") }
                TextButton(onClick = {
                    text = default
                    save(default)
                }) { Text("恢复默认") }
            }
        }
    }
}

@Composable
private fun AboutCard(
    onOpen: (String) -> Unit,
    checkingUpdate: Boolean,
    updateInfo: com.examhelper.shua.data.update.UpdateInfo?,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: () -> Unit
) {
    val extra = shuaColors
    var showChangelog by remember { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1B6E53)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(ABOUT_ICON_RES),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("刷了个题", style = serifTitle(18.sp, FontWeight.Medium))
                }
                SemanticChip("v${BuildConfig.VERSION_NAME}", ChipTone.Brand)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))

            AboutLine("作者", AUTHOR, onClick = null)
            AboutLine("反馈邮箱", CONTACT_EMAIL, onClick = { onOpen("mailto:$CONTACT_EMAIL") })
            AboutLine("项目地址", PROJECT_URL, onClick = { onOpen(PROJECT_URL) })
            AboutLine("版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onClick = null)
            AboutLine(
                "检查更新",
                if (checkingUpdate) "正在查询 GitHub Release…" else "检查是否有新版本",
                onClick = if (checkingUpdate) null else onCheckUpdate
            )
            AboutLine(
                "更新日志",
                "查看历次版本改动（共 ${RELEASE_NOTES.size} 个版本）",
                onClick = { showChangelog = true }
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))

            Text(
                "开源声明",
                style = serifTitle(13.sp, FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "本项目以 MIT License 开源，源码公开于 GitHub，欢迎查阅与共建。" +
                    "OCR 采用 Google ML Kit（离线中文识别）与 RapidOCR（PP-OCRv5，Apache-2.0）。" +
                    "题库内容与解析结果均由用户自行导入或 AI 生成，仅用于个人学习，" +
                    "请遵守所使用模型服务商的条款与当地法律法规。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
            Text(
                "© 2026 刷了个题 · Made with care by $AUTHOR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    if (showChangelog) {
        ChangelogDialog(notes = RELEASE_NOTES, onDismiss = { showChangelog = false })
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("检查到新版本") },
            text = {
                Text(
                    "检查到新版本 v${info.version}" +
                        info.versionCode?.let { "（versionCode $it）" }.orEmpty() +
                        "，是否下载更新？\n\n更新日志：\n" +
                        info.notes.ifBlank { "暂无更新说明" }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onOpen(info.downloadUrl.ifBlank { info.releaseUrl })
                    onDismissUpdate()
                }) { Text("下载更新") }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text("稍后") } }
        )
    }
}

/** 更新日志弹窗：按版本倒序，重点条目用彩色小标签区分新增 / 优化 / 修复 */
@Composable
private fun ChangelogDialog(notes: List<ReleaseNote>, onDismiss: () -> Unit) {
    val extra = shuaColors
    Dialog(onDismissRequest = onDismiss) {
        PaperCard(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(22.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("更新日志", style = serifTitle(18.sp, FontWeight.Medium))
                    Text(
                        "记录每一次能感知到的变化",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(notes.size) { index ->
                    val note = notes[index]
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "v${note.version}",
                                style = serifTitle(16.sp, FontWeight.Medium),
                                color = if (note.current) extra.terracotta else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                note.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (note.current) {
                                Spacer(Modifier.width(8.dp))
                                SemanticChip("当前版本", ChipTone.Brand)
                            }
                        }
                        note.items.forEach { item ->
                            Row(Modifier.fillMaxWidth()) {
                                NoteTag(item.kind)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteTag(kind: NoteItem.Kind) {
    val extra = shuaColors
    val (label, color) = when (kind) {
        NoteItem.Kind.NEW -> "新增" to extra.terracotta
        NoteItem.Kind.IMPROVE -> "优化" to extra.amber
        NoteItem.Kind.FIX -> "修复" to extra.sage
    }
    Box(
        Modifier
            .width(34.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
    }
}

@Composable
private fun AboutLine(label: String, value: String, onClick: (() -> Unit)?) {
    val extra = shuaColors
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                else Modifier
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = if (onClick != null) extra.terracotta else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            Icon(
                if (value.contains("@")) Icons.Filled.Email else Icons.Filled.Link,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = extra.terracotta
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = serifTitle(17.sp, FontWeight.Medium)) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.name.ifBlank { "未命名" },
                            style = serifTitle(14.sp, FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (provider.isPrimary) {
                            Spacer(Modifier.width(6.dp))
                            SemanticChip("主部", ChipTone.Brand)
                        }
                        if (!provider.enabled) {
                            Spacer(Modifier.width(6.dp))
                            SemanticChip("已停用", ChipTone.Neutral)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${provider.model} @ ${provider.baseUrl.removePrefix("https://").take(28)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            testResult?.let { tr ->
                if (!tr.testing) {
                    Text(
                        tr.message.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tr.ok == true) extra.terracotta else extra.sage
                    )
                } else {
                    Text(
                        "测试中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onTest) { Text("测试", style = MaterialTheme.typography.labelMedium) }
                if (!provider.isPrimary) {
                    TextButton(onClick = onSetPrimary) { Text("设为主部", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text("删除", style = MaterialTheme.typography.labelMedium, color = extra.sage)
                }
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
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                if (initial.id == 0L) "添加模型" else "编辑模型",
                style = serifTitle(17.sp, FontWeight.Medium)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（如：吏部·DeepSeek）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    }
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名（如 deepseek-chat）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            baseUrl = baseUrl.trim().removeSuffix("/"),
                            apiKey = apiKey.trim(),
                            model = model.trim()
                        )
                    )
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() &&
                    apiKey.isNotBlank() && model.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
