package dev.turkce.olcum

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

private const val SHOT_W = 960
private const val SHOT_H = 540
private const val SHOT_QUALITY = 75
private const val SHOT_WAIT_MS = 800L

/** The latest game picture for the phone page. Made only while someone asks for it, so the stream pays nothing otherwise. */
internal object FrameShot {
    @Volatile private var wanted = false
    @Volatile private var jpeg: ByteArray? = null
    @Volatile private var madeAt = 0L
    private val lock = Object()

    /** Called with every decoded frame; does work only when the page asked for a picture. */
    fun offer(image: Image, picture: PictureRect = image.picture()) {
        if (!wanted) return
        wanted = false
        val out = ByteArrayOutputStream()
        colorPreview(image, picture).compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
        jpeg = out.toByteArray()
        synchronized(lock) {
            madeAt = System.nanoTime()
            lock.notifyAll()
        }
    }

    /** A new picture when the game is streaming, else the last one seen, kept in [file] across runs. */
    fun latest(file: File): ByteArray? {
        val asked = System.nanoTime()
        wanted = true
        synchronized(lock) {
            val end = asked + SHOT_WAIT_MS * 1_000_000
            while (madeAt < asked) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                lock.wait(left)
            }
        }
        val fresh = jpeg.takeIf { madeAt >= asked }
        if (fresh != null) {
            runCatching { file.writeBytes(fresh) }
            return fresh
        }
        return jpeg ?: file.takeIf { it.exists() }?.readBytes()
    }

    /**
     * The picture scaled to 960x540 (every second pixel of a 1080p one), BT.709 limited range as the PS5 sends it.
     * Reads the YUV 4:2:0 planes with their own row and pixel steps, so padded or interleaved frames come out right.
     */
    private fun colorPreview(image: Image, picture: PictureRect): Bitmap {
        val (y, u, v) = image.planes
        val columns = IntArray(SHOT_W) { picture.column(it, SHOT_W) }
        val pixels = IntArray(SHOT_W * SHOT_H)
        for (row in 0 until SHOT_H) {
            val frameRow = picture.row(row, SHOT_H)
            val lumaRow = frameRow * y.rowStride
            val uRow = frameRow / 2 * u.rowStride
            val vRow = frameRow / 2 * v.rowStride
            for (col in 0 until SHOT_W) {
                val frameCol = columns[col]
                val luma = (y.buffer.get(lumaRow + frameCol * y.pixelStride).toInt() and 0xff) - 16
                val cb = (u.buffer.get(uRow + frameCol / 2 * u.pixelStride).toInt() and 0xff) - 128
                val cr = (v.buffer.get(vRow + frameCol / 2 * v.pixelStride).toInt() and 0xff) - 128
                val c = 1192 * luma
                val r = (c + 1836 * cr) shr 10
                val g = (c - 218 * cb - 546 * cr) shr 10
                val b = (c + 2163 * cb) shr 10
                pixels[row * SHOT_W + col] =
                    (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(pixels, SHOT_W, SHOT_H, Bitmap.Config.ARGB_8888)
    }
}

/** Just enough HTTP/1.1 for one page and three small API calls. */
class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val bytes: ByteArray = ByteArray(0)) {
    val body: String get() = String(bytes, Charsets.UTF_8)

    companion object {
        private const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 32 * 1024

        /**
         * Null for an empty or broken request. [bodyLimit] sees the request line before any body is read; a body over
         * the limit is left unread and [tooBig] hears of it with the request line and the announced length.
         */
        fun read(
            input: InputStream,
            tooBig: (HttpRequest, Int) -> Unit = { _, _ -> },
            bodyLimit: (HttpRequest) -> Int = { MAX_BODY },
        ): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) return null
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.let { it.toLongOrNull() ?: return null } ?: 0L
            val target = parts[1]
            val bare = HttpRequest(parts[0].uppercase(), target.substringBefore('?'), query(target.substringAfter('?', "")))
            if (length < 0) return null
            if (length > bodyLimit(bare)) {
                tooBig(bare, length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                return null
            }
            val body = ByteArray(length.toInt())
            DataInputStream(input).readFully(body)
            return HttpRequest(bare.method, bare.path, bare.query, body)
        }

        private fun readHead(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val next = input.read()
                if (next < 0 || bytes.size() >= MAX_HEAD) return null
                bytes.write(next)
                matched = when {
                    next == '\r'.code && matched % 2 == 0 -> matched + 1
                    next == '\n'.code && matched % 2 == 1 -> matched + 1
                    next == '\r'.code -> 1
                    else -> 0
                }
            }
            return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }

        fun query(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
    }
}

/**
 * A fixed number of daemon threads with a short queue; work that finds both full is refused, not queued without end.
 * Idle threads end after a while, so the settings page costs nothing between visits.
 */
internal class BoundedWorkers(workers: Int, waiting: Int, name: String) {
    private val count = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        workers, workers, IDLE_S, TimeUnit.SECONDS, ArrayBlockingQueue(waiting),
        { task -> Thread(task, "$name-${count.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** False when every worker is busy and the queue is full; the caller then drops the work. */
    fun offer(task: () -> Unit): Boolean = try {
        pool.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private companion object {
        const val IDLE_S = 30L
    }
}

/**
 * The phone settings page, served on the home network by the app's service ([OlcumService]) for as long as it runs.
 * The address carries a random key, shown only in the TV's QR code, and the key stays the same while the service
 * lives, so the phone's page keeps working after the game takes the screen. With the translation off and no request
 * for four hours, the server stops and lets the service go; the TV screen starts both again with a new key.
 */
internal object AyarSunucusu {
    const val PORT = 8095
    private const val PORT_TRIES = 10
    private const val IDLE_MS = 4 * 60 * 60_000L
    private const val CHECK_MS = 30_000L
    /** An upload over the limit is read and thrown away up to this much, so the phone sees the answer, not a cut line. */
    private const val MAX_DRAIN = 256L * 1024 * 1024
    private const val DRAIN_MS = 60_000L
    private const val TOO_BIG = "Dosya çok büyük: en fazla 30 MB olabilir. Büyük yamalarda yalnız Türkçe dosyayı ya da .zip'ini yükle."
    private const val GONE = "Bağlantı kapandı. TV'de Tv Türkçe Altyazı'yı aç, QR'ı yeniden okut."
    private const val SOCKET_TIMEOUT_MS = 10_000
    private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val TOKEN_LENGTH = 12
    private const val WORKERS = 8
    private const val WAITING = 8

    /** The requests served at once; the phone page needs a few, so more than this is someone flooding the port. */
    private val workers = BoundedWorkers(WORKERS, WAITING, "ayar-istek")

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token = ""
    @Volatile private var port = PORT
    @Volatile private var lastUse = 0L

    /** The page address for the QR code, or null when the TV has no home-network address. */
    @Synchronized
    fun start(context: Context): String? {
        val address = localAddress() ?: return null
        lastUse = System.currentTimeMillis()
        if (server == null) {
            val app = context.applicationContext
            token = newToken()
            val socket = bindFree()
            server = socket
            port = socket.localPort
            thread(isDaemon = true, name = "ayar-sunucu") { accept(socket, app) }
            thread(isDaemon = true, name = "ayar-bekci") { watch(socket, app) }
        }
        return "http://$address:$port/?t=$token"
    }

    /** Closes the server at once (the service ended); the next [start] opens it again with a new key. */
    @Synchronized
    fun stopNow() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    /** The usual port, or one of the next few when another app on the TV already holds it. */
    private fun bindFree(): ServerSocket {
        var last: java.io.IOException? = null
        for (candidate in PORT until PORT + PORT_TRIES) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                return socket
            } catch (e: java.io.IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw last ?: java.io.IOException("no free port")
    }

    @Synchronized
    private fun stop(socket: ServerSocket) {
        runCatching { socket.close() }
        if (server === socket) server = null
    }

    private fun watch(socket: ServerSocket, app: Context) {
        while (!socket.isClosed) {
            Thread.sleep(CHECK_MS)
            if (System.currentTimeMillis() - lastUse <= IDLE_MS) continue
            // While the game is translated the phone must still be able to stop it, so nothing ends then. The switch's
            // own lock keeps a start from slipping in between the look and the stop.
            synchronized(Translation) {
                if (!Translation.running.get() && System.currentTimeMillis() - lastUse > IDLE_MS) {
                    stop(socket)
                    OlcumService.stop(app)
                }
            }
        }
    }

    private fun accept(socket: ServerSocket, context: Context) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            // A connection that never sends gives up its worker after the timeout, even before its turn comes.
            runCatching { client.soTimeout = SOCKET_TIMEOUT_MS }
            // A failed request must never take the game overlay down with it; when every worker is busy and the
            // queue is full, the connection is closed at once instead of starting one more thread.
            val taken = workers.offer { runCatching { client.use { serve(it, context) } } }
            if (!taken) runCatching { client.close() }
        }
    }

    private fun serve(client: Socket, context: Context) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val out = client.getOutputStream()
        val input = client.getInputStream()
        // Only a request carrying the key may send a whole translation file. A file over the limit gets a plain answer.
        var oversize = 0
        val request = runCatching {
            HttpRequest.read(input, { bare, length -> if (isUpload(bare)) oversize = length }) { bare ->
                if (isUpload(bare)) PackStore.MAX_BYTES else HttpRequest.MAX_BODY
            }
        }.getOrNull()
        if (request == null) {
            if (oversize == 0) return respond(out, 400, "text/plain", "Bozuk istek")
            respond(out, 413, "application/json; charset=utf-8", JSONObject().put("error", TOO_BIG).toString())
            return drain(input, oversize)
        }
        // An old key (the service ended since the QR was read) says what to do, not just "forbidden".
        if (!allowed(request)) return respond(out, 403, "text/plain; charset=utf-8", GONE)
        lastUse = System.currentTimeMillis()
        val prefs = SettingsStore.prefs(context)
        when {
            request.method == "GET" && request.path == "/" ->
                respond(out, 200, "text/html; charset=utf-8", context.assets.open("ayar.html").use { it.readBytes() })
            request.method == "GET" && request.path == "/api/ayar" ->
                respond(out, 200, "application/json; charset=utf-8", SettingsStore.load(prefs).toJson())
            request.method == "POST" && request.path == "/api/ayar" -> {
                val settings = runCatching { Settings.fromJson(request.body) }.getOrNull()
                    ?: return respond(out, 400, "text/plain; charset=utf-8", "Ayar okunamadı")
                respond(out, 200, "application/json; charset=utf-8", SettingsStore.save(prefs, settings).toJson())
            }
            request.method == "GET" && request.path == "/api/kare.jpg" -> {
                val picture = FrameShot.latest(File(context.filesDir, "son_kare.jpg"))
                if (picture == null) respond(out, 404, "text/plain", "Goruntu yok") else respond(out, 200, "image/jpeg", picture)
            }
            request.method == "GET" && request.path == "/hakkinda" ->
                respond(out, 200, "text/html; charset=utf-8", About.page(BuildConfig.VERSION_NAME, About.groups(context), token))
            request.method == "GET" && request.path == "/lisanslar" ->
                respond(out, 200, "text/plain; charset=utf-8", About.fullText(context).toByteArray(Charsets.UTF_8))
            request.path.startsWith("/api/paket") -> packs(request, out, context, prefs)
            request.path == "/api/durum" || request.path == "/api/ceviri" || request.path.startsWith("/api/ps5/") ->
                controls(request, out, context)
            else -> respond(out, 404, "text/plain", "Yok")
        }
    }

    private fun allowed(request: HttpRequest) = MessageDigest.isEqual((request.query["t"] ?: "").toByteArray(), token.toByteArray())

    private fun isUpload(request: HttpRequest) = allowed(request) && request.path == "/api/paket/yukle"

    /** Reads and drops what the phone is still sending, within limits, so its browser shows the answer already sent. */
    private fun drain(input: InputStream, length: Int) {
        val buffer = ByteArray(64 * 1024)
        var left = minOf(length.toLong(), MAX_DRAIN)
        val end = System.currentTimeMillis() + DRAIN_MS
        runCatching {
            while (left > 0 && System.currentTimeMillis() < end) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
        }
    }

    /**
     * The pack list with what the TV has in use: the active pack as read into memory, whether its lines get a ✓, and,
     * after an upload or a download, what was read from the file.
     */
    private fun packJson(context: Context, prefs: android.content.SharedPreferences, result: JSONObject? = null): String {
        ActivePack.reload(context)
        return JSONObject(PackStore.listJson(context, prefs))
            .put("etkin", ActivePack.toJson())
            .put("isaret", PackMark.on(context))
            .apply { if (result != null) put("sonuc", result) }
            .toString()
    }

    /** Puts a new pack in use and tells the TV and the phone what came in. */
    private fun added(context: Context, prefs: android.content.SharedPreferences, info: PackInfo, file: String): String {
        val pack = ActivePack.choose(context, prefs, info.id)
        PackToast.show(context, PackToast.text("yüklendi", pack.info, pack.size))
        val result = JSONObject().put("id", info.id).put("oyun", info.game).put("satir", pack.size).put("dosya", file)
            .put("cevirmen", info.credit)
        return packJson(context, prefs, result)
    }

    /** Game packs: search, look inside a repository, download, upload from the phone, choose, delete, the ✓ mark. */
    private fun packs(request: HttpRequest, out: OutputStream, context: Context, prefs: android.content.SharedPreferences) {
        val json = "application/json; charset=utf-8"
        try {
            val body = if (request.method == "POST" && request.path != "/api/paket/yukle" && request.body.isNotBlank()) JSONObject(request.body) else JSONObject()
            when ("${request.method} ${request.path}") {
                "GET /api/paket" -> respond(out, 200, json, packJson(context, prefs))
                "GET /api/paket/ara" -> {
                    val game = request.query["q"] ?: ""
                    val results = runCatching { YamaArama.search(game) }
                    val reply = JSONObject().put("links", YamaArama.links(game))
                        .put("results", JSONArray(results.getOrDefault(emptyList()).map { it.toJson() }))
                    results.exceptionOrNull()?.let { reply.put("error", SetupGuide.friendlyError(request.path, it)) }
                    respond(out, 200, json, reply.toString())
                }
                "GET /api/paket/dosyalar" -> {
                    val repo = YamaArama.repo(request.query["repo"] ?: "")
                    respond(out, 200, json, JSONObject().put("repo", repo.toJson()).put("files", YamaArama.files(repo)).toString())
                }
                "POST /api/paket/indir" -> {
                    val repo = YamaArama.repo(body.optString("repo"))
                    val path = body.optString("path")
                    val bytes = YamaArama.download(repo, path)
                    val file = path.substringAfterLast('/')
                    val info = PackStore.add(context, file, bytes, body.optString("game"),
                        repo.repo.substringBefore('/'), repo.url, repo.license, YamaArama.funding(repo))
                    respond(out, 200, json, added(context, prefs, info, file))
                }
                "POST /api/paket/yukle" -> {
                    val file = request.query["dosya"] ?: ""
                    val info = PackStore.add(context, file, request.bytes, request.query["oyun"] ?: "",
                        request.query["cevirmen"] ?: "", request.query["kaynak"] ?: "", "", request.query["bagis"] ?: "")
                    respond(out, 200, json, added(context, prefs, info, file))
                }
                "POST /api/paket/sec" -> {
                    val pack = ActivePack.choose(context, prefs, body.optString("id"))
                    PackToast.show(context, if (pack.size == 0) {
                        "Oyun paketi kapatıldı: oyun metni makine çevirisiyle çevrilecek"
                    } else {
                        PackToast.text("seçildi", pack.info, pack.size)
                    })
                    respond(out, 200, json, packJson(context, prefs))
                }
                "POST /api/paket/sil" -> {
                    val id = body.optString("id")
                    val gone = PackStore.list(context).firstOrNull { it.id == id }
                    val wasActive = ActivePack.pack.info.id == id && id.isNotEmpty()
                    PackStore.remove(context, prefs, id)
                    if (gone != null) {
                        PackToast.show(context, "Oyun paketi silindi: ${gone.game}" + if (wasActive) " · artık makine çevirisi" else "")
                    }
                    respond(out, 200, json, packJson(context, prefs))
                }
                "POST /api/paket/isaret" -> {
                    PackMark.set(context, body.optBoolean("acik", true))
                    respond(out, 200, json, packJson(context, prefs))
                }
                else -> respond(out, 404, "text/plain", "Yok")
            }
        } catch (e: Exception) {
            respond(out, 400, json, JSONObject().put("error", SetupGuide.friendlyError(request.path, e)).toString())
        }
    }

    /** Translation on and off, and the PS5 pairing steps. Answers with the state, never with a key. */
    private fun controls(request: HttpRequest, out: OutputStream, context: Context) {
        val json = "application/json; charset=utf-8"
        // Every answer carries the setup steps too, so the page's ticks follow each button at once and survive a reload.
        // The run's line sources and last lines live in memory only and go to this page only, never to a file.
        fun stateJson() = Translation.toJson(context).let {
            it.put("girisAdresi", Ps5Setup.loginUrl).put("kurulum", SetupGuide.toJson(context, it.getJSONObject("ps5"), localAddress()))
                .put("oturum", LineStats.toJson())
                .put("paket", JSONObject().put("etkin", ActivePack.toJson()).put("isaret", PackMark.on(context)))
        }
        fun state() = stateJson().toString()
        try {
            val body = if (request.method == "POST" && request.body.isNotBlank()) JSONObject(request.body) else JSONObject()
            when ("${request.method} ${request.path}") {
                "GET /api/durum" -> respond(out, 200, json, state())
                "POST /api/ceviri" -> {
                    when (body.optString("islem")) {
                        // Debug builds only: the demo clip from the phone, to try the switch without a PS5.
                        "baslat" -> if (BuildConfig.DEBUG && body.optBoolean("deneme")) {
                            Translation.start(context, demo = true)
                        } else {
                            require(Ps5Setup.status(context).getBoolean("eslesti")) { "Önce PS5 ile eşleştir (5. adım)." }
                            Translation.start(context)
                        }
                        "durdur" -> Translation.stop()
                        "duraklat" -> if (Translation.running.get()) Translation.paused = true
                        "devam" -> Translation.paused = false
                        else -> throw IllegalArgumentException("Bilinmeyen işlem.")
                    }
                    respond(out, 200, json, state())
                }
                "GET /api/ps5/bul" -> {
                    val found = Ps5Setup.discover()
                    // One PS5 at home is the one: step 4 is done without a tap. Several wait for the phone to pick.
                    found.singleOrNull()?.let { SetupGuide.choose(context, it.address) }
                    respond(out, 200, json, stateJson().put("bulunan", JSONArray(found.map { it.toJson() })).toString())
                }
                "POST /api/ps5/kurulum" -> {
                    if (body.optBoolean("bastan")) {
                        // Start over: the account number, the PS5 keys and every tick go; the PS5 side stays as it is.
                        // Not under a running PS5 session, which still uses those keys; the demo needs none.
                        check(!Translation.running.get() || Translation.demo) { "Önce çeviriyi kapat, sonra kurulumu baştan başlat." }
                        Ps5Setup.forget(context)
                        SetupGuide.reset(context)
                    }
                    if (body.has("yeniHesap")) SetupGuide.markNewAccount(context, body.getBoolean("yeniHesap"))
                    if (body.has("uzaktanOynama")) SetupGuide.markRemotePlay(context, body.getBoolean("uzaktanOynama"))
                    if (body.has("adres")) SetupGuide.choose(context, body.getString("adres"))
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/psn" -> {
                    val code = Ps5Setup.codeFrom(body.optString("adres"))
                        ?: throw IllegalArgumentException("Bu adreste giriş kodu yok. Sony sayfasında giriş yaptıktan sonra açılan sayfanın adresini kopyala.")
                    Ps5Setup.saveAccount(context, Ps5Setup.fetchAccount(code))
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/hesap" -> {
                    val account = Ps5Setup.accountFromBase64(body.optString("kimlik"))
                        ?: throw IllegalArgumentException("Hesap kimliği 12 karakterlik base64 olmalı (ör. AbCdEfGhIjk=).")
                    Ps5Setup.saveAccount(context, account)
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/eslestir" -> {
                    val host = SetupGuide.cleanAddress(body.optString("ip"))
                    SetupGuide.addressProblem(host)?.let { throw IllegalArgumentException(it) }
                    try {
                        Ps5Setup.pair(context, host, body.optString("pin"))
                    } catch (refused: PairRefused) {
                        // One more look at the network tells a wrong code from a sleeping, missing or moved PS5.
                        val found = runCatching { Ps5Setup.discover() }.getOrDefault(emptyList())
                        throw IllegalStateException(SetupGuide.pairRefused(host, found), refused)
                    }
                    SetupGuide.choose(context, host)
                    // A pairing proves steps 1 and 2 (it is done on the new account with Remote Play on): they stay ticked.
                    SetupGuide.markNewAccount(context, true)
                    SetupGuide.markRemotePlay(context, true)
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/uyandir" -> {
                    check(Ps5Setup.status(context).getBoolean("eslesti")) { "Önce PS5'i eşleştir (5. adım)." }
                    check(Ps5Setup.wake(context)) { "PS5'e uyandırma gönderilemedi. Kumandadaki PS tuşuna basarak PS5'i elle aç." }
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/unut" -> {
                    Ps5Setup.forget(context)
                    respond(out, 200, json, state())
                }
                else -> respond(out, 404, "text/plain", "Yok")
            }
        } catch (e: Exception) {
            respond(out, 400, json, JSONObject().put("error", SetupGuide.friendlyError(request.path, e)).toString())
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) = respond(out, code, type, body.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 413 to "Payload Too Large")[code] ?: "Error"
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return String(CharArray(TOKEN_LENGTH) { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] })
    }

    /** The TV's address on the home network, wired or Wi-Fi. */
    private fun localAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}
