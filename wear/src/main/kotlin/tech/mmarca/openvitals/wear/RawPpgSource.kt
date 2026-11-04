package tech.mmarca.openvitals.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.domain.insights.NightHrv
import tech.mmarca.openvitals.wear.features.measure.MeasureError
import tech.mmarca.openvitals.wear.features.measure.MeasureException

/**
 * Samsung's raw PPG (`com.samsung.sensor.hr_raw`, 25 Hz) for a screen that
 * wants to work out heart rate variability while it is open. It is the same
 * sensor, channel and unbatched registration as [NightHrvRecorder], and the
 * math is `:healthcalc`'s `NightHrv`; this class only owns the sensor, a
 * partial wake lock (the batched stream loses beats when the processor
 * dozes) and a trailing [PpgBuffer].
 *
 * Collecting starts the sensor and holds the wake lock; cancelling stops
 * both, so leaving the screen ends it.
 */
class RawPpgSource(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Whether the watch has the raw PPG sensor. Without it there is no HRV. */
    val isAvailable: Boolean
        get() = find(sensorManager) != null

    /**
     * A copy of the trailing [windowMillis] of samples every [everyMillis]
     * for as long as the flow is collected. Fails with a [MeasureException]
     * when there is no sensor, no permission, or the sensor cannot start.
     */
    fun windows(everyMillis: Long, windowMillis: Long = NightHrv.WINDOW_MILLIS): Flow<PpgBuffer.Snapshot> = channelFlow {
        val sensor = find(sensorManager) ?: throw MeasureException(MeasureError.NOT_SUPPORTED)
        val buffer = PpgBuffer(windowMillis)
        val startedAt = System.currentTimeMillis()
        val thread = HandlerThread("RawPpgSource").apply { start() }
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size <= CHANNEL) return
                // The cached reading a new listener is handed carries the time the sensor last ran.
                if (SensorTime.epochMillisOf(event.timestamp) < startedAt - STALE_MILLIS) return
                // The channel carries an integer's bits in a float.
                buffer.add(event.timestamp / 1_000_000.0, java.lang.Float.floatToRawIntBits(event.values[CHANNEL]).toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            wakeLock.acquire(MAX_HOLD_MILLIS)
            val registered = try {
                sensorManager.registerListener(listener, sensor, sensor.minDelay.coerceAtLeast(1), 0, Handler(thread.looper))
            } catch (e: SecurityException) {
                throw MeasureException(MeasureError.PERMISSION_DENIED, e)
            }
            if (!registered) throw MeasureException(MeasureError.FAILED)
            emitEvery(everyMillis, buffer)
            awaitCancellation()
        } finally {
            sensorManager.unregisterListener(listener)
            if (wakeLock.isHeld) wakeLock.release()
            thread.quitSafely()
        }
    }

    private fun ProducerScope<PpgBuffer.Snapshot>.emitEvery(everyMillis: Long, buffer: PpgBuffer) {
        launch {
            while (true) {
                delay(everyMillis)
                send(buffer.snapshot())
            }
        }
    }

    companion object {
        /** Raw PPG channel `v5`: the pulsatile one, found by the research spike. */
        const val CHANNEL = 5

        private const val WAKE_LOCK_TAG = "OpenVitals:livePpg"
        private const val STALE_MILLIS = 1_000L

        /** A screen left open on a forgotten wrist stops holding the processor after this. */
        private const val MAX_HOLD_MILLIS = 15L * 60 * 1000

        /** The sensor, found by its vendor string type. Null on a watch without it. */
        fun find(sensorManager: SensorManager): Sensor? =
            sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == PpgRawLogger.SENSOR_TYPE }
    }
}
