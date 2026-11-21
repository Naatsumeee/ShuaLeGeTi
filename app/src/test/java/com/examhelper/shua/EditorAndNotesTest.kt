package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.model.BankJsonImport
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionNote
import com.examhelper.shua.data.repo.BankRepository
import com.examhelper.shua.data.repo.StudyRepository
import com.examhelper.shua.ui.editor.buildChapterBlocks
import com.examhelper.shua.ui.editor.computeInsertChapterBreakRange
import com.examhelper.shua.ui.editor.computeRemoveChapterBreakPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EditorAndNotesTest {

    private lateinit var db: AppDatabase
    private lateinit var bankRepo: BankRepository
    private lateinit var studyRepo: StudyRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        bankRepo = BankRepository(db)
        studyRepo = StudyRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleQuestions(): List<Question> = listOf(
        Question(id = 1, bankId = 1, orderInBank = 0, chapter = "第一章", type = QuestionType.SINGLE, stem = "Q1"),
        Question(id = 2, bankId = 1, orderInBank = 1, chapter = "第一章", type = QuestionType.SINGLE, stem = "Q2"),
        Question(id = 3, bankId = 1, orderInBank = 2, chapter = "第一章", type = QuestionType.SINGLE, stem = "Q3"),
        Question(id = 4, bankId = 1, orderInBank = 3, chapter = "第二章", type = QuestionType.SINGLE, stem = "Q4"),
        Question(id = 5, bankId = 1, orderInBank = 4, chapter = "第一章", type = QuestionType.SINGLE, stem = "Q5")
    )

    @Test
    fun `chapter blocks preserve contiguous runs and split or merge at boundaries only`() {
        val qs = sampleQuestions()
        val blocks = buildChapterBlocks(qs)
        assertEquals(3, blocks.size)
        assertEquals(0..2, blocks[0].startIndex..blocks[0].endIndexInclusive)
        assertEquals(3..3, blocks[1].startIndex..blocks[1].endIndexInclusive)
        assertEquals(4..4, blocks[2].startIndex..blocks[2].endIndexInclusive)

        // 在第 2 题处（下标 1）新建分章，只作用于下标 1..2，不越过下标 3 的第二章边界
        val insertRange = computeInsertChapterBreakRange(qs, atQuestionIndex = 1)
        assertEquals(1..2, insertRange)

        // 删除下标 4 的分章，合并回紧邻上一章「第二章」而不影响下标 0..2 的第一章
        val removePlan = computeRemoveChapterBreakPlan(qs, blockStartIndex = 4)
        assertEquals("第二章" to (4..4), removePlan)

        // 删除第一块的分章标记，设为未分章 null
        val removeFirst = computeRemoveChapterBreakPlan(qs, blockStartIndex = 0)
        assertNull(removeFirst?.first)
        assertEquals(0..2, removeFirst?.second)
    }

    @Test
    fun `notes encode and decode chronologically and survive bank export reimport`() = runBlocking {
        val notes = listOf(
            QuestionNote(id = "n2", content = "第二条笔记", createdAt = 2000L),
            QuestionNote(id = "n1", content = "第一条笔记", createdAt = 1000L)
        )
        val decoded = JsonCodec.decodeNotes(JsonCodec.encodeNotes(notes))
        assertEquals(listOf("n1", "n2"), decoded.map { it.id })

        val bankId = db.bankDao().insert(QuestionBank(name = "导出口令测试库"))
        db.questionDao().insertAll(
            listOf(
                Question(
                    bankId = bankId,
                    orderInBank = 0,
                    chapter = "习题", // 用户手动设置的自定义章名
                    type = QuestionType.SINGLE,
                    stem = "细胞膜结构",
                    answer = "A",
                    notesJson = JsonCodec.encodeNotes(notes)
                )
            )
        )
        val exported = bankRepo.exportBank(bankId, includeAiGenerated = false)!!
        assertEquals(listOf("n1", "n2"), exported.questions.first().notes.map { it.id })

        val reimportedBankId = bankRepo.importBankFile(exported)
        val reimportedQuestions = bankRepo.getQuestions(reimportedBankId)
        assertEquals("习题", reimportedQuestions.first().chapter)
        assertEquals(
            listOf("第一条笔记", "第二条笔记"),
            JsonCodec.decodeNotes(reimportedQuestions.first().notesJson).map { it.content }
        )

        val jsonImported = BankJsonImport.parse(
            """
            {
              "name": "外部JSON",
              "questions": [
                {
                  "stem": "测试题",
                  "type": "SINGLE",
                  "options": ["A. 1", "B. 2"],
                  "answer": "A",
                  "notes": [
                    {"id": "x2", "content": "后加", "createdAt": 300},
                    {"id": "x1", "content": "先加", "createdAt": 100}
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf("x1", "x2"), jsonImported.questions.first().notes.map { it.id })
    }

    @Test
    fun `editing answered question rejudges round record and wrong book`() = runBlocking {
        val bankId = db.bankDao().insert(QuestionBank(name = "重判测试库"))
        val qid = db.questionDao().insertAll(
            listOf(
                Question(
                    bankId = bankId,
                    orderInBank = 0,
                    type = QuestionType.SINGLE,
                    stem = "原题干",
                    answer = "B"
                )
            )
        ).first()
        val original = db.questionDao().getById(qid)!!
        val session = studyRepo.currentSession(bankId, StudyMode.SEQUENTIAL, listOf(original))

        // 用户选 A，原答案 B → 判错并入错题本
        studyRepo.submitAnswer(
            question = original,
            mode = StudyMode.SEQUENTIAL,
            userAnswer = "A",
            correct = false,
            correctKnown = true,
            runId = session.roundId
        )
        assertFalse(db.recordDao().getWrong(qid)!!.mastered)

        // 用户发现题库答案录错了，在做题页直接改答案为 A 并重新判分
        val edited = original.copy(answer = "A")
        bankRepo.updateQuestion(edited)
        studyRepo.rejudgeRoundAnswer(
            question = edited,
            runId = session.roundId,
            correct = true,
            isRebrush = false
        )
        val latest = studyRepo.latestAnswersByRound(session.roundId)[qid]
        assertEquals(true, latest?.correct)
        assertTrue(db.recordDao().getWrong(qid)!!.mastered)
    }
}
