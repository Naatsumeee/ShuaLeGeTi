package com.examhelper.shua.data.model

import com.examhelper.shua.data.db.Question
import com.examhelper.shua.data.db.QuestionType

/** 选择题真正可点击的选项：普通题用自身选项，B1/B2 型题回退到共用选项。 */
object QuestionChoices {
    fun forQuestion(question: Question): List<QuestionOption> {
        val own = JsonCodec.decodeOptions(question.optionsJson)
        val normalized = if (question.type == QuestionType.JUDGE) {
            JudgeOptions.normalize(question.type, own, question.answer).first
        } else {
            own
        }
        return normalized.ifEmpty { JsonCodec.decodeOptions(question.sharedOptionsJson) }
    }

    fun usesShared(question: Question): Boolean =
        JsonCodec.decodeOptions(question.optionsJson).isEmpty() &&
            JsonCodec.decodeOptions(question.sharedOptionsJson).isNotEmpty()
}
