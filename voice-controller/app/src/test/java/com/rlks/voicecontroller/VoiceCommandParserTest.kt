package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun parsesEnglishCoordinateMove() {
        assertEquals(VoiceCommand.ChessMove("e2", "e4"), VoiceCommandParser.parse("E two E four"))
    }

    @Test
    fun parsesPortugueseCoordinateMove() {
        assertEquals(VoiceCommand.ChessMove("g1", "f3"), VoiceCommandParser.parse("G um F três"))
    }

    @Test
    fun parsesCastle() {
        assertEquals(VoiceCommand.Castle(true), VoiceCommandParser.parse("roque pequeno"))
    }

    @Test
    fun parsesNamedPoint() {
        assertEquals(VoiceCommand.SetPoint("reroll"), VoiceCommandParser.parse("set reroll"))
    }

    @Test
    fun parsesNamedDrag() {
        assertEquals(
            VoiceCommand.DragPoint("bench one", "board back left"),
            VoiceCommandParser.parse("drag bench one to board back left")
        )
    }

    @Test
    fun mapsWhiteBoard() {
        val rect = BoardRect(0f, 0f, 1f, 1f)
        val a1 = ChessMapper.squareToPoint("a1", rect, true)!!
        assertTrue(a1.x < 0.2f)
        assertTrue(a1.y > 0.8f)
    }
}
