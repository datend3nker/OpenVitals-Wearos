package tech.mmarca.openvitals.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import tech.mmarca.openvitals.domain.workout.WorkoutSport
import tech.mmarca.openvitals.wear.features.quicklog.EntryLog
import tech.mmarca.openvitals.wear.workout.MetricStoreWorkoutRows
import tech.mmarca.openvitals.wear.workout.WorkoutRecorder
import tech.mmarca.openvitals.wear.workout.WorkoutStore
import tech.mmarca.openvitals.wearlink.HeartRateInterval

/**
 * The watch's end of the phone link, and the host of the heart rate, step,
 * ascent and sleep minute recorders. The heart rate rests while the watch
 * is off the wrist ([WornMonitor]).
 *
 * Which recorders run is one decision, made in [reconcileRecorders] from the
 * settings the phone pushed ([WearPreferences]), bedtime mode ([BedtimeMonitor])
 * the worn state and the grants. Every change of any of them calls it again.
 *
 * The link itself is [WearLinkServerHost] over `:wearlink`; this service
 * keeps it, the recorders and the trust store alive as a foreground
 * service (a plain background service is stopped about a minute after the
 * activity closes). Its type is `connectedDevice`, plus `health` once the
 * heart rate or the step counter is granted. Bluetooth off closes the listener; on again
 * reopens it; a bond change refreshes which trusted phones are still paired.
 *
 * The phone side is `app/.../devices/wearos/BluetoothWearOsNodePort`.
 */
class WearAppService : Service() {

    /** False when the system refused the foreground start: nothing may run then. */
    private var isForeground = false

    private lateinit var store: MetricStore
    private lateinit var recorder: HeartRateRecorder
    private lateinit var stepRecorder: StepRecorder
    private lateinit var ascentRecorder: AscentRecorder
    private lateinit var minuteRecorder: SleepMinuteRecorder
    private lateinit var nightHrv: NightHrvRecorder
    private lateinit var workout: WorkoutRecorder
    private lateinit var preferences: WearPreferences
    private lateinit var bedtimeMonitor: BedtimeMonitor
    private lateinit var wornMonitor: WornMonitor
    private lateinit var ppgLogger: PpgRawLogger
    private lateinit var trust: WearTrustStore
    private lateinit var diagnostics: DiagnosticsSampler
    private lateinit var link: WearLinkServerHost
    private val mainHandler = Handler(Looper.getMainLooper())

    /** False while [WornMonitor] says the watch is off the wrist. Main thread only. */
    private var heartRateWanted = true

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        WearLinkState.update { it.copy(bluetoothOn = true) }
                        link.start()
                        refreshBondLost()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        WearLinkState.update { it.copy(bluetoothOn = false) }
                        link.stop()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBondLost()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = MetricStore(this)
        // Off the main thread: a week of heart rate is tens of thousands of rows.
        Thread({ LegacyStoreImport.run(this, store) }, "LegacyStoreImport").start()
        ppgLogger = PpgRawLogger(this)
        minuteRecorder = SleepMinuteRecorder(
            this,
            store,
            isHeartRateRecording = ::isHeartRateDense,
            stepsInMinute = ::stepsInMinute,
            // The heart rate runs at full rate while sleep is held; the schedule reads it.
            onSleepHoldChange = { mainHandler.post { reconcileRecorders() } },
        )
        nightHrv = NightHrvRecorder(
            this,
            store,
            isHolding = {
                WearLinkState.state.value.let { it.bedtimeHold == BedtimeHold.HOLDING || it.sleepHoldActive }
            },
        )
        recorder = HeartRateRecorder(
            this,
            store,
            onSample = { at, bpm ->
                minuteRecorder.noteHeartRate(at, bpm)
                if (workout.isActive) workout.noteHeartRate(at, bpm)
            },
            onContact = minuteRecorder::noteHeartRateContact,
        )
        wornMonitor = WornMonitor(
            this,
            onWorn = minuteRecorder::noteWorn,
            onHeartRateWanted = { wanted -> mainHandler.post { setHeartRateWanted(wanted) } },
        )
        stepRecorder = StepRecorder(this, store)
        workout = WorkoutRecorder(this, store) {
            // The location type is held only while a workout reads the GPS.
            if (isForeground) startInForeground()
            mainHandler.post(::reconcileRecorders)
        }
        Thread({ workout.recoverInterrupted() }, "WorkoutRecover").start()
        ascentRecorder = AscentRecorder(this, store)
        trust = WearTrustStore(this).also { it.onPending = { pending -> WearPhoneRequests.notify(this, pending) } }
        preferences = WearPreferences.shared(this).also {
            it.onChange = { mainHandler.post(::reconcileRecorders) }
        }
        bedtimeMonitor = BedtimeMonitor(this, mainHandler) { reconcileRecorders() }
        link = WearLinkServerHost(
            adapter = { getSystemService(BluetoothManager::class.java)?.adapter },
            trust = trust,
            store = store,
            live = LiveHeartRate(this, isRecording = { recorder.isRunning }),
            preferences = preferences,
            hrvAvailable = { nightHrv.hasSensor },
            logEntries = { EntryLog(this).load() },
            localName = ::localName,
            workouts = WorkoutStore(MetricStoreWorkoutRows(store))::workoutsSince,
        )
        isForeground = startInForeground()
        if (!isForeground) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        WearLinkState.update { it.copy(bluetoothOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) }
        link.start()
        refreshBondLost()
        preferences.publish()
        bedtimeMonitor.start()
        wornMonitor.start()
        // Needs no grant: the accelerometer and the barometer are open to every app.
        reconcileRecorders()
        diagnostics = DiagnosticsSampler(DiagnosticLog(diagnosticLogFile(this))) { takeDiagnosticSample() }
        diagnostics.start()
    }

    private fun takeDiagnosticSample(): DiagnosticSample {
        val (percent, charging) = batteryReading(this)
        return DiagnosticSample(
            epochMillis = System.currentTimeMillis(),
            batteryPercent = percent,
            charging = charging,
            heartRateOn = recorder.isRunning,
            stepsOn = stepRecorder.isRunning,
            bedtime = WearLinkState.state.value.bedtimeHold,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate already stopped a service the system refused; do not restart it.
        if (!isForeground) return START_NOT_STICKY
        if (intent?.action == ACTION_TOGGLE_PPG_LOG) {
            if (ppgLogger.isRunning) ppgLogger.stop() else ppgLogger.start()
            WearLinkState.update { it.copy(ppgLogging = ppgLogger.isRunning) }
        } else if (intent?.action == ACTION_SET_INTERACTIVE) {
            val interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false)
            stepRecorder.setInteractive(interactive)
        } else if (intent?.action == ACTION_HEART_RATE_ALARM) {
            recorder.onAlarmFired()
        }
        if (intent?.action in WORKOUT_ACTIONS) handleWorkoutAction(intent!!)
        link.start()
        // A grant made after the start: the type set grows to include health.
        val newHeartRate = heartRateWanted && heartRateInterval() != HeartRateInterval.OFF &&
            !recorder.isRunning && WearPermissions.hasHeartRate(this)
        val newSteps = preferences.steps && !stepRecorder.isRunning && stepRecorder.hasSensor &&
            WearPermissions.hasActivityRecognition(this)
        if (newHeartRate || newSteps) {
            startInForeground()
            reconcileRecorders()
        }
        return START_STICKY
    }

    private fun handleWorkoutAction(intent: Intent) {
        when (intent.action) {
            ACTION_WORKOUT_START -> {
                val sport = WorkoutSport.fromId(intent.getStringExtra(EXTRA_SPORT)) ?: return
                // The location type must be in place before the first fix is asked for.
                val gps = intent.getBooleanExtra(EXTRA_GPS, false) && WearPermissions.hasLocation(this)
                workout.start(sport, gps)
            }
            ACTION_WORKOUT_PAUSE -> workout.pause()
            ACTION_WORKOUT_RESUME -> workout.resume()
            ACTION_WORKOUT_STOP -> workout.stop()
        }
    }

    /**
     * False when the system refuses, e.g. BLUETOOTH_CONNECT not granted on
     * API 34+. Calling it again with the heart rate or step grant in place
     * adds the `health` type to the running service.
     */
    private fun startInForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.link_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.link_notification_text))
            .setOngoing(true)
            .build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val sensing = WearPermissions.hasHeartRate(this) || WearPermissions.hasActivityRecognition(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sensing) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (::workout.isInitialized && workout.usesGps && WearPermissions.hasLocation(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return try {
            startForeground(NOTIFICATION_ID, notification, types)
            true
        } catch (e: Exception) {
            // SecurityException for the missing grant, or a start from the background.
            Log.e(TAG, "Cannot run the phone link in the foreground", e)
            false
        }
    }

    /** The heart rate interval the wearer's settings and bedtime mode ask for now. */
    private fun heartRateInterval(): HeartRateInterval = HeartRateSchedule.interval(
        heartRate = preferences.heartRate,
        sleepRecording = preferences.sleepRecording,
        bedtime = bedtimeMonitor.isBedtime || minuteRecorder.isSleepHolding,
        day = preferences.heartRateDay,
        night = preferences.heartRateNight,
        workout = workout.isActive,
    )

    /** The steps stored for the minute that starts at [minuteStartMillis]. Any thread. */
    private fun stepsInMinute(minuteStartMillis: Long): Int =
        store.since(WearMetrics.STEPS, minuteStartMillis - 1, 2, minuteStartMillis + 60_000L)
            .filter { it.epochMillis == minuteStartMillis }.sumOf { it.steps }

    /**
     * Whether heart rate is recorded at every ten seconds, the rate the sleep
     * minutes judge a no-wrist minute by. At a sparser rate a minute without a
     * sample says nothing about the wrist.
     */
    private fun isHeartRateDense(): Boolean = recorder.interval == HeartRateSchedule.FULL_RATE

    /**
     * Starts or stops each recorder to match the settings, the worn state,
     * bedtime mode and the grants. Main thread. Each recorder that is already
     * right is left as it is. A recorder whose grant is missing waits for a
     * later call.
     */
    private fun reconcileRecorders() {
        if (!isForeground) return
        val heartRateGranted = WearPermissions.hasHeartRate(this)
        when {
            !heartRateGranted -> Log.i(TAG, "Heart rate not granted")
            !heartRateWanted -> Log.i(TAG, "Heart rate resting: not worn")
        }
        val wanted = if ((heartRateWanted || workout.isActive) && heartRateGranted) heartRateInterval() else HeartRateInterval.OFF
        recorder.setInterval(wanted)
        // Without the heart rate's wake-ups the step counter must wake the watch itself.
        stepRecorder.setHeartRateRunning(recorder.isRunning)
        if (preferences.steps) {
            when {
                !stepRecorder.hasSensor -> Log.i(TAG, "No step counter")
                WearPermissions.hasActivityRecognition(this) -> stepRecorder.start()
                else -> Log.i(TAG, "Steps not granted")
            }
        } else {
            stepRecorder.stop()
        }
        if (preferences.ascent) ascentRecorder.start() else ascentRecorder.stop()
        if (preferences.sleepRecording) minuteRecorder.start() else minuteRecorder.stop()
        // The windows need heart rate tracking, and the minute rows that say the arm is still.
        nightHrv.setEnabled(heartRateGranted && preferences.heartRate && preferences.sleepRecording)
    }

    private fun setHeartRateWanted(wanted: Boolean) {
        if (!isForeground || wanted == heartRateWanted) return
        heartRateWanted = wanted
        reconcileRecorders()
    }

    /** The watch's Bluetooth name, as the phone will show it. */
    @SuppressLint("MissingPermission")
    private fun localName(): String {
        // The service only runs with the Bluetooth grant (startIfPermitted); the catch covers a revocation since.
        if (!WearPermissions.hasBluetooth(this)) return Build.MODEL
        return runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** Trusted phones the watch is no longer bonded with: a bond the phone or the watch dropped. */
    @SuppressLint("MissingPermission")
    private fun refreshBondLost() {
        if (!WearPermissions.hasBluetooth(this)) return
        val bonded = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet()
        }.getOrDefault(emptySet())
        val lost = trust.trusted().filter { it.address.uppercase() !in bonded }
        WearLinkState.update { it.copy(bondLost = lost) }
    }

    override fun onDestroy() {
        if (::diagnostics.isInitialized) diagnostics.stop()
        if (isForeground) {
            unregisterReceiver(bluetoothReceiver)
            bedtimeMonitor.stop()
            wornMonitor.stop()
            mainHandler.removeCallbacksAndMessages(null)
            recorder.stop()
            stepRecorder.stop()
            ascentRecorder.stop()
            minuteRecorder.stop()
            nightHrv.stop()
            workout.release()
            ppgLogger.stop()
            WearLinkState.update { it.copy(ppgLogging = false) }
        }
        preferences.onChange = null
        link.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearAppService"
        private const val CHANNEL_ID = "phone_link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE_PPG_LOG = "tech.mmarca.openvitals.wear.TOGGLE_PPG_LOG"
        private const val ACTION_SET_INTERACTIVE = "tech.mmarca.openvitals.wear.SET_INTERACTIVE"
        private const val EXTRA_INTERACTIVE = "interactive"

        /** [HeartRateAlarmReceiver] sends this to wake one power-save heart rate window. */
        const val ACTION_HEART_RATE_ALARM = "tech.mmarca.openvitals.wear.HEART_RATE_ALARM"

        /** Sets whether the watch app UI is active so step counting flushes in real time. */
        fun setInteractive(context: Context, interactive: Boolean) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching {
                val intent = Intent(context, WearAppService::class.java)
                    .setAction(ACTION_SET_INTERACTIVE)
                    .putExtra(EXTRA_INTERACTIVE, interactive)
                context.startForegroundService(intent)
            }.onFailure { Log.e(TAG, "Cannot set interactive mode", it) }
        }

        private const val ACTION_WORKOUT_START = "tech.mmarca.openvitals.wear.WORKOUT_START"
        private const val ACTION_WORKOUT_PAUSE = "tech.mmarca.openvitals.wear.WORKOUT_PAUSE"
        private const val ACTION_WORKOUT_RESUME = "tech.mmarca.openvitals.wear.WORKOUT_RESUME"
        private const val ACTION_WORKOUT_STOP = "tech.mmarca.openvitals.wear.WORKOUT_STOP"
        private const val EXTRA_SPORT = "sport"
        private const val EXTRA_GPS = "gps"
        private val WORKOUT_ACTIONS = setOf(ACTION_WORKOUT_START, ACTION_WORKOUT_PAUSE, ACTION_WORKOUT_RESUME, ACTION_WORKOUT_STOP)

        /** Starts a workout; [gps] only takes effect with the location grant. Called from the foreground app. */
        fun startWorkout(context: Context, sport: WorkoutSport, gps: Boolean) = send(
            context,
            Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_START).putExtra(EXTRA_SPORT, sport.id).putExtra(EXTRA_GPS, gps),
        )

        fun pauseWorkout(context: Context) = send(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_PAUSE))

        fun resumeWorkout(context: Context) = send(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_RESUME))

        fun stopWorkout(context: Context) = send(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_STOP))

        private fun send(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }.onFailure { Log.e(TAG, "Cannot reach the service", it) }
        }

        /** Starts or stops the raw PPG log (debuggable builds with the sensor only). */
        fun togglePpgLog(context: Context) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching {
                context.startForegroundService(Intent(context, WearAppService::class.java).setAction(ACTION_TOGGLE_PPG_LOG))
            }.onFailure { Log.e(TAG, "Cannot toggle the PPG log", it) }
        }

        /** Starts the link once the Bluetooth grant is there; a no-op without it. */
        fun startIfPermitted(context: Context) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching { context.startForegroundService(Intent(context, WearAppService::class.java)) }
                .onFailure { Log.e(TAG, "Cannot start the phone link", it) }
        }
    }
}
