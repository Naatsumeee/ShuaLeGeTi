package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AttemptRecord
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.StudyProgress
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.data.repo.latestAnswerMap
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class StudyAnswerRestoreTest {

    @Test
    fun latestAttemptPerQuestionUsesTimeThenId() {
        val answers = listOf(
            AttemptRecord(
                id = 1,
                questionId = 7,
                bankId = 1,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "A",
                correct = false,
                runId = "r",
                answeredAt = 10
            ),
            AttemptRecord(
                id = 2,
                questionId = 7,
                bankId = 1,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "B",
                correct = true,
                runId = "r",
                answeredAt = 10
            ),
            AttemptRecord(
                id = 3,
                questionId = 7,
                bankId = 1,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "C",
                correct = false,
                runId = "r",
                answeredAt = 9
            )
        )

        val latest = latestAnswerMap(answers)[7]

        assertEquals("B", latest?.userAnswer)
        assertEquals(2L, latest?.attemptId)
    }

    @Test
    fun answerWithoutReferenceKeepsUnknownResult() {
        val answer = latestAnswerMap(
            listOf(
                AttemptRecord(
                    id = 3,
                    questionId = 8,
                    bankId = 1,
                    mode = StudyMode.SEQUENTIAL,
                    userAnswer = "C",
                    correct = false,
                    correctKnown = false,
                    runId = "r",
                    answeredAt = 20
                )
            )
        )[8]

        assertEquals("C", answer?.userAnswer)
        assertNull(answer?.correct)
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: StudyRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = StudyRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun existingProgressWithoutRoundIdGetsStableRoundId() = runBlocking {
        db.progressDao().upsert(
            StudyProgress(
                bankId = 1,
                mode = StudyMode.SEQUENTIAL,
                currentIndex = 4,
                filterJson = "filter-a",
                updatedAt = 123,
                roundId = null
            )
        )

        val upgraded = repository.ensureProgress(
            bankId = 1,
            mode = StudyMode.SEQUENTIAL,
            questionIds = listOf(10, 11),
            filterJson = "filter-a"
        )
        val reopened = repository.ensureProgress(
            bankId = 1,
            mode = StudyMode.SEQUENTIAL,
            questionIds = listOf(10, 11),
            filterJson = "filter-a"
        )

        val persisted = db.progressDao().get(1, StudyMode.SEQUENTIAL)
        assertNotNull(upgraded.roundId)
        assertEquals(upgraded.roundId, reopened.roundId)
        assertEquals(upgraded.roundId, persisted?.roundId)
        assertEquals(4, upgraded.currentIndex)
        assertEquals("filter-a", upgraded.filterJson)
    }

    @Test
    fun newProgressGetsStableRoundId() = runBlocking {
        val created = repository.ensureProgress(
            bankId = 2,
            mode = StudyMode.SPEED,
            questionIds = listOf(20, 21)
        )
        val reopened = repository.ensureProgress(
            bankId = 2,
            mode = StudyMode.SPEED,
            questionIds = listOf(20, 21)
        )

        assertNotNull(created.roundId)
        assertEquals(created.roundId, reopened.roundId)
    }

    @Test
    fun currentSessionExposesRoundIdForSequentialAndRandomModes() = runBlocking {
        db.progressDao().upsert(
            StudyProgress(
                bankId = 3,
                mode = StudyMode.SEQUENTIAL,
                roundId = "round-sequential"
            )
        )
        db.progressDao().upsert(
            StudyProgress(
                bankId = 3,
                mode = StudyMode.RANDOM,
                randomOrderJson = "[1]",
                roundId = "round-random"
            )
        )

        val sequential = repository.currentSession(
            bankId = 3,
            mode = StudyMode.SEQUENTIAL,
            questions = emptyList()
        )
        val random = repository.currentSession(
            bankId = 3,
            mode = StudyMode.RANDOM,
            questions = emptyList()
        )

        assertEquals("round-sequential", sequential.roundId)
        assertEquals("round-random", random.roundId)
    }

    @Test
    fun recordStatsIgnoreUnknownCorrectness() = runBlocking {
        val timestamp = System.currentTimeMillis()
        db.recordDao().insertAttempt(
            AttemptRecord(
                bankId = 4,
                questionId = 40,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "A",
                correct = true,
                answeredAt = timestamp
            )
        )
        db.recordDao().insertAttempt(
            AttemptRecord(
                bankId = 4,
                questionId = 41,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "B",
                correct = false,
                answeredAt = timestamp
            )
        )
        db.recordDao().insertAttempt(
            AttemptRecord(
                bankId = 4,
                questionId = 42,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "C",
                correct = true,
                correctKnown = false,
                answeredAt = timestamp
            )
        )

        val bankStat = db.recordDao().observeBankStats().first().single()
        assertEquals(2, bankStat.cnt)
        assertEquals(1, bankStat.ok)
        assertEquals(1, db.recordDao().observeCorrectCountSince(timestamp).first())
        assertEquals(1, db.recordDao().observeTotalCorrect().first())
    }

    @Test
    fun unknownCorrectnessLeavesWrongBookUnchanged() = runBlocking {
        val existingWrong = WrongRecord(
            questionId = 50,
            bankId = 5,
            wrongCount = 2,
            lastWrongAt = 100,
            flagged = true
        )
        db.recordDao().upsertWrong(existingWrong)

        db.recordDao().submitAnswer(
            AttemptRecord(
                bankId = 5,
                questionId = 50,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "A",
                correct = false,
                correctKnown = false,
                runId = "unknown-round",
                answeredAt = 200
            ),
            existingWrong
        )
        db.recordDao().submitAnswer(
            AttemptRecord(
                bankId = 5,
                questionId = 51,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "B",
                correct = false,
                correctKnown = false,
                runId = "unknown-round",
                answeredAt = 201
            ),
            null
        )

        assertEquals(existingWrong, db.recordDao().getWrong(50))
        assertNull(db.recordDao().getWrong(51))
        assertEquals(2, db.recordDao().getByRun("unknown-round").size)
    }
}
