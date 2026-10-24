package com.examhelper.shua.data.repo

import com.examhelper.shua.data.db.AppDatabase
import com.examhelper.shua.data.db.BankDao
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.db.QuestionDao
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

    suspend fun countNoAnswer(bankId: Long): Int = questionDao.countNoAnswer(bankId)

    /** 由解析结果创建题库 */
    suspend fun createBank(name: String, sourceFileName: String?, parsed: List<ParsedQuestion>): Long {
        val bankId = bankDao.insert(
            QuestionBank(name = name, sourceFileName = sourceFileName, questionCount = parsed.size)
        )
        val questions = parsed.mapIndexed { index, p ->
            Question(
                bankId = bankId,
                orderInBank = index,
                chapter = p.chapter,
                type = p.type,
                stem = p.stem,
                optionsJson = p.options.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
                answer = p.answer,
                analysis = p.analysis
            )
        }
        questionDao.insertAll(questions)
        return bankId
    }

    /** 追加题目（LLM 解析分批入库用） */
    suspend fun appendQuestions(bankId: Long, parsed: List<ParsedQuestion>) {
        val existing = questionDao.getByBank(bankId)
        val start = existing.size
        val questions = parsed.mapIndexed { index, p ->
            Question(
                bankId = bankId,
                orderInBank = start + index,
                chapter = p.chapter,
                type = p.type,
                stem = p.stem,
                optionsJson = p.options.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
                answer = p.answer,
                analysis = p.analysis
            )
        }
        questionDao.insertAll(questions)
        bankDao.getById(bankId)?.let {
            bankDao.update(it.copy(questionCount = start + parsed.size, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteBank(bankId: Long) {
        questionDao.deleteByBank(bankId)
        progressDao.deleteByBank(bankId)
        recordDao.deleteAttemptsByBank(bankId)
        recordDao.deleteWrongByBank(bankId)
        recordDao.deleteFavoritesByBank(bankId)
        bankDao.deleteById(bankId)
    }

    suspend fun updateQuestion(q: Question) = questionDao.update(q)

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
                    stem = q.stem,
                    options = JsonCodec.decodeOptions(q.optionsJson),
                    answer = q.answer,
                    analysis = q.analysis,
                    aiGenerated = q.aiGenerated,
                    confidence = q.confidence,
                    agreement = q.agreement
                )
            }
        )
    }

    suspend fun importBankFile(file: ShuaBankFile): Long {
        val parsed = file.questions.map { jq ->
            ParsedQuestion(
                chapter = jq.chapter,
                type = runCatching { com.examhelper.shua.data.db.QuestionType.valueOf(jq.type) }
                    .getOrElse { com.examhelper.shua.data.db.QuestionType.SINGLE },
                stem = jq.stem,
                options = jq.options.toMutableList(),
                answer = jq.answer,
                analysis = jq.analysis
            )
        }
        return createBank(file.name, file.sourceFileName, parsed)
    }
}
