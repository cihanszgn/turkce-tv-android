package dev.turkce.olcum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HakkindaTest {
    private fun asset(name: String) = File("src/main/assets", name).readText(Charsets.UTF_8)

    @Test
    fun theComponentListCoversEveryPartOfTheApp() {
        val groups = About.groups(asset(About.THIRD_PARTY_FILE))
        assertEquals(4, groups.size)
        for (group in groups) {
            assertTrue(group.title, group.items.isNotEmpty())
            for (item in group.items) {
                listOf(item.name, item.version, item.license, item.copyright).forEach { assertTrue(item.toString(), it.isNotBlank()) }
            }
        }
        val names = groups.flatMap { it.items }.map { it.name }
        assertTrue(names.any { it.startsWith("chiaki-ng") })
        assertTrue("com.google.zxing:core" in names)
        assertTrue("com.google.mlkit:text-recognition" in names)
        // The model's licence is not shipped with it; the list must say so rather than guess.
        val model = groups.single { it.title.startsWith("Çeviri modeli") }.items.single()
        assertTrue(model.license.contains("doğrulanacak"))
    }

    @Test
    fun theLicenceFilesAreInTheApp() {
        // The official text is centred with leading spaces; it is kept as published.
        assertTrue(asset(About.AGPL_FILE).trimStart().startsWith("GNU AFFERO GENERAL PUBLIC LICENSE"))
        assertTrue(asset(About.THIRD_PARTY_FILE).contains(About.TEXTS_MARK))
        assertTrue(asset(About.GOOGLE_FILE).isNotBlank())
        assertEquals(3, About.FULL_TEXTS.size)
    }

    @Test
    fun theTrademarkNoticeIsWordForWord() {
        assertEquals(
            "Bu uygulama Sony Interactive Entertainment ile bağlantılı, onaylı veya destekli değildir. " +
                "PlayStation ve PS5, sahiplerinin tescilli markalarıdır.",
            About.TRADEMARK,
        )
    }

    @Test
    fun theLinksAreHttps() {
        assertTrue(Baglantilar.SOURCE.startsWith("https://"))
        assertTrue(Baglantilar.PRIVACY.startsWith("https://"))
    }

    @Test
    fun thePhonePageEscapesTextAndKeepsTheKey() {
        val groups = listOf(ComponentGroup("<grup>", listOf(Component("a<b>", "-", "MIT & co", "\"x\""))))
        val page = About.page("1.0", groups, "abc123")
        assertTrue(page.contains("<meta name=\"referrer\" content=\"no-referrer\">"))
        assertTrue(page.contains("href=\"/?t=abc123\""))
        assertTrue(page.contains("href=\"/lisanslar?t=abc123\""))
        assertTrue(page.contains(Baglantilar.SOURCE))
        assertTrue(page.contains(Baglantilar.PRIVACY))
        assertTrue(page.contains("&lt;grup&gt;"))
        assertTrue(page.contains("a&lt;b&gt; · MIT &amp; co"))
        assertTrue(page.contains("&quot;x&quot;"))
        assertFalse(page.contains("<grup>"))
        assertFalse(page.contains("a<b>"))
        // A version shown as "-" is left out.
        assertFalse(page.contains("a&lt;b&gt; -"))
    }

    @Test
    fun theFullTextIsCutAtEachLicence() {
        val text = "baş\n---- MIT ----\n" + (1..45).joinToString("\n") { "satır $it" } + "\n==== zlib ====\nson"
        val parts = About.parts(listOf(text, "ikinci dosya"), lines = 20)
        assertEquals(text + "\nikinci dosya", parts.joinToString("\n") { it.text })
        assertTrue(parts.all { it.text.lines().size <= 20 })
        assertEquals(listOf("baş", "---- MIT ----", "==== zlib ====", "ikinci dosya"), parts.filter { it.starts }.map { it.text.lines().first() })
    }
}
