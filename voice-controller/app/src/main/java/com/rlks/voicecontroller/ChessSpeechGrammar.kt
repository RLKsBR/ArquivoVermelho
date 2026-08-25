package com.rlks.voicecontroller

object ChessSpeechGrammar {
    private val rankWords = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight"
    )

    private val fileVariants = linkedMapOf(
        'a' to listOf("a", "eight"),
        'b' to listOf("b", "bee"),
        'c' to listOf("c", "see"),
        'd' to listOf("d", "dee"),
        'e' to listOf("e"),
        'f' to listOf("f"),
        'g' to listOf("g"),
        'h' to listOf("h")
    )

    fun phrases(): List<String> {
        val squares = mutableListOf<Pair<String, String>>()
        for ((file, spokenVariants) in fileVariants) {
            for (rankIndex in rankWords.indices) {
                val rank = rankIndex + 1
                for (spokenFile in spokenVariants) {
                    squares += "$file$rank" to "$spokenFile ${rankWords[rankIndex]}"
                }
            }
        }

        val phrases = LinkedHashSet<String>()
        for ((_, fromSpeech) in squares) {
            for ((_, toSpeech) in squares) {
                phrases += "$fromSpeech $toSpeech"
            }
        }

        phrases += "castle kingside"
        phrases += "castle queenside"
        phrases += "white orientation"
        phrases += "black orientation"
        phrases += "calibrate board"
        return phrases.toList()
    }

    fun json(): String = phrases().joinToString(prefix = "[", postfix = "]") { phrase ->
        "\"${phrase.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
