package tech.mmarca.openvitals.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tech.mmarca.openvitals.wear.features.dashboard.DashboardScreen
import tech.mmarca.openvitals.wear.features.metric.MetricDetailScreen
import tech.mmarca.openvitals.wear.features.settings.SettingsScreen
import tech.mmarca.openvitals.wear.ui.theme.OpenVitalsWearTheme

/**
 * The whole watch UI: theme, scaffold and navigation. It renders [state];
 * nothing here reads a sensor. Tile choices are kept in
 * [WearTilePreferences].
 */
@Composable
fun OpenVitalsWearApp(
    state: WearUiState,
    /** Link and recorder status, permissions and paired phones; the activity owns its stores. */
    statusScreen: @Composable () -> Unit = {},
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)

    OpenVitalsWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val context = LocalContext.current
            val tilePrefs = remember(context) { WearTilePreferences(context) }
            val chosenTiles = remember { tilePrefs.loadTiles() }
            val navController = rememberSwipeDismissableNavController()
            // A chosen tile whose sensor is missing stays chosen but is not shown.
            fun visibleTiles() = chosenTiles.filter { it in currentState.availableMetrics }

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                composable(WearRoutes.DASHBOARD) {
                    DashboardScreen(
                        tiles = visibleTiles(),
                        metrics = currentState.metrics,
                        unitSystem = currentState.preferences.unitSystem,
                        onOpenMetric = { navController.navigate(WearRoutes.metric(it)) },
                        onOpenSettings = { navController.navigate(WearRoutes.SETTINGS) },
                    )
                }
                composable(WearRoutes.METRIC) { entry ->
                    val metric = MetricTile.valueOf(checkNotNull(entry.arguments?.getString(WearRoutes.METRIC_ARG)))
                    MetricDetailScreen(
                        metric = metric,
                        state = currentState.metrics[metric] ?: MetricUiState(),
                        unitSystem = currentState.preferences.unitSystem,
                        maxHeartRate = currentState.preferences.maxHeartRate,
                        restingHeartRate = currentState.metrics[MetricTile.RESTING_HEART_RATE]?.current,
                        sleepStages = currentState.sleepStages,
                    )
                }
                composable(WearRoutes.SETTINGS) {
                    SettingsScreen(
                        state = currentState.settings,
                        unitSystem = currentState.preferences.unitSystem,
                        onPhoneClick = { navController.navigate(WearRoutes.STATUS) },
                        onSensorAccessClick = { navController.navigate(WearRoutes.STATUS) },
                    )
                }
                composable(WearRoutes.STATUS) { statusScreen() }
            }
        }
    }
}
