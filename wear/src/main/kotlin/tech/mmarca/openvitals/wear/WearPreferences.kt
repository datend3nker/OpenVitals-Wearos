package tech.mmarca.openvitals.wear

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import tech.mmarca.openvitals.domain.preferences.StrideLength
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/**
 * The settings kept on the watch. The phone's Watches screen pushes them over
 * the link; the watch's own Tracking screen changes the same values through
 * [setFlag] and [setInterval]. A setting survives a restart, so the watch keeps
 * behaving as the wearer last chose even before the phone reconnects.
 *
 * Only keys and values the protocol defines are accepted; [applyPreference]
 * returns false for anything else, which the phone sees as `ERROR
 * bad_request`. The current value feeds both the status screen (via
 * [WearLinkState]) and the link's advertised capabilities.
 *
 * Every switch defaults on, except [debugData], and the heart rate runs every ten seconds, so a
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

    /**
     * Whether the watch keeps debug files for `adb pull` (see [DebugTrace]). Off until the wearer
     * switches it on, unlike the other switches.
     */
    val debugData: Boolean
        get() = flag(WearLinkProtocol.Preferences.DEBUG_DATA)

    /** The heart rate interval by day. */
    val heartRateDay: HeartRateInterval
        get() = interval(WearLinkProtocol.Preferences.HEART_RATE_DAY)

    /** The heart rate interval in bedtime mode while sleep recording is off. */
    val heartRateNight: HeartRateInterval
        get() = interval(WearLinkProtocol.Preferences.HEART_RATE_NIGHT)

    /** The wearer's weight in kilograms as the phone last sent it, or null before it did. */
    val weightKg: Double?
        get() = prefs.getInt(KEY_WEIGHT, 0).takeIf { it > 0 }?.let { it / TENTHS }

    /** The step length in metres as the phone last sent it, else the phone's own default. */
    val strideMeters: Double
        get() = prefs.getInt(KEY_STRIDE_CM, 0).takeIf { it > 0 }?.let { it / CENTIMETRES } ?: StrideLength.defaultMeters

    /** Once now and after every stored change, whichever instance made it. */
    val changes: Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** The unit system chosen for display on the watch. */
    var unitSystem: UnitSystem
        get() = prefs.getString(KEY_UNIT_SYSTEM, null)?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC
        set(value) {
            if (unitSystem == value) return
            prefs.edit().putString(KEY_UNIT_SYSTEM, value.name).apply()
            onChange?.invoke()
        }

    /** Changes a tracking flag from the watch's own screen. */
    fun setFlag(protocolKey: String, on: Boolean) {
        val value = if (on) WearLinkProtocol.Preferences.ON else WearLinkProtocol.Preferences.OFF
        applyPreference(protocolKey, value)
    }

    /** Changes a heart rate interval locally on the watch. */
    fun setInterval(protocolKey: String, interval: HeartRateInterval) {
        applyPreference(protocolKey, interval.wire)
    }

    /** Every preference the link knows, in the values [applyPreference] takes: what the phone reads back. */
    fun snapshot(): Map<String, String> = buildMap {
        for (key in FLAG_KEYS) put(key, if (flag(key)) WearLinkProtocol.Preferences.ON else WearLinkProtocol.Preferences.OFF)
        put(WearLinkProtocol.Preferences.HEART_RATE_DAY, heartRateDay.wire)
        put(WearLinkProtocol.Preferences.HEART_RATE_NIGHT, heartRateNight.wire)
    }

    private fun flag(key: String): Boolean = prefs.getBoolean(FLAG_STORAGE.getValue(key), defaultOf(key))

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
            WearLinkProtocol.Preferences.BODY_WEIGHT -> applyNumber(KEY_WEIGHT, value, MIN_WEIGHT_TENTHS..MAX_WEIGHT_TENTHS) ?: return false
            WearLinkProtocol.Preferences.STRIDE_CM -> applyNumber(KEY_STRIDE_CM, value, MIN_STRIDE_CM..MAX_STRIDE_CM) ?: return false
            else -> return false
        }
        if (changed) onChange?.invoke()
        return true
    }

    /** Null for a value that is not a whole number in [range]; true when the stored value changed. */
    private fun applyNumber(storageKey: String, value: String, range: IntRange): Boolean? {
        val number = value.toIntOrNull()?.takeIf { it in range } ?: return null
        if (prefs.getInt(storageKey, 0) == number) return false
        prefs.edit().putInt(storageKey, number).apply()
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
        if (prefs.getBoolean(stored, defaultOf(key)) == on) return false
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
        @Volatile
        private var shared: WearPreferences? = null

        /** The one instance of the process, so a change made on a screen reaches the service's [onChange]. */
        fun shared(context: Context): WearPreferences =
            shared ?: synchronized(this) {
                shared ?: WearPreferences(context.applicationContext).also { shared = it }
            }

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
            WearLinkProtocol.Preferences.DEBUG_DATA to "debug_data",
        )

        /** Every switch starts on, except the debug files. */
        private fun defaultOf(key: String): Boolean = key != WearLinkProtocol.Preferences.DEBUG_DATA
        private val FLAG_KEYS: Set<String> = FLAG_STORAGE.keys
        private const val KEY_SLEEP_RECORDING = "sleep_recording"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_WEIGHT = "body_weight_tenths"
        private const val KEY_STRIDE_CM = "stride_cm"
        private const val TENTHS = 10.0
        private const val CENTIMETRES = 100.0

        /** 20 to 500 kg, in tenths. */
        private const val MIN_WEIGHT_TENTHS = 200
        private const val MAX_WEIGHT_TENTHS = 5000

        /** The bounds of `StrideLength`, in centimetres. */
        private val MIN_STRIDE_CM = (StrideLength.minMeters * 100).toInt()
        private val MAX_STRIDE_CM = (StrideLength.maxMeters * 100).toInt()
    }
}
