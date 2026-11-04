package tech.mmarca.openvitals.wear

/**
 * The last [maxMillis] of raw PPG samples, appended on the sensor's thread
 * and copied out on another, so every access holds the same lock. Times are
 * the sensor clock in milliseconds, the values what `NightHrv.analyze` takes.
 * Pure Kotlin so the trimming is tested without a sensor.
 */
class PpgBuffer(private val maxMillis: Long) {
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var values = DoubleArray(INITIAL_CAPACITY)
    private var first = 0
    private var size = 0

    /** A copy of the samples held now, oldest first. */
    class Snapshot(val times: DoubleArray, val values: DoubleArray) {
        val isEmpty: Boolean get() = times.isEmpty()

        /** Milliseconds from the first sample to the last. */
        val spanMillis: Double get() = if (times.size < 2) 0.0 else times.last() - times.first()
    }

    @Synchronized
    fun add(timeMillis: Double, value: Double) {
        if (first + size == times.size) makeRoom()
        times[first + size] = timeMillis
        values[first + size] = value
        size++
        while (size > 0 && timeMillis - times[first] > maxMillis) {
            first++
            size--
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        times.copyOfRange(first, first + size),
        values.copyOfRange(first, first + size),
    )

    /** Moves the live samples to the front, or doubles the arrays when they are mostly live. */
    private fun makeRoom() {
        val capacity = if (size * 2 > times.size) times.size * 2 else times.size
        val movedTimes = DoubleArray(capacity)
        val movedValues = DoubleArray(capacity)
        System.arraycopy(times, first, movedTimes, 0, size)
        System.arraycopy(values, first, movedValues, 0, size)
        times = movedTimes
        values = movedValues
        first = 0
    }

    private companion object {
        const val INITIAL_CAPACITY = 4_096
    }
}
