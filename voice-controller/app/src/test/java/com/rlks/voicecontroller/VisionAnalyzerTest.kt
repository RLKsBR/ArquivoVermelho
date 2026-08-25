package com.rlks.voicecontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionAnalyzerTest {
    @Test
    fun blackImageIsRejected() {
        assertFalse(VisionAnalyzer.analyze(IntArray(400) { 0xff000000.toInt() }).isUsable)
    }

    @Test
    fun variedImageIsAccepted() {
        val pixels = IntArray(400) { index ->
            if (index % 2 == 0) 0xff101020.toInt() else 0xffe0c060.toInt()
        }
        assertTrue(VisionAnalyzer.analyze(pixels).isUsable)
    }
}

