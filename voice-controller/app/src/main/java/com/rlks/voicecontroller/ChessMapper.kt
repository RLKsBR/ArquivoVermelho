package com.rlks.voicecontroller

object ChessMapper {
    fun squareToPoint(square: String, rect: BoardRect, whiteBottom: Boolean): NormalizedPoint? {
        if (!square.matches(Regex("^[a-h][1-8]$"))) return null
        val file = square[0] - 'a'
        val rank = square[1] - '1'

        val column = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank

        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0f || height <= 0f) return null

        return NormalizedPoint(
            rect.left + width * ((column + 0.5f) / 8f),
            rect.top + height * ((row + 0.5f) / 8f)
        )
    }

    fun castleMove(kingside: Boolean, whiteBottom: Boolean): Pair<String, String> {
        val rank = if (whiteBottom) '1' else '8'
        return "e$rank" to if (kingside) "g$rank" else "c$rank"
    }
}
