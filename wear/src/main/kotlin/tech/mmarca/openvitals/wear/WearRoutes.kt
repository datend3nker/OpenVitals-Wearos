package tech.mmarca.openvitals.wear

import tech.mmarca.openvitals.wear.features.measure.Measurement

/** Navigation routes. Shared with the tiles, which open the app at some of them. */
object WearRoutes {
    const val DASHBOARD = "dashboard"
    const val TILE_EDITOR = "tile_editor"
    const val METRIC_ARG = "metric"
    const val METRIC = "metric/{$METRIC_ARG}"
    fun metric(metric: DashboardMetric) = "metric/${metric.name}"
    const val ACTIVITY_PICKER = "activity_picker"
    const val RECORDING = "recording"
    const val WORKOUT_SUMMARY = "workout_summary"
    const val SETTINGS = "settings"
    const val TRACKING = "tracking"
    const val STATUS = "status"
    const val PHONE = "phone"
    const val SENSORS = "sensors"
    const val MEASURE = "measure"
    const val MEASUREMENT_ARG = "measurement"
    const val MEASUREMENT = "measure/{$MEASUREMENT_ARG}"
    fun measurement(measurement: Measurement) = "measure/${measurement.name}"
    const val QUICK_LOG = "quick_log"
    const val QUICK_LOG_EDITOR = "quick_log_editor"
    const val LOG_WEIGHT = "log_weight"
    const val LOG_BLOOD_PRESSURE = "log_blood_pressure"
    const val LOG_VALUE_ARG = "type"
    const val LOG_VALUE = "log_value/{$LOG_VALUE_ARG}"
    fun logValue(type: tech.mmarca.openvitals.wear.features.quicklog.EntryType) = "log_value/${type.name}"
    const val BREATHE = "breathe"
    const val LOG_CAFFEINE = "log_caffeine"

    /** Routes an outside caller, such as a tile, may open. Anything else is ignored. */
    fun isDeepLinkable(route: String): Boolean =
        route in setOf(QUICK_LOG, MEASURE, LOG_WEIGHT, LOG_BLOOD_PRESSURE, BREATHE, LOG_CAFFEINE) ||
            Measurement.entries.any { route == measurement(it) }
}
