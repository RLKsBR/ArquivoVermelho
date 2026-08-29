package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test
    fun coordinatorAllowsOnlyOneActiveCapture() {
        val coordinator = CaptureCoordinator()
        assertTrue(coordinator.begin("monitor"))
        assertFalse(coordinator.begin("calibration"))
        assertEquals(1, coordinator.queueDepth())
        assertEquals("calibration", coordinator.finish())
        assertTrue(coordinator.begin("calibration"))
    }

    @Test
    fun duplicateCaptureReasonsAreCoalesced() {
        val coordinator = CaptureCoordinator()
        coordinator.begin("monitor")
        coordinator.begin("monitor")
        coordinator.begin("monitor")
        assertEquals(1, coordinator.queueDepth())
    }

    @Test
    fun cancelClearsCaptureQueue() {
        val coordinator = CaptureCoordinator()
        coordinator.begin("monitor")
        coordinator.begin("context")
        coordinator.cancelAll()
        assertFalse(coordinator.isBusy())
        assertEquals(0, coordinator.queueDepth())
    }

    @Test
    fun unchangedNormalScreenUsesSlowCadence() {
        assertEquals(4_500L, AdaptiveCapturePolicy.nextDelayMillis(true, false, false, 0))
        assertEquals(650L, AdaptiveCapturePolicy.nextDelayMillis(true, true, true, 0))
        assertEquals(Long.MAX_VALUE, AdaptiveCapturePolicy.nextDelayMillis(false, false, true, 0))
    }

    @Test
    fun fingerprintDetectsMaterialDifference() {
        val dark = IntArray(9 * 8) { if (it % 9 < 4) 0xff000000.toInt() else 0xffffffff.toInt() }
        val inverse = IntArray(9 * 8) { if (it % 9 < 4) 0xffffffff.toInt() else 0xff000000.toInt() }
        val first = FrameFingerprint.dHash(dark, 9, 8)
        val second = FrameFingerprint.dHash(inverse, 9, 8)
        assertTrue(FrameFingerprint.changed(first, second, 1))
        assertFalse(FrameFingerprint.changed(first, first, 1))
    }
}
