package com.rlks.voicecontroller

object CalibrationFlow {
    fun next(current: String): String = when (current) {
        "core", "all" -> "none"
        "items" -> "traits"
        "traits" -> "choices"
        else -> "none"
    }

    fun label(stage: String): String = when (stage) {
        "core", "all" -> "principal: tabuleiro, banco, loja e botões"
        "items" -> "região dos itens"
        "traits" -> "região das sinergias"
        "choices" -> "região das escolhas/aprimoramentos"
        else -> "nenhuma"
    }

    fun prompt(stage: String): String = when (stage) {
        "core", "all" -> "Abra uma partida do TFT com tabuleiro, banco e loja visíveis; depois toque no 🎙. Itens, sinergias e escolhas serão detectados depois."
        "items" -> "Abra uma tela em que os itens estejam visíveis; depois toque no 🎙 para marcar os dois cantos."
        "traits" -> "Deixe a lista de sinergias visível; depois toque no 🎙 para marcar os dois cantos."
        "choices" -> "Quando aparecer uma tela de escolha ou aprimoramento, toque no 🎙 para marcar os dois cantos."
        else -> "Calibração concluída."
    }
}
