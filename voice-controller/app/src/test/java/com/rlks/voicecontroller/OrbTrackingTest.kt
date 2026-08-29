package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbTrackingTest {
    @Test
    fun candidateMustExistInTwoFrames() {
        val confirmed = OrbTemporalTracker.confirm(
            listOf(
                listOf(NormalizedPoint(0.3f, 0.4f), NormalizedPoint(0.8f, 0.2f)),
                listOf(NormalizedPoint(0.31f, 0.41f))
            )
        )
        assertEquals(1, confirmed.size)
        assertTrue(confirmed.single().confidence >= 0.9f)
    }

    @Test
    fun transientBrightObjectIsRejected() {
        val confirmed = OrbTemporalTracker.confirm(
            listOf(listOf(NormalizedPoint(0.3f, 0.4f)), emptyList())
        )
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun movedCandidateOutsideToleranceIsRejected() {
        val confirmed = OrbTemporalTracker.confirm(
            listOf(listOf(NormalizedPoint(0.2f, 0.2f)), listOf(NormalizedPoint(0.7f, 0.7f)))
        )
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun disappearanceCanBeVerified() {
        assertNull(OrbTemporalTracker.nearest(NormalizedPoint(0.4f, 0.4f), emptyList()))
    }
}
