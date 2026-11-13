package com.examhelper.shua.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors

/** 题库维护二级选择页：AI 生成与编辑章节都从这里选择目标子库。 */
@Composable
fun MaintenancePickerScreen(
    kind: String,
    bankIds: List<Long>,
    onBack: () -> Unit,
    onStartOne: (Long) -> Unit,
    onStartSequence: (List<Long>) -> Unit
) {
    val repo = ShuaApp.instance.container.bankRepo
    var banks by remember { mutableStateOf<List<QuestionBank>>(emptyList()) }
    var selected by remember(bankIds) { mutableStateOf(bankIds.toSet()) }
    val isAi = kind == "generate"
    val extra = shuaColors

    LaunchedEffect(bankIds) {
        banks = bankIds.mapNotNull { repo.getBank(it) }
    }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 10.dp)) {
        CompactTopBar(title = if (isAi) "AI 生成 · 选择题库" else "编辑题目 · 选择题库", onBack = onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), elevation = 8.dp) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isAi) Icons.Filled.AutoAwesome else Icons.Filled.Edit,
                            contentDescription = null,
                            tint = extra.terracotta
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (isAi) "选择要补全答案/解析的子题库" else "选择要编辑题目和章节的子题库",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            items(banks, key = { it.id }) { bank ->
                val checked = bank.id in selected
                PaperCard(
                    Modifier.fillMaxWidth().clickable {
                        selected = if (checked) selected - bank.id else selected + bank.id
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (checked) selected - bank.id else selected + bank.id
                            },
                            colors = CheckboxDefaults.colors(checkedColor = extra.terracotta)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(bank.name, style = serifTitle(15.sp, FontWeight.Medium))
                            Text("${bank.questionCount} 题", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (bank.isGroup) SemanticChip("题库组", ChipTone.Verdant)
                    }
                }
            }
        }

        GlassSurface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = 12.dp
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val ids = selected.toList()
                        if (ids.size == 1) onStartOne(ids.first()) else if (ids.isNotEmpty()) onStartSequence(ids)
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
                ) {
                    Text("开始维护所选 · ${selected.size} 本", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { bankIds.takeIf { it.isNotEmpty() }?.let(onStartSequence) },
                    enabled = bankIds.size > 1,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("一键维护全部 · ${bankIds.size} 本")
                }
                Text(
                    "一键维护会按顺序逐本处理，但总耗时较长，单本失败或中断的概率也会增高；建议先分别维护重要题库。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
