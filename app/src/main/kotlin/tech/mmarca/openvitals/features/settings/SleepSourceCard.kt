package tech.mmarca.openvitals.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.components.OpenVitalsCard

/**
 * Which app's sleep shows when two recorded the same night. Health Connect keeps the user's own
 * priority list to itself, so OpenVitals asks once. The chosen app stays in the list even when it
 * wrote nothing lately, so the choice is never hidden by a quiet month.
 */
@Composable
internal fun SleepSourceCard(
    selected: String?,
    sources: List<String>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = (sources + listOfNotNull(selected)).distinct()
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.settings_sleep_source_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_sleep_source_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                SourceRow(stringResource(R.string.settings_sleep_source_automatic), selected == null) { onSelect(null) }
                options.forEach { packageName ->
                    SourceRow(sourceName(packageName), selected == packageName) { onSelect(packageName) }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun sourceName(packageName: String): String =
    HealthConnectSource(packageName, recordCount = 0, lastSeen = Instant.EPOCH, metrics = emptySet()).displayName
