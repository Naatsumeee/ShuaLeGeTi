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
import com.examhelper.shua.data.model.JudgeOptions
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedQuestion
import com.examhelper.shua.data.model.ShuaBankFile
import com.examhelper.shua.data.model.ShuaQuestionJson
import com.examhelper.shua.data.parse.HeadingClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class BankRepository(
    private val db: AppDatabase,
    private val bankDao: BankDao = db.bankDao(),
    private val questionDao: QuestionDao = db.questionDao(),
    private val progressDao: ProgressDao = db.progressDao(),
    private val recordDao: RecordDao = db.recordDao()
) {
    fun observeBanks(): Flow<List<QuestionBank>> = bankDao.observeAll()

    suspend fun getBank(id: Long): QuestionBank? = bankDao.getById(id)

    /** 题库组包含的子题库（组本身不复制题目） */
    suspend fun getGroupBanks(groupId: Long): List<QuestionBank> = bankDao.getGroupBanks(groupId)

    /**
     * 解析「首页卡片 → 可作答的题目集合」。
     *
     * 普通题库直接取自己的题；题库组把组内子题库按组内顺序拼接。
     * **合并题库**是真实题库（题目已搬到自己名下），所以也走普通分支。
     */
    suspend fun getQuestionsForCard(bank: QuestionBank): List<Question> {
        if (!bank.isGroup) return questionDao.getByBank(bank.id)
        val children = bankDao.getGroupBanks(bank.id)
        return children.flatMap { questionDao.getByBank(it.id) }
    }

    suspend fun cardQuestionCount(bank: QuestionBank): Int =
        if (bank.isGroup) bankDao.getGroupBanks(bank.id).sumOf { it.questionCount }
        else bank.questionCount

    /**
     * 首页卡片上「题库组」的真实题数 / 学习记录同步。
     *
     * 组本身不搬题目，子题库的 bankId 仍指向自身；因此：
     * - 统计只做聚合；
     * - 组内刷题仍以子题库 id 落 attempt/wrong，错题本自然会显示子题库名。
     * 这里先从 groupId 找子题库，再按子题库 id 批量取题，避免 N+1。
     */
    suspend fun groupQuestionStats(group: QuestionBank): Pair<Int, Int> {
        if (!group.isGroup) return group.questionCount to group.questionCount
        val children = bankDao.getGroupBanks(group.id)
        val ids = children.map { it.id }
        val questions = if (ids.isEmpty()) emptyList() else questionDao.getByBanks(ids)
        return questions.size to children.size
    }

    /** 新建题库组；只建立归属关系，不移动任何题目 */
    suspend fun createGroup(name: String, note: String?, color: Long?, childIds: List<Long>): Long {
        val now = System.currentTimeMillis()
        val id = bankDao.insert(
            QuestionBank(
                name = name.trim().ifBlank { "未命名题库组" },
                note = note?.trim()?.takeIf { it.isNotBlank() },
                accentColor = color,
                isGroup = true,
                questionCount = childIds.distinct().size,
                updatedAt = now
            )
        )
        childIds.distinct().forEach { child -> bankDao.setGroup(child, id, now) }
        return id
    }

    /**
     * 合并题库：把选中题库的题目与学习记录搬到新题库，然后删除旧库。
     *
     * 不能只做展示聚合：用户要求「合并后的题库」在错题本中也同步，
     * 所以 attempt/wrong/favorite/kill 的 bankId 必须一起迁移。
     * 原子题库 id 存在 mergedFromJson，用于撤销时重建空壳与恢复归属关系。
     */
    suspend fun mergeBanks(name: String, note: String?, color: Long?, sourceIds: List<Long>): Long {
        val ids = sourceIds.distinct()
        require(ids.size >= 2) { "至少选择两个题库才能合并" }
        val sources = ids.mapNotNull { bankDao.getById(it) }
        require(sources.size == ids.size) { "部分题库已不存在" }
        val now = System.currentTimeMillis()
        val mergedId = bankDao.insert(
            QuestionBank(
                name = name.trim().ifBlank { sources.first().name },
                note = note?.trim()?.takeIf { it.isNotBlank() },
                accentColor = color,
                questionCount = sources.sumOf { it.questionCount },
                updatedAt = now
            )
        )
        var order = 0
        val originalQuestions = sources.flatMap { source ->
            questionDao.getByBank(source.id).map { q ->
                Triple(source.id, q.id, q.orderInBank)
            }
        }
        sources.forEach { source ->
            val qs = questionDao.getByBank(source.id)
            // **保留原题 id，只改归属和顺序**。复制成新 id 会让撤销快照失效，
            // 也会让尝试/错题/收藏等按 questionId 关联的记录变成孤儿。
            qs.forEach { q ->
                questionDao.moveQuestion(q.id, mergedId, order++)
            }
            recordDao.moveAttemptsBank(source.id, mergedId)
            recordDao.moveWrongBank(source.id, mergedId)
            recordDao.moveFavoritesBank(source.id, mergedId)
            recordDao.moveKillsBank(source.id, mergedId)
            progressDao.deleteByBank(source.id)
            bankDao.deleteById(source.id)
        }
        // 完整快照：撤销合并时按原 bankId/原题 id/原顺序还原，不能只恢复空壳。
        val snapshot = buildJsonObject {
            put("banks", buildJsonArray {
                sources.forEach { s -> add(buildJsonObject {
                    put("id", s.id); put("name", s.name); put("sourceFileName", s.sourceFileName)
                    put("createdAt", s.createdAt); put("pinned", s.pinned); put("archived", s.archived)
                    put("sortOrder", s.sortOrder); put("accentColor", s.accentColor); put("note", s.note)
                }) }
            })
            put("questions", buildJsonArray {
                originalQuestions.forEach { (bankId, qid, originalOrder) ->
                    add(buildJsonObject {
                        put("bankId", bankId); put("questionId", qid); put("order", originalOrder)
                    })
                }
            })
        }
        // mergedFromJson 只存原 id，保留给旧版数据/调试；真正撤销用完整快照。
        bankDao.setMergedState(
            mergedId,
            count = order,
            snapshot = JsonCodec.json.encodeToString(sources.map { it.id }),
            mergeSnapshot = snapshot.toString(),
            now = now
        )
        return mergedId
    }

    /** 撤销合并：删除合并库，恢复原题库空壳（题目已合并，无法逐题无损还原）。 */
    suspend fun undoMerge(mergedId: Long): Boolean {
        val merged = bankDao.getById(mergedId) ?: return false
        val raw = merged.mergedFromJson ?: return false
        val ids = runCatching { JsonCodec.json.decodeFromString<List<Long>>(raw) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return false
        val now = System.currentTimeMillis()
        val snapshot = runCatching {
            JsonCodec.json.parseToJsonElement(merged.mergeSnapshotJson ?: return false).jsonObject
        }.getOrNull() ?: return false
        val bankNodes = snapshot["banks"]?.jsonArray ?: return false
        val questionNodes = snapshot["questions"]?.jsonArray ?: return false
        bankNodes.forEach { node ->
            val o = node.jsonObject
            bankDao.insert(QuestionBank(
                id = o["id"]!!.jsonPrimitive.long,
                name = o["name"]!!.jsonPrimitive.content,
                sourceFileName = o["sourceFileName"]?.jsonPrimitive?.contentOrNull,
                createdAt = o["createdAt"]!!.jsonPrimitive.long,
                pinned = o["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                archived = o["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
                sortOrder = o["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0,
                accentColor = o["accentColor"]?.jsonPrimitive?.longOrNull,
                note = o["note"]?.jsonPrimitive?.contentOrNull,
                updatedAt = now
            ))
        }
        questionNodes.forEach { node ->
            val o = node.jsonObject
            questionDao.moveQuestion(
                o["questionId"]!!.jsonPrimitive.long,
                o["bankId"]!!.jsonPrimitive.long,
                o["order"]!!.jsonPrimitive.int
            )
        }
        // 学习记录按原题库拆回去：用快照里的原 bankId 恢复记录归属。
        bankNodes.forEach { node ->
            val oldId = node.jsonObject["id"]!!.jsonPrimitive.long
            recordDao.moveAttemptsBank(mergedId, oldId)
            recordDao.moveWrongBank(mergedId, oldId)
            recordDao.moveFavoritesBank(mergedId, oldId)
            recordDao.moveKillsBank(mergedId, oldId)
        }
        bankDao.deleteById(mergedId)
        return true
    }

    fun observeQuestions(bankId: Long): Flow<List<Question>> = questionDao.observeByBank(bankId)

    suspend fun getQuestions(bankId: Long): List<Question> = questionDao.getByBank(bankId)

    suspend fun getQuestionsByIds(ids: List<Long>): List<Question> =
        if (ids.isEmpty()) emptyList() else questionDao.getByIds(ids)

    suspend fun countNoAnswer(bankId: Long): Int = questionDao.countNoAnswer(bankId)

    suspend fun countAiGenerated(bankId: Long): Int = questionDao.countAiGenerated(bankId)

    suspend fun countNoAnalysis(bankId: Long): Int = questionDao.countNoAnalysis(bankId)

    private fun ParsedQuestion.toEntity(bankId: Long, order: Int): Question {
        // 判断题统一在这里补选项+对齐答案字母：所有导入路径（正则解析 / AI 解析 /
        // JSON 导入）最后都会走到这里，放在这里一处就够，不必每个解析器各写一遍。
        val (judgeOpts, judgeAns) = JudgeOptions.normalize(type, options, answer)
        val finalOptions = if (type == QuestionType.JUDGE) judgeOpts else options
        val finalAnswer = if (type == QuestionType.JUDGE) judgeAns else answer
        // 章节名最后一道闸：所有导入路径（正则 / AI / JSON）都会走到这里。
        // 「（一）名词解释」「一、单项选择题」这类题型分组标题不是章节，
        // cleanChapter 会把它们判成 null（未分章），或把「第三章 名词解释」压成「第三章」。
        val finalChapter = HeadingClassifier.cleanChapter(chapter)
        return Question(
            bankId = bankId,
            orderInBank = order,
            chapter = finalChapter,
            type = type,
            subtype = subtype,
            stem = stem,
            sharedStem = sharedStem,
            sharedOptionsJson = sharedOptions.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
            optionsJson = finalOptions.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeOptions),
            answer = finalAnswer,
            analysis = analysis,
            sourcePage = sourcePage,
            aiGenerated = aiGenerated,
            confidence = confidence,
            agreement = agreement,
            dissentJson = dissent.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeDissent)
        )
    }

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
