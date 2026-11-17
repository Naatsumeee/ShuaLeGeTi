package com.examhelper.shua

import com.examhelper.shua.data.parse.FailedChunk
import com.examhelper.shua.data.parse.MissingAnswer
import com.examhelper.shua.data.parse.ParseReport
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
}
