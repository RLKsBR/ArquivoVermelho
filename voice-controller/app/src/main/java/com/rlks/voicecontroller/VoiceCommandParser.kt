package com.rlks.voicecontroller

import java.text.Normalizer

sealed class VoiceCommand {
    data class ChessMove(val from: String, val to: String) : VoiceCommand()
    data class Castle(val kingside: Boolean) : VoiceCommand()
    data object CalibrateBoard : VoiceCommand()
    data class SetOrientation(val whiteBottom: Boolean) : VoiceCommand()
    data class SetPoint(val name: String) : VoiceCommand()
    data class TapPoint(val name: String) : VoiceCommand()
    data class TapPercent(val x: Float, val y: Float) : VoiceCommand()
    data class DragPoint(val from: String, val to: String) : VoiceCommand()
    data class DragPercent(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : VoiceCommand()
    data object Help : VoiceCommand()
    data class Unknown(val raw: String) : VoiceCommand()
}

object VoiceCommandParser {
    private val numberWords = mapOf(
        "1" to "1", "2" to "2", "3" to "3", "4" to "4",
        "5" to "5", "6" to "6", "7" to "7", "8" to "8",
        "one" to "1", "won" to "1",
        "two" to "2", "to" to "2", "too" to "2",
        "three" to "3", "tree" to "3",
        "four" to "4", "for" to "4",
        "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "ate" to "8",
        "um" to "1", "uma" to "1", "dois" to "2", "duas" to "2",
        "tres" to "3", "quatro" to "4", "cinco" to "5", "seis" to "6",
        "sete" to "7", "oito" to "8"
    )

    private val fileWords = mapOf(
        "a" to "a", "ay" to "a", "ei" to "a",
        "b" to "b", "be" to "b", "bee" to "b", "bi" to "b",
        "c" to "c", "ce" to "c", "see" to "c", "sea" to "c", "si" to "c",
        "d" to "d", "de" to "d", "dee" to "d", "di" to "d",
        "e" to "e", "ee" to "e", "i" to "e",
        "f" to "f", "ef" to "f", "efe" to "f",
        "g" to "g", "ge" to "g", "gee" to "g", "ji" to "g",
        "h" to "h", "aga" to "h", "aitch" to "h", "eitch" to "h"
    )

    private val literalSquareRegex = Regex("[a-h][1-8]")

    fun parse(raw: String): VoiceCommand {
        val text = normalize(raw)
        if (text.isBlank()) return VoiceCommand.Unknown(raw)

        if (text in setOf("help", "ajuda", "commands", "comandos")) return VoiceCommand.Help

        if (text.contains("calibrate board") || text.contains("calibrar tabuleiro") ||
            text.contains("calibrate chess") || text.contains("calibrar xadrez")) {
            return VoiceCommand.CalibrateBoard
        }

        if (text in setOf("white orientation", "white side", "white bottom", "brancas", "brancas embaixo", "orientacao branca")) {
            return VoiceCommand.SetOrientation(true)
        }
        if (text in setOf("black orientation", "black side", "black bottom", "pretas", "pretas embaixo", "orientacao preta")) {
            return VoiceCommand.SetOrientation(false)
        }

        if (text.contains("castle kingside") || text.contains("king side castle") ||
            text.contains("short castle") || text.contains("roque pequeno") || text == "castle short") {
            return VoiceCommand.Castle(true)
        }
        if (text.contains("castle queenside") || text.contains("queen side castle") ||
            text.contains("long castle") || text.contains("roque grande") || text == "castle long") {
            return VoiceCommand.Castle(false)
        }

        parseDragPercent(text)?.let { return it }
        parseTapPercent(text)?.let { return it }

        val drag = parseNamedDrag(text)
        if (drag != null) return drag

        for (prefix in listOf("set ", "define ", "mark ", "marcar ", "definir ")) {
            if (text.startsWith(prefix)) {
                val name = canonicalName(text.removePrefix(prefix))
                if (name.isNotBlank()) return VoiceCommand.SetPoint(name)
            }
        }

        for (prefix in listOf("tap ", "press ", "click ", "tocar ", "toque ", "apertar ", "clique ")) {
            if (text.startsWith(prefix)) {
                val name = canonicalName(text.removePrefix(prefix))
                if (name.isNotBlank()) return VoiceCommand.TapPoint(name)
            }
        }

        val squares = extractSquares(text)
        if (squares.size >= 2) return VoiceCommand.ChessMove(squares[0], squares[1])

        return VoiceCommand.Unknown(raw)
    }

    /**
     * Android speech recognition commonly returns several hypotheses. Short chess
     * coordinates are especially ambiguous (for example D = "de/dee" and 2 = "to/two").
     * Prefer the first hypothesis that actually parses into a supported command.
     */
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
        .replace(Regex("\\bbutton\\b"), "")
        .replace(Regex("\\bbotao\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseNamedDrag(text: String): VoiceCommand.DragPoint? {
        val english = Regex("^(?:drag|move) (.+?) (?:to|into) (.+)$").matchEntire(text)
        if (english != null) {
            return VoiceCommand.DragPoint(canonicalName(english.groupValues[1]), canonicalName(english.groupValues[2]))
        }
        val portuguese = Regex("^(?:arrastar|mover) (.+?) (?:para|ate) (.+)$").matchEntire(text)
        if (portuguese != null) {
            return VoiceCommand.DragPoint(canonicalName(portuguese.groupValues[1]), canonicalName(portuguese.groupValues[2]))
        }
        return null
    }

    private fun parseTapPercent(text: String): VoiceCommand.TapPercent? {
        val match = Regex("^(?:tap|touch|tocar|toque) (\\d{1,3})[ ,]+(\\d{1,3})$").matchEntire(text) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        if (x !in 0f..100f || y !in 0f..100f) return null
        return VoiceCommand.TapPercent(x / 100f, y / 100f)
    }

    private fun parseDragPercent(text: String): VoiceCommand.DragPercent? {
        val match = Regex("^(?:drag|move|arrastar|mover) (\\d{1,3})[ ,]+(\\d{1,3}) (?:to|para|ate) (\\d{1,3})[ ,]+(\\d{1,3})$").matchEntire(text) ?: return null
        val values = match.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
        if (values.size != 4 || values.any { it !in 0f..100f }) return null
        return VoiceCommand.DragPercent(values[0] / 100f, values[1] / 100f, values[2] / 100f, values[3] / 100f)
    }

    private fun extractSquares(text: String): List<String> {
        val tokens = text.split(' ').filter { it.isNotBlank() }
        val squares = mutableListOf<String>()
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]

            // Handles D2 D4, D2D4, B1C3 and coordinates embedded in natural phrases.
            val literalSquares = literalSquareRegex.findAll(token).map { it.value }.toList()
            if (literalSquares.isNotEmpty()) {
                squares += literalSquares
                index += 1
                continue
            }

            // Handles "D two", "de dois", "bê um", "cê três", etc.
            val file = fileWords[token]
            val rank = tokens.getOrNull(index + 1)?.let { numberWords[it] }
            if (file != null && rank != null) {
                squares += "$file$rank"
                index += 2
                continue
            }

            index += 1
        }

        return squares
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
