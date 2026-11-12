package tech.mmarca.openvitals.domain.insights

import kotlin.math.ln
import kotlin.math.sqrt

/** Fewer days than this and the spread of the baseline means nothing. */
private const val MinimumBaselineDays = 7

/** The smallest worthwhile change, as a share of the baseline's standard deviation. */
private const val SmallestWorthwhileChangeSd = 0.5

/**
 * A personal HRV baseline, on the natural log of rMSSD, and the band around it
 * that counts as normal: the mean plus or minus the smallest worthwhile change,
 * half the day-to-day standard deviation. The log tames rMSSD's skew; the band
 * moves with each person's own variability instead of a fixed share of the mean.
 * Plews, Laursen, Stanley, Kilding and Buchheit, "Training adaptation and heart
 * rate variability in elite endurance athletes", Sports Med 2013;43(9):773–781,
 * doi:10.1007/s40279-013-0071-8; Buchheit, "Monitoring training status with HR
 * measures: do all roads lead to Rome?", Front Physiol 2014;5:73,
 * doi:10.3389/fphys.2014.00073.
 */
data class HrvBaseline(
    val lnRmssdMean: Double,
    val lnRmssdSd: Double,
) {
    /** How far [rmssdMs] lies from the mean, in smallest worthwhile changes; null for a value that is not positive. */
    fun worthwhileChangesFromMean(rmssdMs: Double): Double? {
        if (rmssdMs <= 0.0) return null
        return (ln(rmssdMs) - lnRmssdMean) / (SmallestWorthwhileChangeSd * lnRmssdSd)
    }

    companion object {
        /** The baseline of [dailyRmssdMs], one value a day; null with fewer than a week of positive values or no spread. */
        fun of(dailyRmssdMs: List<Double>): HrvBaseline? {
            val logs = dailyRmssdMs.filter { it > 0.0 }.map { ln(it) }
            if (logs.size < MinimumBaselineDays) return null
            val mean = logs.average()
            val sd = sqrt(logs.sumOf { (it - mean) * (it - mean) } / (logs.size - 1))
            // Identical nights leave rounding noise, not a spread.
            if (sd < 1e-9) return null
            return HrvBaseline(lnRmssdMean = mean, lnRmssdSd = sd)
        }
    }
}
