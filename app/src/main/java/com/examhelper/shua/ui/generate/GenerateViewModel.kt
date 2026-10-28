package com.examhelper.shua.ui.generate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.GenerateJob
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.generate.GenerateWorker
import com.examhelper.shua.data.repo.BankRepository
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

    /** 判定「卡住」的静默阈值：进度超过 3 分钟没动且 Worker 已不在跑 */
    private val staleMs = 3 * 60 * 1000L

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
        val log: List<String> = emptyList(),
        /** 题库里出现过的章节（用于按章节范围重生成） */
        val chapters: List<String> = emptyList(),
        /** 当前选中的章节范围（空 = 全库） */
        val selectedChapters: List<String> = emptyList(),
        /** 上次进度卡住（UI 需要显示自愈按钮） */
        val stalled: Boolean = false
    ) {
        val running: Boolean get() = job?.stage == "RUNNING"
        val answerCoverage: Int
            get() = if (questions.isEmpty()) 0 else (questions.size - noAnswer) * 100 / questions.size
        val analysisCoverage: Int
            get() = if (questions.isEmpty()) 0 else (questions.size - noAnalysis) * 100 / questions.size

        /** 当前范围内缺答案的题数 */
        val scopedNoAnswer: Int
            get() = scoped(questions).count { it.answer.isNullOrBlank() }
        val scopedNoAnalysis: Int
            get() = scoped(questions).count { it.analysis.isNullOrBlank() }
        val scopedTotal: Int get() = scoped(questions).size

        private fun scoped(list: List<Question>): List<Question> =
            if (selectedChapters.isEmpty()) list else list.filter { it.chapter in selectedChapters }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { reloadStatics() }
        viewModelScope.launch {
            var lastMessage: String? = null
            jobDao.observe(bankId).collect { job ->
                if (job != null) {
                    val newLog = if (job.message != null && job.message != lastMessage) {
                        lastMessage = job.message
                        _state.value.log + job.message
                    } else _state.value.log
                    _state.value = _state.value.copy(job = job, log = newLog, stalled = isStalled(job))
                    reloadStatics()
                }
            }
        }
        // 定时复查「卡住」判定：后台进程被杀时数据库仍停在 RUNNING，需要 UI 主动自愈
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(20_000)
                val job = _state.value.job ?: continue
                val stalled = isStalled(job)
                if (stalled != _state.value.stalled) {
                    _state.value = _state.value.copy(stalled = stalled)
                }
            }
        }
    }

    /** RUNNING 但长时间无更新 → 判定为卡住（进程被杀/系统回收） */
    private fun isStalled(job: GenerateJob): Boolean =
        job.stage == "RUNNING" && System.currentTimeMillis() - job.updatedAt > staleMs

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
            concurrency = settings.parseConcurrency.coerceIn(1, 6),
            chapters = questions.mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
        )
    }

    /** 勾选/取消章节范围（空 = 全库） */
    fun toggleChapter(c: String) {
        val cur = _state.value.selectedChapters
        _state.value = _state.value.copy(
            selectedChapters = if (c in cur) cur - c else cur + c
        )
    }

    fun clearChapters() {
        _state.value = _state.value.copy(selectedChapters = emptyList())
    }

    fun start(forceAll: Boolean) {
        GenerateWorker.enqueue(
            context = ShuaApp.instance,
            bankId = bankId,
            forceAll = forceAll,
            concurrency = _state.value.concurrency,
            chapters = _state.value.selectedChapters
        )
    }

    fun cancel() {
        GenerateWorker.cancel(ShuaApp.instance, bankId)
    }

    /**
     * 自愈：把停在 RUNNING 的僵尸任务改成 CANCELED，
     * 已生成的部分保留在题库里，随后可直接点「生成缺失」继续。
     */
    fun healStalled() {
        viewModelScope.launch {
            // 先取消 WorkManager 里的僵尸唯一任务（KEEP 策略下不取消会导致再次入队被忽略）
            GenerateWorker.cancel(ShuaApp.instance, bankId)
            val job = jobDao.get(bankId) ?: return@launch
            jobDao.upsert(
                job.copy(
                    stage = "CANCELED",
                    message = "进度已中断（后台任务被系统回收）· 已生成 ${job.ok} 题保留，可重新开始继续",
                    updatedAt = System.currentTimeMillis()
                )
            )
            _state.value = _state.value.copy(stalled = false)
        }
    }

    /** 清空任务记录，回到干净的初始状态（不动题库数据） */
    fun clearJob() {
        viewModelScope.launch {
            jobDao.upsert(
                GenerateJob(
                    bankId = bankId,
                    stage = "CANCELED",
                    message = "已清空任务记录",
                    updatedAt = System.currentTimeMillis()
                )
            )
            _state.value = _state.value.copy(log = emptyList(), stalled = false)
        }
    }
}
