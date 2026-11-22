package com.examhelper.shua.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.AttemptRecord
import com.examhelper.shua.data.db.FavoriteRecord
import com.examhelper.shua.data.db.KillRecord
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.StudyDailyDuration
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.data.db.WrongRecord
import com.examhelper.shua.data.export.ShuaArchive
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.JudgeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地学习数据管理：学习记录导出、清空收藏夹、重置刷题进度。
 * 导出格式为可读 JSON（含题库名/题干，方便用户自留或迁移）。
 */
class LocalDataRepository(
    private val db: AppDatabase,
    private val bankRepo: BankRepository = BankRepository(db)
) {
    private val recordDao = db.recordDao()
    private val bankDao = db.bankDao()
    private val questionDao = db.questionDao()
    private val progressDao = db.progressDao()

    data class ExportSummary(
        val fileName: String,
        val attempts: Int,
        val wrong: Int,
        val favorites: Int,
        val kills: Int,
        val banks: Int = 0,
        val questions: Int = 0
    )

    data class RestorePreview(
        val exportedAtText: String,
        val banks: Int,
        val questions: Int,
        val attempts: Int,
        val wrong: Int,
        val favorites: Int,
        val kills: Int
    )

    /**
     * 导出为单个 `.shua` 学习包：manifest + 每个题库一份标准 JSON + 学习记录 JSON。
     *
     * 这是「学习数据导出」和「题库导出」的整合入口。内部仍是可读 JSON，
     * 外部扩展名让它在文件管理器中看起来像一个应用文件。
     */
    suspend fun exportShuaPackage(context: Context, uri: Uri): ExportSummary =
        withContext(Dispatchers.IO) {
            val (bytes, summary) = exportShuaPackageBytes()
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: error("无法写入所选文件")
            summary
        }

    suspend fun exportShuaPackageBytes(): Pair<ByteArray, ExportSummary> =
        withContext(Dispatchers.IO) {
            val banks = bankDao.observeAll().first()
            // 题库组也作为元数据记录导出；子题库通过 groupId 指向它。
            val exported = banks.mapNotNull { b ->
                bankRepo.exportBank(b.id, includeAiGenerated = true)?.let { file ->
                    file to Json { prettyPrint = true; ignoreUnknownKeys = true }
                        .encodeToString(com.examhelper.shua.data.model.ShuaBankFile.serializer(), file)
                        .toByteArray(Charsets.UTF_8)
                }
            }
            val bankShaById = exported.mapNotNull { (file, bytes) -> file.bankId?.let { it to ShuaArchive.sha256(bytes) } }.toMap()
            val recordJson = buildLearningRecordsJson(bankShaById)
            val bytes = ShuaArchive.build(System.currentTimeMillis(), exported, recordJson.toByteArray(Charsets.UTF_8))
            val summary = ExportSummary(
                fileName = ShuaArchive.suggestedFileName(dateStamp()),
                attempts = recordDao.allAttempts().size,
                wrong = recordDao.allWrong().size,
                favorites = recordDao.allFavorites().size,
                kills = recordDao.allKills().size,
                banks = banks.size,
                questions = exported.sumOf { it.first.questions.size }
            )
            bytes to summary
        }

    /** 校验并预览 `.shua` 备份包内容（不修改本地数据库）。 */
    suspend fun inspectShuaPackage(context: Context, uri: Uri): RestorePreview =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选文件")
            inspectShuaPackageBytes(bytes)
        }

    suspend fun inspectShuaPackageBytes(bytes: ByteArray): RestorePreview =
        withContext(Dispatchers.IO) {
            val archive = ShuaArchive.validate(bytes)
            RestorePreview(
                exportedAtText = archive.exportedAtText,
                banks = archive.banks.size,
                questions = archive.questionCount,
                attempts = archive.attemptCount,
                wrong = archive.wrongCount,
                favorites = archive.favoriteCount,
                kills = archive.killCount
            )
        }

    /**
     * 从校验通过的 `.shua` 备份包一键全量恢复题库与学习数据（事务内原子替换）。
     * AI 模型配置与软件偏好设置不受影响。
     */
    suspend fun importShuaPackage(context: Context, uri: Uri): ExportSummary =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选文件")
            importShuaPackageBytes(bytes)
        }

    suspend fun importShuaPackageBytes(bytes: ByteArray): ExportSummary =
        withContext(Dispatchers.IO) {
            val archive = ShuaArchive.validate(bytes)
            val now = System.currentTimeMillis()
            db.withTransaction {
                recordDao.deleteAllAttempts()
                recordDao.deleteAllWrong()
                recordDao.clearAllFavorites()
                recordDao.deleteAllKills()
                recordDao.deleteAllDailyDurations()
                recordDao.clearAllProgress()
                questionDao.deleteAll()
                bankDao.deleteAll()

                val oldBankIdToNew = mutableMapOf<Long, Long>()
                val groupNameToNewId = mutableMapOf<String, Long>()
                val insertedBanks = mutableListOf<Pair<com.examhelper.shua.data.model.ShuaBankFile, Long>>()

                archive.banks.forEachIndexed { idx, bankFile ->
                    val newBankId = bankDao.insert(
                        QuestionBank(
                            name = bankFile.name,
                            sourceFileName = bankFile.sourceFileName,
                            questionCount = if (bankFile.isGroup) 0 else bankFile.questions.size,
                            createdAt = now,
                            updatedAt = now,
                            pinned = bankFile.pinned,
                            archived = bankFile.archived,
                            groupId = null,
                            isGroup = bankFile.isGroup,
                            accentColor = bankFile.accentColor,
                            note = bankFile.note,
                            sortOrder = if (bankFile.sortOrder != 0) bankFile.sortOrder else idx
                        )
                    )
                    bankFile.bankId?.let { oldBankIdToNew[it] = newBankId }
                    if (bankFile.isGroup) {
                        groupNameToNewId[bankFile.name] = newBankId
                    }
                    insertedBanks += bankFile to newBankId
                }

                // 第二轮：回填子题库的 groupId 关联
                insertedBanks.forEach { (bankFile, newBankId) ->
                    if (!bankFile.isGroup) {
                        val mappedGroupId = bankFile.groupId?.let { oldBankIdToNew[it] }
                            ?: bankFile.groupName?.let { groupNameToNewId[it] }
                        if (mappedGroupId != null) {
                            bankDao.setGroup(newBankId, mappedGroupId, now)
                        }
                    }
                }

                val oldQuestionIdToNew = mutableMapOf<Long, Pair<Long, Long>>()
                val fallbackByBankAndStem = mutableMapOf<Pair<Long, String>, Pair<Long, Long>>()
                val fallbackByBankNameAndStem = mutableMapOf<Pair<String, String>, Pair<Long, Long>>()
                var totalQuestions = 0

                insertedBanks.forEach { (bankFile, newBankId) ->
                    if (bankFile.isGroup || bankFile.questions.isEmpty()) return@forEach
                    val entities = bankFile.questions.mapIndexed { index, q ->
                        val type = runCatching { QuestionType.valueOf(q.type) }.getOrElse { QuestionType.SINGLE }
                        val (judgeOpts, judgeAns) = JudgeOptions.normalize(type, q.options, q.answer)
                        val finalOptions = if (type == QuestionType.JUDGE) judgeOpts else q.options
                        val finalAnswer = if (type == QuestionType.JUDGE) judgeAns else q.answer
                        Question(
                            bankId = newBankId,
                            orderInBank = index,
                            chapter = q.chapter?.trim()?.takeIf { it.isNotEmpty() },
                            type = type,
                            subtype = q.subtype,
                            stem = q.stem,
                            sharedStem = q.sharedStem,
                            sharedOptionsJson = q.sharedOptions.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
                            optionsJson = finalOptions.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
                            answer = finalAnswer,
                            originalAnswer = q.originalAnswer,
                            aiAnswer = q.aiAnswer,
                            answerVerified = q.answerVerified,
                            deepSolved = q.deepSolved,
                            minimalSolved = q.minimalSolved,
                            analysis = q.analysis,
                            aiGenerated = q.aiGenerated,
                            confidence = q.confidence,
                            agreement = q.agreement,
                            dissentJson = q.dissent.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeDissent),
                            createdAt = now,
                            askChatJson = q.askChat.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeChat),
                            notesJson = JsonCodec.encodeNotes(q.notes)
                        )
                    }
                    val newIds = questionDao.insertAll(entities)
                    totalQuestions += newIds.size
                    bankFile.questions.forEachIndexed { index, q ->
                        val newQId = newIds[index]
                        val mapped = newQId to newBankId
                        q.id?.let { oldQuestionIdToNew[it] = mapped }
                        val stemKey = q.stem.take(80)
                        bankFile.bankId?.let { oldBId ->
                            fallbackByBankAndStem.putIfAbsent(oldBId to stemKey, mapped)
                        }
                        fallbackByBankNameAndStem.putIfAbsent(bankFile.name to stemKey, mapped)
                    }
                }

                fun resolveQuestion(obj: JsonObject): Pair<Long, Long>? {
                    val oldQId = obj["questionId"]?.jsonPrimitive?.longOrNull
                    if (oldQId != null) {
                        oldQuestionIdToNew[oldQId]?.let { return it }
                    }
                    val stem = obj["stem"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80)
                    val oldBId = obj["bankId"]?.jsonPrimitive?.longOrNull
                    if (oldBId != null && stem.isNotEmpty()) {
                        fallbackByBankAndStem[oldBId to stem]?.let { return it }
                    }
                    val bankName = obj["bankName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (bankName.isNotEmpty() && stem.isNotEmpty()) {
                        fallbackByBankNameAndStem[bankName to stem]?.let { return it }
                    }
                    return null
                }

                val attemptsToInsert = archive.records["attempts"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val (qId, bId) = resolveQuestion(obj) ?: return@mapNotNull null
                    val mode = runCatching {
                        StudyMode.valueOf(obj["mode"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }.getOrDefault(StudyMode.SEQUENTIAL)
                    val duration = obj["durationMs"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                    AttemptRecord(
                        bankId = bId,
                        questionId = qId,
                        mode = mode,
                        userAnswer = obj["userAnswer"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        correct = obj["correct"]?.jsonPrimitive?.booleanOrNull ?: false,
                        selfRated = obj["selfRated"]?.jsonPrimitive?.booleanOrNull ?: false,
                        runId = obj["runId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        durationMs = duration,
                        answeredAt = obj["answeredAt"]?.jsonPrimitive?.longOrNull ?: now,
                        correctKnown = obj["correctKnown"]?.jsonPrimitive?.booleanOrNull ?: true
                    )
                }
                if (attemptsToInsert.isNotEmpty()) {
                    attemptsToInsert.chunked(500).forEach { recordDao.insertAttempts(it) }
                }

                val wrongsToInsert = archive.records["wrongBook"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val (qId, bId) = resolveQuestion(obj) ?: return@mapNotNull null
                    WrongRecord(
                        questionId = qId,
                        bankId = bId,
                        wrongCount = (obj["wrongCount"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
                        lastWrongAt = obj["lastWrongAt"]?.jsonPrimitive?.longOrNull ?: now,
                        mastered = obj["mastered"]?.jsonPrimitive?.booleanOrNull ?: false,
                        flagged = obj["flagged"]?.jsonPrimitive?.booleanOrNull ?: false,
                        rebrushFailed = obj["rebrushFailed"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }
                if (wrongsToInsert.isNotEmpty()) {
                    wrongsToInsert.chunked(500).forEach { recordDao.insertWrongs(it) }
                }

                val favoritesToInsert = archive.records["favorites"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val (qId, bId) = resolveQuestion(obj) ?: return@mapNotNull null
                    FavoriteRecord(
                        questionId = qId,
                        bankId = bId,
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: now
                    )
                }
                if (favoritesToInsert.isNotEmpty()) {
                    favoritesToInsert.chunked(500).forEach { recordDao.insertFavorites(it) }
                }

                val killsToInsert = archive.records["kills"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val (qId, bId) = resolveQuestion(obj) ?: return@mapNotNull null
                    KillRecord(
                        questionId = qId,
                        bankId = bId,
                        killedAt = obj["killedAt"]?.jsonPrimitive?.longOrNull ?: now
                    )
                }
                if (killsToInsert.isNotEmpty()) {
                    killsToInsert.chunked(500).forEach { recordDao.insertKills(it) }
                }

                val durationsToInsert = archive.records["studyDurations"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val dayKey = obj["dayKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L
                    StudyDailyDuration(
                        dayKey = dayKey,
                        durationMs = durationMs,
                        updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: now
                    )
                }
                if (durationsToInsert.isNotEmpty()) {
                    durationsToInsert.chunked(500).forEach { recordDao.insertDailyDurations(it) }
                }

                ExportSummary(
                    fileName = "",
                    attempts = attemptsToInsert.size,
                    wrong = wrongsToInsert.size,
                    favorites = favoritesToInsert.size,
                    kills = killsToInsert.size,
                    banks = insertedBanks.size,
                    questions = totalQuestions
                )
            }
        }

    private suspend fun buildLearningRecordsJson(bankShaById: Map<Long, String> = emptyMap()): String {
        val banks = bankDao.observeAll().first()
        val bankNames = banks.associate { it.id to it.name }
        val attempts = recordDao.allAttempts()
        val wrong = recordDao.allWrong()
        val favorites = recordDao.allFavorites()
        val kills = recordDao.allKills()
        val durations = recordDao.allDailyDurations()
        val questions = questionDao.getByIds(
            (attempts.map { it.questionId } + wrong.map { it.questionId } +
                favorites.map { it.questionId } + kills.map { it.questionId }).distinct()
        ).associateBy { it.id }
        fun stemOf(id: Long): String = questions[id]?.stem.orEmpty().take(80)
        val json = buildJsonObject {
            put("schema", "shualegeti.export.v1")
            put("exportedAt", System.currentTimeMillis())
            put("exportedAtText", nowText())
            put("bankCount", banks.size)
            put("questionCount", questions.size)
            put("attempts", buildJsonArray { attempts.forEach { r -> add(buildJsonObject {
                put("questionId", r.questionId); put("bankId", r.bankId); put("bankSha256", bankShaById[r.bankId] ?: "")
                put("bankName", bankNames[r.bankId] ?: ""); put("stem", stemOf(r.questionId))
                put("mode", r.mode.name); put("userAnswer", r.userAnswer); put("correct", r.correct)
                put("correctKnown", r.correctKnown)
                r.runId?.let { put("runId", it) }
                put("selfRated", r.selfRated); put("durationMs", r.durationMs ?: 0L); put("answeredAt", r.answeredAt)
            }) } })
            put("wrongBook", buildJsonArray { wrong.forEach { r -> add(buildJsonObject {
                put("questionId", r.questionId); put("bankId", r.bankId); put("bankSha256", bankShaById[r.bankId] ?: ""); put("bankName", bankNames[r.bankId] ?: "")
                put("stem", stemOf(r.questionId)); put("wrongCount", r.wrongCount)
                put("mastered", r.mastered); put("flagged", r.flagged); put("rebrushFailed", r.rebrushFailed); put("lastWrongAt", r.lastWrongAt)
            }) } })
            put("favorites", buildJsonArray { favorites.forEach { r -> add(buildJsonObject {
                put("questionId", r.questionId); put("bankId", r.bankId); put("bankSha256", bankShaById[r.bankId] ?: ""); put("bankName", bankNames[r.bankId] ?: "")
                put("stem", stemOf(r.questionId)); put("createdAt", r.createdAt)
            }) } })
            put("kills", buildJsonArray { kills.forEach { r -> add(buildJsonObject {
                put("questionId", r.questionId); put("bankId", r.bankId); put("bankSha256", bankShaById[r.bankId] ?: ""); put("bankName", bankNames[r.bankId] ?: "")
                put("stem", stemOf(r.questionId)); put("killedAt", r.killedAt)
            }) } })
            put("studyDurations", buildJsonArray { durations.forEach { d -> add(buildJsonObject {
                put("dayKey", d.dayKey); put("durationMs", d.durationMs); put("updatedAt", d.updatedAt)
            }) } })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json)
    }

    /** 导出全部学习记录到用户选定的 Uri；返回统计 */
    suspend fun exportLearningRecords(context: Context, uri: Uri): ExportSummary =
        withContext(Dispatchers.IO) {
            val banks = bankDao.observeAll().first()
            val bankNames = banks.associate { it.id to it.name }
            val questions = questionDao.getByIds(
                (recordDao.allAttempts().map { it.questionId } +
                    recordDao.allWrong().map { it.questionId } +
                    recordDao.allFavorites().map { it.questionId } +
                    recordDao.allKills().map { it.questionId }).distinct()
            ).associateBy { it.id }

            fun stemOf(id: Long): String = questions[id]?.stem.orEmpty().take(80)

            val attempts = recordDao.allAttempts()
            val wrong = recordDao.allWrong()
            val favorites = recordDao.allFavorites()
            val kills = recordDao.allKills()

            val json = buildJsonObject {
                put("schema", "shualegeti.export.v1")
                put("exportedAt", System.currentTimeMillis())
                put("exportedAtText", nowText())
                put("bankCount", banks.size)
                put("questionCount", questions.size)
                put(
                    "attempts", buildJsonArray {
                        attempts.forEach { r ->
                            add(buildJsonObject {
                                put("questionId", r.questionId)
                                put("bankId", r.bankId)
                                put("bankName", bankNames[r.bankId] ?: "")
                                put("stem", stemOf(r.questionId))
                                put("mode", r.mode.name)
                                put("userAnswer", r.userAnswer)
                                put("correct", r.correct)
                                put("selfRated", r.selfRated)
                                put("durationMs", r.durationMs ?: 0L)
                                put("answeredAt", r.answeredAt)
                            })
                        }
                    }
                )
                put(
                    "wrongBook", buildJsonArray {
                        wrong.forEach { r ->
                            add(buildJsonObject {
                                put("questionId", r.questionId)
                                put("bankName", bankNames[r.bankId] ?: "")
                                put("stem", stemOf(r.questionId))
                                put("wrongCount", r.wrongCount)
                                put("mastered", r.mastered)
                                put("lastWrongAt", r.lastWrongAt)
                            })
                        }
                    }
                )
                put(
                    "favorites", buildJsonArray {
                        favorites.forEach { r ->
                            add(buildJsonObject {
                                put("questionId", r.questionId)
                                put("bankName", bankNames[r.bankId] ?: "")
                                put("stem", stemOf(r.questionId))
                                put("createdAt", r.createdAt)
                            })
                        }
                    }
                )
                put(
                    "kills", buildJsonArray {
                        kills.forEach { r ->
                            add(buildJsonObject {
                                put("questionId", r.questionId)
                                put("bankName", bankNames[r.bankId] ?: "")
                                put("stem", stemOf(r.questionId))
                                put("killedAt", r.killedAt)
                            })
                        }
                    }
                )
            }

            val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选文件")

            ExportSummary(
                fileName = "shualegeti-learning-${dateStamp()}.json",
                attempts = attempts.size,
                wrong = wrong.size,
                favorites = favorites.size,
                kills = kills.size,
                banks = banks.size,
                questions = questions.size
            )
        }

    /** 清空收藏夹 */
    suspend fun clearFavorites(): Int = withContext(Dispatchers.IO) { recordDao.clearAllFavorites() }

    /** 重置刷题进度（四种模式的游标与乱序队列） */
    suspend fun resetStudyProgress(): Int = withContext(Dispatchers.IO) { recordDao.clearAllProgress() }

    /** 建议的导出文件名 */
    fun suggestedFileName(): String = ShuaArchive.suggestedFileName(dateStamp())

    private fun dateStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
