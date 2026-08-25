package com.rlks.voicecontroller

enum class AutoContextKind(
    val regionName: String,
    val spokenName: String
) {
    CHOICES(ProfileStore.REGION_CHOICES, "escolhas ou aprimoramentos"),
    ITEMS(ProfileStore.REGION_ITEMS, "inventário de itens"),
    TRAITS(ProfileStore.REGION_TRAITS, "sinergias")
}

data class ContextDetection(
    val kind: AutoContextKind,
    val region: NormalizedRect,
    val confidence: Float
)

object ContextScreenDetector {
    fun detect(lines: List<RecognizedTextLine>): List<ContextDetection> {
        if (lines.isEmpty()) return emptyList()
        val normalized = lines.map { it to ItemRecipeBook.normalize(it.text) }
        val combined = normalized.joinToString(" ") { it.second }
        val detections = mutableListOf<ContextDetection>()

        val choiceMarker = normalized.firstOrNull { (_, text) ->
            listOf(
                "escolha um aprimoramento", "selecione um aprimoramento",
                "escolha um item", "selecione um item", "escolha uma recompensa",
                "augment", "armory", "arsenal"
            ).any { it in text }
        }
        if (choiceMarker != null || listOf(
                "escolha um aprimoramento", "selecione um aprimoramento",
                "escolha um item", "selecione um item"
            ).any { it in combined }
        ) {
            detections += ContextDetection(
                AutoContextKind.CHOICES,
                NormalizedRect(0.07f, 0.16f, 0.93f, 0.86f),
                if (choiceMarker != null) 0.98f else 0.9f
            )
        }

        val itemMarker = normalized.firstOrNull { (_, text) ->
            text == "inventario" || "inventario de itens" in text || "itens disponiveis" in text
        }
        if (itemMarker != null) {
            detections += ContextDetection(
                AutoContextKind.ITEMS,
                sidePanel(itemMarker.first.region),
                0.92f
            )
        }

        val traitMarker = normalized.firstOrNull { (_, text) ->
            text == "sinergias" || text == "caracteristicas" || text == "traits"
        }
        if (traitMarker != null) {
            detections += ContextDetection(
                AutoContextKind.TRAITS,
                sidePanel(traitMarker.first.region),
                0.9f
            )
        }
        return detections.distinctBy { it.kind }
    }

    private fun sidePanel(marker: NormalizedRect): NormalizedRect {
        val center = (marker.left + marker.right) / 2f
        return if (center < 0.5f) {
            NormalizedRect(0f, 0.05f, 0.42f, 0.95f)
        } else {
            NormalizedRect(0.58f, 0.05f, 1f, 0.95f)
        }
    }
}
