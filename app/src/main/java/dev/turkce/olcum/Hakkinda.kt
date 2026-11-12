package dev.turkce.olcum

import android.content.Context
import java.net.URLEncoder

/** One component the app ships, as the third-party list names it. */
internal data class Component(val name: String, val version: String, val license: String, val copyright: String)

internal data class ComponentGroup(val title: String, val items: List<Component>)

/** A few lines of the full licence text; [starts] when a file or a licence begins with them. */
internal data class TextPart(val text: String, val starts: Boolean)

/** Lines of the full licence text per list row on the TV, so one press of the remote moves a screenful or less. */
private const val LINES_PER_PART = 20

/**
 * The about texts the TV screen and the phone page share, and the licence files the app carries in
 * `assets/lisanslar`: the third-party list with its licence texts, the notices Google ships inside ML Kit and
 * Play services, and the AGPL-3.0 text from chiaki-ng.
 */
internal object About {
    const val NAME = "Tv Türkçe Altyazı"
    const val DESCRIPTION = "Tv Türkçe Altyazı, PlayStation 5 oyunlarındaki İngilizce altyazıyı ekrandan okur ve Türkçesini " +
        "görüntünün üstünde gösterir. Okuma da çeviri de bu TV'nin içinde yapılır."
    const val PRIVACY = "Oyun görüntüsü ve altyazı metni yalnız bu TV'de işlenir; internete gönderilmez, saklanmaz. " +
        "Tek istisna: telefondan altyazı yerini ayarlarken istediğin son kare, telefonda gösterilmek için TV'de tutulur. " +
        "Geliştiricinin sunucusu yoktur; uygulama geliştiriciye hiçbir bilgi göndermez."
    const val LICENSE = "Tv Türkçe Altyazı özgür yazılımdır: GNU Affero Genel Kamu Lisansı sürüm 3 (AGPL-3.0) koşullarıyla " +
        "kullanabilir, değiştirebilir ve dağıtabilirsin. Program hiçbir garanti olmadan sunulur. Kaynak kodu " +
        "yukarıdaki adreste; lisansın tam metni «Tam lisans metinleri» içinde."
    const val THIRD_PARTY_NOTE = "Uygulamanın içindeki başka yazılımlar kendi lisanslarıyla gelir. Google ML Kit (metin " +
        "okuma) ve Google Play hizmetleri kitaplıkları Google'ın ML Kit şartlarıyla gelir " +
        "(developers.google.com/ml-kit/terms); içlerindeki bileşenlerin bildirimleri tam lisans metinlerinde."
    const val TRADEMARK = "Bu uygulama Sony Interactive Entertainment ile bağlantılı, onaylı veya destekli değildir. PlayStation ve PS5, sahiplerinin tescilli markalarıdır."

    const val THIRD_PARTY_FILE = "lisanslar/UCUNCU-TARAF.txt"
    const val GOOGLE_FILE = "lisanslar/GOOGLE-BILESENLERI.txt"
    const val AGPL_FILE = "lisanslar/AGPL-3.0.txt"
    /** Every licence text the app carries, in the order the full-text screen shows them. */
    val FULL_TEXTS = listOf(THIRD_PARTY_FILE, GOOGLE_FILE, AGPL_FILE)
    /** In the third-party file the summary ends here and the licence texts begin. */
    const val TEXTS_MARK = "==== LİSANS METİNLERİ ===="

    /** The summary part of the third-party file: a group per `[title]`, one `ad | sürüm | lisans | telif` line per component. */
    fun groups(text: String): List<ComponentGroup> {
        val groups = mutableListOf<ComponentGroup>()
        var title: String? = null
        var items = mutableListOf<Component>()
        fun close() {
            title?.let { groups += ComponentGroup(it, items) }
            title = null
            items = mutableListOf()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line == TEXTS_MARK) break
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    close()
                    title = line.substring(1, line.length - 1)
                }
                line.isEmpty() -> close()
                title != null -> {
                    val fields = line.split(" | ")
                    if (fields.size == 4) items += Component(fields[0], fields[1], fields[2], fields[3])
                }
            }
        }
        close()
        return groups
    }

    fun groups(context: Context): List<ComponentGroup> = groups(read(context, THIRD_PARTY_FILE))

    fun fullTexts(context: Context): List<String> = FULL_TEXTS.map { read(context, it) }

    /** All licence files, one after the other, for the phone. */
    fun fullText(context: Context): String = fullTexts(context).joinToString("\n\n")

    /**
     * The licence files cut into rows of at most [lines] lines, so the TV list lays out only what is on screen.
     * Each file and each `---- ad ----` / `==== ad ====` block starts a new row marked [TextPart.starts], which the
     * remote's left and right keys jump between.
     */
    fun parts(texts: List<String>, lines: Int = LINES_PER_PART): List<TextPart> {
        val parts = mutableListOf<TextPart>()
        for (text in texts) {
            var rows = mutableListOf<String>()
            var starts = true
            fun flush() {
                if (rows.isEmpty()) return
                parts += TextPart(rows.joinToString("\n"), starts)
                rows = mutableListOf()
                starts = false
            }
            for (line in text.lines()) {
                if (isHeading(line) && rows.isNotEmpty()) {
                    flush()
                    starts = true
                } else if (rows.size >= lines) {
                    flush()
                }
                rows += line
            }
            flush()
        }
        return parts
    }

    private fun isHeading(line: String) =
        line.length > 10 && (line.startsWith("---- ") && line.endsWith(" ----") || line.startsWith("==== ") && line.endsWith(" ===="))

    /** One component as a line of plain text; a version of "-" is left out. */
    fun describe(item: Component): String =
        listOf(item.name, item.version.takeIf { it != "-" }).filterNotNull().joinToString(" ") + " · " + item.license

    /** The phone's about page: the same content as the TV screen, links instead of QR codes, the key kept on every link. */
    fun page(version: String, groups: List<ComponentGroup>, token: String): String {
        val key = URLEncoder.encode(token, "UTF-8")
        val list = groups.joinToString("") { group ->
            "<h3>${html(group.title)}</h3><ul>" + group.items.joinToString("") {
                "<li>${html(describe(it))}<br><span class=\"dim\">${html(it.copyright)}</span></li>"
            } + "</ul>"
        }
        return """<!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${html(NAME)} · Hakkında</title>
<style>
  body { margin:0; background:#0c1218; color:#eef2f6; font:16px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { max-width:760px; margin:0 auto; padding:12px 16px 28px; }
  .kart { background:#151f29; border:1px solid #273746; border-radius:14px; padding:16px; margin:14px 0; }
  h1 { font-size:22px; margin:8px 0 0; } h2 { font-size:17px; margin:0 0 10px; } h3 { font-size:15px; margin:14px 0 6px; }
  a { color:#50c8ff; word-break:break-all; } .dim { color:#93a4b5; font-size:13px; }
  ul { padding-left:18px; margin:0; } li { margin:0 0 8px; overflow-wrap:anywhere; }
</style></head><body><main>
<p><a href="/?t=$key">← Ayarlara dön</a></p>
<h1>${html(NAME)}</h1>
<p class="dim">Sürüm ${html(version)}</p>
<p>${html(DESCRIPTION)}</p>
<section class="kart"><h2>Gizlilik</h2><p>${html(PRIVACY)}</p>
<p>Gizlilik politikası: <a href="${html(Baglantilar.PRIVACY)}" rel="noopener noreferrer">${html(Baglantilar.PRIVACY)}</a></p></section>
<section class="kart"><h2>Kaynak kodu ve lisans</h2>
<p>Kaynak kodu: <a href="${html(Baglantilar.SOURCE)}" rel="noopener noreferrer">${html(Baglantilar.SOURCE)}</a></p>
<p>${html(LICENSE)}</p>
<p><a href="/lisanslar?t=$key">Tam lisans metinleri</a></p></section>
<section class="kart"><h2>Üçüncü taraf bileşenleri</h2><p class="dim">${html(THIRD_PARTY_NOTE)}</p>$list</section>
<section class="kart"><h2>Marka</h2><p>${html(TRADEMARK)}</p></section>
</main></body></html>
"""
    }

    private fun read(context: Context, name: String) = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun html(text: String) = buildString {
        for (c in text) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
