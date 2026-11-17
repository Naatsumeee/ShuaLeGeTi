package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.StudyProgress
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StudySavedProgressFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StudyRepository
    private var bankId: Long = 0

    @Before
    fun setUp() {
        runBlocking {
            db = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                AppDatabase::class.java
            ).allowMainThreadQueries().build()
            repository = StudyRepository(db)
            bankId = db.bankDao().insert(QuestionBank(name = "测试题库"))
            db.questionDao().insertAll(
                (0..2).map { index ->
                    Question(
                        bankId = bankId,
                        orderInBank = index,
                        chapter = "第一章",
                        type = QuestionType.SINGLE,
                        stem = "题目 $index"
                    )
                }
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedProgressFlowDropsDeletedAndFinishedRounds() = runBlocking {
        db.progressDao().upsert(
            StudyProgress(
                bankId = bankId,
                mode = StudyMode.SEQUENTIAL,
                currentIndex = 1,
                roundId = "active",
                updatedAt = 1
            )
        )

        val active = repository.observeSavedProgress(bankId).first()
        assertEquals(1, active.size)
        assertEquals(2, active.single().position)

        db.progressDao().deleteOne(bankId, StudyMode.SEQUENTIAL)
        assertTrue(repository.observeSavedProgress(bankId).first().isEmpty())

        db.progressDao().upsert(
            StudyProgress(
                bankId = bankId,
                mode = StudyMode.SEQUENTIAL,
                currentIndex = 3,
                roundId = "finished",
                updatedAt = 2
            )
        )
        assertTrue(repository.observeSavedProgress(bankId).first().isEmpty())
    }
}
