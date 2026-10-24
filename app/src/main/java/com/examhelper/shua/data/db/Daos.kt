package com.examhelper.shua.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BankDao {
    @Query("SELECT * FROM banks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<QuestionBank>>

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun getById(id: Long): QuestionBank?

    @Insert
    suspend fun insert(bank: QuestionBank): Long

    @Update
    suspend fun update(bank: QuestionBank)

    @Query("DELETE FROM banks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface QuestionDao {
    @Insert
    suspend fun insertAll(questions: List<Question>): List<Long>

    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY orderInBank ASC")
    fun observeByBank(bankId: Long): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY orderInBank ASC")
    suspend fun getByBank(bankId: Long): List<Question>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Question>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    suspend fun countByBank(bankId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND (answer IS NULL OR answer = '')")
    suspend fun countNoAnswer(bankId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND (analysis IS NULL OR analysis = '')")
    suspend fun countNoAnalysis(bankId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND aiGenerated = 1")
    suspend fun countAiGenerated(bankId: Long): Int

    @Update
    suspend fun update(question: Question)

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)

    @Query("SELECT DISTINCT chapter FROM questions WHERE bankId = :bankId AND chapter IS NOT NULL ORDER BY chapter")
    suspend fun chaptersOfBank(bankId: Long): List<String>

    // 全库搜索
    data class SearchRow(
        val id: Long,
        val bankId: Long,
        val bankName: String,
        val type: QuestionType,
        val stem: String,
        val answer: String?
    )

    @Query("""
        SELECT q.id AS id, q.bankId AS bankId, b.name AS bankName, q.type AS type,
               q.stem AS stem, q.answer AS answer
        FROM questions q JOIN banks b ON b.id = q.bankId
        WHERE q.stem LIKE '%' || :keyword || '%'
           OR q.answer LIKE '%' || :keyword || '%'
           OR q.analysis LIKE '%' || :keyword || '%'
           OR q.optionsJson LIKE '%' || :keyword || '%'
        ORDER BY q.bankId, q.orderInBank
        LIMIT :limit
    """)
    suspend fun searchAll(keyword: String, limit: Int = 200): List<SearchRow>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM study_progress WHERE bankId = :bankId AND mode = :mode")
    suspend fun get(bankId: Long, mode: StudyMode): StudyProgress?

    @Query("SELECT * FROM study_progress WHERE bankId = :bankId AND mode = :mode")
    fun observe(bankId: Long, mode: StudyMode): Flow<StudyProgress?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: StudyProgress)

    @Query("DELETE FROM study_progress WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)
}

@Dao
interface RecordDao {
    @Insert
    suspend fun insertAttempt(record: AttemptRecord)

    @Query("SELECT * FROM attempt_records WHERE questionId = :questionId ORDER BY answeredAt DESC LIMIT 1")
    suspend fun latestAttempt(questionId: Long): AttemptRecord?

    @Query("SELECT COUNT(*) FROM attempt_records WHERE answeredAt >= :since")
    fun observeAttemptCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM attempt_records WHERE correct = 1 AND answeredAt >= :since")
    fun observeCorrectCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM attempt_records")
    fun observeTotalAttempts(): Flow<Int>

    @Query("SELECT COUNT(*) FROM attempt_records WHERE correct = 1")
    fun observeTotalCorrect(): Flow<Int>

    // 测试模式报告
    @Query("SELECT * FROM attempt_records WHERE runId = :runId ORDER BY answeredAt ASC")
    suspend fun getByRun(runId: String): List<AttemptRecord>

    // 热力图：日期聚合在 Kotlin 层做
    @Query("SELECT answeredAt FROM attempt_records WHERE answeredAt >= :since")
    suspend fun timestampsSince(since: Long): List<Long>

    // 筛选用：已作答/做错/收藏/已斩 的题目集合
    @Query("SELECT DISTINCT questionId FROM attempt_records WHERE bankId = :bankId")
    suspend fun answeredIds(bankId: Long): List<Long>

    @Query("SELECT questionId FROM wrong_records WHERE mastered = 0")
    suspend fun wrongIds(): List<Long>

    @Query("SELECT questionId FROM favorites")
    suspend fun favoriteIds(): List<Long>

    @Query("SELECT questionId FROM kill_records")
    suspend fun killedIds(): List<Long>

    data class BankStatRow(val bankId: Long, val cnt: Int, val ok: Int)

    @Query("SELECT bankId, COUNT(*) as cnt, SUM(CASE WHEN correct THEN 1 ELSE 0 END) as ok FROM attempt_records GROUP BY bankId")
    fun observeBankStats(): Flow<List<BankStatRow>>

    // ---- 错题本 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWrong(record: WrongRecord)

    @Query("SELECT * FROM wrong_records WHERE mastered = 0 ORDER BY lastWrongAt DESC")
    fun observeWrongBook(): Flow<List<WrongRecord>>

    @Query("SELECT * FROM wrong_records WHERE mastered = 1 ORDER BY lastWrongAt DESC")
    fun observeMasteredWrong(): Flow<List<WrongRecord>>

    @Query("SELECT * FROM wrong_records WHERE questionId = :questionId")
    suspend fun getWrong(questionId: Long): WrongRecord?

    @Query("UPDATE wrong_records SET mastered = 1 WHERE questionId = :questionId")
    suspend fun markMastered(questionId: Long)

    @Query("DELETE FROM wrong_records WHERE questionId = :questionId")
    suspend fun removeWrong(questionId: Long)

    // ---- 收藏 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(record: FavoriteRecord)

    @Query("DELETE FROM favorites WHERE questionId = :questionId")
    suspend fun removeFavorite(questionId: Long)

    @Query("SELECT * FROM favorites WHERE questionId = :questionId")
    suspend fun getFavorite(questionId: Long): FavoriteRecord?

    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<FavoriteRecord>>

    @Query("DELETE FROM attempt_records WHERE bankId = :bankId")
    suspend fun deleteAttemptsByBank(bankId: Long)

    @Query("DELETE FROM wrong_records WHERE bankId = :bankId")
    suspend fun deleteWrongByBank(bankId: Long)

    @Query("DELETE FROM favorites WHERE bankId = :bankId")
    suspend fun deleteFavoritesByBank(bankId: Long)

    @Query("DELETE FROM kill_records WHERE bankId = :bankId")
    suspend fun deleteKillsByBank(bankId: Long)

    // ---- 斩题 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKill(record: KillRecord)

    @Query("DELETE FROM kill_records WHERE questionId = :questionId")
    suspend fun removeKill(questionId: Long)

    @Query("SELECT * FROM kill_records ORDER BY killedAt DESC")
    fun observeKills(): Flow<List<KillRecord>>

    @Query("SELECT * FROM kill_records WHERE questionId = :questionId")
    suspend fun getKill(questionId: Long): KillRecord?

    @Transaction
    suspend fun submitAnswer(
        record: AttemptRecord,
        existingWrong: WrongRecord?
    ) {
        insertAttempt(record)
        if (!record.correct) {
            if (existingWrong != null) {
                upsertWrong(
                    existingWrong.copy(
                        wrongCount = existingWrong.wrongCount + 1,
                        lastWrongAt = record.answeredAt,
                        mastered = false
                    )
                )
            } else {
                upsertWrong(
                    WrongRecord(
                        questionId = record.questionId,
                        bankId = record.bankId,
                        wrongCount = 1,
                        lastWrongAt = record.answeredAt
                    )
                )
            }
        } else if (existingWrong != null) {
            markMastered(record.questionId)
        }
    }
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM llm_providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<LlmProvider>>

    @Query("SELECT * FROM llm_providers WHERE enabled = 1 ORDER BY createdAt ASC")
    fun observeEnabled(): Flow<List<LlmProvider>>

    @Query("SELECT * FROM llm_providers WHERE isPrimary = 1 LIMIT 1")
    suspend fun getPrimary(): LlmProvider?

    @Query("SELECT * FROM llm_providers WHERE isPrimary = 1 LIMIT 1")
    fun observePrimary(): Flow<LlmProvider?>

    @Query("SELECT * FROM llm_providers WHERE id = :id")
    suspend fun getById(id: Long): LlmProvider?

    @Insert
    suspend fun insert(provider: LlmProvider): Long

    @Update
    suspend fun update(provider: LlmProvider)

    @Query("DELETE FROM llm_providers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE llm_providers SET isPrimary = 0")
    suspend fun clearPrimary()
}

@Dao
interface ImportJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ImportJob)

    @Query("SELECT * FROM import_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportJob>>

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun getById(id: String): ImportJob?

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    fun observe(id: String): Flow<ImportJob?>

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GenerateJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: GenerateJob)

    @Query("SELECT * FROM generate_jobs WHERE bankId = :bankId")
    fun observe(bankId: Long): Flow<GenerateJob?>

    @Query("SELECT * FROM generate_jobs WHERE bankId = :bankId")
    suspend fun get(bankId: Long): GenerateJob?
}
