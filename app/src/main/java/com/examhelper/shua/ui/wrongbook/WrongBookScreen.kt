package com.examhelper.shua.ui.wrongbook

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.examhelper.shua.ui.theme.Latex
import com.examhelper.shua.ui.theme.Markdown
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
        /** 重点标记的错题（单独成组复刷） */
        val flagged: List<Pair<WrongRecord, Question?>> = emptyList(),
        /** 收藏题目 id 集合（给每题的菜单显示「取消收藏 / 收藏」） */
        val favoriteIds: Set<Long> = emptySet(),
        /** 斩题 id 集合（菜单显示「斩题 / 恢复」） */
        val killedIds: Set<Long> = emptySet(),
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
                _lists.value = _lists.value.copy(
                    favorites = records.map { it to questions[it.questionId] },
                    favoriteIds = records.map { it.questionId }.toSet()
                )
                refreshBankNames()
            }
        }
        viewModelScope.launch {
            studyRepo.observeKills().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(
                    kills = records.map { it to questions[it.questionId] },
                    killedIds = records.map { it.questionId }.toSet()
                )
                refreshBankNames()
            }
        }
        viewModelScope.launch {
            studyRepo.observeFlaggedWrong().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(flagged = records.map { it to questions[it.questionId] })
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
        // 合并题库后，旧题库 id 已搬走；错题记录里的 bankId 已随合并更新。
        // 题库组是聚合视图，不直接拥有错题，不在筛选器里显示空组。
        _lists.value = _lists.value.copy(bankNames = names.filterKeys { it in ids })
    }

    fun markMastered(questionId: Long) = viewModelScope.launch { studyRepo.markMastered(questionId) }
    fun removeWrong(questionId: Long) = viewModelScope.launch { studyRepo.removeWrong(questionId) }
    fun reviveKill(questionId: Long) = viewModelScope.launch { studyRepo.reviveKill(questionId) }

    /** 重点标记：错题本里单独成组复刷 */
    fun toggleFlag(questionId: Long) = viewModelScope.launch { studyRepo.toggleFlag(questionId) }

    /** 收藏/取消收藏（错题本菜单里直接切，不必回做题页） */
    fun toggleFavorite(question: Question) = viewModelScope.launch { studyRepo.toggleFavorite(question) }

    /** 斩题/恢复（斩掉的题从错题本列表里消失，进斩题墙） */
    fun toggleKill(question: Question) = viewModelScope.launch { studyRepo.toggleKill(question) }

    /** 整本忽略：一次清掉该题库的全部错题记录 */
    fun ignoreBank(bankId: Long) = viewModelScope.launch { studyRepo.ignoreWrongBank(bankId) }
}

@Composable
fun WrongBookScreen(
    onOpenBank: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 错题重刷 / 随机抽检：复用题库刷题的同款做题页（study 路由 + 筛选指纹） */
    onStartStudy: (bankId: Long, mode: StudyMode, filterJson: String) -> Unit = { _, _, _ -> },
    /** 点某一道题 → 单题详情页（完整题干/选项/参考答案/解析） */
    onOpenQuestion: ((questionId: Long) -> Unit)? = null
) {
    val vm: LibraryViewModel = viewModel()
    val lists by vm.lists.collectAsState()
    val selectedBank by vm.selectedBank.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Question?>(null) }
    /** 章节筛选（null = 该题库全部章节） */
    var chapter by remember { mutableStateOf<String?>(null) }
    /** 「忽略整本」二次确认 */
    var ignoreConfirm by remember { mutableStateOf(false) }
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
            "重点" to lists.flagged.size,
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


        val bankFilter: (Question?) -> Boolean = { q ->
            selectedBank == null || q?.bankId == selectedBank
        }
        val tabEntries = when (tab) {
            0 -> lists.wrong.filter { bankFilter(it.second) }
            1 -> lists.mastered.filter { bankFilter(it.second) }
            2 -> lists.flagged.filter { bankFilter(it.second) }
            3 -> lists.favorites.filter { bankFilter(it.second) }
            else -> lists.kills.filter { bankFilter(it.second) }
        }

        // 章节分类：错题按章节归拢，可单选章节做「重刷 / 抽检」
        val chapters = remember(tabEntries) {
            tabEntries.mapNotNull { it.second?.chapter?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
        }
        val entries = if (chapter == null) tabEntries else tabEntries.filter { it.second?.chapter == chapter }

        // 筛选条：题库 / 章节 两个下拉菜单，替代原来两整排横向滚动的 chip
        //（用户反馈那一块太松散、高度过大 —— 从约 200dp 压到约 62dp）。
        if (lists.bankNames.isNotEmpty()) {
            val bankEntries: List<Pair<Long?, String>> =
                listOf<Pair<Long?, String>>(null to "全部题库") +
                    lists.bankNames.entries.sortedBy { it.value }.map { it.key to it.value }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PickerPill(
                    label = "题库",
                    value = selectedBank?.let { lists.bankNames[it] } ?: "全部题库",
                    modifier = Modifier.weight(1f),
                    options = bankEntries.map { (id, name) -> name to (id == selectedBank) },
                    onSelect = { i -> vm.selectBank(bankEntries[i].first) }
                )
                PickerPill(
                    label = "章节",
                    value = chapter ?: "全部章节",
                    enabled = chapters.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    options = (listOf<String?>(null) + chapters).map { c -> (c ?: "全部章节") to (c == chapter) },
                    onSelect = { i -> chapter = if (i == 0) null else chapters[i - 1] }
                )
            }
        }
        // 重刷 / 抽检入口：需要先选定题库（否则不知道在哪个库里重刷）
        if (tabEntries.isNotEmpty() && selectedBank != null) {
            val scopeBank = selectedBank!!
            val scopeChapters = listOfNotNull(chapter)
            val rebrushFilter = StudyFilter(
                chapters = scopeChapters, status = "WRONG", freshAnswer = true
            ).fingerprint()
            val sampleFilter = StudyFilter(
                chapters = scopeChapters, status = "WRONG", freshAnswer = true
            ).fingerprint()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
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
            // 范围提示 +「忽略整本」独占一行（用户要求：不要再和题库/章节筛选挤一排）。
            // 整本忽略会删掉该库全部错题记录，属破坏性操作，加二次确认。
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (chapter == null) "范围：整个题库的错题" else "范围：章节「${chapter!!.take(14)}」的错题",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { ignoreConfirm = true }) {
                    Text("忽略整本", style = MaterialTheme.typography.labelMedium, color = extra.sage)
                }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    when (tab) {
                        0 -> "还没有错题，刷题去吧！"
                        1 -> "还没有已掌握的错题"
                        2 -> "还没有标记重点的题 —— 在题目右上角菜单里点「标记重点」"
                        3 -> "还没有收藏，做题时点右上角星标"
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
                        // 点卡片进单题详情页看完整题目与解析；没有回调时退回原来的弹窗
                        onClick = {
                            if (onOpenQuestion != null) onOpenQuestion(q.id) else detail = q
                        }
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
                                    is WrongRecord -> if (meta.flagged) {
                                        SemanticChip("重点", ChipTone.Amber)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    else -> {}
                                }
                                when (meta) {
                                    is WrongRecord -> SemanticChip("错 ${meta.wrongCount} 次", ChipTone.Sage)
                                    is FavoriteRecord -> SemanticChip("收藏", ChipTone.Amber)
                                    is com.examhelper.shua.data.db.KillRecord -> SemanticChip("已斩", ChipTone.Neutral)
                                }
                                // 每题的二级菜单：收藏 / 斩题 / 标记重点 / 从错题本删除
                                Box {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { menuOpen = true }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "更多",
                                            modifier = Modifier.size(17.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        val fav = q.id in lists.favoriteIds
                                        DropdownMenuItem(
                                            text = { Text(if (fav) "取消收藏" else "收藏") },
                                            leadingIcon = {
                                                Icon(
                                                    if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(17.dp),
                                                    tint = if (fav) extra.amber else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            onClick = { menuOpen = false; vm.toggleFavorite(q) }
                                        )
                                        val killed = q.id in lists.killedIds
                                        DropdownMenuItem(
                                            text = { Text(if (killed) "取消斩题" else "斩题") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.ContentCut,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            },
                                            onClick = { menuOpen = false; vm.toggleKill(q) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if ((meta as? WrongRecord)?.flagged == true) "取消重点" else "标记重点") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Flag,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(17.dp),
                                                    tint = if ((meta as? WrongRecord)?.flagged == true) extra.amber
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            enabled = meta is WrongRecord,
                                            onClick = { menuOpen = false; vm.toggleFlag(q.id) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("从错题本删除", color = extra.sage) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(17.dp),
                                                    tint = extra.sage
                                                )
                                            },
                                            onClick = { menuOpen = false; vm.removeWrong(q.id) }
                                        )
                                    }
                                }
                            }
                            // 硬截断会切断 LaTeX 命令，交给排版层按 2 行省略
                            Latex.LatexText(
                                text = q.stem,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    if (ignoreConfirm) {
        val bankName = selectedBank?.let { lists.bankNames[it] } ?: "该题库"
        AlertDialog(
            onDismissRequest = { ignoreConfirm = false },
            title = { Text("忽略整本？", style = serifTitle(17.sp, FontWeight.Medium)) },
            text = {
                Text(
                    "将清空「" + bankName + "」在错题本里的全部记录（收藏、斩题不受影响）。此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedBank?.let { vm.ignoreBank(it) }
                    ignoreConfirm = false
                }) { Text("确认忽略", color = extra.sage) }
            },
            dismissButton = {
                TextButton(onClick = { ignoreConfirm = false }) { Text("取消") }
            }
        )
    }

    detail?.let { q ->
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(typeLabel(q.type), style = serifTitle(18.sp, FontWeight.Medium)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Latex.LatexText(
                        text = q.stem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    q.answer?.let {
                        Latex.LatexText(
                            text = "参考答案：$it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            allowDisplay = false
                        )
                    }
                    q.analysis?.let {
                        Markdown.MarkdownText(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (tab == 4) {
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


/**
 * 紧凑下拉筛选器：胶囊里放「小标签 + 当前值 + 箭头」，点开是菜单。
 * 错题本原来用两整排 FilterChip（题库一排、章节一排，都要横向滚动），
 * 小屏上把这区撑到近 200dp 且两排左右参差；改成两个等宽下拉既对齐也矮一半。
 */
@Composable
private fun PickerPill(
    label: String,
    value: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    val extra = shuaColors
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, extra.creamLine, RoundedCornerShape(13.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, (text, selected) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) extra.terracotta else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(i)
                    }
                )
            }
        }
    }
}
