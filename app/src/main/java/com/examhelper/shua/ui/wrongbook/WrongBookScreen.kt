package com.examhelper.shua.ui.wrongbook

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ShuaApp).container
    private val studyRepo = container.studyRepo
    private val questionDao = container.database.questionDao()

    data class Lists(
        val wrong: List<Pair<WrongRecord, Question?>> = emptyList(),
        val mastered: List<Pair<WrongRecord, Question?>> = emptyList(),
        val favorites: List<Pair<FavoriteRecord, Question?>> = emptyList()
    )

    private val _lists = MutableStateFlow(Lists())
    val lists: StateFlow<Lists> = _lists

    init {
        viewModelScope.launch {
            studyRepo.observeWrongBook().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(wrong = records.map { it to questions[it.questionId] })
            }
        }
        viewModelScope.launch {
            studyRepo.observeMasteredWrong().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(mastered = records.map { it to questions[it.questionId] })
            }
        }
        viewModelScope.launch {
            studyRepo.observeFavorites().collect { records ->
                val questions = questionDao.getByIds(records.map { it.questionId }).associateBy { it.id }
                _lists.value = _lists.value.copy(favorites = records.map { it to questions[it.questionId] })
            }
        }
    }

    fun markMastered(questionId: Long) = viewModelScope.launch { studyRepo.markMastered(questionId) }
    fun removeWrong(questionId: Long) = viewModelScope.launch { studyRepo.removeWrong(questionId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrongBookScreen(onOpenBank: (Long) -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val lists by vm.lists.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Question?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("错题本") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("未掌握 (${lists.wrong.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("已掌握 (${lists.mastered.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("收藏 (${lists.favorites.size})") })
            }
            val entries = when (tab) {
                0 -> lists.wrong
                1 -> lists.mastered
                else -> lists.favorites
            }
            if (entries.isEmpty()) {
                Text(
                    when (tab) {
                        0 -> "还没有错题，刷题去吧！"
                        1 -> "还没有已掌握的错题"
                        else -> "还没有收藏，做题时点右上角星标"
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
                    TextButton(onClick = {
                        vm.markMastered(q.id)
                        detail = null
                    }) { Text("标记掌握") }
                    TextButton(onClick = {
                        vm.removeWrong(q.id)
                        detail = null
                    }) { Text("移出错题本") }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }
        )
    }
}
