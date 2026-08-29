package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProfileTest {
    private val geometry = DisplayGeometry(2400, 1080, 1)

    @Test
    fun metadataIsScopedToItsOwnGeometry() {
        val metadata = metadata(CalibrationComponent.SHOP, geometry)
        assertTrue(metadata.isCurrent(geometry))
        assertFalse(metadata.isCurrent(DisplayGeometry(1920, 1080, 1)))
        assertFalse(metadata.isCurrent(DisplayGeometry(2400, 1080, 3)))
    }

    @Test
    fun legacyCoordinatesNeverAuthorizeGestures() {
        val metadata = metadata(CalibrationComponent.BOARD, geometry)
            .copy(source = CalibrationSource.LEGACY, confidence = 1f)
        assertFalse(metadata.isCurrent(geometry))
    }

    @Test
    fun partialNewProfileCannotValidateAnotherComponent() {
        val shop = metadata(CalibrationComponent.SHOP, DisplayGeometry(1920, 1080, 1))
        val board = metadata(CalibrationComponent.BOARD, geometry)
        assertTrue(shop.isCurrent(DisplayGeometry(1920, 1080, 1)))
        assertFalse(board.isCurrent(DisplayGeometry(1920, 1080, 1)))
    }

    @Test
    fun lowConfidenceCandidateIsBlocked() {
        assertFalse(metadata(CalibrationComponent.BENCH, geometry).copy(confidence = 0.7f).isCurrent(geometry))
    }

    @Test
    fun stabilityRequiresTwoCompatibleFrames() {
        val tracker = CalibrationStabilityTracker(requiredFrames = 2)
        assertEquals(1, tracker.observe(CalibrationComponent.SHOP, listOf(0.1f, 0.8f, 0.9f, 0.8f)))
        assertEquals(2, tracker.observe(CalibrationComponent.SHOP, listOf(0.11f, 0.8f, 0.89f, 0.8f)))
        assertTrue(tracker.isConfirmed(CalibrationComponent.SHOP))
    }

    @Test
    fun incompatibleFrameRollsBackPendingConfirmation() {
        val tracker = CalibrationStabilityTracker(requiredFrames = 2)
        tracker.observe(CalibrationComponent.CHOICES, listOf(0.1f, 0.1f, 0.9f, 0.9f))
        assertEquals(1, tracker.observe(CalibrationComponent.CHOICES, listOf(0.3f, 0.1f, 0.9f, 0.9f)))
        assertFalse(tracker.isConfirmed(CalibrationComponent.CHOICES))
    }

    @Test
    fun migrationMarksOnlyUnversionedExistingComponents() {
        val result = LegacyCalibrationMigration.componentsToMarkLegacy(
            true,
            setOf(CalibrationComponent.BOARD, CalibrationComponent.SHOP),
            setOf(CalibrationComponent.SHOP)
        )
        assertEquals(setOf(CalibrationComponent.BOARD), result)
    }

    private fun metadata(component: CalibrationComponent, geometry: DisplayGeometry) = CalibrationMetadata(
        component, geometry, CalibrationSource.MANUAL, 0.99f, 1L, geometry.profileId()
    )
}
