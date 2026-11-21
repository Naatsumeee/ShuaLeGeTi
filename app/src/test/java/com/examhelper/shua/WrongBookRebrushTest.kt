package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WrongBookRebrushTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StudyRepository
    private var bankId: Long = 0
    private lateinit var q1: Question
    private lateinit var q2: Question

    @Before
    fun setUp() {
        runBlocking {
            db = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                AppDatabase::class.java
            ).allowMainThreadQueries().build()
            repository = StudyRepository(db)
            bankId = db.bankDao().insert(QuestionBank(name = "错题测试库"))
            val ids = db.questionDao().insertAll(
                listOf(
                    Question(
                        bankId = bankId,
                        orderInBank = 0,
                        chapter = "第一章",
                        type = QuestionType.SINGLE,
                        stem = "题1",
                        answer = "A"
                    ),
                    Question(
                        bankId = bankId,
                        orderInBank = 1,
                        chapter = "第一章",
                        type = QuestionType.SINGLE,
                        stem = "题2",
                        answer = "B"
                    )
                )
            )
            val all = db.questionDao().getByIds(ids).associateBy { it.id }
            q1 = all.getValue(ids[0])
            q2 = all.getValue(ids[1])
            db.recordDao().upsertWrong(WrongRecord(questionId = q1.id, bankId = bankId))
            db.recordDao().upsertWrong(WrongRecord(questionId = q2.id, bankId = bankId))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun wrongRebrushKeepsQueueLockedUntilRoundFinishes() = runBlocking {
        val filter = StudyFilter(status = "WRONG", freshAnswer = true)
        val fp = filter.fingerprint()
        val initialQuestions = repository.filteredQuestions(bankId, filter)
        assertEquals(2, initialQuestions.size)

        val session1 = repository.currentSession(bankId, StudyMode.SEQUENTIAL, initialQuestions, fp)
        assertEquals(q1.id, session1.question?.id)
        assertEquals(2, session1.total)

        // 答对第一题：延迟标为已掌握，不得从队列移除
        repository.submitAnswer(
            question = q1,
            mode = StudyMode.SEQUENTIAL,
            userAnswer = "A",
            correct = true,
            runId = session1.roundId,
            deferMastered = true,
            isRebrush = true
        )
        assertFalse(db.recordDao().getWrong(q1.id)!!.mastered)

        repository.advance(bankId, StudyMode.SEQUENTIAL)
        val afterFirst = repository.filteredQuestions(bankId, filter)
        val session2 = repository.currentSession(bankId, StudyMode.SEQUENTIAL, afterFirst, fp)
        assertEquals(q2.id, session2.question?.id)
        assertEquals(2, session2.total)

        // 第二题答错：打上重刷未掌握标记
        repository.submitAnswer(
            question = q2,
            mode = StudyMode.SEQUENTIAL,
            userAnswer = "A",
            correct = false,
            runId = session2.roundId,
            deferMastered = true,
            isRebrush = true
        )
        assertTrue(db.recordDao().getWrong(q2.id)!!.rebrushFailed)

        // 本轮结束后统一标注第一题为已掌握
        repository.finalizeRoundMastered(listOf(q1.id))
        assertTrue(db.recordDao().getWrong(q1.id)!!.mastered)
        assertFalse(db.recordDao().getWrong(q2.id)!!.mastered)

        // 「重刷仍未掌握」特殊章节仅包含 q2
        val rebrushFilter = StudyFilter(chapters = listOf(StudyRepository.REBRUSH_UNMASTERED_CHAPTER))
        val rebrushQs = repository.filteredQuestions(bankId, rebrushFilter)
        assertEquals(listOf(q2.id), rebrushQs.map { it.id })
    }
}
