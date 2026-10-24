package com.examhelper.shua.ui.generate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.generate.AggregatedVerdict
import com.examhelper.shua.data.generate.AnswerGenerator
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class GenerateViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    private val container = ShuaApp.instance.container
    private val bankRepo: BankRepository = container.bankRepo
    private val providerDao = container.database.providerDao()
    private val settings: SettingsStore = container.settings

    enum class QStatus { PENDING, RUNNING, OK, FAILED, SKIPPED }

    data class ItemState(
        var question: Question,
        var status: QStatus = QStatus.PENDING,
        var message: String? = null
    )

    data class UiState(
        val bankName: String = "",
        val items: List<ItemState> = emptyList(),
        val running: Boolean = false,
        val doneCount: Int = 0,
        val okCount: Int = 0,
        val failCount: Int = 0,
        val providers: List<LlmProvider> = emptyList(),
        val primaryName: String? = null,
        val sanSheng: Boolean = false,
        val finished: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            val bank = bankRepo.getBank(bankId)
            val questions = bankRepo.getQuestions(bankId)
            val providers = providerDao.observeAll().first().filter { it.enabled }
            val sanSheng = settings.settings.first().sanShengLiuBu
            _state.value = _state.value.copy(
                bankName = bank?.name.orEmpty(),
                items = questions.map { ItemState(it) },
                providers = providers,
                primaryName = providers.firstOrNull { it.isPrimary }?.name,
                sanSheng = sanSheng && providers.size >= 2
            )
        }
    }

    fun start(forceAll: Boolean = false) {
        if (_state.value.running) return
        generateJob = viewModelScope.launch {
            val s = _state.value
            val providers = s.providers
            if (providers.isEmpty()) {
                _state.value = s.copy(error = "尚未配置可用模型，请到\"设置\"添加")
                return@launch
            }
            val primary = providers.firstOrNull { it.isPrimary } ?: providers.first()
            val others = if (s.sanSheng) providers.filter { it.id != primary.id } else emptyList()
            val customPrompt = settings.settings.first().customAnswerPrompt
            val generator = AnswerGenerator(
                container.llmClient,
                customPrompt.ifBlank { com.examhelper.shua.data.llm.Prompts.ANSWER_DEFAULT }
            )

            val targets = s.items.filter { forceAll || it.question.answer.isNullOrBlank() }
            if (targets.isEmpty()) {
                _state.value = s.copy(finished = true)
                return@launch
            }

            _state.value = _state.value.copy(running = true, error = null, doneCount = 0, okCount = 0, failCount = 0)
            val semaphore = Semaphore(2) // 并发批次数，每批 8 题
            val batches = targets.chunked(AnswerGenerator.BATCH_SIZE)

            batches.forEach { batch ->
                semaphore.withPermit {
                    if (!_state.value.running) return@withPermit
                    batch.forEach { updateItem(it.question.id) { it.status = QStatus.RUNNING } }
                    val verdicts = withTimeoutOrNull(300_000) {
                        runCatching { generator.aggregateBatch(batch.map { it.question }, primary, others) }.getOrNull()
                    }
                    if (verdicts != null) {
                        batch.forEachIndexed { i, item ->
                            val v = verdicts.getOrNull(i)
                            if (v != null && v.answer != null) {
                                val updated = item.question.copy(
                                    answer = v.answer,
                                    analysis = v.analysis ?: item.question.analysis,
                                    aiGenerated = true,
                                    confidence = v.confidence,
                                    agreement = v.agreement,
                                    dissentJson = v.dissents.takeIf { it.isNotEmpty() }
                                        ?.let(JsonCodec::encodeDissent)
                                )
                                bankRepo.updateQuestion(updated)
                                updateItem(item.question.id) {
                                    it.status = QStatus.OK
                                    it.message = "置信度 ${v.confidence}" +
                                        (v.agreement?.let { a -> " · $a" } ?: "")
                                    it.question = updated
                                }
                                bumpCount(ok = true)
                            } else {
                                updateItem(item.question.id) { it.status = QStatus.FAILED; it.message = "生成失败（可重试）" }
                                bumpCount(ok = false)
                            }
                        }
                    } else {
                        batch.forEach {
                            updateItem(it.question.id) { it.status = QStatus.FAILED; it.message = "本批生成失败（可重试）" }
                            bumpCount(ok = false)
                        }
                    }
                }
            }
            _state.value = _state.value.copy(running = false, finished = true)
        }
    }

    fun stop() {
        generateJob?.cancel()
        _state.value = _state.value.copy(running = false)
    }

    private fun updateItem(questionId: Long, mutate: (ItemState) -> Unit) {
        _state.value = _state.value.copy(
            items = _state.value.items.map { if (it.question.id == questionId) it.also(mutate) else it }
        )
    }

    private fun bumpCount(ok: Boolean) {
        val s = _state.value
        _state.value = s.copy(
            doneCount = s.doneCount + 1,
            okCount = if (ok) s.okCount + 1 else s.okCount,
            failCount = if (!ok) s.failCount + 1 else s.failCount
        )
    }
}
