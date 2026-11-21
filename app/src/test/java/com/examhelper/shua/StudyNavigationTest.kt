package com.examhelper.shua

import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.ui.study.canMoveForward
import com.examhelper.shua.ui.study.shouldShowAnswerCard
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

    @Test
    fun answeredNeighborKeepsAnswerCardVisible() {
        assertTrue(
            shouldShowAnswerCard(
                interactive = false,
                answered = true,
                revealed = true
            )
        )
    }

    @Test
    fun unansweredNeighborDoesNotRevealAnswerCard() {
        assertFalse(
            shouldShowAnswerCard(
                interactive = false,
                answered = false,
                revealed = true
            )
        )
    }

    @Test
    fun reciteNeighborRevealsAnswerCardDuringTransition() {
        assertTrue(
            shouldShowAnswerCard(
                interactive = false,
                answered = false,
                revealed = true,
                mode = StudyMode.RECITE
            )
        )
    }
}
