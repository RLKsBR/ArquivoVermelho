package com.rlks.voicecontroller

import org.junit.Assert.assertTrue
import org.junit.Test

class ChessSpeechGrammarTest {
    @Test
    fun includesCriticalPawnAndKnightMoves() {
        val phrases = ChessSpeechGrammar.phrases().toHashSet()
        assertTrue("g two g four" in phrases)
        assertTrue("e two e four" in phrases)
        assertTrue("b one c three" in phrases)
    }

    @Test
    fun includesObservedAMisrecognitionInsideGrammar() {
        val phrases = ChessSpeechGrammar.phrases().toHashSet()
        assertTrue("eight two eight four" in phrases)
    }
}
