package com.examhelper.shua.data.parse

import com.examhelper.shua.data.model.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class FailedChunk(
    val chunkIndex: Int,
    val pageFrom: Int? = null,
    val pageTo: Int? = null,
    val reason: String
)

@Serializable
data class MissingAnswer(
    val chunkIndex: Int,
    val pages: List<Int> = emptyList(),
    val chapter: String? = null,
    val number: Int? = null,
    val stemPreview: String
)

@Serializable
data class ParseReport(
    val failedChunks: List<FailedChunk> = emptyList(),
    val missingAnswers: List<MissingAnswer> = emptyList()
) {
    fun encode(): String = JsonCodec.json.encodeToString(this)

    companion object {
        fun encode(report: ParseReport): String = report.encode()

        fun decode(raw: String?): ParseReport =
            runCatching { JsonCodec.json.decodeFromString<ParseReport>(raw ?: return ParseReport()) }
                .getOrDefault(ParseReport())
    }
}
