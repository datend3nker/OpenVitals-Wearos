package tech.mmarca.openvitals.wear.features.quicklog

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import tech.mmarca.openvitals.wear.R
import tech.mmarca.openvitals.wear.ui.theme.MindfulnessColor

/**
 * One row of the watch's quick log, so the wearer can hide the ones they never
 * use. Each entry type the log takes has its own row; [BREATHE] is the guided
 * breath, which logs no entry type of its own on the row.
 */
enum class QuickLogItem(@param:StringRes val label: Int, val entryType: EntryType?) {
    WATER(R.string.metric_hydration, EntryType.WATER),
    WEIGHT(R.string.metric_weight, EntryType.WEIGHT),
    BLOOD_PRESSURE(R.string.quicklog_blood_pressure, EntryType.BLOOD_PRESSURE),
    BODY_FAT(R.string.metric_body_fat, EntryType.BODY_FAT),
    BLOOD_OXYGEN(R.string.metric_blood_oxygen, EntryType.BLOOD_OXYGEN),
    RESPIRATORY_RATE(R.string.metric_respiratory_rate, EntryType.RESPIRATORY_RATE),
    BODY_TEMPERATURE(R.string.metric_body_temperature, EntryType.BODY_TEMPERATURE),
    BLOOD_GLUCOSE(R.string.metric_blood_glucose, EntryType.BLOOD_GLUCOSE),
    HEIGHT(R.string.metric_height, EntryType.HEIGHT),
    BREATHE(R.string.breathe_title, null),
    ;

    val icon: ImageVector get() = entryType?.icon ?: Icons.Outlined.SelfImprovement
    val color: Color get() = entryType?.color ?: MindfulnessColor

    companion object {
        /** The row that logs [type]; null for a type the quick log does not offer (spot measurements). */
        fun of(type: EntryType): QuickLogItem? = entries.firstOrNull { it.entryType == type }
    }
}

/** The names stored for the hidden rows. Nothing stored shows every row; unknown names are dropped. */
internal fun encodeHidden(hidden: Set<QuickLogItem>): String = hidden.joinToString(",") { it.name }

internal fun decodeHidden(raw: String?): Set<QuickLogItem> =
    raw.orEmpty().split(",").mapNotNull { name -> QuickLogItem.entries.firstOrNull { it.name == name.trim() } }.toSet()
