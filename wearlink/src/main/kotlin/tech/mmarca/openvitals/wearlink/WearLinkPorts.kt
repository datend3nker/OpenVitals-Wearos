package tech.mmarca.openvitals.wearlink

import java.io.InputStream
import java.io.OutputStream

/** One accepted or opened connection: the streams, who the peer is, and how to close it. */
class WearLinkConnection(
    val input: InputStream,
    val output: OutputStream,
    /** The peer's Bluetooth address, the key of its trust. */
    val peerAddress: String,
    private val closer: () -> Unit,
) {
    @Volatile
    var isClosed: Boolean = false
        private set

    fun close() {
        isClosed = true
        runCatching { closer() }
    }
}

/**
 * What the watch knows about phones. A token is stored only once the wearer
 * has allowed the phone; until then the phone is pending.
 */
interface WearLinkTrustStore {
    /** The token stored for [peerAddress], or null when the phone is unknown. */
    fun tokenFor(peerAddress: String): String?

    fun isBlocked(peerAddress: String): Boolean

    /**
     * A phone presented [token], which the watch does not hold for it: an
     * unknown phone, or a known one with another token. Remember it for the
     * wearer to allow or block; allowing replaces a stored token.
     */
    fun notePending(peerAddress: String, token: String, peerName: String)
}

/** One page of a pull. [more] means the limit cut it short. */
data class Page<T>(val items: List<T>, val more: Boolean)

/** What the watch serves once a phone is trusted. */
interface WearLinkRequestHandler {
    /** The watch's name, sent in the `OK` reply. */
    fun localName(): String

    fun capabilities(): Set<String>

    fun heartRateSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HeartRateSample>

    fun sleepMinutesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.SleepMinute>

    fun stepsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.StepMinute>

    fun ascentSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.AscentMinute>

    /** The night-time HRV windows newer than [sinceEpochMillis], oldest first. Only called when the watch advertised [WearLinkProtocol.CAP_HRV]. */
    fun hrvSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HrvWindow>

    /**
     * The finished workouts that started after [sinceEpochMillis], oldest first, at most [limit], each with
     * its pauses and route. Only called when the watch advertised [WearLinkProtocol.CAP_WORKOUTS].
     */
    fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout>

    /**
     * The entries the wearer logged on the watch and that are newer than
     * [sinceEpochMillis], oldest first, at most [limit]. Only called when the
     * watch advertised [WearLinkProtocol.CAP_LOG].
     */
    fun logEntriesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.LogEntry>

    /**
     * Blocks for up to [seconds], handing each heart rate reading to [emit]
     * on the caller's thread as it arrives; returns how many. Returns 0 at
     * once when the watch is off the wrist or cannot measure.
     */
    fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int

    /**
     * Apply a preference the phone pushed. Returns false for a key or value
     * this watch does not know, which the phone sees as `ERROR bad_request`.
     * Only called when the watch advertised [WearLinkProtocol.CAP_PREFERENCES].
     */
    fun setPreference(key: String, value: String): Boolean

    /**
     * Keeps the phone's [kind] value for [epochDay] (days since 1970-01-01 in the wearer's zone),
     * in tenths of the unit. False when the watch refuses it, for a day it does not keep.
     */
    fun putDay(kind: WearLinkProtocol.DayKind, epochDay: Long, tenths: Int): Boolean

    /**
     * Every preference the watch holds, by key, in the values [setPreference] takes. The watch is the
     * truth: the wearer can change a setting on the watch itself, and the phone reads it back with this.
     */
    fun heldPreferences(): Map<String, String>
    /** Keeps the phone's settings for the tiles; the newest replaces the last. Only a watch announcing `pf` is sent it. */
    fun applyProfile(profile: WearLinkProfile.Profile)

    /** Keeps Health Connect's totals for today; the newest replaces the last. Only a watch announcing `pf` is sent them. */
    fun applyTotals(totals: WearLinkProfile.Totals)
}
