package com.rlks.voicecontroller

enum class GameScreen {
    UNKNOWN,
    BOARD,
    AUGMENT,
    ARMORY,
    COMPONENT_SELECTION,
    CAROUSEL
}
enum class ObservationSource { OCR, VISUAL, USER, LEGACY }

data class ObservedValue<T>(
    val value: T,
    val confidence: Float,
    val observedAt: Long,
    val source: ObservationSource
)

data class GameState(
    val screen: ObservedValue<GameScreen> = ObservedValue(
        GameScreen.UNKNOWN, 0f, 0L, ObservationSource.LEGACY
    ),
    val stageRound: ObservedValue<StageRound>? = null,
    val selection: ObservedValue<SelectionScreen>? = null,
    val calibratedComponents: Map<CalibrationComponent, CalibrationMetadata> = emptyMap(),
    val lastFrameAt: Long = 0L
)

class GameStateRepository(initial: GameState = GameState()) {
    @Volatile private var current = initial

    fun get(): GameState = current

    @Synchronized
    fun update(transform: (GameState) -> GameState): GameState {
        current = transform(current)
        return current
    }

    fun clearDynamicState(now: Long = System.currentTimeMillis()) {
        update {
            it.copy(
                screen = ObservedValue(GameScreen.UNKNOWN, 0f, now, ObservationSource.LEGACY),
                stageRound = null,
                selection = null,
                lastFrameAt = now
            )
        }
    }
}
