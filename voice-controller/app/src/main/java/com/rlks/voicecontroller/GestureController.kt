package com.rlks.voicecontroller

/** Pure safety policy kept separate from Android gesture dispatch. */
object GestureController {
    fun isSensitive(command: VoiceCommand): Boolean =
        command is VoiceCommand.SellBench || command is VoiceCommand.SellBoard ||
            command is VoiceCommand.Choice

    fun sensitiveDescription(command: VoiceCommand): String = when (command) {
        is VoiceCommand.SellBench -> "vender o campeão do banco ${command.bench}"
        is VoiceCommand.SellBoard -> "vender o campeão de ${command.square.uppercase()}"
        is VoiceCommand.Choice -> "selecionar a opção ${command.index}"
        else -> "executar essa ação"
    }

    fun requiredComponents(command: VoiceCommand): Set<CalibrationComponent> = when (command) {
        is VoiceCommand.BuySlots -> setOf(CalibrationComponent.SHOP)
        VoiceCommand.Reroll -> setOf(CalibrationComponent.REROLL)
        VoiceCommand.BuyXp -> setOf(CalibrationComponent.XP)
        VoiceCommand.ToggleShop -> setOf(CalibrationComponent.SHOP_TOGGLE)
        is VoiceCommand.BenchToBoard, is VoiceCommand.BenchToTactical ->
            setOf(CalibrationComponent.BENCH, CalibrationComponent.BOARD)
        is VoiceCommand.BoardToBench -> setOf(CalibrationComponent.BOARD, CalibrationComponent.BENCH)
        is VoiceCommand.BoardToBoard, is VoiceCommand.BoardToTactical -> setOf(CalibrationComponent.BOARD)
        is VoiceCommand.SellBench -> setOf(CalibrationComponent.BENCH, CalibrationComponent.SELL)
        is VoiceCommand.SellBoard -> setOf(CalibrationComponent.BOARD, CalibrationComponent.SELL)
        is VoiceCommand.Choice -> setOf(CalibrationComponent.CHOICES)
        else -> emptySet()
    }
}
