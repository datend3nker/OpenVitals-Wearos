package tech.mmarca.openvitals.wear

import tech.mmarca.openvitals.wear.health.WearCapabilities

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val settings: SettingsUiState = SettingsUiState(),
    val preferences: WearPreferences = WearPreferences(),
    /** What the watch can measure; decides which sensor metrics are offered. */
    val capabilities: WearCapabilities = WearCapabilities(),
    /** Last night's minutes per stage, as synced from the phone. */
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
) {
    /** The metrics to offer as tiles. Every metric until the probe answers, so nothing chosen vanishes at start. */
    val availableMetrics: Set<MetricTile>
        get() = if (capabilities.probed) capabilities.availableMetrics else MetricTile.entries.toSet()
}

/** One metric's readings, in the base unit documented on [MetricTile]. */
data class MetricUiState(
    /** Today's total for a cumulative metric, the latest sample otherwise. */
    val current: Double? = null,
    /** A daily goal. Only meaningful for a cumulative metric. */
    val goal: Double? = null,
    /** Today, oldest first: hourly totals for a cumulative metric, samples otherwise. */
    val today: List<Double> = emptyList(),
    /** The last 7 days, oldest first, today last: daily totals or daily averages. */
    val week: List<Double> = emptyList(),
)

/**
 * Display settings and goals the watch does not own: the phone app is where
 * they are chosen. Until the phone sends them, these defaults apply.
 * Readings stay metric; only their display changes.
 */
data class WearPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Top of the heart rate zones. */
    val maxHeartRate: Int = 190,
    val stepGoal: Int = 10_000,
    /** Daily water goal in millilitres. */
    val waterGoalMl: Int = 2_000,
)

/** Same two values as the phone app's `UnitSystem`. */
enum class UnitSystem {
    METRIC,
    IMPERIAL,
}

data class SettingsUiState(
    /** A phone is allowed and still paired. */
    val phoneConnected: Boolean = false,
    /** Heart rate and step counter access are both granted. */
    val sensorAccessGranted: Boolean = false,
)
