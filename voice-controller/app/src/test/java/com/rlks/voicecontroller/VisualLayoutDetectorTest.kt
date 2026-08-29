package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualLayoutDetectorTest {
    @Test
    fun detectsSyntheticLandscapeGridCandidate() {
        val width = 240
        val height = 120
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        fun horizontal(y: Int) {
            for (x in 20 until 220) pixels[y * width + x] = 0xffffffff.toInt()
        }
        listOf(30, 42, 54, 66, 82).forEach(::horizontal)
        for (y in 28..82) {
            pixels[y * width + 24] = 0xffffffff.toInt()
            pixels[y * width + 216] = 0xffffffff.toInt()
        }
        val result = VisualLayoutDetector.detect(
            pixels,
            width,
            height,
            TftLine(NormalizedPoint(0.12f, 0.92f), NormalizedPoint(0.88f, 0.92f))
        )
        assertNotNull(result.boardRows)
        assertNotNull(result.benchLine)
        assertEquals(4, result.boardRows?.size)
        assertTrue(result.confidence >= CalibrationMetadata.MIN_GESTURE_CONFIDENCE)
    }

    @Test
    fun rejectsPortraitImage() {
        val result = VisualLayoutDetector.detect(IntArray(80 * 120), 80, 120, null)
        assertEquals(0f, result.confidence)
    }
}
