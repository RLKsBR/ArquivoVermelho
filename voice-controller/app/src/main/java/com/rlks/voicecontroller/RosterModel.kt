package com.rlks.voicecontroller

enum class ItemStatus {
    NONE,
    EQUIPPED,
    UNKNOWN
}

enum class TacticalRole {
    FRONTLINE,
    BACKLINE,
    UNKNOWN
}

enum class HealthFilter {
    ALL,
    WITHOUT_ITEMS,
    WITH_ITEMS
}

data class ChampionObservation(
    val name: String,
    val maxHealth: Int,
    val value: Int,
    val itemStatus: ItemStatus,
    val role: TacticalRole
)

object RosterAnalyzer {
    fun highestHealth(
        champions: List<ChampionObservation>,
        filter: HealthFilter
    ): List<ChampionObservation> {
        val candidates = champions.filter {
            when (filter) {
                HealthFilter.ALL -> true
                HealthFilter.WITHOUT_ITEMS -> it.itemStatus == ItemStatus.NONE
                HealthFilter.WITH_ITEMS -> it.itemStatus == ItemStatus.EQUIPPED
            }
        }
        val maximum = candidates.maxOfOrNull { it.maxHealth } ?: return emptyList()
        return candidates.filter { it.maxHealth == maximum }
    }

    fun highestValue(champions: List<ChampionObservation>): List<ChampionObservation> {
        val maximum = champions.maxOfOrNull { it.value } ?: return emptyList()
        return champions.filter { it.value == maximum }
    }

    fun byRole(
        champions: List<ChampionObservation>,
        role: TacticalRole
    ): List<ChampionObservation> = champions.filter { it.role == role }
}
