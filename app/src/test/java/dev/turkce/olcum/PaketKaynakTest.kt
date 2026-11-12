package dev.turkce.olcum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingModel : LineTranslator {
    val asked = ArrayList<String>()
    override fun translate(source: String): String {
        asked.add(source)
        return "[$source]"
    }
    override fun close() = Unit
}

/** The game pack as the player meets it: lines read off the screen with the reader's usual slips, and where each came from. */
class PaketKaynakTest {
    private val demoPairs = PackFormat.tsv(File("src/main/assets/demo/deneme-paketi.tsv").readText())
    private val demo = GamePack(PackInfo("deneme", "Deneme", "Tv Türkçe Altyazı", "", "", demoPairs.size), demoPairs)

    private fun turkish(english: String) = demoPairs.getValue(english)

    @Test
    fun theDemoPackHasEveryLineOfTheClipInOrder() {
        assertEquals(DEMO_LINES, demoPairs.keys.toList())
        for (line in DEMO_LINES) {
            assertNotEquals(line, line, turkish(line))
            assertEquals(line, turkish(line), demo.find(line))
        }
    }

    /** The reader often loses the full stop or comma, or keeps a space at either end. */
    @Test
    fun aLineWithoutPunctuationOrWithSpacesAroundIsFound() {
        for (line in DEMO_LINES) {
            assertEquals(line, turkish(line), demo.find(line.replace(Regex("[.,?!]"), "")))
            assertEquals(line, turkish(line), demo.find("$line "))
            assertEquals(line, turkish(line), demo.find("  ${line.replace(" ", "  ")}\t"))
        }
    }

    /** Capital I and small l look alike on screen, and so do l and 1; one or a few such slips still find the line. */
    @Test
    fun aLineWithIAndLMixedUpIsFound() {
        assertEquals(turkish("Is anyone there? I heard a noise."), demo.find("Is anyone there? l heard a noise."))
        assertEquals(turkish("Is anyone there? I heard a noise."), demo.find("ls anyone there? I heard a noise"))
        assertEquals(turkish("I left the key under the old table."), demo.find("l left the key under the old tabIe. "))
        assertEquals(turkish("Follow me, I know a safe place."), demo.find("Fol1ow me, l know a safe place"))
        assertEquals(turkish("Stay close and keep your eyes open."), demo.find("Stay cIose and keep youreyes open."))
        assertEquals(turkish("The train leaves in ten minutes."), demo.find("The traln Ieaves in ten mlnutes."))
    }

    /** Another line, a line cut short, or the same words turned around by a "not" is left to the model. */
    @Test
    fun aDifferentLineIsNotTakenForAPackLine() {
        assertNull(demo.find("We should leave before the storm comes."))
        assertNull(demo.find("Is anyone there?"))
        assertNull(demo.find("Thank you for helping me tonight."))
        assertNull(demo.find("We will meet again at the station."))
        assertNull(demo.find("Take this map, you will not need it."))
        assertNull(demo.find("Take this map, you won't need it."))
        assertNull(demo.find("Take this map, you wont need it."))
        val pack = GamePack(demo.info, mapOf("I can't find the key." to "Anahtarı bulamıyorum."))
        assertNull(pack.find("I can find the key."))
        assertEquals("Anahtarı bulamıyorum.", pack.find("l cant find the key"))
    }

    /** A line that is letter for letter close but has another word in it says something else: the model takes it. */
    @Test
    fun aLineWithAnotherWordInItIsNotAPackLine() {
        assertNull(demo.find("The bridge is open, find another way."))
        assertNull(demo.find("The train leaves in two minutes."))
        val pack = GamePack(demo.info, mapOf("Get in the car and drive." to "Arabaya bin ve sür.", "Put it on the table." to "Masaya koy."))
        assertNull(pack.find("Get on the car and drive."))
        assertEquals("Arabaya bin ve sür.", pack.find("Get ln the car and drlve"))
        assertEquals(true, GamePack.misreadOnly("stay close and keep youreyes open", "stay close and keep your eyes open"))
        assertEquals(false, GamePack.misreadOnly("the bridge is open", "the bridge is closed"))
        assertEquals(true, GamePack.misreadOnly("wait for me bv the horscs", "wait for me by the horses"))
        assertEquals(false, GamePack.misreadOnly("get on the car", "get in the car") { it == "on" })
    }

    @Test
    fun everyLineSaysWhereItCameFrom() {
        val model = RecordingModel()
        val phrases = mapOf(
            GameTranslator.key("Open the door.") to "Kapıyı aç.",
            GameTranslator.key("Take this map, you will need it.") to "Sözlükteki çeviri.",
        )
        val translator = GameTranslator(model, phrases)
        translator.pack = demo
        // The game's own pack comes before the phrase book made for every game.
        assertEquals(Translated(turkish("Take this map, you will need it."), LineSource.PACK),
            translator.translateWithSource("Take this map, you will need it."))
        assertEquals(Translated("Kapıyı aç.", LineSource.PHRASES), translator.translateWithSource("Open the door."))
        assertEquals(Translated("[Where is the boat?]", LineSource.MACHINE), translator.translateWithSource("Where is the boat?"))
        assertEquals(Translated("[Where is the boat?]", LineSource.MEMORY), translator.translateWithSource("Where is the boat?"))
        assertEquals(listOf("Where is the boat?"), model.asked)
        assertEquals(Translated("Sadie: " + turkish("Follow me, I know a safe place."), LineSource.PACK),
            translator.translateWithSource("Sadie: Fol1ow me, l know a safe place."))
        // Two human sentences keep the pack's mark; one machine sentence makes the whole line the machine's.
        assertEquals(Translated("Kapıyı aç. " + turkish("The train leaves in ten minutes."), LineSource.PACK),
            translator.translateWithSource("Open the door. The train leaves in ten minutes."))
        assertEquals(LineSource.MACHINE, translator.translateWithSource("Open the door. Who is driving?").source)
        assertTrue(LineSource.PACK.human && LineSource.PHRASES.human)
        assertFalse(LineSource.MEMORY.human || LineSource.MACHINE.human)
    }

    /** Picking another pack while the translation runs takes effect on the next line, and the old memory goes with it. */
    @Test
    fun aNewPackTakesOverAtOnce() {
        val model = RecordingModel()
        val translator = GameTranslator(model, mapOf(GameTranslator.key("Take this map, you will need it.") to "Sözlükteki çeviri."))
        assertEquals(LineSource.MACHINE, translator.translateWithSource("Good job. Let us rest for a while.").source)
        translator.pack = demo
        assertEquals(Translated(turkish("Good job. Let us rest for a while."), LineSource.PACK),
            translator.translateWithSource("Good job. Let us rest for a while."))
        assertEquals(LineSource.MACHINE, translator.translateWithSource("Where is the boat?").source)
        translator.pack = GamePack.EMPTY
        assertEquals(Translated("Sözlükteki çeviri.", LineSource.PHRASES), translator.translateWithSource("Take this map, you will need it."))
        assertEquals(LineSource.MACHINE, translator.translateWithSource("Where is the boat?").source)
        assertEquals(2, model.asked.count { it == "Where is the boat?" })
    }

    /** The counters and the last lines live in memory for the phone panel; a new run starts from zero. */
    @Test
    fun theSessionCountersAddUp() {
        LineStats.reset()
        LineStats.add(LineSource.PACK, "bir")
        LineStats.add(LineSource.PACK, "iki")
        LineStats.add(LineSource.MACHINE, "üç")
        LineStats.add(LineSource.MEMORY, "dört")
        var json = LineStats.toJson()
        val counts = json.getJSONObject("sayac")
        assertEquals(listOf(2, 0, 1, 1), listOf("paket", "sozluk", "bellek", "makine").map { counts.getInt(it) })
        assertEquals(4, json.getInt("toplam"))
        assertEquals("dört", json.getJSONArray("son").getJSONObject(0).getString("tr"))
        assertEquals("bellek", json.getJSONArray("son").getJSONObject(0).getString("kaynak"))
        repeat(10) { LineStats.add(LineSource.PHRASES, "satır $it") }
        json = LineStats.toJson()
        assertEquals(14, json.getInt("toplam"))
        assertEquals(8, json.getJSONArray("son").length())
        assertEquals("satır 9", json.getJSONArray("son").getJSONObject(0).getString("tr"))
        LineStats.reset()
        assertEquals(0, LineStats.toJson().getInt("toplam"))
        assertEquals(0, LineStats.toJson().getJSONArray("son").length())
    }

    @Test
    fun onlyAFileThatStartsLikeAZipIsOpenedAsOne() {
        assertTrue(PackFormat.isZip(byteArrayOf(0x50, 0x4B, 3, 4, 0)))
        assertFalse(PackFormat.isZip("PK".toByteArray()))
        assertFalse(PackFormat.isZip("msgid \"Start\"".toByteArray()))
        assertTrue(PackFormat.readable("Translation/tr/a.TXT"))
        assertFalse(PackFormat.readable("readme.md"))
    }

    /** An upload over the limit is answered "too big" without reading it, so the phone hears why at once. */
    @Test
    fun anUploadOverTheLimitIsReportedNotRead() {
        val raw = "POST /api/paket/yukle?t=k&dosya=yama.zip HTTP/1.1\r\nContent-Length: 40000000\r\n\r\n"
        var seen: Triple<String, String?, Int>? = null
        val request = HttpRequest.read(raw.byteInputStream(), { bare, length -> seen = Triple(bare.path, bare.query["dosya"], length) }) {
            PackStore.MAX_BYTES
        }
        assertNull(request)
        assertEquals(Triple("/api/paket/yukle", "yama.zip", 40_000_000), seen)
    }
}
