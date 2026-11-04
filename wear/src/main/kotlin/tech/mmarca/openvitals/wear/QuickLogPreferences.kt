package tech.mmarca.openvitals.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import tech.mmarca.openvitals.wear.features.quicklog.QuickLogItem
import tech.mmarca.openvitals.wear.features.quicklog.decodeHidden
import tech.mmarca.openvitals.wear.features.quicklog.encodeHidden

/**
 * Which quick log rows the wearer hid. Hidden rather than shown is stored, so
 * a row added by a later version appears without the wearer opting in.
 */
class QuickLogPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hidden(): Set<QuickLogItem> = decodeHidden(prefs.getString(KEY_HIDDEN, null))

    fun save(hidden: Set<QuickLogItem>) {
        prefs.edit { putString(KEY_HIDDEN, encodeHidden(hidden)) }
    }

    private companion object {
        const val PREFS_NAME = "wear_quicklog_prefs"
        const val KEY_HIDDEN = "hidden_items"
    }
}
