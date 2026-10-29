package com.examhelper.shua.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.QuestionDao
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.ui.bank.typeLabel
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.shuaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class UiState(
        val keyword: String = "",
        val results: List<QuestionDao.SearchRow> = emptyList(),
        val searching: Boolean = false,
        /** 至少真正搜过一次，才允许显示「没有匹配结果」——否则刚输入两个字就报无结果，看起来像功能坏了 */
        val searched: Boolean = false,
        val detail: Question? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val bankRepo = ShuaApp.instance.container.bankRepo

    /** 输入即搜的防抖 job，避免每敲一个字都查一次库 */
    private var debounceJob: Job? = null

    fun updateKeyword(v: String) {
        _state.value = _state.value.copy(keyword = v, searched = false, results = emptyList())
        debounceJob?.cancel()
        if (v.trim().length < 2) return
        debounceJob = viewModelScope.launch {
            delay(280)
            runSearch(v)
        }
    }

    fun search() {
        debounceJob?.cancel()
        viewModelScope.launch { runSearch(_state.value.keyword) }
    }

    private suspend fun runSearch(raw: String) {
        val kw = raw.trim()
        if (kw.length < 2 || _state.value.searching) return
        _state.value = _state.value.copy(searching = true)
        val rows = runCatching { bankRepo.searchAll(kw) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(searching = false, results = rows, searched = true)
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

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsState()
    val extra = shuaColors

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(title = "全库搜索", onBack = onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::updateKeyword,
                placeholder = { Text("搜索题干 / 选项 / 解析（≥2 字，边输边搜）", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                trailingIcon = {
                    IconButton(onClick = vm::search) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索", tint = extra.terracotta)
                    }
                }
            )

            when {
                state.searching -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = extra.terracotta)
                }
                state.results.isEmpty() && state.searched && state.keyword.length >= 2 -> Box(
                    Modifier.fillMaxWidth().padding(top = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("没有匹配结果", style = serifTitle(16.sp, FontWeight.Medium))
                        Text(
                            "换个关键词试试，至少两个字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                state.results.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(top = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "在全部题库中检索题干、选项与解析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                else -> {
                    Text(
                        "找到 ${state.results.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(state.results, key = { it.id }) { row ->
                            PaperCard(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(15.dp),
                                onClick = { vm.openDetail(row.id) }
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        SemanticChip(typeLabel(row.type), ChipTone.Brand)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            row.bankName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Text(
                                        row.stem,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.detail?.let { q ->
        AlertDialog(
            onDismissRequest = vm::closeDetail,
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(typeLabel(q.type) + (q.subtype?.let { " · $it" } ?: ""), style = serifTitle(17.sp, FontWeight.Medium)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    q.sharedStem?.let {
                        Text("共用题干", style = MaterialTheme.typography.labelSmall, color = extra.amber)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(q.stem, style = MaterialTheme.typography.bodyMedium)
                    JsonCodec.decodeOptions(q.optionsJson).forEach {
                        Text("${it.key}. ${it.text}", style = MaterialTheme.typography.bodySmall)
                    }
                    q.answer?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SemanticChip("答案", ChipTone.Brand)
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = extra.terracotta, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    q.analysis?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeDetail) { Text("关闭", color = extra.terracotta) } }
        )
    }
}
