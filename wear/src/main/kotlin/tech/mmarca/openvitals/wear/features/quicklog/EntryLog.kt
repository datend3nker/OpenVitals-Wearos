package tech.mmarca.openvitals.wear.features.quicklog

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import tech.mmarca.openvitals.wear.tiles.QuickLogTileService

/** What a logged entry records, with the unit of [LoggedEntry.value]. */
enum class EntryType {
    /** Millilitres. */
    WATER,

    /** Kilograms. */
    WEIGHT,

    /** Systolic mmHg; [LoggedEntry.value2] is diastolic. */
    BLOOD_PRESSURE,

    /** Minutes of a breathing session. */
    MINDFULNESS,

    /** Beats per minute from a spot measurement. */
    HEART_RATE,

    /** Milliseconds RMSSD from a spot measurement. */
    HRV,

    /** Percent of body mass. */
    BODY_FAT,

    /** Percent of haemoglobin carrying oxygen. */
    BLOOD_OXYGEN,

    /** Breaths per minute. */
    RESPIRATORY_RATE,

    /** Degrees Celsius; shown in Fahrenheit with the imperial units. */
    BODY_TEMPERATURE,

    /** Millimoles per litre; shown in mg/dL with the imperial units. */
    BLOOD_GLUCOSE,

    /** Centimetres; shown in inches with the imperial units. */
    HEIGHT,
}

data class LoggedEntry(
    val type: EntryType,
    val timeMillis: Long,
    val value: Double,
    val value2: Double? = null,
    /** True when a spot measurement made it, false when the user typed it. */
    val measured: Boolean = false,
)

/**
 * True for an entry the user made by hand. The quick log's recent list shows
 * only these; the measurement results stay in the log, the sync and the tile
 * detail screens.
 */
fun isManual(entry: LoggedEntry): Boolean = !entry.measured

/**
 * Lines written before the flag existed. Heart rate and HRV were almost only
 * ever made by a spot measurement, so those two count as measured.
 */
internal fun measuredBeforeTheFlag(type: EntryType): Boolean =
    type == EntryType.HEART_RATE || type == EntryType.HRV

/**
 * Entries made on the watch: quick logs and spot measurements. They stay
 * here until phone sync exists to carry them to Health Connect. Shared with
 * the quick log tile, which writes to it directly.
 */
class EntryLog(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<LoggedEntry> = decodeEntries(prefs.getString(KEY_ENTRIES, null))

    /** [refreshTile] is false when the tile itself logs, as it renders the new total anyway. */
    fun add(entry: LoggedEntry, refreshTile: Boolean = true) {
        val updated = (load() + entry).takeLast(MaxEntries)
        prefs.edit { putString(KEY_ENTRIES, encodeEntries(updated)) }
        if (refreshTile) TileService.getUpdater(appContext).requestUpdate(QuickLogTileService::class.java)
    }

    /** The log now and after every change, including changes the tile makes. */
    val entries: Flow<List<LoggedEntry>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENTRIES) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(load())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS_NAME = "wear_entry_log"
        const val KEY_ENTRIES = "entries"
        const val MaxEntries = 500
    }
}

/**
 * One entry per line: `TYPE;time;value;value2;flag`. value2 is empty when there
 * is none. The flag is `m` for measured and `h` for by hand.
 */
internal fun encodeEntries(entries: List<LoggedEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            entry.type.name,
            entry.timeMillis,
            entry.value,
            entry.value2 ?: "",
            if (entry.measured) "m" else "h",
        ).joinToString(";")
    }

/**
 * Lines that do not parse, for example from a type that no longer exists, are
 * dropped. Lines with three or four fields are from before the flag: see
 * [measuredBeforeTheFlag].
 */
internal fun decodeEntries(raw: String?): List<LoggedEntry> =
    raw.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split(";")
        if (parts.size < 3) return@mapNotNull null
        val type = EntryType.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
        LoggedEntry(
            type = type,
            timeMillis = parts[1].toLongOrNull() ?: return@mapNotNull null,
            value = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
            value2 = parts.getOrNull(3)?.toDoubleOrNull(),
            measured = when (parts.getOrNull(4)) {
                "m" -> true
                "h" -> false
                else -> measuredBeforeTheFlag(type)
            },
        )
    }.toList()
