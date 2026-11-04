package tech.mmarca.openvitals.wear.features.measure

import tech.mmarca.openvitals.domain.insights.HrvRmssd
import tech.mmarca.openvitals.domain.insights.PpgBeats
import tech.mmarca.openvitals.wear.PpgBuffer

/**
 * What the HRV button asks of the raw PPG it has gathered so far. The beat
 * finder and RMSSD are `:healthcalc`'s (`PpgBeats`, `HrvRmssd`, the same as
 * the night windows); the only rule added here is how much signal a spot
 * reading waits for.
 */
internal object HrvSpot {

    /**
     * The trailing window the beats are found in. A gap in the signal (the
     * wrist moved, the band slipped) spoils the window it falls in, and a
     * shorter one lets it scroll out. This app's own choice, not from a paper.
     */
    const val WINDOW_MILLIS = 180_000L

    /**
     * A result is given only from at least this much signal: `HrvRmssd` also
     * wants 100 clean beats, which at rest is about two minutes. This app's
     * own choice, not from a paper; the night windows use five minutes.
     */
    const val MIN_SPAN_MILLIS = 150_000.0

    /** [rmssdMillis] is null until the window is long enough and clean enough. */
    data class Reading(val rmssdMillis: Double?)

    fun read(snapshot: PpgBuffer.Snapshot): Reading {
        if (snapshot.spanMillis < MIN_SPAN_MILLIS) return Reading(null)
        val beats = PpgBeats.detect(snapshot.times, snapshot.values) ?: return Reading(null)
        return Reading(HrvRmssd.of(beats)?.rmssdMillis)
    }
}
