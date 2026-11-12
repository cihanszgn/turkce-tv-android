package dev.turkce.olcum

import java.nio.ByteBuffer

/** The native side of the PS5 connection, `chiaki/chiaki_jni.c`. One session at a time. */
internal object ChiakiBridge {
    const val STATE_CONNECTED = 2
    const val STATE_ENDED = 3

    init {
        System.loadLibrary("turkce_chiaki")
    }

    /** Pairs with the PS5 and returns the regist key followed by the rp key, or null. */
    external fun regist(host: String, accountId: ByteArray, pin: Int): ByteArray?
    external fun wake(host: String, registKey: ByteArray): Boolean
    external fun start(
        host: String, registKey: ByteArray, rpKey: ByteArray, accountId: ByteArray,
        width: Int, height: Int, fps: Int, bitrateKbps: Int,
    ): Boolean
    external fun state(): Int
    external fun quitReason(): String?

    /** Copies one H.264 sample into a direct buffer: its size, 0 when none came in time, -1 when too big. */
    external fun nextSample(target: ByteBuffer, timeoutMs: Int): Int

    /** Samples, bytes, frames lost, frames recovered, samples dropped, samples queued. */
    external fun stats(): LongArray
    external fun requestIdr()
    external fun stop()
}
