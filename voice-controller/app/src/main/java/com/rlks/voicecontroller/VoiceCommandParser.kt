package com.rlks.voicecontroller

import java.text.Normalizer

sealed class VoiceCommand {
    data class BuySlots(val slots: List<Int>) : VoiceCommand()
    data object Reroll : VoiceCommand()
    data object BuyXp : VoiceCommand()
    data object ToggleShop : VoiceCommand()
    data class BenchToBoard(val bench: Int, val square: String) : VoiceCommand()
    data class BoardToBench(val square: String, val bench: Int) : VoiceCommand()
    data class BoardToBoard(val from: String, val to: String) : VoiceCommand()
    data class SellBench(val bench: Int) : VoiceCommand()
    data class SellBoard(val square: String) : VoiceCommand()
    data class Choice(val index: Int) : VoiceCommand()
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
        val text = normalize(raw)
        if (text.isBlank()) return VoiceCommand.Unknown(raw)

        if (text in setOf("ajuda", "comandos", "help")) return VoiceCommand.Help
        if (text in setOf("rolar", "rerrolar", "reroll", "rerolar")) return VoiceCommand.Reroll
        if (text in setOf("xp", "comprar xp", "subir nivel", "upar nivel", "nivel")) return VoiceCommand.BuyXp
        if (text in setOf("abrir loja", "fechar loja", "loja")) return VoiceCommand.ToggleShop
        if (text in setOf("pausar controle", "parar controle", "pausa")) return VoiceCommand.PauseControl
        if (text in setOf("retomar controle", "ativar controle", "continuar controle", "retomar")) return VoiceCommand.ResumeControl

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

    private fun parseChoice(text: String): VoiceCommand? {
        val match = Regex("^(?:escolha|aprimoramento|augment) (.+)$").matchEntire(text) ?: return null
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

        return when {
            fromBench != null && toSquare != null -> VoiceCommand.BenchToBoard(fromBench, toSquare)
            fromSquare != null && toBench != null -> VoiceCommand.BoardToBench(fromSquare, toBench)
            fromSquare != null && toSquare != null -> VoiceCommand.BoardToBoard(fromSquare, toSquare)
            else -> null
        }
    }

    private fun parseBench(text: String): Int? {
        val match = Regex("^banco (.+)$").matchEntire(text) ?: return null
        val slot = numbers[match.groupValues[1]] ?: return null
        return slot.takeIf { it in 1..9 }
    }

    private fun parseSquare(text: String): String? {
        val compact = text.replace(" ", "")
        Regex("^([a-g])([1-4])$").matchEntire(compact)?.let { return "${it.groupValues[1]}${it.groupValues[2]}" }

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
