package com.examhelper.shua.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.QuestionDao
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class UiState(
        val keyword: String = "",
        val results: List<QuestionDao.SearchRow> = emptyList(),
        val searching: Boolean = false,
        val detail: Question? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val bankRepo = ShuaApp.instance.container.bankRepo

    fun updateKeyword(v: String) {
        _state.value = _state.value.copy(keyword = v)
    }

    fun search() {
        val kw = _state.value.keyword.trim()
        if (kw.length < 2 || _state.value.searching) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(searching = true)
            val rows = runCatching { bankRepo.searchAll(kw) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(searching = false, results = rows)
        }
    }

    fun openDetail(id: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                detail = ShuaApp.instance.container.bankRepo.getQuestionsByIds(listOf(id)).firstOrNull()
            )
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全库搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::updateKeyword,
                label = { Text("搜索题干 / 选项 / 解析（至少 2 个字）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = vm::search) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            )
            if (state.results.isEmpty() && !state.searching && state.keyword.length >= 2) {
                Text("没有匹配结果", color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.results, key = { it.id }) { row ->
                    Card(onClick = { vm.openDetail(row.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(typeLabel(row.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                Text(row.bankName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(
                                row.stem.take(60) + if (row.stem.length > 60) "…" else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    state.detail?.let { q ->
        AlertDialog(
            onDismissRequest = vm::closeDetail,
            title = { Text(typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: "")) },
            text = {
                Column {
                    q.sharedStem?.let {
                        Text("【共用题干】$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(q.stem, style = MaterialTheme.typography.bodyMedium)
                    JsonCodec.decodeOptions(q.optionsJson).forEach {
                        Text("${it.key}. ${it.text}", style = MaterialTheme.typography.bodySmall)
                    }
                    q.answer?.let { Text("答案：$it", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                    q.analysis?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeDetail) { Text("关闭") } }
        )
    }
}
