package dev.turkce.olcum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

private const val CHANNEL = "ceviri"
private const val NOTIFICATION_ID = 1
private const val PRODUCT_STAGE = "ps5boru"

/**
 * The translation service: holds the PS5 Remote Play link and draws the Turkish subtitles while the game's HDMI
 * input or the home screen is in front. The app starts it with [Translation.start].
 *
 * Debug builds only (the service is exported there) also take the measurement stages over adb:
 * am start-foreground-service -n dev.turkcetv.tv/dev.turkce.olcum.OlcumService --es stage decode|ocr|mt|pipeline --ei seconds 120
 * PS5 stages: ps5kayit (--es host, --es hesap, --es pin), ps5uyandir, ps5akis, ps5boru (--ei okuma 2 waits for two equal reads;
 * --ez deneme true runs it on the demo clip instead of the PS5)
 * Without the PS5: kututest (--es resim file.png), the info-box path on one 1920x1080 picture
 */
class OlcumService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A release build runs only the product flow, whatever the intent says.
        val stage = if (BuildConfig.DEBUG) intent?.getStringExtra("stage") ?: "decode" else PRODUCT_STAGE
        // Demo mode: the same flow on the clip inside the app instead of the PS5 (the service is not exported in release).
        val demo = stage == PRODUCT_STAGE && intent?.getBooleanExtra("deneme", false) == true
        if (!goForeground(stage, demo)) {
            if (stage == PRODUCT_STAGE) {
                Translation.note = "Başlatılamadı"
                Translation.running.set(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val debug = BuildConfig.DEBUG
        val seconds = if (debug) intent?.getIntExtra("seconds", 120) ?: 120 else UNTIL_STOPPED
        val interval = if (debug) intent?.getIntExtra("ocrms", 120) ?: 120 else 120
        val reads = if (debug) intent?.getIntExtra("okuma", 1) ?: 1 else 1
        val threads = if (debug) intent?.getIntExtra("threads", 2) ?: 2 else 2
        val fromApp = !debug || intent?.getBooleanExtra("uygulama", false) == true
        Thread({
            val root = workDir(this)
            val run = Measurements(root)
            val ps5 = Ps5Measurements(root, filesDir, run::log, this@OlcumService)
            try {
                when (stage) {
                    "hazirla" -> run.log("hazir $root")
                    "decode" -> run.decodeStage()
                    "ocr" -> run.ocrStage()
                    "mt" -> run.translationStage(threads)
                    "pipeline" -> run.pipelineStage(seconds, interval, threads)
                    "ps5kayit" -> ps5.register(
                        intent!!.getStringExtra("host")!!, intent.getStringExtra("hesap")!!, intent.getStringExtra("pin")!!)
                    "ps5uyandir" -> ps5.wake()
                    "ps5akis" -> ps5.streamStage(seconds)
                    PRODUCT_STAGE -> translate(fromApp, demo, run::log) {
                        prepareModel()
                        ps5.pipelineStage(seconds, interval, reads, if (demo) DemoSource(this, run::log) else ps5.ps5Source())
                    }
                    "kututest" -> ps5.boxStage(intent!!.getStringExtra("resim")!!)
                    "argotest" -> ps5.slangStage(intent!!.getStringExtra("dosya")!!)
                }
            } catch (error: Throwable) {
                run.log("HATA $stage: ${Log.getStackTraceString(error).take(1500)}")
            }
            run.log("BITTI $stage")
            // Only this start's work ended; a newer start keeps the service.
            stopSelf(startId)
        }, "olcum").start()
        return START_NOT_STICKY
    }

    /**
     * The lasting notification Android asks of a foreground service, with the PS5 link as its type (Android 10+
     * takes the type here; Android 14+ requires it). False when Android refused, e.g. started from the background.
     */
    private fun goForeground(stage: String, demo: Boolean): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tv Türkçe Altyazı çeviri", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AyarActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = when {
            demo -> "Tv Türkçe Altyazı deneme modu açık"
            stage == PRODUCT_STAGE -> "Tv Türkçe Altyazı çeviri açık"
            else -> "Tv Türkçe Altyazı ölçüm: $stage"
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: RuntimeException) {
            Log.w("TurkceTv", "on plan servisi baslatilamadi: $error")
            false
        }
    }

    /**
     * The model comes out of the APK on first use, here on the service's own thread rather than on the screen's or
     * the phone page's; the TV and the phone say so meanwhile.
     */
    private fun prepareModel() {
        if (FirstRun.modelReady(this)) return
        Translation.note = "Çeviri modeli hazırlanıyor"
        try {
            FirstRun.installModel(this)
        } catch (error: Exception) {
            Translation.note = MODEL_FAILED
            throw IllegalStateException(MODEL_FAILED, error)
        }
    }

    /**
     * One translation at a time, whether the app or adb started it, on the PS5 or in demo mode; the switch shows it
     * stopped once it ends.
     */
    private fun translate(fromApp: Boolean, demo: Boolean, log: (String) -> Unit, work: () -> Unit) {
        if (!fromApp) {
            if (!Translation.running.compareAndSet(false, true)) {
                log("ceviri zaten calisiyor")
                return
            }
            Translation.stopAsked = false
        }
        Translation.demo = demo
        var failure: String? = null
        try {
            work()
        } catch (error: IllegalStateException) {
            failure = error.message
            throw error
        } finally {
            val stopped = Translation.stopAsked
            Translation.paused = false
            Translation.stopAsked = false
            Translation.note = when {
                stopped -> "Kapalı"
                failure == NO_DECODER -> NO_DECODER
                failure != null && failure == busyMessage(ChiakiGate.PAIRING) -> failure
                Translation.note.startsWith("PlayStation'a bağlanamadı") -> Translation.note
                Translation.note == DEMO_FAILED || Translation.note == MODEL_FAILED -> Translation.note
                else -> "Kapandı"
            }
            Translation.demo = false
            Translation.running.set(false)
        }
    }
}

internal const val HARDWARE = "OMX.MTK.VIDEO.DECODER.AVC"
private const val SOFTWARE = "c2.android.avc.decoder"
internal const val FRAME_W = 1920
internal const val FRAME_H = 1080
// The app's default subtitle region (left 0.1, top 0.72, right 0.9, bottom 1.0).
internal const val CROP_X = 192
internal const val CROP_Y = 777
internal const val CROP_W = 1536
internal const val CROP_H = 302

internal fun percentile(values: List<Long>, p: Double): Long =
    if (values.isEmpty()) -1 else values.sorted()[((values.size - 1) * p).toInt()]

private fun wordMatch(read: String, truth: String): Int {
    fun words(s: String) = s.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
    val seen = words(read).toMutableList()
    val wanted = words(truth)
    return wanted.count { seen.remove(it) } * 100 / wanted.size
}

internal fun lumaBitmap(luma: ByteArray, width: Int = CROP_W, height: Int = CROP_H): Bitmap {
    val pixels = IntArray(luma.size) {
        val v = luma[it].toInt() and 0xff
        (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

internal fun memoryLine(): String {
    val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val available = File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable") }
        .filter(Char::isDigit).toLong() / 1024
    return "bellek uygulama ${info.totalPss / 1024} MB, TV'de bos $available MB"
}

internal class DecodeResult(val frames: Int, val seconds: Double, val latenciesMs: List<Long>, val nullImages: Int)

/** Plays the clip in a loop. `paced` feeds frames at 30 per second, the way a live stream arrives. */
private class ClipDecoder(
    private val clip: File,
    private val codecName: String,
    private val surfaceMode: Boolean,
    private val paced: Boolean,
    private val log: (String) -> Unit,
) {
    fun run(seconds: Int, onImage: (Image, Long) -> Unit): DecodeResult {
        val extractor = MediaExtractor().apply { setDataSource(clip.path); selectTrack(0) }
        val format = extractor.getTrackFormat(0)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (!surfaceMode) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var thread: HandlerThread? = null
        var reader: ImageReader? = null
        var readerFrames = 0
        if (surfaceMode) {
            thread = HandlerThread("reader").apply { start() }
            reader = ImageReader.newInstance(FRAME_W, FRAME_H, ImageFormat.YUV_420_888, 5)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use {
                    readerFrames++
                    onImage(it, it.timestamp / 1000)
                }
            }, Handler(thread.looper))
            codec.configure(format, reader.surface, null, 0)
        } else {
            codec.configure(format, null, null, 0)
        }
        codec.start()

        val info = MediaCodec.BufferInfo()
        val fed = HashMap<Long, Long>()
        val latencies = ArrayList<Long>()
        var frames = 0
        var nullImages = 0
        var offsetUs = 0L
        var lastPts = 0L
        val start = System.nanoTime()
        val end = start + seconds * 1_000_000_000L
        while (System.nanoTime() < end) {
            val pts = extractor.sampleTime
            if (pts < 0) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                offsetUs = lastPts + 33_333
                continue
            }
            val due = start + (pts + offsetUs) * 1000
            if (!paced || System.nanoTime() >= due) {
                val input = codec.dequeueInputBuffer(1_000)
                if (input >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                    lastPts = pts + offsetUs
                    fed[lastPts] = System.nanoTime()
                    codec.queueInputBuffer(input, 0, size, lastPts, 0)
                    extractor.advance()
                }
            }
            while (true) {
                val output = codec.dequeueOutputBuffer(info, 1_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("  cikis bicimi: ${codec.outputFormat}")
                    continue
                }
                if (output < 0) break
                frames++
                fed.remove(info.presentationTimeUs)?.let { latencies.add((System.nanoTime() - it) / 1_000_000) }
                if (!surfaceMode) {
                    val image = codec.getOutputImage(output)
                    if (image == null) nullImages++ else image.use { onImage(it, info.presentationTimeUs) }
                }
                codec.releaseOutputBuffer(output, surfaceMode)
            }
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        codec.stop()
        codec.release()
        extractor.release()
        reader?.close()
        thread?.quitSafely()
        return DecodeResult(if (surfaceMode) readerFrames else frames, elapsed, latencies, nullImages)
    }
}

private class Measurements(private val root: File) {
    private val results = File(root, "sonuc.txt")
    private val clip = File(root, "klip.mp4")

    /**
     * Debug builds keep every line in logcat and in sonuc.txt. A release build writes nothing to disk and only the
     * state lines to logcat; the lines that carry game text are logged only in debug builds by their callers.
     */
    fun log(line: String) {
        Log.i("Olcum", line)
        if (BuildConfig.DEBUG) results.appendText(line + "\n")
    }

    // MARK: Video

    fun decodeStage() {
        val cases = listOf(
            Triple(HARDWARE, false, true), Triple(HARDWARE, true, true),
            Triple(HARDWARE, false, false), Triple(SOFTWARE, false, true),
        )
        for ((name, surface, paced) in cases) {
            log("VIDEO $name ${if (surface) "surface" else "buffer"} ${if (paced) "30fps canli" else "en hizli"}")
            try {
                var sample: ByteArray? = null
                var count = 0
                val cpu = Process.getElapsedCpuTime()
                val result = ClipDecoder(clip, name, surface, paced, ::log).run(15) { image, _ ->
                    if (++count == 60) {
                        log("  kare ${image.width}x${image.height} bicim ${image.format} " +
                            "stride ${image.planes[0].rowStride} adim ${image.planes[0].pixelStride}")
                        sample = cropLuma(image)
                    }
                }
                val spent = Process.getElapsedCpuTime() - cpu
                log("  %.1f kare/sn, gecikme p50 %d ms p95 %d ms, bos kare %d, CPU %d%% (400 uzerinden)".format(
                    result.frames / result.seconds, percentile(result.latenciesMs, .5),
                    percentile(result.latenciesMs, .95), result.nullImages, spent / 10 / result.seconds.toInt()))
                sample?.let { saveSample(it, "kare_${name.takeLast(7)}_${surface}_$paced.png") }
                    ?: log("  ORNEK KARE ALINAMADI")
            } catch (error: Throwable) {
                log("  BASARISIZ: $error")
            }
        }
    }

    private fun saveSample(luma: ByteArray, name: String) {
        val mean = luma.sumOf { it.toInt() and 0xff } / luma.size.toDouble()
        val variance = luma.sumOf { val d = (it.toInt() and 0xff) - mean; d * d } / luma.size
        log("  ornek parlaklik %.1f, sapma %.1f".format(mean, sqrt(variance)))
        File(root, name).outputStream().use { lumaBitmap(luma).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // MARK: OCR

    fun ocrStage() {
        val crops = arrayOfNulls<ByteArray>(SENTENCES.size)
        ClipDecoder(clip, HARDWARE, false, false, ::log).run(30) { image, pts ->
            val line = ((pts / 1_000_000) / SECONDS_PER_LINE % SENTENCES.size).toInt()
            if (crops[line] == null && (pts / 1_000_000) % SECONDS_PER_LINE == 2L) crops[line] = cropLuma(image)
        }
        log("OCR ${crops.count { it != null }} kare toplandi")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val times = ArrayList<Long>()
        var matched = 0
        var total = 0
        for (round in 0 until 3) for ((index, crop) in crops.withIndex()) {
            val bitmap = lumaBitmap(crop ?: continue)
            val started = System.nanoTime()
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.replace('\n', ' ')
            val ms = (System.nanoTime() - started) / 1_000_000
            if (round == 0 && index == 0) log("  ilk okuma (isinma) $ms ms") else times.add(ms)
            if (round == 1) {
                val score = wordMatch(text, SENTENCES[index])
                matched += score
                total++
                log("  $ms ms, %$score: $text")
            }
        }
        log("OCR ortalama ${times.average().toLong()} ms, p95 ${percentile(times, .95)} ms, " +
            "kelime dogrulugu %${if (total > 0) matched / total else -1}; ${memoryLine()}")
        recognizer.close()
    }

    // MARK: Translation

    /** The tiny Bergamot model the app installs to `kucuk/`; it picks its own threads. */
    private fun openTranslator(): LineTranslator = BergamotTranslator(File(root, "kucuk"))

    fun translationStage(threads: Int) {
        val started = System.nanoTime()
        openTranslator().use { translator ->
            log("CEVIRI model yuklendi ${(System.nanoTime() - started) / 1_000_000} ms, $threads is parcacigi; ${memoryLine()}")
            val times = ArrayList<Long>()
            for (round in 0 until 2) for (sentence in SENTENCES) {
                val begin = System.nanoTime()
                val turkish = translator.translate(sentence)
                val ms = (System.nanoTime() - begin) / 1_000_000
                times.add(ms)
                if (round == 0) log("  $ms ms: $sentence => $turkish")
            }
            log("CEVIRI ortalama ${times.average().toLong()} ms, p95 ${percentile(times, .95)} ms; ${memoryLine()}")
        }
    }

    // MARK: Everything at once

    /** Live 30 fps video + OCR + translation for `seconds`, measured from the moment a subtitle appears. */
    fun pipelineStage(seconds: Int, ocrIntervalMs: Int, threads: Int) {
        log("BORU HATTI $seconds sn, OCR her $ocrIntervalMs ms, ceviri $threads is parcacigi")
        // threads = 0 leaves translation out: how fast the TV alone can read a line.
        val translator = if (threads > 0) openTranslator() else null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val ocrPool = Executors.newSingleThreadExecutor()
        val translatePool = Executors.newSingleThreadExecutor()
        val ocrBusy = AtomicBoolean(false)
        val ocrTimes = java.util.Collections.synchronizedList(ArrayList<Long>())
        val totals = java.util.Collections.synchronizedList(ArrayList<Long>())
        val scores = java.util.Collections.synchronizedList(ArrayList<Int>())
        val pending = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)
        val translating = AtomicBoolean(false)
        val dropped = java.util.concurrent.atomic.AtomicInteger(0)
        var lastRead = ""
        var lastSent = ""
        var lastCopy = 0L
        var lastReport = System.nanoTime()
        val startCpu = Process.getElapsedCpuTime()
        val start = System.nanoTime()
        val result = ClipDecoder(clip, HARDWARE, false, true, ::log).run(seconds) { image, pts ->
            val now = System.nanoTime()
            if ((now - lastCopy) / 1_000_000 >= ocrIntervalMs && ocrBusy.compareAndSet(false, true)) {
                lastCopy = now
                val crop = cropLuma(image)
                ocrPool.execute {
                    val begin = System.nanoTime()
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(lumaBitmap(crop), 0)))
                        .text.replace('\n', ' ').trim()
                    ocrTimes.add((System.nanoTime() - begin) / 1_000_000)
                    ocrBusy.set(false)
                    // Two equal reads confirm a line, as on the phone.
                    if (text.length > 3 && text == lastRead && text != lastSent) {
                        lastSent = text
                        val line = ((pts / 1_000_000) / SECONDS_PER_LINE % SENTENCES.size).toInt()
                        val shownAt = start + (pts / (SECONDS_PER_LINE * 1_000_000L)) * SECONDS_PER_LINE * 1_000_000_000L
                        scores.add(wordMatch(text, SENTENCES[line]))
                        // The game has moved on, so a newer line replaces one still waiting, as on the phone.
                        if (pending.getAndSet(text to shownAt) != null) dropped.incrementAndGet()
                        if (translating.compareAndSet(false, true)) translatePool.execute {
                            while (true) {
                                val (line2, shown) = pending.getAndSet(null) ?: break
                                val turkish = translator?.translate(line2) ?: line2
                                val total = (System.nanoTime() - shown) / 1_000_000
                                totals.add(total)
                                log("  altyazi $total ms sonra hazir: $turkish")
                            }
                            translating.set(false)
                        }
                    }
                    lastRead = text
                }
            }
            if ((now - lastReport) / 1_000_000_000 >= 30) {
                lastReport = now
                val cpu = (Process.getElapsedCpuTime() - startCpu) / 10 / ((now - start) / 1_000_000_000)
                log("  ${(now - start) / 1_000_000_000} sn: CPU %$cpu (400 uzerinden), ${memoryLine()}")
            }
        }
        ocrPool.shutdown()
        ocrPool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        translatePool.shutdown()
        translatePool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        val cpu = (Process.getElapsedCpuTime() - startCpu) / 10 / seconds
        log("BORU HATTI SONUC: %.1f kare/sn, video gecikmesi p95 %d ms, OCR ort %d ms p95 %d ms, altyazi %d adet, atlanan %d".format(
            result.frames / result.seconds, percentile(result.latenciesMs, .95),
            ocrTimes.average().toLong(), percentile(ocrTimes.toList(), .95), totals.size, dropped.get()))
        log("  yazi cikana dek toplam: p50 ${percentile(totals.toList(), .5)} ms, p95 ${percentile(totals.toList(), .95)} ms, " +
            "kelime dogrulugu %${if (scores.isEmpty()) -1 else scores.sum() / scores.size}, CPU %$cpu (400 uzerinden); ${memoryLine()}")
        translator?.close()
        recognizer.close()
    }
}
