package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNoticeDetectorTest {
    @Test
    fun explainsFullReserve() {
        val result = GameNoticeDetector.explain("Sua reserva está cheia")
        assertTrue(result!!.contains("reserva está cheia"))
    }

    @Test
    fun explainsFullItemSpace() {
        val result = GameNoticeDetector.explain("Não há espaço para o item")
        assertEquals("Não há espaço disponível para receber outro item.", result)
    }
}
