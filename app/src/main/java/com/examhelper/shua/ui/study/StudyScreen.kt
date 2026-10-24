package com.examhelper.shua.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    bankId: Long,
    modeName: String,
    onBack: () -> Unit
) {
    val vm: StudyViewModel = viewModel()
    val state by vm.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.bankName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (state.mode) {
                                StudyMode.SEQUENTIAL -> "标准做题"
                                StudyMode.SPEED -> "速刷模式"
                                StudyMode.RECITE -> "背题模式"
                                StudyMode.RANDOM -> "随机巩固"
                                StudyMode.EXAM -> "测试模式"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 斩题
                    IconButton(onClick = vm::toggleKill) {
                        Icon(
                            Icons.Filled.ContentCut,
                            contentDescription = "斩题",
                            tint = if (state.killed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (state.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "收藏",
                            tint = if (state.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                        )
                    }
                    if (state.mode != StudyMode.RANDOM && state.session?.total ?: 0 > 1) {
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Filled.Apps, contentDescription = "答题卡")
                        }
                    }
                    if (state.mode == StudyMode.RANDOM) {
                        IconButton(onClick = vm::resetRandom) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重置进度")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.session?.finished == true -> FinishedView(
                mode = state.mode,
                total = state.session?.total ?: 0,
                onBack = onBack,
                onRestart = if (state.mode == StudyMode.RANDOM) vm::resetRandom else vm::restart
            )

            else -> {
                val q = state.session?.question
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                ) {
                    // 进度
                    val session = state.session
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (state.mode == StudyMode.RANDOM)
                                "已巩固 ${session?.doneCount ?: 0} / ${session?.total ?: 0}"
                            else
                                "第 ${session?.indexInQueue ?: 0} / ${session?.total ?: 0} 题",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.mode == StudyMode.RANDOM) {
                            Text("做完不再出现", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            val total = session?.total ?: 0
                            if (total == 0) 0f else (session?.doneCount ?: 0).toFloat() / total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.filterSummary?.let {
                        Text(
                            "筛选：$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (q == null) {
                            Text("题目加载中…")
                        } else {
                            QuestionBody(q = q, state = state, vm = vm)
                        }
                    }

                    BottomControls(state = state, vm = vm, onBack = onBack)
                }
            }
        }

        // 答题卡（顺序类模式快速跳题）
        if (showSheet && state.mode != StudyMode.RANDOM) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val session = state.session
                    Text(
                        "答题卡（${session?.doneCount ?: 0}/${session?.total ?: 0} 已完成）",
                        style = MaterialTheme.typography.titleMedium
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        val total = session?.total ?: 0
                        items((0 until total).toList()) { i ->
                            val isCurrent = i + 1 == session?.indexInQueue
                            val isDone = i < (session?.doneCount ?: 0)
                            Surface(
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isDone -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(4.dp).size(40.dp),
                                onClick = {
                                    vm.jumpTo(i + 1)
                                    showSheet = false
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${i + 1}",
                                        color = if (isCurrent) androidx.compose.ui.graphics.Color.White
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionBody(q: Question, state: StudyUiState, vm: StudyViewModel) {
    val options = JsonCodec.decodeOptions(q.optionsJson)

    // A3 共用题干
    q.sharedStem?.let {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("共用题干（A3 型）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: "")) })
                q.chapter?.let { AssistChip(onClick = {}, label = { Text(it.take(12)) }) }
            }
            Text(q.stem, style = MaterialTheme.typography.bodyLarge)
        }
    }

    // B 型共用选项
    q.sharedOptionsJson?.let {
        com.examhelper.shua.ui.exam.SharedOptionsCard(it)
    }

    when (q.type) {
        QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE -> {
            options.forEach { opt ->
                val selected = opt.key in state.selected
                val isAnswer = state.revealed && !q.answer.isNullOrBlank() &&
                    q.answer!!.uppercase().contains(opt.key)
                val isWrongPick = state.answered && selected && !isAnswer
                OptionRow(
                    key = opt.key,
                    text = if (q.type == QuestionType.JUDGE && opt.text.length <= 2) judgeLabel(opt.text) else opt.text,
                    selected = selected,
                    revealed = state.revealed,
                    isAnswer = isAnswer,
                    isWrongPick = isWrongPick,
                    enabled = !state.answered,
                    onClick = { vm.onOptionTap(opt.key) }
                )
            }
            if (q.type == QuestionType.MULTIPLE && !state.answered && state.selected.isNotEmpty()) {
                Button(onClick = vm::confirmMultiple, modifier = Modifier.fillMaxWidth()) {
                    Text("确认提交（已选 ${state.selected.size} 项）")
                }
            }
        }

        QuestionType.FILL, QuestionType.SHORT_ANSWER -> {
            if (!state.revealed) {
                OutlinedTextField(
                    value = state.textInput,
                    onValueChange = vm::updateTextInput,
                    label = { Text(if (q.type == QuestionType.FILL) "输入你的答案" else "写下你的回答（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (q.type == QuestionType.SHORT_ANSWER) 3 else 1
                )
                Button(onClick = vm::revealReference, modifier = Modifier.fillMaxWidth()) {
                    Text("对照参考答案")
                }
            }
        }
    }

    // 答案与解析
    if (state.revealed) {
        if (q.answer.isNullOrBlank()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "本题暂无参考答案。可回到题库详情，用 AI 生成答案与解析。",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val contentPreview = answerContentPreview(q)
                    Text(
                        "参考答案：${q.answer}" + (contentPreview?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (q.aiGenerated) {
                        Text(
                            buildString {
                                append("AI 生成")
                                q.confidence?.let { append(" · 置信度 $it%") }
                                q.agreement?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic
                        )
                    }
                    q.analysis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                    // 三省六部分歧
                    val dissents = JsonCodec.decodeDissent(q.dissentJson)
                    if (dissents.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起各部分歧" else "查看各部分歧（${dissents.size} 部）")
                        }
                        if (expanded) {
                            dissents.forEach { d ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(
                                            "${d.provider}：${d.answer}" +
                                                (d.selfConfidence?.let { "（自评 $it%）" } ?: ""),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        d.analysis?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 主观题自评
        if ((q.type == QuestionType.FILL || q.type == QuestionType.SHORT_ANSWER) && !state.answered) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.selfRate(true) }, modifier = Modifier.weight(1f)) { Text("我答对了") }
                OutlinedButton(onClick = { vm.selfRate(false) }, modifier = Modifier.weight(1f)) { Text("我答错了") }
            }
        }
    }
}

@Composable
private fun OptionRow(
    key: String,
    text: String,
    selected: Boolean,
    revealed: Boolean,
    isAnswer: Boolean,
    isWrongPick: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val container = when {
        isAnswer -> MaterialTheme.colorScheme.primaryContainer
        isWrongPick -> MaterialTheme.colorScheme.errorContainer
        selected && !revealed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isAnswer) MaterialTheme.colorScheme.primary
                else if (isWrongPick) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outlineVariant
            ) {
                Text(
                    key,
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
            Spacer(Modifier.padding(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (isAnswer) Icon(Icons.Filled.Check, "正确", tint = MaterialTheme.colorScheme.primary)
            if (isWrongPick) Icon(Icons.Filled.Close, "错误", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BottomControls(state: StudyUiState, vm: StudyViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 判分提示
        if (state.answered && state.correct != null) {
            Text(
                if (state.correct == true) "✓ 回答正确" else "✗ 回答错误",
                color = if (state.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium
            )
        }
        // 速刷倒计时
        state.countdown?.let { sec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${sec} 秒后自动下一题", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::pauseCountdown) { Text("暂停") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.mode != StudyMode.RANDOM) {
                OutlinedButton(onClick = vm::prev, enabled = (state.session?.indexInQueue ?: 1) > 1, modifier = Modifier.weight(1f)) {
                    Text("上一题")
                }
            }
            val canNext = state.answered || state.revealed || state.mode == StudyMode.RECITE
            Button(onClick = vm::next, enabled = canNext, modifier = Modifier.weight(1f)) {
                Text("下一题")
            }
        }
    }
}

@Composable
private fun FinishedView(mode: StudyMode, total: Int, onBack: () -> Unit, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                when (mode) {
                    StudyMode.RANDOM -> "本轮随机巩固完成！"
                    else -> "本模式题目已刷完！"
                },
                style = MaterialTheme.typography.titleLarge
            )
            Text("共 $total 题", color = MaterialTheme.colorScheme.outline)
            Button(onClick = onRestart) {
                Text(if (mode == StudyMode.RANDOM) "重新随机巩固" else "从头再来")
            }
            OutlinedButton(onClick = onBack) { Text("返回题库") }
        }
    }
}

private fun judgeLabel(text: String): String = when (text.trim()) {
    "对", "正确", "√", "T", "是" -> "对"
    "错", "错误", "×", "F", "否" -> "错"
    else -> text
}

/** 选择题答案附内容预览，字母错位时用户可直接核对内容 */
private fun answerContentPreview(q: Question): String? {
    if (q.type != QuestionType.SINGLE && q.type != QuestionType.MULTIPLE) return null
    val ans = q.answer ?: return null
    val letters = ans.filter { it in 'A'..'E' }
    if (letters.isEmpty()) return null
    val options = JsonCodec.decodeOptions(q.optionsJson)
    return options.filter { it.key.isNotEmpty() && letters.contains(it.key[0]) }
        .joinToString("、") { it.text.take(18) }
        .ifBlank { null }
}
