package tech.mmarca.openvitals.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openvitals.wear.features.recording.ActivityType
import tech.mmarca.openvitals.wear.workout.WorkoutState
import tech.mmarca.openvitals.wear.features.settings.PhoneScreen
import tech.mmarca.openvitals.wear.features.settings.SensorAccess
import tech.mmarca.openvitals.wear.features.settings.SensorAccessScreen

/**
 * The watch app: the dashboard, tiles' screens and quick log, with the link
 * and recorder status one step down in Settings. Asks for the permissions
 * the link and the recorders need; each part starts on its own once
 * granted. The recording itself lives in [WearAppService], so closing the
 * app changes nothing.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearAppViewModel by viewModels()
    private val store by lazy { MetricStore(this) }
    private val trust by lazy { WearTrustStore(this) }
    private val diagnosticLog by lazy { DiagnosticLog(java.io.File(filesDir, DiagnosticLog.FILE_NAME)) }

    private var permissionsVersion by mutableStateOf(0)

    /** A screen a tile asked for, opened once and then cleared. */
    private var pendingRoute by mutableStateOf<String?>(null)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsVersion++
        WearAppService.startIfPermitted(this)
        // The background grant is asked on its own, and only after the foreground one.
        requestBackgroundHeartRateIfNeeded()
    }

    /** The sport waiting for the location answer; null when none is. */
    private var workoutAwaitingLocation: ActivityType? = null

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Declined: the workout still records, without a route or a distance.
        workoutAwaitingLocation?.let { WearAppService.startWorkout(this, it.sport, gps = granted) }
        workoutAwaitingLocation = null
    }

    /** Asks for location at the first workout that uses the GPS, then starts it. */
    private fun startWorkout(type: ActivityType) {
        if (!type.sport.gps || WearPermissions.hasLocation(this)) {
            WearAppService.startWorkout(this, type.sport, gps = type.sport.gps)
        } else {
            workoutAwaitingLocation = type
            requestLocation.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val link by WearLinkState.state.collectAsStateWithLifecycle()
            // Read again on every resume: a grant can change in the system settings.
            val settings = remember(link, permissionsVersion) {
                SettingsUiState.from(
                    link = link,
                    sensorAccessGranted = WearPermissions.hasHeartRate(this) &&
                        WearPermissions.hasHeartRateInBackground(this),
                )
            }
            OpenVitalsWearApp(
                state = uiState.copy(settings = settings),
                onLog = viewModel::log,
                deepLinkRoute = pendingRoute,
                onDeepLinkHandled = { pendingRoute = null },
                statusScreen = { StatusScreen() },
                phoneScreen = { PhoneRoute() },
                sensorScreen = { onOpenDetails -> SensorAccessRoute(onOpenDetails) },
                onStartWorkout = ::startWorkout,
                onPauseWorkout = { WearAppService.pauseWorkout(this) },
                onResumeWorkout = { WearAppService.resumeWorkout(this) },
                onStopWorkout = { WearAppService.stopWorkout(this) },
                onCloseSummary = WorkoutState::clearFinished,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        // Also catches a grant made in the system settings.
        permissionsVersion++
        WearAppService.startIfPermitted(this)
    }

    /** What the watch records, whether the phone can reach it, and which phones may. */
    @Composable
    private fun StatusScreen() {
        WatchStatusScreen(
            permissionsVersion = permissionsVersion,
            hasHeartRate = { WearPermissions.hasHeartRate(this) },
            hasBluetooth = { WearPermissions.hasBluetooth(this) },
            hasSteps = { WearPermissions.hasActivityRecognition(this) },
            hasStepCounter = StepRecorder.hasStepCounter(this),
            readLatest = { store.latest(WearMetrics.HEART_RATE) },
            readCount = { store.count(WearMetrics.HEART_RATE) },
            readMinuteCount = { store.count(WearMetrics.SLEEP_MINUTES) },
            readStepMinuteCount = { store.count(WearMetrics.STEPS) },
            ppgLogAvailable = PpgRawLogger(this).isAvailable,
            onTogglePpgLog = { WearAppService.togglePpgLog(this) },
            onGrant = ::requestMissingPermissions,
            onAllow = { pending ->
                trust.trust(pending.address, pending.token, pending.name)
                WearPhoneRequests.cancel(this)
            },
            onBlock = { pending ->
                trust.block(pending.address)
                WearPhoneRequests.cancel(this)
            },
            onOpenBluetoothSettings = ::openBluetoothSettings,
            readDiagnostics = { summarizeDiagnostics(diagnosticLog.samples()) },
        )
    }

    /** The phones that may read this watch, and the link they use. */
    @Composable
    private fun PhoneRoute() {
        val link by WearLinkState.state.collectAsStateWithLifecycle()
        PhoneScreen(
            link = link,
            bluetoothGranted = remember(permissionsVersion) { WearPermissions.hasBluetooth(this) },
            onAllow = { pending ->
                trust.trust(pending.address, pending.token, pending.name)
                WearPhoneRequests.cancel(this)
            },
            onBlock = { pending ->
                trust.block(pending.address)
                WearPhoneRequests.cancel(this)
            },
            onOpenBluetoothSettings = ::openBluetoothSettings,
        )
    }

    /** One row per grant the watch app uses. */
    @Composable
    private fun SensorAccessRoute(onOpenDetails: () -> Unit) {
        val access = remember(permissionsVersion) {
            SensorAccess(
                heartRate = WearPermissions.hasHeartRate(this),
                heartRateInBackground = WearPermissions.hasHeartRateInBackground(this),
                steps = WearPermissions.hasActivityRecognition(this).takeIf { StepRecorder.hasStepCounter(this) },
                nearbyDevices = WearPermissions.hasBluetooth(this),
            )
        }
        SensorAccessScreen(access = access, onGrant = ::requestMissingPermissions, onOpenDetails = onOpenDetails)
    }

    private fun openBluetoothSettings() {
        // Not every watch has a Bluetooth settings activity; the system settings are the fallback.
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(WearPermissions.heartRate)
            add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            // The link's ongoing notification and the phone requests; the service runs without it, just unseen.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            requestBackgroundHeartRateIfNeeded()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestBackgroundHeartRateIfNeeded() {
        val background = WearPermissions.heartRateInBackground ?: return
        if (!WearPermissions.hasHeartRate(this) || WearPermissions.hasHeartRateInBackground(this)) return
        requestPermissions.launch(arrayOf(background))
    }

    companion object {
        /** Extra a tile sets to open a screen; see [WearRoutes.isDeepLinkable]. */
        const val EXTRA_ROUTE = "tech.mmarca.openvitals.wear.ROUTE"
    }
}
