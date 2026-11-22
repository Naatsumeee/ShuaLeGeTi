package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.StudyFilter
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.LocalDataRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.ui.stats.StatsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GroupSplitAndStatsTest {

    private lateinit var db: AppDatabase
    private lateinit var bankRepo: BankRepository
    private lateinit var studyRepo: StudyRepository
    private lateinit var localRepo: LocalDataRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        bankRepo = BankRepository(db)
        studyRepo = StudyRepository(db)
        localRepo = LocalDataRepository(db, bankRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updateGroupMembership splits selected banks out and dissolves empty group`(): Unit = runBlocking {
        val b1 = bankRepo.createBank("生理", null, emptyList())
        val b2 = bankRepo.createBank("病理", null, emptyList())
        db.questionDao().insertAll(
            listOf(
                Question(bankId = b1, orderInBank = 0, type = QuestionType.SINGLE, stem = "Q1"),
                Question(bankId = b2, orderInBank = 0, type = QuestionType.SINGLE, stem = "Q2")
            )
        )
        val groupId = bankRepo.createGroup("医学组", null, null, listOf(b1, b2))
        assertEquals(2, bankRepo.getBank(groupId)?.questionCount)

        // 拆出 b2，只保留 b1
        bankRepo.updateGroupMembership(groupId, "医学组2", "备注", null, listOf(b1))
        assertNull(bankRepo.getBank(b2)?.groupId)
        assertEquals(groupId, bankRepo.getBank(b1)?.groupId)
        assertEquals(1, bankRepo.getBank(groupId)?.questionCount)
        assertEquals("医学组2", bankRepo.getBank(groupId)?.name)

        // 全部拆出后自动解散空题库组
        bankRepo.updateGroupMembership(groupId, "医学组2", null, null, emptyList())
        assertNull(bankRepo.getBank(groupId))
        assertNull(bankRepo.getBank(b1)?.groupId)
    }

    @Test
    fun `daily duration accumulates, formats in minutes, and round-trips via shua archive`(): Unit = runBlocking {
        val day = "2026-11-22"
        db.recordDao().addStudyDuration(day, 45_000L, 1000L)
        db.recordDao().addStudyDuration(day, 90_000L, 2000L)
        assertEquals(135_000L, db.recordDao().observeDayDurationMs(day).first())
        assertEquals("0", StatsViewModel.formatDurationMinutes(0L))
        assertEquals("1", StatsViewModel.formatDurationMinutes(10_000L))
        assertEquals("2", StatsViewModel.formatDurationMinutes(135_000L))

        val (bytes, _) = localRepo.exportShuaPackageBytes()
        db.recordDao().deleteAllDailyDurations()
        assertEquals(0L, db.recordDao().observeTotalDurationMs().first())

        localRepo.importShuaPackageBytes(bytes)
        assertEquals(135_000L, db.recordDao().observeDayDurationMs(day).first())
        assertEquals(135_000L, db.recordDao().observeTotalDurationMs().first())
    }

    @Test
    fun `filteredQuestions with questionIds fetches cross-bank wrong questions in order`(): Unit = runBlocking {
        val b1 = db.bankDao().insert(QuestionBank(name = "库A"))
        val b2 = db.bankDao().insert(QuestionBank(name = "库B"))
        val q1 = db.questionDao().insertAll(listOf(Question(bankId = b1, orderInBank = 0, type = QuestionType.SINGLE, stem = "A1"))).first()
        val q2 = db.questionDao().insertAll(listOf(Question(bankId = b2, orderInBank = 0, type = QuestionType.SINGLE, stem = "B1"))).first()

        val list = studyRepo.filteredQuestions(
            bankId = 0L,
            filter = StudyFilter(questionIds = listOf(q2, q1), freshAnswer = true)
        )
        assertEquals(listOf(q2, q1), list.map { it.id })
        assertTrue(list.size == 2)
    }
}
