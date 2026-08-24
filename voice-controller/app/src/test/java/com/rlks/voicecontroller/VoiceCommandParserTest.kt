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
    fun parsesCompactCoordinateMove() {
        assertEquals(VoiceCommand.ChessMove("d2", "d4"), VoiceCommandParser.parse("D2D4"))
        assertEquals(VoiceCommand.ChessMove("b1", "c3"), VoiceCommandParser.parse("B1C3"))
        assertEquals(VoiceCommand.ChessMove("b8", "c6"), VoiceCommandParser.parse("B8 C6"))
    }

    @Test
    fun parsesCollapsedSameFileCoordinates() {
        assertEquals(VoiceCommand.ChessMove("e7", "e5"), VoiceCommandParser.parse("E75"))
        assertEquals(VoiceCommand.ChessMove("d7", "d5"), VoiceCommandParser.parse("D 75"))
        assertEquals(VoiceCommand.ChessMove("e7", "e5"), VoiceCommandParser.parse("E seven five"))
    }

    @Test
    fun parsesEnglishLetterMisrecognition() {
        assertEquals(VoiceCommand.ChessMove("e7", "e5"), VoiceCommandParser.parse("he seven he five"))
        assertEquals(VoiceCommand.ChessMove("d7", "d5"), VoiceCommandParser.parse("the seven the five"))
    }

    @Test
    fun parsesPortugueseSpokenLetterNames() {
        assertEquals(VoiceCommand.ChessMove("d2", "d4"), VoiceCommandParser.parse("dê dois dê quatro"))
        assertEquals(VoiceCommand.ChessMove("b1", "c3"), VoiceCommandParser.parse("bê um cê três"))
    }

    @Test
    fun parsesCommonEnglishRecognitionConfusions() {
        assertEquals(VoiceCommand.ChessMove("d2", "d4"), VoiceCommandParser.parse("dee to dee for"))
    }

    @Test
    fun choosesParsableSpeechAlternative() {
        val parsed = VoiceCommandParser.parseAlternatives(listOf("D two before", "D2D4", "D two D four"))
        assertEquals("D2D4", parsed?.first)
        assertEquals(VoiceCommand.ChessMove("d2", "d4"), parsed?.second)
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
