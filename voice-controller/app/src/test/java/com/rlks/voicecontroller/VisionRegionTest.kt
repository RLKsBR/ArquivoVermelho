package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRegionTest {
    @Test
    fun derivesAndClampsShopRegion() {
        val line = TftLine(
            NormalizedPoint(0.02f, 0.9f),
            NormalizedPoint(0.98f, 0.9f)
        )
        val region = VisionRegion.shopFromLine(line)
        assertNotNull(region)
        assertEquals(0f, region!!.left, 0.0001f)
        assertEquals(1f, region.right, 0.0001f)
        assertTrue(region.top < 0.9f)
        assertEquals(1f, region.bottom, 0.0001f)
    }

    @Test
    fun derivesBoardRegionFromFourCalibratedRows() {
        val rows = (1..4).associateWith { rank ->
            TftRow(
                NormalizedPoint(0.2f, 0.25f + rank * 0.1f),
                NormalizedPoint(0.8f, 0.25f + rank * 0.1f)
            )
        }
        val region = VisionRegion.boardFromRows(rows)
        assertNotNull(region)
        assertTrue(region!!.left < 0.2f)
        assertTrue(region.right > 0.8f)
    }
}
