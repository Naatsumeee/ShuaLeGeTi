package com.examhelper.shua.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.monoStyle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors

/**
 * 做题页顶栏常驻的「问 AI」按钮。
 *
 * 有字面标签而不是纯图标：这是本页唯一会花钱调模型的入口，用户必须一眼看见。
 * 用 compact 时只留图标 + 短标签，避免小屏顶栏被挤爆。
 */
@Composable
fun AskAiButton(compact: Boolean, active: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    val bg = if (active) extra.terracotta else extra.terracottaSoft
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else extra.terracotta
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = "问 AI",
            tint = fg,
            modifier = Modifier.size(15.dp)
        )
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            Text("问 AI", style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/**
 * 追问面板（底部弹层）。
 *
 * 深度解题之后这里就是"继续问"的入口，也可以不深度解题直接问。对话只属于当前题目，
 * 切题即清空（由 ViewModel 的 reload 负责），避免把上一题的上下文带进来聊错。
 */
@Composable
fun AskAiPanel(
    state: StudyUiState,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val extra = shuaColors
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息进来滚到底部，跟聊天软件一致
    LaunchedEffect(state.chat.size, state.asking) {
        val last = state.chat.size + if (state.asking) 1 else 0
        if (last > 0) listState.animateScrollToItem((last - 1).coerceAtLeast(0))
    }

    val send = {
        val text = input.trim()
        if (text.isNotEmpty() && !state.asking) {
            onAsk(text)
            input = ""
        }
    }

    // 键盘 / 手势条只取「两者中更大的那个」，绝不叠加：
    // 早先这里同时用了 imePadding() + safeBottomPadding()，而 safeDrawing 本身
    // 已包含 IME 高度，两份键盘高度相加 → 一大块空白把输入框顶出屏幕、
    // 发送按钮被挤到屏幕外（这正是「输入框被空白遮盖且丢失发送按钮」的根因）。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(imeBottom, navBottom, 10.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInset)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("问 AI", style = serifTitle(18.sp, FontWeight.Medium))
            Spacer(Modifier.width(8.dp))
            // 模型名可能很长（名称+模型），限宽省略，别把标题挤掉
            Text(
                text = state.askModelName ?: "主部模型",
                style = monoStyle(12.sp, FontWeight.Medium),
                color = extra.terracotta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            if (state.chat.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClear)
                        .padding(6.dp)
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "清空对话",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.padding(top = 2.dp))
        Text(
            if (state.deepSolved) "已深度解题，可以继续追问考点、易错点或相似题"
            else "就当前这道题提问，例如「为什么不选 C」「这题考的什么」",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.padding(top = 8.dp))

        // ---- 对话区 ----
        if (state.chat.isEmpty() && !state.asking) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有提问",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                // weight 而非固定高度：键盘弹起、可视高度变小时对话区自己收缩，
                // 保证下方输入框与发送按钮永远留在屏幕内
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(state.chat) { _, turn ->
                    ChatBubble(turn)
                }
                if (state.asking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = extra.terracotta
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "模型思考中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.terracotta
                            )
                        }
                    }
                }
            }
        }

        state.askError?.let {
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = extra.sage,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.padding(top = 10.dp))
        // ---- 输入区 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(500) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("追问这道题…", style = MaterialTheme.typography.bodySmall)
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Spacer(Modifier.width(8.dp))
            val enabled = input.isNotBlank() && !state.asking
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (enabled) extra.terracotta else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { send() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        Text(
            "关闭",
            style = monoStyle(12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val extra = shuaColors
    val isUser = turn.isUser
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (isUser) 0.86f else 1f)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp
                    )
                )
                .background(if (isUser) extra.terracottaSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (isUser) {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
            } else {
                // 模型回答里的公式也走 LaTeX 渲染，与解析区表现一致
                Latex.LatexText(
                    text = turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
