package dev.turkce.olcum

import java.util.Locale

private const val CACHE_LINES = 500
private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}' ]")
private val SPACES = Regex("\\s+")
private val SPEAKER = Regex("^(\\p{L}[\\p{L} .'-]{1,24}):\\s+(.+)$")

/** Where a Turkish line came from; the phone panel counts them, and pack lines get a mark on the TV. */
enum class LineSource(val key: String, val label: String) {
    PACK("paket", "oyun paketi"),
    PHRASES("sozluk", "yerleşik sözlük"),
    MEMORY("bellek", "bellek"),
    MACHINE("makine", "makine çevirisi"),
    ;

    /** Written by a person: shown even when it looks much like the English (names, short words). */
    val human get() = this == PACK || this == PHRASES
}

/** A translated line and where it came from. */
data class Translated(val text: String, val source: LineSource)

/**
 * Puts a hand-written game phrase book and a memory of past lines in front of the model:
 * a known line costs nothing, and the phrase book fixes what the tiny model gets wrong.
 */
class GameTranslator(
    private val model: LineTranslator,
    private val phrases: Map<String, String>,
    private val slang: Slang = Slang.NONE,
) : LineTranslator {
    private val cache = object : LinkedHashMap<String, String>(CACHE_LINES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > CACHE_LINES
    }

    /** The pack the remembered lines were made with; a new pack makes them stale, so they are forgotten. */
    private var cachedWith: GamePack = GamePack.EMPTY

    /** The human translation of the game being played; swapped while the game runs when the player picks another. */
    @Volatile var pack: GamePack = GamePack.EMPTY

    fun knows(line: String): Boolean {
        val words = SPEAKER.find(line)?.groupValues?.get(2) ?: line
        return phrases.containsKey(key(words)) || pack.find(words) != null
    }

    override fun translate(source: String): String = translateWithSource(source).text

    /** The Turkish line and where it came from: the game pack, the phrase book, the memory of past lines or the model. */
    fun translateWithSource(source: String): Translated {
        val speaker = SPEAKER.find(source) ?: return speech(source)
        val said = speech(speaker.groupValues[2])
        return said.copy(text = "${speaker.groupValues[1]}: ${said.text}")
    }

    /** The game's own human translation first: it is made for this very game, the phrase book for every game. */
    private fun known(line: String, pack: GamePack): Translated? {
        pack.find(line)?.let { return Translated(it, LineSource.PACK) }
        return phrases[key(line)]?.let { Translated(it, LineSource.PHRASES) }
    }

    @Synchronized
    private fun speech(line: String): Translated {
        val pack = pack
        if (pack !== cachedWith) {
            cache.clear()
            cachedWith = pack
        }
        known(line, pack)?.let { return it }
        val key = key(line)
        cache[key]?.let { return Translated(it, LineSource.MEMORY) }
        val sentences = line.split(SENTENCE_END)
        val parts = sentences.map { known(it, pack) }
        if (sentences.size > 1 && parts.all { it != null }) {
            // Every sentence is a human line: nothing is asked or remembered, so the line keeps its true source.
            val source = if (parts.any { it!!.source == LineSource.PACK }) LineSource.PACK else LineSource.PHRASES
            return Translated(parts.joinToString(" ") { it!!.text }, source)
        }
        val turkish = if (sentences.size > 1 && parts.any { it != null }) {
            sentences.indices.joinToString(" ") { parts[it]?.text ?: ask(sentences[it]) }
        } else {
            ask(line)
        }
        cache[key] = turkish
        return Translated(turkish, LineSource.MACHINE)
    }

    private fun ask(line: String) = model.translate(slang.plain(calm(line)))

    override fun close() = model.close()

    companion object {
        fun key(line: String) = line.lowercase(Locale.ROOT).replace('’', '\'').replace(NOT_WORD, " ")
            .replace(SPACES, " ").trim()

        /** The model was trained on normal sentences, so a line in capitals goes in as one. */
        fun calm(line: String) = if (line.any { it.isLowerCase() }) {
            line
        } else {
            line.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        /** One "English<TAB>Turkish" pair per line; blank lines and # comments are skipped. */
        fun parse(lines: Sequence<String>): Map<String, String> = lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t', limit = 2) }
            .filter { it.size == 2 }
            .associate { key(it[0]) to it[1].trim() }
    }
}
