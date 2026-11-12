package dev.turkce.olcum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val KEY_SIZE = 16
private const val ACCOUNT_SIZE = 8
private const val MAX_SAMPLE = 2 * 1024 * 1024
private const val STREAM_FPS = 30
private const val STREAM_KBPS = 15_000
private const val CONNECT_TIMEOUT_S = 30
private const val FIRST_FRAME_TIMEOUT_S = 40
private const val DECODER_RESTART_S = 8
private const val BOX_SCAN_MS = 1_000L
private const val TIMED_READS = 40
private const val READ_GAP_MS = 100L

/** One OCR engine serves both reads, so scans stay small: the usual top-left corner, then all above the subtitles. */
private val SCAN_AREAS = listOf(ScreenBlock(0, 0, 960, 389, ""), ScreenBlock(0, 0, FRAME_W, CROP_Y, ""))
private const val REPORT_EVERY_S = 10
private const val TRACED_SAMPLES = 6
private const val MAX_DUMP = 8L * 1024 * 1024
private const val PREVIEW_W = 480
private const val PREVIEW_H = 270
/** Samples in the decoder waiting for their frame, for the latency log; more means frames were dropped. */
private const val MAX_PENDING = 256
/** How long the end of a run waits for each worker before it closes what the worker uses. */
private const val OCR_STOP_S = 10L
private const val TRANSLATE_STOP_S = 30L

/**
 * What pairing leaves behind. Kept in the app's private folder, never in the results folder, encrypted with the
 * Keystore key ([SecretFiles]); a file that cannot be decrypted counts as no pairing.
 */
internal class Pairing(val registKey: ByteArray, val rpKey: ByteArray, val account: ByteArray, val host: String) {
    fun save(file: File) = SecretFiles.write(file, registKey + rpKey + account + host.toByteArray())

    companion object {
        fun load(file: File): Pairing {
            check(file.exists()) { "PS5 kaydi yok, once ps5kayit" }
            val data = checkNotNull(SecretFiles.read(file)) { "PS5 kaydı açılamadı; PlayStation'ı yeniden eşleştir." }
            val keys = KEY_SIZE * 2
            check(data.size >= keys + ACCOUNT_SIZE) { "PS5 kaydı bozuk; PlayStation'ı yeniden eşleştir." }
            return Pairing(
                data.copyOfRange(0, KEY_SIZE), data.copyOfRange(KEY_SIZE, keys),
                data.copyOfRange(keys, keys + ACCOUNT_SIZE), String(data, keys + ACCOUNT_SIZE, data.size - keys - ACCOUNT_SIZE),
            )
        }
    }
}

/** The PS5's Remote Play stream, through chiaki-ng, as a [VideoSource]. */
private class Ps5Source(private val pairingFile: File, private val log: (String) -> Unit) : VideoSource {
    override val openingNote = "PlayStation'a bağlanıyor"
    override val failedNote = "PlayStation'a bağlanamadı: PlayStation açık mı, yeni hesapta Uzaktan Oynama etkin mi?"
    override val ended get() = ChiakiBridge.state() == ChiakiBridge.STATE_ENDED

    override fun open(): Boolean {
        val pairing = Pairing.load(pairingFile)
        val started = System.nanoTime()
        val opened = ChiakiGate.calls.run(ChiakiGate.CONNECTING, ChiakiGate.SHORT_WAIT_MS) {
            ChiakiBridge.start(
                pairing.host, pairing.registKey, pairing.rpKey, pairing.account,
                FRAME_W, FRAME_H, STREAM_FPS, STREAM_KBPS,
            )
        }
        if (!opened) {
            log("  oturum acilamadi")
            return false
        }
        val deadline = started + CONNECT_TIMEOUT_S * 1_000_000_000L
        while (System.nanoTime() < deadline && !Translation.stopAsked) {
            when (ChiakiBridge.state()) {
                ChiakiBridge.STATE_CONNECTED -> {
                    log("  baglandi ${(System.nanoTime() - started) / 1_000_000} ms")
                    return true
                }
                ChiakiBridge.STATE_ENDED -> {
                    log("  baglanamadi: ${ChiakiBridge.quitReason()}")
                    return false
                }
            }
            Thread.sleep(50)
        }
        log("  $CONNECT_TIMEOUT_S sn icinde baglanamadi")
        return false
    }

    override fun next(target: ByteBuffer, waitMs: Int) = ChiakiBridge.nextSample(target, waitMs)

    override fun requestKeyFrame() = ChiakiBridge.requestIdr()

    override fun counters(): LongArray = ChiakiBridge.stats()

    override fun endReason() = ChiakiBridge.quitReason()

    override fun close() = ChiakiBridge.stop()
}

/**
 * Decodes the live stream into readable YUV 4:2:0 frames. The Sony's MediaTek decoder goes first, as in the
 * clip test; on other TVs the best H.264 decoder Android lists, and the next one when a decoder fails or stays silent.
 */
private class LiveDecoder(private val log: (String) -> Unit, private val source: VideoSource, dump: File? = null) {
    private val dumpOut = dump?.outputStream()
    private var dumped = 0L
    private var traced = 0
    private val decoders = avcDecoders()
    private var current = 0

    /** Logs the first samples and keeps the start of the stream, to see what the PS5 really sends. */
    private fun trace(sample: ByteBuffer, size: Int) {
        val bytes = ByteArray(size)
        sample.duplicate().get(bytes)
        if (traced < TRACED_SAMPLES) {
            traced++
            val start = bytes.take(16).joinToString(" ") { "%02x".format(it) }
            log("  ornek $traced: $size bayt: $start")
        }
        if (dumpOut != null && dumped < MAX_DUMP) {
            dumpOut.write(bytes)
            dumped += size
        }
    }

    /**
     * Runs for [seconds], or until stopped when it is 0 or less. [onImage] gets each frame with where the picture
     * sits in it, as the decoder's output format (or the frame's crop) describes.
     */
    fun run(seconds: Int, onImage: (Image, PictureRect) -> Unit): DecodeResult {
        val failover = DecoderFailover(decoders.size)
        val watch = StallWatch()
        var codec = try {
            newCodec()
        } catch (error: Exception) {
            runCatching { dumpOut?.close() }
            throw error
        }
        try {
            val staging = ByteBuffer.allocateDirect(MAX_SAMPLE)
            val info = MediaCodec.BufferInfo()
            val fed = HashMap<Long, Long>()
            val latencies = RecentSamples()
            var frames = 0
            var images = 0
            var nullImages = 0
            var input = -1
            var idrAsked = false
            var header: ByteArray? = null
            var resendHeader = false
            var restartAt = DECODER_RESTART_S
            var restarts = 0
            var picture: PictureRect? = null
            val start = System.nanoTime()
            val end = if (seconds > 0) start + seconds * 1_000_000_000L else Long.MAX_VALUE
            var report = Report(start, source.counters())

            /** Swaps in the decoder at [next] (or the next one that opens) and has the stream start over on it. */
            fun restart(next: Int) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                codec = newCodec(next, failover.skipped)
                picture = null
                input = -1
                fed.clear()
                resendHeader = true
                watch.reset()
                source.requestKeyFrame()
            }

            while (System.nanoTime() < end && !source.ended && !Translation.stopAsked) {
                val elapsedS = (System.nanoTime() - start) / 1_000_000_000
                if (images == 0 && elapsedS >= FIRST_FRAME_TIMEOUT_S) {
                    log("  $FIRST_FRAME_TIMEOUT_S sn icinde kare cikmadi; ${statsLine()}")
                    break
                }
                if (images == 0 && elapsedS >= 5 && !idrAsked) {
                    idrAsked = true
                    source.requestKeyFrame()
                }
                // The Sony's decoder sometimes stays silent on its first run; the next one has always worked.
                // So each decoder gets two runs before the next one in the list takes over.
                if (images == 0 && elapsedS >= restartAt) {
                    restarts++
                    val next = if (restarts % 2 == 0) (current + 1) % decoders.size else current
                    log("  $restartAt sn icinde kare cikmadi, cozucu yeniden baslatiliyor (${decoders[next]}); ${statsLine()}")
                    restartAt += DECODER_RESTART_S
                    restart(next)
                }

                try {
                    if (input < 0) input = codec.dequeueInputBuffer(1_000)
                    if (input < 0) watch.refused(System.nanoTime())
                    val saved = header
                    if (input >= 0 && resendHeader && saved != null) {
                        resendHeader = false
                        codec.getInputBuffer(input)!!.apply { clear(); put(saved) }
                        codec.queueInputBuffer(input, 0, saved.size, (System.nanoTime() - start) / 1000, 0)
                        input = -1
                        watch.fed(System.nanoTime())
                    }
                    if (input >= 0) {
                        staging.clear()
                        val size = outside { source.next(staging, 2) }
                        if (size > 0) {
                            staging.limit(size)
                            trace(staging, size)
                            if (header == null) header = ByteArray(size).also { staging.duplicate().get(it) }
                            val target = codec.getInputBuffer(input)!!
                            target.clear()
                            target.put(staging)
                            val pts = (System.nanoTime() - start) / 1000
                            // Frames a decoder drops never come out; their entries must not pile up in a long run.
                            if (fed.size >= MAX_PENDING) fed.clear()
                            fed[pts] = System.nanoTime()
                            codec.queueInputBuffer(input, 0, size, pts, 0)
                            input = -1
                            watch.fed(System.nanoTime())
                        } else if (size < 0) {
                            log("  ornek $MAX_SAMPLE bayttan buyuk, atlandi")
                        }
                    }

                    while (true) {
                        val output = codec.dequeueOutputBuffer(info, 0)
                        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val format = codec.outputFormat
                            picture = format.picture()
                            log("  cikis bicimi: $format; resim alani $picture")
                            continue
                        }
                        if (output < 0) break
                        frames++
                        watch.output()
                        failover.worked()
                        fed.remove(info.presentationTimeUs)?.let { latencies.add((System.nanoTime() - it) / 1_000_000) }
                        val image = codec.getOutputImage(output)
                        if (image == null) {
                            nullImages++
                        } else {
                            images++
                            image.use {
                                val area = (picture ?: it.picture()).inside(it.width, it.height)
                                outside { onImage(it, area) }
                            }
                        }
                        codec.releaseOutputBuffer(output, false)
                    }
                } catch (error: OutsideFailure) {
                    // The source or the reader failed, not the decoder: that ends the run as before.
                    throw error.cause ?: error
                } catch (error: IllegalStateException) {
                    // MediaCodec.CodecException is one too: the decoder broke mid-stream, so the next one takes over.
                    val next = failover.threw(current)
                    log("  cozucu ${decoders[current]} hata verdi: $error; ${if (next == null) "baska cozucu kalmadi" else "siradaki ${decoders[next]}"}")
                    restart(next ?: throw IllegalStateException(NO_DECODER, error))
                }

                val now = System.nanoTime()
                // After the first picture: input keeps going in, nothing comes out. A quiet source never trips it.
                if (images > 0 && watch.stalled(now)) {
                    val next = failover.stuck(current)
                    log("  cozucu ${decoders[current]} ${STALL_LIMIT_NS / 1_000_000_000} sn kare vermedi; " +
                        "${if (next == null) "baska cozucu kalmadi" else "yeniden baslatiliyor (${decoders[next]})"}; ${statsLine()}")
                    restart(next ?: throw IllegalStateException(NO_DECODER))
                }
                if ((now - report.at) / 1_000_000_000 >= REPORT_EVERY_S) {
                    val next = Report(now, source.counters(), frames)
                    log(report.line(next, (now - start) / 1_000_000_000))
                    report = next
                }
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            return DecodeResult(frames, elapsed, latencies.toList(), nullImages)
        } finally {
            runCatching { dumpOut?.close() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Carries an error of the source or the reader past the decoder's error handling, which is only for the decoder. */
    private class OutsideFailure(cause: Throwable) : RuntimeException(cause)

    private inline fun <T> outside(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw OutsideFailure(error)
    }

    /**
     * Opens the decoder at [first] in the list, or the next one that opens, leaving out [skip]; [current] becomes
     * the one in use.
     */
    private fun newCodec(first: Int = current, skip: Set<Int> = emptySet()): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, FRAME_W, FRAME_H)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        for (step in decoders.indices) {
            val index = (first + step) % decoders.size
            if (index in skip) continue
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(decoders[index])
                codec.configure(format, null, null, 0)
                codec.start()
                current = index
                log("  cozucu ${decoders[index]}")
                return codec
            } catch (error: Exception) {
                log("  cozucu ${decoders[index]} acilamadi: $error")
                codec?.let { runCatching { it.release() } }
            }
        }
        throw IllegalStateException(NO_DECODER)
    }

    private fun statsLine(): String {
        val stats = source.counters()
        return "gelen ornek ${stats[0]}, kayip kare ${stats[2]}, kurtarilan ${stats[3]}, dusen ${stats[4]}"
    }

    /** A snapshot of the counters, so each report covers only its own ten seconds. */
    private class Report(val at: Long, val stats: LongArray, val frames: Int = 0) {
        val cpuMs = Process.getElapsedCpuTime()

        fun line(next: Report, total: Long): String {
            val seconds = (next.at - at) / 1e9
            return "  %d sn: %.1f kare/sn cozuldu, %.1f ornek/sn geldi, %.1f Mbit/s, kayip %d, kurtarilan %d, dusen %d, CPU %%%d (400 uzerinden); %s".format(
                total, (next.frames - frames) / seconds, (next.stats[0] - stats[0]) / seconds,
                (next.stats[1] - stats[1]) * 8 / seconds / 1e6, next.stats[2] - stats[2], next.stats[3] - stats[3],
                next.stats[4] - stats[4], ((next.cpuMs - cpuMs) / 10 / seconds).toInt(), memoryLine())
        }
    }
}

/** Takes the TV's place of the phone against a real PS5. `secrets` is the app's private folder. */
internal class Ps5Measurements(
    private val root: File,
    secrets: File,
    private val log: (String) -> Unit,
    private val context: android.content.Context,
) {
    private val pairingFile = File(secrets, "ps5.kayit")

    /** `account` is the PSN account ID as base64, the same value PS Remote Play tools ask for. */
    fun register(host: String, account: String, pin: String) {
        val accountId = Base64.decode(account, Base64.DEFAULT)
        check(accountId.size == ACCOUNT_SIZE) { "hesap kimligi $ACCOUNT_SIZE bayt olmali" }
        check(pin.length == 8) { "kod 8 hane olmali" }
        log("PS5 KAYIT basliyor")
        val keys = ChiakiGate.calls.run(ChiakiGate.PAIRING) { ChiakiBridge.regist(host, accountId, pin.toInt()) }
        if (keys == null) {
            log("PS5 KAYIT BASARISIZ (logcat etiketi Chiaki)")
            return
        }
        Pairing(keys.copyOfRange(0, KEY_SIZE), keys.copyOfRange(KEY_SIZE, KEY_SIZE * 2), accountId, host).save(pairingFile)
        log("PS5 KAYIT TAMAM")
    }

    fun wake() {
        val pairing = Pairing.load(pairingFile)
        val sent = ChiakiGate.calls.run(ChiakiGate.WAKING) { ChiakiBridge.wake(pairing.host, pairing.registKey) }
        log("PS5 UYANDIRMA ${if (sent) "gonderildi" else "BASARISIZ"}")
    }

    /** The PS5 stream, the product's usual source. */
    fun ps5Source(): VideoSource = Ps5Source(pairingFile, log)

    private fun endLine(requested: Int, result: DecodeResult, source: VideoSource): String {
        val reason = when {
            source.ended -> "KOPTU: ${source.endReason()}"
            Translation.stopAsked -> "durduruldu"
            else -> "sure doldu"
        }
        return "  %d sn istendi, %.0f sn surdu, %s".format(requested, result.seconds, reason)
    }

    /** Connection and video only: how much the stream itself costs. */
    fun streamStage(seconds: Int) {
        log("PS5 AKIS $seconds sn")
        val source = ps5Source()
        try {
            if (!source.open()) return
            var saved = false
            var first = true
            val startCpu = Process.getElapsedCpuTime()
            val start = System.nanoTime()
            val result = LiveDecoder(log, source, File(root, "ps5_ham.h264")).run(seconds) { image, picture ->
                if (first) {
                    first = false
                    log("  ilk kare ${(System.nanoTime() - start) / 1_000_000} ms, ${image.width}x${image.height} bicim ${image.format}, resim $picture")
                }
                if (!saved && (System.nanoTime() - start) / 1_000_000_000 >= 8) {
                    saved = true
                    save(lumaBitmap(cropLuma(image, picture)), "ps5_altyazi.png")
                    save(previewBitmap(image, picture), "ps5_ekran.png")
                }
            }
            val cpu = (Process.getElapsedCpuTime() - startCpu) / 10 / result.seconds
            log("PS5 AKIS SONUC: %.1f kare/sn, video gecikmesi p50 %d ms p95 %d ms, bos kare %d, CPU %%%d (400 uzerinden); %s".format(
                result.frames / result.seconds, percentile(result.latenciesMs, .5), percentile(result.latenciesMs, .95),
                result.nullImages, cpu.toInt(), memoryLine()))
            log(endLine(seconds, result, source))
        } finally {
            source.close()
        }
    }

    private fun gameTranslator(): GameTranslator {
        val phrases = context.assets.open("oyun-sozluk.tsv").bufferedReader().useLines { GameTranslator.parse(it) }
        log("  oyun sozlugu ${phrases.size} kalip")
        return GameTranslator(BergamotTranslator(File(root, "kucuk")), phrases, slang())
    }

    private fun slang() = context.assets.open("argo-sade.tsv").bufferedReader().useLines { Slang.parse(it) }

    /** Shows what the real model makes of each line with and without the slang rewrite. */
    fun slangStage(name: String) {
        val slang = slang()
        BergamotTranslator(File(root, "kucuk")).use { model ->
            for (line in File(root, name).readLines().filter { it.isNotBlank() }) {
                val plain = slang.plain(line)
                log("  ONCE $line => ${model.translate(line)}")
                log("  SONRA $plain => ${model.translate(plain)}")
            }
        }
    }

    /** Reads one scan area, cut from the picture at its corner, and keeps what looks like a whole info box. */
    private fun infoBlocks(
        recognizer: TextRecognizer, picture: Bitmap, area: ScreenBlock, translator: GameTranslator, skipIntro: Boolean = true,
    ): List<ScreenBlock> {
        val blocks = Tasks.await(recognizer.process(InputImage.fromBitmap(picture, 0))).textBlocks.mapNotNull { block ->
            block.boundingBox?.let {
                ScreenBlock(it.left + area.left, it.top + area.top, it.right + area.left, it.bottom + area.top, block.text.replace('\n', ' ').trim())
            }
        }
        return ScreenText.mergeBlocks(blocks).filter { block ->
            val known = translator.knows(block.text)
            block.inside(area) && !(skipIntro && !known && ScreenText.isIntro(block.text)) &&
                (ScreenText.isInfoText(block.text) || ScreenText.looksLikeSpeech(block.text) && known)
        }
    }

    /** The info-box path on one picture, without the PS5, and what the scans cost the subtitle reads. */
    fun boxStage(picture: String) {
        val bitmap = BitmapFactory.decodeFile(File(root, picture).path)
        log("KUTU TESTI ${bitmap.width}x${bitmap.height}")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val boxRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val band = Bitmap.createBitmap(bitmap, CROP_X, CROP_Y, CROP_W, CROP_H)
        val areas = SCAN_AREAS.map { it to Bitmap.createBitmap(bitmap, 0, 0, it.right, it.bottom) }
        fun timedReads() = List(TIMED_READS) {
            val begin = System.nanoTime()
            Tasks.await(recognizer.process(InputImage.fromBitmap(band, 0)))
            val took = (System.nanoTime() - begin) / 1_000_000
            Thread.sleep(READ_GAP_MS)
            took
        }
        gameTranslator().use { translator ->
            val alone = timedReads()
            log("  altyazi okuma tek basina: ort ${alone.average().toLong()} ms, p95 ${percentile(alone, .95)} ms")
            val scanning = AtomicBoolean(true)
            val scans = SCAN_AREAS.map { Collections.synchronizedList(ArrayList<Long>()) }
            val scanner = thread {
                var turn = 0
                while (scanning.get()) {
                    val index = turn++ % areas.size
                    val begin = System.nanoTime()
                    infoBlocks(boxRecognizer, areas[index].second, areas[index].first, translator)
                    val took = (System.nanoTime() - begin) / 1_000_000
                    scans[index].add(took)
                    Thread.sleep((BOX_SCAN_MS - took).coerceAtLeast(0))
                }
            }
            val together = timedReads()
            scanning.set(false)
            scanner.join()
            log("  tarama surerken: ort ${together.average().toLong()} ms, p95 ${percentile(together, .95)} ms; tarama ort kose ${scans[0].average().toLong()} ms, ust ${scans[1].average().toLong()} ms")
            for ((area, part) in areas) {
                for (block in infoBlocks(boxRecognizer, part, area, translator)) {
                    val begin = System.nanoTime()
                    val turkish = translator.translate(block.text)
                    log("  ${(System.nanoTime() - begin) / 1_000_000} ms: $block => $turkish; yer ${ScreenText.placePanel(block, FRAME_W, FRAME_H)}")
                }
            }
        }
        recognizer.close()
        boxRecognizer.close()
    }

    /**
     * Stream + OCR + translation together, on whatever the [source] shows: the PS5, or the demo clip in demo mode.
     * The source is closed when this returns.
     */
    fun pipelineStage(seconds: Int, ocrIntervalMs: Int, reads: Int, source: VideoSource = ps5Source()) {
        log("PS5 BORU HATTI $seconds sn, OCR her $ocrIntervalMs ms, $reads okuma, kaynak ${source.javaClass.simpleName}")
        val translator = gameTranslator()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val boxRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val ocrPool = Executors.newSingleThreadExecutor()
        val boxPool = Executors.newSingleThreadExecutor()
        val translatePool = Executors.newSingleThreadExecutor()
        val ocrBusy = AtomicBoolean(false)
        val boxBusy = AtomicBoolean(false)
        val ocrTimes = RecentSamples()
        val boxTimes = RecentSamples()
        val translateTimes = RecentSamples()
        val subtitle = LiveSubtitle(context)
        val tracker = BoxTracker()
        val live = LiveSettings(context)
        // The demo plays with its own small pack, on or off by the demo screen's OK button; a game uses the pack the
        // player chose, read into memory when chosen, and takes a new choice over on its next frame.
        val demoRun = source is DemoSource
        val demoPack = if (demoRun) DemoPack.get(context) else GamePack.EMPTY
        if (!demoRun) ActivePack.reload(context)
        fun wantedPack() = if (demoRun) (if (Translation.demoPack) demoPack else GamePack.EMPTY) else ActivePack.pack
        // A pack line gets a ✓ (unless the phone turned it off) and every shown line is counted for the phone panel.
        fun marked(source: LineSource, turkish: String) =
            if (source == LineSource.PACK && PackMark.on(context)) PackMark.SIGN + turkish else turkish
        // Which workers finished, once the run has stopped them; null until then.
        var stopped: List<Boolean>? = null
        fun stopWorkers() = stopped ?: stopPools(ocrPool to OCR_STOP_S, boxPool to OCR_STOP_S, translatePool to TRANSLATE_STOP_S)
            .also { stopped = it }
        try {
            Translation.say(source.openingNote)
            if (!source.open()) {
                Translation.say(source.failedNote)
                return
            }
            Translation.say("Çeviri açık", Phase.ON)
            var wasPaused = false
            val states = HashMap<ReadArea, ReadState>()
            // Set when the pack changes: the boxes on screen are translated again with it.
            val repack = AtomicBoolean(false)
            var lastCopy = 0L
            var lastScan = 0L
            var readTurn = 0
            var scanTurn = 0
            val startCpu = Process.getElapsedCpuTime()
            val result = LiveDecoder(log, source).run(seconds) { image, picture ->
                FrameShot.offer(image, picture)
                if (Translation.paused != wasPaused) {
                    wasPaused = Translation.paused
                    if (wasPaused) subtitle.hideAll()
                    Translation.say(if (wasPaused) "Duraklatıldı" else "Çeviri açık")
                }
                if (wasPaused) return@run
                val wanted = wantedPack()
                if (translator.pack !== wanted) {
                    translator.pack = wanted
                    // The lines on screen are read again with the new pack at once, not when the next line comes.
                    states.values.forEach { it.lastSent = "" }
                    repack.set(true)
                    if (BuildConfig.DEBUG) log("  oyun paketi: ${wanted.info.game.ifEmpty { "yok" }}, ${wanted.size} satir")
                    // The translators are thanked on screen each time their pack starts working.
                    if (!demoRun && wanted.size > 0) {
                        subtitle.show(0, "Türkçe çeviri: ${wanted.info.credit.ifEmpty { "çevirmenler" }} — emeğiniz için teşekkürler!")
                    }
                }
                val now = System.nanoTime()
                val skipIntro = live.settings.skipIntro
                val subtitleAreas = live.settings.subtitleAreas
                if (subtitleAreas.isNotEmpty() && (now - lastCopy) / 1_000_000 >= ocrIntervalMs / subtitleAreas.size &&
                    ocrBusy.compareAndSet(false, true)) {
                    lastCopy = now
                    val slot = readTurn++ % subtitleAreas.size
                    val area = subtitleAreas[slot]
                    val state = states.getOrPut(area) { ReadState() }
                    val crop = cropLuma(image, picture, area.left, area.top, area.width, area.height)
                    ocrPool.execute {
                        // A worker's error must not take the app down: it is logged and the next read goes on.
                        var released = false
                        try {
                            val begin = System.nanoTime()
                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(lumaBitmap(crop, area.width, area.height), 0)))
                                .textBlocks.map { it.text.replace('\n', ' ').trim() }
                                .filterNot { !translator.knows(it) && (ScreenText.isNoise(it) || skipIntro && ScreenText.isIntro(it)) }.joinToString(" ")
                            ocrTimes.add((System.nanoTime() - begin) / 1_000_000)
                            ocrBusy.set(false)
                            released = true
                            if (text != state.lastRead) state.firstSeen = begin
                            val confirmed = reads < 2 || text == state.lastRead
                            val readable = ScreenText.looksLikeSpeech(text) || translator.knows(text)
                            if (text.isEmpty()) {
                                state.lastSent = ""
                            } else if (ScreenText.sameLine(text, state.lastSent)) {
                                subtitle.keep(slot)
                            } else if (readable && confirmed) {
                                state.lastSent = text
                                val seen = state.firstSeen
                                translatePool.execute {
                                    try {
                                        val translating = System.nanoTime()
                                        val line = translator.translateWithSource(text)
                                        val turkish = line.text
                                        val done = System.nanoTime()
                                        translateTimes.add((done - translating) / 1_000_000)
                                        // What was on screen stays out of the release log.
                                        if (BuildConfig.DEBUG) log("  ${(done - seen) / 1_000_000} ms [${area.name}] ${line.source.key}: $text => $turkish")
                                        if (!Translation.paused && (line.source.human || translator.knows(text) ||
                                                !ScreenText.barelyChanged(text, turkish))) {
                                            subtitle.show(slot, marked(line.source, turkish))
                                            LineStats.add(line.source, turkish)
                                        }
                                    } catch (error: Exception) {
                                        if (BuildConfig.DEBUG) log("  ceviri hatasi: $error")
                                    }
                                }
                            }
                            state.lastRead = text
                        } catch (error: Exception) {
                            if (BuildConfig.DEBUG) log("  okuma hatasi: $error")
                        } finally {
                            if (!released) ocrBusy.set(false)
                        }
                    }
                }
                val boxAreas = live.settings.boxAreas
                if (boxAreas.isNotEmpty() && (now - lastScan) / 1_000_000 >= BOX_SCAN_MS && boxBusy.compareAndSet(false, true)) {
                    lastScan = now
                    val area = boxAreas[scanTurn++ % boxAreas.size]
                    val part = cropLuma(image, picture, area.left, area.top, area.width, area.height)
                    boxPool.execute {
                        try {
                            val begin = System.nanoTime()
                            val found = infoBlocks(boxRecognizer, lumaBitmap(part, area.width, area.height), area.block, translator, skipIntro)
                            boxTimes.add((System.nanoTime() - begin) / 1_000_000)
                            val boxes = tracker.update(found, area.block)
                            if (repack.getAndSet(false)) boxes.forEach { it.turkish = null }
                            val fresh = boxes.filter { it.turkish == null }
                            for (box in fresh) {
                                val line = translatePool.submit(Callable { translator.translateWithSource(box.block.text) }).get()
                                box.turkish = line.text
                                box.source = line.source
                                if (BuildConfig.DEBUG) log("  kutu ${box.block.left},${box.block.top} ${line.source.key}: ${box.block.text} => ${box.turkish}")
                            }
                            val readable = boxes.filter {
                                it.source.human || translator.knows(it.block.text) || !ScreenText.barelyChanged(it.block.text, it.turkish!!)
                            }
                            if (!Translation.paused) {
                                subtitle.showBoxes(readable.map { it.block to marked(it.source, it.turkish!!) })
                                readable.filter { it in fresh }.forEach { LineStats.add(it.source, it.turkish!!) }
                            }
                        } catch (error: Exception) {
                            if (BuildConfig.DEBUG) log("  kutu hatasi: $error")
                        } finally {
                            boxBusy.set(false)
                        }
                    }
                }
            }
            stopWorkers()
            val cpu = (Process.getElapsedCpuTime() - startCpu) / 10 / result.seconds
            log("PS5 BORU HATTI SONUC: %.1f kare/sn, video gecikmesi p95 %d ms, OCR ort %d ms p95 %d ms, kutu taramasi ort %d ms, ceviri %d adet p95 %d ms, CPU %%%d (400 uzerinden); %s".format(
                result.frames / result.seconds, percentile(result.latenciesMs, .95),
                ocrTimes.average(), percentile(ocrTimes.toList(), .95), boxTimes.average(),
                translateTimes.count, percentile(translateTimes.toList(), .95), cpu.toInt(), memoryLine()))
            log(endLine(seconds, result, source))
        } finally {
            // Workers may still be inside the OCR engine or the native translator, and closing either under them
            // crashes the app. So no new work, a wait for the running work, and an engine still in use is left open:
            // a leak until the app ends is better than a crash. Workers are never interrupted mid-call.
            val (ocrDone, boxDone, translateDone) = stopWorkers()
            subtitle.close()
            live.close()
            source.close()
            if (translateDone) translator.close() else leftOpen("ceviri motoru")
            if (ocrDone) recognizer.close() else leftOpen("altyazi okuyucu")
            if (boxDone) boxRecognizer.close() else leftOpen("kutu okuyucu")
        }
    }

    private fun leftOpen(what: String) {
        if (BuildConfig.DEBUG) log("  $what is bitmeden kapanmadi, acik birakildi")
    }

    /**
     * Stops each pool taking work and waits for it, in order (the readers hand lines to the translator); true for
     * each pool whose work all ended in time.
     */
    private fun stopPools(vararg pools: Pair<ExecutorService, Long>): List<Boolean> = pools.map { (pool, seconds) ->
        pool.shutdown()
        try {
            pool.awaitTermination(seconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(root, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("  $name kaydedildi")
    }

    /** The whole picture at quarter size, to see what the PS5 was showing. */
    private fun previewBitmap(image: Image, picture: PictureRect): Bitmap {
        val plane = image.planes[0]
        val grey = copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, picture, 0, 0, PREVIEW_W, PREVIEW_H, PREVIEW_W, PREVIEW_H)
        val pixels = IntArray(PREVIEW_W * PREVIEW_H) {
            val value = grey[it].toInt() and 0xff
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(pixels, PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
    }
}

/** What one subtitle area read last, so each area confirms and repeats on its own. */
private class ReadState {
    var lastRead = ""
    /** Written by the reader and, when the pack changes, by the frame loop. */
    @Volatile var lastSent = ""
    var firstSeen = 0L
}

/** The settings as the phone or the TV screen last saved them; the reader picks them up between frames. */
private class LiveSettings(context: android.content.Context) {
    private val prefs = SettingsStore.prefs(context)
    @Volatile var settings = SettingsStore.load(prefs)
        private set
    private val changed = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = SettingsStore.load(prefs) }

    init {
        prefs.registerOnSharedPreferenceChangeListener(changed)
    }

    fun close() = prefs.unregisterOnSharedPreferenceChangeListener(changed)
}
