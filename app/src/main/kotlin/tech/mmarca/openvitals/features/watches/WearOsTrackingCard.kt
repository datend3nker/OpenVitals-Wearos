package tech.mmarca.openvitals.features.watches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.devices.wearos.WearOsHeartRatePeriod
import tech.mmarca.openvitals.devices.wearos.WearOsWatchSetting
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.Spacing
import tech.mmarca.openvitals.wearlink.HeartRateInterval

/** The heart rate intervals the phone offers, in the order the segments show them. */
private val IntervalChoices = listOf(
    HeartRateInterval.OFF,
    HeartRateInterval.EVERY_10_SECONDS,
    HeartRateInterval.POWER_SAVE,
)

/**
 * What the Wear OS watch tracks, set from the phone because the watch is a
 * companion that only shows its state: heart rate with its day and night
 * intervals, steps, height climbed and sleep. Each switch off stops the
 * watch from measuring that; the data it already holds stays on the watch.
 *
 * The push needs the watch in reach; the rows are disabled while one is in
 * flight, and a push to a watch too old for settings or out of range
 * surfaces through [WearOsStatusCard]'s error line. The card shows nothing
 * until the watch app answers.
 */
@Composable
internal fun WearOsTrackingCard(
    state: WatchDeviceUiState,
    onSettingChange: (WearOsWatchSetting, Boolean) -> Unit,
    onIntervalChange: (WearOsHeartRatePeriod, HeartRateInterval) -> Unit,
) {
    if (!state.wearOsStatus.isAppRunning) return
    val tracking = state.wearOsTracking
    val enabled = !state.isPushingWearOsSetting
    OpenVitalsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_watch_wearos_tracking_title),
                style = MaterialTheme.typography.titleSmall,
            )
            TrackingSwitchRow(
                label = stringResource(R.string.settings_watch_wearos_heart_rate),
                checked = tracking.heartRate,
                enabled = enabled,
                onCheckedChange = { onSettingChange(WearOsWatchSetting.HEART_RATE, it) },
            )
            if (tracking.heartRate) {
                IntervalRow(
                    label = stringResource(R.string.settings_watch_wearos_interval_day),
                    interval = tracking.heartRateDay,
                    enabled = enabled,
                    onChange = { onIntervalChange(WearOsHeartRatePeriod.DAY, it) },
                )
                // While sleep is recorded the night is the full rate, whatever this says: show what applies.
                IntervalRow(
                    label = stringResource(R.string.settings_watch_wearos_interval_night),
                    interval = HeartRateInterval.atNight(tracking.heartRateNight, state.wearOsSleepRecording),
                    enabled = enabled && !state.wearOsSleepRecording,
                    onChange = { onIntervalChange(WearOsHeartRatePeriod.NIGHT, it) },
                )
                Text(
                    text = stringResource(R.string.settings_watch_wearos_interval_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TrackingSwitchRow(
                label = stringResource(R.string.settings_watch_wearos_steps),
                checked = tracking.steps,
                enabled = enabled,
                onCheckedChange = { onSettingChange(WearOsWatchSetting.STEPS, it) },
            )
            TrackingSwitchRow(
                label = stringResource(R.string.settings_watch_wearos_ascent),
                checked = tracking.ascent,
                enabled = enabled,
                onCheckedChange = { onSettingChange(WearOsWatchSetting.ASCENT, it) },
            )
            TrackingSwitchRow(
                label = stringResource(R.string.settings_watch_wearos_sleep_recording),
                body = stringResource(R.string.settings_watch_wearos_sleep_recording_body),
                checked = state.wearOsSleepRecording,
                // Sleep needs heart rate: with heart rate off the switch is locked off.
                enabled = enabled && tracking.heartRate,
                onCheckedChange = { onSettingChange(WearOsWatchSetting.SLEEP_RECORDING, it) },
            )
            // Off by default. What it collects is said on the switch, because the files hold values.
            TrackingSwitchRow(
                label = stringResource(R.string.settings_watch_wearos_debug_data),
                body = stringResource(R.string.settings_watch_wearos_debug_data_body),
                checked = tracking.debugData,
                enabled = enabled,
                onCheckedChange = { onSettingChange(WearOsWatchSetting.DEBUG_DATA, it) },
            )
            Text(
                text = stringResource(R.string.settings_watch_wearos_tracking_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    body: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One choice list per part of the day. Radio rows rather than segments: the long label "Energiesparend"
 * broke in a third of the width and made one segment taller, and a selected segment was hard to tell
 * from the others.
 */
@Composable
private fun IntervalRow(
    label: String,
    interval: HeartRateInterval,
    enabled: Boolean,
    onChange: (HeartRateInterval) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Column(modifier = Modifier.selectableGroup()) {
            IntervalChoices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = interval == choice,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onChange(choice) },
                        )
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = interval == choice, onClick = null, enabled = enabled)
                    Text(
                        text = intervalLabel(choice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun intervalLabel(interval: HeartRateInterval): String = when (interval) {
    HeartRateInterval.OFF -> stringResource(R.string.settings_watch_wearos_interval_off)
    HeartRateInterval.EVERY_10_SECONDS -> stringResource(R.string.settings_watch_wearos_interval_10_seconds)
    HeartRateInterval.POWER_SAVE -> stringResource(R.string.settings_watch_wearos_interval_power_save)
}
