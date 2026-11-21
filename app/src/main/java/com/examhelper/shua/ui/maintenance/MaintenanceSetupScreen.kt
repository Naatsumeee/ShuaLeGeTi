package com.examhelper.shua.ui.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.GenerateJob
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.generate.GenerateScope
import com.examhelper.shua.data.generate.GenerateWorker
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 多题库维护页：先选题库/章节/生成类型，再由用户手动开始。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MaintenanceSetupScreen(
    kind: String,
    bankIds: List<Long>,
    onBack: () -> Unit,
    onStartOne: (Long) -> Unit,
    onStartSequence: (List<Long>) -> Unit
) {
    val container = ShuaApp.instance.container
    val repo = container.bankRepo
    val jobDao = container.database.generateJobDao()
    val scope = rememberCoroutineScope()
    var banks by remember { mutableStateOf<List<QuestionBank>>(emptyList()) }
    var selected by remember(bankIds) { mutableStateOf(bankIds.toSet()) }
    var chapters by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedChapters by remember { mutableStateOf<Set<String>>(emptySet()) }
    var generateScope by remember { mutableStateOf(GenerateScope.MISSING) }
    var startedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var jobs by remember { mutableStateOf<Map<Long, GenerateJob?>>(emptyMap()) }
    var canceling by remember { mutableStateOf(false) }
    var stoppedByUser by remember { mutableStateOf(false) }
    val isAi = kind == "generate"
    val extra = shuaColors
    val runningJob = jobs.values.firstOrNull { it?.stage == "RUNNING" }
    val allFinished = startedIds.isNotEmpty() && startedIds.all { id ->
        jobs[id]?.stage in setOf("DONE", "FAILED", "CANCELED")
    }
    val running = startedIds.isNotEmpty() && !allFinished && !stoppedByUser

    LaunchedEffect(bankIds) {
        banks = bankIds.mapNotNull { repo.getBank(it) }
    }

    LaunchedEffect(selected) {
        chapters = selected.flatMap { id ->
            repo.getQuestions(id).mapNotNull { it.chapter?.takeIf(String::isNotBlank) }
        }.distinct()
        selectedChapters = selectedChapters.intersect(chapters.toSet())
    }

    LaunchedEffect(startedIds) {
        val ids = startedIds
        if (ids.isEmpty()) return@LaunchedEffect
        val flows = ids.map { jobDao.observe(it) }
        if (flows.size == 1) {
            flows.first().collect { job -> jobs = mapOf(ids.first() to job) }
        } else {
            combine(flows) { values -> ids.zip(values).toMap() }.collect { jobs = it }
        }
    }

    fun toggleBank(id: Long) {
        if (running) return
        selected = if (id in selected) selected - id else selected + id
    }

    fun startMaintenance() {
        val ids = banks.map { it.id }.filter { it in selected }
        if (ids.isEmpty()) return
        if (ids.size == 1) {
            onStartOne(ids.first())
            return
        }
        if (isAi) {
            startedIds = ids
            jobs = emptyMap()
            stoppedByUser = false
            GenerateWorker.enqueueSequential(
                context = ShuaApp.instance,
                bankIds = ids,
                chapters = selectedChapters.toList(),
                scope = generateScope
            )
        } else {
            onStartSequence(ids)
        }
    }

    fun stopMaintenance() {
        if (startedIds.isEmpty() || canceling) return
        canceling = true
        stoppedByUser = true
        val ids = startedIds
        GenerateWorker.cancelSequence(ShuaApp.instance, ids)
        scope.launch {
            ids.forEach { id ->
                val job = jobDao.get(id) ?: return@forEach
                if (job.stage == "RUNNING") {
                    jobDao.upsert(
                        job.copy(
                            stage = "CANCELED",
                            message = "已停止当前生成任务",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            canceling = false
        }
    }

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 10.dp)) {
        CompactTopBar(
            title = if (isAi) "AI 生成 · 维护范围" else "编辑题目 · 选择题库",
            onBack = onBack
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionLabel("题库", trailing = "已选 ${selected.size} 本") }
            items(banks, key = { it.id }) { bank ->
                val checked = bank.id in selected
                PaperCard(
                    Modifier.fillMaxWidth().clickable(enabled = !running) { toggleBank(bank.id) },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !running,
                            onCheckedChange = { toggleBank(bank.id) },
                            colors = CheckboxDefaults.colors(checkedColor = extra.terracotta)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(bank.name, style = serifTitle(15.sp, FontWeight.Medium))
                            Text(
                                "${bank.questionCount} 题",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (bank.isGroup) SemanticChip("题库组", ChipTone.Verdant)
                    }
                }
            }

            if (isAi && chapters.isNotEmpty()) {
                item {
                    SectionLabel(
                        "生成范围",
                        trailing = if (selectedChapters.isEmpty()) "全库" else "${selectedChapters.size} 章"
                    )
                }
                item {
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        FlowRow(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            ChoiceChip("全库", selectedChapters.isEmpty(), !running) {
                                selectedChapters = emptySet()
                            }
                            chapters.forEach { chapter ->
                                ChoiceChip(chapter, chapter in selectedChapters, !running) {
                                    selectedChapters = if (chapter in selectedChapters) {
                                        selectedChapters - chapter
                                    } else {
                                        selectedChapters + chapter
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isAi) {
                item { SectionLabel("生成类型") }
                item {
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        FlowRow(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            ChoiceChip("补齐缺失", generateScope == GenerateScope.MISSING, !running) {
                                generateScope = GenerateScope.MISSING
                            }
                            ChoiceChip("全部重生成", generateScope == GenerateScope.ALL, !running) {
                                generateScope = GenerateScope.ALL
                            }
                            ChoiceChip("极简生成", generateScope == GenerateScope.MINIMAL, !running) {
                                generateScope = GenerateScope.MINIMAL
                            }
                        }
                    }
                }
            }

            if (isAi && startedIds.isNotEmpty()) {
                item { SectionLabel("维护进度", trailing = if (running) "运行中" else "已停止") }
                item {
                    val progressJob = runningJob ?: jobs.values.lastOrNull { it != null }
                    val done = progressJob?.done ?: 0
                    val total = progressJob?.total ?: 0
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                runningJob?.message
                                    ?: jobs.values.lastOrNull { it != null }?.message
                                    ?: "等待后台任务…",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (total > 0) {
                                LinearProgressIndicator(
                                    progress = { done.toFloat() / total },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = extra.terracotta
                                )
                                Text(
                                    "$done / $total 题",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (running) {
                                OutlinedButton(
                                    onClick = ::stopMaintenance,
                                    enabled = !canceling,
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(if (canceling) "正在停止…" else "停止当前生成任务") }
                            }
                        }
                    }
                }
            }
        }

        if (!running) {
            GlassSurface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = 12.dp
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::startMaintenance,
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = extra.terracotta)
                    ) {
                        Text(
                            if (isAi) "开始维护所选 · ${selected.size} 本" else "开始编辑所选 · ${selected.size} 本",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) extra.terracottaSoft
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) extra.terracottaDeep else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
