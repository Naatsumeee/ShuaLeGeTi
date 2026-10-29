package com.examhelper.shua.ui.generate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.GenerateJob
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.generate.GenerateScope
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

        /**
         * 「生成缺失」按钮上的数字：按需求口径改为**缺解析**的题数。
         * 缺答案的题仍会被生成（见 [scopedIncomplete]），只是不体现在这个计数里。
         */
        val scopedIncomplete: Int
            get() = scoped(questions).count { it.analysis.isNullOrBlank() }

        /** 实际送生成的题：缺答案 **或** 缺解析（用于按钮可用性判定） */
        val scopedTodo: Int
            get() = scoped(questions).count { it.answer.isNullOrBlank() || it.analysis.isNullOrBlank() }

        /**
         * 错误范围：已有答案却缺解析的题。
         * AI 全部跑完后解析覆盖率仍不满，就是这些题被跳过了（断点续传只按「缺答案」判定），
         * 单独列出来才能补齐。
         */
        val scopedBroken: Int
            get() = scoped(questions).count { !it.answer.isNullOrBlank() && it.analysis.isNullOrBlank() }
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
            chapters = sortChapters(questions.mapNotNull { it.chapter?.takeIf { c -> c.isNotBlank() } }.distinct())
        )
    }

    /**
     * 章节按「自然顺序」排序：直接 sorted() 是字典序，"第10章" 会排在 "第2章" 前面，
     * 这就是生成范围里章节乱序的原因。这里把字符串切成数字段与非数字段逐段比较。
     */
    private fun sortChapters(list: List<String>): List<String> {
        val nat = java.text.Collator.getInstance(java.util.Locale.CHINA)
        nat.strength = java.text.Collator.PRIMARY
        return list.sortedWith { a, b -> naturalCompare(a, b) ?: nat.compare(a, b) }
    }

    private fun naturalCompare(a: String, b: String): Int? {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca.code - cb.code
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
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
        // 「生成缺失」的语义是「补齐缺失」：缺答案或缺解析都算，
        // 否则题库自带答案的题永远不会被送去生成，解析覆盖率永远补不满。
        val scope = if (forceAll) GenerateScope.ALL else GenerateScope.MISSING
        GenerateWorker.enqueue(
            context = ShuaApp.instance,
            bankId = bankId,
            forceAll = forceAll,
            concurrency = _state.value.concurrency,
            chapters = _state.value.selectedChapters,
            scope = scope
        )
    }

    /** 极简模式：补齐缺答案/缺解析的题，解析限制 30 字以内 */
    fun startMinimal() {
        GenerateWorker.enqueue(
            context = ShuaApp.instance,
            bankId = bankId,
            forceAll = false,
            concurrency = _state.value.concurrency,
            chapters = _state.value.selectedChapters,
            scope = GenerateScope.MINIMAL
        )
    }

    /** 错误范围：补「有答案但缺解析」的题 */
    fun startBroken() {
        GenerateWorker.enqueue(
            context = ShuaApp.instance,
            bankId = bankId,
            forceAll = false,
            concurrency = _state.value.concurrency,
            chapters = _state.value.selectedChapters,
            scope = GenerateScope.BROKEN
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
