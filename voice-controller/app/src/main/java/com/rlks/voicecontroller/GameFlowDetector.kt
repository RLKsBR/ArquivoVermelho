package com.rlks.voicecontroller

data class StageRound(val stage: Int, val round: Int) {
    val key: String = "$stage-$round"
    fun announcement(): String = "Estágio $stage, rodada $round"
}

enum class SelectionKind(val announcement: String) {
    AUGMENT("Escolha de aprimoramento"),
    ARMORY("Escolha de item da bigorna"),
    COMPONENTS("Escolha de componentes")
}

data class SelectionOption(val index: Int, val text: String)

data class SelectionScreen(
    val kind: SelectionKind,
    val options: List<SelectionOption>,
    val remainingSeconds: Int?
) {
    val fingerprint: String = buildString {
        append(kind.name)
        options.forEach { append('|').append(ItemRecipeBook.normalize(it.text)) }
    }
}

object GameFlowDetector {
    fun stageRound(lines: List<RecognizedTextLine>): StageRound? {
        val candidates = lines.filter { line ->
            line.region.top <= 0.32f || "estagio" in ItemRecipeBook.normalize(line.text)
        }
        for (line in candidates) {
            val text = ItemRecipeBook.normalize(line.text)
            val match = Regex("(?:estágio|estagio)?\\s*([1-9])\\s*[-–—]\\s*([1-9])", RegexOption.IGNORE_CASE)
                .find(line.text)
                ?: Regex("^(?:estagio )?([1-9]) ([1-9])$").matchEntire(text)
                ?: continue
            val stage = match.groupValues[1].toInt()
            val round = match.groupValues[2].toInt()
            if (stage in 1..9 && round in 1..9) return StageRound(stage, round)
        }
        return null
    }

    fun selection(lines: List<RecognizedTextLine>): SelectionScreen? {
        if (lines.isEmpty()) return null
        val normalized = lines.map { it to ItemRecipeBook.normalize(it.text) }
        val combined = normalized.joinToString(" ") { it.second }
        val kind = when {
            listOf("aprimoramento", "augment").any { it in combined } -> SelectionKind.AUGMENT
            listOf("bigorna", "arsenal", "armory", "escolha um item", "selecione um item")
                .any { it in combined } -> SelectionKind.ARMORY
            listOf("escolha de componentes", "escolha componentes", "selecione componentes",
                "escolha varios componentes", "component anvil").any { it in combined } ->
                SelectionKind.COMPONENTS
            else -> return null
        }

        val content = normalized.filterNot { (line, text) ->
            isHeader(text) || isTimer(line, text) || line.region.top < 0.12f || line.region.bottom > 0.94f
        }
        val options = (0..2).mapNotNull { column ->
            val columnLines = content.filter { (line, _) ->
                val center = (line.region.left + line.region.right) / 2f
                center >= column / 3f && center < (column + 1) / 3f
            }.sortedBy { it.first.region.top }
                .map { it.first.text.trim() }
                .filter { it.length >= 2 }
                .distinctBy { ItemRecipeBook.normalize(it) }
            columnLines.takeIf { it.isNotEmpty() }?.let {
                SelectionOption(column + 1, it.joinToString(". ").take(700))
            }
        }
        return SelectionScreen(kind, options, remainingSeconds(normalized))
    }

    private fun remainingSeconds(lines: List<Pair<RecognizedTextLine, String>>): Int? {
        val explicit = lines.firstNotNullOfOrNull { (line, text) ->
            Regex("(?:tempo|restante|segundos?|s)\\s*:?\\s*(\\d{1,2})|(?:^|\\s)(\\d{1,2})\\s*(?:s|segundos?)")
                .find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                ?.toIntOrNull()?.takeIf { it in 0..60 }
        }
        if (explicit != null) return explicit
        return lines.firstNotNullOfOrNull { (line, text) ->
            if (line.region.top > 0.28f) return@firstNotNullOfOrNull null
            Regex("^([0-5]?\\d)$").matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun isHeader(text: String): Boolean = listOf(
        "escolha", "selecione", "aprimoramento", "augment", "bigorna", "arsenal", "armory",
        "componentes", "recompensa"
    ).any { it in text } && text.length < 80

    private fun isTimer(line: RecognizedTextLine, text: String): Boolean =
        line.region.top < 0.3f && (Regex("^\\d{1,2}$").matches(text) || "segundo" in text || "tempo" in text)
}
