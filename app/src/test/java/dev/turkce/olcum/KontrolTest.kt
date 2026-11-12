package dev.turkce.olcum

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KontrolTest {
    @Test
    fun studioIntrosAndLegalTextAreRecognised() {
        listOf(
            "Rockstar Games presents", "A Naughty Dog production", "Developed by Santa Monica Studio",
            "© 2018 Rockstar Games, Inc. All rights reserved.", "Executive Producer", "Music by Gustavo Santaolalla",
            "Press any button", "This game saves automatically. Do not turn off your system.",
            "Photosensitive seizure warning", "Powered by Unreal Engine", "Ubisoft Entertainment",
            "NETWURKING", "NEWORKIWG DANIELELLAND ROBERT ScHMITZ JUHN HYND",
        ).forEach { assertTrue(it, ScreenText.isIntro(it)) }
    }

    @Test
    fun speechIsNotTakenForAnIntro() {
        listOf(
            "Arthur, wait for me by the horses.", "Who's the producer of this mess?", "Our games are over, Dutch.",
            "The director wants to see you, now.", "We need to get out of here before the law shows up and takes all of us in",
            "That production was a disaster!", "I don't see what other choice we have.", "WAIT! Don't shoot.",
            "Listen..,",
        ).forEach { assertFalse(it, ScreenText.isIntro(it)) }
    }

    @Test
    fun theTurkishPlaceAndIntroSwitchSurviveASave() {
        val chosen = Settings.DEFAULT.copy(turkish = ReadArea("x", AreaKind.BOX, 100, 100, 900, 300), skipIntro = false)
        val back = Settings.fromJson(chosen.clamped().toJson())
        assertEquals(ReadArea(Settings.TURKISH_NAME, AreaKind.SUBTITLE, 100, 100, 900, 300), back.turkish)
        assertFalse(back.skipIntro)
        val plain = Settings.fromJson(Settings.DEFAULT.toJson())
        assertNull(plain.turkish)
        assertTrue(plain.skipIntro)
        assertNull(Settings.fromJson(JSONObject(Settings.DEFAULT.toJson()).put("turkish", JSONObject.NULL).toString()).turkish)
    }

    @Test
    fun sonysAccountNumberBecomesEightLittleEndianBytes() {
        // 0x0102030405060708 -> 08 07 06 05 04 03 02 01
        assertEquals("CAcGBQQDAgE=", Ps5Setup.accountFrom("72623859790382856"))
        assertEquals("//////////8=", Ps5Setup.accountFrom("18446744073709551615"))
        assertNull(Ps5Setup.accountFrom("-1"))
        assertNull(Ps5Setup.accountFrom("abc"))
        assertEquals("CAcGBQQDAgE=", Ps5Setup.accountFromBase64(" CAcGBQQDAgE= "))
        assertNull(Ps5Setup.accountFromBase64("AAAA"))
        assertNull(Ps5Setup.accountFromBase64("not base64!"))
    }

    @Test
    fun theCodeIsTakenFromThePastedAddress() {
        assertEquals("Ab12-x_Y", Ps5Setup.codeFrom("https://remoteplay.dl.playstation.net/remoteplay/redirect?code=Ab12-x_Y&cid=9"))
        assertEquals("Ab12xY", Ps5Setup.codeFrom("  Ab12xY "))
        assertNull(Ps5Setup.codeFrom("https://remoteplay.dl.playstation.net/remoteplay/redirect?error=denied"))
        assertTrue(Ps5Setup.loginUrl.startsWith("https://auth.api.sonyentertainmentnetwork.com/"))
        assertFalse(Ps5Setup.loginUrl.contains(' '))
    }

    @Test
    fun onlyHomeAddressesAreAccepted() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "172.31.255.254").forEach { assertTrue(it, Ps5Setup.isHomeAddress(it)) }
        listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "192.168.1", "192.168.1.256", "192.168.01.2", "evil.com", "")
            .forEach { assertFalse(it, Ps5Setup.isHomeAddress(it)) }
    }

    @Test
    fun aPs5AnswerSaysWhetherItIsAwake() {
        val on = "HTTP/1.1 200 Ok\nhost-id:ABCDEF\nhost-type:PS5\nhost-name:Salon\nhost-request-port:997\n"
        assertEquals(FoundPs5("192.168.1.30", "Salon", true, "PS5"), Ps5Setup.parseDiscovery("192.168.1.30", on))
        val resting = "HTTP/1.1 620 Server Standby\r\nhost-type:PS5\r\nhost-name:Salon\r\n"
        assertEquals(false, Ps5Setup.parseDiscovery("192.168.1.30", resting)?.awake)
        assertNull(Ps5Setup.parseDiscovery("192.168.1.31", "HTTP/1.1 200 Ok\nhost-type:PS4\nhost-name:Eski\n"))
        assertNull(Ps5Setup.parseDiscovery("192.168.1.32", "garbage"))
    }
}
