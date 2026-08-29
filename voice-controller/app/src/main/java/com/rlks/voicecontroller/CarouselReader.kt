package com.rlks.voicecontroller

object CarouselReader {
    fun describe(frames: List<ScreenTextResult>): String {
        val entries = frames.flatMap { it.lines }
            .filter { line ->
                val centerX = (line.region.left + line.region.right) / 2f
                val centerY = (line.region.top + line.region.bottom) / 2f
                centerX in 0.08f..0.92f && centerY in 0.12f..0.86f && line.text.length >= 2
            }
            .distinctBy { ItemRecipeBook.normalize(it.text) }
            .take(18)
        if (entries.isEmpty()) {
            return "Carrossel. Não encontrei nomes legíveis. Os campeões e itens continuam girando; tente novamente quando estiverem mais próximos."
        }
        val spoken = entries.joinToString(". ") { line ->
            val center = line.region.center()
            "${line.text}, na direção de ${clockDirection(center)}"
        }
        return "Carrossel em movimento. $spoken."
    }

    private fun clockDirection(point: NormalizedPoint): String = when {
        point.y < 0.34f && point.x < 0.38f -> "onze horas"
        point.y < 0.34f && point.x > 0.62f -> "uma hora"
        point.y > 0.66f && point.x < 0.38f -> "sete horas"
        point.y > 0.66f && point.x > 0.62f -> "cinco horas"
        point.y < 0.38f -> "doze horas"
        point.y > 0.62f -> "seis horas"
        point.x < 0.5f -> "nove horas"
        else -> "três horas"
    }
}
