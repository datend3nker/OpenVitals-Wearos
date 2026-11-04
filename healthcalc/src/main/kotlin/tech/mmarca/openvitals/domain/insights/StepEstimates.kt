package tech.mmarca.openvitals.domain.insights

import tech.mmarca.openvitals.domain.preferences.StrideLength

/**
 * What a day's steps are worth in distance and active energy, the way the phone works them out:
 * distance is steps times the step length ([StrideLength]), energy is [WalkingEnergy] per minute.
 * Both are estimates from steps alone.
 */
object StepEstimates {

    /** Metres for [steps] at [strideMeters], which is clamped to the bounds of [StrideLength]. */
    fun distanceMetres(steps: Double, strideMeters: Double): Double =
        steps * StrideLength.normalize(strideMeters)

    /** Active kilocalories for the minutes of [stepsPerMinute]; zero with no known weight. */
    fun activeKcal(stepsPerMinute: Iterable<Int>, weightKg: Double?): Double {
        if (weightKg == null || weightKg <= 0.0) return 0.0
        return stepsPerMinute.sumOf { WalkingEnergy.activeKcalPerMinute(it, weightKg) }
    }
}
