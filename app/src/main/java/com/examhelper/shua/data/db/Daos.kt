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
    // 置顶的排最前，其次按最近更新；归档的由 UI 单独收起，这里一并取出
    @Query("SELECT * FROM banks ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<QuestionBank>>

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun getById(id: Long): QuestionBank?

    @Query("UPDATE banks SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE banks SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE banks SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("UPDATE banks SET groupId = :groupId, updatedAt = :now WHERE id = :id")
    suspend fun setGroup(id: Long, groupId: Long?, now: Long)

    @Query("UPDATE banks SET note = :note, updatedAt = :now WHERE id = :id")
    suspend fun setNote(id: Long, note: String?, now: Long)

    @Query("UPDATE banks SET accentColor = :color, updatedAt = :now WHERE id = :id")
    suspend fun setAccentColor(id: Long, color: Long?, now: Long)

    @Query("UPDATE banks SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int, now: Long)

    @Query("UPDATE banks SET questionCount = :count, mergedFromJson = :snapshot, mergeSnapshotJson = :mergeSnapshot, updatedAt = :now WHERE id = :id")
    suspend fun setMergedState(
        id: Long,
        count: Int,
        snapshot: String?,
        mergeSnapshot: String?,
        now: Long
    )

    @Query("SELECT * FROM banks WHERE groupId = :groupId AND archived = 0 ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun getGroupBanks(groupId: Long): List<QuestionBank>

    @Query("SELECT * FROM banks WHERE isGroup = 1 ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun getGroups(): List<QuestionBank>

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

    @Query("SELECT * FROM questions WHERE bankId IN (:bankIds) ORDER BY bankId, orderInBank ASC")
    suspend fun getByBanks(bankIds: List<Long>): List<Question>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: Long): Flow<Question?>

    @Query("UPDATE questions SET bankId = :bankId, orderInBank = :order WHERE id = :id")
    suspend fun moveQuestion(id: Long, bankId: Long, order: Int)

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Question>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    suspend fun countByBank(bankId: Long): Int

    // 缺失判定必须与 UI 的 isNullOrBlank() 口径一致：纯空白串也算缺失。
    // 之前只判断 = ''，AI 返回空白串时统计不报缺失，覆盖率却算成不满。
    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND (answer IS NULL OR TRIM(answer) = '')")
    suspend fun countNoAnswer(bankId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND (analysis IS NULL OR TRIM(analysis) = '')")
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

    /** 全库各模式进度（「回到上次进度」用，按最近活动倒序） */
    @Query("SELECT * FROM study_progress WHERE bankId = :bankId ORDER BY updatedAt DESC")
    suspend fun allByBank(bankId: Long): List<StudyProgress>

    /** 实时刷新最近活动时间：作答即算触达，不必等点“下一题” */
    @Query("UPDATE study_progress SET updatedAt = :ts WHERE bankId = :bankId AND mode = :mode")
    suspend fun touch(bankId: Long, mode: StudyMode, ts: Long)

    @Query("DELETE FROM study_progress WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)

    /** 只清某一个模式的进度（这轮刷完即清，范围页不该再挂着一个不存在的「第 N/N 题」） */
    @Query("DELETE FROM study_progress WHERE bankId = :bankId AND mode = :mode")
    suspend fun deleteOne(bankId: Long, mode: StudyMode)
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

    /** 重点标记的分组：按标记时间无存储，统一按最近做错时间排 */
    @Query("SELECT * FROM wrong_records WHERE flagged = 1 ORDER BY lastWrongAt DESC")
    fun observeFlaggedWrong(): Flow<List<WrongRecord>>

    @Query("UPDATE wrong_records SET flagged = :flagged WHERE questionId = :questionId")
    suspend fun setFlagged(questionId: Long, flagged: Boolean)

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

    @Query("UPDATE attempt_records SET bankId = :toBankId WHERE bankId = :fromBankId")
    suspend fun moveAttemptsBank(fromBankId: Long, toBankId: Long)

    @Query("DELETE FROM wrong_records WHERE bankId = :bankId")
    suspend fun deleteWrongByBank(bankId: Long)

    @Query("UPDATE wrong_records SET bankId = :toBankId WHERE bankId = :fromBankId")
    suspend fun moveWrongBank(fromBankId: Long, toBankId: Long)

    @Query("DELETE FROM favorites WHERE bankId = :bankId")
    suspend fun deleteFavoritesByBank(bankId: Long)

    @Query("UPDATE favorites SET bankId = :toBankId WHERE bankId = :fromBankId")
    suspend fun moveFavoritesBank(fromBankId: Long, toBankId: Long)

    @Query("DELETE FROM kill_records WHERE bankId = :bankId")
    suspend fun deleteKillsByBank(bankId: Long)

    @Query("UPDATE kill_records SET bankId = :toBankId WHERE bankId = :fromBankId")
    suspend fun moveKillsBank(fromBankId: Long, toBankId: Long)

    // ---- 斩题 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKill(record: KillRecord)

    @Query("DELETE FROM kill_records WHERE questionId = :questionId")
    suspend fun removeKill(questionId: Long)

    @Query("SELECT * FROM kill_records ORDER BY killedAt DESC")
    fun observeKills(): Flow<List<KillRecord>>

    @Query("SELECT * FROM kill_records WHERE questionId = :questionId")
    suspend fun getKill(questionId: Long): KillRecord?

    // ---- 本地学习数据管理（设置页） ----

    /** 导出用：全部作答记录 */
    @Query("SELECT * FROM attempt_records ORDER BY answeredAt ASC")
    suspend fun allAttempts(): List<AttemptRecord>

    /** 导出用：全部错题记录 */
    @Query("SELECT * FROM wrong_records ORDER BY lastWrongAt ASC")
    suspend fun allWrong(): List<WrongRecord>

    /** 导出用：全部收藏 */
    @Query("SELECT * FROM favorites ORDER BY createdAt ASC")
    suspend fun allFavorites(): List<FavoriteRecord>

    /** 导出用：全部斩题记录 */
    @Query("SELECT * FROM kill_records ORDER BY killedAt ASC")
    suspend fun allKills(): List<KillRecord>

    /** 清空收藏夹（返回删除条数） */
    @Query("DELETE FROM favorites")
    suspend fun clearAllFavorites(): Int

    /** 重置刷题进度（游标/乱序队列全清，题库与作答历史保留） */
    @Query("DELETE FROM study_progress")
    suspend fun clearAllProgress(): Int

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

    /** 还在进行中的任务（进程重启后自愈用） */
    @Query("SELECT * FROM import_jobs WHERE stage IN ('INGEST', 'PARSE') ORDER BY createdAt DESC")
    suspend fun activeJobs(): List<ImportJob>

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun delete(id: String)

    /** 窗口内的导入任务（用量统计用） */
    @Query("SELECT * FROM import_jobs WHERE createdAt >= :since")
    suspend fun since(since: Long): List<ImportJob>
}

@Dao
interface GenerateJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: GenerateJob)

    @Query("SELECT * FROM generate_jobs WHERE bankId = :bankId")
    fun observe(bankId: Long): Flow<GenerateJob?>

    @Query("SELECT * FROM generate_jobs WHERE bankId = :bankId")
    suspend fun get(bankId: Long): GenerateJob?

    /** 窗口内的生成任务（用量统计用，按 updatedAt 近似任务完成时间） */
    @Query("SELECT * FROM generate_jobs WHERE updatedAt >= :since")
    suspend fun since(since: Long): List<GenerateJob>
}

@Dao
interface BalanceSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BalanceSnapshot): Long

    /** 该 provider 最近一次快照（判断是否需要再采一次） */
    @Query("SELECT * FROM balance_snapshots WHERE providerId = :providerId ORDER BY sampledAt DESC LIMIT 1")
    suspend fun latest(providerId: Long): BalanceSnapshot?

    /**
     * 窗口内快照。多取一条窗口起点之前的值 —— 只有「起点之前」和「窗口内第一条」
     * 之间的差值才能覆盖窗口开头那段时间的消费。
     */
    @Query("SELECT * FROM balance_snapshots WHERE providerId = :providerId AND sampledAt >= :since ORDER BY sampledAt ASC")
    suspend fun since(providerId: Long, since: Long): List<BalanceSnapshot>

    @Query("SELECT * FROM balance_snapshots WHERE providerId = :providerId AND sampledAt < :before ORDER BY sampledAt DESC LIMIT 1")
    suspend fun lastBefore(providerId: Long, before: Long): BalanceSnapshot?

    /** 只留最近 [before] 之后的快照，避免无限增长 */
    @Query("DELETE FROM balance_snapshots WHERE sampledAt < :before")
    suspend fun purgeBefore(before: Long): Int
}
