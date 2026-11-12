package dev.turkce.olcum

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

private const val PICTURE_W = 1920
private const val PICTURE_H = 1080
private const val MIN_AREA_W = 40
private const val MIN_AREA_H = 20
const val MAX_AREAS = 6
const val MIN_TEXT_SIZE = 8
const val MAX_TEXT_SIZE = 48

/** How an area is read: as one subtitle line, or as separate info boxes found inside it. */
enum class AreaKind(val key: String, val label: String) {
    SUBTITLE("altyazi", "Altyazı"),
    BOX("kutu", "Bilgi kutusu");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: SUBTITLE
    }
}

/** A part of the 1920x1080 game picture the app reads. */
data class ReadArea(val name: String, val kind: AreaKind, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val block get() = ScreenBlock(left, top, right, bottom, "")

    /** Kept inside the picture and never smaller than a line of text. */
    fun clamped(): ReadArea {
        val l = left.coerceIn(0, PICTURE_W - MIN_AREA_W)
        val t = top.coerceIn(0, PICTURE_H - MIN_AREA_H)
        return copy(
            name = name.trim().take(30),
            left = l, top = t,
            right = right.coerceIn(l + MIN_AREA_W, PICTURE_W),
            bottom = bottom.coerceIn(t + MIN_AREA_H, PICTURE_H),
        )
    }
}

enum class SubtitleFont(val key: String, val label: String) {
    BOLD("kalin", "Kalın"),
    PLAIN("sans", "Düz"),
    SERIF("serif", "Tırnaklı"),
    MONO("mono", "Daktilo");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: BOLD
    }
}

/** Colours are 0xRRGGBB; [opacity] is the box background in percent. */
data class OverlayStyle(
    val font: SubtitleFont, val subtitleSize: Int, val boxSize: Int,
    val textColor: Int, val boxColor: Int, val opacity: Int,
) {
    fun clamped() = copy(
        subtitleSize = subtitleSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
        boxSize = boxSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
        textColor = textColor and 0xFFFFFF, boxColor = boxColor and 0xFFFFFF,
        opacity = opacity.coerceIn(0, 100),
    )

    /** The box colour with its opacity, ready for a view background. */
    val boxArgb get() = (opacity * 255 / 100 shl 24) or boxColor
    val textArgb get() = (0xFF shl 24) or textColor
}

/**
 * [turkish] is where the player put the Turkish subtitle; null keeps it over the game's own line.
 * [skipIntro] leaves out studio intros, legal notices and credits.
 */
data class Settings(
    val areas: List<ReadArea>, val style: OverlayStyle, val turkish: ReadArea? = null, val skipIntro: Boolean = true,
) {
    val subtitleAreas get() = areas.filter { it.kind == AreaKind.SUBTITLE }
    val boxAreas get() = areas.filter { it.kind == AreaKind.BOX }

    fun clamped() = Settings(
        areas.take(MAX_AREAS).mapIndexed { index, area ->
            area.clamped().let { if (it.name.isEmpty()) it.copy(name = "Alan ${index + 1}") else it }
        }.ifEmpty { DEFAULT_AREAS },
        style.clamped(),
        turkish?.clamped()?.copy(name = TURKISH_NAME, kind = AreaKind.SUBTITLE),
        skipIntro,
    )

    fun toJson(): String = JSONObject()
        .put("regions", JSONArray(areas.map { area ->
            JSONObject().put("name", area.name).put("kind", area.kind.key)
                .put("left", area.left).put("top", area.top).put("right", area.right).put("bottom", area.bottom)
        }))
        .put("font", style.font.key)
        .put("subtitleSize", style.subtitleSize)
        .put("boxSize", style.boxSize)
        .put("textColor", hex(style.textColor))
        .put("boxColor", hex(style.boxColor))
        .put("opacity", style.opacity)
        .put("turkish", turkish?.let { JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom) })
        .put("skipIntro", skipIntro)
        .toString()

    companion object {
        const val TURKISH_NAME = "Türkçe altyazı"
        val DEFAULT_AREAS = listOf(
            ReadArea("Altyazı", AreaKind.SUBTITLE, 192, 777, 1728, 1079),
            ReadArea("Görev kutusu", AreaKind.BOX, 0, 0, 960, 389),
            ReadArea("Üst bölge", AreaKind.BOX, 0, 0, 1920, 777),
        )
        val DEFAULT_STYLE = OverlayStyle(SubtitleFont.BOLD, 18, 12, 0xFFFFFF, 0x101820, 75)
        val DEFAULT = Settings(DEFAULT_AREAS, DEFAULT_STYLE)

        /** Missing or broken fields fall back to the defaults; numbers are clamped. */
        fun fromJson(text: String): Settings {
            val json = JSONObject(text)
            val regions = json.optJSONArray("regions")
            val areas = if (regions == null) DEFAULT_AREAS else List(regions.length()) { i ->
                val r = regions.getJSONObject(i)
                ReadArea(
                    r.optString("name", ""), AreaKind.of(r.optString("kind", "altyazi")),
                    r.getInt("left"), r.getInt("top"), r.getInt("right"), r.getInt("bottom"),
                )
            }
            val d = DEFAULT_STYLE
            val style = OverlayStyle(
                SubtitleFont.of(json.optString("font", d.font.key)),
                json.optInt("subtitleSize", d.subtitleSize), json.optInt("boxSize", d.boxSize),
                color(json.optString("textColor"), d.textColor), color(json.optString("boxColor"), d.boxColor),
                json.optInt("opacity", d.opacity),
            )
            val turkish = json.optJSONObject("turkish")?.let {
                ReadArea(TURKISH_NAME, AreaKind.SUBTITLE, it.getInt("left"), it.getInt("top"), it.getInt("right"), it.getInt("bottom"))
            }
            return Settings(areas, style, turkish, json.optBoolean("skipIntro", true)).clamped()
        }

        private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

        private fun color(text: String, fallback: Int): Int =
            if (Regex("#[0-9a-fA-F]{6}").matches(text)) text.substring(1).toInt(16) else fallback
    }
}

/** One copy of the settings for the whole app; the overlay and the reader follow its changes. */
internal object SettingsStore {
    private const val FILE = "altyazi"
    const val KEY = "ayar"
    private const val OLD_SUBTITLE = "altyazi_boyut"
    private const val OLD_BOX = "kutu_boyut"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(prefs: SharedPreferences): Settings =
        prefs.getString(KEY, null)?.let { runCatching { Settings.fromJson(it) }.getOrNull() }
            ?: Settings.DEFAULT.copy(style = Settings.DEFAULT_STYLE.copy(
                subtitleSize = prefs.getInt(OLD_SUBTITLE, Settings.DEFAULT_STYLE.subtitleSize),
                boxSize = prefs.getInt(OLD_BOX, Settings.DEFAULT_STYLE.boxSize),
            ))

    fun save(prefs: SharedPreferences, settings: Settings): Settings {
        val clean = settings.clamped()
        prefs.edit().putString(KEY, clean.toJson()).apply()
        return clean
    }
}
