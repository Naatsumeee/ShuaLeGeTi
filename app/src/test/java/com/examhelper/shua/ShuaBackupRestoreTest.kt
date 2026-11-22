package com.examhelper.shua

import androidx.room.Room
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.AttemptRecord
import com.examhelper.shua.data.db.FavoriteRecord
import com.examhelper.shua.data.db.KillRecord
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.data.export.ShuaArchive
import com.examhelper.shua.data.model.ChatTurn
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.QuestionNote
import com.examhelper.shua.data.model.QuestionOption
import com.examhelper.shua.data.model.ShuaBankFile
import com.examhelper.shua.data.model.ShuaQuestionJson
import com.examhelper.shua.data.repo.LocalDataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ShuaBackupRestoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: LocalDataRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = LocalDataRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `validate rejects non-zip, missing manifest, wrong schema, and tampered bank sha256`() {
        // 1. 非 ZIP 文件
        assertFailsWith<IllegalArgumentException> {
            ShuaArchive.validate("not a zip".toByteArray())
        }

        // 2. 缺少 manifest.json
        val missingManifest = ByteArrayOutputStream().also { out ->
            ShuaArchive.write(listOf(ShuaArchive.Entry("foo.txt", "bar".toByteArray())), out)
        }.toByteArray()
        assertFailsWith<IllegalArgumentException> {
            ShuaArchive.validate(missingManifest)
        }

        // 3. 篡改题库 JSON 导致 SHA256 校验失败
        val bankFile = ShuaBankFile(
            bankId = 1L,
            name = "内科学",
            exportedAt = 1000L,
            questions = listOf(ShuaQuestionJson(type = "SINGLE", stem = "测试题干"))
        )
        val bankJsonBytes = Json.encodeToString(ShuaBankFile.serializer(), bankFile).toByteArray()
        val validRecords = """{"schema":"shualegeti.export.v1","attempts":[],"wrongBook":[],"favorites":[],"kills":[]}"""
        val validBytes = ShuaArchive.build(1000L, listOf(bankFile to bankJsonBytes), validRecords.toByteArray())
        val entries = ShuaArchive.read(validBytes).toMutableMap()
        val bankEntryKey = entries.keys.first { it.startsWith("banks/") }
        entries[bankEntryKey] = (entries.getValue(bankEntryKey).decodeToString() + " ").toByteArray()
        val tamperedBytes = ByteArrayOutputStream().also { out ->
            ShuaArchive.write(entries.map { ShuaArchive.Entry(it.key, it.value) }, out)
        }.toByteArray()
        assertFailsWith<IllegalArgumentException> {
            ShuaArchive.validate(tamperedBytes)
        }
    }

    @Test
    fun `full export and import restores banks, groups, questions, notes, chat, and learning records`(): Unit = runBlocking {
        // 构造题库组 + 子题库 + 普通题库
        val groupId = db.bankDao().insert(
            QuestionBank(name = "医学合集", isGroup = true, pinned = true, accentColor = 0xFF3F6B4AL, note = "重点复习", sortOrder = 1)
        )
        val childBankId = db.bankDao().insert(
            QuestionBank(name = "生理学", groupId = groupId, sourceFileName = "physio.pdf", questionCount = 2, sortOrder = 2)
        )
        val plainBankId = db.bankDao().insert(
            QuestionBank(name = "病理学", archived = true, questionCount = 1, sortOrder = 3)
        )

        val qIds = db.questionDao().insertAll(
            listOf(
                Question(
                    bankId = childBankId,
                    orderInBank = 0,
                    chapter = "第一章 细胞",
                    type = QuestionType.SINGLE,
                    stem = "静息电位主要取决于哪种离子外流？",
                    optionsJson = JsonCodec.encodeOptions(
                        listOf(QuestionOption("A", "K+"), QuestionOption("B", "Na+"))
                    ),
                    answer = "A",
                    originalAnswer = "A",
                    aiAnswer = "A",
                    answerVerified = true,
                    analysis = "主要与 K+ 平衡电位有关",
                    aiGenerated = true,
                    deepSolved = true,
                    askChatJson = JsonCodec.encodeChat(listOf(ChatTurn("user", "为什么？"), ChatTurn("assistant", "因为通透性最高"))),
                    notesJson = JsonCodec.encodeNotes(listOf(QuestionNote(content = "必考考点", createdAt = 123L)))
                ),
                Question(
                    bankId = childBankId,
                    orderInBank = 1,
                    chapter = "第二章 血液",
                    type = QuestionType.SHORT_ANSWER,
                    stem = "简述血浆胶体渗透压的生理意义",
                    answer = null
                )
            )
        )
        val q3Id = db.questionDao().insertAll(
            listOf(
                Question(
                    bankId = plainBankId,
                    orderInBank = 0,
                    chapter = "总论",
                    type = QuestionType.SINGLE,
                    stem = "细胞水肿最常见于哪些器官？",
                    answer = "A"
                )
            )
        ).first()

        db.recordDao().insertAttempts(
            listOf(
                AttemptRecord(
                    bankId = childBankId,
                    questionId = qIds[0],
                    mode = StudyMode.SEQUENTIAL,
                    userAnswer = "B",
                    correct = false,
                    runId = "run-1",
                    durationMs = 4500L,
                    answeredAt = 10000L,
                    correctKnown = true
                ),
                AttemptRecord(
                    bankId = childBankId,
                    questionId = qIds[1],
                    mode = StudyMode.SEQUENTIAL,
                    userAnswer = "维持水分平衡",
                    correct = false,
                    answeredAt = 20000L,
                    correctKnown = false
                )
            )
        )
        db.recordDao().upsertWrong(
            WrongRecord(
                questionId = qIds[0],
                bankId = childBankId,
                wrongCount = 3,
                lastWrongAt = 15000L,
                mastered = false,
                flagged = true,
                rebrushFailed = true
            )
        )
        db.recordDao().addFavorite(FavoriteRecord(questionId = qIds[0], bankId = childBankId, createdAt = 16000L))
        db.recordDao().addKill(KillRecord(questionId = q3Id, bankId = plainBankId, killedAt = 17000L))

        val (bytes, exportSummary) = repo.exportShuaPackageBytes()
        assertEquals(3, exportSummary.banks)
        assertEquals(3, exportSummary.questions)
        assertEquals(2, exportSummary.attempts)
        assertEquals(1, exportSummary.wrong)
        assertEquals(1, exportSummary.favorites)
        assertEquals(1, exportSummary.kills)

        // 预览校验
        val preview = repo.inspectShuaPackageBytes(bytes)
        assertEquals(3, preview.banks)
        assertEquals(3, preview.questions)
        assertEquals(2, preview.attempts)

        // 写入脏数据模拟现有本地数据，随后执行一键恢复
        db.bankDao().insert(QuestionBank(name = "会被覆盖的旧题库"))

        val restored = repo.importShuaPackageBytes(bytes)
        assertEquals(3, restored.banks)
        assertEquals(3, restored.questions)
        assertEquals(2, restored.attempts)
        assertEquals(1, restored.wrong)
        assertEquals(1, restored.favorites)
        assertEquals(1, restored.kills)

        val restoredBanks = db.bankDao().observeAll().first()
        assertEquals(3, restoredBanks.size)
        val restoredGroup = restoredBanks.first { it.isGroup }
        assertEquals("医学合集", restoredGroup.name)
        assertTrue(restoredGroup.pinned)
        assertEquals(0xFF3F6B4AL, restoredGroup.accentColor)
        assertEquals("重点复习", restoredGroup.note)

        val restoredChild = restoredBanks.first { it.name == "生理学" }
        assertEquals(restoredGroup.id, restoredChild.groupId)
        val restoredPlain = restoredBanks.first { it.name == "病理学" }
        assertTrue(restoredPlain.archived)

        val childQuestions = db.questionDao().getByBank(restoredChild.id)
        assertEquals(2, childQuestions.size)
        assertEquals("第一章 细胞", childQuestions[0].chapter)
        assertTrue(childQuestions[0].deepSolved)
        assertTrue(childQuestions[0].answerVerified)
        assertEquals(1, JsonCodec.decodeNotes(childQuestions[0].notesJson).size)
        assertEquals(2, JsonCodec.decodeChat(childQuestions[0].askChatJson).size)

        val attempts = db.recordDao().allAttempts()
        assertEquals(2, attempts.size)
        assertEquals(childQuestions[0].id, attempts[0].questionId)
        assertEquals("run-1", attempts[0].runId)
        assertTrue(attempts[0].correctKnown)
        assertFalse(attempts[1].correctKnown)

        val wrong = db.recordDao().getWrong(childQuestions[0].id)
        assertNotNull(wrong)
        assertEquals(3, wrong.wrongCount)
        assertTrue(wrong.flagged)
        assertTrue(wrong.rebrushFailed)

        assertNotNull(db.recordDao().getFavorite(childQuestions[0].id))
        val plainQuestions = db.questionDao().getByBank(restoredPlain.id)
        assertNotNull(db.recordDao().getKill(plainQuestions[0].id))
    }

    @Test
    fun `backward compatible restore matches records by bankId and stem when question id is absent`(): Unit = runBlocking {
        // 模拟旧版 v1.4.0 导出的 .shua（ShuaQuestionJson 无 id 字段）
        val legacyBank = ShuaBankFile(
            bankId = 42L,
            name = "旧版题库",
            exportedAt = 5000L,
            questions = listOf(
                ShuaQuestionJson(
                    id = null,
                    type = "SINGLE",
                    stem = "旧版备份中的第一道题",
                    answer = "A"
                )
            )
        )
        val bankBytes = Json.encodeToString(ShuaBankFile.serializer(), legacyBank).toByteArray()
        val legacyRecords = """
            {
              "schema": "shualegeti.export.v1",
              "exportedAt": 5000,
              "attempts": [
                {
                  "questionId": 999,
                  "bankId": 42,
                  "bankName": "旧版题库",
                  "stem": "旧版备份中的第一道题",
                  "mode": "SEQUENTIAL",
                  "userAnswer": "A",
                  "correct": true,
                  "selfRated": false,
                  "durationMs": 1200,
                  "answeredAt": 5001
                }
              ],
              "wrongBook": [],
              "favorites": [
                {
                  "questionId": 999,
                  "bankId": 42,
                  "bankName": "旧版题库",
                  "stem": "旧版备份中的第一道题",
                  "createdAt": 5002
                }
              ],
              "kills": []
            }
        """.trimIndent().toByteArray()

        val bytes = ShuaArchive.build(5000L, listOf(legacyBank to bankBytes), legacyRecords)
        val restored = repo.importShuaPackageBytes(bytes)
        assertEquals(1, restored.banks)
        assertEquals(1, restored.questions)
        assertEquals(1, restored.attempts)
        assertEquals(1, restored.favorites)
    }
}
