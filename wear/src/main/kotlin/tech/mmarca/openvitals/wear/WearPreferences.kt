package tech.mmarca.openvitals.wear

import android.content.Context
import android.content.SharedPreferences
import tech.mmarca.openvitals.wearlink.HeartRateInterval
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
 *
 * Every switch defaults on and the heart rate runs every ten seconds, so a
 * watch that never heard from the phone records what it did before.
 */
class WearPreferences(
    private val prefs: SharedPreferences,
    private val state: WearLinkState = WearLinkState,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Called on the thread that pushed a change, after it is stored. The service then re-reads what it needs. */
    var onChange: (() -> Unit)? = null

    /** Whether the watch records sleep minutes. Default on, so an existing watch keeps recording. */
    val sleepRecording: Boolean
        get() = flag(WearLinkProtocol.Preferences.SLEEP_RECORDING)

    /** Whether the watch measures heart rate at all. */
    val heartRate: Boolean
        get() = flag(WearLinkProtocol.Preferences.HEART_RATE)

    /** Whether the watch counts steps. */
    val steps: Boolean
        get() = flag(WearLinkProtocol.Preferences.STEPS)

    /** Whether the watch records height climbed. */
    val ascent: Boolean
        get() = flag(WearLinkProtocol.Preferences.ASCENT)

    /** The heart rate interval by day. */
    val heartRateDay: HeartRateInterval
        get() = interval(WearLinkProtocol.Preferences.HEART_RATE_DAY)

    /** The heart rate interval in bedtime mode while sleep recording is off. */
    val heartRateNight: HeartRateInterval
        get() = interval(WearLinkProtocol.Preferences.HEART_RATE_NIGHT)

    private fun flag(key: String): Boolean = prefs.getBoolean(FLAG_STORAGE.getValue(key), true)

    /** Publishes the stored values to the status screen; call once at startup. */
    fun publish() {
        state.update { it.copy(sleepRecording = sleepRecording) }
    }

    /**
     * Applies a preference the phone pushed. Returns false for a key or value
     * this watch does not know.
     */
    fun applyPreference(key: String, value: String): Boolean {
        val changed = when (key) {
            in FLAG_KEYS -> applyFlag(key, value) ?: return false
            WearLinkProtocol.Preferences.HEART_RATE_DAY, WearLinkProtocol.Preferences.HEART_RATE_NIGHT ->
                applyInterval(key, value) ?: return false
            else -> return false
        }
        if (changed) onChange?.invoke()
        return true
    }

    /** Null for a value that is not on or off; true when the stored value changed. */
    private fun applyFlag(key: String, value: String): Boolean? {
        val on = when (value) {
            WearLinkProtocol.Preferences.ON -> true
            WearLinkProtocol.Preferences.OFF -> false
            else -> return null
        }
        val stored = FLAG_STORAGE.getValue(key)
        if (prefs.getBoolean(stored, true) == on) return false
        prefs.edit().putBoolean(stored, on).apply()
        if (key == WearLinkProtocol.Preferences.SLEEP_RECORDING) {
            state.update { it.copy(sleepRecording = on) }
        }
        return true
    }

    /** Null for a value this build does not know; true when the stored value changed. */
    private fun applyInterval(key: String, value: String): Boolean? {
        val interval = HeartRateInterval.fromWire(value) ?: return null
        if (prefs.getString(key, null) == interval.wire) return false
        prefs.edit().putString(key, interval.wire).apply()
        return true
    }

    private fun interval(key: String): HeartRateInterval =
        HeartRateInterval.fromWire(prefs.getString(key, null).orEmpty()) ?: DEFAULT_INTERVAL

    companion object {
        /** The interval a watch measures at before the phone says otherwise. */
        val DEFAULT_INTERVAL: HeartRateInterval = HeartRateInterval.EVERY_10_SECONDS

        private const val PREFS = "wear_preferences"

        /**
         * The file key of each switch, by its protocol key. The sleep switch
         * keeps the name it was first stored under, so an upgraded watch keeps
         * its choice.
         */
        private val FLAG_STORAGE = mapOf(
            WearLinkProtocol.Preferences.SLEEP_RECORDING to KEY_SLEEP_RECORDING,
            WearLinkProtocol.Preferences.HEART_RATE to "heart_rate",
            WearLinkProtocol.Preferences.STEPS to "steps",
            WearLinkProtocol.Preferences.ASCENT to "ascent",
        )
        private val FLAG_KEYS: Set<String> = FLAG_STORAGE.keys
        private const val KEY_SLEEP_RECORDING = "sleep_recording"
    }
}
