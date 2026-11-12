package dev.turkce.olcum

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val HEADER = "#paket "
private const val FUZZY_MIN_CHARS = 12
private const val FUZZY_MIN_SCORE = 0.88
private const val FUZZY_LENGTH_SLACK = 0.15
private const val COMMON_WORD = 3_000
private const val MEMO_LINES = 512
private const val MISSING = "\u0000"
private val TAG = Regex("<[^<>]{1,40}>")
private val SPACES = Regex("\\s+")
private const val MAX_UNZIPPED = 120 * 1024 * 1024
/** Files in a ZIP the TV cannot read are unpacked only to be skipped; this keeps a "ZIP bomb" from running for hours. */
private const val MAX_SKIPPED = 1024L * 1024 * 1024
private const val MAX_ZIP_FILES = 10_000
private const val MAX_LINK = 300
/** No spaces, control characters, quotes, angle brackets or backslashes in a link the phone page will open. */
private val LINK_CHARS = Regex("[^\\s\\p{Cntrl}\"'<>\\\\`]+")
private val READABLE = listOf(".po", ".tsv", ".txt", ".csv", ".rpy")
private val ENGLISH_COLUMNS = setOf("en", "en-us", "en_us", "eng", "source", "original", "english")
private val TURKISH_COLUMNS = setOf("tr", "tr-tr", "tr_tr", "tur", "turkce", "türkçe", "turkish", "translation")
private val TRANSLATE = Regex("^translate\\s+(\\w+)\\s")
private val SAY = Regex("^(?:[\\w.]+\\s+)*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val COMMENTED_SAY = Regex("^#\\s*(?:[\\w.]+\\s+)*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val RENPY_TAG = Regex("\\{[^{}]*\\}")

/** A path or name that says Turkish: "tr", "tr_TR", "turkish", "türkçe"... */
internal val TURKISH_PATH = Regex("(^|[^a-z])(tr|tr[_-]tr|turkish|turkce|türkçe)([^a-z]|$)", RegexOption.IGNORE_CASE)

/**
 * A link the phone page may open: https with a real host, no user name in it, at most 300 characters; otherwise "".
 * Links come from translators' files and repositories, so `javascript:`, `data:`, plain `http:` and the like are dropped.
 */
internal fun webLink(text: String): String {
    val link = text.trim()
    if (link.length > MAX_LINK || !LINK_CHARS.matches(link)) return ""
    val uri = runCatching { java.net.URI(link) }.getOrNull() ?: return ""
    return if (uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) link else ""
}

/** Who made a pack and where it came from; shown next to the pack so the translators get their credit. */
data class PackInfo(
    val id: String, val game: String, val credit: String, val source: String, val license: String, val lines: Int,
    val donate: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("game", game).put("credit", credit)
        .put("source", source).put("license", license).put("lines", lines).put("donate", donate)

    companion object {
        fun fromJson(json: JSONObject) = PackInfo(
            json.optString("id"), json.optString("game"), json.optString("credit"),
            webLink(json.optString("source")), json.optString("license"), json.optInt("lines"), webLink(json.optString("donate")),
        )

        /** A file-safe name made from the game and the translator, so two translations of one game can sit side by side. */
        fun idOf(game: String, credit: String): String {
            val plain = Normalizer.normalize("$game $credit".replace('ı', 'i'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").lowercase()
            return plain.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "paket" }
        }
    }
}

/**
 * The human translation of one game: English line to Turkish line. A line read off the screen is matched exactly first,
 * then loosely, because the reader sometimes gets a letter or two wrong.
 */
class GamePack(val info: PackInfo, pairs: Map<String, String>) {
    private val keys: Array<String>
    private val values: Array<String>
    private val exact: Map<String, String>
    private val index: Map<String, IntArray>

    init {
        val clean = LinkedHashMap<String, String>()
        pairs.forEach { (english, turkish) ->
            val key = GameTranslator.key(english.replace(TAG, " "))
            val text = turkish.replace(TAG, "").replace(SPACES, " ").trim()
            if (key.isNotEmpty() && text.isNotEmpty()) clean.putIfAbsent(key, text)
        }
        exact = clean
        keys = clean.keys.toTypedArray()
        values = clean.values.toTypedArray()
        val words = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, key -> words(key).forEach { words.getOrPut(it) { ArrayList() }.add(i) } }
        index = words.mapValues { it.value.toIntArray() }
    }

    val size get() = keys.size

    /** The screen repeats a line many times a second; the loose search runs once per line. */
    private val memo = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > MEMO_LINES
    }

    /** The pack's Turkish for a line read off the screen, or null when the pack does not have it. */
    fun find(line: String): String? {
        val key = GameTranslator.key(line)
        exact[key]?.let { return it }
        if (key.length < FUZZY_MIN_CHARS || keys.isEmpty()) return null
        synchronized(memo) { memo[key] }?.let { return it.takeIf { it != MISSING } }
        val found = loose(key)
        synchronized(memo) { memo[key] = found ?: MISSING }
        return found
    }

    private fun loose(key: String): String? {
        val candidates = HashSet<Int>()
        words(key).mapNotNull { index[it] }.filter { it.size <= COMMON_WORD }.sortedBy { it.size }.take(3)
            .forEach { list -> list.forEach(candidates::add) }
        var best = -1
        var bestScore = FUZZY_MIN_SCORE
        for (i in candidates) {
            val other = keys[i]
            if (abs(other.length - key.length) > key.length * FUZZY_LENGTH_SLACK) continue
            val longest = max(key.length, other.length)
            val limit = (longest * (1 - bestScore)).toInt()
            val distance = distance(key, other, limit) ?: continue
            val score = 1 - distance.toDouble() / longest
            if (score >= bestScore) {
                best = i
                bestScore = score
            }
        }
        return if (best >= 0) values[best] else null
    }

    private fun words(key: String) = key.split(' ').filter { it.length >= 3 }.distinct()

    companion object {
        val EMPTY = GamePack(PackInfo("", "", "", "", "", 0), emptyMap())

        /** Edit distance, or null as soon as it must exceed [limit]. */
        fun distance(a: String, b: String, limit: Int): Int? {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowBest = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
                    rowBest = min(rowBest, current[j])
                }
                if (rowBest > limit) return null
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length].takeIf { it <= limit }
        }
    }
}

/** Reads the translation files people publish: our own TSV and gettext PO, the usual open-source format. */
object PackFormat {
    /** English to Turkish pairs from a file, picked by its name and content. Empty when nothing usable is inside. */
    fun pairs(name: String, text: String): Map<String, String> {
        val body = text.removePrefix("\uFEFF")
        val lower = name.lowercase()
        return when {
            // XML string tables (Hollow Knight, RDR2...) map ids to Turkish and hold no English to match against.
            body.trimStart().startsWith("<") -> emptyMap()
            lower.endsWith(".po") || body.contains("\nmsgid ") || body.startsWith("msgid ") -> po(body)
            lower.endsWith(".rpy") || body.contains("\ntranslate ") || body.startsWith("translate ") -> renpy(body)
            lower.endsWith(".csv") -> csv(body)
            lower.endsWith(".tsv") -> tsv(body)
            // A plain .txt is either "English<TAB>Turkish" or XUnity's "English=Turkish".
            else -> body.lineSequence().take(200).toList().let { lines ->
                if (lines.count { '\t' in it } >= lines.count { '=' in it }) tsv(body) else xunity(body)
            }
        }
    }

    /**
     * Pairs from a downloaded or uploaded file; a ZIP is opened and every readable file inside is used.
     * When the ZIP holds several languages, only the files whose path says Turkish are taken.
     */
    fun read(name: String, bytes: ByteArray): Map<String, String> {
        if (!isZip(bytes)) return pairs(name, text(bytes))
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var skipped = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                // Unread files are unpacked anyway on the way to the next one, so they are read here and counted.
                val out = if (entry.isDirectory || !readable(path)) null else ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    if (out == null) {
                        skipped += n
                        require(skipped <= MAX_SKIPPED) { "Arşivin içi çok büyük." }
                        continue
                    }
                    total += n
                    require(total <= MAX_UNZIPPED) { "Arşivin içi çok büyük." }
                    out.write(buffer, 0, n)
                }
                if (out == null) continue
                require(files.size < MAX_ZIP_FILES) { "Arşivde çok fazla dosya var." }
                files[path] = out.toByteArray()
            }
        }
        val turkish = files.keys.filter { TURKISH_PATH.containsMatchIn(it) }
        val pairs = LinkedHashMap<String, String>()
        turkish.ifEmpty { files.keys.toList() }.forEach { path ->
            pairs(path.substringAfterLast('/'), text(files.getValue(path))).forEach { (k, v) -> pairs.putIfAbsent(k, v) }
        }
        return pairs
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** Whether a file inside a ZIP may hold a translation, judged by its name. */
    fun readable(path: String) = READABLE.any { path.lowercase().endsWith(it) }

    /** UTF-8 unless the file starts with a UTF-16 mark, as some Windows tools write. */
    fun text(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }.removePrefix("\uFEFF")

    /** XUnity.AutoTranslator, common in Unity game patches: "English=Turkish", "\=" for an equals sign, "//" comments. */
    fun xunity(text: String): Map<String, String> {
        val pairs = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("//") || line.startsWith("r:") || line.startsWith("sr:")) continue
            var split = -1
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i += 2
                    '=' -> { split = i; break }
                    else -> i++
                }
            }
            if (split <= 0) continue
            val english = unescape(line.substring(0, split)).trim()
            val turkish = unescape(line.substring(split + 1)).trim()
            if (english.isNotEmpty() && turkish.isNotEmpty() && english != turkish) pairs.putIfAbsent(english, turkish)
        }
        return pairs
    }

    /** A table whose header row names the languages, as Unity's I2 Localization and many patch tools export. */
    fun csv(text: String): Map<String, String> {
        val first = text.lineSequence().firstOrNull() ?: return emptyMap()
        val separator = listOf(',', ';', '\t').maxBy { c -> first.count { it == c } }
        val rows = csvRows(text, separator)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyMap()
        val en = header.indexOfFirst { it in ENGLISH_COLUMNS || it.startsWith("english") }
        val tr = header.indexOfFirst { it in TURKISH_COLUMNS || it.startsWith("turkish") || it.startsWith("türkçe") }
        if (en < 0 || tr < 0) return emptyMap()
        val pairs = LinkedHashMap<String, String>()
        rows.drop(1).forEach { row ->
            val english = row.getOrNull(en)?.trim().orEmpty()
            val turkish = row.getOrNull(tr)?.trim().orEmpty()
            if (english.isNotEmpty() && turkish.isNotEmpty()) pairs.putIfAbsent(english, turkish)
        }
        return pairs
    }

    private fun csvRows(text: String, separator: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == separator -> { row.add(cell.toString()); cell.clear() }
                c == '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                c != '\r' -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    /**
     * Ren'Py's translation files: a dialogue block keeps the English as a comment above the Turkish line,
     * and the strings block pairs "old" with "new". Only blocks for Turkish are read.
     */
    fun renpy(text: String): Map<String, String> {
        val pairs = LinkedHashMap<String, String>()
        var turkish = false
        var english: String? = null
        var old: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            TRANSLATE.find(line)?.let { match ->
                val language = match.groupValues[1].lowercase()
                turkish = language == "tr" || language.startsWith("tur")
                english = null
                old = null
            }
            if (!turkish) continue
            val said = SAY.find(line)?.let { renpyText(it.groupValues[1]) }
            when {
                line.startsWith("old ") -> old = said
                line.startsWith("new ") -> {
                    val from = old
                    if (!from.isNullOrBlank() && !said.isNullOrBlank()) pairs.putIfAbsent(from, said)
                    old = null
                }
                line.startsWith("#") -> COMMENTED_SAY.find(line)?.let { english = renpyText(it.groupValues[1]) }
                said != null -> {
                    val from = english
                    if (!from.isNullOrBlank() && said.isNotBlank()) pairs.putIfAbsent(from, said)
                    english = null
                }
            }
        }
        return pairs
    }

    private fun renpyText(text: String) = unescape(text).replace(RENPY_TAG, "").replace(SPACES, " ").trim()

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(when (val next = text[i + 1]) { 'n', 't', 'r' -> ' '; else -> next })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun tsv(text: String): Map<String, String> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }
        .filter { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        .associate { it[0].trim() to it[1].trim() }

    /** gettext: msgid is the English, msgstr (or msgstr[0]) the Turkish; untranslated and "fuzzy" entries are left out. */
    fun po(text: String): Map<String, String> {
        val pairs = LinkedHashMap<String, String>()
        val id = StringBuilder()
        val str = StringBuilder()
        var target: StringBuilder? = null
        var fuzzy = false
        var seenStr = false
        fun flush() {
            if (!fuzzy && id.isNotBlank() && str.isNotBlank()) pairs[id.toString()] = str.toString()
            id.clear()
            str.clear()
            target = null
            fuzzy = false
            seenStr = false
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            // An entry ends at a blank line, or where the next one's comments, context or msgid begin.
            val nextEntry = line.isEmpty() || line.startsWith("#") || line.startsWith("msgctxt") ||
                (line.startsWith("msgid") && !line.startsWith("msgid_plural"))
            if (seenStr && nextEntry) flush()
            when {
                line.startsWith("#,") -> if ("fuzzy" in line) fuzzy = true
                line.isEmpty() || line.startsWith("#") || line.startsWith("msgctxt") || line.startsWith("msgid_plural") -> target = null
                line.startsWith("msgid") -> { target = id; id.append(quoted(line)) }
                line.startsWith("msgstr[0]") || line.startsWith("msgstr ") -> { seenStr = true; target = str; str.append(quoted(line)) }
                line.startsWith("msgstr") -> { seenStr = true; target = null }
                line.startsWith("\"") -> target?.append(quoted(line))
            }
        }
        flush()
        return pairs
    }

    private fun quoted(line: String): String {
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"')
        if (start < 0 || end <= start) return ""
        return unescape(line.substring(start + 1, end))
    }

    /** Our own file: a JSON line about the pack, then "English<TAB>Turkish" lines. */
    fun write(info: PackInfo, pairs: Map<String, String>): String = buildString {
        append(HEADER).append(info.toJson().toString()).append('\n')
        pairs.forEach { (english, turkish) ->
            append(english.replace(SPACES, " ")).append('\t').append(turkish.replace(SPACES, " ")).append('\n')
        }
    }

    fun info(firstLine: String): PackInfo? =
        firstLine.takeIf { it.startsWith(HEADER) }?.let { runCatching { PackInfo.fromJson(JSONObject(it.removePrefix(HEADER))) }.getOrNull() }
}

/** The packs kept on the TV and which one the reader uses now. */
internal object PackStore {
    const val ACTIVE_KEY = "paket"
    const val MAX_BYTES = 30 * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "paketler").apply { mkdirs() }

    fun list(context: Context): List<PackInfo> = dir(context).listFiles { f -> f.name.endsWith(".tsv") }.orEmpty()
        .mapNotNull { file -> file.bufferedReader().use { PackFormat.info(it.readLine() ?: "") } }
        .sortedBy { it.game.lowercase() }

    /** Turns a downloaded or uploaded file into a pack; throws with a Turkish message when it holds no translation. */
    fun add(
        context: Context, fileName: String, bytes: ByteArray, game: String, credit: String, source: String, license: String,
        donate: String = "",
    ): PackInfo {
        val pairs = PackFormat.read(fileName, bytes)
        require(pairs.isNotEmpty()) {
            "Dosyada İngilizce–Türkçe eşleşen satır yok. TV şunları okur: .po, .tsv, İngilizce ve Türkçe sütunlu .csv, " +
                "XUnity .txt, Ren'Py .rpy ve bunları içeren .zip. Yalnız Türkçe metin tutan yamalar (ör. RDR2) eşleştirilemez."
        }
        val name = game.trim().ifEmpty { fileName.substringBeforeLast('.') }.take(60)
        val info = PackInfo(
            PackInfo.idOf(name, credit), name, credit.trim().take(80), webLink(source), license.take(40), pairs.size, webLink(donate),
        )
        File(dir(context), "${info.id}.tsv").writeText(PackFormat.write(info, pairs))
        return info
    }

    fun load(context: Context, id: String): GamePack? {
        if (id.isEmpty()) return null
        val file = File(dir(context), "$id.tsv").takeIf { it.exists() } ?: return null
        val lines = file.readLines()
        val info = PackFormat.info(lines.firstOrNull() ?: "") ?: return null
        return GamePack(info, PackFormat.tsv(lines.drop(1).joinToString("\n")))
    }

    fun remove(context: Context, prefs: SharedPreferences, id: String) {
        if (id.isEmpty() || id.contains('/') || id.contains("..")) return
        File(dir(context), "$id.tsv").delete()
        if (active(prefs) == id) select(prefs, "")
    }

    fun active(prefs: SharedPreferences) = prefs.getString(ACTIVE_KEY, "") ?: ""

    fun select(prefs: SharedPreferences, id: String) = prefs.edit().putString(ACTIVE_KEY, id).apply()

    fun listJson(context: Context, prefs: SharedPreferences): String = JSONObject()
        .put("active", active(prefs))
        .put("packs", JSONArray(list(context).map { it.toJson() }))
        .toString()
}
