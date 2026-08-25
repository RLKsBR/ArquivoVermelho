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
        "a" to "a", "ay" to "a", "ei" to "a", "hey" to "a", "alpha" to "a",
        "b" to "b", "be" to "b", "bee" to "b", "bi" to "b", "bravo" to "b",
        "c" to "c", "ce" to "c", "see" to "c", "sea" to "c", "si" to "c", "charlie" to "c",
        "d" to "d", "de" to "d", "dee" to "d", "di" to "d", "the" to "d", "delta" to "d",
        "e" to "e", "ee" to "e", "he" to "e", "echo" to "e",
        "f" to "f", "ef" to "f", "efe" to "f", "foxtrot" to "f",
        "g" to "g", "ge" to "g", "gee" to "g", "ji" to "g", "golf" to "g",
        "h" to "h", "aga" to "h", "aitch" to "h", "eitch" to "h", "age" to "h", "hotel" to "h"
    )

    private val twoLiteralSquares = Regex("^([a-h])([1-8])([a-h])([1-8])$")
    private val collapsedSameFile = Regex("^([a-h])([1-8])([1-8])$")
    private val oneLiteralSquare = Regex("^([a-h])([1-8])$")
    private val rankPair = Regex("^([1-8])([1-8])$")
    private val aAsEightTokens = setOf("8", "eight", "ate")

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

        // The Android recognizer on some devices has been observed turning spoken
        // "A two A four" into "8 2 8 4". In chess coordinates a file can never
        // be the digit 8, so it is safe to interpret 8/eight as file A only when
        // it appears specifically in a FILE position of a coordinate sequence.
        parseCoordinateSequence(text)?.let { return it }

        val squares = extractSquares(text)
        if (squares.size >= 2) return VoiceCommand.ChessMove(squares[0], squares[1])

        return VoiceCommand.Unknown(raw)
    }

    fun parseAlternatives(candidates: List<String>): Pair<String, VoiceCommand>? {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null

        for (candidate in cleaned) {
            val command = parse(candidate)
            if (command is VoiceCommand.ChessMove || command is VoiceCommand.Castle) {
                return candidate to command
            }
        }
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

    private fun parseCoordinateSequence(text: String): VoiceCommand.ChessMove? {
        val tokens = text.split(' ').filter { it.isNotBlank() }

        if (tokens.size == 4) {
            val file1 = coordinateFile(tokens[0])
            val rank1 = numberWords[tokens[1]]
            val file2 = coordinateFile(tokens[2])
            val rank2 = numberWords[tokens[3]]
            if (file1 != null && rank1 != null && file2 != null && rank2 != null) {
                return VoiceCommand.ChessMove("$file1$rank1", "$file2$rank2")
            }
        }

        if (tokens.size == 2) {
            val first = compactSquare(tokens[0])
            val second = compactSquare(tokens[1])
            if (first != null && second != null) return VoiceCommand.ChessMove(first, second)
        }

        if (tokens.size == 1 && tokens[0].length == 4) {
            val first = compactSquare(tokens[0].substring(0, 2))
            val second = compactSquare(tokens[0].substring(2, 4))
            if (first != null && second != null) return VoiceCommand.ChessMove(first, second)
        }

        return null
    }

    private fun coordinateFile(token: String): String? =
        fileWords[token] ?: if (token in aAsEightTokens) "a" else null

    private fun compactSquare(token: String): String? {
        if (token.length != 2) return null
        val file = when (val first = token[0]) {
            in 'a'..'h' -> first.toString()
            '8' -> "a"
            else -> null
        } ?: return null
        val rank = token[1]
        if (rank !in '1'..'8') return null
        return "$file$rank"
    }

    private fun parseNamedDrag(text: String): VoiceCommand.DragPoint? {
        val english = Regex("^(?:drag|move) (.+?) (?:to|into) (.+)$").matchEntire(text)
        if (english != null) return VoiceCommand.DragPoint(canonicalName(english.groupValues[1]), canonicalName(english.groupValues[2]))
        val portuguese = Regex("^(?:arrastar|mover) (.+?) (?:para|ate) (.+)$").matchEntire(text)
        if (portuguese != null) return VoiceCommand.DragPoint(canonicalName(portuguese.groupValues[1]), canonicalName(portuguese.groupValues[2]))
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

            val twoSquares = twoLiteralSquares.matchEntire(token)
            if (twoSquares != null) {
                squares += "${twoSquares.groupValues[1]}${twoSquares.groupValues[2]}"
                squares += "${twoSquares.groupValues[3]}${twoSquares.groupValues[4]}"
                index += 1
                continue
            }

            val sameFile = collapsedSameFile.matchEntire(token)
            if (sameFile != null) {
                squares += "${sameFile.groupValues[1]}${sameFile.groupValues[2]}"
                squares += "${sameFile.groupValues[1]}${sameFile.groupValues[3]}"
                index += 1
                continue
            }

            val oneSquare = oneLiteralSquare.matchEntire(token)
            if (oneSquare != null) {
                squares += "${oneSquare.groupValues[1]}${oneSquare.groupValues[2]}"
                index += 1
                continue
            }

            val file = fileWords[token]
            if (file != null) {
                val next = tokens.getOrNull(index + 1)

                val fusedRanks = next?.let { rankPair.matchEntire(it) }
                if (fusedRanks != null) {
                    squares += "$file${fusedRanks.groupValues[1]}"
                    squares += "$file${fusedRanks.groupValues[2]}"
                    index += 2
                    continue
                }

                val rank = next?.let { numberWords[it] }
                if (rank != null) {
                    val secondFile = tokens.getOrNull(index + 2)?.let { fileWords[it] }
                    val secondRank = tokens.getOrNull(index + 3)?.let { numberWords[it] }
                    if (secondFile != null && secondRank != null) {
                        squares += "$file$rank"
                        squares += "$secondFile$secondRank"
                        index += 4
                        continue
                    }

                    val sameFileSecondRank = tokens.getOrNull(index + 2)?.let { numberWords[it] }
                    if (sameFileSecondRank != null) {
                        squares += "$file$rank"
                        squares += "$file$sameFileSecondRank"
                        index += 3
                        continue
                    }

                    squares += "$file$rank"
                    index += 2
                    continue
                }
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
