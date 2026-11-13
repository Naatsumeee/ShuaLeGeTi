package com.examhelper.shua

import com.examhelper.shua.data.repo.MergeBankSnapshot
import com.examhelper.shua.data.repo.MergeQuestionSnapshot
import com.examhelper.shua.data.repo.MergeSnapshot
import com.examhelper.shua.data.repo.MergeSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeSnapshotTest {

    @Test
    fun `撤销合并按题目原始 bankId 恢复而不是按题库顺序反复搬移`() {
        val snapshot = MergeSnapshot(
            banks = listOf(
                bank(id = 11L, name = "甲题库"),
                bank(id = 22L, name = "乙题库")
            ),
            questions = listOf(
                MergeQuestionSnapshot(bankId = 11L, questionId = 101L, order = 0),
                MergeQuestionSnapshot(bankId = 22L, questionId = 201L, order = 0),
                MergeQuestionSnapshot(bankId = 11L, questionId = 102L, order = 1)
            )
        )

        val restored = requireNotNull(
            MergeSnapshotCodec.decode(MergeSnapshotCodec.encode(snapshot))
        )

        assertEquals(
            mapOf(101L to 11L, 201L to 22L, 102L to 11L),
            restored.questionOwners
        )
    }

    @Test
    fun `损坏或重复的题目归属快照不会被静默接受`() {
        assertTrue(MergeSnapshotCodec.decode("{}") == null)

        val duplicateQuestion = MergeSnapshot(
            banks = listOf(bank(1L, "甲")),
            questions = listOf(
                MergeQuestionSnapshot(1L, 9L, 0),
                MergeQuestionSnapshot(1L, 9L, 1)
            )
        )
        assertTrue(
            MergeSnapshotCodec.decode(MergeSnapshotCodec.encode(duplicateQuestion)) == null
        )
    }

    private fun bank(id: Long, name: String) = MergeBankSnapshot(
        id = id,
        name = name,
        sourceFileName = null,
        createdAt = 1L,
        updatedAt = 1L,
        pinned = false,
        archived = false,
        sortOrder = 0,
        accentColor = null,
        note = null,
        groupId = null,
        isGroup = false
    )
}
