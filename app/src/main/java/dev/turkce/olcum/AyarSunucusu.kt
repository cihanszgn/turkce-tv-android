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

        /** Null for an empty or broken request. [bodyLimit] sees the request line before any body is read. */
        fun read(input: InputStream, bodyLimit: (HttpRequest) -> Int = { MAX_BODY }): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) return null
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val target = parts[1]
            val bare = HttpRequest(parts[0].uppercase(), target.substringBefore('?'), query(target.substringAfter('?', "")))
            if (length !in 0..bodyLimit(bare)) return null
            val body = ByteArray(length)
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
 * The phone settings page, served on the home network while it is in use. The address carries a random key,
 * shown only in the TV's QR code; the server stops after a quarter hour without requests.
 */
internal object AyarSunucusu {
    const val PORT = 8095
    private const val PORT_TRIES = 10
    private const val IDLE_MS = 15 * 60_000L
    private const val CHECK_MS = 30_000L
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
            thread(isDaemon = true, name = "ayar-bekci") { watch(socket) }
        }
        return "http://$address:$port/?t=$token"
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

    private fun watch(socket: ServerSocket) {
        while (!socket.isClosed) {
            Thread.sleep(CHECK_MS)
            // While the game is translated the phone must still be able to stop it, so the page stays reachable.
            if (System.currentTimeMillis() - lastUse > IDLE_MS && !Translation.running.get()) stop(socket)
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
        // Only a request carrying the key may send a whole translation file.
        val request = runCatching {
            HttpRequest.read(client.getInputStream()) { bare ->
                if (allowed(bare) && bare.path == "/api/paket/yukle") PackStore.MAX_BYTES else HttpRequest.MAX_BODY
            }
        }.getOrNull() ?: return respond(out, 400, "text/plain", "Bozuk istek")
        if (!allowed(request)) {
            return respond(out, 403, "text/plain; charset=utf-8", "Bağlantı süresi doldu. TV'deki QR kodu yeniden okut.")
        }
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

    /** Game packs: search, look inside a repository, download, upload from the phone, choose, delete. */
    private fun packs(request: HttpRequest, out: OutputStream, context: Context, prefs: android.content.SharedPreferences) {
        val json = "application/json; charset=utf-8"
        try {
            val body = if (request.method == "POST" && request.path != "/api/paket/yukle" && request.body.isNotBlank()) JSONObject(request.body) else JSONObject()
            when ("${request.method} ${request.path}") {
                "GET /api/paket" -> respond(out, 200, json, PackStore.listJson(context, prefs))
                "GET /api/paket/ara" -> {
                    val game = request.query["q"] ?: ""
                    val results = runCatching { YamaArama.search(game) }
                    val reply = JSONObject().put("links", YamaArama.links(game))
                        .put("results", JSONArray(results.getOrDefault(emptyList()).map { it.toJson() }))
                    results.exceptionOrNull()?.let { reply.put("error", it.message ?: "Arama yapılamadı.") }
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
                    val info = PackStore.add(context, path.substringAfterLast('/'), bytes, body.optString("game"),
                        repo.repo.substringBefore('/'), repo.url, repo.license, YamaArama.funding(repo))
                    PackStore.select(prefs, info.id)
                    respond(out, 200, json, PackStore.listJson(context, prefs))
                }
                "POST /api/paket/yukle" -> {
                    val info = PackStore.add(context, request.query["dosya"] ?: "", request.bytes, request.query["oyun"] ?: "",
                        request.query["cevirmen"] ?: "", request.query["kaynak"] ?: "", "", request.query["bagis"] ?: "")
                    PackStore.select(prefs, info.id)
                    respond(out, 200, json, PackStore.listJson(context, prefs))
                }
                "POST /api/paket/sec" -> {
                    val id = body.optString("id")
                    require(id.isEmpty() || PackStore.list(context).any { it.id == id }) { "Paket bulunamadı." }
                    PackStore.select(prefs, id)
                    respond(out, 200, json, PackStore.listJson(context, prefs))
                }
                "POST /api/paket/sil" -> {
                    PackStore.remove(context, prefs, body.optString("id"))
                    respond(out, 200, json, PackStore.listJson(context, prefs))
                }
                else -> respond(out, 404, "text/plain", "Yok")
            }
        } catch (e: Exception) {
            respond(out, 400, json, JSONObject().put("error", e.message ?: "İşlem yapılamadı.").toString())
        }
    }

    /** Translation on and off, and the PS5 pairing steps. Answers with the state, never with a key. */
    private fun controls(request: HttpRequest, out: OutputStream, context: Context) {
        val json = "application/json; charset=utf-8"
        fun state() = Translation.toJson(context).toString()
        try {
            val body = if (request.method == "POST" && request.body.isNotBlank()) JSONObject(request.body) else JSONObject()
            when ("${request.method} ${request.path}") {
                "GET /api/durum" -> respond(out, 200, json, Translation.toJson(context).put("girisAdresi", Ps5Setup.loginUrl).toString())
                "POST /api/ceviri" -> {
                    when (body.optString("islem")) {
                        "baslat" -> {
                            require(Ps5Setup.status(context).getBoolean("eslesti")) { "Önce PlayStation ile eşleştir (aşağıdaki adımlar)." }
                            Translation.start(context)
                        }
                        "durdur" -> Translation.stop()
                        "duraklat" -> if (Translation.running.get()) Translation.paused = true
                        "devam" -> Translation.paused = false
                        else -> throw IllegalArgumentException("Bilinmeyen işlem.")
                    }
                    respond(out, 200, json, state())
                }
                "GET /api/ps5/bul" -> respond(out, 200, json, Ps5Setup.discoverJson())
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
                    Ps5Setup.pair(context, body.optString("ip"), body.optString("pin"))
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/uyandir" -> {
                    check(Ps5Setup.wake(context)) { "Uyandırma gönderilemedi." }
                    respond(out, 200, json, state())
                }
                "POST /api/ps5/unut" -> {
                    Ps5Setup.forget(context)
                    respond(out, 200, json, state())
                }
                else -> respond(out, 404, "text/plain", "Yok")
            }
        } catch (e: Exception) {
            respond(out, 400, json, JSONObject().put("error", e.message ?: "İşlem yapılamadı.").toString())
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) = respond(out, code, type, body.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found")[code] ?: "Error"
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
