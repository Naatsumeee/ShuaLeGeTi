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
    private val recordDao: RecordDao = db.recordDao()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeWrongBook() = recordDao.observeWrongBook()
    fun observeMasteredWrong() = recordDao.observeMasteredWrong()
    fun observeFavorites() = recordDao.observeFavorites()
    fun observeTodayStats() = recordDao.observeAttemptCountSince(startOfToday())
    fun observeTotalStats() = recordDao.observeTotalAttempts()

    /** 确保某模式进度存在；随机巩固首次进入时生成乱序队列 */
    suspend fun ensureProgress(bankId: Long, mode: StudyMode, questionIds: List<Long>): StudyProgress {
        progressDao.get(bankId, mode)?.let {
            if (!(mode == StudyMode.RANDOM && it.randomOrderJson == null)) return it
        }
        val fresh = if (mode == StudyMode.RANDOM) {
            StudyProgress(bankId = bankId, mode = mode, randomOrderJson = JsonCodec.encodeIdList(questionIds.shuffled()))
        } else {
            StudyProgress(bankId = bankId, mode = mode)
        }
        progressDao.upsert(fresh)
        return fresh
    }

    /** 取当前会话状态 */
    suspend fun currentSession(bankId: Long, mode: StudyMode, questions: List<Question>): StudySession {
        val progress = ensureProgress(bankId, mode, questions.map { it.id })
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
    fun judge(question: Question, userAnswer: String): Boolean {
        val expected = question.answer?.trim()?.uppercase() ?: return false
        val actual = userAnswer.trim().uppercase()
        return when (question.type) {
            com.examhelper.shua.data.db.QuestionType.SINGLE,
            com.examhelper.shua.data.db.QuestionType.JUDGE -> expected == actual
            com.examhelper.shua.data.db.QuestionType.MULTIPLE ->
                expected.toCharArray().sorted() == actual.toCharArray().sorted()
            else -> expected == actual
        }
    }

    suspend fun submitAnswer(
        bankId: Long,
        question: Question,
        mode: StudyMode,
        userAnswer: String,
        correct: Boolean,
        selfRated: Boolean = false
    ) {
        val existing = recordDao.getWrong(question.id)
        recordDao.submitAnswer(
            AttemptRecord(
                bankId = bankId,
                questionId = question.id,
                mode = mode,
                userAnswer = userAnswer,
                correct = correct,
                selfRated = selfRated
            ),
            existing
        )
    }

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
    suspend fun resetRandom(bankId: Long, questionIds: List<Long>) {
        progressDao.upsert(
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

    suspend fun markMastered(questionId: Long) = recordDao.markMastered(questionId)

    suspend fun removeWrong(questionId: Long) = recordDao.removeWrong(questionId)

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
