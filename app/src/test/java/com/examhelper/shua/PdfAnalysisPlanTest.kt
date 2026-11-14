package com.examhelper.shua

import com.examhelper.shua.data.analysis.AnalysisLevel
import com.examhelper.shua.data.analysis.PdfAnalysisPlan
import com.examhelper.shua.data.analysis.PdfAnalysisWorker
import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfAnalysisPlanTest {

    @Test
    fun `跳过已有解析只选择缺失深度解析的题`() {
        val questions = listOf(
            question(1, analysis = "已有", deepSolved = true),
            question(2, analysis = "普通解析", deepSolved = false),
            question(3, analysis = null, deepSolved = false)
        )

        val plan = PdfAnalysisPlan.create(questions, AnalysisLevel.DEEP, skipExisting = true)

        assertEquals(1, plan.existingCount)
        assertEquals(listOf(2L, 3L), plan.targets.map { it.id })
    }

    @Test
    fun `仍重新生成时包含所有选中题目`() {
        val questions = listOf(
            question(1, analysis = "已有", minimalSolved = true),
            question(2, analysis = null, minimalSolved = false)
        )

        val plan = PdfAnalysisPlan.create(questions, AnalysisLevel.MINIMAL, skipExisting = false)

        assertEquals(1, plan.existingCount)
        assertEquals(listOf(1L, 2L), plan.targets.map { it.id })
    }

    @Test
    fun `极简解析使用足够输出预算`() {
        assertEquals(512, PdfAnalysisWorker.MINIMAL_MAX_TOKENS)
    }

    private fun question(
        id: Long,
        analysis: String?,
        deepSolved: Boolean = false,
        minimalSolved: Boolean = false
    ) = Question(
        id = id,
        bankId = 1,
        orderInBank = id.toInt(),
        type = QuestionType.SINGLE,
        stem = "题 $id",
        analysis = analysis,
        deepSolved = deepSolved,
        minimalSolved = minimalSolved
    )
}
