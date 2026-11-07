package com.examhelper.shua.ui.question

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JudgeOptions
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.Markdown
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import com.examhelper.shua.ui.sound.Haptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 单题查看页：从**测试报告**或**错题本**点进来，看完整题目、你的答案、参考答案与解析。
 *
 * 与做题页的区别：
 * - 不做判分、不写作答记录（这些题早就答过了，再写一遍会污染统计）；
 * - **默认展开解析**（用户就是从报告/错题本来复盘的，不该再点一次）；
 * - 底部两个按钮换成「返回测试报告 / 返回错题本」，由入口决定文案。
 */
class SingleQuestionViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val questionId: Long =
        savedStateHandle.get<String>("questionId")?.toLongOrNull() ?: -1L

    data class UiState(
        val question: Question? = null,
        /** 这份报告里这题选了什么（可能为空 = 没做） */
        val userAnswer: String? = null,
        /** 报告里这题判对没有（错题本入口时为 null） */
        val correct: Boolean? = null,
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun load(runId: String?) {
        if (!_state.value.loading) return
        viewModelScope.launch {
            val q = ShuaApp.instance.container.database.questionDao().getById(questionId)
            var userAnswer: String? = null
            var correct: Boolean? = null
            if (q != null && !runId.isNullOrBlank()) {
                val rep = runCatching {
                    ShuaApp.instance.container.studyRepo.examReport(runId)
                }.getOrNull()
                rep?.items?.firstOrNull { it.question.id == q.id }?.let {
                    userAnswer = it.userAnswer
                    correct = it.correct
                }
            }
            _state.value = UiState(question = q, userAnswer = userAnswer, correct = correct, loading = false)
        }
    }
}

@Composable
fun SingleQuestionScreen(
    questionId: Long,
    runId: String?,
    /** 底部主按钮文案：报告入口传「返回测试报告」，错题本入口传「返回错题本」 */
    backLabel: String,
    onBack: () -> Unit
) {
    val vm: SingleQuestionViewModel = viewModel()
    val state by vm.state.collectAsState()
    val extra = shuaColors

    LaunchedEffect(questionId, runId) { vm.load(runId) }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(title = "题目详情", onBack = onBack)

        val q = state.question
        if (q == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "加载中…" else "题目不存在或已被删除")
            }
            return@Column
        }

        val rawOptions = JsonCodec.decodeOptions(q.optionsJson)
        val options = if (q.type == QuestionType.JUDGE) {
            JudgeOptions.normalize(q.type, rawOptions, q.answer).first
        } else rawOptions
        val shared = JsonCodec.decodeOptions(q.sharedOptionsJson)
        val answerLetter = q.answer?.trim()?.uppercase().orEmpty()
        val userLetter = state.userAnswer?.trim()?.uppercase().orEmpty()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SemanticChip(typeLabel(q.type), ChipTone.Brand)
                    q.chapter?.takeIf { it.isNotBlank() }?.let {
                        SemanticChip(it.take(14), ChipTone.Neutral)
                    }
                    state.correct?.let {
                        SemanticChip(if (it) "答对" else "答错", if (it) ChipTone.Verdant else ChipTone.Sage)
                    }
                }
            }

            // 共用题干（B1/A3 型）
            q.sharedStem?.takeIf { it.isNotBlank() }?.let { shared0 ->
                item {
                    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 4.dp) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "共用题干",
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.terracottaDeep
                            )
                            Latex.LatexText(
                                text = shared0,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 8.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Latex.LatexText(
                            text = q.stem,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 共用备选答案（B1 型）
                        if (shared.isNotEmpty()) {
                            Text(
                                "共用备选答案",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            shared.forEach { opt ->
                                OptionLine(key = opt.key, text = opt.text, state = OptionState.Plain)
                            }
                        }
                        options.forEach { opt ->
                            val st = when {
                                opt.key.uppercase() == answerLetter && answerLetter.isNotEmpty() -> OptionState.Correct
                                opt.key.uppercase() == userLetter && userLetter.isNotEmpty() -> OptionState.Wrong
                                else -> OptionState.Plain
                            }
                            OptionLine(key = opt.key, text = opt.text, state = st)
                        }
                        if (q.type != QuestionType.JUDGE && q.type != QuestionType.SINGLE &&
                            q.type != QuestionType.MULTIPLE && options.isEmpty()
                        ) {
                            // 主观题：显示用户作答（如果有）
                            Text(
                                "你的作答",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    state.userAnswer?.takeIf { it.isNotBlank() } ?: "（未作答）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // ---- 答案与解析（默认展开）----
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 6.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("参考答案", style = serifTitle(16.sp, FontWeight.Medium))
                            Spacer(Modifier.width(8.dp))
                            MonoText(
                                JudgeOptions.answerText(q.type, answerLetter).ifBlank { "无" },
                                color = extra.verdant
                            )
                        }
                        if (state.userAnswer?.isNotBlank() == true) {
                            Text(
                                "你的答案：${JudgeOptions.answerText(q.type, userLetter)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.correct == true) extra.verdant else extra.sage
                            )
                        }
                        q.originalAnswer?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "题库原答案：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(extra.creamLine)
                        )
                        Text(
                            "解析",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.terracottaDeep
                        )
                        if (q.analysis.isNullOrBlank()) {
                            Text(
                                "这道题还没有解析，可以去做题页点「深度解题」生成。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Markdown.MarkdownText(
                                text = q.analysis!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            "内容为 AI 生成，可能会出错",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { Haptics.done(); onBack() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text(backLabel, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

private enum class OptionState { Plain, Correct, Wrong }

@Composable
private fun OptionLine(key: String, text: String, state: OptionState) {
    val extra = shuaColors
    val border = when (state) {
        OptionState.Correct -> extra.verdant
        OptionState.Wrong -> extra.sage
        OptionState.Plain -> extra.creamLine
    }
    val bg = when (state) {
        OptionState.Correct -> extra.verdantSoft
        OptionState.Wrong -> extra.sageSoft
        OptionState.Plain -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        OptionState.Correct -> extra.verdant
                        OptionState.Wrong -> extra.sage
                        OptionState.Plain -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                key,
                style = MaterialTheme.typography.labelSmall,
                color = if (state == OptionState.Plain) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.width(9.dp))
        Latex.LatexText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            allowDisplay = false
        )
        if (state != OptionState.Plain) {
            Icon(
                if (state == OptionState.Correct) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (state == OptionState.Correct) extra.verdant else extra.sage
            )
        }
    }
}
