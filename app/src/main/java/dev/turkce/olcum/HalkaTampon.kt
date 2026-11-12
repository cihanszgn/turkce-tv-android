package dev.turkce.olcum

/**
 * Timings for the logs that stay the same size however long the translation runs: the last [capacity] values for
 * percentiles, plus a count and a sum over the whole run. Safe to add to from the worker threads.
 */
internal class RecentSamples(private val capacity: Int = 1_000) {
    private val values = LongArray(capacity)
    private var next = 0
    private var kept = 0
    private var total = 0L
    private var sum = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun add(value: Long) {
        values[next] = value
        next = (next + 1) % capacity
        if (kept < capacity) kept++
        total++
        sum += value
    }

    /** How many were added over the whole run. */
    @get:Synchronized
    val count: Long get() = total

    /** The mean over the whole run, or -1 when nothing was added. */
    @Synchronized
    fun average(): Long = if (total == 0L) -1 else sum / total

    /** The values kept, oldest first. */
    @Synchronized
    fun toList(): List<Long> = List(kept) { values[(next - kept + it + capacity) % capacity] }
}
