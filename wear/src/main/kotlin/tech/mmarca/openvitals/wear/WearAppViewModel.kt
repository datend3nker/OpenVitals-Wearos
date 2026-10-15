package tech.mmarca.openvitals.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.wear.health.CapabilityProbe
import tech.mmarca.openvitals.wear.health.WearCapabilities

/**
 * Puts the app-wide state together: what the watch recorded, read again
 * every minute while a screen shows it, what its sensors can measure, the
 * phone link and the sensor grants.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val records = WatchRecordsSource(MetricStore(application))
    private val capabilities = MutableStateFlow(WearCapabilities())
    private val sensorAccess = MutableStateFlow(hasSensorAccess())
    private val preferences = MutableStateFlow(WearPreferences())

    private val metrics = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            emit(WatchReadings.summarize(records.load(zone, now), zone, now))
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<WearUiState> =
        combine(metrics, capabilities, preferences, sensorAccess, WearLinkState.state) { metrics, capabilities, preferences, sensorAccess, link ->
            WearUiState(
                metrics = metrics.withGoals(preferences),
                settings = SettingsUiState(
                    phoneConnected = link.trusted.any { phone -> link.bondLost.none { it.address == phone.address } },
                    sensorAccessGranted = sensorAccess,
                ),
                preferences = preferences,
                capabilities = capabilities,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WearUiState())

    init {
        viewModelScope.launch(Dispatchers.Default) { capabilities.value = CapabilityProbe(application).probe() }
    }

    /** Reads the grants again, after a permission dialog or a return from the system settings. */
    fun onPermissionsChanged() {
        sensorAccess.value = hasSensorAccess()
    }

    private fun hasSensorAccess(): Boolean {
        val app = getApplication<Application>()
        return WearPermissions.hasHeartRate(app) && WearPermissions.hasActivityRecognition(app)
    }

    private fun Map<MetricTile, MetricUiState>.withGoals(preferences: WearPreferences): Map<MetricTile, MetricUiState> =
        this + (MetricTile.STEPS to (this[MetricTile.STEPS] ?: MetricUiState()).copy(goal = preferences.stepGoal.toDouble()))

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val REFRESH_MILLIS = 60_000L
    }
}
