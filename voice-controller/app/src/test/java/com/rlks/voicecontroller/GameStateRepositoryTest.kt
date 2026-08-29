package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameStateRepositoryTest {
    @Test
    fun updateProducesNewImmutableState() {
        val repository = GameStateRepository()
        repository.update { it.copy(lastFrameAt = 42L) }
        assertEquals(42L, repository.get().lastFrameAt)
    }

    @Test
    fun leavingGameClearsDynamicState() {
        val repository = GameStateRepository(GameState(stageRound = ObservedValue(
            StageRound(2, 1), 1f, 1L, ObservationSource.OCR
        )))
        repository.clearDynamicState(5L)
        assertNull(repository.get().stageRound)
        assertEquals(GameScreen.UNKNOWN, repository.get().screen.value)
    }
}
