package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun parsesShopCommands() {
        assertEquals(VoiceCommand.Reroll, VoiceCommandParser.parse("rolar"))
        assertEquals(VoiceCommand.BuyXp, VoiceCommandParser.parse("subir nível"))
        assertEquals(VoiceCommand.BuySlots(listOf(3)), VoiceCommandParser.parse("comprar três"))
        assertEquals(VoiceCommand.BuySlots(listOf(1, 3, 5)), VoiceCommandParser.parse("comprar um três e cinco"))
    }

    @Test
    fun parsesBenchToBoardDrag() {
        assertEquals(
            VoiceCommand.BenchToBoard(2, "d4"),
            VoiceCommandParser.parse("banco dois para D4")
        )
    }

    @Test
    fun parsesBoardToBenchDrag() {
        assertEquals(
            VoiceCommand.BoardToBench("d4", 3),
            VoiceCommandParser.parse("D4 para banco três")
        )
    }

    @Test
    fun parsesBoardToBoardDragWithSpokenSquare() {
        assertEquals(
            VoiceCommand.BoardToBoard("c3", "f4"),
            VoiceCommandParser.parse("c três para efe quatro")
        )
    }

    @Test
    fun parsesSellAndChoice() {
        assertEquals(VoiceCommand.SellBench(2), VoiceCommandParser.parse("vender banco dois"))
        assertEquals(VoiceCommand.SellBoard("d2"), VoiceCommandParser.parse("vender D2"))
        assertEquals(VoiceCommand.Choice(2), VoiceCommandParser.parse("aprimoramento dois"))
    }

    @Test
    fun parsesScreenReadingCommands() {
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.SHOP),
            VoiceCommandParser.parse("ler loja")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.ITEMS),
            VoiceCommandParser.parse("quais itens")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.TRAITS),
            VoiceCommandParser.parse("ler sinergias")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.CHOICES),
            VoiceCommandParser.parse("ler aprimoramentos")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.FULL_SCREEN),
            VoiceCommandParser.parse("ler orbes")
        )
        assertEquals(VoiceCommand.RepeatLastRead, VoiceCommandParser.parse("repetir leitura"))
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.NOTICE),
            VoiceCommandParser.parse("por que não pegou")
        )
    }

    @Test
    fun parsesRosterFactsAndQueries() {
        assertEquals(
            VoiceCommand.RecordChampion(
                ChampionObservation(
                    "Indigena",
                    1800,
                    4,
                    ItemStatus.EQUIPPED,
                    TacticalRole.FRONTLINE
                )
            ),
            VoiceCommandParser.parse(
                "registrar indígena vida 1800 valor quatro com item frontline"
            )
        )
        assertEquals(
            VoiceCommand.HighestHealth(HealthFilter.WITHOUT_ITEMS),
            VoiceCommandParser.parse("qual boneco com maior vida sem item")
        )
        assertEquals(
            VoiceCommand.HighestValue,
            VoiceCommandParser.parse("quais os bonecos de maior valor")
        )
        assertEquals(
            VoiceCommand.ListRole(TacticalRole.FRONTLINE),
            VoiceCommandParser.parse("listar frontline")
        )
    }

    @Test
    fun parsesItemKnowledgeAndAccessibleReadCommands() {
        assertEquals(
            VoiceCommand.ItemRecipeQuery("gume do infinito"),
            VoiceCommandParser.parse("quais componentes fazem o Gume do Infinito")
        )
        assertEquals(
            VoiceCommand.ItemsFromComponent("arco"),
            VoiceCommandParser.parse("o que faz com arco")
        )
        assertEquals(VoiceCommand.ListItemCatalog, VoiceCommandParser.parse("quais itens existem"))
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.BOARD),
            VoiceCommandParser.parse("ler tabuleiro")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.INVENTORY),
            VoiceCommandParser.parse("ler inventário")
        )
        assertEquals(
            VoiceCommand.ReadScreen(ScreenReadTarget.CAROUSEL),
            VoiceCommandParser.parse("ler carrossel")
        )
        assertEquals(
            VoiceCommand.NonSynergyChampions,
            VoiceCommandParser.parse("quem não faz parte das sinergias")
        )
        assertEquals(
            VoiceCommand.RecordActiveSynergies(setOf("bastiao", "arcana")),
            VoiceCommandParser.parse("registrar sinergias ativas bastião e arcana")
        )
    }

    @Test
    fun mapsTftBoardGeometry() {
        val rows = mapOf(
            1 to TftRow(NormalizedPoint(0.1f, 0.8f), NormalizedPoint(0.9f, 0.8f)),
            2 to TftRow(NormalizedPoint(0.12f, 0.7f), NormalizedPoint(0.88f, 0.7f)),
            3 to TftRow(NormalizedPoint(0.1f, 0.6f), NormalizedPoint(0.9f, 0.6f)),
            4 to TftRow(NormalizedPoint(0.12f, 0.5f), NormalizedPoint(0.88f, 0.5f))
        )
        val a1 = TftLayout.boardSquareToPoint("a1", rows)
        val g4 = TftLayout.boardSquareToPoint("g4", rows)
        assertNotNull(a1)
        assertNotNull(g4)
        assertTrue(a1!!.x < g4!!.x)
        assertTrue(a1.y > g4.y)
    }

    @Test
    fun mapsBenchAndShopEndpoints() {
        val line = TftLine(NormalizedPoint(0.1f, 0.9f), NormalizedPoint(0.9f, 0.9f))
        assertEquals(0.1f, TftLayout.benchSlotToPoint(1, line)!!.x, 0.0001f)
        assertEquals(0.9f, TftLayout.benchSlotToPoint(9, line)!!.x, 0.0001f)
        assertEquals(0.5f, TftLayout.shopSlotToPoint(3, line)!!.x, 0.0001f)
    }
}
