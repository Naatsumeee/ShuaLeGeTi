package com.examhelper.shua.data.model

import com.examhelper.shua.data.db.QuestionType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 题目选项 */
@Serializable
data class QuestionOption(val key: String, val text: String)

/** 解析器输出的中间题目模型 */
data class ParsedQuestion(
    var chapter: String? = null,
    var type: QuestionType = QuestionType.SINGLE,
    /** 医学题型子类型 A1/A2/A3/B */
    var subtype: String? = null,
    var stem: String = "",
    /** A3 型共用题干材料 */
    var sharedStem: String? = null,
    /** B 型共用选项组 */
    var sharedOptions: MutableList<QuestionOption> = mutableListOf(),
    val options: MutableList<QuestionOption> = mutableListOf(),
    var answer: String? = null,
    var analysis: String? = null,
    /** 原文页码（PDF 导入时记录，章节划分辅助） */
    var sourcePage: Int? = null,
    /** 导入标准题库文件时还原 AI 元数据 */
    var aiGenerated: Boolean = false,
    var confidence: Int? = null,
    var agreement: String? = null,
    var dissent: List<DissentEntry> = emptyList()
)

/** 标准题库文件格式（导出/导入/分享用） */
@Serializable
data class ShuaBankFile(
    val version: Int = 3,
    val name: String,
    val sourceFileName: String? = null,
    val exportedAt: Long,
    /** 导出选项：是否包含 AI 生成的答案与解析（false 时 AI 部分被剔除，人工内容保留） */
    val includeAiGenerated: Boolean = true,
    /** 章节分类汇总 */
    val chapters: List<ChapterSummary> = emptyList(),
    val questions: List<ShuaQuestionJson>
)

@Serializable
data class ChapterSummary(
    val chapter: String,
    val count: Int,
    val types: Map<String, Int> = emptyMap(),
    val subtypes: Map<String, Int> = emptyMap(),
    val pageFrom: Int? = null,
    val pageTo: Int? = null
)

@Serializable
data class ShuaQuestionJson(
    val chapter: String? = null,
    val type: String,
    val subtype: String? = null,
    val stem: String,
    val sharedStem: String? = null,
    val sharedOptions: List<QuestionOption> = emptyList(),
    val options: List<QuestionOption> = emptyList(),
    val answer: String? = null,
    val analysis: String? = null,
    val sourcePage: Int? = null,
    val aiGenerated: Boolean = false,
    val confidence: Int? = null,
    val agreement: String? = null,
    val dissent: List<DissentEntry> = emptyList()
)

/** 后台解析 JSONL 行 DTO：带分块索引用于恢复全局顺序 */
@Serializable
data class ParsedLine(
    val chunkIndex: Int,
    val indexInChunk: Int,
    val chapter: String? = null,
    val type: String = "SINGLE",
    val subtype: String? = null,
    val stem: String,
    val sharedStem: String? = null,
    val sharedOptions: List<QuestionOption> = emptyList(),
    val options: List<QuestionOption> = emptyList(),
    val answer: String? = null,
    val analysis: String? = null,
    val page: Int? = null
)

/** 三省六部各部分歧展示模型 */
@Serializable
data class DissentEntry(
    val provider: String,
    val answer: String,
    val analysis: String? = null,
    val selfConfidence: Int? = null
)

object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encodeOptions(options: List<QuestionOption>): String = json.encodeToString(options)

    fun decodeOptions(raw: String?): List<QuestionOption> =
        runCatching { json.decodeFromString<List<QuestionOption>>(raw ?: return emptyList()) }
            .getOrDefault(emptyList())

    fun encodeDissent(entries: List<DissentEntry>): String = json.encodeToString(entries)

    fun decodeDissent(raw: String?): List<DissentEntry> =
        runCatching { json.decodeFromString<List<DissentEntry>>(raw ?: return emptyList()) }
            .getOrDefault(emptyList())

    fun encodeIdList(ids: List<Long>): String = json.encodeToString(ids)

    fun decodeIdList(raw: String?): List<Long> =
        runCatching { json.decodeFromString<List<Long>>(raw ?: return emptyList()) }
            .getOrDefault(emptyList())

    fun encodeStringList(items: List<String>): String? =
        if (items.isEmpty()) null else json.encodeToString(items)

    fun decodeStringList(raw: String?): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw ?: return emptyList()) }
            .getOrDefault(emptyList())

    /** 读取 JSONL（容忍空行与坏行） */
    fun readParsedLines(file: java.io.File): List<ParsedLine> =
        runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString<ParsedLine>(line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
}

fun ParsedLine.toParsedQuestion(): ParsedQuestion = ParsedQuestion(
    chapter = chapter,
    type = runCatching { QuestionType.valueOf(type) }.getOrDefault(QuestionType.SINGLE),
    subtype = subtype,
    stem = stem,
    sharedStem = sharedStem,
    sharedOptions = sharedOptions.toMutableList(),
    options = options.toMutableList(),
    answer = answer,
    analysis = analysis,
    sourcePage = page
)
