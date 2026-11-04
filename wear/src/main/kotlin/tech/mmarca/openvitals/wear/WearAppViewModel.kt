package tech.mmarca.openvitals.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.wear.features.quicklog.EntryLog
import tech.mmarca.openvitals.wear.features.quicklog.LoggedEntry
import tech.mmarca.openvitals.wear.features.quicklog.withEntries
import tech.mmarca.openvitals.wear.health.CapabilityProbe
import tech.mmarca.openvitals.wear.health.WearCapabilities
import tech.mmarca.openvitals.wear.workout.WorkoutState

/**
 * Puts the app-wide state together: what the watch recorded, what this watch
 * can really measure, and the entries made on it.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val entryLog = EntryLog(application)
    private val wearPreferences = WearPreferences.shared(application)
    private val capabilities = MutableStateFlow(WearCapabilities())
    private val readings = WearReadingsSource(MetricStore(application), profile = { StepProfile(wearPreferences.weightKg, wearPreferences.strideMeters) })

    private val initial = WearUiState()

    val uiState: StateFlow<WearUiState> =
        combine(
            capabilities,
            entryLog.entries,
            readings.readings(),
            wearPreferences.changes,
            WorkoutState.state,
        ) { capabilities, entries, readings, _, workout ->
            initial.copy(
                workout = workout,
                recording = workout.toRecording(),
                metrics = readings.metrics,
                sleepStages = readings.sleepStages,
                sleepTimes = readings.sleepTimes,
                capabilities = capabilities,
                preferences = DisplayPreferences(unitSystem = wearPreferences.unitSystem),
                // Until the probe answers, keep offering what was on offer before.
                availableMetrics = if (capabilities.probed) capabilities.availableMetrics else initial.availableMetrics,
            ).withEntries(entries)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), initial)

    init {
        viewModelScope.launch { capabilities.value = CapabilityProbe(application).probe() }
    }

    fun log(entry: LoggedEntry) = entryLog.add(entry)

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
