package tech.mmarca.openvitals.wear

import android.content.Context
import android.content.SharedPreferences
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/**
 * The settings the phone pushes over the link, kept on the watch. The watch
 * is a companion: it stores and shows these, the phone's Watches screen is
 * where they are changed. A setting survives a restart, so the watch keeps
 * behaving as the wearer last chose even before the phone reconnects.
 *
 * Only keys and values the protocol defines are accepted; [applyPreference]
 * returns false for anything else, which the phone sees as `ERROR
 * bad_request`. The current value feeds both the status screen (via
 * [WearLinkState]) and the link's advertised capabilities.
 */
class WearPreferences(
    private val prefs: SharedPreferences,
    private val state: WearLinkState = WearLinkState,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Called on the thread that pushed the change when sleep recording flips. */
    var onSleepRecordingChange: ((Boolean) -> Unit)? = null

    /** Whether the watch records sleep minutes. Default on, so an existing watch keeps recording. */
    val sleepRecording: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_RECORDING, true)

    /** Publishes the stored values to the status screen; call once at startup. */
    fun publish() {
        state.update { it.copy(sleepRecording = sleepRecording) }
    }

    /**
     * Applies a preference the phone pushed. Returns false for a key or value
     * this watch does not know.
     */
    fun applyPreference(key: String, value: String): Boolean = when (key) {
        WearLinkProtocol.Preferences.SLEEP_RECORDING -> {
            val on = when (value) {
                WearLinkProtocol.Preferences.ON -> true
                WearLinkProtocol.Preferences.OFF -> false
                else -> return false
            }
            if (on != sleepRecording) {
                prefs.edit().putBoolean(KEY_SLEEP_RECORDING, on).apply()
                state.update { it.copy(sleepRecording = on) }
                onSleepRecordingChange?.invoke(on)
            }
            true
        }
        else -> false
    }

    private companion object {
        const val PREFS = "wear_preferences"
        const val KEY_SLEEP_RECORDING = "sleep_recording"
    }
}
