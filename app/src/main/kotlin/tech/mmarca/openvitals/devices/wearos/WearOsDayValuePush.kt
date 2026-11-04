package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.domain.model.DailyHydration
import tech.mmarca.openvitals.domain.preferences.StrideLength
import tech.mmarca.openvitals.domain.model.WeightEntry
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/** One day's value for the watch, in tenths of the unit (see [WearLinkProtocol.DayKind]). */
data class WearOsDayValue(val kind: WearLinkProtocol.DayKind, val epochDay: Long, val tenths: Int)

/**
 * What the watch needs to turn steps into distance and energy: the wearer's weight in tenths of a
 * kilogram (null when unknown) and the step length in centimetres.
 */
data class WearOsStepProfile(val weightTenthsKg: Int?, val strideCm: Int)

/**
 * The last seven days of what the phone knows and the watch cannot measure:
 * the water drunk per day and the day's last weight, from Health Connect. The
 * watch keeps the newest value for each day, so sending them all every sync is
 * safe and a late entry on the phone reaches the watch at the next one.
 *
 * Without a read grant Health Connect returns nothing, so nothing is sent and
 * the watch keeps what it has. The weight and step length go along as watch settings.
 */
internal class WearOsDayValuePush(
    private val nodePort: WearOsNodePort,
    /** The weight the watch's energy estimate uses: Health Connect's latest, else the profile's. Null when unknown. */
    private val weightKg: suspend () -> Double?,
    private val strideMeters: () -> Double,
    private val hydrationByDay: suspend (LocalDate, LocalDate) -> List<DailyHydration>,
    private val weights: suspend (Instant, Instant) -> List<WeightEntry>,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
) {
    /**
     * Sends the values to the watch after the sync's pulls, so the totals hold what the watch logged.
     * Optional: a failure here costs the tiles one sync and never fails it.
     */
    suspend fun send(address: String?, name: String?) {
        try {
            val values = values()
            val profile = WearOsStepProfile(
                weightTenthsKg = weightKg()?.let { (it * TENTHS).roundToInt() },
                strideCm = (StrideLength.normalize(strideMeters()) * CENTIMETRES_PER_METRE).roundToInt(),
            )
            val kept = nodePort.pushDayValues(address, name, values, profile)
            WearOsLog.log("day values: ${kept ?: 0} of ${values.size + 2} kept by the watch")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("day values not sent: ${error::class.java.simpleName}")
        }
    }

    suspend fun values(): List<WearOsDayValue> {
        val last = today()
        val first = last.minusDays(WearLinkProtocol.DAY_VALUE_DAYS - 1L)
        val zone = zone()
        val water = hydrationByDay(first, last)
            .filter { it.liters > 0.0 && it.date in first..last }
            .map { WearOsDayValue(WearLinkProtocol.DayKind.HYDRATION, it.date.toEpochDay(), (it.liters * MILLILITRES_PER_LITRE * TENTHS).roundToInt()) }
        val weight = weights(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapNotNull { (date, entries) ->
                val kilograms = entries.maxBy { it.time }.weightKg
                if (kilograms <= 0.0 || date !in first..last) return@mapNotNull null
                WearOsDayValue(WearLinkProtocol.DayKind.WEIGHT, date.toEpochDay(), (kilograms * TENTHS).roundToInt())
            }
        return (water + weight).filter { it.tenths > 0 }.sortedBy { it.epochDay }
    }

    companion object {
        private const val MILLILITRES_PER_LITRE = 1000.0
        private const val TENTHS = 10.0

        private const val CENTIMETRES_PER_METRE = 100.0

        fun of(
            healthConnect: HealthConnectManager,
            nodePort: WearOsNodePort,
            clock: () -> Instant,
            zone: () -> ZoneId,
            weightKg: suspend () -> Double?,
            strideMeters: () -> Double,
        ) =
            WearOsDayValuePush(
                nodePort = nodePort,
                weightKg = weightKg,
                strideMeters = strideMeters,
                hydrationByDay = { from, to -> healthConnect.readDailyHydration(from, to) },
                weights = { from, to -> healthConnect.readWeightEntries(from, to) },
                zone = zone,
                today = { clock().atZone(zone()).toLocalDate() },
            )
    }
}
