package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextScreenDetectorTest {
    @Test
    fun detectsAugmentChoice() {
        val lines = listOf(
            RecognizedTextLine(
                "Escolha um aprimoramento",
                NormalizedRect(0.3f, 0.05f, 0.7f, 0.12f)
            )
        )
        val result = ContextScreenDetector.detect(lines)
        assertEquals(AutoContextKind.CHOICES, result.single().kind)
        assertTrue(result.single().confidence >= 0.9f)
    }

    @Test
    fun detectsSidePanelsFromHeaders() {
        val lines = listOf(
            RecognizedTextLine("Sinergias", NormalizedRect(0.02f, 0.1f, 0.18f, 0.15f)),
            RecognizedTextLine("Inventário de itens", NormalizedRect(0.75f, 0.1f, 0.97f, 0.15f))
        )
        val result = ContextScreenDetector.detect(lines)
        assertEquals(2, result.size)
        assertTrue(result.first { it.kind == AutoContextKind.TRAITS }.region.right <= 0.5f)
        assertTrue(result.first { it.kind == AutoContextKind.ITEMS }.region.left >= 0.5f)
    }
}
