package tech.mmarca.openvitals.wear.features.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openvitals.wear.MetricKind
import tech.mmarca.openvitals.wear.MetricUiState
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.UnitSystem
import tech.mmarca.openvitals.wear.MetricTile
import tech.mmarca.openvitals.wear.ui.components.ActivityRing
import tech.mmarca.openvitals.wear.ui.components.ActivityRings
import tech.mmarca.openvitals.wear.ui.components.MetricTile
import tech.mmarca.openvitals.wear.ui.components.formatMetricValue
import tech.mmarca.openvitals.wear.ui.components.metricUnitLabel
import tech.mmarca.openvitals.wear.ui.preview.SampleData
import tech.mmarca.openvitals.wear.ui.preview.WearPreviews
import tech.mmarca.openvitals.wear.ui.theme.OpenVitalsWearTheme

/**
 * [tiles] is what the user chose to see, in their order. The first three of
 * them with a daily goal also show as activity rings at the top.
 */
@Composable
fun DashboardScreen(
    tiles: List<MetricTile>,
    metrics: Map<MetricTile, MetricUiState>,
    unitSystem: UnitSystem,
    onOpenMetric: (MetricTile) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val rings = activityRings(tiles, metrics, unitSystem)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.dashboard_today))
                }
            }
            if (rings.isNotEmpty()) {
                item { ActivityRings(rings, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            items(tiles, key = { it.name }) { metric ->
                val state = metrics[metric]
                MetricTile(
                    title = stringResource(metric.label),
                    value = state?.current?.let { formatMetricValue(metric, it, unitSystem) },
                    unit = stringResource(metricUnitLabel(metric, unitSystem)),
                    subtitle = state?.goal?.let {
                        stringResource(R.string.metric_goal, formatMetricValue(metric, it, unitSystem))
                    },
                    icon = metric.icon,
                    accentColor = metric.accentColor,
                    onClick = { onOpenMetric(metric) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                Button(
                    onClick = onOpenSettings,
                    label = { Text(stringResource(R.string.settings_title)) },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
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

/** Rings for the first three chosen cumulative metrics that have a goal. */
@Composable
private fun activityRings(
    tiles: List<MetricTile>,
    metrics: Map<MetricTile, MetricUiState>,
    unitSystem: UnitSystem,
): List<ActivityRing> =
    tiles.mapNotNull { metric ->
        val state = metrics[metric] ?: return@mapNotNull null
        val goal = state.goal?.takeIf { it > 0 } ?: return@mapNotNull null
        if (metric.kind != MetricKind.CUMULATIVE) return@mapNotNull null
        val current = state.current ?: 0.0
        ActivityRing(
            progress = (current / goal).toFloat(),
            color = metric.accentColor,
            icon = metric.icon,
            value = formatMetricValue(metric, current, unitSystem),
        )
    }.take(3)

@WearPreviews
@Composable
private fun DashboardScreenPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            DashboardScreen(MetricTile.DefaultTiles, SampleData.metrics, UnitSystem.METRIC, {}, {})
        }
    }
}

@WearPreviews
@Composable
private fun DashboardScreenEmptyPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            DashboardScreen(MetricTile.DefaultTiles, emptyMap(), UnitSystem.METRIC, {}, {})
        }
    }
}

@WearPreviews
@Composable
private fun DashboardScreenImperialPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            DashboardScreen(MetricTile.DefaultTiles, SampleData.metrics, UnitSystem.IMPERIAL, {}, {})
        }
    }
}
