package com.examhelper.shua.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChapterBlock(
    val blockIndex: Int,
    val chapter: String?,
    val startIndex: Int,
    val endIndexInclusive: Int,
    val questions: List<Question>
) {
    val pageRange: IntRange?
        get() = questions.mapNotNull { it.sourcePage }.takeIf { it.isNotEmpty() }
            ?.let { it.min()..it.max() }
}

internal fun normalizeChapterLabel(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() }

internal fun buildChapterBlocks(questions: List<Question>): List<ChapterBlock> {
    if (questions.isEmpty()) return emptyList()
    val blocks = mutableListOf<ChapterBlock>()
    var start = 0
    while (start < questions.size) {
        val chap = normalizeChapterLabel(questions[start].chapter)
        var end = start
        while (end + 1 < questions.size && normalizeChapterLabel(questions[end + 1].chapter) == chap) {
            end++
        }
        blocks += ChapterBlock(
            blockIndex = blocks.size,
            chapter = chap,
            startIndex = start,
            endIndexInclusive = end,
            questions = questions.subList(start, end + 1)
        )
        start = end + 1
    }
    return blocks
}

internal fun computeInsertChapterBreakRange(
    questions: List<Question>,
    atQuestionIndex: Int
): IntRange? {
    if (atQuestionIndex !in questions.indices) return null
    val base = normalizeChapterLabel(questions[atQuestionIndex].chapter)
    var end = atQuestionIndex
    while (end + 1 < questions.size && normalizeChapterLabel(questions[end + 1].chapter) == base) {
        end++
    }
    return atQuestionIndex..end
}

internal fun computeRemoveChapterBreakPlan(
    questions: List<Question>,
    blockStartIndex: Int
): Pair<String?, IntRange>? {
    if (blockStartIndex !in questions.indices) return null
    val base = normalizeChapterLabel(questions[blockStartIndex].chapter)
    var end = blockStartIndex
    while (end + 1 < questions.size && normalizeChapterLabel(questions[end + 1].chapter) == base) {
        end++
    }
    val targetChapter = if (blockStartIndex > 0) {
        normalizeChapterLabel(questions[blockStartIndex - 1].chapter)
    } else {
        null
    }
    return targetChapter to (blockStartIndex..end)
}

class EditorViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L

    data class UiState(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val bankName: String = "",
        val questions: List<Question> = emptyList(),
        val chapterBlocks: List<ChapterBlock> = emptyList(),
        val editing: Question? = null,
        val showBatchDialog: Boolean = false,
        val insertBreakAtIndex: Int? = null,
        val renamingBlock: ChapterBlock? = null,
        val removingBlock: ChapterBlock? = null,
        val toast: String? = null
    ) {
        val chapterSummary: List<Pair<String, Int>>
            get() {
                val counts = linkedMapOf<String, Int>()
                questions.forEach { q ->
                    val name = normalizeChapterLabel(q.chapter) ?: "未分章"
                    counts[name] = (counts[name] ?: 0) + 1
                }
                return counts.entries.map { it.key to it.value }
            }
        val pageRange: IntRange?
            get() = questions.mapNotNull { it.sourcePage }.takeIf { it.isNotEmpty() }
                ?.let { it.min()..it.max() }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val container = ShuaApp.instance.container
    private val bankRepo = container.bankRepo

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val bank = bankRepo.getBank(bankId)
            val questions = bankRepo.getQuestions(bankId)
            _state.value = _state.value.copy(
                loading = false,
                busy = false,
                bankName = bank?.name.orEmpty(),
                questions = questions,
                chapterBlocks = buildChapterBlocks(questions)
            )
        }
    }

    fun openBatchDialog() {
        _state.value = _state.value.copy(showBatchDialog = true)
    }

    fun closeBatchDialog() {
        _state.value = _state.value.copy(showBatchDialog = false)
    }

    /** 按题号范围批量分章（1-based，闭区间） */
    fun assignByIndex(fromQ: Int, toQ: Int, chapterName: String) {
        val normalized = normalizeChapterLabel(chapterName) ?: return
        val qs = _state.value.questions
        if (qs.isEmpty() || _state.value.busy) return
        val from = fromQ.coerceIn(1, qs.size)
        val to = toQ.coerceIn(from, qs.size)
        val ids = qs.subList(from - 1, to).map { it.id }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestionsChapter(bankId, ids, normalized)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showBatchDialog = false,
                    toast = "已将第 $from-$to 题划分为「$normalized」(${ids.size} 题)"
                )
                reload()
            }
        }
    }

    /** 按页码范围批量分章 */
    fun assignByPage(fromPage: Int, toPage: Int, chapterName: String) {
        val normalized = normalizeChapterLabel(chapterName) ?: return
        if (_state.value.busy) return
        val qs = _state.value.questions
        val lo = minOf(fromPage, toPage)
        val hi = maxOf(fromPage, toPage)
        val hit = qs.filter { it.sourcePage != null && it.sourcePage in lo..hi }
        if (hit.isEmpty()) {
            _state.value = _state.value.copy(toast = "该页码范围内没有题目")
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestionsChapter(bankId, hit.map { it.id }, normalized)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showBatchDialog = false,
                    toast = "已把第 $lo-$hi 页的 ${hit.size} 题划分为「$normalized」"
                )
                reload()
            }
        }
    }

    fun openInsertBreak(atQuestionIndex: Int) {
        _state.value = _state.value.copy(insertBreakAtIndex = atQuestionIndex)
    }

    fun closeInsertBreak() {
        _state.value = _state.value.copy(insertBreakAtIndex = null)
    }

    /** 在任意两道题之间新建分章标记，作用到当前分章块的末尾（下一个分章边界前） */
    fun confirmInsertBreak(atQuestionIndex: Int, chapterName: String) {
        val normalized = normalizeChapterLabel(chapterName) ?: return
        val qs = _state.value.questions
        if (_state.value.busy) return
        val range = computeInsertChapterBreakRange(qs, atQuestionIndex) ?: return
        val currentChap = normalizeChapterLabel(qs[atQuestionIndex].chapter)
        if (currentChap == normalized) {
            _state.value = _state.value.copy(insertBreakAtIndex = null, toast = "该区间已属于「$normalized」")
            return
        }
        val ids = qs.subList(range.first, range.last + 1).map { it.id }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestionsChapter(bankId, ids, normalized)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    insertBreakAtIndex = null,
                    toast = "已从第 ${range.first + 1} 题新建分章「$normalized」(${ids.size} 题)"
                )
                reload()
            }
        }
    }

    fun openRenameBlock(block: ChapterBlock) {
        _state.value = _state.value.copy(renamingBlock = block)
    }

    fun closeRenameBlock() {
        _state.value = _state.value.copy(renamingBlock = null)
    }

    fun confirmRenameBlock(block: ChapterBlock, newName: String) {
        val normalized = normalizeChapterLabel(newName)
        if (_state.value.busy) return
        val ids = block.questions.map { it.id }
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestionsChapter(bankId, ids, normalized)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    renamingBlock = null,
                    toast = if (normalized != null) "已将该段 ${ids.size} 题设为「$normalized」"
                    else "已将该段 ${ids.size} 题设为未分章"
                )
                reload()
            }
        }
    }

    fun openRemoveBreak(block: ChapterBlock) {
        _state.value = _state.value.copy(removingBlock = block)
    }

    fun closeRemoveBreak() {
        _state.value = _state.value.copy(removingBlock = null)
    }

    /** 删除分章标记：将该分章块合并回上一章（首块删除则设为未分章） */
    fun confirmRemoveBreak(block: ChapterBlock) {
        val qs = _state.value.questions
        if (_state.value.busy) return
        val plan = computeRemoveChapterBreakPlan(qs, block.startIndex) ?: return
        val (targetChapter, range) = plan
        val ids = qs.subList(range.first, range.last + 1).map { it.id }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestionsChapter(bankId, ids, targetChapter)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    removingBlock = null,
                    toast = if (targetChapter != null) {
                        "已删除分章并合并至「$targetChapter」"
                    } else {
                        "已取消分章标记"
                    }
                )
                reload()
            }
        }
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun openEdit(questionId: Long) {
        _state.value = _state.value.copy(editing = _state.value.questions.firstOrNull { it.id == questionId })
    }

    fun closeEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun saveQuestion(
        chapter: String?, type: QuestionType, subtype: String?, stem: String,
        options: List<QuestionOption>, answer: String?, analysis: String?
    ) {
        val q = _state.value.editing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bankRepo.updateQuestion(
                q.copy(
                    chapter = chapter?.trim()?.takeIf { it.isNotBlank() },
                    type = type,
                    subtype = subtype,
                    stem = stem.trim(),
                    optionsJson = options.filter { it.text.isNotBlank() }.takeIf { it.isNotEmpty() }
                        ?.let(JsonCodec::encodeOptions),
                    answer = answer?.trim()?.takeIf { it.isNotBlank() },
                    analysis = analysis?.trim()?.takeIf { it.isNotBlank() }
                )
            )
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(editing = null, toast = "题目已保存")
                reload()
            }
        }
    }
}
