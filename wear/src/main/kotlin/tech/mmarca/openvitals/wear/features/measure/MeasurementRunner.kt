package tech.mmarca.openvitals.wear.features.measure

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openvitals.wear.RawPpgSource
import tech.mmarca.openvitals.wear.SensorTime
import tech.mmarca.openvitals.wear.isUsableHeartRate

/**
 * Runs one [Measurement] against the real sensors. Each flow emits
 * [MeasureEvent]s, ends with a [MeasureEvent.Result] and fails with a
 * [MeasureException] when there is no result to give. Collecting stops the
 * sensor, so leaving the screen cancels the measurement.
 */
class MeasurementRunner(private val context: Context) {

    private val rawPpg = RawPpgSource(context)

    fun run(measurement: Measurement): Flow<MeasureEvent> = when (measurement) {
        Measurement.HEART_RATE -> heartRate()
        // Heart rate variability has no button: it is only shown live.
        Measurement.HRV -> flow { throw MeasureException(MeasureError.NOT_SUPPORTED) }
        Measurement.AIR_PRESSURE -> airPressure()
    }

    private fun heartRate(): Flow<MeasureEvent> = flow {
        val readings = mutableListOf<Double>()
        var offBody = false
        var settled: Double? = null
        withTimeoutOrNull(Measurement.HEART_RATE.durationSeconds * 1000L) {
            sensorHeartRate()
                .transformWhile { sample ->
                    when (sample) {
                        is HeartRateSample.Reading -> {
                            offBody = false
                            if (sample.bpm > 0) {
                                readings += sample.bpm
                                emit(MeasureEvent.Live(sample.bpm))
                            }
                            settled = settledHeartRate(readings)
                        }
                        is HeartRateSample.Waiting -> {
                            offBody = sample.offBody
                            emit(MeasureEvent.Waiting(sample.offBody))
                        }
                    }
                    settled == null
                }
                .collect { emit(it) }
        }
        val value = settled
            ?: throw MeasureException(if (offBody) MeasureError.OFF_BODY else MeasureError.NO_SIGNAL)
        emit(MeasureEvent.Result(value))
    }

    /**
     * The measurement's value as it goes, for a screen that shows it: no end,
     * no result, and the sensor stops with the collector. HRV is the RMSSD of
     * the last three minutes of raw PPG, first seen once two and a half
     * minutes are in (see [HrvSpot]) and renewed every [HrvLiveMillis].
     */
    fun live(measurement: Measurement): Flow<Double> = when (measurement) {
        Measurement.HEART_RATE ->
            sensorHeartRate().mapNotNull { (it as? HeartRateSample.Reading)?.bpm?.takeIf { bpm -> bpm > 0 } }
        Measurement.HRV -> rawPpg.windows(everyMillis = HrvLiveMillis, windowMillis = HrvSpot.WINDOW_MILLIS)
            .mapNotNull { HrvSpot.read(it).rmssdMillis }
        Measurement.AIR_PRESSURE -> sensorEvents(Sensor.TYPE_PRESSURE).mapNotNull { it.values.firstOrNull()?.toDouble() }
    }

    private fun airPressure(): Flow<MeasureEvent> = flow {
        val readings = mutableListOf<Double>()
        withTimeoutOrNull(Measurement.AIR_PRESSURE.durationSeconds * 1000L) {
            sensorEvents(Sensor.TYPE_PRESSURE)
                .transformWhile { event ->
                    val hpa = event.values[0].toDouble()
                    readings += hpa
                    emit(MeasureEvent.Live(hpa))
                    readings.size < PressureReadingsNeeded
                }
                .collect { emit(it) }
        }
        if (readings.size < PressureReadingsNeeded) throw MeasureException(MeasureError.NO_SIGNAL)
        val hpa = readings.average()
        val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa.toFloat())
        emit(MeasureEvent.Result(value = hpa, secondary = altitude.toDouble()))
    }

    /**
     * The heart rate sensor, unbatched, as the recorder reads it: no contact
     * means off the wrist, an unreliable reading means still settling.
     */
    private fun sensorHeartRate(): Flow<HeartRateSample> =
        sensorEvents(Sensor.TYPE_HEART_RATE).mapNotNull { event ->
            val bpm = event.values.firstOrNull() ?: return@mapNotNull null
            // The cached reading handed to a new listener carries the time the sensor last ran.
            if (SensorTime.isStale(SensorTime.epochMillisOf(event.timestamp))) return@mapNotNull null
            when {
                event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT -> HeartRateSample.Waiting(offBody = true)
                isUsableHeartRate(event.accuracy, bpm.toInt()) -> HeartRateSample.Reading(bpm.toDouble())
                else -> HeartRateSample.Waiting(offBody = false)
            }
        }

    private fun sensorEvents(type: Int): Flow<SensorEvent> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(type) ?: throw MeasureException(MeasureError.NOT_SUPPORTED)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        } catch (e: SecurityException) {
            throw MeasureException(MeasureError.PERMISSION_DENIED, e)
        }
        if (!registered) throw MeasureException(MeasureError.FAILED)
        awaitClose { manager.unregisterListener(listener) }
    }

    private sealed interface HeartRateSample {
        data class Reading(val bpm: Double) : HeartRateSample
        data class Waiting(val offBody: Boolean) : HeartRateSample
    }

    private companion object {
        const val PressureReadingsNeeded = 5
        const val HrvLiveMillis = 10_000L
    }
}
