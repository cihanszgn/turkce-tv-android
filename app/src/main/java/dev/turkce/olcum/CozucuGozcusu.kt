package dev.turkce.olcum

/** How long a decoder may hold input without a frame while samples keep coming, before it counts as stuck. */
internal const val STALL_LIMIT_NS = 5_000_000_000L

/** Input this recent means the source still sends; a longer gap means the source went quiet, not the decoder. */
internal const val STALL_FRESH_NS = 1_000_000_000L

/**
 * Tells a stuck decoder from a quiet source. The decoder is stuck when it has held input for [limitNs] without giving
 * a frame while samples kept going in, or while it refused more because its buffers were full. A source that goes
 * quiet never counts: the wait starts over when samples come back after a gap.
 */
internal class StallWatch(private val limitNs: Long = STALL_LIMIT_NS, private val freshNs: Long = STALL_FRESH_NS) {
    private var waiting = false
    private var waitingSince = 0L
    private var pushed = false
    private var lastPush = 0L

    /** A sample went into the decoder at [now]. */
    fun fed(now: Long) {
        if (!waiting || !pushed || now - lastPush > freshNs) {
            waiting = true
            waitingSince = now
        }
        pushed = true
        lastPush = now
    }

    /** The decoder had no free input buffer at [now]: it is holding what it got. */
    fun refused(now: Long) {
        if (waiting) {
            pushed = true
            lastPush = now
        }
    }

    /** A frame came out: nothing is waiting any more. */
    fun output() {
        waiting = false
    }

    /** A new decoder starts from scratch. */
    fun reset() {
        waiting = false
        pushed = false
    }

    fun stalled(now: Long) = waiting && pushed && now - waitingSince >= limitNs && now - lastPush <= freshNs
}

/**
 * Which decoder takes over when one throws or sticks mid-stream. A stuck decoder is restarted once; when it sticks
 * again before a frame, or throws, the next one in the list takes over. Decoders that failed are skipped until one
 * gives a frame again, so a run ends with a message only when every decoder has failed in a row.
 */
internal class DecoderFailover(private val count: Int) {
    private val failed = HashSet<Int>()
    private var restartedStuck = false

    /** Decoders not to open again for now. */
    val skipped: Set<Int> get() = failed

    /** A frame came out: every decoder may be tried again after a later failure. */
    fun worked() {
        if (failed.isNotEmpty()) failed.clear()
        restartedStuck = false
    }

    /** Decoder [index] threw: the next one to open, or null when all have failed in a row. */
    fun threw(index: Int): Int? {
        failed += index
        restartedStuck = false
        return (1..count).map { (index + it) % count }.firstOrNull { it !in failed }
    }

    /** Decoder [index] stuck: itself once, then the next one; null when all have failed in a row. */
    fun stuck(index: Int): Int? {
        if (!restartedStuck) {
            restartedStuck = true
            return index
        }
        return threw(index)
    }
}
