package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class RosterAnalyzerTest {
    private val champions = listOf(
        ChampionObservation("A", 1800, 4, ItemStatus.EQUIPPED, TacticalRole.FRONTLINE),
        ChampionObservation("B", 1800, 5, ItemStatus.NONE, TacticalRole.FRONTLINE),
        ChampionObservation("C", 1200, 5, ItemStatus.NONE, TacticalRole.BACKLINE)
    )

    @Test
    fun returnsAllHealthTiesAndRespectsItemFilter() {
        assertEquals(
            listOf("A", "B"),
            RosterAnalyzer.highestHealth(champions, HealthFilter.ALL).map { it.name }
        )
        assertEquals(
            listOf("B"),
            RosterAnalyzer.highestHealth(champions, HealthFilter.WITHOUT_ITEMS).map { it.name }
        )
        assertEquals(
            listOf("A"),
            RosterAnalyzer.highestHealth(champions, HealthFilter.WITH_ITEMS).map { it.name }
        )
    }

    @Test
    fun returnsAllHighestValueTies() {
        assertEquals(
            listOf("B", "C"),
            RosterAnalyzer.highestValue(champions).map { it.name }
        )
    }

    @Test
    fun findsChampionsOutsideActiveSynergiesWithoutRecommendingActions() {
        val observed = listOf(
            ChampionObservation(
                "A", 1000, 1, ItemStatus.NONE, TacticalRole.FRONTLINE,
                setOf("Bastião", "Arcana")
            ),
            ChampionObservation(
                "B", 900, 2, ItemStatus.NONE, TacticalRole.BACKLINE,
                setOf("Feérico")
            )
        )
        assertEquals(
            listOf("B"),
            RosterAnalyzer.outsideActiveSynergies(observed, setOf("Bastião")).map { it.name }
        )
    }
}
