package tech.mmarca.openvitals.features.watches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    HeartRateInterval.EVERY_MINUTE,
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
                // While sleep is recorded the night is the full rate, whatever this says.
                IntervalRow(
                    label = stringResource(R.string.settings_watch_wearos_interval_night),
                    interval = tracking.heartRateNight,
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

@Composable
private fun IntervalRow(
    label: String,
    interval: HeartRateInterval,
    enabled: Boolean,
    onChange: (HeartRateInterval) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            IntervalChoices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = interval == choice,
                    onClick = { onChange(choice) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = IntervalChoices.size),
                    icon = {},
                ) {
                    Text(intervalLabel(choice))
                }
            }
        }
    }
}

@Composable
private fun intervalLabel(interval: HeartRateInterval): String = when (interval) {
    HeartRateInterval.OFF -> stringResource(R.string.settings_watch_wearos_interval_off)
    HeartRateInterval.EVERY_10_SECONDS -> stringResource(R.string.settings_watch_wearos_interval_10_seconds)
    HeartRateInterval.EVERY_MINUTE -> stringResource(R.string.settings_watch_wearos_interval_minute)
}
