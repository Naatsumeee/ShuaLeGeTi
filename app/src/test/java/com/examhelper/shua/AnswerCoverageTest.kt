package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.AnswerCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerCoverageTest {

    @Test
    fun `核对整章答案覆盖率并列出低覆盖章节`() {
        val lines = buildList {
            repeat(5) { i ->
                add(line(i, "第一章 绪论", if (i == 0) "A" else null))
            }
            repeat(6) { i ->
                add(line(10 + i, "第三章 病因与因果推断", if (i < 1) "B" else null))
            }
        }

        val audit = AnswerCoverage.audit(lines)

        assertEquals(11, audit.total)
        assertEquals(2, audit.answered)
        assertEquals(listOf("第三章 病因与因果推断"), audit.lowCoverageChapters)
    }

    private fun line(no: Int, chapter: String, answer: String?) = ParsedLine(
        chunkIndex = 0,
        indexInChunk = no,
        chapter = chapter,
        type = QuestionType.SINGLE.name,
        stem = "题 $no",
        answer = answer
    )
}
