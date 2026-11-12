package dev.turkce.olcum

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val API = "https://api.github.com"
private const val SITE = "https://github.com"
private const val RAW = "https://raw.githubusercontent.com"
private const val FILE_LIST = "https://data.jsdelivr.com/v1/packages/gh"
private const val CONNECT_MS = 15_000
private const val READ_MS = 30_000
private const val MAX_JSON = 4 * 1024 * 1024
private const val MAX_RESULTS = 12
private const val MAX_FILES = 20
/** A repository whose first files are all unreadable is not worth more lookups during a search. */
private const val SEARCH_TRIES = 8
/** How much of a file is read to see whether the TV understands it. */
private const val PEEK = 32 * 1024
private const val MIN_PAIRS = 2
private const val CHECK_SECONDS = 45L
private const val REMEMBER_MS = 15 * 60_000L
/** "owner/name" as GitHub writes it; "." and ".." are not names, so a path cannot climb out of the repository. */
private val REPO = Regex("(?!\\.{1,2}/)[A-Za-z0-9_.-]{1,100}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}")
/** A git branch name: no "..", no "//" or "/.", not starting with "." or "/", as git itself requires. */
private val BRANCH = Regex("(?!.*(\\.\\.|//|/\\.))(?![./])[A-Za-z0-9_./-]{1,100}")
private val TAG = Regex("[A-Za-z0-9_.+-]{1,100}")
private val TAG_LINK = Regex("/releases/tag/([^\"<>?#]{1,200})\"")
private val SMALL_WORDS = setOf("the", "of", "a", "an", "and", "ve")
private val TURKISH_WORD = Regex("(^| )(tr|turk\\w*|yama\\w*|ceviri\\w*)( |$)")
private val MARKS = Regex("\\p{M}")
private val NOT_WORD = Regex("[^a-z0-9]+")
private val PACK_FILES = listOf(".po", ".tsv", ".csv", ".txt", ".rpy", ".zip")
private val NOT_TRANSLATIONS = Regex("(^|/)(readme|license|licence|changelog|requirements|cmakelists|notice)[^/]*$", RegexOption.IGNORE_CASE)
private val RELEASE_PATH = Regex("release/([^/]{1,100})/([^/]{1,200})")
private val FUNDING_SITES = mapOf(
    "github" to "https://github.com/sponsors/", "patreon" to "https://www.patreon.com/", "ko_fi" to "https://ko-fi.com/",
    "buy_me_a_coffee" to "https://www.buymeacoffee.com/", "liberapay" to "https://liberapay.com/",
    "open_collective" to "https://opencollective.com/", "custom" to "",
)

/** A repository that may hold a Turkish translation; [open] only tells the phone whether a licence is stated. */
data class PatchRepo(
    val repo: String, val description: String, val stars: Int, val updated: String,
    val license: String, val url: String, val branch: String,
) {
    val open get() = license.isNotEmpty() && license != "NOASSERTION" && license != "other"

    fun toJson(): JSONObject = JSONObject().put("repo", repo).put("description", description).put("stars", stars)
        .put("updated", updated).put("license", license).put("open", open).put("url", url).put("branch", branch)

    companion object {
        /** A repository from GitHub's answer; the name must be a plain "owner/name" and the page must be on github.com. */
        fun fromJson(json: JSONObject): PatchRepo {
            val repo = json.getString("full_name")
            require(REPO.matches(repo)) { "Depo adı geçersiz." }
            val page = json.optString("html_url")
            return PatchRepo(
                repo, json.optString("description").takeUnless { it == "null" } ?: "",
                json.optInt("stargazers_count"), json.optString("pushed_at").take(10),
                json.optJSONObject("license")?.optString("spdx_id")?.takeUnless { it == "null" } ?: "",
                if (page.startsWith("$SITE/") && webLink(page) == page) page else "$SITE/$repo",
                json.optString("default_branch", "main"),
            )
        }
    }
}

/** A file in a repository, or "release/<tag>/<name>" for one attached to a release. */
data class PatchFile(val path: String, val size: Long) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("size", size)
}

/**
 * Finds Turkish translations on GitHub. The TV downloads straight from the translators' own repository, so the files
 * never pass through us, and the pack keeps their name, page and donation link. Only translations the TV can actually
 * read are offered: the start of each file is fetched and parsed before it is listed.
 */
internal object YamaArama {
    private val pool = Executors.newFixedThreadPool(8) { Thread(it, "yama-kontrol").apply { isDaemon = true } }
    private val known = ConcurrentHashMap<String, PatchRepo>()
    private val checked = ConcurrentHashMap<String, Pair<Long, List<PatchFile>>>()

    /** Repositories that name the game, say Turkish and hold at least one file the TV can read. */
    fun search(game: String): List<PatchRepo> {
        val name = game.trim().take(80)
        require(name.length >= 2) { "Oyun adını yaz." }
        val found = LinkedHashMap<String, PatchRepo>()
        for (words in listOf("türkçe", "turkish", "yama")) {
            val query = URLEncoder.encode("$name $words in:name,description,readme", "UTF-8")
            val items = JSONObject(String(get("$API/search/repositories?q=$query&per_page=30", MAX_JSON)))
                .optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                runCatching { PatchRepo.fromJson(items.getJSONObject(i)) }.getOrNull()?.let { found.putIfAbsent(it.repo, it) }
            }
        }
        val ranked = rank(name, found.values)
        ranked.forEach { known[it.repo] = it }
        // Each task looks at one repository on its own and never waits on the pool, so the pool cannot deadlock.
        val readable = ranked.map { repo -> pool.submit<Boolean> { runCatching { hasReadable(repo) }.getOrDefault(false) } }
        return ranked.filterIndexed { i, _ -> runCatching { readable[i].get(CHECK_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    /**
     * GitHub also matches words anywhere in a README, which brings up link lists and unrelated projects. Only repositories
     * whose name or description names the game are kept; those that also say Turkish come first.
     */
    fun rank(game: String, repos: Collection<PatchRepo>): List<PatchRepo> {
        val all = fold(game).split(' ').filter { it.isNotEmpty() }
        val words = all.filter { it !in SMALL_WORDS }.ifEmpty { all }
        if (words.isEmpty()) return emptyList()
        // "Red Dead Redemption 2" is also written "rdr2" or "reddeadredemption2", "0 A.D." as "0ad".
        val short = setOf(words.joinToString("") { if (it.all(Char::isDigit)) it else it.take(1) }, all.joinToString(""))
            .filter { it.length >= 3 }
        return repos.mapNotNull { repo ->
            val text = fold("${repo.repo.replace('/', ' ')} ${repo.description}")
            val tokens = text.split(' ').toSet()
            val namesGame = words.all { it in tokens } || short.any { it in tokens }
            if (!namesGame) null else repo to (if (TURKISH_WORD.containsMatchIn(text)) 1 else 0)
        }
            .sortedWith(compareByDescending<Pair<PatchRepo, Int>> { it.second }
                .thenByDescending { it.first.stars }.thenByDescending { it.first.updated })
            .map { it.first }.take(MAX_RESULTS)
    }

    /** Lower case, Turkish letters to plain ones, everything else to single spaces; "RDR2-Türkçe" becomes "rdr2 turkce". */
    private fun fold(text: String) = Normalizer.normalize(text.replace('ı', 'i').replace('İ', 'i'), Normalizer.Form.NFD)
        .replace(MARKS, "").lowercase().replace(NOT_WORD, " ").trim()

    /** Ordinary web searches for translations kept outside GitHub; the phone opens them, the TV downloads nothing. */
    fun links(game: String): JSONArray {
        val words = URLEncoder.encode("${game.trim()} türkçe yama", "UTF-8")
        return JSONArray()
            .put(JSONObject().put("label", "Google'da Türkçe yama ara").put("url", "https://www.google.com/search?q=$words"))
            .put(JSONObject().put("label", "GitHub'da ara").put("url", "https://github.com/search?type=repositories&q=$words"))
    }

    fun repo(repo: String): PatchRepo {
        require(REPO.matches(repo)) { "Depo adı geçersiz." }
        known[repo]?.let { return it }
        return PatchRepo.fromJson(JSONObject(String(get("$API/repos/$repo", MAX_JSON)))).also { known[repo] = it }
    }

    /** The files of a repository the TV has read the start of and understood. */
    fun files(repo: PatchRepo): JSONArray = JSONArray(readable(repo).map { it.toJson() })

    private fun readable(repo: PatchRepo): List<PatchFile> {
        checked[repo.repo]?.let { (at, files) -> if (System.currentTimeMillis() - at < REMEMBER_MS) return files }
        // Called from the settings server's own thread, so waiting on the pool here is safe.
        val checks = candidates(repo).map { file -> pool.submit<PatchFile?> { check(repo, file) } }
        val files = checks.mapNotNull { runCatching { it.get(CHECK_SECONDS, TimeUnit.SECONDS) }.getOrNull() }
        checked[repo.repo] = System.currentTimeMillis() to files
        return files
    }

    private fun hasReadable(repo: PatchRepo): Boolean {
        checked[repo.repo]?.let { (at, files) -> if (System.currentTimeMillis() - at < REMEMBER_MS) return files.isNotEmpty() }
        return candidates(repo).take(SEARCH_TRIES).any { check(repo, it) != null }
    }

    /**
     * Files that may hold a translation, by name: those in the repository and those attached to its two latest releases,
     * where patch groups usually put the finished patch. In a repository that is not itself Turkish, only Turkish-looking
     * paths are taken. The lists come from jsDelivr and GitHub's release feed, which do not count against GitHub's small
     * hourly allowance for apps without an account.
     */
    private fun candidates(repo: PatchRepo): List<PatchFile> {
        require(BRANCH.matches(repo.branch)) { "Dal adı geçersiz." }
        val turkishRepo = TURKISH_PATH.containsMatchIn(repo.repo) || TURKISH_PATH.containsMatchIn(repo.description)
        val all = runCatching { releaseFiles(repo) }.getOrDefault(emptyList()) + treeFiles(repo)
        return all
            .filter { f -> PACK_FILES.any { f.path.lowercase().endsWith(it) } && !NOT_TRANSLATIONS.containsMatchIn(f.path) }
            .filter { it.size <= PackStore.MAX_BYTES }
            .filter { turkishRepo || it.path.startsWith("release/") || TURKISH_PATH.containsMatchIn(it.path) }
            .sortedByDescending { TURKISH_PATH.containsMatchIn(it.path) }
            .take(MAX_FILES)
    }

    private fun treeFiles(repo: PatchRepo): List<PatchFile> = runCatching {
        val files = JSONObject(String(get("$FILE_LIST/${repo.repo}@${repo.branch}?structure=flat", MAX_JSON))).getJSONArray("files")
        (0 until files.length()).map { files.getJSONObject(it) }
            .map { PatchFile(it.optString("name").removePrefix("/"), it.optLong("size")) }
    }.getOrElse {
        val tree = JSONObject(String(get("$API/repos/${repo.repo}/git/trees/${repo.branch}?recursive=1", MAX_JSON)))
            .optJSONArray("tree") ?: JSONArray()
        (0 until tree.length()).map { tree.getJSONObject(it) }
            .filter { it.optString("type") == "blob" }
            .map { PatchFile(it.optString("path"), it.optLong("size")) }
    }

    private fun releaseFiles(repo: PatchRepo): List<PatchFile> {
        val feed = String(get("$SITE/${repo.repo}/releases.atom", MAX_JSON, "application/atom+xml"), Charsets.UTF_8)
        val tags = TAG_LINK.findAll(feed).map { decode(it.groupValues[1]) }.filter { TAG.matches(it) }.distinct().take(2).toList()
        val asset = Regex("href=\"/${Regex.escape(repo.repo)}/releases/download/([^\"/]+)/([^\"/]+)\"", RegexOption.IGNORE_CASE)
        return tags.flatMap { tag ->
            val page = String(get("$SITE/${repo.repo}/releases/expanded_assets/${encode(tag)}", MAX_JSON, "text/html"), Charsets.UTF_8)
            asset.findAll(page).map { "release/${decode(it.groupValues[1])}/${decode(it.groupValues[2])}" }
                .distinct().map { PatchFile(it, 0) }.toList()
        }
    }

    /** The file with its size when the TV understands its beginning, otherwise null. */
    private fun check(repo: PatchRepo, file: PatchFile): PatchFile? = runCatching {
        val url = fileUrl(repo, file.path)
        val name = file.path.substringAfterLast('/')
        if (name.lowercase().endsWith(".zip")) return@runCatching checkZip(url, file)
        val (bytes, total) = peek(url, 0, PEEK - 1L)
        val text = PackFormat.text(bytes).let { if (bytes.size < total) it.substringBeforeLast('\n') else it }
        if (PackFormat.pairs(name, text).size >= MIN_PAIRS) file.copy(size = if (file.size > 0) file.size else total) else null
    }.getOrNull()

    /** Reads the list of files at the end of the ZIP, then the start of up to three translation files inside it. */
    private fun checkZip(url: String, file: PatchFile): PatchFile? {
        val total = if (file.size > 0) file.size else peek(url, 0, 0).second
        if (total <= 0 || total > PackStore.MAX_BYTES) return null
        val tailStart = maxOf(0L, total - ZipSample.TAIL)
        val tail = peek(url, tailStart, total - 1).first
        val range = ZipSample.directory(tail) ?: return null
        val directory = if (range.first >= tailStart) {
            tail.copyOfRange((range.first - tailStart).toInt(), minOf(tail.size.toLong(), range.last + 1 - tailStart).toInt())
        } else {
            peek(url, range.first, minOf(range.last, range.first + MAX_JSON - 1)).first
        }
        val readable = ZipSample.entries(directory).filter { PackFormat.readable(it.name) }
        val turkish = readable.filter { TURKISH_PATH.containsMatchIn(it.name) }
        for (entry in turkish.ifEmpty { readable }.take(3)) {
            // The local header may carry more extra bytes than the list says, hence the margin.
            val end = minOf(total - 1, entry.offset + 30 + entry.nameBytes + 1024 + minOf(entry.compressed, PEEK.toLong()))
            val content = ZipSample.content(peek(url, entry.offset, end).first, entry, 2 * PEEK)
            val text = PackFormat.text(content).substringBeforeLast('\n')
            if (PackFormat.pairs(entry.name.substringAfterLast('/'), text).size >= MIN_PAIRS) return file.copy(size = total)
        }
        return null
    }

    /** The file as published by its translators: a file in the repository, or "release/<tag>/<name>" for a release file. */
    fun download(repo: PatchRepo, path: String): ByteArray = get(fileUrl(repo, path), PackStore.MAX_BYTES, "*/*")

    private fun fileUrl(repo: PatchRepo, path: String): String {
        require(BRANCH.matches(repo.branch)) { "Dal adı geçersiz." }
        require(path.isNotBlank() && ".." !in path && !path.startsWith("/")) { "Dosya yolu geçersiz." }
        RELEASE_PATH.matchEntire(path)?.let { release ->
            val tag = release.groupValues[1]
            require(TAG.matches(tag)) { "Yayın adı geçersiz." }
            // Built under this repository's own address, so it cannot point anywhere else.
            return "$SITE/${repo.repo}/releases/download/${encode(tag)}/${encode(release.groupValues[2])}"
        }
        return "$RAW/${repo.repo}/${repo.branch}/" + path.split('/').joinToString("/") { encode(it) }
    }

    private fun encode(part: String) = URLEncoder.encode(part, "UTF-8").replace("+", "%20")
    private fun decode(part: String) = URLDecoder.decode(part.replace("+", "%2B"), "UTF-8")

    /** Where the translators take donations, from the repository's FUNDING.yml; empty when they have none. */
    fun funding(repo: PatchRepo): String = runCatching {
        val text = String(get("$RAW/${repo.repo}/${repo.branch}/.github/FUNDING.yml", 64 * 1024), Charsets.UTF_8)
        fundingLink(text)
    }.getOrDefault("")

    fun fundingLink(yaml: String): String {
        for (raw in yaml.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            val key = line.substringBefore(':').trim()
            val prefix = FUNDING_SITES[key] ?: continue
            val value = line.substringAfter(':', "").trim().removePrefix("[").removeSuffix("]")
                .split(',').map { it.trim().trim('"', '\'') }.firstOrNull { it.isNotEmpty() && it != "~" && it != "null" } ?: continue
            val link = webLink(if (prefix.isEmpty()) value else prefix + value)
            if (link.isNotEmpty()) return link
        }
        return ""
    }

    /** Only https, also after redirects; anything else is refused before a byte of it is read. */
    private fun open(url: String, accept: String, range: String? = null): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("Yalnız güvenli (https) bağlantı kullanılır.")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_MS
        connection.readTimeout = READ_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "turkce-tv")
        connection.setRequestProperty("Accept", accept)
        range?.let { connection.setRequestProperty("Range", it) }
        try {
            val code = connection.responseCode
            if (connection.url.protocol != "https") throw IOException("Yalnız güvenli (https) bağlantı kullanılır.")
            when (code) {
                in 200..299 -> {}
                403, 429 -> throw IOException("GitHub şu an çok istek aldı; bir dakika sonra tekrar dene.")
                404 -> throw IOException("Bulunamadı.")
                else -> throw IOException("GitHub yanıt vermedi ($code).")
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        return connection
    }

    /** Bytes [from]..[to] of a file and the size of the whole file. */
    private fun peek(url: String, from: Long, to: Long): Pair<ByteArray, Long> {
        val connection = open(url, "*/*", "bytes=$from-$to")
        try {
            val partial = connection.responseCode == 206
            // A server that ignores the range sends the file from its start, which is only usable when that is what was asked.
            if (!partial && from > 0) throw IOException("Parça okunamadı.")
            val total = connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: connection.contentLengthLong
            return read(connection, (to - from + 1).toInt(), stopAtLimit = true) to total
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, limit: Int, accept: String = "application/vnd.github+json"): ByteArray {
        val connection = open(url, accept)
        try {
            // A file that says up front it is too big is not read at all; the reading below stops at the limit anyway.
            if (connection.contentLengthLong > limit) throw IOException("Dosya çok büyük.")
            return read(connection, limit, stopAtLimit = false)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection, limit: Int, stopAtLimit: Boolean): ByteArray =
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer, 0, if (stopAtLimit) minOf(buffer.size, limit - out.size()) else buffer.size)
                if (n <= 0) break
                out.write(buffer, 0, n)
                if (out.size() >= limit) {
                    if (stopAtLimit) break
                    if (out.size() > limit) throw IOException("Dosya çok büyük.")
                }
            }
            out.toByteArray()
        }
}
