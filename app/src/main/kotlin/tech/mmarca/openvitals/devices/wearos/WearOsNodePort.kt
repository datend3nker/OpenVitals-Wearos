package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import tech.mmarca.openvitals.domain.model.WearSleepMinute
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/**
 * What the phone can tell about the OpenVitals Wear OS app on a watch.
 *
 * A bond says nothing about reach: a paired watch can be off or out of range.
 * Only an answer to the ping proves the link, so a paired watch that stays
 * silent is [NO_ANSWER], never "connected".
 */
enum class WearOsAppStatus {
    /** The phone's Bluetooth is off. */
    BLUETOOTH_OFF,

    /** No bonded Wear OS watch matches the registered one. */
    NOT_PAIRED,

    /** Paired, but no answer: the watch is off or out of range, or the app on it is not installed or not running. */
    NO_ANSWER,

    /** The watch is asking the wearer whether to allow this phone. */
    PENDING_CONFIRMATION,

    /** The watch holds another token for this phone. */
    UNAUTHORIZED_MISMATCH,

    /** The wearer blocked this phone on the watch. */
    UNAUTHORIZED_BLOCKED,

    /** The watch app speaks an older protocol than this phone. */
    UPDATE_WATCH,

    /** The watch app speaks a newer protocol than this phone. */
    UPDATE_PHONE,

    /** The watch answered something the protocol does not allow. */
    PROTOCOL_ERROR,

    /** The OpenVitals Wear OS app answered the hello. */
    APP_RUNNING,
}

/**
 * Pairing and app responsiveness of a Wear OS watch.
 */
data class WearOsCompanionStatus(
    val isPaired: Boolean = false,
    val connectedNodeName: String? = null,
    val connectedNodeAddress: String? = null,
    val appStatus: WearOsAppStatus = WearOsAppStatus.NOT_PAIRED,
    val lastCheckedAt: Instant? = null,
    /** The name the watch gave in its hello, once it has. */
    val watchName: String? = null,
    /** The bond this watch once had with the phone is gone. */
    val bondLost: Boolean = false,
) {
    val isAppRunning: Boolean
        get() = appStatus == WearOsAppStatus.APP_RUNNING
}

/** One heart rate sample the watch recorded. */
data class WearOsHeartRateSample(val time: Instant, val beatsPerMinute: Int)

/** One page of a heart rate pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsHeartRatePage(
    val samples: List<WearOsHeartRateSample>,
    val hasMore: Boolean,
)

/** One page of a sleep minute pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsSleepMinutePage(
    val minutes: List<WearSleepMinute>,
    val hasMore: Boolean,
)

/** The steps the watch counted in the clock minute starting at [start]. */
data class WearOsStepMinute(val start: Instant, val steps: Int)

/** One page of a step pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsStepPage(
    val minutes: List<WearOsStepMinute>,
    val hasMore: Boolean,
)

/** The height the watch's barometer climbed in the clock minute starting at [start], elevators included. */
data class WearOsAscentMinute(val start: Instant, val metres: Double)

/** One page of an ascent pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsAscentPage(
    val minutes: List<WearOsAscentMinute>,
    val hasMore: Boolean,
)

/** One night-time HRV window the watch measured: its start, the RMSSD and the clean beats it rests on. */
data class WearOsHrvWindow(val start: Instant, val rmssdMillis: Double, val beats: Int)

/** One page of an HRV pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsHrvPage(
    val windows: List<WearOsHrvWindow>,
    val hasMore: Boolean,
)

/** One GPS fix of a watch workout. Altitude and accuracy in metres, null when the fix had none. */
data class WearOsWorkoutPoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double?,
    val accuracyMetres: Double?,
)

/**
 * One finished workout the watch recorded. [exerciseType] is the Health Connect type; the totals are
 * null where the watch measured none.
 */
data class WearOsWorkout(
    val start: Instant,
    val end: Instant,
    val exerciseType: Int,
    val steps: Int?,
    val distanceMetres: Int?,
    val ascentMetres: Double?,
    val pauses: List<ClosedRange<Instant>>,
    val route: List<WearOsWorkoutPoint>,
)

/** One page of a workout pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsWorkoutPage(
    val workouts: List<WearOsWorkout>,
    val hasMore: Boolean,
)

/** One entry the wearer logged on the watch, in metric; [value2] is a blood pressure's diastolic. */
data class WearOsLogEntry(
    val time: Instant,
    val kind: WearLinkProtocol.LogKind,
    val value: Double,
    val value2: Double?,
)

/** One page of a logged-entry pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsLogEntryPage(
    val entries: List<WearOsLogEntry>,
    val hasMore: Boolean,
)

/** A switch the phone pushes to the watch app; the phone is the watch's settings screen. All on by default, so an added watch keeps recording what it did; only [DEBUG_DATA] starts off. */
enum class WearOsWatchSetting(val protocolKey: String, val defaultOn: Boolean) {
    /** Whether the watch records sleep at all. */
    SLEEP_RECORDING(WearLinkProtocol.Preferences.SLEEP_RECORDING, defaultOn = true),

    /** Whether the watch measures heart rate. Off measures nothing, whatever the intervals say. */
    HEART_RATE(WearLinkProtocol.Preferences.HEART_RATE, defaultOn = true),

    /** Whether the watch counts steps. */
    STEPS(WearLinkProtocol.Preferences.STEPS, defaultOn = true),

    /** Whether the watch records height climbed. */
    ASCENT(WearLinkProtocol.Preferences.ASCENT, defaultOn = true),

    /**
     * Whether the watch and this phone keep debug files for `adb pull`. Off by default, unlike the others.
     * The phone's own file log follows this switch; see `WearOsLog`.
     */
    DEBUG_DATA(WearLinkProtocol.Preferences.DEBUG_DATA, defaultOn = false),
}

/** Which part of the day a heart rate interval applies to: by day, or in bedtime mode when sleep recording is off. */
enum class WearOsHeartRatePeriod(val protocolKey: String) {
    DAY(WearLinkProtocol.Preferences.HEART_RATE_DAY),
    NIGHT(WearLinkProtocol.Preferences.HEART_RATE_NIGHT),
}

/**
 * Port for talking to the OpenVitals app on a Wear OS watch.
 *
 * A missing Bluetooth permission surfaces as a [SecurityException], so the
 * screen can turn it into a grant affordance.
 */
interface WearOsNodePort {
    /**
     * [targetAddress] is the registered address, [targetName] the advertised
     * name. Both are hints: the address stored at onboarding is the BLE scan
     * address, which on most watches differs from the bonded Classic one.
     */
    suspend fun checkStatus(
        targetAddress: String? = null,
        targetName: String? = null,
    ): WearOsCompanionStatus

    /**
     * Heart rate samples newer than [since], oldest first, up to the watch's
     * page limit. Null when no bonded watch matches. Throws a
     * [WearOsLinkFailure] for everything else the watch could not give.
     */
    suspend fun pullHeartRate(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHeartRatePage?

    /**
     * Sleep minutes newer than [since], oldest first, up to the watch's page
     * limit. Null when no bonded watch matches. Throws a [WearOsLinkFailure]
     * for everything else the watch could not give.
     */
    suspend fun pullSleepMinutes(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsSleepMinutePage?

    /**
     * Step minutes newer than [since], oldest first, up to the watch's page
     * limit. Empty page: the watch app is too old to count steps. Null: no
     * bonded watch matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullSteps(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsStepPage?

    /**
     * Ascent minutes newer than [since], oldest first, up to the watch's page
     * limit. Empty page: the watch app is too old to send ascent, or the
     * watch has no barometer. Null: no bonded watch matches. Any other
     * failure throws [WearOsLinkFailure].
     */
    suspend fun pullAscent(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsAscentPage?

    /**
     * Night-time HRV windows newer than [since], oldest first, up to the
     * watch's page limit. Empty page: the watch app is too old, has no raw
     * PPG sensor or has the measurement switched off. Null: no bonded watch
     * matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullHrv(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHrvPage?

    /**
     * Workouts that started after [since], oldest first, at most a few per page. Empty page: the watch
     * app is too old to record workouts. Null: no bonded watch matches. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun pullWorkouts(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsWorkoutPage? = WearOsWorkoutPage(emptyList(), false)

    /**
     * Entries the wearer logged on the watch, newer than [since], oldest
     * first, up to the watch's page limit. Empty page: the watch app is too
     * old to keep a log. Null: no bonded watch matches. Any other failure
     * throws [WearOsLinkFailure].
     */
    suspend fun pullLogEntries(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsLogEntryPage?

    /**
     * Sends [values] and the step [profile] to the watch on one connection. The number the watch kept; 0 when the watch
     * app is too old to take them. Null: no bonded watch matches. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun pushDayValues(
        targetAddress: String?,
        targetName: String?,
        values: List<WearOsDayValue>,
        profile: WearOsStepProfile,
    ): Int? = 0

    /**
     * Holds one connection and hands each heart rate reading to [onSample],
     * on an I/O thread, as the watch takes it. Returns true once the watch
     * reports none (it is off the wrist) or the connection nears the watch's
     * age limit; the caller connects again later. False when no bonded watch
     * matches or the watch app cannot stream. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun liveHeartRate(
        targetAddress: String?,
        targetName: String?,
        onSample: (WearOsHeartRateSample) -> Unit,
    ): Boolean

    /**
     * Pushes [setting] to the watch and stores it so later syncs re-apply it.
     * True when the watch applied it. False when the watch app is too old to
     * accept settings, or no bonded watch matches. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun setWatchSetting(
        targetAddress: String?,
        targetName: String?,
        setting: WearOsWatchSetting,
        enabled: Boolean,
    ): Boolean

    /** The last value the phone stored for [setting] on the registered watch; the default when never set. */
    fun watchSetting(targetAddress: String?, setting: WearOsWatchSetting): Boolean

    /**
     * Pushes the heart rate interval for [period] to the watch and stores it.
     * The same contract as [setWatchSetting]: True when applied, false for a
     * watch app too old to accept settings or no bonded watch, and any other
     * failure throws [WearOsLinkFailure].
     */
    suspend fun setHeartRateInterval(
        targetAddress: String?,
        targetName: String?,
        period: WearOsHeartRatePeriod,
        interval: HeartRateInterval,
    ): Boolean

    /** The last interval the phone stored for [period] on the registered watch; [HeartRateInterval.EVERY_10_SECONDS] when never set. */
    fun heartRateInterval(targetAddress: String?, period: WearOsHeartRatePeriod): HeartRateInterval
}
