package tech.mmarca.openvitals.wear.features.measure

/** Readings a heart rate measurement needs before it settles. */
internal const val HeartRateReadingsNeeded = 8

/**
 * The settled pulse from a run of readings: the median of the last five, so
 * one stray reading while the sensor locks on does not move it. Zero readings
 * mean "no signal" and are ignored.
 */
internal fun settledHeartRate(readings: List<Double>): Double? {
    val valid = readings.filter { it in 25.0..250.0 }
    if (valid.size < HeartRateReadingsNeeded) return null
    return valid.takeLast(5).sorted()[2]
}
