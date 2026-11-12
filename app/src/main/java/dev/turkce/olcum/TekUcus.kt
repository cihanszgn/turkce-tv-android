package dev.turkce.olcum

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Shown when another call already holds the PS5 link's native side. */
internal fun busyMessage(task: String) = "$task sürüyor, bitmesini bekle."

/**
 * One call at a time through a shared native state. A second call waits up to its own limit (none by default) and
 * then fails at once with a message naming the call in progress, instead of running beside it.
 */
internal class SingleFlight {
    private val lock = ReentrantLock()
    @Volatile private var running: String? = null

    /** True while a call runs. */
    val busy: Boolean get() = lock.isLocked

    /** The running call's name, or null. */
    val current: String? get() = running

    fun <T> run(task: String, waitMs: Long = 0, block: () -> T): T {
        val got = if (waitMs > 0) lock.tryLock(waitMs, TimeUnit.MILLISECONDS) else lock.tryLock()
        if (!got) throw IllegalStateException(busyMessage(running ?: task))
        running = task
        try {
            return block()
        } finally {
            running = null
            lock.unlock()
        }
    }
}

/**
 * The native PS5 code keeps one pairing state and initialises its library on first use without a lock, so pairing,
 * waking and opening a session go through here one at a time. Kept apart from [ChiakiBridge] so it loads without
 * the native library.
 */
internal object ChiakiGate {
    const val PAIRING = "Eşleştirme"
    const val WAKING = "Uyandırma"
    const val CONNECTING = "PlayStation bağlantısı"

    /** Waking only sends a packet; a session start waits this long for it rather than failing. */
    const val SHORT_WAIT_MS = 3_000L

    val calls = SingleFlight()
}
