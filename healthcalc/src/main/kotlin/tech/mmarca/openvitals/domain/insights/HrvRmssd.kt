package tech.mmarca.openvitals.domain.insights

import kotlin.math.abs
import kotlin.math.sqrt

/** What one window of beats gave: the RMSSD and how many beats it rests on. */
data class HrvWindowResult(
    val rmssdMillis: Double,
    /** Beat-to-beat intervals that took part in at least one clean successive pair. */
    val beatCount: Int,
)

/**
 * RMSSD from beat times, after the intervals that cannot be a resting heart
 * are taken out.
 *
 * RMSSD is the root mean square of the differences between successive
 * normal-to-normal intervals (Task Force of the ESC and NASPE, 1996). Only a
 * difference between two intervals that both passed counts, so a missed or
 * doubled beat never turns into one huge step. The artefact rule is in the
 * spirit of Kubios' threshold correction: an interval further than a quarter
 * from the median of its neighbours is dropped. Not validated against an ECG
 * reference.
 */
object HrvRmssd {

    /** 200 bpm. */
    const val MIN_INTERVAL_MILLIS = 300.0

    /** 30 bpm. */
    const val MAX_INTERVAL_MILLIS = 2000.0

    /** An interval this far from the local median is an artefact. */
    const val MAX_DEVIATION_FROM_MEDIAN = 0.25

    /** A successive difference above this share of the local median is no resting rhythm. */
    const val MAX_STEP_OF_MEDIAN = 0.30

    /** Intervals on each side that make the local median. */
    private const val MEDIAN_RADIUS = 5

    /** Fewer clean beats than this and the window is dropped (a five-minute window at rest holds 250 to 350). */
    const val MIN_CLEAN_BEATS = 100

    /** Share of detected intervals that must be clean, else the signal was poor. */
    const val MIN_CLEAN_SHARE = 0.6

    /** An RMSSD above this is a detector failure, not a heart; it is also the most Health Connect takes. */
    const val MAX_PLAUSIBLE_RMSSD_MILLIS = 200.0

    /**
     * [beatMillis] are beat times in milliseconds, ascending. Null when too
     * few beats are clean, or the result is not physiological.
     */
    fun of(beatMillis: DoubleArray): HrvWindowResult? {
        if (beatMillis.size < 3) return null
        val intervals = DoubleArray(beatMillis.size - 1) { beatMillis[it + 1] - beatMillis[it] }
        val good = cleanIntervals(intervals)
        var sumSquares = 0.0
        var pairs = 0
        val used = BooleanArray(intervals.size)
        for (i in 0 until intervals.size - 1) {
            if (!good[i] || !good[i + 1]) continue
            val step = intervals[i + 1] - intervals[i]
            if (abs(step) > MAX_STEP_OF_MEDIAN * localMedian(intervals, i)) continue
            sumSquares += step * step
            pairs++
            used[i] = true
            used[i + 1] = true
        }
        val beats = used.count { it }
        if (pairs == 0 || beats < MIN_CLEAN_BEATS || beats < MIN_CLEAN_SHARE * intervals.size) return null
        val rmssd = sqrt(sumSquares / pairs)
        if (rmssd > MAX_PLAUSIBLE_RMSSD_MILLIS) return null
        return HrvWindowResult(rmssd, beats)
    }

    private fun cleanIntervals(intervals: DoubleArray): BooleanArray = BooleanArray(intervals.size) { i ->
        val value = intervals[i]
        value in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS &&
            abs(value - localMedian(intervals, i)) <= MAX_DEVIATION_FROM_MEDIAN * localMedian(intervals, i)
    }

    /** Median of the plausible intervals around [index], the interval itself included. */
    private fun localMedian(intervals: DoubleArray, index: Int): Double {
        val from = maxOf(0, index - MEDIAN_RADIUS)
        val to = minOf(intervals.size - 1, index + MEDIAN_RADIUS)
        val near = (from..to).map { intervals[it] }.filter { it in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS }.sorted()
        if (near.isEmpty()) return intervals[index]
        val middle = near.size / 2
        return if (near.size % 2 == 1) near[middle] else (near[middle - 1] + near[middle]) / 2
    }
}
