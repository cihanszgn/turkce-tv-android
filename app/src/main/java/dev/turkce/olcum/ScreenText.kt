package dev.turkce.olcum

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MIN_LETTER_SHARE = 0.7
private const val SAME_LINE_SHARE = 0.8
private const val CONFIRM_SCANS = 2
private const val MAX_MISSED = 2
private const val MAX_BOXES = 3
private const val MERGE_LEFT_PX = 40
private const val MERGE_GAP_LINES = 1.5
private const val MIN_PANEL_PX = 520
private const val PANEL_GAP_PX = 8
private const val PANEL_EDGE_PX = 16
private const val BELOW_LIMIT = 0.75
private const val CUT_EDGE_PX = 4
private const val MAX_CREDIT_WORDS = 5
private const val CAPITALS_SHARE = 0.5
private const val TURKISH_ONLY = "ğĞİşŞ"
private const val SPEECH_MARKS = ".!?,:"
private val EVERYWHERE = ScreenBlock(0, 0, Int.MAX_VALUE, Int.MAX_VALUE, "")
private const val MAX_INTRO_WORDS = 7
private val INTRO_ALWAYS = Regex(
    "[©®™]|\\(c\\)\\s*\\d{4}|all rights reserved|\\bcopyright|\\btrademark|\\bepilep|photosensitiv|\\bseizure|" +
        "press any (button|key)|\\bauto-?sav|\\bpowered by\\b|\\bwww\\.|https?://|\\blicen[cs]ed (to|by|from|under)\\b|" +
        "\\bdo not (turn off|switch off|power off)\\b",
)
/** Job titles in credits; speech uses them too, so only in a line with no punctuation at all. */
private val INTRO_ROLE = Regex("\\b(producer|director|designer|composer|programmer|animator|cast|credits)s?\\b")
private val INTRO_SHORT = Regex(
    "\\bpresents?\\b|\\bproduction\\b|\\bin association with\\b|" +
        "\\b(developed|published|produced|directed|written|created|designed|composed|distributed) by\\b|\\bmusic by\\b|" +
        "\\b(studios?|entertainment|interactive|games|inc|ltd|llc|gmbh|corporation|corp|co)\\.?\\s*$",
)

/** A piece of text on the 1920x1080 game picture. */
data class ScreenBlock(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String) {
    val height: Int get() = bottom - top

    fun overlaps(other: ScreenBlock) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** False too for text that touches the far edges of [area]: that text was cut by the scan. */
    fun inside(area: ScreenBlock) =
        left >= area.left && top >= area.top && right <= area.right - CUT_EDGE_PX && bottom <= area.bottom - CUT_EDGE_PX
}

/** Where a Turkish panel goes. With [fromBottom], [y] is measured up from the bottom edge. */
data class PanelPlace(val x: Int, val y: Int, val fromBottom: Boolean, val maxWidth: Int)

object ScreenText {
    private fun letterShare(line: String) =
        line.count { it.isLetter() || it.isWhitespace() }.toDouble() / max(line.length, 1)

    private fun words(line: String, letters: Int) =
        line.split(' ').count { word -> word.count { it.isLetter() } >= letters }

    private fun lettered(line: String) = line.split(' ').filter { word -> word.count { it.isLetter() } >= 2 }

    private fun isCapitals(word: String) = word.none { it.isLowerCase() }

    /** PS5 notifications come in the console's language. The dotless i is left out: OCR writes it for English i. */
    fun isTurkish(line: String) = line.any { it in TURKISH_ONLY }

    /** Film credits and name lists: a few words in capitals, with no punctuation. */
    fun isCredit(line: String) =
        lettered(line).size in 2..MAX_CREDIT_WORDS && isCapitals(line) && line.none { it in SPEECH_MARKS }

    /**
     * What a game shows before it starts: studio names, legal notices, health warnings, "press any button" and
     * film credits. Words that also occur in speech ("presents", "studio") count only in a short line without
     * a question or an exclamation.
     */
    fun isIntro(line: String): Boolean {
        val text = line.lowercase()
        if (INTRO_ALWAYS.containsMatchIn(text)) return true
        // Credits rolling over the first scenes: names and jobs in capitals, with no punctuation.
        if (lettered(line).isNotEmpty() && line.none { it in SPEECH_MARKS } && isMostlyCapitals(line)) return true
        if (lettered(line).size > MAX_INTRO_WORDS || line.any { it in "!?" }) return false
        return INTRO_SHORT.containsMatchIn(text) || line.none { it in SPEECH_MARKS } && INTRO_ROLE.containsMatchIn(text)
    }

    /** Number plates, scores and other things OCR finds around a subtitle. */
    fun isNoise(line: String) =
        letterShare(line) < MIN_LETTER_SHARE || words(line, 3) == 0 || isCredit(line) || isTurkish(line)

    fun looksLikeSpeech(line: String) = letterShare(line) >= MIN_LETTER_SHARE && words(line, 2) >= 2

    private fun isMostlyCapitals(line: String): Boolean {
        val words = lettered(line)
        return words.count(::isCapitals) >= words.size * CAPITALS_SHARE
    }

    /** Boxes need more proof than subtitles: signs, menus and map names are also short text. */
    fun isInfoText(line: String) = letterShare(line) >= MIN_LETTER_SHARE && words(line, 2) >= 3 &&
        !isMostlyCapitals(line) && !isTurkish(line)

    /** The model hands back what it could not read, so a near copy means there is nothing to show. */
    fun barelyChanged(source: String, turkish: String) = sameLine(source, turkish)

    /** True for two reads of one line. OCR rarely reads a line twice the same way. */
    fun sameLine(first: String, second: String): Boolean {
        val a = first.lowercase()
        val b = second.lowercase()
        if (a == b) return true
        val longest = max(a.length, b.length)
        return 1.0 - distance(a, b).toDouble() / longest >= SAME_LINE_SHARE
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val swap = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(swap, min(previous[j], current[j - 1]) + 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** Joins blocks stacked under one another with the same left edge: one info box, several blocks. */
    fun mergeBlocks(blocks: List<ScreenBlock>): List<ScreenBlock> {
        val merged = ArrayList<ScreenBlock>()
        for (block in blocks.sortedBy { it.top }) {
            val index = merged.indexOfFirst { above ->
                val gap = block.top - above.bottom
                abs(block.left - above.left) <= MERGE_LEFT_PX && gap >= -block.height / 2 &&
                    gap <= block.height * MERGE_GAP_LINES
            }
            if (index < 0) {
                merged.add(block)
            } else {
                val above = merged[index]
                merged[index] = ScreenBlock(
                    min(above.left, block.left), above.top, max(above.right, block.right), block.bottom,
                    "${above.text} ${block.text}",
                )
            }
        }
        return merged
    }

    /** Right under the box, or above it when the box sits low on the picture. */
    fun placePanel(block: ScreenBlock, frameWidth: Int, frameHeight: Int): PanelPlace {
        val width = max(block.right - block.left, MIN_PANEL_PX)
        val x = max(PANEL_EDGE_PX, min(block.left, frameWidth - width - PANEL_EDGE_PX))
        return if (block.bottom < frameHeight * BELOW_LIMIT) {
            PanelPlace(x, block.bottom + PANEL_GAP_PX, false, width)
        } else {
            PanelPlace(x, frameHeight - block.top + PANEL_GAP_PX, true, width)
        }
    }
}

class TrackedBox(val block: ScreenBlock) {
    var seen = 1
    var missed = 0
    var turkish: String? = null
}

/** Keeps a box only once it stayed put across two scans, and drops it soon after it goes away. */
class BoxTracker {
    private val boxes = ArrayList<TrackedBox>()

    /** A scan of [area] says nothing about boxes outside it. */
    fun update(found: List<ScreenBlock>, area: ScreenBlock = EVERYWHERE): List<TrackedBox> {
        val unmatched = found.toMutableList()
        for (box in boxes.filter { it.block.inside(area) }) {
            val match = unmatched.firstOrNull { it.overlaps(box.block) && ScreenText.sameLine(it.text, box.block.text) }
            if (match == null) {
                box.missed++
            } else {
                unmatched.remove(match)
                box.seen++
                box.missed = 0
            }
        }
        boxes.removeAll { it.missed >= if (it.seen < CONFIRM_SCANS) 1 else MAX_MISSED }
        unmatched.forEach { boxes.add(TrackedBox(it)) }
        return boxes.filter { it.seen >= CONFIRM_SCANS }.take(MAX_BOXES)
    }
}
