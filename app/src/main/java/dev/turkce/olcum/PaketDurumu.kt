package dev.turkce.olcum

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** 12345 as a Turkish reader writes it: "12.345". */
internal fun turkishNumber(value: Int): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("tr")).format(value)

/**
 * Where this run's Turkish lines came from, and the last few of them, for the phone panel only.
 * Kept in memory and never written anywhere: the game's text stays out of every file, in release and debug alike.
 */
internal object LineStats {
    private const val RECENT = 8
    private val counts = IntArray(LineSource.entries.size)
    private val recent = ArrayDeque<Pair<String, LineSource>>()

    @Synchronized
    fun reset() {
        counts.fill(0)
        recent.clear()
    }

    @Synchronized
    fun add(source: LineSource, turkish: String) {
        counts[source.ordinal]++
        recent.addFirst(turkish to source)
        while (recent.size > RECENT) recent.removeLast()
    }

    @Synchronized
    fun count(source: LineSource) = counts[source.ordinal]

    @Synchronized
    fun toJson(): JSONObject {
        val sayac = JSONObject()
        LineSource.entries.forEach { sayac.put(it.key, counts[it.ordinal]) }
        return JSONObject()
            .put("sayac", sayac)
            .put("toplam", counts.sum())
            .put("son", JSONArray(recent.map { (turkish, source) -> JSONObject().put("tr", turkish).put("kaynak", source.key) }))
    }
}

/**
 * The game pack the player chose, read into memory the moment it is chosen, so the TV and the phone know what is in it
 * even with the translation off, and a running translation takes it over on its next line.
 */
internal object ActivePack {
    @Volatile var pack: GamePack = GamePack.EMPTY
        private set

    /** The pack id [pack] was read for; null before the first read since the app started. */
    @Volatile private var loadedId: String? = null
    private val loading = AtomicBoolean(false)
    private val lock = Any()

    /** Reads the stored choice once, beside the caller, when nothing was read yet since the app started. */
    fun ensure(context: Context) {
        if (loadedId != null || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(isDaemon = true, name = "oyun-paketi") {
            try {
                reload(app)
            } finally {
                loading.set(false)
            }
        }
    }

    /** The stored choice, read again only when it changed; a pack that no longer reads is dropped from the choice. */
    fun reload(context: Context): GamePack = synchronized(lock) {
        val prefs = SettingsStore.prefs(context)
        val id = PackStore.active(prefs)
        if (id == loadedId) return pack
        val loaded = runCatching { PackStore.load(context, id) }.getOrNull()?.takeIf { it.size > 0 }
        if (loaded == null && id.isNotEmpty()) PackStore.select(prefs, "")
        pack = loaded ?: GamePack.EMPTY
        loadedId = if (loaded == null) "" else id
        pack
    }

    /**
     * Makes [id] the pack in use ("" for none), reading and checking it first: a pack that cannot be read is never
     * chosen, so the TV never says "pack on" while translating with nothing. Throws with a Turkish message.
     */
    fun choose(context: Context, prefs: SharedPreferences, id: String): GamePack = synchronized(lock) {
        if (id.isEmpty()) {
            PackStore.select(prefs, "")
            pack = GamePack.EMPTY
            loadedId = ""
            return GamePack.EMPTY
        }
        require(PackStore.list(context).any { it.id == id }) { "Bu paket TV'de bulunamadı. Listeyi yenile." }
        val loaded = runCatching { PackStore.load(context, id) }.getOrNull()
        require(loaded != null && loaded.size > 0) { "Paket okunamadı ya da içinde satır yok. Paketi silip yeniden yükle." }
        PackStore.select(prefs, id)
        pack = loaded
        loadedId = id
        loaded
    }

    /** The TV screen's line about the pack. */
    fun summary(): String {
        val now = pack
        return if (now.size == 0) "Oyun paketi yok" else "Oyun paketi: ${now.info.game} (${turkishNumber(now.size)} satır)"
    }

    /** What the phone panel shows about the pack in use; empty when there is none. */
    fun toJson(): JSONObject {
        val now = pack
        if (now.size == 0) return JSONObject()
        return JSONObject().put("id", now.info.id).put("oyun", now.info.game).put("satir", now.size).put("cevirmen", now.info.credit)
    }
}

/** The demo screen's own small pack: natural Turkish for the demo clip's lines. Never listed, never the player's pack. */
internal object DemoPack {
    const val FILE = "demo/deneme-paketi.tsv"
    @Volatile private var pack: GamePack? = null

    fun get(context: Context): GamePack = pack ?: synchronized(this) {
        pack ?: runCatching {
            val pairs = context.assets.open(FILE).bufferedReader().use { PackFormat.tsv(it.readText()) }
            GamePack(PackInfo("deneme", "Deneme", "Tv Türkçe Altyazı", "", "", pairs.size), pairs)
        }.getOrDefault(GamePack.EMPTY).also { pack = it }
    }
}

/** Whether a pack line gets a ✓ on the TV, so the player sees which lines are human work. On unless the panel turns it off. */
internal object PackMark {
    private const val KEY = "paket_isareti"
    const val SIGN = "✓ "

    fun on(context: Context) = SettingsStore.prefs(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) = SettingsStore.prefs(context).edit().putBoolean(KEY, on).apply()
}

/**
 * A short note at the top of the TV about the game pack, over whatever is on screen, the game included. Works with the
 * translation off: it needs only the leave to draw over other apps, which the app asks for on first start (without
 * it, Android's own short message says the same).
 */
internal object PackToast {
    private const val SHOW_MS = 5_000L
    private const val TOP_GAP_PX = 48
    private const val TEXT_SP = 20f
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var manager: WindowManager? = null
    private val hide = Runnable { remove() }

    /** "Oyun paketi yüklendi: Oyun · 12.345 satır · çeviren: Ad". */
    fun text(what: String, info: PackInfo, lines: Int): String = buildString {
        append("Oyun paketi ").append(what).append(": ").append(info.game)
        append(" · ").append(turkishNumber(lines)).append(" satır")
        if (info.credit.isNotBlank()) append(" · çeviren: ").append(info.credit)
    }

    fun show(context: Context, text: String) {
        val app = context.applicationContext
        if (!FirstRun.canOverlay(app)) {
            // Without the leave the note goes to Android's own short message, which shows over other apps as well.
            handler.post { runCatching { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } }
            return
        }
        handler.post {
            val label = view ?: runCatching { add(app) }.getOrNull() ?: return@post
            label.text = text
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, SHOW_MS)
        }
    }

    private fun add(app: Context): TextView {
        val windows = app.getSystemService(WindowManager::class.java)
        val label = TextView(app).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            background = rounded(Color.argb(235, 16, 24, 32))
            setPadding(36, 18, 36, 18)
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_GAP_PX
        }
        windows.addView(label, params)
        view = label
        manager = windows
        return label
    }

    private fun remove() {
        val label = view ?: return
        runCatching { manager?.removeView(label) }
        view = null
        manager = null
    }
}
