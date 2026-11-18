package com.examhelper.shua

import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ParsedLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ParsedJsonlTest {

    @Test
    fun `middle jsonl survives read back`() {
        val lines = listOf(
            ParsedLine(chunkIndex = 0, indexInChunk = 0, stem = "第一题"),
            ParsedLine(chunkIndex = 1, indexInChunk = 0, stem = "第二题")
        )
        val file = File.createTempFile("parsed", ".jsonl").apply {
            writeText(JsonCodec.encodeParsedLines(lines))
            deleteOnExit()
        }

        assertEquals(lines, JsonCodec.readParsedLines(file))
    }
}
