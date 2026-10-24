package com.examhelper.shua.ui.generate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.GenerateJob
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.generate.AnswerGenerator
import com.examhelper.shua.data.generate.GenerateWorker
import com.examhelper.shua.data.repo.BankRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GenerateViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bankId: Long = savedStateHandle.get<String>("bankId")?.toLongOrNull() ?: -1L
    private val container = ShuaApp.instance.container
    private val bankRepo: BankRepository = container.bankRepo
    private val jobDao = container.database.generateJobDao()

    data class UiState(
        val bankName: String = "",
        val questions: List<Question> = emptyList(),
        val noAnswer: Int = 0,
        val noAnalysis: Int = 0,
        val providers: Int = 0,
        val primaryName: String? = null,
        val sanSheng: Boolean = false,
        val concurrency: Int = 3,
        val job: GenerateJob? = null,
        /** 生成过程日志 */
        val log: List<String> = emptyList()
    ) {
        val running: Boolean get() = job?.stage == "RUNNING"
        val answerCoverage: Int
            get() = if (questions.isEmpty()) 0 else (questions.size - noAnswer) * 100 / questions.size
        val analysisCoverage: Int
            get() = if (questions.isEmpty()) 0 else (questions.size - noAnalysis) * 100 / questions.size
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            reloadStatics()
        }
        viewModelScope.launch {
            var lastMessage: String? = null
            jobDao.observe(bankId).collect { job ->
                if (job != null) {
                    val newLog = if (job.message != null && job.message != lastMessage) {
                        lastMessage = job.message
                        _state.value.log + job.message
                    } else _state.value.log
                    _state.value = _state.value.copy(job = job, log = newLog)
                    reloadStatics()
                }
            }
        }
    }

    private suspend fun reloadStatics() {
        val bank = bankRepo.getBank(bankId)
        val questions = bankRepo.getQuestions(bankId)
        val providers = container.database.providerDao().observeAll().first().filter { it.enabled }
        val settings = container.settings.settings.first()
        _state.value = _state.value.copy(
            bankName = bank?.name.orEmpty(),
            questions = questions,
            noAnswer = questions.count { it.answer.isNullOrBlank() },
            noAnalysis = questions.count { it.analysis.isNullOrBlank() },
            providers = providers.size,
            primaryName = providers.firstOrNull { it.isPrimary }?.name,
            sanSheng = settings.sanShengLiuBu && providers.size >= 2,
            concurrency = settings.parseConcurrency.coerceIn(1, 6)
        )
    }

    fun start(forceAll: Boolean) {
        GenerateWorker.enqueue(
            context = ShuaApp.instance,
            bankId = bankId,
            forceAll = forceAll,
            concurrency = _state.value.concurrency
        )
    }

    fun cancel() {
        GenerateWorker.cancel(ShuaApp.instance, bankId)
    }
}
