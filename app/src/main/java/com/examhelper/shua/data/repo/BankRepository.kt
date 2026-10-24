package com.examhelper.shua.data.repo

import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.BankDao
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionDao
import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.db.RecordDao
import com.examhelper.shua.data.db.ProgressDao
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
        sourcePage = sourcePage
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

    suspend fun exportBank(bankId: Long): ShuaBankFile? {
        val bank = bankDao.getById(bankId) ?: return null
        val questions = questionDao.getByBank(bankId)
        return ShuaBankFile(
            name = bank.name,
            sourceFileName = bank.sourceFileName,
            exportedAt = System.currentTimeMillis(),
            questions = questions.map { q ->
                ShuaQuestionJson(
                    chapter = q.chapter,
                    type = q.type.name,
                    subtype = q.subtype,
                    stem = q.stem,
                    sharedStem = q.sharedStem,
                    sharedOptions = JsonCodec.decodeOptions(q.sharedOptionsJson),
                    options = JsonCodec.decodeOptions(q.optionsJson),
                    answer = q.answer,
                    analysis = q.analysis,
                    sourcePage = q.sourcePage,
                    aiGenerated = q.aiGenerated,
                    confidence = q.confidence,
                    agreement = q.agreement
                )
            }
        )
    }

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
        sourcePage = sourcePage
    )
}
