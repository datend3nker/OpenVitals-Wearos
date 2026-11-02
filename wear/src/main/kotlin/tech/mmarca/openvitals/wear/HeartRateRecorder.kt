package tech.mmarca.openvitals.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/**
 * Keeps the heart rate sensor on at the interval the wearer chose and writes
 * what it reports to the [MetricStore]. Runs inside `WearAppService`, so it
 * outlives the activity.
 *
 * Two ways of running, one per interval:
 * - [HeartRateInterval.EVERY_10_SECONDS]: the sensor stays registered, batched:
 *   the sensor hub buffers readings for up to [MAX_REPORT_LATENCY_MICROS] and
 *   wakes the CPU once per batch. One stored sample every ten seconds.
 * - [HeartRateInterval.EVERY_MINUTE]: the sensor is registered for a window of
 *   at most [WINDOW_MILLIS] at the start of each minute, unbatched, and is
 *   unregistered as soon as its first usable reading is stored. Between the
 *   windows the sensor is off, so this is not only thinner storage. The window
 *   is not batched: a batch could not be flushed before the unregistration
 *   without a race, and the reading has to be stored before the sensor goes.
 *
 * [setInterval] is the only entry point; the service calls it with the
 * interval in force, and an unchanged one does nothing. The listener and the
 * timers run on the recorder's own thread, where the SQLite write is allowed.
 */
class HeartRateRecorder(
    context: Context,
    private val store: MetricStore,
    /** Every stored sample, for the sleep minute recorder. Called on this recorder's thread. */
    private val onSample: (epochMillis: Long, bpm: Int) -> Unit = { _, _ -> },
    /** False when the sensor reported no contact, an unreliable reading or 0 bpm. */
    private val onContact: (epochMillis: Long, contact: Boolean) -> Unit = { _, _ -> },
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null

    /** Set with [thread]; the timers and the window run on it. */
    @Volatile
    private var handler: Handler? = null

    /** The interval this recorder runs at; [HeartRateInterval.OFF] while stopped. Read on any thread. */
    @Volatile
    var interval: HeartRateInterval = HeartRateInterval.OFF
        private set

    private var sensor: Sensor? = null
    private var lastStoredAt = 0L
    private var lastPrunedAt = 0L

    // Read and written on the recorder's thread only. The tasks are fields:
    // removing a timer needs the same Runnable that posted it.
    private var windowOpen = false
    private var windowOpenedAt = 0L
    private val openWindowTask = Runnable { openWindow() }
    private val closeWindowTask = Runnable { closeWindow() }

    val isRunning: Boolean
        get() = thread != null

    /**
     * Brings the recorder to [wanted]: starts it, changes its interval or
     * stops it. False when [wanted] is not off and the watch has no heart rate
     * sensor or the registration failed.
     */
    fun setInterval(wanted: HeartRateInterval): Boolean {
        if (wanted == interval && (wanted == HeartRateInterval.OFF || thread != null)) return true
        stop()
        if (wanted == HeartRateInterval.OFF) return true
        return start(wanted)
    }

    private fun start(wanted: HeartRateInterval): Boolean {
        // The wake-up variant empties its batch even while the watch sleeps; the
        // other one hands over only what survived until the next wake.
        val found = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (found == null) {
            Log.w(TAG, "No heart rate sensor")
            return false
        }
        val worker = HandlerThread("HeartRateRecorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        sensor = found
        thread = worker
        handler = workerHandler
        interval = wanted
        if (wanted == HeartRateInterval.EVERY_10_SECONDS) {
            val registered = registerBatched(found, workerHandler)
            if (!registered) {
                Log.w(TAG, "Heart rate listener not registered")
                stop()
                return false
            }
        } else {
            workerHandler.post(openWindowTask)
        }
        Log.i(TAG, "Recording heart rate from ${found.name} every ${wanted.seconds} s")
        return true
    }

    /** Registration for the continuous interval: one reading a second, batched. */
    private fun registerBatched(found: Sensor, workerHandler: Handler): Boolean =
        try {
            sensorManager.registerListener(
                this,
                found,
                SAMPLING_PERIOD_MICROS,
                MAX_REPORT_LATENCY_MICROS,
                workerHandler,
            )
        } catch (e: SecurityException) {
            // The grant was revoked between the check and the registration.
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }

    fun stop() {
        val worker = thread ?: return
        val workerHandler = handler
        // Pending timers would reopen the sensor after this returns.
        workerHandler?.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        windowOpen = false
        worker.quitSafely()
        thread = null
        handler = null
        sensor = null
        interval = HeartRateInterval.OFF
        Log.i(TAG, "Stopped recording heart rate")
    }

    /** Starts a window: the sensor on, until its first usable reading or [WINDOW_MILLIS]. Recorder thread. */
    private fun openWindow() {
        val workerHandler = handler ?: return
        val found = sensor ?: return
        windowOpenedAt = SystemClock.elapsedRealtime()
        val registered = try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, 0, workerHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }
        if (!registered) {
            Log.w(TAG, "Heart rate window not registered")
            scheduleNextWindow()
            return
        }
        windowOpen = true
        workerHandler.postDelayed(closeWindowTask, WINDOW_MILLIS)
    }

    /**
     * Ends the open window: the sensor off, and the next window at the
     * minute's start. Does nothing when no window is open, so the reading path
     * and the timeout cannot both schedule the next one. Recorder thread.
     */
    private fun closeWindow() {
        if (!windowOpen) return
        handler?.removeCallbacks(closeWindowTask)
        windowOpen = false
        sensorManager.unregisterListener(this)
        scheduleNextWindow()
    }

    private fun scheduleNextWindow() {
        val workerHandler = handler ?: return
        val elapsed = SystemClock.elapsedRealtime() - windowOpenedAt
        val wait = (interval.seconds * 1000L - elapsed).coerceAtLeast(0L)
        workerHandler.postDelayed(openWindowTask, wait)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        // No contact and unreliable readings carry a rate of zero or a flag; both are skipped.
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        val at = SensorTime.epochMillisOf(event.timestamp)
        // The cached reading handed to a new listener carries the time the sensor last ran.
        if (SensorTime.isStale(at)) return
        if (!isUsableHeartRate(event.accuracy, bpm)) {
            noteSkipped(event.accuracy, bpm)
            onContact(at, false)
            return
        }

        if (at - lastStoredAt >= MIN_SAMPLE_GAP_MILLIS) {
            lastStoredAt = at
            store.put(WearMetrics.HEART_RATE, WearLinkProtocol.HeartRateSample(at, bpm))
            onSample(at, bpm)
            Log.d(TAG, "Stored $bpm bpm at $at")
            prune(at)
        }
        // One reading per window is all the minute needs: the sensor goes off now.
        if (interval == HeartRateInterval.EVERY_MINUTE && windowOpen) closeWindow()
    }

    private fun prune(at: Long) {
        if (at - lastPrunedAt > PRUNE_EVERY_MILLIS) {
            lastPrunedAt = at
            store.prune(WearMetrics.HEART_RATE, at)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private var lastSkipLogAt = 0L

    /** Off the wrist the sensor keeps reporting; one line a minute says so without flooding. */
    private fun noteSkipped(accuracy: Int, bpm: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipLogAt < SKIP_LOG_EVERY_MILLIS) return
        lastSkipLogAt = now
        Log.d(TAG, "Skipping readings: accuracy=$accuracy bpm=$bpm")
    }

    private companion object {
        const val TAG = "HeartRateRecorder"

        /** One reading a second from the sensor; the store keeps one in ten. */
        const val SAMPLING_PERIOD_MICROS = 1_000_000

        /**
         * How long the sensor hub may hold readings before waking the CPU.
         * This is the wake-up sensor, so it sets how often the watch wakes at
         * night, and that must fit inside the accelerometer's buffer: that
         * sensor cannot wake the watch, its buffer holds 300 readings, 48
         * seconds at the 6.25 Hz the hub delivers, and everything older is
         * lost. At a minute the first night kept 30 readings a minute.
         */
        const val MAX_REPORT_LATENCY_MICROS = 40 * 1_000_000

        /**
         * Stored sample spacing at the continuous interval. Enough for resting
         * and daily curves; a tenth of the bytes. The per-minute interval stores
         * one reading per window, so it never meets this gap.
         */
        const val MIN_SAMPLE_GAP_MILLIS = 10_000L

        /**
         * The most time the sensor stays on for one minute's reading. A
         * reading usually comes well inside it; the sensor's warm-up on a real
         * watch is not measured here.
         */
        const val WINDOW_MILLIS = 20_000L

        const val PRUNE_EVERY_MILLIS = 6L * 60 * 60 * 1000
        const val SKIP_LOG_EVERY_MILLIS = 60_000L
    }
}

/** False for no contact, an unreliable reading, or a rate outside what a heart does. */
internal fun isUsableHeartRate(accuracy: Int, bpm: Int): Boolean =
    accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT &&
        accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
        bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM
