package com.examhelper.shua.data.repo

import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.BankDao
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionDao
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.RecordDao
import com.examhelper.shua.data.db.ProgressDao
import com.examhelper.shua.data.model.ChapterSummary
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.model.ShuaBankFile
import com.examhelper.shua.data.model.ShuaQuestionJson
import kotlinx.coroutines.flow.Flow

class BankRepository(
    private val db: AppDatabase,
    private val bankDao: BankDao = db.bankDao(),
    private val questionDao: QuestionDao = db.questionDao(),
    private val progressDao: ProgressDao = db.progressDao(),
    private val recordDao: RecordDao = db.recordDao()
) {
    fun observeBanks(): Flow<List<QuestionBank>> = bankDao.observeAll()

    suspend fun getBank(id: Long): QuestionBank? = bankDao.getById(id)

    fun observeQuestions(bankId: Long): Flow<List<Question>> = questionDao.observeByBank(bankId)

    suspend fun getQuestions(bankId: Long): List<Question> = questionDao.getByBank(bankId)

    suspend fun getQuestionsByIds(ids: List<Long>): List<Question> =
        if (ids.isEmpty()) emptyList() else questionDao.getByIds(ids)

    suspend fun countNoAnswer(bankId: Long): Int = questionDao.countNoAnswer(bankId)

    suspend fun countAiGenerated(bankId: Long): Int = questionDao.countAiGenerated(bankId)

    private fun ParsedQuestion.toEntity(bankId: Long, order: Int): Question = Question(
        bankId = bankId,
        orderInBank = order,
        chapter = chapter,
        type = type,
        subtype = subtype,
        stem = stem,
        sharedStem = sharedStem,
        sharedOptionsJson = sharedOptions.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
        optionsJson = options.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
        answer = answer,
        analysis = analysis,
        sourcePage = sourcePage,
        aiGenerated = aiGenerated,
        confidence = confidence,
        agreement = agreement,
        dissentJson = dissent.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeDissent)
    )

    /** 由解析结果创建题库 */
    suspend fun createBank(name: String, sourceFileName: String?, parsed: List<ParsedQuestion>): Long {
        val bankId = bankDao.insert(
            QuestionBank(name = name, sourceFileName = sourceFileName, questionCount = parsed.size)
        )
        questionDao.insertAll(parsed.mapIndexed { index, p -> p.toEntity(bankId, index) })
        return bankId
    }

    suspend fun deleteBank(bankId: Long) {
        questionDao.deleteByBank(bankId)
        progressDao.deleteByBank(bankId)
        recordDao.deleteAttemptsByBank(bankId)
        recordDao.deleteWrongByBank(bankId)
        recordDao.deleteFavoritesByBank(bankId)
        recordDao.deleteKillsByBank(bankId)
        bankDao.deleteById(bankId)
    }

    suspend fun updateQuestion(q: Question) = questionDao.update(q)

    suspend fun chapters(bankId: Long): List<String> = questionDao.chaptersOfBank(bankId)

    suspend fun searchAll(keyword: String) = questionDao.searchAll(keyword)

    // ---- 标准题库文件导出/导入 ----

    /**
     * 导出题库（当前数据库状态，含用户手动修改）。
     * @param includeAiGenerated false 时剔除 AI 生成的答案/解析/置信度/分歧，
     * 原题库自带答案与用户内容保留
     */
    suspend fun exportBank(bankId: Long, includeAiGenerated: Boolean = true): ShuaBankFile? {
        val bank = bankDao.getById(bankId) ?: return null
        val questions = questionDao.getByBank(bankId)
        val exported = questions.map { q ->
            val stripAi = !includeAiGenerated && q.aiGenerated
            ShuaQuestionJson(
                chapter = q.chapter,
                type = q.type.name,
                subtype = q.subtype,
                stem = q.stem,
                sharedStem = q.sharedStem,
                sharedOptions = JsonCodec.decodeOptions(q.sharedOptionsJson),
                options = JsonCodec.decodeOptions(q.optionsJson),
                answer = if (stripAi) null else q.answer,
                analysis = if (stripAi) null else q.analysis,
                sourcePage = q.sourcePage,
                aiGenerated = if (stripAi) false else q.aiGenerated,
                confidence = if (stripAi) null else q.confidence,
                agreement = if (stripAi) null else q.agreement,
                dissent = if (stripAi) emptyList() else JsonCodec.decodeDissent(q.dissentJson)
            )
        }
        return ShuaBankFile(
            name = bank.name,
            sourceFileName = bank.sourceFileName,
            exportedAt = System.currentTimeMillis(),
            includeAiGenerated = includeAiGenerated,
            chapters = buildChapterSummaries(exported),
            questions = exported
        )
    }

    private fun buildChapterSummaries(questions: List<ShuaQuestionJson>): List<ChapterSummary> =
        questions.groupBy { it.chapter ?: "未分章" }
            .map { (chapter, list) ->
                ChapterSummary(
                    chapter = chapter,
                    count = list.size,
                    types = list.groupingBy { it.type }.eachCount(),
                    subtypes = list.mapNotNull { it.subtype }.groupingBy { it }.eachCount()
                        .takeIf { it.isNotEmpty() } ?: emptyMap(),
                    pageFrom = list.mapNotNull { it.sourcePage }.minOrNull(),
                    pageTo = list.mapNotNull { it.sourcePage }.maxOrNull()
                )
            }
            .sortedBy { it.pageFrom ?: Int.MAX_VALUE }

    suspend fun importBankFile(file: ShuaBankFile): Long {
        val parsed = file.questions.map { it.toParsedQuestion() }
        return createBank(file.name, file.sourceFileName, parsed)
    }

    private fun ShuaQuestionJson.toParsedQuestion(): ParsedQuestion = ParsedQuestion(
        chapter = chapter,
        type = runCatching { QuestionType.valueOf(type) }.getOrElse { QuestionType.SINGLE },
        subtype = subtype,
        stem = stem,
        sharedStem = sharedStem,
        sharedOptions = sharedOptions.toMutableList(),
        options = options.toMutableList(),
        answer = answer,
        analysis = analysis,
        sourcePage = sourcePage,
        aiGenerated = aiGenerated,
        confidence = confidence,
        agreement = agreement,
        dissent = dissent
    )
}
