package com.examhelper.shua.ui.wrongbook

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.FavoriteRecord
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ShuaApp).container
    private val studyRepo = container.studyRepo
    private val questionDao = container.database.questionDao()
    private val bankDao = container.database.bankDao()

    data class Lists(
        val wrong: List<Pair<WrongRecord, Question?>> = emptyList(),
        val mastered: List<Pair<WrongRecord, Question?>> = emptyList(),
        val favorites: List<Pair<FavoriteRecord, Question?>> = emptyList(),
        val kills: List<Pair<com.examhelper.shua.data.db.KillRecord, Question?>> = emptyList(),
        /** bankId -> 题库名（出现的题库） */
        val bankNames: Map<Long, String> = emptyMap()
    ) {
        fun bankOf(q: Question?): Long = q?.bankId ?: -1L
    }

    private val _lists = MutableStateFlow(Lists())
    val lists: StateFlow<Lists> = _lists

    /** 题库筛选（null = 全部） */
    private val _selectedBank = MutableStateFlow<Long?>(null)
    val selectedBank: StateFlow<Long?> = _selectedBank

    fun selectBank(id: Long?) {
        _selectedBank.value = id
    }

    init {
        viewModelScope.launch {
            studyRepo.observeWrongBook().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(wrong = records.map { it to questions[it.questionId] })
                refreshBankNames()
            }
        }
        viewModelScope.launch {
            studyRepo.observeMasteredWrong().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(mastered = records.map { it to questions[it.questionId] })
                refreshBankNames()
            }
        }
        viewModelScope.launch {
            studyRepo.observeFavorites().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(favorites = records.map { it to questions[it.questionId] })
                refreshBankNames()
            }
        }
        viewModelScope.launch {
            studyRepo.observeKills().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(kills = records.map { it to questions[it.questionId] })
                refreshBankNames()
            }
        }
    }

    private suspend fun refreshBankNames() {
        val ids = buildSet {
            _lists.value.wrong.forEach { add(it.second?.bankId ?: return@forEach) }
            _lists.value.mastered.forEach { add(it.second?.bankId ?: return@forEach) }
            _lists.value.favorites.forEach { add(it.second?.bankId ?: return@forEach) }
            _lists.value.kills.forEach { add(it.second?.bankId ?: return@forEach) }
        }.filter { it > 0 }
        if (ids.isEmpty()) return
        val names = bankDao.observeAll().first().associate { it.id to it.name }
        _lists.value = _lists.value.copy(bankNames = names.filterKeys { it in ids })
    }

    fun markMastered(questionId: Long) = viewModelScope.launch { studyRepo.markMastered(questionId) }
    fun removeWrong(questionId: Long) = viewModelScope.launch { studyRepo.removeWrong(questionId) }
    fun reviveKill(questionId: Long) = viewModelScope.launch { studyRepo.reviveKill(questionId) }
}

@Composable
fun WrongBookScreen(
    onOpenBank: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 错题重刷 / 随机抽检：复用题库刷题的同款做题页（study 路由 + 筛选指纹） */
    onStartStudy: (bankId: Long, mode: StudyMode, filterJson: String) -> Unit = { _, _, _ -> }
) {
    val vm: LibraryViewModel = viewModel()
    val lists by vm.lists.collectAsState()
    val selectedBank by vm.selectedBank.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Question?>(null) }
    /** 章节筛选（null = 该题库全部章节） */
    var chapter by remember { mutableStateOf<String?>(null) }
    val extra = shuaColors

    // 切 tab / 切题库时章节选择失效，避免停在上一组不存在的章节上
    LaunchedEffect(tab, selectedBank) { chapter = null }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding() + 4.dp)
    ) {
        CompactTopBar(
            title = "错题本",
        )

        // 数字 + 小字标签的分段控件
        val tabDefs = listOf(
            "未掌握" to lists.wrong.size,
            "已掌握" to lists.mastered.size,
            "收藏" to lists.favorites.size,
            "斩题墙" to lists.kills.size
        )
        PaperCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            container = MaterialTheme.colorScheme.surface,
            borderColor = extra.creamLine
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                tabDefs.forEachIndexed { i, (label, count) ->
                    val selected = tab == i
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { tab = i }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$count",
                            style = serifTitle(20.sp, FontWeight.Medium),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 题库筛选
        if (lists.bankNames.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedBank == null,
                    onClick = { vm.selectBank(null) },
                    label = { Text("全部题库", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedBank == null,
                        borderColor = extra.creamLine,
                        selectedBorderColor = extra.terracotta.copy(alpha = 0.4f)
                    )
                )
                lists.bankNames.forEach { (id, name) ->
                    FilterChip(
                        selected = selectedBank == id,
                        onClick = { vm.selectBank(id) },
                        label = { Text(name.take(10), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedBank == id,
                            borderColor = extra.creamLine,
                            selectedBorderColor = extra.terracotta.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }

        val bankFilter: (Question?) -> Boolean = { q ->
            selectedBank == null || q?.bankId == selectedBank
        }
        val tabEntries = when (tab) {
            0 -> lists.wrong.filter { bankFilter(it.second) }
            1 -> lists.mastered.filter { bankFilter(it.second) }
            2 -> lists.favorites.filter { bankFilter(it.second) }
            else -> lists.kills.filter { bankFilter(it.second) }
        }

        // 章节分类：错题按章节归拢，可单选章节做「重刷 / 抽检」
        val chapters = remember(tabEntries) {
            tabEntries.mapNotNull { it.second?.chapter?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
        }
        val entries = if (chapter == null) tabEntries else tabEntries.filter { it.second?.chapter == chapter }

        // 重刷 / 抽检入口：需要先选定题库（否则不知道在哪个库里重刷）
        if (tabEntries.isNotEmpty() && selectedBank != null) {
            val scopeBank = selectedBank!!
            val scopeChapters = listOfNotNull(chapter)
            val rebrushFilter = StudyFilter(chapters = scopeChapters, status = "WRONG").fingerprint()
            val sampleFilter = StudyFilter(chapters = scopeChapters, status = "WRONG").fingerprint()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onStartStudy(scopeBank, StudyMode.SEQUENTIAL, rebrushFilter) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp)
                ) { Text("全部重刷", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(
                    onClick = { onStartStudy(scopeBank, StudyMode.RANDOM, sampleFilter) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                ) { Text("随机抽检", style = MaterialTheme.typography.labelLarge) }
            }
            Text(
                if (chapter == null) "范围：整个题库的错题" else "范围：章节「${chapter!!.take(14)}」的错题",
                Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        if (chapters.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = chapter == null,
                    onClick = { chapter = null },
                    label = { Text("全部章节", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = chapter == null,
                        borderColor = extra.creamLine,
                        selectedBorderColor = extra.terracotta.copy(alpha = 0.4f)
                    )
                )
                chapters.forEach { c ->
                    FilterChip(
                        selected = chapter == c,
                        onClick = { chapter = if (chapter == c) null else c },
                        label = {
                            Text(c.take(12), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = chapter == c,
                            borderColor = extra.creamLine,
                            selectedBorderColor = extra.terracotta.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    when (tab) {
                        0 -> "还没有错题，刷题去吧！"
                        1 -> "还没有已掌握的错题"
                        2 -> "还没有收藏，做题时点右上角星标"
                        else -> "还没有斩过的题——做题时点剪刀图标斩掉已掌握的题"
                    },
                    Modifier.fillMaxWidth().padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.second?.id ?: it.hashCode().toLong() }) { (meta, q) ->
                    if (q == null) return@items
                    PaperCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { detail = q }
                    ) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SemanticChip(typeLabel(q.type), ChipTone.Brand)
                                q.chapter?.let {
                                    Spacer(Modifier.width(6.dp))
                                    SemanticChip(it.take(10), ChipTone.Neutral)
                                }
                                Spacer(Modifier.weight(1f))
                                when (meta) {
                                    is WrongRecord -> SemanticChip("错 ${meta.wrongCount} 次", ChipTone.Sage)
                                    is FavoriteRecord -> SemanticChip("收藏", ChipTone.Amber)
                                    is com.examhelper.shua.data.db.KillRecord -> SemanticChip("已斩", ChipTone.Neutral)
                                }
                            }
                            Text(
                                q.stem.take(60) + if (q.stem.length > 60) "…" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { q ->
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(typeLabel(q.type), style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(q.stem, style = MaterialTheme.typography.bodyMedium)
                    q.answer?.let {
                        Text(
                            "参考答案：$it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    q.analysis?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (tab == 3) {
                        TextButton(onClick = {
                            vm.reviveKill(q.id)
                            detail = null
                        }) { Text("恢复此题") }
                    } else {
                        TextButton(onClick = {
                            vm.markMastered(q.id)
                            detail = null
                        }) { Text("标记掌握") }
                        TextButton(onClick = {
                            vm.removeWrong(q.id)
                            detail = null
                        }) { Text("移出错题本", color = extra.sage) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }
        )
    }
}
