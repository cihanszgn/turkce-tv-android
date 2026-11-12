package dev.turkce.olcum

import java.util.Locale

private const val WORD_START = "(?<![\\p{L}\\p{N}])"
private const val WORD_END = "(?![\\p{L}\\p{N}])"
private val SPACES = Regex("\\s+")
private val LOOSE_COMMA = Regex("^[ ,]+| +(?=[,.!?])")

/** Rewrites street English as plain English, because the tiny model only learned normal sentences. */
class Slang(private val rules: List<Pair<Regex, String>>) {
    fun plain(line: String): String {
        val plain = rules.fold(line.replace('’', '\'')) { text, (pattern, word) -> pattern.replace(text, word) }
            .replace(SPACES, " ").replace(LOOSE_COMMA, "").trim()
        if (plain.isEmpty()) return line
        return if (line.first().isUpperCase()) plain.replaceFirstChar { it.titlecase(Locale.ROOT) } else plain
    }

    companion object {
        val NONE = Slang(emptyList())

        /**
         * One "pattern<TAB>plain words" rule per line, applied in order; a pattern alone deletes what it matches.
         * Blank lines and # comments are skipped.
         */
        fun parse(lines: Sequence<String>) = Slang(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t', limit = 2) }
                .map { Regex(WORD_START + "(?:" + it[0] + ")" + WORD_END, RegexOption.IGNORE_CASE) to it.getOrElse(1) { "" } }
                .toList(),
        )
    }
}
