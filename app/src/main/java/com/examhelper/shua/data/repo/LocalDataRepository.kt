package com.examhelper.shua.data.repo

import android.content.Context
import android.net.Uri
import com.examhelper.shua.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
        val kills: Int
    )

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
                kills = kills.size
            )
        }

    /** 清空收藏夹 */
    suspend fun clearFavorites(): Int = withContext(Dispatchers.IO) { recordDao.clearAllFavorites() }

    /** 重置刷题进度（四种模式的游标与乱序队列） */
    suspend fun resetStudyProgress(): Int = withContext(Dispatchers.IO) { recordDao.clearAllProgress() }

    /** 建议的导出文件名 */
    fun suggestedFileName(): String = "shualegeti-learning-${dateStamp()}.json"

    private fun dateStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
