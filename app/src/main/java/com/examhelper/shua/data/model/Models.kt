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
    var stem: String = "",
    val options: MutableList<QuestionOption> = mutableListOf(),
    var answer: String? = null,
    var analysis: String? = null
)

/** 标准题库文件格式（导出/导入/分享用） */
@Serializable
data class ShuaBankFile(
    val version: Int = 1,
    val name: String,
    val sourceFileName: String? = null,
    val exportedAt: Long,
    val questions: List<ShuaQuestionJson>
)

@Serializable
data class ShuaQuestionJson(
    val chapter: String? = null,
    val type: String,
    val stem: String,
    val options: List<QuestionOption> = emptyList(),
    val answer: String? = null,
    val analysis: String? = null,
    val aiGenerated: Boolean = false,
    val confidence: Int? = null,
    val agreement: String? = null
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
}
