package tech.mmarca.openvitals.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
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
 * - [HeartRateInterval.POWER_SAVE]: the sensor is registered for a window of
 *   at most [WINDOW_CAP_MILLIS], unbatched, and is unregistered as soon as its
 *   first usable reading is stored. Between the windows the sensor, and
 *   usually the CPU, are off: `Handler.postDelayed` does not fire while the
 *   CPU sleeps, so the window between windows is scheduled with
 *   [AlarmManager.setAndAllowWhileIdle] instead, through [HeartRateAlarmReceiver]
 *   and `WearAppService`. [WINDOW_CAP_MILLIS] is a safety ceiling, not the
 *   normal length of a window: a window usually closes within a few seconds,
 *   on the first usable reading.
 *
 * [setInterval] is the only entry point; the service calls it with the
 * interval in force, and an unchanged one does nothing. The listener and the
 * timers run on the recorder's own thread, where the SQLite write is allowed.
 * [onAlarmFired] is the exception: `WearAppService` calls it on its own
 * thread, and it only posts to the recorder's thread.
 */
class HeartRateRecorder(
    private val context: Context,
    private val store: MetricStore,
    /** Every stored sample, for the sleep minute recorder. Called on this recorder's thread. */
    private val onSample: (epochMillis: Long, bpm: Int) -> Unit = { _, _ -> },
    /** False when the sensor reported no contact, an unreliable reading or 0 bpm. */
    private val onContact: (epochMillis: Long, contact: Boolean) -> Unit = { _, _ -> },
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private val alarmPendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, HeartRateAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
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
        alarmManager.cancel(alarmPendingIntent)
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        windowOpen = false
        worker.quitSafely()
        thread = null
        handler = null
        sensor = null
        interval = HeartRateInterval.OFF
        Log.i(TAG, "Stopped recording heart rate")
    }

    /**
     * An alarm fired while this recorder was [HeartRateInterval.POWER_SAVE].
     * Called by `WearAppService` on its own thread; only posts to the
     * recorder's thread, which does the actual work. A no-op once the
     * interval has since changed: cancelling the alarm in [stop] races the
     * alarm already having fired.
     */
    fun onAlarmFired() {
        val workerHandler = handler ?: return
        if (interval != HeartRateInterval.POWER_SAVE) return
        workerHandler.post(openWindowTask)
    }

    /** Starts a window: the sensor on, until its first usable reading or [WINDOW_CAP_MILLIS]. Recorder thread. */
    private fun openWindow() {
        val workerHandler = handler ?: return
        val found = sensor ?: return
        windowOpenedAt = SystemClock.elapsedRealtime()
        wakeLock.acquire(WINDOW_CAP_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        val registered = try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, 0, workerHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }
        if (!registered) {
            Log.w(TAG, "Heart rate window not registered")
            if (wakeLock.isHeld) wakeLock.release()
            scheduleNextWindow()
            return
        }
        windowOpen = true
        workerHandler.postDelayed(closeWindowTask, WINDOW_CAP_MILLIS)
    }

    /**
     * Ends the open window: the sensor off, and the next window scheduled.
     * Does nothing when no window is open, so the reading path and the
     * timeout cannot both schedule the next one. Recorder thread.
     */
    private fun closeWindow() {
        if (!windowOpen) return
        handler?.removeCallbacks(closeWindowTask)
        windowOpen = false
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        scheduleNextWindow()
    }

    /**
     * Schedules the next window: [AlarmManager.setAndAllowWhileIdle] fires
     * [HeartRateAlarmReceiver] even while the watch is asleep, unlike
     * `Handler.postDelayed`. The wait is the interval minus the time the
     * window already used, never negative.
     */
    private fun scheduleNextWindow() {
        val elapsed = SystemClock.elapsedRealtime() - windowOpenedAt
        val wait = nextWindowDelayMillis(elapsed, interval.seconds)
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + wait, alarmPendingIntent)
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
        // One reading per window is all a power-save window needs: the sensor goes off now.
        if (interval == HeartRateInterval.POWER_SAVE && windowOpen) closeWindow()
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
        const val WAKE_LOCK_TAG = "OpenVitals:heartRatePowerSave"

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
         * and daily curves; a tenth of the bytes. The power-save interval
         * stores one reading per window, so it never meets this gap.
         */
        const val MIN_SAMPLE_GAP_MILLIS = 10_000L

        /**
         * The safety ceiling on a power-save window: the most time the sensor
         * stays on if no usable reading ever arrives. On-device, a reading
         * usually closes the window within a few seconds.
         */
        const val WINDOW_CAP_MILLIS = 20_000L

        /** Held past [WINDOW_CAP_MILLIS] so the lock cannot expire mid-window on a slow start. */
        const val WAKE_LOCK_SLACK_MILLIS = 10_000L

        const val ALARM_REQUEST_CODE = 1

        const val PRUNE_EVERY_MILLIS = 6L * 60 * 60 * 1000
        const val SKIP_LOG_EVERY_MILLIS = 60_000L
    }
}

/** False for no contact, an unreliable reading, or a rate outside what a heart does. */
internal fun isUsableHeartRate(accuracy: Int, bpm: Int): Boolean =
    accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT &&
        accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
        bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM

/**
 * How long to wait, from now, before a power-save window should next open:
 * the interval minus the time the window just used, never negative. Pure, so
 * it is tested without the real clock or [android.app.AlarmManager].
 */
internal fun nextWindowDelayMillis(elapsedSinceOpenMillis: Long, intervalSeconds: Int): Long =
    (intervalSeconds * 1000L - elapsedSinceOpenMillis).coerceAtLeast(0L)

/**
 * Wakes the watch for one power-save heart rate window. `AlarmManager`
 * delivers this while the CPU is asleep; it reaches the running
 * `WearAppService` the way [WearPhoneConfirmReceiver] reaches the trust
 * store, a broadcast that starts the foreground service with an action,
 * never a static reference to the recorder itself.
 */
class HeartRateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            context.startForegroundService(
                Intent(context, WearAppService::class.java).setAction(WearAppService.ACTION_HEART_RATE_ALARM),
            )
        }.onFailure { Log.e("HeartRateAlarmReceiver", "Cannot reach the service for the heart rate alarm", it) }
    }
}
