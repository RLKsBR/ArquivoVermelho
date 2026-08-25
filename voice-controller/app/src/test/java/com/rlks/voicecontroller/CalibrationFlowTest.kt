package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationFlowTest {
    @Test
    fun guidedStagesAdvanceInOrder() {
        assertEquals("items", CalibrationFlow.next("core"))
        assertEquals("traits", CalibrationFlow.next("items"))
        assertEquals("choices", CalibrationFlow.next("traits"))
        assertEquals("none", CalibrationFlow.next("choices"))
    }
}

