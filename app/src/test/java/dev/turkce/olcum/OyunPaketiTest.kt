package dev.turkce.olcum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class EchoModel : LineTranslator {
    val asked = ArrayList<String>()
    override fun translate(source: String): String {
        asked.add(source)
        return "<$source>"
    }
    override fun close() = Unit
}

class OyunPaketiTest {
    private val info = PackInfo("rdr2-grup", "RDR2", "grup", "https://example.org/rdr2", "MIT", 3)
    private val pack = GamePack(info, mapOf(
        "Sounds like quite the party." to "Bayağı eğlenceli bir parti gibi.",
        "We need to get out of here, <i>now</i>." to "Hemen buradan gitmemiz lazım.",
        "Arthur, wait for me by the horses." to "Arthur, beni atların yanında bekle.",
    ))

    @Test
    fun exactLinesAreFoundWhateverTheCaseAndPunctuation() {
        assertEquals("Bayağı eğlenceli bir parti gibi.", pack.find("SOUNDS LIKE QUITE THE PARTY"))
        assertEquals("Hemen buradan gitmemiz lazım.", pack.find("We need to get out of here, now."))
    }

    @Test
    fun aLineReadWithALetterWrongIsStillFound() {
        assertEquals("Arthur, beni atların yanında bekle.", pack.find("Arthur, wait for me bv the horscs."))
    }

    @Test
    fun aDifferentLineIsNotMistakenForOne() {
        assertNull(pack.find("Arthur, wait for me by the river bank tonight."))
        assertNull(pack.find("party"))
    }

    @Test
    fun distanceStopsEarly() {
        assertEquals(1, GamePack.distance("horses", "horscs", 3))
        assertNull(GamePack.distance("horses", "river", 2))
    }

    @Test
    fun readsGettextFiles() {
        val po = """
            msgid ""
            msgstr ""
            "Language: tr\n"

            #: menu.cpp:12
            msgid "Start game"
            msgstr "Oyuna başla"

            #, fuzzy
            msgid "Options"
            msgstr "Seçenekler"

            msgctxt "dialog"
            msgid ""
            "We ride "
            "at dawn."
            msgstr ""
            "Şafakta "
            "yola çıkıyoruz."

            msgid "Not done yet"
            msgstr ""

            msgid "One horse"
            msgid_plural "%d horses"
            msgstr[0] "Bir at"
            msgstr[1] "%d at"
            msgid "Say \"hi\""
            msgstr "\"Selam\" de"
        """.trimIndent()
        assertEquals(
            mapOf("Start game" to "Oyuna başla", "We ride at dawn." to "Şafakta yola çıkıyoruz.", "One horse" to "Bir at",
                "Say \"hi\"" to "\"Selam\" de"),
            PackFormat.pairs("tr.po", po),
        )
    }

    @Test
    fun ourOwnFileKeepsWhoMadeIt() {
        val text = PackFormat.write(info, mapOf("Start game" to "Oyuna başla"))
        assertEquals(info, PackFormat.info(text.lines().first()))
        assertEquals(mapOf("Start game" to "Oyuna başla"), PackFormat.pairs("rdr2.tsv", text))
    }

    @Test
    fun idsAreSafeFileNames() {
        assertEquals("red-dead-redemption-2-turk-ceviri-ekibi", PackInfo.idOf("Red Dead Redemption 2", "Türk Çeviri Ekibi"))
        assertEquals("paket", PackInfo.idOf("../", "/"))
    }

    @Test
    fun searchKeepsOnlyRepositoriesThatNameTheGame() {
        fun repo(name: String, description: String, stars: Int) = PatchRepo(name, description, stars, "2026-01-01", "MIT", "", "main")
        val found = YamaArama.rank("Red Dead Redemption 2", listOf(
            repo("someone/Binance-MCP", "Trading tools", 40),
            repo("public-apis/public-apis", "A list of APIs", 99999),
            repo("ekip/RDR2-Turkce", "Türkçe yama dosyaları", 3),
            repo("modder/red-dead-redemption-2-tools", "Save editor", 50),
            repo("grup/ceviri", "Red Dead Redemption 2 için Türkçe çeviri", 1),
        ))
        assertEquals(listOf("ekip/RDR2-Turkce", "grup/ceviri", "modder/red-dead-redemption-2-tools"), found.map { it.repo })
        assertEquals(listOf("x/0ad-tr"), YamaArama.rank("0 A.D.", listOf(repo("x/0ad-tr", "", 1), repo("y/awesome", "", 9))).map { it.repo })
    }

    @Test
    fun theTranslatorPrefersThePackOverTheModel() {
        val model = EchoModel()
        val translator = GameTranslator(model, emptyMap())
        assertEquals("<Sounds like quite the party.>", translator.translate("Sounds like quite the party."))
        translator.pack = pack
        assertEquals("Bayağı eğlenceli bir parti gibi.", translator.translate("Sounds like quite the party."))
        assertEquals("Dutch: Bayağı eğlenceli bir parti gibi.", translator.translate("Dutch: Sounds like quite the party."))
        assertTrue(translator.knows("Arthur, wait for me bv the horscs."))
        assertEquals("Bayağı eğlenceli bir parti gibi. <Who invited him?>", translator.translate("Sounds like quite the party. Who invited him?"))
    }

    @Test
    fun readsXUnityFiles() {
        val text = "// yorum\nStart Game=Oyuna Başla\nA \\= B=A eşittir B\nr:\"^Day (\\d+)$\"=Gün $1\nSame=Same\n"
        assertEquals(mapOf("Start Game" to "Oyuna Başla", "A = B" to "A eşittir B"), PackFormat.xunity(text))
        assertEquals(PackFormat.xunity(text), PackFormat.pairs("_AutoGeneratedTranslations.txt", text))
    }

    @Test
    fun xmlStringTablesWithoutEnglishAreNotRead() {
        val xml = "<entries>\n<entry name=\"BANKER_ACCT_DECLINE\">Seni anlıyorum.</entry>\n</entries>"
        assertTrue(PackFormat.pairs("Banker.txt", xml).isEmpty())
    }

    @Test
    fun readsCsvTablesByTheirLanguageColumns() {
        val text = "Key,Type,English,Turkish\n" +
            "menu_start,Text,Start,Başla\n" +
            "line_2,Text,\"Hello, stranger\",\"Merhaba, yabancı\"\n" +
            "line_3,Text,\"Two\nlines\",\"İki \"\"satır\"\"\"\n" +
            "line_4,Text,Untranslated,\n"
        assertEquals(mapOf("Start" to "Başla", "Hello, stranger" to "Merhaba, yabancı", "Two\nlines" to "İki \"satır\""), PackFormat.csv(text))
        assertEquals(mapOf("Yes" to "Evet"), PackFormat.csv("id;en;tr\n1;Yes;Evet\n"))
        assertTrue(PackFormat.csv("a,b\n1,2\n").isEmpty())
    }

    @Test
    fun readsRenPyTranslationsForTurkishOnly() {
        val text = """
            # game/script.rpy:10
            translate turkish start_1a2b:

                # e "Hello, {i}stranger{/i}."
                e "Merhaba, yabancı."

            translate french start_1a2b:

                # e "Goodbye."
                e "Au revoir."

            translate turkish strings:

                old "Start"
                new "Başla"
        """.trimIndent()
        assertEquals(mapOf("Hello, stranger." to "Merhaba, yabancı.", "Start" to "Başla"), PackFormat.renpy(text))
    }

    @Test
    fun aZipIsOpenedAndItsTurkishFilesPreferred() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("README.md", "Start=Ignored")
            put("Translation/fr/Text/a.txt", "Start=Commencer")
            put("Translation/tr/Text/a.txt", "Start=Başla")
            put("Translation/tr/Text/b.txt", "Quit=Çık")
        }
        assertEquals(mapOf("Start" to "Başla", "Quit" to "Çık"), PackFormat.read("yama.zip", out.toByteArray()))
    }

    @Test
    fun theStartOfAFileInsideAZipIsReadFromItsPieces() {
        val out = java.io.ByteArrayOutputStream()
        val text = (1..400).joinToString("\n") { "Line $it=Satır $it" }
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Translation/tr/a.txt"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("readme.md"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val zip = out.toByteArray()
        val range = ZipSample.directory(zip)!!
        val entries = ZipSample.entries(zip.copyOfRange(range.first.toInt(), range.last.toInt() + 1))
        assertEquals(listOf("Translation/tr/a.txt", "readme.md"), entries.map { it.name })
        val entry = entries[0]
        val local = zip.copyOfRange(entry.offset.toInt(), zip.size)
        assertEquals(text, String(ZipSample.content(local, entry, 1 shl 20), Charsets.UTF_8))
        assertEquals(100, ZipSample.content(local, entry, 100).size)
        assertNull(ZipSample.directory("not a zip".toByteArray()))
    }

    @Test
    fun aDonationLinkComesFromFundingYml() {
        assertEquals("https://ko-fi.com/grup", YamaArama.fundingLink("# bağış\ngithub: []\nko_fi: grup\n"))
        assertEquals("https://www.patreon.com/ekip", YamaArama.fundingLink("patreon: ekip # aylık"))
        assertEquals("https://example.org/destek", YamaArama.fundingLink("custom: [\"https://example.org/destek\", \"x\"]"))
        assertEquals("", YamaArama.fundingLink("custom: javascript:alert(1)\n"))
    }

    @Test
    fun theDonationLinkIsKeptWithThePack() {
        val withLink = info.copy(donate = "https://ko-fi.com/grup")
        assertEquals(withLink, PackInfo.fromJson(withLink.toJson()))
        assertEquals("", PackInfo.fromJson(org.json.JSONObject().put("id", "x")).donate)
    }

    /** Links from translators' files are opened on the phone, so only plain https pages get through. */
    @Test
    fun onlyHttpsLinksAreKept() {
        assertEquals("https://ko-fi.com/grup", webLink(" https://ko-fi.com/grup "))
        assertEquals("https://github.com/a/b?tab=readme#tr", webLink("https://github.com/a/b?tab=readme#tr"))
        for (bad in listOf("http://example.org", "javascript:alert(1)", "data:text/html,x", "https://", "//example.org",
            "https://a.org/\"><script>", "https://user@evil.example/", "https://a.org/x y", "https://a.org/" + "x".repeat(300))) {
            assertEquals(bad, "", webLink(bad))
        }
        assertEquals("", YamaArama.fundingLink("custom: http://example.org/destek\n"))
        val stored = info.toJson().put("source", "javascript:alert(1)").put("donate", "http://example.org")
        assertEquals("", PackInfo.fromJson(stored).source)
        assertEquals("", PackInfo.fromJson(stored).donate)
    }

    @Test
    fun aRepositoryFromGitHubIsChecked() {
        val json = org.json.JSONObject().put("full_name", "ekip/rdr2-tr").put("html_url", "javascript:alert(1)")
        assertEquals("https://github.com/ekip/rdr2-tr", PatchRepo.fromJson(json).url)
        assertEquals("https://github.com/ekip/x", PatchRepo.fromJson(json.put("html_url", "https://github.com/ekip/x")).url)
        for (name in listOf("../x", "ekip/..", "ekip", "a/b/c", "ekip/x?y")) {
            assertThrows(name, IllegalArgumentException::class.java) { PatchRepo.fromJson(json.put("full_name", name)) }
        }
    }

    /** A branch or path that climbs out of the repository is refused before anything is fetched. */
    @Test
    fun aDownloadStaysInsideTheRepository() {
        for (branch in listOf("../main", "a/../b", "/main", ".hidden", "a//b", "a/.b")) {
            val repo = PatchRepo("ekip/x", "", 0, "", "MIT", "https://github.com/ekip/x", branch)
            assertThrows(branch, IllegalArgumentException::class.java) { YamaArama.download(repo, "tr.po") }
        }
        val repo = PatchRepo("ekip/x", "", 0, "", "MIT", "https://github.com/ekip/x", "main")
        for (path in listOf("../tr.po", "/etc/tr.po", "a/../../tr.po", "")) {
            assertThrows(path, IllegalArgumentException::class.java) { YamaArama.download(repo, path) }
        }
    }
}
