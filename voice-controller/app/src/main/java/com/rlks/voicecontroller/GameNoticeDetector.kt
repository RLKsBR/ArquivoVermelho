package com.rlks.voicecontroller

import java.text.Normalizer

object GameNoticeDetector {
    fun explain(rawText: String): String? {
        val text = normalize(rawText)
        if (text.isBlank()) return null

        val reserveFull =
            ("reserva" in text && ("cheia" in text || "cheio" in text)) ||
            ("banco" in text && ("cheio" in text || "cheia" in text))
        if (reserveFull) {
            return "Sua reserva está cheia. O jogo não conseguiu colocar outro campeão no banco."
        }

        val itemSpaceFull =
            (("item" in text || "inventario" in text || "bancada" in text) &&
                ("cheio" in text || "cheia" in text || "sem espaco" in text)) ||
            "nao ha espaco para o item" in text
        if (itemSpaceFull) {
            return "Não há espaço disponível para receber outro item."
        }

        if ("nao ha espaco" in text || "sem espaco" in text) {
            return "O jogo informou que não há espaço disponível."
        }

        val usefulLines = rawText.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 4 }
            .take(5)
        return usefulLines.takeIf { it.isNotEmpty() }?.joinToString(". ")?.take(500)
    }

    private fun normalize(raw: String): String {
        val withoutAccents = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return withoutAccents
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
