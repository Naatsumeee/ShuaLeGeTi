package com.examhelper.shua.data.db

import androidx.room.Dao
import androidx.room.Delete
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

    @Query("SELECT * FROM questions WHERE bankId = :bankId AND (answer IS NULL OR answer = '') ORDER BY orderInBank ASC")
    suspend fun getNoAnswerByBank(bankId: Long): List<Question>

    @Update
    suspend fun update(question: Question)

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)
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
            // 答对不自动移出错题本，仅标记已掌握，可在错题本中查看
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
