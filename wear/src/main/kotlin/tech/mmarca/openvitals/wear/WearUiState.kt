package tech.mmarca.openvitals.wear

import tech.mmarca.openvitals.wear.features.quicklog.LoggedEntry
import tech.mmarca.openvitals.wear.features.recording.ActivityType
import tech.mmarca.openvitals.wear.health.WearCapabilities
import tech.mmarca.openvitals.wear.workout.WorkoutUiState

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /**
     * The metrics this watch has a sensor for. Only these are offered as
     * tiles. Every metric until sensor discovery narrows it.
     */
    val availableMetrics: Set<DashboardMetric> = DashboardMetric.entries.toSet(),
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<DashboardMetric, MetricUiState> = emptyMap(),
    val recording: RecordingUiState = RecordingUiState(),
    val workout: WorkoutUiState = WorkoutUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val preferences: DisplayPreferences = DisplayPreferences(),
    /** What the watch can measure; gates measurement buttons and workouts. */
    val capabilities: WearCapabilities = WearCapabilities(),
    /** Last night's minutes per stage, as synced from the phone. */
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
    /** Last night's bedtime, sleep onset and wake. */
    val sleepTimes: SleepTimes = SleepTimes(),
    /** Quick logs and spot measurements made on this watch, oldest first. */
    val entries: List<LoggedEntry> = emptyList(),
)

/** One metric's readings, in the base unit documented on [DashboardMetric]. */
data class MetricUiState(
    /** Today's total for a cumulative metric, the latest sample otherwise. */
    val current: Double? = null,
    /** A daily goal. Only meaningful for a cumulative metric. */
    val goal: Double? = null,
    /** Today, oldest first: hourly totals for a cumulative metric, samples otherwise. */
    val today: List<Double> = emptyList(),
    /** The last days, oldest first, today last: daily totals or daily averages. */
    val week: List<Double> = emptyList(),
)

/**
 * Display settings the watch does not own: the phone app is where they are
 * chosen, and a later sync writes them here. Readings stay metric; only
 * their display changes.
 */
data class DisplayPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Top of the heart rate zones. The phone will know the user's age; until then a fixed guess. */
    val maxHeartRate: Int = 190,
    /** Daily water goal in millilitres. */
    val waterGoalMl: Int = 2_000,
)

data class RecordingUiState(
    val activityType: ActivityType = ActivityType.WALK,
    val elapsedSeconds: Long = 0,
    val heartRateBpm: Int? = null,
    val distanceMeters: Int? = null,
    val caloriesKcal: Int? = null,
    val elevationGainMeters: Int? = null,
    val speedMetersPerSecond: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val paused: Boolean = false,
    val steps: Int? = null,
    val cadenceStepsPerMinute: Int? = null,
    val floors: Int? = null,
    val waitingForFix: Boolean = false,
)

/** The screens' view of the workout: its latest snapshot, or the final one. */
fun WorkoutUiState.toRecording(): RecordingUiState {
    val s = snapshot ?: return RecordingUiState()
    val type = ActivityType.entries.firstOrNull { it.sport == sport } ?: ActivityType.WORKOUT
    return RecordingUiState(
        activityType = type,
        elapsedSeconds = s.activeSeconds,
        heartRateBpm = s.heartRateBpm,
        distanceMeters = s.distanceMetres,
        elevationGainMeters = s.ascentMetres,
        speedMetersPerSecond = s.speedMetresPerSecond,
        averageHeartRateBpm = s.averageHeartRateBpm,
        paused = s.paused,
        steps = s.steps,
        cadenceStepsPerMinute = s.cadenceStepsPerMinute,
        floors = s.floors,
        waitingForFix = s.waitingForFix,
    )
}

data class SettingsUiState(
    val phoneConnected: Boolean = false,
    val sensorAccessGranted: Boolean = false,
) {
    companion object {
        /**
         * A phone counts as connected while one is allowed, still paired and
         * the link listens. Not while a request is open: the phone only holds
         * a connection for as long as a pull takes, so no client at this
         * moment says nothing about the link.
         */
        fun from(link: WearLinkUiState, sensorAccessGranted: Boolean): SettingsUiState =
            SettingsUiState(
                phoneConnected = link.listening &&
                    link.trusted.any { phone -> link.bondLost.none { it.address == phone.address } },
                sensorAccessGranted = sensorAccessGranted,
            )
    }
}
