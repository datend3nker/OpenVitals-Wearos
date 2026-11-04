package tech.mmarca.openvitals.wear

import java.io.File

/** Where [DebugSnapshot] reads rows from: the [MetricStore] on the watch, a list in a test. */
internal interface SnapshotSource {
    /** Up to [limit] rows of [metric] newer than [fromEpochMillis], oldest first. */
    fun <T> since(metric: WearMetric<T>, fromEpochMillis: Long, limit: Int): List<T>
}

internal class StoreSnapshotSource(private val store: MetricStore) : SnapshotSource {
    override fun <T> since(metric: WearMetric<T>, fromEpochMillis: Long, limit: Int): List<T> =
        store.since(metric, fromEpochMillis, limit)
}

/**
 * The last 48 hours of the [MetricStore] as text files in [folder], for the debug switch. Every
 * file is replaced whole through a temporary file, so a reader never sees half a file.
 *
 * A line is the row exactly as the store keeps it, which is the line of the phone link
 * (`WearLinkProtocol.formatSleepMinute` and the others): the `:wearlink` parsers read the files
 * again, and the offline check in `:healthcalc` does.
 *
 * - `minutes.csv`: sleep minutes, one line per minute
 * - `heartrate.csv`, `steps.csv`, `ascent.csv`, `hrv.csv`: the samples
 * - `dayvalues.csv`: the weight and water the phone sent, `dw` or `dh` first
 * - `workouts.csv`: the headers of the workouts, not their route or pauses
 *
 * The files hold values. They stay on the watch until the wearer pulls them.
 */
internal class DebugSnapshot(
    private val folder: File,
    private val source: SnapshotSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Writes all files. Call off the main thread. */
    fun write() {
        folder.mkdirs()
        val from = clock() - WINDOW_MILLIS
        replace("minutes.csv", lines(WearMetrics.SLEEP_MINUTES, from, MINUTE_LIMIT))
        replace("heartrate.csv", lines(WearMetrics.HEART_RATE, from, HEART_RATE_LIMIT))
        replace("steps.csv", lines(WearMetrics.STEPS, from, MINUTE_LIMIT))
        replace("ascent.csv", lines(WearMetrics.ASCENT, from, MINUTE_LIMIT))
        replace("hrv.csv", lines(WearMetrics.HRV, from, HRV_LIMIT))
        replace("workouts.csv", lines(WearMetrics.WORKOUTS, from, WORKOUT_LIMIT))
        // Day values are keyed on the day number, not on a time: all of them are kept.
        val days = listOf(WearMetrics.DAY_WEIGHT, WearMetrics.DAY_HYDRATION).flatMap { metric ->
            lines(metric, -1L, DAY_LIMIT).map { "${metric.key} $it" }
        }
        replace("dayvalues.csv", days)
    }

    private fun <T> lines(metric: WearMetric<T>, from: Long, limit: Int): List<String> =
        source.since(metric, from, limit).map(metric.encode)

    private fun replace(name: String, lines: List<String>) {
        val target = File(folder, name)
        val temporary = File(folder, "$name.tmp")
        temporary.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        check(temporary.renameTo(target)) { "Cannot replace $name" }
    }

    companion object {
        const val WINDOW_MILLIS = 48L * 60 * 60 * 1000

        /** 2 880 minutes in 48 hours, with room. */
        private const val MINUTE_LIMIT = 4_000

        /** One sample every ten seconds is 17 280 in 48 hours. */
        private const val HEART_RATE_LIMIT = 20_000
        private const val HRV_LIMIT = 500
        private const val WORKOUT_LIMIT = 100
        private const val DAY_LIMIT = 100
    }
}
