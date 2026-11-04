package tech.mmarca.openvitals.devices.wearos

import tech.mmarca.openvitals.devices.core.sync.DeviceSyncResult

/**
 * Start and end of one watch sync for the debug log: how long it took and how it ended. Kept out of
 * `WearOsWatchSyncService`, which is at the file size limit. Counts and types only.
 */
internal object WearOsSyncTrace {

    suspend fun around(clock: () -> Long = System::currentTimeMillis, block: suspend () -> DeviceSyncResult): DeviceSyncResult {
        val startedAt = clock()
        WearOsLog.log("sync start")
        val result = try {
            block()
        } catch (error: Throwable) {
            WearOsLog.failure("sync aborted after ${clock() - startedAt} ms", error)
            throw error
        }
        WearOsLog.log("sync end after ${clock() - startedAt} ms: ${describe(result)}")
        return result
    }

    internal fun describe(result: DeviceSyncResult): String = when (result) {
        is DeviceSyncResult.Succeeded ->
            "succeeded, rows=${result.fileCount} nights=${result.nightCount} stepMinutes=${result.stepMinuteCount} " +
                "unwritten=${result.unwritten.size} withheldGrants=${result.withheldPermissions.size}"
        is DeviceSyncResult.Failed -> "failed (${result.error::class.java.simpleName})"
    }
}
