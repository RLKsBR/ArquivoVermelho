package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureControllerTest {
    @Test
    fun sellAndChoiceRequireConfirmation() {
        assertTrue(GestureController.isSensitive(VoiceCommand.SellBench(1)))
        assertTrue(GestureController.isSensitive(VoiceCommand.Choice(2)))
        assertFalse(GestureController.isSensitive(VoiceCommand.Reroll))
    }

    @Test
    fun movementRequiresOnlyItsOwnComponents() {
        assertEquals(
            setOf(CalibrationComponent.BENCH, CalibrationComponent.BOARD),
            GestureController.requiredComponents(VoiceCommand.BenchToBoard(1, "a1"))
        )
    }

    @Test
    fun partialShopCalibrationCannotAuthorizeBoardMovement() {
        assertFalse(
            GestureController.requiredComponents(VoiceCommand.BoardToBoard("a1", "b2"))
                .contains(CalibrationComponent.SHOP)
        )
    }
}
