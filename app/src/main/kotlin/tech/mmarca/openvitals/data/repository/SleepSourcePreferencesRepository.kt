package tech.mmarca.openvitals.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openvitals.healthconnect.PreferredSleepSource

/** In a preference file of its own: [PreferencesRepository] is at its size ceiling. */
@Singleton
class SleepSourcePreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) : PreferredSleepSource {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override var preferredSleepSource: String?
        get() = prefs.getString(KEY_SOURCE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_SOURCE) else putString(KEY_SOURCE, value)
        }

    private companion object {
        const val PREFS_FILE = "openvitals_sleep_source_preferences"
        const val KEY_SOURCE = "preferred_sleep_source"
    }
}
