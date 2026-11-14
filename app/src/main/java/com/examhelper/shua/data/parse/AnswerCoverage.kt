package com.examhelper.shua.data.parse

import com.examhelper.shua.data.model.ParsedLine

data class AnswerCoverage(
    val total: Int,
    val answered: Int,
    val lowCoverageChapters: List<String>
) {
    companion object {
        fun audit(questions: List<ParsedLine>): AnswerCoverage {
            val grouped = questions.groupBy { it.chapter ?: "未分章" }
            val low = grouped.mapNotNull { (chapter, items) ->
                if (items.size < 5) return@mapNotNull null
                val answered = items.count { !it.answer.isNullOrBlank() }
                chapter.takeIf { answered.toDouble() / items.size < 0.2 }
            }
            return AnswerCoverage(
                total = questions.size,
                answered = questions.count { !it.answer.isNullOrBlank() },
                lowCoverageChapters = low
            )
        }
    }
}
