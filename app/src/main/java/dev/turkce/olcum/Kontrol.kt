package dev.turkce.olcum

import android.content.Context
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** The translation runs until someone stops it from the TV or the phone, or the PS5 ends the session. */
internal const val UNTIL_STOPPED = 0
internal const val MODEL_FAILED = "Çeviri modeli kurulamadı: TV'de yer var mı?"
/** The app's service, which holds the phone panel and the translation, is not running: only the TV can start it. */
internal const val OPEN_APP = "TV'de Tv Türkçe Altyazı'yı aç, sonra tekrar dene."
internal const val NEED_OVERLAY = "Önce TV'de izin ver: TV ekranındaki \"İzni ver\" düğmesine bas."
/** A PS5 session that ended without anyone stopping it. */
internal const val SESSION_ENDED = "PS5 ile bağlantı kesildi: PS5 kapanmış, dinlenmeye geçmiş ya da ağ kopmuş olabilir. Tekrar aç."
internal const val DEMO_ENDED = "Deneme kapandı. Tekrar açabilirsin."
private val TURKISH_LETTERS = Regex("[çğıöşüÇĞİÖŞÜ]")
private const val PIN_DIGITS = 8
private const val ACCOUNT_BYTES = 8
private const val DISCOVERY_PORT = 9302
private const val DISCOVERY_WAIT_MS = 1_500
private const val HTTP_TIMEOUT_MS = 15_000
private const val PSN_CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
// Public client of Sony's own Remote Play app, the one chiaki-ng uses; not anyone's password.
private const val PSN_CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
private const val PSN_REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
private const val PSN_TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
private const val PSN_SCOPE =
    "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"

/** Where the switch stands, for the phone page and the TV; [FAILED] is "off, and here is why" (the note). */
internal enum class Phase(val key: String) {
    OFF("kapali"), STARTING("baslatiliyor"), ON("acik"), STOPPING("durduruluyor"), FAILED("hata"),
}

/**
 * The on/off switch the phone page and the TV share. A run is a thread inside the app's service, never a new start
 * of the service: the TV starts the service once while its screen is in front ([OlcumService.ready]), and from then
 * on the phone can turn the translation on and off while the game holds the screen. One run at a time, PS5 or demo.
 */
internal object Translation {
    val running = AtomicBoolean(false)
    @Volatile var stopAsked = false
    @Volatile var paused = false
    @Volatile var note = "Kapalı"
    @Volatile var phase = Phase.OFF
        private set

    /** True while the run reads the demo clip instead of the PS5. One run at a time either way: [running] is shared. */
    @Volatile var demo = false
        private set

    /** When the demo clip's first frame went to the decoder ([System.nanoTime]), so the demo screen plays in step; 0 when not. */
    @Volatile var demoStartNs = 0L

    /** The demo screen's OK button: the demo pack's human lines when on, the machine's own when off. */
    @Volatile var demoPack = true

    /** Counts runs, so a screen watching one sees it end even when the next one starts right after. */
    @Volatile var runId = 0
        private set

    /** A start asked for while the last run was still letting go; it begins as soon as that run has ended. */
    private var queued: Run? = null

    private class Run(val demo: Boolean, val seconds: Int, val ocrMs: Int, val reads: Int)

    /**
     * False when the same kind of run is already on. Throws with a message for the phone page or the TV screen when
     * it cannot start. [demo] reads the clip inside the app instead of the PS5. A game asked for during the demo
     * replaces it; the demo never replaces a game. A start during a stop waits for the stop, then runs.
     */
    @Synchronized
    fun start(
        context: Context, demo: Boolean = false, seconds: Int = UNTIL_STOPPED, ocrMs: Int = 120, reads: Int = 1,
    ): Boolean {
        val app = context.applicationContext
        check(FirstRun.canOverlay(app)) { NEED_OVERLAY }
        check(OlcumService.alive) { OPEN_APP }
        val run = Run(demo, seconds, ocrMs, reads)
        if (running.get()) {
            if (!stopAsked) {
                if (this.demo == demo) return false
                check(!demo) { "Önce çeviriyi durdur." }
                stopNow()
            }
            queued = run
            phase = Phase.STARTING
            note = "Başlatılıyor"
            return true
        }
        running.set(true)
        // A pairing checks [running] after taking the gate and this checks the gate after setting [running], so the
        // PS5 session and a pairing never overlap: the later one of the two is refused.
        if (!demo && ChiakiGate.calls.current == ChiakiGate.PAIRING) {
            running.set(false)
            throw IllegalStateException(busyMessage(ChiakiGate.PAIRING))
        }
        launch(app, run)
        return true
    }

    /** Off: the run lets go of the PS5 and ends; the service and the phone panel stay. Also clears a shown error. */
    @Synchronized
    fun stop() {
        queued = null
        if (running.get()) {
            stopNow()
        } else if (phase == Phase.FAILED) {
            phase = Phase.OFF
            note = "Kapalı"
        }
    }

    /** A start is waiting for the last run to let go. */
    val waiting: Boolean get() = synchronized(this) { queued != null }

    /** Leaving the demo screen ends the demo, and only the demo: a game that replaced it keeps running. */
    @Synchronized
    fun stopDemo() {
        if (queued?.demo == true) {
            queued = null
            return
        }
        if (running.get() && demo && queued == null && !stopAsked) stopNow()
    }

    private fun stopNow() {
        stopAsked = true
        phase = Phase.STOPPING
        note = "Durduruluyor"
    }

    /** The running pipeline's word for the switch; dropped once a stop was asked, so "Durduruluyor" stays till the end. */
    @Synchronized
    fun say(text: String, now: Phase? = null) {
        if (stopAsked) return
        note = text
        if (now != null) phase = now
    }

    private fun launch(app: Context, run: Run) {
        running.set(true)
        stopAsked = false
        paused = false
        demo = run.demo
        demoStartNs = 0L
        runId++
        phase = Phase.STARTING
        note = if (FirstRun.modelReady(app)) "Başlatılıyor" else "Çeviri modeli hazırlanıyor"
        LineStats.reset()
        OlcumService.showState(app)
        Thread({
            val root = workDir(app)
            val log = runLog(root)
            var failure: String? = null
            try {
                prepareModel(app)
                if (!stopAsked) {
                    val ps5 = Ps5Measurements(root, app.filesDir, log, app)
                    ps5.pipelineStage(run.seconds, run.ocrMs, run.reads, if (run.demo) DemoSource(app, log) else ps5.ps5Source())
                }
            } catch (error: Throwable) {
                if (error is IllegalStateException) failure = error.message
                log("HATA ceviri: ${Log.getStackTraceString(error).take(1500)}")
            } finally {
                ended(app, run, failure)
            }
        }, "ceviri").start()
    }

    /**
     * The model comes out of the APK on first use, here on the run's own thread rather than on the screen's or the
     * phone page's; the TV and the phone say so meanwhile.
     */
    private fun prepareModel(app: Context) {
        if (FirstRun.modelReady(app)) return
        say("Çeviri modeli hazırlanıyor")
        try {
            FirstRun.installModel(app)
        } catch (error: Exception) {
            throw IllegalStateException(MODEL_FAILED, error)
        }
    }

    /** A run ended: starts the one waiting, or says why it ended when nobody stopped it. */
    @Synchronized
    private fun ended(app: Context, run: Run, failure: String?) {
        val stopped = stopAsked
        val wasDemo = demo
        val wasOn = phase == Phase.ON
        val next = queued
        queued = null
        paused = false
        stopAsked = false
        demo = false
        demoStartNs = 0L
        if (next != null) {
            val problem = when {
                !FirstRun.canOverlay(app) -> NEED_OVERLAY
                !OlcumService.alive -> OPEN_APP
                !next.demo && ChiakiGate.calls.current == ChiakiGate.PAIRING -> busyMessage(ChiakiGate.PAIRING)
                else -> null
            }
            if (problem == null) {
                launch(app, next)
                return
            }
            note = problem
            phase = Phase.FAILED
        } else if (stopped || run.seconds != UNTIL_STOPPED && wasOn && failure == null) {
            // Stopped from the TV or the phone, or (debug only) a timed run that reached its end.
            note = "Kapalı"
            phase = Phase.OFF
        } else {
            note = when {
                // The app's own messages are Turkish; system text (English) is replaced by what probably happened.
                failure != null && TURKISH_LETTERS.containsMatchIn(failure) -> failure
                note.startsWith("PlayStation'a bağlanamadı") || note == DEMO_FAILED || note == MODEL_FAILED -> note
                wasDemo -> DEMO_ENDED
                else -> SESSION_ENDED
            }
            phase = Phase.FAILED
        }
        running.set(false)
        OlcumService.showState(app)
    }

    fun toJson(context: Context): JSONObject = JSONObject()
        .put("calisiyor", running.get())
        .put("deneme", running.get() && demo)
        .put("duraklatildi", paused)
        .put("not", note)
        .put("durum", phase.key)
        .put("neden", if (phase == Phase.FAILED) note else "")
        .put("izin", FirstRun.canOverlay(context))
        .put("ps5", Ps5Setup.status(context))
}

/** The app's folder for the model and, in debug builds, the measurement results; some TVs have no external storage. */
internal fun workDir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

/** What a fresh TV needs once: the translation model out of the APK, and leave to draw over the game. */
internal object FirstRun {
    private const val MODEL = "kucuk"
    private const val DONE_MARK = ".kuruldu"

    fun canOverlay(context: Context) = android.provider.Settings.canDrawOverlays(context)

    private fun modelDir(context: Context) = File(workDir(context), MODEL)

    fun modelReady(context: Context) = File(modelDir(context), DONE_MARK).exists()

    /** Copies the model next to the app's files on first use; a cut-short copy is redone next time. */
    @Synchronized
    fun installModel(context: Context) {
        if (modelReady(context)) return
        val target = modelDir(context)
        val temporary = File(target.parentFile, "$MODEL.yeni")
        temporary.deleteRecursively()
        temporary.mkdirs()
        for (name in context.assets.list(MODEL).orEmpty()) {
            context.assets.open("$MODEL/$name").use { input -> File(temporary, name).outputStream().use { input.copyTo(it) } }
        }
        File(temporary, DONE_MARK).writeText("1")
        target.deleteRecursively()
        check(temporary.renameTo(target)) { MODEL_FAILED }
    }
}

/** One PS5 found on the home network. */
internal data class FoundPs5(val address: String, val name: String, val awake: Boolean, val type: String) {
    fun toJson(): JSONObject = JSONObject().put("adres", address).put("ad", name).put("uyanik", awake).put("tur", type)
}

/**
 * Pairing the TV with a PS5, step by step from the phone page. The PSN password is typed only on Sony's own page;
 * the TV keeps just the account number Sony hands back and the keys the PS5 gives at pairing.
 */
internal object Ps5Setup {
    private const val ACCOUNT_FILE = "psn.hesap"
    const val PAIRING_FILE = "ps5.kayit"

    val loginUrl: String = ("https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
        "&response_type=code&client_id=$PSN_CLIENT_ID&redirect_uri=$PSN_REDIRECT&scope=$PSN_SCOPE&request_locale=en_US&ui=pr" +
        "&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&").replace(" ", "%20")

    /** The one-time code from the address Sony's page ends on, or the code itself when only that was pasted. */
    fun codeFrom(pasted: String): String? {
        val text = pasted.trim()
        Regex("[?&]code=([A-Za-z0-9._~-]+)").find(text)?.let { return it.groupValues[1] }
        return text.takeIf { Regex("[A-Za-z0-9._~-]{4,64}").matches(it) }
    }

    /** Sony's decimal account number as the 8 little-endian bytes PS Remote Play wants, in base64. */
    fun accountFrom(userId: String): String? {
        val number = userId.trim().toULongOrNull() ?: return null
        val bytes = ByteArray(ACCOUNT_BYTES) { (number shr (8 * it)).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** The same value typed by hand (other Remote Play tools show it), checked to be 8 bytes. */
    fun accountFromBase64(text: String): String? = runCatching {
        Base64.getDecoder().decode(text.trim()).takeIf { it.size == ACCOUNT_BYTES }?.let { Base64.getEncoder().encodeToString(it) }
    }.getOrNull()

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun basicAuth() = "Basic " + Base64.getEncoder().encodeToString("$PSN_CLIENT_ID:$PSN_CLIENT_SECRET".toByteArray())

    private fun request(url: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Authorization", basicAuth())
            connection.setRequestProperty("User-Agent", "turkce-tv")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            // No status number on the page: the person needs only what to do next.
            check(code !in 400..499) {
                "Sony bu girişi kabul etmedi: adres eksik kopyalandı ya da süresi geçti (her adres bir kez ve birkaç dakika geçerli). " +
                    "«Sony'de giriş yap»a yeniden bas, yeni adresi yapıştır."
            }
            check(code in 200..299) { "Sony'nin sunucusu şu an cevap vermiyor. Birkaç dakika sonra tekrar dene." }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Trades the one-time code for the account number. The access token is used once and never kept. */
    fun fetchAccount(code: String): String {
        val token = request(PSN_TOKEN_URL,
            "grant_type=authorization_code&code=${enc(code)}&scope=${enc(PSN_SCOPE)}&redirect_uri=${enc(PSN_REDIRECT)}&")
            .optString("access_token")
        require(token.isNotEmpty()) { "Sony giriş anahtarı vermedi. Girişi baştan yap." }
        val userId = request("$PSN_TOKEN_URL/${enc(token)}", null).opt("user_id")?.toString() ?: ""
        return accountFrom(userId) ?: error("Sony hesap numarası vermedi.")
    }

    /** The account number is kept encrypted, like the PS5 keys; one that cannot be decrypted counts as none. */
    fun saveAccount(context: Context, account: String) = SecretFiles.writeText(File(context.filesDir, ACCOUNT_FILE), account)

    private fun account(context: Context): String? = SecretFiles.readText(File(context.filesDir, ACCOUNT_FILE))

    /** Only addresses inside a home network: the TV never sends its keys anywhere else. */
    fun isHomeAddress(text: String): Boolean {
        val parts = text.trim().split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 && it == v.toString() } ?: return false }
        return n[0] == 10 || n[0] == 172 && n[1] in 16..31 || n[0] == 192 && n[1] == 168
    }

    /** Reads a PS5's answer to the search: "200 Ok" when on, "620 Server Standby" when resting. */
    fun parseDiscovery(address: String, reply: String): FoundPs5? {
        val lines = reply.split('\n').map { it.trim() }
        val first = lines.firstOrNull() ?: return null
        val status = first.split(' ').getOrNull(1)
        val awake = status == "200"
        val resting = status == "620"
        if (!awake && !resting) return null
        val fields = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val type = fields["host-type"] ?: ""
        if (!type.startsWith("PS5", ignoreCase = true)) return null
        return FoundPs5(address, fields["host-name"] ?: "PS5", awake, type)
    }

    /** Asks the home network for a PS5 for a moment and lists what answered. */
    fun discover(): List<FoundPs5> {
        val found = LinkedHashMap<String, FoundPs5>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val query = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()
            for (target in broadcastAddresses()) {
                runCatching { socket.send(DatagramPacket(query, query.size, target, DISCOVERY_PORT)) }
            }
            val end = System.currentTimeMillis() + DISCOVERY_WAIT_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val address = packet.address.hostAddress ?: continue
                parseDiscovery(address, String(packet.data, 0, packet.length))?.let { found[address] = it }
            }
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
        .mapNotNull { it.broadcast }
        .ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }

    /** Pairs with the PIN the PS5 shows under Remote Play. The keys go to the app's private folder only. */
    fun pair(context: Context, host: String, pin: String) {
        val account = account(context) ?: error("Önce 3. adımda yeni hesapla giriş yap.")
        require(isHomeAddress(host)) { "PlayStation adresi ev ağına ait olmalı (192.168.x.x gibi)." }
        val digits = pin.filter(Char::isDigit)
        require(digits.length == PIN_DIGITS) { "PlayStation'ın gösterdiği kod $PIN_DIGITS haneli olmalı." }
        val accountId = Base64.getDecoder().decode(account)
        // The native side keeps one pairing at a time: a second request fails at once instead of running beside it.
        // A running translation refuses it too, checked inside the gate (see [Translation.start]).
        ChiakiGate.calls.run(ChiakiGate.PAIRING) {
            check(!Translation.running.get()) { "Önce çeviriyi durdur." }
            val keys = ChiakiBridge.regist(host, accountId, digits.toInt())
                ?: throw PairRefused()
            Pairing(keys.copyOfRange(0, 16), keys.copyOfRange(16, 32), accountId, host).save(File(context.filesDir, PAIRING_FILE))
        }
    }

    fun wake(context: Context): Boolean {
        val pairing = Pairing.load(File(context.filesDir, PAIRING_FILE))
        return ChiakiGate.calls.run(ChiakiGate.WAKING) { ChiakiBridge.wake(pairing.host, pairing.registKey) }
    }

    /** Forgets the account number and the PS5 keys. The PS5 side is removed under Remote Play's linked devices. */
    fun forget(context: Context) {
        check(!Translation.running.get()) { "Önce çeviriyi durdur." }
        File(context.filesDir, ACCOUNT_FILE).delete()
        File(context.filesDir, PAIRING_FILE).delete()
    }

    /** What the page may show: never a key, only whether there is one and the PS5's home address. */
    fun status(context: Context): JSONObject {
        val pairing = File(context.filesDir, PAIRING_FILE).takeIf { it.exists() }?.let { runCatching { Pairing.load(it) }.getOrNull() }
        return JSONObject()
            .put("hesap", account(context) != null)
            .put("eslesti", pairing != null)
            .put("adres", pairing?.host ?: "")
            // A key file from before encryption that could not be encrypted yet: pairing works, the next read retries.
            .put("sifrelemeBekliyor", SecretFiles.migrationPending)
    }
}
