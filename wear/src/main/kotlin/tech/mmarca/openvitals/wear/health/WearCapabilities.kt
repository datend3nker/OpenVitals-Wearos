package tech.mmarca.openvitals.wear.health

import tech.mmarca.openvitals.wear.MetricSource
import tech.mmarca.openvitals.wear.MetricTile

/** What this watch can measure, read from its sensor list. The UI offers no sensor reading beyond it. */
data class WearCapabilities(
    /** False until the probe has answered. */
    val probed: Boolean = false,
    /** Metrics the watch records by itself. Phone and logged metrics are always on offer. */
    val sensorMetrics: Set<MetricTile> = emptySet(),
) {
    /** The metrics to offer as tiles: every phone and logged metric, sensor metrics if measured here. */
    val availableMetrics: Set<MetricTile>
        get() = MetricTile.entries.filterTo(mutableSetOf()) {
            it.source != MetricSource.SENSOR || it in sensorMetrics
        }
}
