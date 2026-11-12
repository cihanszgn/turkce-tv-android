package dev.turkce.olcum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeModel : LineTranslator {
    val asked = ArrayList<String>()
    override fun translate(source: String): String {
        asked.add(source)
        return "<$source>"
    }
    override fun close() = Unit
}

class GameTranslatorTest {
    private val model = FakeModel()
    private val translator = GameTranslator(model, mapOf("follow me" to "Beni takip et", "watch out" to "Dikkat et"))

    @Test fun `a known phrase never reaches the model, however it is written`() {
        assertEquals("Beni takip et", translator.translate("FOLLOW ME!"))
        assertEquals("Beni takip et", translator.translate("Follow me..."))
        assertTrue(model.asked.isEmpty())
    }

    @Test fun `a repeated line is translated once`() {
        translator.translate("Where is the money?")
        translator.translate("where is the money")
        assertEquals(listOf("Where is the money?"), model.asked)
    }

    @Test fun `only the unknown sentence goes to the model`() {
        assertEquals("Dikkat et <They are behind us.>", translator.translate("Watch out! They are behind us."))
    }

    @Test fun `the speaker's name stays and the words are translated`() {
        assertEquals("Lamar: Beni takip et", translator.translate("Lamar: Follow me"))
        assertTrue(translator.knows("Lamar: Follow me"))
        assertFalse(translator.knows("Lamar: Keep going north"))
    }

    @Test fun `a line in capitals reaches the model as a sentence`() {
        translator.translate("THE BRIDGE IS CLOSED")
        assertEquals(listOf("The bridge is closed"), model.asked)
    }

    @Test fun `curly apostrophes match straight ones`() {
        assertEquals(GameTranslator.key("Don't move"), GameTranslator.key("Don’t move!"))
    }

    @Test fun `comments and broken lines are skipped`() {
        val phrases = GameTranslator.parse(sequenceOf("# title", "", "no tab here", "Run!\tKoş!"))
        assertEquals(mapOf("run" to "Koş!"), phrases)
    }

    @Test fun `the shipped phrase book is whole`() {
        val lines = File("src/main/assets/oyun-sozluk.tsv").readLines(Charsets.UTF_8)
        val pairs = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("every line needs a tab: ${pairs.filter { '\t' !in it }}", pairs.all { '\t' in it })
        val phrases = GameTranslator.parse(pairs.asSequence())
        val repeats = pairs.groupBy { GameTranslator.key(it.substringBefore('\t')) }.filter { it.value.size > 1 }.keys
        assertTrue("repeated phrases: $repeats", repeats.isEmpty())
        assertTrue("only ${phrases.size} phrases", phrases.size >= 300)
        assertTrue(phrases.values.none { it.isBlank() })
    }
}
