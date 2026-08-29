package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GameFlowDetectorTest {
    @Test
    fun detectsStageAndRoundNearTopOfScreen() {
        val lines = listOf(
            RecognizedTextLine("Estágio 3-2", NormalizedRect(0.45f, 0.03f, 0.55f, 0.09f))
        )
        assertEquals(StageRound(3, 2), GameFlowDetector.stageRound(lines))
    }

    @Test
    fun detectsAugmentOptionsAndTimer() {
        val lines = listOf(
            RecognizedTextLine("Escolha um aprimoramento", NormalizedRect(0.3f, 0.04f, 0.7f, 0.1f)),
            RecognizedTextLine("12 s", NormalizedRect(0.48f, 0.1f, 0.52f, 0.14f)),
            RecognizedTextLine("Força Bruta", NormalizedRect(0.08f, 0.3f, 0.25f, 0.37f)),
            RecognizedTextLine("Ganho de poder", NormalizedRect(0.39f, 0.3f, 0.61f, 0.37f)),
            RecognizedTextLine("Economia", NormalizedRect(0.73f, 0.3f, 0.9f, 0.37f))
        )
        val selection = GameFlowDetector.selection(lines)
        assertNotNull(selection)
        assertEquals(SelectionKind.AUGMENT, selection!!.kind)
        assertEquals(3, selection.options.size)
        assertEquals(12, selection.remainingSeconds)
    }

    @Test
    fun detectsAnvilAndComponentSelections() {
        val anvil = listOf(
            RecognizedTextLine("Escolha um item da Bigorna", NormalizedRect(0.3f, 0.04f, 0.7f, 0.1f))
        )
        val components = listOf(
            RecognizedTextLine("Escolha de componentes", NormalizedRect(0.3f, 0.04f, 0.7f, 0.1f))
        )
        assertEquals(SelectionKind.ARMORY, GameFlowDetector.selection(anvil)!!.kind)
        assertEquals(SelectionKind.COMPONENTS, GameFlowDetector.selection(components)!!.kind)
    }
}
