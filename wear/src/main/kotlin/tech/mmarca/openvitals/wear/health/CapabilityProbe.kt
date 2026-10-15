package tech.mmarca.openvitals.wear.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import tech.mmarca.openvitals.wear.MetricTile

/** The sensors that decide what the watch offers. */
data class WatchSensors(
    val heartRate: Boolean,
    val stepCounter: Boolean,
    val barometer: Boolean,
)

/** Reads the sensor list, nothing else: the watch app stays on AOSP APIs, without Health Services. */
class CapabilityProbe(private val context: Context) {

    fun probe(): WearCapabilities {
        val sensors = context.getSystemService(SensorManager::class.java)
        val has = { type: Int -> sensors?.getDefaultSensor(type) != null }
        return capabilitiesOf(
            WatchSensors(
                heartRate = has(Sensor.TYPE_HEART_RATE),
                stepCounter = has(Sensor.TYPE_STEP_COUNTER),
                barometer = has(Sensor.TYPE_PRESSURE),
            ),
        )
    }
}

/**
 * The metrics a recorder on this watch feeds. Distance and active calories
 * come from the step minutes: the phone derives them. Blood oxygen and
 * VO2 max have no recorder, so they are never offered.
 */
fun capabilitiesOf(sensors: WatchSensors): WearCapabilities {
    val sensorMetrics = buildSet {
        if (sensors.stepCounter) addAll(listOf(MetricTile.STEPS, MetricTile.DISTANCE, MetricTile.ACTIVE_CALORIES))
        if (sensors.heartRate) add(MetricTile.HEART_RATE)
        if (sensors.barometer) addAll(listOf(MetricTile.FLOORS, MetricTile.ELEVATION))
    }
    return WearCapabilities(probed = true, sensorMetrics = sensorMetrics)
}
