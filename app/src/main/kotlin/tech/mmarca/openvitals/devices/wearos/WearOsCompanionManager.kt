package tech.mmarca.openvitals.devices.wearos

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * High-level manager to query whether a Wear OS watch is paired and whether
 * the OpenVitals Wear OS app on it answers, and to follow its live heart rate.
 */
@Singleton
class WearOsCompanionManager @Inject constructor(
    private val nodePort: WearOsNodePort,
) {
    /** Throws [SecurityException] when the Bluetooth permission is missing. */
    suspend fun checkWearOsWatchStatus(
        targetAddress: String? = null,
        targetName: String? = null,
    ): WearOsCompanionStatus = nodePort.checkStatus(targetAddress, targetName)

    /** The value the phone last pushed for [setting] to the watch registered under [targetAddress]. */
    fun watchSetting(targetAddress: String?, setting: WearOsWatchSetting): Boolean =
        nodePort.watchSetting(targetAddress, setting)

    /**
     * Pushes [setting] to the watch. True when applied; false when the watch
     * app is too old to accept settings or no bonded watch matches. Throws
     * [SecurityException] for a missing Bluetooth grant, [WearOsLinkFailure]
     * for a watch that could not be reached.
     */
    suspend fun setWatchSetting(
        targetAddress: String?,
        targetName: String?,
        setting: WearOsWatchSetting,
        enabled: Boolean,
    ): Boolean = nodePort.setWatchSetting(targetAddress, targetName, setting, enabled)

    /**
     * The watch's heart rate as it happens, for as long as it is collected:
     * each reading's bpm, and null whenever the stream pauses (the watch is
     * off the wrist, out of reach, or the connection was renewed). Ends when
     * the watch app cannot stream or the Bluetooth grant is missing. Nothing
     * is stored; the recorded samples arrive with the next sync.
     */
    fun liveHeartRate(targetAddress: String?, targetName: String?): Flow<Int?> = channelFlow {
        while (true) {
            val supported = try {
                nodePort.liveHeartRate(targetAddress, targetName) { trySend(it.beatsPerMinute) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: WearOsLinkFailure) {
                true
            } catch (_: SecurityException) {
                false
            }
            send(null)
            if (!supported) return@channelFlow
            delay(LIVE_RETRY_MILLIS)
        }
    }

    private companion object {
        /** Off the wrist or out of reach: how long until the next try. */
        const val LIVE_RETRY_MILLIS = 15_000L
    }
}
