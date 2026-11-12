package dev.turkce.olcum

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KurulumTest {
    @Test
    fun theStepsFollowWhatIsDone() {
        val none = SetupGuide.steps(newAccount = false, remotePlay = false, account = false, chosen = "", paired = false)
        assertEquals(listOf(false, false, false, false, false), none.done)
        assertEquals(0, none.count)
        assertEquals(1, none.current)
        // Step 3 waits for both PS5-side steps, step 5 for step 3; steps 1, 2 and 4 are never locked.
        assertTrue(none.locks[2].contains("1. ve 2. adımı"))
        assertTrue(none.locks[4].contains("3. adımda"))
        assertEquals("", none.locks[0])
        assertEquals("", none.locks[1])
        assertEquals("", none.locks[3])

        val onlyFirst = SetupGuide.steps(newAccount = true, remotePlay = false, account = false, chosen = "", paired = false)
        assertTrue(onlyFirst.locks[2].startsWith("Önce 2. adımı"))
        assertEquals(2, onlyFirst.current)
        val onlySecond = SetupGuide.steps(newAccount = false, remotePlay = true, account = false, chosen = "", paired = false)
        assertTrue(onlySecond.locks[2].startsWith("Önce 1. adımı"))

        val two = SetupGuide.steps(newAccount = true, remotePlay = true, account = false, chosen = "", paired = false)
        assertEquals(2, two.count)
        assertEquals(3, two.current)
        assertEquals("", two.locks[2])
        assertTrue(two.locks[4].isNotEmpty())

        // A linked account unlocks step 5 and proves step 1 (the page asked for it first), not Remote Play.
        val linked = SetupGuide.steps(newAccount = false, remotePlay = false, account = true, chosen = "", paired = false)
        assertEquals(listOf(true, false, true, false, false), linked.done)
        assertEquals("", linked.locks[4])
        assertEquals(2, linked.current)

        // A PS5 chosen early ticks step 4 without unlocking anything else.
        val skipped = SetupGuide.steps(newAccount = true, remotePlay = true, account = false, chosen = "192.168.1.20", paired = false)
        assertEquals(listOf(true, true, false, true, false), skipped.done)
        assertEquals(3, skipped.current)

        // An address that is not a home one does not tick step 4.
        assertFalse(SetupGuide.steps(newAccount = true, remotePlay = true, account = true, chosen = "8.8.8.8", paired = false).done[3])

        // Pairing proves every step, even after a reinstall that lost the page's marks.
        val paired = SetupGuide.steps(newAccount = false, remotePlay = false, account = false, chosen = "", paired = true)
        assertEquals(5, paired.count)
        assertEquals(6, paired.current)
        assertTrue(paired.locks.all { it.isEmpty() })

        val json = two.toJson()
        assertEquals(2, json.getInt("tamam"))
        assertEquals(3, json.getInt("sira"))
        assertEquals(5, json.getJSONArray("adimlar").length())
        assertEquals(5, json.getJSONArray("kilitler").length())
        assertTrue(json.getJSONArray("adimlar").getBoolean(1))
        assertFalse(json.getJSONArray("adimlar").getBoolean(2))
        assertEquals("", json.getJSONArray("kilitler").getString(2))
        assertTrue(json.getJSONArray("kilitler").getString(4).isNotEmpty())
    }

    @Test
    fun oldOrOddMarksNeverCrash() {
        // The four-step setup stored the Remote Play mark under another key; whatever is there reads as yes or no.
        assertTrue(SetupGuide.mark(true))
        assertFalse(SetupGuide.mark(false))
        assertFalse(SetupGuide.mark(null))
        assertFalse(SetupGuide.mark("true"))
        assertFalse(SetupGuide.mark(1))
    }

    @Test
    fun thePrefixIsTheTvsHomeNetwork() {
        assertEquals("192.168.1", SetupGuide.networkPrefix("192.168.1.34"))
        assertEquals("10.0.0", SetupGuide.networkPrefix(" 10.0.0.5 "))
        assertEquals("172.20.4", SetupGuide.networkPrefix("172.20.4.9"))
        assertNull(SetupGuide.networkPrefix(null))
        assertNull(SetupGuide.networkPrefix(""))
        assertNull(SetupGuide.networkPrefix("8.8.8.8"))
        assertNull(SetupGuide.networkPrefix("100.64.1.2"))
    }

    @Test
    fun typedAndPastedAddressesAreCleaned() {
        mapOf(
            "192.168.1.20" to "192.168.1.20",
            // A Turkish phone keypad has a comma where the dot should be.
            "192,168,1,20" to "192.168.1.20",
            " 192,168.1,20 " to "192.168.1.20",
            "192٫168٫1٫20" to "192.168.1.20",
            "１９２．１６８．１．２０" to "192.168.1.20",
            "١٩٢.١٦٨.١.٢٠" to "192.168.1.20",
            "192.168.001.020" to "192.168.1.20",
            "IP Adresi: 192.168.1.20" to "192.168.1.20",
            "IP adresi 192.168.1.20, alt ağ maskesi 255.255.255.0" to "192.168.1.20",
            "192.168.1.20." to "192.168.1.20",
            "192 168 1 20" to "192168120",
            "192.168.1" to "192.168.1",
            "192.168.1.2000" to "192.168.1.2000",
            "" to "",
            "   " to "",
        ).forEach { (typed, clean) -> assertEquals(typed, clean, SetupGuide.cleanAddress(typed)) }
    }

    @Test
    fun aWrongAddressSaysWhy() {
        assertNull(SetupGuide.addressProblem("192.168.1.20"))
        assertNull(SetupGuide.addressProblem("10.0.0.254"))
        assertNull(SetupGuide.addressProblem("172.16.0.1"))
        assertTrue(SetupGuide.addressProblem("")!!.startsWith("Adresi yaz"))
        assertTrue(SetupGuide.addressProblem("192.168.1")!!.contains("dört sayı"))
        assertTrue(SetupGuide.addressProblem("192.168.1.2.3")!!.contains("dört sayı"))
        assertTrue(SetupGuide.addressProblem("192..168.1")!!.contains("dört sayı"))
        assertTrue(SetupGuide.addressProblem("192168120")!!.contains("dört sayı"))
        assertTrue(SetupGuide.addressProblem("192.168.1.300")!!.contains("300 olamaz"))
        assertTrue(SetupGuide.addressProblem("192.168.1.2000")!!.contains("2000 olamaz"))
        assertEquals("Son sayı 1 ile 254 arasında olur.", SetupGuide.addressProblem("192.168.1.0"))
        assertEquals("Son sayı 1 ile 254 arasında olur.", SetupGuide.addressProblem("192.168.1.255"))
        assertTrue(SetupGuide.addressProblem("8.8.8.8")!!.contains("ev ağına ait değil"))
        // Every typed form the page sends goes through the cleaning first.
        assertNull(SetupGuide.addressProblem(SetupGuide.cleanAddress("192,168,1,20")))
    }

    @Test
    fun aRefusedPairingIsExplained() {
        val awake = FoundPs5("192.168.1.20", "PS5-123", awake = true, type = "PS5")
        val resting = awake.copy(awake = false)
        assertTrue(SetupGuide.pairRefused("192.168.1.20", emptyList()).contains("cevap vermedi"))
        assertTrue(SetupGuide.pairRefused("192.168.1.30", listOf(awake)).contains("192.168.1.20 adresinde"))
        assertTrue(SetupGuide.pairRefused("192.168.1.20", listOf(resting)).contains("dinlenme modunda"))
        val wrongCode = SetupGuide.pairRefused("192.168.1.20", listOf(awake))
        assertTrue(wrongCode.contains("kodu kabul etmedi"))
        assertTrue(wrongCode.contains("yeni hesap"))
        assertTrue(wrongCode.contains("3. adımda"))
        assertTrue(SetupGuide.pairRefused("192.168.1.20", emptyList()).contains("4. adımda"))
        assertTrue(SetupGuide.pairRefused("192.168.1.20", listOf(resting)).contains("Cihazı Eşleştir"))
    }

    @Test
    fun rawErrorsNeverReachThePage() {
        // The app's own messages are Turkish and go through as they are.
        assertEquals("Eşleştirme sürüyor, bitmesini bekle.",
            SetupGuide.friendlyError("/api/ps5/eslestir", IllegalStateException(busyMessage(ChiakiGate.PAIRING))))
        assertEquals("Önce çeviriyi durdur.", SetupGuide.friendlyError("/api/ps5/unut", IllegalStateException("Önce çeviriyi durdur.")))
        assertTrue(SetupGuide.friendlyError("/api/ps5/eslestir", PairRefused()).contains("yeni kod"))

        // System text, in English or empty, is replaced by what to do.
        val offline = SetupGuide.friendlyError("/api/ps5/psn", IOException("Unable to resolve host \"ca.account.sony.com\""))
        assertTrue(offline.contains("internete"))
        assertFalse(offline.contains("Unable"))
        assertTrue(SetupGuide.friendlyError("/api/ps5/psn", IllegalStateException("Value <html> of type java.lang.String")).contains("Sony"))
        assertTrue(SetupGuide.friendlyError("/api/ps5/bul", java.net.SocketException("Network is unreachable")).contains("Wi-Fi"))
        assertTrue(SetupGuide.friendlyError("/api/ps5/eslestir", NullPointerException()).contains("Cihazı Eşleştir"))
        assertTrue(SetupGuide.friendlyError("/api/ps5/uyandir", RuntimeException("timeout")).contains("PS tuşuna"))
        assertTrue(SetupGuide.friendlyError("/api/paket/indir", IOException("HTTP 404")).contains("internet"))
        assertEquals("İşlem yapılamadı. Sayfayı yenileyip tekrar dene.", SetupGuide.friendlyError("/api/ceviri", RuntimeException()))

        // Parse errors may echo what was sent (Turkish letters included); they never pass through.
        val echoed = SetupGuide.friendlyError("/api/ps5/kurulum", JSONException("A JSONObject text must begin with '{' at ğüş"))
        assertFalse(echoed.contains("JSONObject"))
        assertNotNull(SetupGuide.friendlyError("/api/ps5/kurulum", NumberFormatException("For input string: \"ş\"")))
        assertFalse(SetupGuide.friendlyError("/api/ps5/kurulum", NumberFormatException("For input string: \"ş\"")).contains("input"))
    }
}
