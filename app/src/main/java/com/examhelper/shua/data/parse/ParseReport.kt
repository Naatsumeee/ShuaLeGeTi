package com.examhelper.shua.data.parse

import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedLine
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

object ParseReportBuilder {
    private val pageMarker = Regex("""\[\[PG:(\d+)]]""")

    fun build(
        chunks: List<ChunkPlanner.Chunk>,
        rawText: String,
        lines: List<ParsedLine>,
        failedChunks: List<FailedChunk>
    ): ParseReport {
        val pagesByChunk = chunks.associate { chunk ->
            chunk.index to pagesIn(rawText, chunk)
        }
        val missing = lines.mapNotNull { line ->
            if (!line.answer.isNullOrBlank()) return@mapNotNull null
            MissingAnswer(
                chunkIndex = line.chunkIndex,
                pages = listOfNotNull(line.page).ifEmpty {
                    pagesByChunk[line.chunkIndex].orEmpty()
                },
                chapter = line.chapter,
                number = line.number,
                stemPreview = line.stem.take(80)
            )
        }
        return ParseReport(failedChunks, missing)
    }

    private fun pagesIn(rawText: String, chunk: ChunkPlanner.Chunk): List<Int> {
        val rawLines = rawText.lines()
        val from = chunk.startLine.coerceAtLeast(0)
        val to = chunk.endLine.coerceAtMost(rawLines.lastIndex)
        if (from > to) return emptyList()
        return rawLines.subList(from, to + 1)
            .flatMap { line ->
                pageMarker.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }
            }
            .distinct()
            .sorted()
    }
}
