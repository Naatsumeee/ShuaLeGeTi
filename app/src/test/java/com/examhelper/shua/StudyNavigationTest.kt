package com.examhelper.shua

import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.ui.study.canMoveForward
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyNavigationTest {

    @Test
    fun answeredLastSequentialQuestionCanFinish() {
        assertTrue(
            canMoveForward(
                mode = StudyMode.SEQUENTIAL,
                answered = true,
                revealed = true,
                hasNext = false
            )
        )
    }

    @Test
    fun unansweredLastSequentialQuestionStaysBlocked() {
        assertFalse(
            canMoveForward(
                mode = StudyMode.SEQUENTIAL,
                answered = false,
                revealed = false,
                hasNext = false
            )
        )
    }

    @Test
    fun reciteAndRandomCanFinishTheirLastQuestion() {
        assertTrue(
            canMoveForward(
                mode = StudyMode.RECITE,
                answered = false,
                revealed = true,
                hasNext = false
            )
        )
        assertTrue(
            canMoveForward(
                mode = StudyMode.RANDOM,
                answered = true,
                revealed = true,
                hasNext = false
            )
        )
        assertTrue(
            canMoveForward(
                mode = StudyMode.SPEED,
                answered = true,
                revealed = true,
                hasNext = false
            )
        )
    }
}
