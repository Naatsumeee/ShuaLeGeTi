package com.examhelper.shua.data.analysis

import com.examhelper.shua.data.db.Question

enum class AnalysisLevel {
    DEEP,
    MINIMAL
}

data class PdfAnalysisPlan(
    val targets: List<Question>,
    val existingCount: Int
) {
    companion object {
        fun create(
            questions: List<Question>,
            level: AnalysisLevel,
            skipExisting: Boolean
        ): PdfAnalysisPlan {
            val existing = questions.filter { hasAnalysis(it, level) }
            return PdfAnalysisPlan(
                targets = if (skipExisting) questions.filterNot { hasAnalysis(it, level) } else questions,
                existingCount = existing.size
            )
        }

        fun hasAnalysis(question: Question, level: AnalysisLevel): Boolean =
            !question.analysis.isNullOrBlank() && when (level) {
                AnalysisLevel.DEEP -> question.deepSolved
                AnalysisLevel.MINIMAL -> question.minimalSolved
            }
    }
}
