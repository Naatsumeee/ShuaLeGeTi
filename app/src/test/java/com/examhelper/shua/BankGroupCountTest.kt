package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionBank
import com.examhelper.shua.data.repo.withLiveGroupCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class BankGroupCountTest {

    @Test
    fun `题库组题数按未归档子题库实时聚合`() {
        val group = QuestionBank(
            id = 10,
            name = "组",
            questionCount = 99,
            isGroup = true
        )
        val banks = listOf(
            group,
            QuestionBank(id = 1, name = "甲", groupId = 10, questionCount = 2),
            QuestionBank(id = 2, name = "乙", groupId = 10, questionCount = 3),
            QuestionBank(
                id = 3,
                name = "已归档子库",
                groupId = 10,
                questionCount = 5,
                archived = true
            ),
            QuestionBank(id = 4, name = "普通库", questionCount = 7)
        )

        val updated = withLiveGroupCounts(
            banks,
            questionCounts = mapOf(1L to 2, 2L to 4, 3L to 5, 4L to 7)
        )

        assertEquals(6, updated.first { it.id == 10L }.questionCount)
        assertEquals(7, updated.first { it.id == 4L }.questionCount)
    }
}
