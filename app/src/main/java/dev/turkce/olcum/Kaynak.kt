package dev.turkce.olcum

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Shown when the demo clip cannot be opened; the service keeps it on screen after the run ends. */
internal const val DEMO_FAILED = "Deneme görüntüsü açılamadı."

/**
 * Where the translation's H.264 comes from: the PS5's Remote Play stream, or the demo clip inside the app.
 * Everything after it (decoder choice, frames, OCR, filters, translation, overlay) is the same code for both.
 */
internal interface VideoSource : AutoCloseable {
    /** What the TV and the phone show while [open] runs. */
    val openingNote: String

    /** What they show when [open] fails. */
    val failedNote: String

    /** True once the source has nothing more to give (the PS5 ended the session). */
    val ended: Boolean

    /** Gets ready to give samples; false when it cannot. */
    fun open(): Boolean

    /**
     * Writes the next H.264 access unit, with start codes, into [target] from position 0. Returns its size,
     * 0 when none came within [waitMs], or -1 when one did not fit and was skipped.
     */
    fun next(target: ByteBuffer, waitMs: Int): Int

    /** Asks for a key frame soon, for a decoder that started without one. */
    fun requestKeyFrame()

    /** Samples, bytes, lost, recovered and dropped frames so far, for the log. */
    fun counters(): LongArray

    /** Why the source ended, for the log. */
    fun endReason(): String?
}

/** The demo clip in the APK, copied once per run of the app to a plain file both the player and the reader can open. */
internal object DemoClip {
    private const val ASSET = "demo/deneme.mp4"
    private var copied: File? = null

    /** The clip as a file; the APK may keep assets compressed, so it is copied out rather than opened in place. */
    @Synchronized
    fun file(context: Context): File {
        copied?.takeIf { it.isFile }?.let { return it }
        val target = File(context.cacheDir, "deneme.mp4")
        val temporary = File(context.cacheDir, "deneme.mp4.yeni")
        context.assets.open(ASSET).use { input -> temporary.outputStream().use { input.copyTo(it) } }
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { DEMO_FAILED }
        }
        copied = target
        return target
    }
}

/**
 * Gives the demo clip's frames as if they came live: each at its own time, the clip starting over at its end.
 * Reads only the file in the app; no network and no PS5. [startedAtNs] tells the demo screen where the clip is.
 */
internal class DemoSource(private val context: Context, private val log: (String) -> Unit) : VideoSource {
    override val openingNote = "Deneme görüntüsü açılıyor"
    override val failedNote = DEMO_FAILED
    override val ended = false
    private val extractor = MediaExtractor()
    private var header = ByteArray(0)
    private var startNs = 0L
    private var offsetUs = 0L
    private var lastUs = 0L
    private var frameUs = 33_333L
    private var samples = 0L
    private var bytes = 0L

    override fun open(): Boolean = runCatching {
        extractor.setDataSource(DemoClip.file(context).path)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        // An MP4 keeps SPS and PPS beside the samples; a live stream carries them in band, so they go before each key frame.
        header = listOf("csd-0", "csd-1").filter { format.containsKey(it) }.fold(ByteArray(0)) { all, key ->
            val buffer = format.getByteBuffer(key)!!.duplicate()
            all + ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameUs = 1_000_000L / format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
        log("  deneme klibi: $format")
        true
    }.onFailure { log("  deneme klibi acilamadi: $it") }.getOrDefault(false)

    override fun next(target: ByteBuffer, waitMs: Int): Int {
        val now = System.nanoTime()
        if (startNs == 0L) {
            startNs = now
            Translation.demoStartNs = now
        }
        val dueNs = startNs + (offsetUs + extractor.sampleTime) * 1_000
        if (dueNs - now > waitMs * 1_000_000L) {
            Thread.sleep(waitMs.toLong())
            return 0
        }
        if (dueNs > now) Thread.sleep((dueNs - now) / 1_000_000, ((dueNs - now) % 1_000_000).toInt())
        val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        val prefix = if (key) header.size else 0
        val size = try {
            target.clear()
            if (key) target.put(header)
            extractor.readSampleData(target, prefix)
        } catch (_: IllegalArgumentException) {
            -1
        }
        lastUs = extractor.sampleTime
        if (!extractor.advance() || extractor.sampleTime < 0) {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            offsetUs += lastUs + frameUs
        }
        if (size < 0) return -1
        target.position(0)
        samples++
        bytes += prefix + size
        return prefix + size
    }

    /** Every key frame of the clip already carries what a decoder needs. */
    override fun requestKeyFrame() = Unit

    override fun counters() = longArrayOf(samples, bytes, 0, 0, 0)

    override fun endReason(): String? = null

    override fun close() {
        Translation.demoStartNs = 0L
        extractor.release()
    }
}
