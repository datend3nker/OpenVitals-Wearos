package tech.mmarca.openvitals.wear

import tech.mmarca.openvitals.wearlink.WearLinkProtocol
import tech.mmarca.openvitals.wearlink.WearLinkWorkoutLines

/**
 * One series the watch records for the phone, and everything [MetricStore]
 * needs to keep it: a stable key, how a value becomes the row's text and
 * back, its time, what a second value at the same time does, and how long
 * rows stay.
 *
 * The row text is the value's line in the `:wearlink` protocol, so the
 * format is defined and tested once, in that module, and what the store
 * holds is exactly what the phone receives. A new metric is one more
 * definition in [WearMetrics] plus its line in `:wearlink`; the store, its
 * pruning and its tests do not change.
 */
class WearMetric<T>(
    /** The table key. Never reused or renamed: it is how the rows are found. */
    val key: String,
    val encode: (T) -> String,
    /** Null for a row this build cannot read; such a row is skipped, never fatal. */
    val decode: (String) -> T?,
    val timeOf: (T) -> Long,
    val onSameTime: OnSameTime,
    val retentionMillis: Long = WEEK_MILLIS,
) {
    /** What a value does when the store already holds one at its time. */
    enum class OnSameTime {
        /** The first value stays: a sample is final once taken. */
        KEEP,

        /** The new value replaces it: a row that is rewritten as its period fills in. */
        REPLACE,
    }

    override fun toString(): String = "WearMetric($key)"

    companion object {
        /** A week: long enough to miss a few days of syncing, small enough to stay bounded. */
        const val WEEK_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/** Every series the watch records. */
object WearMetrics {

    /** One heart rate sample every ten seconds at most; see `HeartRateRecorder`. */
    val HEART_RATE = WearMetric(
        key = WearLinkProtocol.CAP_HEART_RATE,
        encode = { s: WearLinkProtocol.HeartRateSample -> WearLinkProtocol.formatSample(s.epochMillis, s.bpm) },
        decode = WearLinkProtocol::parseSample,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.KEEP,
    )

    /**
     * One row per clock minute, the sleep pipeline's input; see
     * `docs/engineering/sleep-minute-features.md`. A minute is written again
     * while late readings for it arrive, so the newest row wins.
     */
    val SLEEP_MINUTES = WearMetric(
        key = WearLinkProtocol.CAP_SLEEP_MINUTES,
        encode = WearLinkProtocol::formatSleepMinute,
        decode = WearLinkProtocol::parseSleepMinute,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.REPLACE,
    )

    /**
     * Steps per clock minute; see `StepRecorder`. A minute is written again
     * while late steps for it arrive, so the newest row wins.
     */
    val STEPS = WearMetric(
        key = WearLinkProtocol.CAP_STEPS,
        encode = WearLinkProtocol::formatStepMinute,
        decode = WearLinkProtocol::parseStepMinute,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.REPLACE,
    )

    /**
     * Height climbed per clock minute, in decimetres; see `AscentRecorder`. A
     * minute is written again while late readings arrive, so the newest row
     * wins. Rows of builds that sent whole floors (`fl`) are under another
     * key and are never read as ascent.
     */
    val ASCENT = WearMetric(
        key = WearLinkProtocol.CAP_ASCENT,
        encode = WearLinkProtocol::formatAscentMinute,
        decode = WearLinkProtocol::parseAscentMinute,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.REPLACE,
    )

    /**
     * One row per night-time HRV window, keyed on its start; see
     * `NightHrvRecorder`. A window is final once measured.
     */
    val HRV = WearMetric(
        key = WearLinkProtocol.CAP_HRV,
        encode = WearLinkProtocol::formatHrvWindow,
        decode = WearLinkProtocol::parseHrvWindow,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.KEEP,
    )

    /**
     * One header row per finished workout, keyed on its start; see
     * `WorkoutStore`. The pauses and route points are the two series below, found by
     * the workout's time range. A workout is final once saved.
     */
    val WORKOUTS = WearMetric(
        key = WearLinkProtocol.CAP_WORKOUTS,
        encode = WearLinkWorkoutLines::formatHeader,
        decode = WearLinkWorkoutLines::parseHeader,
        timeOf = { it.startMillis },
        onSameTime = WearMetric.OnSameTime.KEEP,
        retentionMillis = WORKOUT_RETENTION_MILLIS,
    )

    /** The pauses of the workouts, keyed on a pause's start. */
    val WORKOUT_PAUSES = WearMetric(
        key = "wp",
        encode = WearLinkWorkoutLines::formatPause,
        decode = WearLinkWorkoutLines::parsePause,
        timeOf = { it.startMillis },
        onSameTime = WearMetric.OnSameTime.KEEP,
        retentionMillis = WORKOUT_RETENTION_MILLIS,
    )

    /** The GPS fixes of the workouts, keyed on a fix's time. Stored as they arrive, so a killed app keeps its route. */
    val WORKOUT_POINTS = WearMetric(
        key = "wr",
        encode = WearLinkWorkoutLines::formatPoint,
        decode = WearLinkWorkoutLines::parsePoint,
        timeOf = { it.epochMillis },
        onSameTime = WearMetric.OnSameTime.KEEP,
        retentionMillis = WORKOUT_RETENTION_MILLIS,
    )

    /**
     * A day's weight in tenths of a kilogram, sent by the phone with `PUT_DAY`; see
     * [DayValue]. The phone sends the day again whenever it changes, so the newest wins.
     */
    val DAY_WEIGHT = dayMetric("dw")

    /** A day's water in tenths of a millilitre, sent by the phone with `PUT_DAY`. */
    val DAY_HYDRATION = dayMetric("dh")

    private fun dayMetric(key: String) = WearMetric(
        key = key,
        encode = DayValue::encode,
        decode = DayValue::decode,
        timeOf = DayValue::keyMillis,
        onSameTime = WearMetric.OnSameTime.REPLACE,
        retentionMillis = DAY_VALUE_RETENTION_MILLIS,
    )

    /** Nine days, so the seven the watch shows are never cut by the zone's offset. */
    private const val DAY_VALUE_RETENTION_MILLIS: Long = 9L * 24 * 60 * 60 * 1000

    /** A month: a workout is rare and big, and the phone may be away for a holiday. */
    private const val WORKOUT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    val ALL: List<WearMetric<*>> = listOf(HEART_RATE, SLEEP_MINUTES, STEPS, ASCENT, HRV, WORKOUTS, WORKOUT_PAUSES, WORKOUT_POINTS, DAY_WEIGHT, DAY_HYDRATION)
}

/**
 * A value of one day the phone sent: [tenths] of the unit, for the day [epochDay]
 * (days since 1970-01-01 in the wearer's zone). The store keys on [keyMillis], which
 * is only a unique, ordered number per day, not a point in time.
 */
data class DayValue(val epochDay: Long, val tenths: Int) {
    val keyMillis: Long get() = epochDay * DAY_MILLIS
    val value: Double get() = tenths / TENTHS

    fun encode(): String = "$epochDay $tenths"

    companion object {
        private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
        private const val TENTHS = 10.0

        fun decode(line: String): DayValue? {
            val fields = line.trim().split(' ')
            if (fields.size != 2) return null
            return DayValue(fields[0].toLongOrNull() ?: return null, fields[1].toIntOrNull() ?: return null)
        }
    }
}
