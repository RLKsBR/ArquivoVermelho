package com.rlks.voicecontroller

import java.text.Normalizer

enum class ScreenReadTarget(val spokenName: String) {
    SHOP("Loja"),
    ITEMS("Itens"),
    TRAITS("Sinergias"),
    CHOICES("Escolhas"),
    BOARD("Tabuleiro"),
    INVENTORY("Inventário"),
    CAROUSEL("Carrossel"),
    NOTICE("Aviso"),
    FULL_SCREEN("Tela")
}

sealed class VoiceCommand {
    data class BuySlots(val slots: List<Int>) : VoiceCommand()
    data object Reroll : VoiceCommand()
    data object BuyXp : VoiceCommand()
    data object ToggleShop : VoiceCommand()
    data class BenchToBoard(val bench: Int, val square: String) : VoiceCommand()
    data class BoardToBench(val square: String, val bench: Int) : VoiceCommand()
    data class BoardToBoard(val from: String, val to: String) : VoiceCommand()
    data class BenchToTactical(val bench: Int, val position: TacticalPosition) : VoiceCommand()
    data class BoardToTactical(val square: String, val position: TacticalPosition) : VoiceCommand()
    data class SellBench(val bench: Int) : VoiceCommand()
    data class SellBoard(val square: String) : VoiceCommand()
    data class Choice(val index: Int) : VoiceCommand()
    data class ReadChoice(val index: Int) : VoiceCommand()
    data object StopReading : VoiceCommand()
    data object CollectOrbs : VoiceCommand()
    data object AutoCalibrate : VoiceCommand()
    data class ReadScreen(val target: ScreenReadTarget) : VoiceCommand()
    data object RepeatLastRead : VoiceCommand()
    data class RecordChampion(val observation: ChampionObservation) : VoiceCommand()
    data class HighestHealth(val filter: HealthFilter) : VoiceCommand()
    data object HighestValue : VoiceCommand()
    data class ListRole(val role: TacticalRole) : VoiceCommand()
    data class ItemRecipeQuery(val itemName: String) : VoiceCommand()
    data class ItemsFromComponent(val componentName: String) : VoiceCommand()
    data object ListItemCatalog : VoiceCommand()
    data class RecordActiveSynergies(val names: Set<String>) : VoiceCommand()
    data object NonSynergyChampions : VoiceCommand()
    data object ClearRoster : VoiceCommand()
    data object PauseControl : VoiceCommand()
    data object ResumeControl : VoiceCommand()
    data object Help : VoiceCommand()
    data class Unknown(val raw: String) : VoiceCommand()
}

object VoiceCommandParser {
    private val numbers = mapOf(
        "1" to 1, "um" to 1, "uma" to 1,
        "2" to 2, "dois" to 2, "duas" to 2,
        "3" to 3, "tres" to 3,
        "4" to 4, "quatro" to 4,
        "5" to 5, "cinco" to 5,
        "6" to 6, "seis" to 6,
        "7" to 7, "sete" to 7,
        "8" to 8, "oito" to 8,
        "9" to 9, "nove" to 9
    )

    private val files = mapOf(
        "a" to "a", "ah" to "a", "ha" to "a",
        "b" to "b", "be" to "b",
        "c" to "c", "ce" to "c",
        "d" to "d", "de" to "d",
        "e" to "e",
        "f" to "f", "efe" to "f",
        "g" to "g", "ge" to "g"
    )

    fun parse(raw: String): VoiceCommand {
        val text = reinterpret(normalize(raw))
        if (text.isBlank()) return VoiceCommand.Unknown(raw)

        if (text in setOf("parar", "parar leitura", "pare", "silencio", "calar")) {
            return VoiceCommand.StopReading
        }
        if (text in setOf(
                "pegar orbe", "pegar orbes", "coletar orbe", "coletar orbes",
                "buscar orbes", "recolher orbes"
            )
        ) return VoiceCommand.CollectOrbs
        if (text in setOf("auto calibrar", "autocalibrar", "calibrar automaticamente", "melhorar calibracao")) {
            return VoiceCommand.AutoCalibrate
        }
        if (text in setOf("ajuda", "comandos", "help")) return VoiceCommand.Help
        if (text in setOf("rolar", "rerrolar", "reroll", "rerolar")) return VoiceCommand.Reroll
        if (text in setOf("xp", "comprar xp", "subir nivel", "upar nivel", "nivel")) return VoiceCommand.BuyXp
        if (text in setOf("abrir loja", "fechar loja", "loja")) return VoiceCommand.ToggleShop
        if (text in setOf("pausar controle", "parar controle", "pausa")) return VoiceCommand.PauseControl
        if (text in setOf("retomar controle", "ativar controle", "continuar controle", "retomar")) return VoiceCommand.ResumeControl
        if (text in setOf("repetir leitura", "repetir ultima leitura", "ler novamente")) {
            return VoiceCommand.RepeatLastRead
        }
        parseRecordChampion(text)?.let { return it }
        parseKnowledgeQuery(text)?.let { return it }
        parseRosterQuery(text)?.let { return it }
        parseReadChoice(text)?.let { return it }
        parseReadScreen(text)?.let { return it }

        if (text.startsWith("comprar ")) {
            val slots = text.removePrefix("comprar ")
                .replace(" e ", " ")
                .split(' ')
                .mapNotNull { numbers[it] }
                .filter { it in 1..5 }
                .distinct()
            if (slots.isNotEmpty()) return VoiceCommand.BuySlots(slots)
        }

        parseChoice(text)?.let { return it }
        parseSell(text)?.let { return it }
        parseMovement(text)?.let { return it }
        return VoiceCommand.Unknown(raw)
    }

    fun parseAlternatives(candidates: List<String>): Pair<String, VoiceCommand>? {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        for (candidate in cleaned) {
            val command = parse(candidate)
            if (command !is VoiceCommand.Unknown) return candidate to command
        }
        return cleaned.first() to VoiceCommand.Unknown(cleaned.first())
    }

    fun canonicalName(raw: String): String = normalize(raw)

    fun reinterpret(raw: String): String {
        val normalized = normalize(raw)
        val phraseAliases = mapOf(
            "para" to "parar",
            "pare de ler" to "parar leitura",
            "para de ler" to "parar leitura",
            "calar a boca" to "calar",
            "pega os orbes" to "pegar orbes",
            "pegue os orbes" to "pegar orbes",
            "pegar os orbes" to "pegar orbes",
            "coleta os orbes" to "coletar orbes",
            "carro cel" to "carrossel"
        )
        phraseAliases[normalized]?.let { return it }
        val shortAliases = mapOf(
            "le" to "ler", "lei" to "ler", "lerh" to "ler",
            "carrocel" to "carrossel", "carocel" to "carrossel",
            "orbis" to "orbes", "orb" to "orbe", "branco" to "banco",
            "rerola" to "rerolar", "rerole" to "rerolar",
            "aprimorament" to "aprimoramento", "augmento" to "augment"
        )
        val vocabulary = setOf(
            "comprar", "vender", "banco", "aprimoramento", "carrossel", "orbes",
            "parar", "rolar", "itens", "sinergias", "inventario", "tabuleiro",
            "escolha", "registrar", "repetir", "coletar"
        )
        return normalized.split(' ').joinToString(" ") { token ->
            shortAliases[token] ?: vocabulary.firstOrNull { candidate ->
                token.length >= 5 && kotlin.math.abs(token.length - candidate.length) <= 1 &&
                    editDistance(token, candidate) <= 1
            } ?: token
        }
    }

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (i in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = i + 1
            for (j in second.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (first[i] == second[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun parseReadScreen(text: String): VoiceCommand? {
        val target = when (text) {
            "ler loja", "leia a loja", "o que tem na loja", "quais campeoes na loja" -> ScreenReadTarget.SHOP
            "ler itens", "leia os itens", "quais itens", "itens disponiveis" -> ScreenReadTarget.ITEMS
            "ler sinergias", "leia as sinergias", "quais sinergias", "ler traits" -> ScreenReadTarget.TRAITS
            "ler escolhas", "ler aprimoramentos", "ler augments", "quais escolhas",
            "ler opcoes", "ler itens para escolher", "ler itens da selecao" -> ScreenReadTarget.CHOICES
            "ler tabuleiro", "quem esta no tabuleiro", "ler board",
            "ler campeoes no tabuleiro", "ler itens no tabuleiro" -> ScreenReadTarget.BOARD
            "ler inventario", "ler componentes no inventario", "itens no inventario" -> ScreenReadTarget.INVENTORY
            "ler carrossel", "resumir carrossel", "quais itens no carrossel",
            "quais campeoes no carrossel" -> ScreenReadTarget.CAROUSEL
            "ler aviso", "ler mensagem", "o que apareceu", "por que nao pegou",
            "por que nao foi", "reserva cheia" -> ScreenReadTarget.NOTICE
            "ler tela", "leia a tela", "o que tem na tela", "ler recompensas",
            "ler orbe", "ler orbes", "ler campeoes adquiridos",
            "ler campeoes ganhos" -> ScreenReadTarget.FULL_SCREEN
            else -> null
        }
        return target?.let { VoiceCommand.ReadScreen(it) }
    }

    private fun parseReadChoice(text: String): VoiceCommand? {
        val match = Regex("^(?:ler|leia) (?:(?:a )?(?:opcao|escolha|aprimoramento|item) )?(.+)$")
            .matchEntire(text) ?: return null
        val index = numbers[match.groupValues[1]] ?: return null
        return if (index in 1..9) VoiceCommand.ReadChoice(index) else null
    }

    private fun parseRecordChampion(text: String): VoiceCommand? {
        val match = Regex(
            "^(?:registrar|atualizar)(?: campeao| boneco)? (.+?) vida(?: maxima)? (\\d{2,5}) valor ([a-z0-9]+)(?: (com (?:item|itens)|sem (?:item|itens)))?(?: (frontline|front line|linha de frente|backline|back line|linha de tras))?(?: sinergias (.+))?$"
        ).matchEntire(text) ?: return null
        val name = match.groupValues[1]
            .split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        val health = match.groupValues[2].toIntOrNull() ?: return null
        val valueToken = match.groupValues[3]
        val value = valueToken.toIntOrNull() ?: numbers[valueToken] ?: return null
        if (health <= 0 || value <= 0) return null
        val itemStatus = when (match.groupValues[4]) {
            "com item", "com itens" -> ItemStatus.EQUIPPED
            "sem item", "sem itens" -> ItemStatus.NONE
            else -> ItemStatus.UNKNOWN
        }
        val role = when (match.groupValues[5]) {
            "frontline", "front line", "linha de frente" -> TacticalRole.FRONTLINE
            "backline", "back line", "linha de tras" -> TacticalRole.BACKLINE
            else -> TacticalRole.UNKNOWN
        }
        val traits = match.groupValues[6]
            .split(Regex("\\s+e\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return VoiceCommand.RecordChampion(
            ChampionObservation(name, health, value, itemStatus, role, traits)
        )
    }

    private fun parseRosterQuery(text: String): VoiceCommand? {
        if (text in setOf("limpar time", "limpar campeoes", "apagar time")) {
            return VoiceCommand.ClearRoster
        }
        if ("maior vida" in text || "mais vida" in text) {
            val filter = when {
                "sem item" in text || "sem itens" in text -> HealthFilter.WITHOUT_ITEMS
                "com item" in text || "com itens" in text -> HealthFilter.WITH_ITEMS
                else -> HealthFilter.ALL
            }
            return VoiceCommand.HighestHealth(filter)
        }
        if ("maior valor" in text || "vale mais" in text || "valem mais" in text) {
            return VoiceCommand.HighestValue
        }
        if (text in setOf(
                "quem nao faz parte das sinergias", "quem esta fora das sinergias",
                "campeoes fora das sinergias", "tem campeao sem sinergia"
            )
        ) {
            return VoiceCommand.NonSynergyChampions
        }
        if (text in setOf(
                "quem e frontline", "quais frontline", "listar frontline",
                "ler frontline", "quem esta na linha de frente"
            )
        ) {
            return VoiceCommand.ListRole(TacticalRole.FRONTLINE)
        }
        if (text in setOf(
                "quem e backline", "quais backline", "listar backline",
                "ler backline", "quem esta na linha de tras"
            )
        ) {
            return VoiceCommand.ListRole(TacticalRole.BACKLINE)
        }
        return null
    }

    private fun parseKnowledgeQuery(text: String): VoiceCommand? {
        if (text in setOf("listar itens", "quais itens existem", "guia de itens", "catalogo de itens")) {
            return VoiceCommand.ListItemCatalog
        }
        Regex("^(?:quais componentes fazem|como faz|receita de|componentes de) (?:o |a )?(.+)$")
            .matchEntire(text)?.let { return VoiceCommand.ItemRecipeQuery(it.groupValues[1]) }
        Regex("^(?:o que faz com|quais itens usam|itens com) (?:o |a )?(.+)$")
            .matchEntire(text)?.let { return VoiceCommand.ItemsFromComponent(it.groupValues[1]) }
        Regex("^(?:registrar|definir) sinergias ativas (.+)$").matchEntire(text)?.let { match ->
            val names = match.groupValues[1]
                .split(Regex("\\s+e\\s+|\\s{2,}"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            if (names.isNotEmpty()) return VoiceCommand.RecordActiveSynergies(names)
        }
        return null
    }

    private fun parseChoice(text: String): VoiceCommand? {
        val match = Regex("^(?:escolha|opcao|aprimoramento|augment) (.+)$").matchEntire(text) ?: return null
        val index = numbers[match.groupValues[1]] ?: return null
        return if (index in 1..3) VoiceCommand.Choice(index) else null
    }

    private fun parseSell(text: String): VoiceCommand? {
        val rest = text.removePrefix("vender ")
        if (rest == text) return null
        val bench = Regex("^banco (.+)$").matchEntire(rest)
        if (bench != null) {
            val slot = numbers[bench.groupValues[1]] ?: return null
            if (slot in 1..9) return VoiceCommand.SellBench(slot)
        }
        parseSquare(rest)?.let { return VoiceCommand.SellBoard(it) }
        return null
    }

    private fun parseMovement(text: String): VoiceCommand? {
        val pieces = text.split(" para ")
        if (pieces.size != 2) return null
        val from = pieces[0].trim()
        val to = pieces[1].trim()
        val fromBench = parseBench(from)
        val toBench = parseBench(to)
        val fromSquare = parseSquare(from)
        val toSquare = parseSquare(to)
        val tactical = parseTacticalPosition(to)
        return when {
            fromBench != null && tactical != null -> VoiceCommand.BenchToTactical(fromBench, tactical)
            fromSquare != null && tactical != null -> VoiceCommand.BoardToTactical(fromSquare, tactical)
            fromBench != null && toSquare != null -> VoiceCommand.BenchToBoard(fromBench, toSquare)
            fromSquare != null && toBench != null -> VoiceCommand.BoardToBench(fromSquare, toBench)
            fromSquare != null && toSquare != null -> VoiceCommand.BoardToBoard(fromSquare, toSquare)
            else -> null
        }
    }

    private fun parseTacticalPosition(text: String): TacticalPosition? {
        val row = when {
            "segunda linha de frente" in text || "segunda linha" in text -> 2
            "terceira linha de frente" in text || "terceira linha" in text -> 3
            "retaguarda" in text || "ultima linha" in text || "linha de tras" in text -> 4
            "linha de frente" in text || "primeira linha" in text -> 1
            text == "meio" || text == "no meio" || text == "centro" -> 2
            else -> return null
        }
        val horizontal = when {
            "esquerda" in text -> HorizontalZone.LEFT
            "direita" in text -> HorizontalZone.RIGHT
            else -> HorizontalZone.CENTER
        }
        return TacticalPosition(row, horizontal)
    }

    private fun parseBench(text: String): Int? {
        val match = Regex("^banco (.+)$").matchEntire(text) ?: return null
        val slot = numbers[match.groupValues[1]] ?: return null
        return slot.takeIf { it in 1..9 }
    }

    private fun parseSquare(text: String): String? {
        val compact = text.replace(" ", "")
        Regex("^([a-g])([1-4])$").matchEntire(compact)?.let {
            return "${it.groupValues[1]}${it.groupValues[2]}"
        }
        val tokens = text.split(' ').filter { it.isNotBlank() }
        if (tokens.size != 2) return null
        val file = files[tokens[0]] ?: return null
        val rank = numbers[tokens[1]] ?: return null
        if (rank !in 1..4) return null
        return "$file$rank"
    }

    private fun normalize(raw: String): String {
        val noAccents = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace('-', ' ')
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
