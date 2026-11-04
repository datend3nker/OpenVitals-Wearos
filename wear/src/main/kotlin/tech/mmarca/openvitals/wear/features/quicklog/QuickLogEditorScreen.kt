package tech.mmarca.openvitals.wear.features.quicklog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
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
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.ui.preview.WearPreviews
import tech.mmarca.openvitals.wear.ui.theme.OpenVitalsWearTheme

/** Switches the quick log's rows on and off. */
@Composable
fun QuickLogEditorScreen(
    hidden: Set<QuickLogItem>,
    onToggle: (QuickLogItem, Boolean) -> Unit,
    onReset: () -> Unit,
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
                    Text(stringResource(R.string.quicklog_editor_title))
                }
            }
            items(QuickLogItem.entries) { item ->
                SwitchButton(
                    checked = item !in hidden,
                    onCheckedChange = { onToggle(item, it) },
                    label = { Text(stringResource(item.label)) },
                    icon = { Icon(item.icon, contentDescription = null, tint = item.color) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            item {
                Button(
                    onClick = onReset,
                    label = { Text(stringResource(R.string.tiles_reset_default)) },
                    icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }
}

@WearPreviews
@Composable
private fun QuickLogEditorScreenPreview() {
    OpenVitalsWearTheme {
        AppScaffold {
            QuickLogEditorScreen(hidden = setOf(QuickLogItem.HEIGHT), onToggle = { _, _ -> }, onReset = {})
        }
    }
}
