package tech.mmarca.openvitals.wear.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.WearPreferences
import tech.mmarca.openvitals.wear.ui.preview.WearPreviews
import tech.mmarca.openvitals.wear.ui.theme.OpenVitalsWearTheme

@Composable
fun TrackingSettingsScreen(
    prefs: WearPreferences,
    onToggleFlag: (String, Boolean) -> Unit,
    onChangeInterval: (String, tech.mmarca.openvitals.wearlink.HeartRateInterval) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

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
            item {
                SwitchButton(
                    checked = prefs.heartRate,
                    onCheckedChange = { onToggleFlag(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.HEART_RATE, it) },
                    label = { Text(stringResource(R.string.settings_tracking_heart_rate)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                SwitchButton(
                    checked = prefs.steps,
                    onCheckedChange = { onToggleFlag(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.STEPS, it) },
                    label = { Text(stringResource(R.string.settings_tracking_steps)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                SwitchButton(
                    checked = prefs.ascent,
                    onCheckedChange = { onToggleFlag(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.ASCENT, it) },
                    label = { Text(stringResource(R.string.settings_tracking_ascent)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                SwitchButton(
                    checked = prefs.sleepRecording,
                    onCheckedChange = { onToggleFlag(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.SLEEP_RECORDING, it) },
                    label = { Text(stringResource(R.string.settings_tracking_sleep)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                val current = prefs.heartRateDay
                val next = cycleInterval(current)
                Button(
                    onClick = { onChangeInterval(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.HEART_RATE_DAY, next) },
                    label = { Text(stringResource(R.string.settings_tracking_hr_day)) },
                    secondaryLabel = { Text(intervalLabel(next)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                val current = prefs.heartRateNight
                val next = cycleInterval(current)
                Button(
                    onClick = { onChangeInterval(tech.mmarca.openvitals.wearlink.WearLinkProtocol.Preferences.HEART_RATE_NIGHT, next) },
                    label = { Text(stringResource(R.string.settings_tracking_hr_night)) },
                    secondaryLabel = { Text(intervalLabel(next)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }
}

private fun cycleInterval(current: tech.mmarca.openvitals.wearlink.HeartRateInterval): tech.mmarca.openvitals.wearlink.HeartRateInterval =
    when (current) {
        tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_10_SECONDS -> tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_MINUTE
        tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_MINUTE -> tech.mmarca.openvitals.wearlink.HeartRateInterval.OFF
        tech.mmarca.openvitals.wearlink.HeartRateInterval.OFF -> tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_10_SECONDS
    }

@Composable
private fun intervalLabel(interval: tech.mmarca.openvitals.wearlink.HeartRateInterval): String =
    when (interval) {
        tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_10_SECONDS -> stringResource(R.string.interval_every_10_seconds)
        tech.mmarca.openvitals.wearlink.HeartRateInterval.EVERY_MINUTE -> stringResource(R.string.interval_every_minute)
        tech.mmarca.openvitals.wearlink.HeartRateInterval.OFF -> stringResource(R.string.interval_off)
    }

@WearPreviews
@Composable
private fun TrackingSettingsScreenPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            // Preview scaffold
        }
    }
}
