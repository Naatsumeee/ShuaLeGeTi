package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ChatTurn
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.repo.StudyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class QuestionChatPersistenceTest {
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
    fun chatRoundTripsWithStableIdentity() {
        val turns = listOf(
            ChatTurn(role = "user", text = "问", id = "u1", createdAt = 1L),
            ChatTurn(role = "assistant", text = "答", id = "a1", createdAt = 2L)
        )
        assertEquals(turns, JsonCodec.decodeChat(JsonCodec.encodeChat(turns)))
    }

    @Test
    fun chatCanBeSavedPerQuestionAndCleared() = runBlocking {
        val bankId = db.bankDao().insert(QuestionBank(name = "测试题库"))
        val questionId = db.questionDao().insertAll(
            listOf(
                Question(
                    bankId = bankId,
                    orderInBank = 0,
                    type = QuestionType.SINGLE,
                    stem = "题干"
                )
            )
        ).single()
        val turns = listOf(
            ChatTurn("user", "问", "u1", 1L),
            ChatTurn("assistant", "完整回答", "a1", 2L)
        )

        repository.saveChat(questionId, turns)
        val saved = db.questionDao().getById(questionId)!!
        assertEquals(turns, repository.loadChat(saved))

        repository.saveChat(questionId, emptyList())
        val cleared = db.questionDao().getById(questionId)!!
        assertTrue(repository.loadChat(cleared).isEmpty())
    }
}
