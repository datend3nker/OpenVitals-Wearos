package tech.mmarca.openvitals.wear.ui.components

import androidx.annotation.StringRes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.UnitSystem
import tech.mmarca.openvitals.wear.DashboardMetric

private const val MetersPerKilometer = 1000.0
private const val MetersPerMile = 1609.344
private const val FeetPerMeter = 3.28084
private const val PoundsPerKilogram = 2.20462
private const val MillilitresPerFluidOunce = 29.5735
private const val MinutesPerHour = 60

fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** [value] is in the metric's base unit. The matching unit label is [metricUnitLabel]. */
fun formatMetricValue(metric: DashboardMetric, value: Double, unitSystem: UnitSystem): String =
    when (metric) {
        DashboardMetric.DISTANCE -> {
            val metersPerUnit = when (unitSystem) {
                UnitSystem.METRIC -> MetersPerKilometer
                UnitSystem.IMPERIAL -> MetersPerMile
            }
            String.format(Locale.getDefault(), "%.1f", value / metersPerUnit)
        }
        DashboardMetric.ELEVATION -> when (unitSystem) {
            UnitSystem.METRIC -> formatCount(value.roundToInt())
            UnitSystem.IMPERIAL -> formatCount((value * FeetPerMeter).roundToInt())
        }
        DashboardMetric.SLEEP -> formatHoursMinutes(value)
        DashboardMetric.WEIGHT -> when (unitSystem) {
            UnitSystem.METRIC -> formatOneDecimal(value)
            UnitSystem.IMPERIAL -> formatOneDecimal(value * PoundsPerKilogram)
        }
        DashboardMetric.HYDRATION -> when (unitSystem) {
            UnitSystem.METRIC -> formatCount(value.roundToInt())
            UnitSystem.IMPERIAL -> formatCount((value / MillilitresPerFluidOunce).roundToInt())
        }
        else -> formatCount(value.roundToInt())
    }

/** [kilograms] in the unit system's body weight unit, one decimal. */
fun formatWeight(kilograms: Double, unitSystem: UnitSystem): String =
    formatMetricValue(DashboardMetric.WEIGHT, kilograms, unitSystem)

/** "7:05" for 425 minutes. */
fun formatHoursMinutes(minutes: Double): String {
    val total = minutes.roundToInt()
    return String.format(Locale.getDefault(), "%d:%02d", total / MinutesPerHour, total % MinutesPerHour)
}

fun formatOneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

/** A clock time in the watch's zone and locale, e.g. "23:47" or "11:47 PM". */
fun formatClockTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(instant.atZone(zone))

@StringRes
fun metricUnitLabel(metric: DashboardMetric, unitSystem: UnitSystem): Int =
    when (metric) {
        DashboardMetric.STEPS -> R.string.unit_steps
        DashboardMetric.HEART_RATE -> R.string.unit_bpm
        DashboardMetric.DISTANCE -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_km
            UnitSystem.IMPERIAL -> R.string.unit_mi
        }
        DashboardMetric.ACTIVE_CALORIES -> R.string.unit_kcal
        DashboardMetric.FLOORS -> R.string.unit_floors
        DashboardMetric.ELEVATION -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_m
            UnitSystem.IMPERIAL -> R.string.unit_ft
        }
        DashboardMetric.BLOOD_OXYGEN -> R.string.unit_percent
        DashboardMetric.VO2_MAX -> R.string.unit_vo2_max
        DashboardMetric.RESTING_HEART_RATE -> R.string.unit_bpm
        DashboardMetric.HRV -> R.string.unit_ms
        DashboardMetric.SLEEP -> R.string.unit_hours
        DashboardMetric.HYDRATION -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_ml
            UnitSystem.IMPERIAL -> R.string.unit_fl_oz
        }
        DashboardMetric.CAFFEINE -> R.string.unit_mg
        DashboardMetric.WEIGHT -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_kg
            UnitSystem.IMPERIAL -> R.string.unit_lb
        }
    }
