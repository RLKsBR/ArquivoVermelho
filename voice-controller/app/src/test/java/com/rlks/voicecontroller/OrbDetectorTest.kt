package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbDetectorTest {
    @Test
    fun findsBrightColoredCompactOrbAndIgnoresDarkBackground() {
        val width = 100
        val height = 60
        val pixels = IntArray(width * height) { 0xff101318.toInt() }
        for (y in 25..31) for (x in 46..52) {
            pixels[y * width + x] = 0xff5ff5ff.toInt()
        }
        val points = OrbDetector.detect(pixels, width, height, NormalizedRect(0f, 0f, 1f, 1f))
        assertEquals(1, points.size)
        assertTrue(points.single().x in 0.45f..0.54f)
        assertTrue(points.single().y in 0.4f..0.56f)
    }
}
