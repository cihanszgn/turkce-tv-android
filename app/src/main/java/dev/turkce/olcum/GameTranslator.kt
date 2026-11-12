package dev.turkce.olcum

import java.util.Locale

private const val CACHE_LINES = 500
private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}' ]")
private val SPACES = Regex("\\s+")
private val SPEAKER = Regex("^(\\p{L}[\\p{L} .'-]{1,24}):\\s+(.+)$")

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

    /** The human translation of the game being played; swapped while the game runs when the player picks another. */
    @Volatile var pack: GamePack = GamePack.EMPTY

    fun knows(line: String): Boolean {
        val words = SPEAKER.find(line)?.groupValues?.get(2) ?: line
        return phrases.containsKey(key(words)) || pack.find(words) != null
    }

    override fun translate(source: String): String {
        val speaker = SPEAKER.find(source) ?: return speech(source)
        return "${speaker.groupValues[1]}: ${speech(speaker.groupValues[2])}"
    }

    private fun known(line: String) = phrases[key(line)] ?: pack.find(line)

    private fun speech(line: String): String {
        known(line)?.let { return it }
        val key = key(line)
        cache[key]?.let { return it }
        val sentences = line.split(SENTENCE_END)
        val parts = sentences.map(::known)
        val turkish = if (sentences.size > 1 && parts.any { it != null }) {
            sentences.indices.joinToString(" ") { parts[it] ?: ask(sentences[it]) }
        } else {
            ask(line)
        }
        cache[key] = turkish
        return turkish
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
