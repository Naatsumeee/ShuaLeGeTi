package com.examhelper.shua

import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.ChunkPlanner
import com.examhelper.shua.data.parse.FailedChunk
import com.examhelper.shua.data.parse.MissingAnswer
import com.examhelper.shua.data.parse.ParseReport
import com.examhelper.shua.data.parse.ParseReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ParseReportTest {
    @Test
    fun parseReportRoundTrips() {
        val report = ParseReport(
            failedChunks = listOf(FailedChunk(3, 8, 9, "不可解析")),
            missingAnswers = listOf(
                MissingAnswer(3, listOf(8, 9), "第十章", 1000, "题干摘要")
            )
        )
        assertEquals(report, ParseReport.decode(ParseReport.encode(report)))
    }

    @Test
    fun buildReportsMissingAnswersWithPageMarkers() {
        val raw = "[[PG:8]]\n1000. 题目\n[[PG:9]]\n选项"
        val chunk = ChunkPlanner.Chunk(2, raw, 0, 3)
        val lines = listOf(
            ParsedLine(
                chunkIndex = 2,
                indexInChunk = 0,
                chapter = "第十章",
                number = 1000,
                stem = "题干",
                answer = null,
                page = null
            )
        )

        val report = ParseReportBuilder.build(listOf(chunk), raw, lines, emptyList())

        assertEquals(listOf(8, 9), report.missingAnswers.single().pages)
        assertEquals(1000, report.missingAnswers.single().number)
    }

    @Test
    fun buildPrefersLinePageAndSkipsAnsweredOrBlankAnswers() {
        val raw = "[[PG:8]]\n题目\n[[PG:9]]\n选项"
        val chunk = ChunkPlanner.Chunk(3, raw, 0, 3)
        val lines = listOf(
            ParsedLine(3, 0, number = 1, stem = "有页码", answer = null, page = 12),
            ParsedLine(3, 1, number = 2, stem = "已作答", answer = "A"),
            ParsedLine(3, 2, number = 3, stem = "空白答案", answer = "  ")
        )

        val report = ParseReportBuilder.build(listOf(chunk), raw, lines, emptyList())

        assertEquals(listOf(12), report.missingAnswers[0].pages)
        assertEquals(listOf(8, 9), report.missingAnswers[1].pages)
    }

    @Test
    fun buildDeduplicatesAndSortsPageMarkers() {
        val raw = "[[PG:9]]\n题目 [[PG:8]] [[PG:9]]"
        val chunk = ChunkPlanner.Chunk(4, raw, 0, 1)
        val line = ParsedLine(4, 0, stem = "题干", answer = null, page = null)

        val report = ParseReportBuilder.build(listOf(chunk), raw, listOf(line), emptyList())

        assertEquals(listOf(8, 9), report.missingAnswers.single().pages)
    }

    @Test
    fun buildPassesFailedChunksThrough() {
        val failed = listOf(FailedChunk(4, 10, 11, "超时"))
        val report = ParseReportBuilder.build(emptyList(), "", emptyList(), failed)

        assertEquals(failed, report.failedChunks)
        assertEquals(emptyList<MissingAnswer>(), report.missingAnswers)
    }
}
