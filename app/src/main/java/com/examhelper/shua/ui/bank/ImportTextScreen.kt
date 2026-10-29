package com.examhelper.shua.ui.bank

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

@Composable
fun ImportTextScreen(onDone: () -> Unit) {
    val vm: ImportTextViewModel = viewModel()
    val state by vm.state.collectAsState()
    val extra = shuaColors

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(
            title = if (state.parsed == null) "粘贴文本" else "确认导入",
            onBack = onDone
        )

        if (state.parsed == null) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
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
                OutlinedTextField(
                    value = state.text,
                    onValueChange = vm::updateText,
                    label = { Text("粘贴题目文本") },
                    placeholder = {
                        Text(
                            "1.题干…\nA.选项 B.选项\n答案：B\n解析：…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                state.error?.let {
                    Text(it, color = extra.sage, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = vm::parse,
                    enabled = state.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("解析题目", style = MaterialTheme.typography.labelLarge) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.parsed!!.size} 道题待确认",
                        style = serifTitle(16.sp, FontWeight.Medium),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.updateText(state.text) }) {
                        Text("重新编辑", color = extra.terracotta)
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    items(state.parsed!!.indices.toList()) { i ->
                        ParsedQuestionCard(i + 1, state.parsed!![i])
                    }
                }
                if (state.importing) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("导入中…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Button(
                        onClick = { vm.confirmImport(onDone) },
                        enabled = state.parsed!!.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("确认导入 ${state.parsed!!.size} 题", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun ParsedQuestionCard(index: Int, q: ParsedQuestion) {
    val extra = shuaColors
    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.terracotta
                    )
                }
                SemanticChip(typeLabel(q.type), ChipTone.Brand)
                if (q.answer != null) SemanticChip("有答案", ChipTone.Neutral)
                else SemanticChip("待 AI 补全", ChipTone.Amber)
            }
            Text(q.stem, style = MaterialTheme.typography.bodyMedium)
            if (q.options.isNotEmpty()) {
                Text(
                    q.options.joinToString("   ") { "${it.key}. ${it.text.take(14)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            q.answer?.let {
                Text("答案 $it", style = MaterialTheme.typography.bodySmall, color = extra.terracotta)
            }
        }
    }
}

fun typeLabel(type: QuestionType): String = when (type) {
    QuestionType.SINGLE -> "单选"
    QuestionType.MULTIPLE -> "多选"
    QuestionType.JUDGE -> "判断"
    QuestionType.FILL -> "填空"
    QuestionType.SHORT_ANSWER -> "简答"
    QuestionType.TERM -> "名词解释"
}
