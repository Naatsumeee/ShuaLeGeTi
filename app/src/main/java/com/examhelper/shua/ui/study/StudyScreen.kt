@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.examhelper.shua.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.Markdown
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.isCompactWidth
import com.examhelper.shua.ui.theme.monoStyle
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

/**
 * 做题界面（Mobile-First 紧凑布局）：
 * 顶部玻璃标题栏 → 细进度条 → 题干卡（限高可滚）→ 选项列表（占满剩余空间可滚）
 * → 底部固定玻璃控制条。目标：题干、选项与答题控制在常见机型上一屏内完成。
 */
@Composable
fun StudyScreen(
    bankId: Long,
    modeName: String,
    onBack: () -> Unit
) {
    val vm: StudyViewModel = viewModel()
    val state by vm.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    val extra = shuaColors
    val compact = isCompactWidth()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeTopPadding()) {
            CompactTopBar(
                title = when (state.mode) {
                    StudyMode.SEQUENTIAL -> "标准做题"
                    StudyMode.SPEED -> "速刷模式"
                    StudyMode.RECITE -> "背题模式"
                    StudyMode.RANDOM -> "随机巩固"
                    StudyMode.EXAM -> "测试模式"
                },
                onBack = onBack
            ) {
                IconAction(
                    icon = Icons.Filled.ContentCut,
                    desc = "斩题",
                    tint = if (state.killed) MaterialTheme.colorScheme.error else extra.inkFaint
                ) { vm.toggleKill() }
                IconAction(
                    icon = if (state.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    desc = "收藏",
                    tint = if (state.favorite) extra.amber else extra.inkFaint
                ) { vm.toggleFavorite() }
                if (state.mode != StudyMode.RANDOM && (state.session?.total ?: 0) > 1) {
                    IconAction(Icons.Filled.Apps, "答题卡", extra.inkFaint) { showSheet = true }
                }
                if (state.mode == StudyMode.RANDOM) {
                    IconAction(Icons.Filled.Refresh, "重置", extra.inkFaint) { vm.resetRandom() }
                }
                // 常驻「问 AI」入口：任何时候都能就当前题目提问
                AskAiButton(compact = compact, active = state.askOpen) { vm.toggleAsk() }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                state.session?.finished == true -> FinishedView(
                    mode = state.mode,
                    total = state.session?.total ?: 0,
                    wrongCount = state.roundWrongCount,
                    wrongOnly = state.roundWrongOnly,
                    onBack = onBack,
                    onRestart = if (state.mode == StudyMode.RANDOM) vm::resetRandom else vm::restart,
                    onBrushWrong = vm::brushRoundWrong,
                    onExitWrong = { vm.dismissRoundWrong(); onBack() }
                )

                else -> {
                    val session = state.session
                    val q = session?.question

                    // ---- 细进度条 + 题号 ----
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (state.mode == StudyMode.RANDOM)
                                "已巩固 ${session?.doneCount ?: 0} / ${session?.total ?: 0}"
                            else
                                "${session?.indexInQueue ?: 0} / ${session?.total ?: 0}",
                            style = monoStyle(12.sp, FontWeight.Medium),
                            color = extra.terracotta
                        )
                        Spacer(Modifier.width(10.dp))
                        ProgressThin(
                            progress = {
                                val total = session?.total ?: 0
                                if (total == 0) 0f else (session?.doneCount ?: 0).toFloat() / total
                            },
                            modifier = Modifier.weight(1f)
                        )
                        state.filterSummary?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // ---- 题干 + 选项：左右连续卡片（跟手 + 遮罩）----
                    // 三张卡片常驻成一条「卡片带」：上一题 / 当前 / 下一题。
                    // 拖动时整条带子跟手平移，相邻题从两侧露出，当前卡片带阴影压在
                    // 上面形成遮罩边缘；松手超过阈值才真正切题，否则弹回原位。
                    // 关键点：邻卡内容直接取自 session.prevQuestion / nextQuestion，
                    // 所以动画结束的瞬间内容已经是目标题目，不会出现"先闪一下旧题"。
                    val pageWidth = remember { mutableIntStateOf(0) }
                    val w = pageWidth.intValue.toFloat().coerceAtLeast(1f)
                    var drag by remember { mutableFloatStateOf(0f) }
                    // 提交切题后，等 VM 把新题装载好（pageKey 变化）再把带子从对侧弹回 0：
                    // 直接"先切状态再补动画"会出现一帧旧题闪回，因为 reload() 是异步的。
                    var pending by remember { mutableIntStateOf(0) } // 1=向后翻 -1=向前翻
                    val pageKey = if (state.mode == StudyMode.RANDOM) {
                        session?.doneCount ?: 0
                    } else {
                        session?.indexInQueue ?: 0
                    }
                    LaunchedEffect(pageKey) {
                        if (pending != 0) {
                            val from = if (pending > 0) w else -w
                            drag = from
                            Animatable(drag).animateTo(
                                0f, tween(180, easing = FastOutLinearInEasing)
                            ) { drag = value }
                            pending = 0
                        }
                    }
                    val canPrev = session?.prevQuestion != null && state.mode != StudyMode.RANDOM
                    val canNext = session?.nextQuestion != null

                    Box(
                        Modifier
                            .weight(1f)
                            .onSizeChanged { pageWidth.intValue = it.width }
                            .clipToBounds()
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    // 没有邻题的那一侧只给一点点阻尼位移，暗示"到头了"
                                    drag = (drag + delta).coerceIn(
                                        if (canPrev) -w else -w * 0.16f,
                                        if (canNext) w else w * 0.16f
                                    )
                                },
                                onDragStopped = { velocity ->
                                    val goNext = canNext && (
                                        drag < -w * 0.22f ||
                                            (velocity < -700f && drag < -w * 0.05f)
                                        )
                                    val goPrev = canPrev && (
                                        drag > w * 0.22f ||
                                            (velocity > 700f && drag > w * 0.05f)
                                        )
                                    when {
                                        goNext -> {
                                            Animatable(drag).animateTo(
                                                -w, tween(140, easing = FastOutLinearInEasing)
                                            ) { drag = value }
                                            pending = 1
                                            vm.next()
                                        }
                                        goPrev -> {
                                            Animatable(drag).animateTo(
                                                w, tween(140, easing = FastOutLinearInEasing)
                                            ) { drag = value }
                                            pending = -1
                                            vm.prev()
                                        }
                                        else -> Animatable(drag).animateTo(
                                            0f, spring(stiffness = Spring.StiffnessMediumLow)
                                        ) { drag = value }
                                    }
                                }
                            )
                    ) {
                        if (canPrev) {
                            PeekSlot(x = drag - w) { session?.prevQuestion?.let { PeekPage(it) } }
                        }
                        if (canNext) {
                            PeekSlot(x = drag + w) { session?.nextQuestion?.let { PeekPage(it) } }
                        }
                        // 当前题：不透明底色 + 阴影，拖动时压在邻卡之上形成遮罩
                        Column(
                            Modifier
                                .fillMaxSize()
                                .offset { IntOffset(drag.toInt(), 0) }
                                .shadow(
                                    if (kotlin.math.abs(drag) > 1f) 20.dp else 0.dp,
                                    RoundedCornerShape(16.dp)
                                )
                                .background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp)
                                .padding(top = 2.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (q == null) {
                                Text("题目加载中…")
                            } else {
                                QuestionBody(q = q, state = state, vm = vm, compact = compact)
                            }
                        }
                    }

                    // ---- 底部答题控制（固定）----
                    BottomControls(state = state, vm = vm, onBack = onBack, compact = compact)
                }
            }
        }
    }

    // ---- 问 AI 追问面板 ----
    if (state.askOpen) {
        ModalBottomSheet(
            onDismissRequest = { vm.toggleAsk() },
            containerColor = MaterialTheme.colorScheme.surface,
            // 键盘弹起时面板自动上移，输入框不会被遮挡
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AskAiPanel(
                state = state,
                onAsk = vm::ask,
                onClear = vm::clearAsk,
                onDismiss = { vm.toggleAsk() }
            )
        }
    }

    // ---- 答题卡 ----
    if (showSheet && state.mode != StudyMode.RANDOM) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val session = state.session
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("答题卡", style = serifTitle(18.sp, FontWeight.Medium))
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        "${session?.doneCount ?: 0}/${session?.total ?: 0}",
                        color = shuaColors.terracotta
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    val total = session?.total ?: 0
                    items((0 until total).toList()) { i ->
                        val isCurrent = i + 1 == session?.indexInQueue
                        val isDone = i < (session?.doneCount ?: 0)
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    when {
                                        isCurrent -> MaterialTheme.colorScheme.primary
                                        isDone -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .clickable {
                                    vm.jumpTo(i + 1)
                                    showSheet = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${i + 1}",
                                style = monoStyle(12.sp, FontWeight.Medium),
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 邻卡槽位：整屏宽，按 [x] 横向偏移。内容做了轻量降级（只画题干与选项字母），
 * 一是省掉邻卡的重排/公式解析开销，二是让"还没翻到的那张"在视觉上退后一层。
 */
@Composable
private fun PeekSlot(x: Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(x.toInt(), 0) }
            .padding(horizontal = 14.dp)
            .padding(top = 2.dp, bottom = 6.dp)
    ) { content() }
}

/** 邻卡预览：题干（最多 6 行）+ 选项字母，够用户判断"下一题是什么"即可 */
@Composable
private fun PeekPage(q: Question) {
    val extra = shuaColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 1.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                SemanticChip(typeLabel(q.type), ChipTone.Brand)
                Spacer(Modifier.height(7.dp))
                Latex.LatexText(
                    text = q.stem,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6
                )
            }
        }
        val options = remember(q.id) { JsonCodec.decodeOptions(q.optionsJson) }
        options.forEach { opt ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, extra.creamLine, RoundedCornerShape(13.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    opt.key,
                    style = monoStyle(12.sp, FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    opt.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProgressThin(progress: () -> Float, modifier: Modifier = Modifier) {    val extra = shuaColors
    Box(
        modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress().coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(extra.terracotta)
        )
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp), tint = tint)
    }
}

@Composable
private fun QuestionBody(
    q: Question,
    state: StudyUiState,
    vm: StudyViewModel,
    compact: Boolean
) {
    val options = JsonCodec.decodeOptions(q.optionsJson)
    val extra = shuaColors

    // A3 共用题干（限高，避免长病例把选项顶出屏）
    q.sharedStem?.let {
        PaperCard(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            elevation = 0.dp
        ) {
            Column(
                Modifier
                    .heightIn(max = if (compact) 96.dp else 128.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    "共用题干 · A3",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.amber
                )
                Latex.LatexText(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    // 题干
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 1.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SemanticChip(
                    typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: ""),
                    ChipTone.Brand
                )
                q.chapter?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Latex.LatexText(
                text = q.stem,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // B 型共用选项
    q.sharedOptionsJson?.let { com.examhelper.shua.ui.exam.SharedOptionsCard(it) }

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
                Button(
                    onClick = vm::confirmMultiple,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认提交（已选 ${state.selected.size} 项）")
                }
            }
        }

        QuestionType.FILL, QuestionType.SHORT_ANSWER, QuestionType.TERM -> {
            if (!state.revealed) {
                OutlinedTextField(
                    value = state.textInput,
                    onValueChange = vm::updateTextInput,
                    label = { Text(if (q.type == QuestionType.FILL) "输入你的答案" else "写下你的回答（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = if (q.type == QuestionType.FILL) 1 else 2
                )
                Button(
                    onClick = vm::revealReference,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("对照参考答案") }
            }
        }
    }

    if (state.revealed) {
        AnswerCard(q = q, state = state, vm = vm)
    }
}

@Composable
private fun AnswerCard(q: Question, state: StudyUiState, vm: StudyViewModel) {
    val extra = shuaColors
    val hasAnswer = !q.answer.isNullOrBlank()
    if (!hasAnswer) {
        // 没有参考答案恰恰是最该让 AI 出手的时候，所以这里不能 return：
        // 仍要把深度解题 / 追问两个入口放出来
        PaperCard(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            container = MaterialTheme.colorScheme.surfaceVariant,
            elevation = 0.dp
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "本题暂无参考答案。可以让 AI 现做一遍，或直接追问这道题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (q.analysis?.isNotBlank() == true) {
                    // 深度解题后的解析是 Markdown，这里统一走 Markdown 渲染
                    Markdown.MarkdownText(
                        text = q.analysis!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AiDisclaimer()
                }
                DeepSolveRow(state = state, q = q, vm = vm)
            }
        }
        return
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = 3.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val contentPreview = answerContentPreview(q)
            // 参考答案优先：题库/OCR 原答案是权威，AI 答案只在附注里出现
            // 注意：q.answer 此时已是「题库答案」，AI 的异议答案在 q.aiAnswer；
            // q.originalAnswer 只是题库答案的备份副本（历史上被误当成 AI 答案显示，导致
            // 出现「AI 答案 B 与题库答案 B 不一致」这种自相矛盾的文案）。
            q.originalAnswer?.let { bankAns ->
                val aiAns = q.aiAnswer?.takeIf { it.isNotBlank() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(extra.sageSoft)
                        .padding(9.dp)
                ) {
                    Column {
                        Text(
                            if (aiAns != null) "AI 答案 $aiAns 与题库答案 $bankAns 不一致"
                            else "题库答案 $bankAns 与本次核对结果不一致",
                            style = MaterialTheme.typography.labelLarge,
                            color = extra.sage
                        )
                        Text(
                            "已以题库参考答案为准，AI 解析仅供参考",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.sage
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "参考答案",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                // 答案本身也可能是公式（如选项内容是 $x^2+1$），走 LaTeX 行内渲染
                Latex.LatexText(
                    text = q.answer.orEmpty(),
                    style = serifTitle(19.sp, FontWeight.SemiBold)
                        .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = extra.terracotta,
                    modifier = Modifier.weight(1f, fill = false),
                    allowDisplay = false
                )
                contentPreview?.let {
                    Spacer(Modifier.width(8.dp))
                    // 选项内容同样可能带公式，走行内 LaTeX，单行省略
                    Latex.LatexText(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        allowDisplay = false
                    )
                }
            }
            if (q.aiGenerated) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (q.answerVerified) SemanticChip("与参考答案一致", ChipTone.Verdant) else SemanticChip("AI 生成", ChipTone.Amber)
                    q.confidence?.let { SemanticChip("置信 $it%", ChipTone.Neutral) }
                    q.agreement?.let { SemanticChip(it, ChipTone.Neutral) }
                }
            }
            // AI 给出的、与题库不一致的答案，单独一行弱化展示
            q.aiAnswer?.takeIf { it.isNotBlank() && !q.answerVerified && it != q.answer }?.let { ai ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI 答案",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ai,
                        style = monoStyle(13.sp, FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            q.analysis?.let {
                Markdown.MarkdownText(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AiDisclaimer()
            }

            // 深度解题 + 追问：两个 AI 入口（无参考答案的分支里也复用同一个组件）
            DeepSolveRow(state = state, q = q, vm = vm)

            val dissents = JsonCodec.decodeDissent(q.dissentJson)
            if (dissents.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        if (expanded) "收起各部分歧" else "查看各部分歧（${dissents.size} 部）",
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.terracotta
                    )
                }
                if (expanded) {
                    dissents.forEach { d ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(9.dp)
                        ) {
                            Column {
                                Text(
                                    "${d.provider}：${d.answer}" +
                                        (d.selfConfidence?.let { "（自评 $it%）" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = extra.sage
                                )
                                d.analysis?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            // 主观题自评
            if ((q.type == QuestionType.FILL || q.type == QuestionType.SHORT_ANSWER || q.type == QuestionType.TERM) && !state.answered) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.selfRate(true) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("我答对了") }
                    TextButton(
                        onClick = { vm.selfRate(false) },
                        modifier = Modifier.weight(1f)
                    ) { Text("我答错了", color = extra.sage) }
                }
            }
        }
    }
}

@Composable
private fun DeepSolveRow(state: StudyUiState, q: Question, vm: StudyViewModel) {
    val extra = shuaColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 流式输出区：模型逐字吐出的内容实时显示在这里。
        // 之前是「转圈等整包」，思考 1-3 分钟期间界面毫无变化，用户以为卡死；
        // 现在思考过程与正文都可见，等待感立刻降下来。
        if (state.deepSolving && (state.deepReasoning.isNotBlank() || state.deepStream.isNotBlank())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.deepReasoning.isNotBlank()) {
                    var showReasoning by remember(state.deepStream.isBlank()) {
                        mutableStateOf(state.deepStream.isBlank())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showReasoning) "思考过程" else "思考过程（已折叠）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "点击${if (showReasoning) "收起" else "展开"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.terracotta,
                            modifier = Modifier.clickable { showReasoning = !showReasoning }
                        )
                    }
                    if (showReasoning) {
                        Markdown.MarkdownText(
                            text = state.deepReasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.deepStream.isNotBlank()) {
                    Markdown.MarkdownText(
                        text = state.deepStream,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (state.deepStream.isBlank()) {
                    TypingDots()
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.deepSolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = extra.terracotta
                )
                Spacer(Modifier.width(6.dp))
                Text("深度解题中…", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
            } else {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(extra.terracottaSoft)
                        .clickable { vm.deepSolve() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = extra.terracotta,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.deepSolved || q.deepSolved) "重新深度解题" else "深度解题",
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.terracotta
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 追问入口：深度解题后主用，没解题时也能直接问
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { vm.toggleAsk() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.chat.isEmpty()) "追问 AI" else "继续问（${state.chat.count { it.isUser }}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        state.deepError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = extra.sage,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 深度解题刚完成时提示下一步能干什么，而不是自动弹面板打断阅读
        if (!state.deepSolving && (state.deepSolved || q.deepSolved) && state.chat.isEmpty()) {
            Text(
                "深度解题完成 · 点「追问 AI」可以接着问考点、易错点或相似题",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 正文还没开始到达时给三个呼吸的圆点，暗示「在输出」而不是「卡住了」 */
@Composable
private fun TypingDots() {
    val extra = shuaColors
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "typingPhase"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val active = phase.toInt() % 3 == i
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(extra.terracotta.copy(alpha = if (active) 0.85f else 0.35f))
            )
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
    val extra = shuaColors
    val container = when {
        isAnswer -> extra.verdantSoft
        isWrongPick -> extra.sageSoft
        selected && !revealed -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        isAnswer -> extra.verdant.copy(alpha = 0.55f)
        isWrongPick -> extra.sage.copy(alpha = 0.5f)
        selected && !revealed -> extra.terracotta.copy(alpha = 0.45f)
        else -> extra.creamLine
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(container)
            .border(1.dp, borderColor, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isAnswer -> extra.verdant
                        isWrongPick -> extra.sage
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                key,
                style = monoStyle(12.sp, FontWeight.Medium),
                color = if (isAnswer || isWrongPick || selected) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Latex.LatexText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            // 选项是单行高度受限的条目：不允许 $$ 撑成行间公式，否则整行错位
            allowDisplay = false
        )
        if (isAnswer) Icon(Icons.Filled.Check, "正确", modifier = Modifier.size(16.dp), tint = extra.verdant)
        if (isWrongPick) Icon(Icons.Filled.Close, "错误", modifier = Modifier.size(16.dp), tint = extra.sage)
    }
}

@Composable
private fun BottomControls(
    state: StudyUiState,
    vm: StudyViewModel,
    onBack: () -> Unit,
    compact: Boolean
) {
    val extra = shuaColors
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .safeBottomPadding(minBottom = 10.dp)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = 12.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // 判分结果 + 速刷倒计时合并成一行，省高度
            if (state.answered && state.correct != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.correct == true) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (state.correct == true) extra.verdant else extra.sage
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.correct == true) "回答正确" else "回答错误",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.correct == true) extra.verdant else extra.sage
                    )
                    Spacer(Modifier.weight(1f))
                    state.countdown?.let { sec ->
                        Text(
                            "${sec}s 后下一题",
                            style = monoStyle(11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = vm::pauseCountdown,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("暂停", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
                        }
                    }
                }
            } else {
                state.countdown?.let { sec ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sec}s 后自动下一题",
                            style = monoStyle(11.sp),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = vm::pauseCountdown) {
                            Text("暂停", style = MaterialTheme.typography.labelSmall, color = extra.terracotta)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.mode != StudyMode.RANDOM) {
                    TextButton(
                        onClick = vm::prev,
                        enabled = (state.session?.indexInQueue ?: 1) > 1,
                        modifier = Modifier
                            .weight(if (compact) 0.8f else 1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text("上一题", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val canNext = state.answered || state.revealed || state.mode == StudyMode.RECITE
                Button(
                    onClick = vm::next,
                    enabled = canNext,
                    modifier = Modifier
                        .weight(if (state.mode == StudyMode.RANDOM) 1f else 1.4f)
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        if (state.answered || state.revealed) "下一题" else "先作答",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** 解析下方的小字免责声明（用户要求：内容为 AI 生成，可能会出错） */
@Composable
private fun AiDisclaimer() {
    Text(
        "内容为 AI 生成，可能会出错",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    )
}

@Composable
private fun FinishedView(
    mode: StudyMode,
    total: Int,
    wrongCount: Int,
    wrongOnly: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onBrushWrong: () -> Unit,
    onExitWrong: () -> Unit
) {
    val extra = shuaColors
    Box(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), elevation = 14.dp) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(extra.terracottaSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = extra.terracotta,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text(
                    if (wrongOnly) "错题也刷完啦" else "这轮题目已经完成啦",
                    style = serifTitle(21.sp, FontWeight.Medium),
                    textAlign = TextAlign.Center
                )
                Text(
                    if (wrongOnly) "本轮错题 $total 题" + if (wrongCount > 0) " · 又错了 $wrongCount 题" else " · 全部拿下"
                    else "共 $total 题" + if (wrongCount > 0) " · 答错 $wrongCount 题" else " · 全对",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                // 主按钮：返回（用户要求着重的是"返回"，不是"从头再来"）
                Button(
                    onClick = if (wrongOnly) onExitWrong else onBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (wrongOnly) "完成" else "返回", style = MaterialTheme.typography.labelLarge)
                }
                if (!wrongOnly && wrongCount > 0) {
                    OutlinedButton(
                        onClick = onBrushWrong,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracottaDeep),
                        border = BorderStroke(1.dp, extra.terracotta.copy(alpha = 0.55f))
                    ) {
                        Text(
                            "刷本轮错题（$wrongCount）",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                if (!wrongOnly) {
                    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (mode == StudyMode.RANDOM) "重新随机巩固" else "从头再来",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
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
        // 不按字符硬切：LaTeX 命令被截断后会渲染成源码。完整传入，由 maxLines + Ellipsis 在排版层省略
        .joinToString("、") { it.text }
        .ifBlank { null }
}
