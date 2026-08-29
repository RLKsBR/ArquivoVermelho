package com.rlks.voicecontroller

data class CalibrationProgress(
    val detected: Set<CalibrationComponent>,
    val committed: Set<CalibrationComponent>,
    val awaitingConfirmation: Set<CalibrationComponent>,
    val rejected: Map<CalibrationComponent, String>
)

class CalibrationManager(private val store: ProfileStore) {
    private val stability = CalibrationStabilityTracker(requiredFrames = 2)

    fun observeAutomaticFrame(
        core: AutoCalibrationResult,
        visual: VisualLayoutCandidate?,
        geometry: DisplayGeometry,
        now: Long = System.currentTimeMillis()
    ): CalibrationProgress {
        val detected = linkedSetOf<CalibrationComponent>()
        val committed = linkedSetOf<CalibrationComponent>()
        val awaiting = linkedSetOf<CalibrationComponent>()
        val rejected = linkedMapOf<CalibrationComponent, String>()

        core.shopLine?.let { line ->
            observe(
                CalibrationComponent.SHOP, line.signature(), 0.9f,
                onAwaiting = { awaiting += CalibrationComponent.SHOP },
                onCommit = {
                    store.saveLineWithMetadata(
                        CalibrationComponent.SHOP, line.first, line.last,
                        metadata(CalibrationComponent.SHOP, geometry, 0.9f, now)
                    )
                }, detected, committed
            )
        }
        core.reroll?.let { point ->
            observePoint(CalibrationComponent.REROLL, ProfileStore.POINT_REROLL, point, 0.92f,
                geometry, now, detected, committed, awaiting)
        }
        core.xp?.let { point ->
            observePoint(CalibrationComponent.XP, ProfileStore.POINT_XP, point, 0.9f,
                geometry, now, detected, committed, awaiting)
        }
        core.shopToggle?.let { point ->
            observePoint(CalibrationComponent.SHOP_TOGGLE, ProfileStore.POINT_SHOP_TOGGLE, point, 0.86f,
                geometry, now, detected, committed, awaiting)
        }
        core.sell?.let { point ->
            observePoint(CalibrationComponent.SELL, ProfileStore.POINT_SELL, point, 0.84f,
                geometry, now, detected, committed, awaiting)
        }
        core.contexts.filter { it.confidence >= CalibrationMetadata.MIN_GESTURE_CONFIDENCE }
            .forEach { detection ->
                val component = detection.kind.component()
                detected += component
                val count = stability.observe(component, detection.region.signature())
                if (count < 2) {
                    awaiting += component
                } else if (store.saveRegionWithMetadata(
                        detection.kind.regionName,
                        detection.region,
                        metadata(component, geometry, detection.confidence, now)
                    )
                ) {
                    committed += component
                    stability.reset(component)
                }
            }

        if (visual != null) {
            val visualConfidence = visual.confidence
            if (visual.boardRows != null) {
                detected += CalibrationComponent.BOARD
                val signature = visual.boardRows.toSortedMap().values.flatMap { it.signature() }
                val count = stability.observe(CalibrationComponent.BOARD, signature)
                when {
                    visualConfidence < CalibrationMetadata.MIN_GESTURE_CONFIDENCE ->
                        rejected[CalibrationComponent.BOARD] =
                            "confiança visual ${percent(visualConfidence)}; ${visual.reason}"
                    count < 2 -> awaiting += CalibrationComponent.BOARD
                    store.saveBoardRows(
                        visual.boardRows,
                        metadata(CalibrationComponent.BOARD, geometry, visualConfidence, now)
                    ) -> {
                        committed += CalibrationComponent.BOARD
                        stability.reset(CalibrationComponent.BOARD)
                    }
                }
            }
            if (visual.benchLine != null) {
                detected += CalibrationComponent.BENCH
                val count = stability.observe(CalibrationComponent.BENCH, visual.benchLine.signature())
                when {
                    visualConfidence < CalibrationMetadata.MIN_GESTURE_CONFIDENCE ->
                        rejected[CalibrationComponent.BENCH] =
                            "confiança visual ${percent(visualConfidence)}; ${visual.reason}"
                    count < 2 -> awaiting += CalibrationComponent.BENCH
                    store.saveLineWithMetadata(
                        CalibrationComponent.BENCH,
                        visual.benchLine.first,
                        visual.benchLine.last,
                        metadata(CalibrationComponent.BENCH, geometry, visualConfidence, now)
                    ) -> {
                        committed += CalibrationComponent.BENCH
                        stability.reset(CalibrationComponent.BENCH)
                    }
                }
            }
        }
        return CalibrationProgress(detected, committed, awaiting, rejected)
    }

    fun observeContextFrame(
        detections: List<ContextDetection>,
        geometry: DisplayGeometry,
        now: Long = System.currentTimeMillis()
    ): Set<CalibrationComponent> {
        val committed = linkedSetOf<CalibrationComponent>()
        detections.filter { it.confidence >= CalibrationMetadata.MIN_GESTURE_CONFIDENCE }
            .forEach { detection ->
                val component = detection.kind.component()
                val existing = store.getRegion(detection.kind.regionName)
                val existingMetadata = store.getCalibrationMetadata(component)
                if (existing != null && existingMetadata?.isCurrent(geometry) == true &&
                    signaturesCompatible(existing.signature(), detection.region.signature())
                ) {
                    return@forEach
                }
                if (stability.observe(component, detection.region.signature()) >= 2 &&
                    store.saveRegionWithMetadata(
                        detection.kind.regionName,
                        detection.region,
                        metadata(component, geometry, detection.confidence, now)
                    )
                ) {
                    committed += component
                    stability.reset(component)
                }
            }
        return committed
    }

    fun reset(component: CalibrationComponent? = null) = stability.reset(component)

    private fun observePoint(
        component: CalibrationComponent,
        key: String,
        point: NormalizedPoint,
        confidence: Float,
        geometry: DisplayGeometry,
        now: Long,
        detected: MutableSet<CalibrationComponent>,
        committed: MutableSet<CalibrationComponent>,
        awaiting: MutableSet<CalibrationComponent>
    ) {
        observe(
            component, point.signature(), confidence,
            onAwaiting = { awaiting += component },
            onCommit = {
                store.savePointWithMetadata(key, point, metadata(component, geometry, confidence, now))
            }, detected, committed
        )
    }

    private fun observe(
        component: CalibrationComponent,
        signature: List<Float>,
        confidence: Float,
        onAwaiting: () -> Unit,
        onCommit: () -> Boolean,
        detected: MutableSet<CalibrationComponent>,
        committed: MutableSet<CalibrationComponent>
    ) {
        detected += component
        if (confidence < CalibrationMetadata.MIN_GESTURE_CONFIDENCE ||
            stability.observe(component, signature) < 2
        ) {
            onAwaiting()
        } else if (onCommit()) {
            committed += component
            stability.reset(component)
        }
    }

    private fun metadata(
        component: CalibrationComponent,
        geometry: DisplayGeometry,
        confidence: Float,
        now: Long
    ) = CalibrationMetadata(
        component = component,
        geometry = geometry,
        source = CalibrationSource.AUTOMATIC,
        confidence = confidence,
        validatedAt = now,
        interfaceProfileId = "tft_${geometry.profileId()}"
    )

    private fun percent(value: Float): String = "%.0f%%".format(value.coerceIn(0f, 1f) * 100f)

    private fun signaturesCompatible(first: List<Float>, second: List<Float>): Boolean =
        first.size == second.size && first.indices.all { kotlin.math.abs(first[it] - second[it]) <= 0.025f }
}

fun AutoContextKind.component(): CalibrationComponent = when (this) {
    AutoContextKind.CHOICES -> CalibrationComponent.CHOICES
    AutoContextKind.ITEMS -> CalibrationComponent.ITEMS
    AutoContextKind.TRAITS -> CalibrationComponent.TRAITS
}
