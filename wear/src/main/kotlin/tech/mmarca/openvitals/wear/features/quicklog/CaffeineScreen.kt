package tech.mmarca.openvitals.wear.features.quicklog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlin.math.roundToInt
import tech.mmarca.openvitals.domain.insights.WatchCaffeineDrink
import tech.mmarca.openvitals.domain.insights.WatchCaffeineDrinks
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.ui.components.formatCount
import tech.mmarca.openvitals.wear.ui.preview.WearPreviews
import tech.mmarca.openvitals.wear.ui.theme.CaffeineColor
import tech.mmarca.openvitals.wear.ui.theme.OpenVitalsWearTheme

/** The watch's text for a drink of [WatchCaffeineDrinks]; the list itself names it by key only. */
@StringRes
internal fun caffeineDrinkName(key: String): Int = when (key) {
    "coffee" -> R.string.caffeine_drink_coffee
    "espresso" -> R.string.caffeine_drink_espresso
    "black_tea" -> R.string.caffeine_drink_black_tea
    "cola" -> R.string.caffeine_drink_cola
    "energy_drink" -> R.string.caffeine_drink_energy_drink
    else -> R.string.metric_caffeine
}

/**
 * A list of drinks. One tap logs the drink now and shows the confirmation;
 * [onDone] runs when the confirmation closes.
 */
@Composable
fun CaffeineScreen(
    onLog: (WatchCaffeineDrink) -> Unit,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var showSaved by remember { mutableStateOf(false) }
    val unit = stringResource(R.string.unit_mg)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.metric_caffeine))
                }
            }
            items(WatchCaffeineDrinks.all, key = { it.key }) { drink ->
                Button(
                    onClick = {
                        onLog(drink)
                        showSaved = true
                    },
                    label = { Text(stringResource(caffeineDrinkName(drink.key))) },
                    secondaryLabel = { Text("${formatCount(drink.caffeineMg.roundToInt())} $unit") },
                    icon = { Icon(Icons.Outlined.Coffee, contentDescription = null, tint = CaffeineColor) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }

    SuccessConfirmationDialog(
        visible = showSaved,
        onDismissRequest = {
            showSaved = false
            onDone()
        },
        curvedText = null,
    )
}

@WearPreviews
@Composable
private fun CaffeineScreenPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            CaffeineScreen({}, {})
        }
    }
}
