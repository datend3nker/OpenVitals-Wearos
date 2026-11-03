package tech.mmarca.openvitals.wear.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.WearPreferences
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences as Keys

@Composable
fun TrackingSettingsScreen(
    prefs: WearPreferences,
    onToggleFlag: (String, Boolean) -> Unit,
    onChangeInterval: (String, HeartRateInterval) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val changed by prefs.changes.collectAsState(Unit)
    val heartRate = remember(changed) { prefs.heartRate }
    val steps = remember(changed) { prefs.steps }
    val ascent = remember(changed) { prefs.ascent }
    val sleep = remember(changed) { prefs.sleepRecording }
    val day = remember(changed) { prefs.heartRateDay }
    val night = remember(changed) { prefs.heartRateNight }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.settings_tracking))
                }
            }
            switchItem(transformationSpec, R.string.settings_tracking_heart_rate, heartRate) { onToggleFlag(Keys.HEART_RATE, it) }
            switchItem(transformationSpec, R.string.settings_tracking_steps, steps) { onToggleFlag(Keys.STEPS, it) }
            switchItem(transformationSpec, R.string.settings_tracking_ascent, ascent) { onToggleFlag(Keys.ASCENT, it) }
            switchItem(transformationSpec, R.string.settings_tracking_sleep, sleep) { onToggleFlag(Keys.SLEEP_RECORDING, it) }
            intervalItem(transformationSpec, R.string.settings_tracking_hr_day, day) {
                onChangeInterval(Keys.HEART_RATE_DAY, it)
            }
            intervalItem(transformationSpec, R.string.settings_tracking_hr_night, night) {
                onChangeInterval(Keys.HEART_RATE_NIGHT, it)
            }
        }
    }
}

private fun TransformingLazyColumnScope.switchItem(
    spec: TransformationSpec,
    label: Int,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) = item {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChecked,
        label = { Text(stringResource(label)) },
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
    )
}

private fun TransformingLazyColumnScope.intervalItem(
    spec: TransformationSpec,
    label: Int,
    current: HeartRateInterval,
    onNext: (HeartRateInterval) -> Unit,
) = item {
    Button(
        onClick = { onNext(current.next()) },
        label = { Text(stringResource(label)) },
        secondaryLabel = { Text(current.label()) },
        colors = ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
    )
}

private fun HeartRateInterval.next(): HeartRateInterval = when (this) {
    HeartRateInterval.EVERY_10_SECONDS -> HeartRateInterval.EVERY_MINUTE
    HeartRateInterval.EVERY_MINUTE -> HeartRateInterval.OFF
    HeartRateInterval.OFF -> HeartRateInterval.EVERY_10_SECONDS
}

@Composable
private fun HeartRateInterval.label(): String = stringResource(
    when (this) {
        HeartRateInterval.EVERY_10_SECONDS -> R.string.interval_every_10_seconds
        HeartRateInterval.EVERY_MINUTE -> R.string.interval_every_minute
        HeartRateInterval.OFF -> R.string.interval_off
    },
)
