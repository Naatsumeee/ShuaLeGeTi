package com.examhelper.shua.data.repo

import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.AttemptRecord
import com.examhelper.shua.data.db.FavoriteRecord
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.RecordDao
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.StudyProgress
import com.examhelper.shua.data.db.ProgressDao
import com.examhelper.shua.data.model.JsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** 学习会话状态：当前应展示的题目与游标信息 */
data class StudySession(
    val mode: StudyMode,
    val question: Question?,
    val indexInQueue: Int,   // 当前第几题（1-based 展示用）
    val total: Int,
    val doneCount: Int,      // 已完成数
    val finished: Boolean
)

class StudyRepository(
    private val db: AppDatabase,
    private val progressDao: ProgressDao = db.progressDao(),
    private val recordDao: RecordDao = db.recordDao(),
    private val bankRepo: BankRepository = BankRepository(db)
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeWrongBook() = recordDao.observeWrongBook()
    fun observeMasteredWrong() = recordDao.observeMasteredWrong()
    fun observeFavorites() = recordDao.observeFavorites()
    fun observeTodayStats() = recordDao.observeAttemptCountSince(startOfToday())
    fun observeTotalStats() = recordDao.observeTotalAttempts()

    /** 确保某模式进度存在；筛选条件变化时自动重置；随机巩固首次进入生成乱序队列 */
    suspend fun ensureProgress(
        bankId: Long,
        mode: StudyMode,
        questionIds: List<Long>,
        filterJson: String? = null
    ): StudyProgress {
        val existing = progressDao.get(bankId, mode)
        if (existing != null && existing.filterJson == filterJson &&
            !(mode == StudyMode.RANDOM && existing.randomOrderJson == null)
        ) return existing
        val fresh = if (mode == StudyMode.RANDOM) {
            StudyProgress(
                bankId = bankId, mode = mode, filterJson = filterJson,
                randomOrderJson = JsonCodec.encodeIdList(questionIds.shuffled())
            )
        } else {
            StudyProgress(bankId = bankId, mode = mode, filterJson = filterJson)
        }
        progressDao.upsert(fresh)
        return fresh
    }

    /** 取当前会话状态 */
    suspend fun currentSession(
        bankId: Long,
        mode: StudyMode,
        questions: List<Question>,
        filterJson: String? = null
    ): StudySession {
        val progress = ensureProgress(bankId, mode, questions.map { it.id }, filterJson)
        val byId = questions.associateBy { it.id }
        return when (mode) {
            StudyMode.RANDOM -> {
                val order = JsonCodec.decodeIdList(progress.randomOrderJson)
                val cursor = progress.randomCursor.coerceIn(0, order.size)
                StudySession(
                    mode = mode,
                    question = order.getOrNull(cursor)?.let(byId::get),
                    indexInQueue = cursor + 1,
                    total = order.size,
                    doneCount = cursor,
                    finished = cursor >= order.size
                )
            }
            else -> {
                val index = progress.currentIndex.coerceIn(0, questions.size)
                StudySession(
                    mode = mode,
                    question = questions.getOrNull(index),
                    indexInQueue = index + 1,
                    total = questions.size,
                    doneCount = index,
                    finished = index >= questions.size
                )
            }
        }
    }

    /** 判分（填空/简答由界面自评，不走这里） */
    fun judge(question: Question, userAnswer: String): Boolean = judgeAnswer(question, userAnswer)

    suspend fun submitAnswer(
        bankId: Long,
        question: Question,
        mode: StudyMode,
        userAnswer: String,
        correct: Boolean,
        selfRated: Boolean = false,
        runId: String? = null,
        durationMs: Long? = null
    ) {
        val existing = recordDao.getWrong(question.id)
        recordDao.submitAnswer(
            AttemptRecord(
                bankId = bankId,
                questionId = question.id,
                mode = mode,
                userAnswer = userAnswer,
                correct = correct,
                selfRated = selfRated,
                runId = runId,
                durationMs = durationMs
            ),
            existing
        )
    }

    /** 测试模式报告数据 */
    suspend fun examReport(runId: String): ExamReport? {
        val records = recordDao.getByRun(runId)
        if (records.isEmpty()) return null
        val questions = bankRepo.getQuestionsByIds(records.map { it.questionId }).associateBy { it.id }
        val details = records.mapNotNull { r ->
            questions[r.questionId]?.let { q ->
                ExamItem(
                    question = q,
                    userAnswer = r.userAnswer,
                    correct = r.correct,
                    durationMs = r.durationMs
                )
            }
        }
        val totalDuration = records.sumOf { it.durationMs ?: 0L }
        val chapterStats = details.groupBy { it.question.chapter ?: "未分章" }.map { (chapter, items) ->
            ChapterStat(chapter, items.size, items.count { it.correct })
        }
        return ExamReport(
            runId = runId,
            bankId = records.first().bankId,
            total = details.size,
            correctCount = details.count { it.correct },
            totalDurationMs = totalDuration,
            chapterStats = chapterStats.sortedByDescending { it.total },
            items = details
        )
    }

    data class ExamItem(
        val question: Question,
        val userAnswer: String,
        val correct: Boolean,
        val durationMs: Long?
    )

    data class ChapterStat(val chapter: String, val total: Int, val correct: Int)

    data class ExamReport(
        val runId: String,
        val bankId: Long,
        val total: Int,
        val correctCount: Int,
        val totalDurationMs: Long,
        val chapterStats: List<ChapterStat>,
        val items: List<ExamItem>
    )

    /** 前进到下一题；随机巩固推进 cursor，其余推进 index */
    suspend fun advance(bankId: Long, mode: StudyMode) {
        val p = progressDao.get(bankId, mode) ?: return
        progressDao.upsert(
            when (mode) {
                StudyMode.RANDOM -> p.copy(randomCursor = p.randomCursor + 1, updatedAt = System.currentTimeMillis())
                else -> p.copy(currentIndex = p.currentIndex + 1, updatedAt = System.currentTimeMillis())
            }
        )
    }

    /** 随机巩固重置（重新乱序） */
    suspend fun resetRandom(bankId: Long, questionIds: List<Long>) {        progressDao.upsert(
            StudyProgress(
                bankId = bankId,
                mode = StudyMode.RANDOM,
                randomOrderJson = JsonCodec.encodeIdList(questionIds.shuffled()),
                randomCursor = 0
            )
        )
    }

    /** 顺序类模式跳回上一题/指定题 */
    suspend fun seek(bankId: Long, mode: StudyMode, index: Int) {
        val p = progressDao.get(bankId, mode) ?: return
        progressDao.upsert(p.copy(currentIndex = index.coerceAtLeast(0), updatedAt = System.currentTimeMillis()))
    }

    /** 作答即刷新该模式的最近活动时间，保证「上次进度」的时间戳真实可信 */
    suspend fun touchProgress(bankId: Long, mode: StudyMode) {
        progressDao.touch(bankId, mode, System.currentTimeMillis())
    }

    /** 清掉某模式进度（「重新开始」用） */
    suspend fun resetProgress(bankId: Long, mode: StudyMode) {
        progressDao.upsert(
            StudyProgress(bankId = bankId, mode = mode, currentIndex = 0, randomCursor = 0)
        )
    }

    /** 一条可恢复的学习进度，「回到上次进度」列表用 */
    data class SavedProgress(
        val mode: StudyMode,
        /** 当前第几题（1-based） */
        val position: Int,
        val total: Int,
        /** 所在章节（无章节时为空） */
        val chapter: String?,
        val updatedAt: Long,
        val filterJson: String?
    ) {
        val hasRealProgress: Boolean get() = position > 1 || total > 0
    }

    /**
     * 该题库下所有"做了一半"的模式进度，按最近活动倒序。
     * 只有真的往前走过（position > 1）才算可恢复，避免刚进入就提示。
     */
    suspend fun savedProgress(bankId: Long, filterJson: String? = null): List<SavedProgress> {
        val rows = progressDao.allByBank(bankId)
        if (rows.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val questions = filteredQuestions(bankId, com.examhelper.shua.data.model.StudyFilter.fromFingerprint(row.filterJson))
            val total = if (row.mode == StudyMode.RANDOM) {
                com.examhelper.shua.data.model.JsonCodec.decodeIdList(row.randomOrderJson).size
            } else {
                questions.size
            }
            val pos = if (row.mode == StudyMode.RANDOM) row.randomCursor + 1 else row.currentIndex + 1
            if (pos <= 1) return@mapNotNull null
            val chapter = questions.getOrNull(pos - 1)?.chapter?.takeIf { it.isNotBlank() }
            SavedProgress(
                mode = row.mode,
                position = pos.coerceAtMost(total.coerceAtLeast(pos)),
                total = total,
                chapter = chapter,
                updatedAt = row.updatedAt,
                filterJson = row.filterJson
            )
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun toggleFavorite(question: Question): Boolean {
        val existing = recordDao.getFavorite(question.id)
        return if (existing != null) {
            recordDao.removeFavorite(question.id)
            false
        } else {
            recordDao.addFavorite(FavoriteRecord(questionId = question.id, bankId = question.bankId))
            true
        }
    }

    suspend fun isFavorite(questionId: Long): Boolean = recordDao.getFavorite(questionId) != null

    /** 该题最近一次作答（用于「划回已做题不重复作答」） */
    suspend fun latestAttempt(questionId: Long) = recordDao.latestAttempt(questionId)

    /** 深度解题：把详细解析写回题目，并标记 deepSolved */
    suspend fun applyDeepSolve(question: Question, analysis: String): Question {
        val updated = question.copy(analysis = analysis, deepSolved = true)
        bankRepo.updateQuestion(updated)
        return updated
    }

    suspend fun markMastered(questionId: Long) = recordDao.markMastered(questionId)

    suspend fun removeWrong(questionId: Long) = recordDao.removeWrong(questionId)

    /** 按筛选条件过滤题库题目 */
    suspend fun filteredQuestions(bankId: Long, filter: com.examhelper.shua.data.model.StudyFilter?): List<Question> {
        val all = bankRepo.getQuestions(bankId)
        if (filter == null || filter.isDefault) return all
        val wrong = recordDao.wrongIds().toSet()
        val favorite = recordDao.favoriteIds().toSet()
        val killed = recordDao.killedIds().toSet()
        val answered = recordDao.answeredIds(bankId).toSet()
        return all.filter { q ->
            (filter.chapters.isEmpty() || filter.chapters.contains(q.chapter)) &&
                (filter.types.isEmpty() || filter.types.contains(q.type.name)) &&
                (filter.subtypes.isEmpty() || filter.subtypes.contains(q.subtype)) &&
                when (filter.status) {
                    "UNDONE" -> q.id !in answered
                    "WRONG" -> q.id in wrong
                    "FAVORITE" -> q.id in favorite
                    "KILLED" -> q.id in killed
                    "UNKILLED" -> q.id !in killed
                    else -> true
                }
        }
    }

    // ---- 斩题 ----

    suspend fun toggleKill(question: Question): Boolean {
        val existing = recordDao.getKill(question.id)
        return if (existing != null) {
            recordDao.removeKill(question.id)
            false
        } else {
            recordDao.addKill(com.examhelper.shua.data.db.KillRecord(questionId = question.id, bankId = question.bankId))
            true
        }
    }

    suspend fun isKilled(questionId: Long): Boolean = recordDao.getKill(questionId) != null

    fun observeKills() = recordDao.observeKills()

    suspend fun reviveKill(questionId: Long) = recordDao.removeKill(questionId)

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        /**
         * 判分：字母匹配优先，选项内容匹配兜底。
         * 兜底覆盖两类真实场景：
         * 1) OCR 字母错位/重排后，答案字母与选项字母对不上，但内容是对的
         * 2) 答案存的是"字母+内容"或纯内容（AI 生成常见），按内容对齐到选项
         */
        fun judgeAnswer(question: Question, userAnswer: String): Boolean {
            val expectedRaw = question.answer?.trim()?.takeIf { it.isNotBlank() } ?: return false
            val options = JsonCodec.decodeOptions(question.optionsJson)

            val expected = resolveToLetters(expectedRaw, options) ?: expectedRaw.uppercase()
            val actual = resolveToLetters(userAnswer.trim(), options) ?: userAnswer.trim().uppercase()
            if (expected == actual) return true

            val isLetterAnswer = expected.length in 1..5 && expected.all { it in 'A'..'E' }
            val isLetterActual = actual.length in 1..5 && actual.all { it in 'A'..'E' }
            return if (isLetterAnswer && isLetterActual) {
                // 字母不同：比较各自指向的选项内容
                val expTexts = options.filter { expected.contains(it.key) }.map { normalizeText(it.text) }
                val actTexts = options.filter { actual.contains(it.key) }.map { normalizeText(it.text) }
                expTexts.isNotEmpty() && actTexts.isNotEmpty() &&
                    expTexts.sorted() == actTexts.sorted()
            } else {
                normalizeText(expected) == normalizeText(actual)
            }
        }

        /** "B"、"b"、"B. 内容"、"内容恰好等于某选项" → 选项字母；解析不了返回 null */
        private fun resolveToLetters(raw: String, options: List<com.examhelper.shua.data.model.QuestionOption>): String? {
            val trimmed = raw.trim()
            if (trimmed.length in 1..5 && trimmed.all { it.uppercaseChar() in 'A'..'E' }) {
                return trimmed.uppercase()
            }
            val m = Regex("""^\(?([A-Ea-e]{1,5})\)?\s*[、.．,，:：)\s]?(.*)$""").find(trimmed)
            if (m != null && m.groupValues[1].length in 1..5) {
                return m.groupValues[1].uppercase()
            }
            val hit = options.firstOrNull { normalizeText(it.text) == normalizeText(trimmed) }
            return hit?.key
        }

        fun normalizeText(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '，', ',', '、', '。', '.', '．', '；', ';', '：', ':', '（', '(', '）', ')',
                    ' ', '　', '\t', '\n', '\r' -> {}
                    else -> append(c.lowercaseChar())
                }
            }
        }
    }
}
