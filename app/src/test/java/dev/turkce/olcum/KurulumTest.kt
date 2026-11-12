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
        val none = SetupGuide.steps(remotePlay = false, account = false, chosen = "", paired = false)
        assertEquals(listOf(false, false, false, false), none.done)
        assertEquals(0, none.count)
        assertEquals(1, none.current)

        val two = SetupGuide.steps(remotePlay = true, account = true, chosen = "", paired = false)
        assertEquals(2, two.count)
        assertEquals(3, two.current)

        // A PS5 chosen before the account: step 2 is still the next one, and it stays open.
        val skipped = SetupGuide.steps(remotePlay = true, account = false, chosen = "192.168.1.20", paired = false)
        assertEquals(listOf(true, false, true, false), skipped.done)
        assertEquals(2, skipped.current)

        // An address that is not a home one does not tick step 3.
        assertFalse(SetupGuide.steps(remotePlay = true, account = true, chosen = "8.8.8.8", paired = false).done[2])

        // Pairing proves Remote Play and the account, even after a reinstall that lost the page's marks.
        val paired = SetupGuide.steps(remotePlay = false, account = false, chosen = "", paired = true)
        assertEquals(4, paired.count)
        assertEquals(5, paired.current)

        val json = two.toJson()
        assertEquals(2, json.getInt("tamam"))
        assertEquals(3, json.getInt("sira"))
        assertEquals(4, json.getJSONArray("adimlar").length())
        assertTrue(json.getJSONArray("adimlar").getBoolean(1))
        assertFalse(json.getJSONArray("adimlar").getBoolean(2))
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
        assertTrue(SetupGuide.friendlyError("/api/ps5/eslestir", NullPointerException()).contains("Cihaz Bağla"))
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
