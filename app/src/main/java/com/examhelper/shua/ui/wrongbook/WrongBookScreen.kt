package com.examhelper.shua.ui.wrongbook

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.FavoriteRecord
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.ui.bank.typeLabel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrongBookScreen(onOpenBank: (Long) -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val lists by vm.lists.collectAsState()
    val selectedBank by vm.selectedBank.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Question?>(null) }

    Scaffold(
        topBar = { com.examhelper.shua.ui.theme.CompactTopBar(title = "错题本") }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 两行页签：数字加粗放大（主题红）+ 小字标签
            val tabDefs = listOf(
                "未掌握" to lists.wrong.size,
                "已掌握" to lists.mastered.size,
                "收藏" to lists.favorites.size,
                "斩题墙" to lists.kills.size
            )
            Row(Modifier.fillMaxWidth()) {
                tabDefs.forEachIndexed { i, (label, count) ->
                    val selected = tab == i
                    Column(
                        Modifier.weight(1f)
                            .clickable { tab = i }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$count",
                            style = androidx.compose.ui.text.TextStyle(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 20.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        )
                        Text(
                            label,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            // 题库筛选
            if (lists.bankNames.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = selectedBank == null,
                        onClick = { vm.selectBank(null) },
                        label = { Text("全部题库") }
                    )
                    lists.bankNames.forEach { (id, name) ->
                        androidx.compose.material3.FilterChip(
                            selected = selectedBank == id,
                            onClick = { vm.selectBank(id) },
                            label = { Text(name.take(10), maxLines = 1) }
                        )
                    }
                }
            }
            val bankFilter: (Question?) -> Boolean = { q ->
                selectedBank == null || q?.bankId == selectedBank
            }
            val entries = when (tab) {
                0 -> lists.wrong.filter { bankFilter(it.second) }
                1 -> lists.mastered.filter { bankFilter(it.second) }
                2 -> lists.favorites.filter { bankFilter(it.second) }
                else -> lists.kills.filter { bankFilter(it.second) }
            }
            if (entries.isEmpty()) {
                Text(
                    when (tab) {
                        0 -> "还没有错题，刷题去吧！"
                        1 -> "还没有已掌握的错题"
                        2 -> "还没有收藏，做题时点右上角星标"
                        else -> "还没有斩过的题——做题时点剪刀图标斩掉已掌握的题"
                    },
                    Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.second?.id ?: it.hashCode().toLong() }) { (meta, q) ->
                        if (q == null) return@items
                        Card(onClick = { detail = q }, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row {
                                    Text(typeLabel(q.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.weight(1f))
                                    when (meta) {
                                        is WrongRecord -> Text("错 ${meta.wrongCount} 次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        is FavoriteRecord -> Text("收藏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                        is com.examhelper.shua.data.db.KillRecord -> Text("已斩", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                Text(
                                    q.stem.take(50) + if (q.stem.length > 50) "…" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { q ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(typeLabel(q.type)) },
            text = {
                Column {
                    Text(q.stem, style = MaterialTheme.typography.bodyMedium)
                    q.answer?.let {
                        Text("答案：$it", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    q.analysis?.let {
                        Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
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
                        }) { Text("移出错题本") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }
        )
    }
}
