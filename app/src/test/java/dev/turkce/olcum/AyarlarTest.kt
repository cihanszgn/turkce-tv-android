package dev.turkce.olcum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AyarlarTest {
    @Test
    fun settingsSurviveTheTripThroughJson() {
        val settings = Settings(
            listOf(
                ReadArea("Altyazı", AreaKind.SUBTITLE, 100, 800, 1800, 1070),
                ReadArea("Harita", AreaKind.BOX, 1500, 0, 1920, 300),
            ),
            OverlayStyle(SubtitleFont.SERIF, 24, 14, 0xFFE14D, 0x000000, 40),
        )
        assertEquals(settings, Settings.fromJson(settings.toJson()))
    }

    @Test
    fun missingFieldsFallBackToTheDefaults() {
        assertEquals(Settings.DEFAULT, Settings.fromJson("{}"))
    }

    @Test
    fun numbersAreKeptInRange() {
        val json = """{"regions":[{"name":"","kind":"kutu","left":-50,"top":2000,"right":5,"bottom":9000}],
            "subtitleSize":200,"boxSize":1,"opacity":150,"textColor":"kirmizi","font":"yok"}"""
        val settings = Settings.fromJson(json)
        assertEquals(ReadArea("Alan 1", AreaKind.BOX, 0, 1060, 40, 1080), settings.areas.single())
        assertEquals(MAX_TEXT_SIZE, settings.style.subtitleSize)
        assertEquals(MIN_TEXT_SIZE, settings.style.boxSize)
        assertEquals(100, settings.style.opacity)
        assertEquals(Settings.DEFAULT_STYLE.textColor, settings.style.textColor)
        assertEquals(SubtitleFont.BOLD, settings.style.font)
    }

    @Test
    fun noMoreThanSixAreasAndNeverNone() {
        val many = Settings(List(9) { ReadArea("A$it", AreaKind.SUBTITLE, 0, 0, 100, 100) }, Settings.DEFAULT_STYLE)
        assertEquals(MAX_AREAS, many.clamped().areas.size)
        assertEquals(Settings.DEFAULT_AREAS, Settings(emptyList(), Settings.DEFAULT_STYLE).clamped().areas)
    }

    @Test
    fun areasSplitByKind() {
        assertEquals(listOf("Altyazı"), Settings.DEFAULT.subtitleAreas.map { it.name })
        assertEquals(2, Settings.DEFAULT.boxAreas.size)
    }

    @Test
    fun opacityGoesIntoTheAlpha() {
        val style = Settings.DEFAULT_STYLE.copy(boxColor = 0x102030, opacity = 50)
        assertEquals(0x7F102030, style.boxArgb)
        assertEquals(0x00102030, style.copy(opacity = 0).boxArgb)
        assertEquals(0xFF102030.toInt(), style.copy(opacity = 100).boxArgb)
    }

    @Test
    fun readsARequestWithBodyAndQuery() {
        val body = """{"opacity":30}"""
        val raw = "POST /api/ayar?t=abc%20d&x= HTTP/1.1\r\nHost: tv\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val request = HttpRequest.read(raw.byteInputStream())!!
        assertEquals("POST", request.method)
        assertEquals("/api/ayar", request.path)
        assertEquals(mapOf("t" to "abc d", "x" to ""), request.query)
        assertEquals(body, request.body)
    }

    @Test
    fun aBigBodyIsTakenOnlyWhereAllowed() {
        val raw = "POST /api/paket/yukle?t=k HTTP/1.1\r\nContent-Length: 40000\r\n\r\n" + "a".repeat(40000)
        assertNull(HttpRequest.read(raw.byteInputStream()))
        assertEquals(40000, HttpRequest.read(raw.byteInputStream()) { if (it.query["t"] == "k") 50000 else 0 }!!.body.length)
    }

    @Test
    fun rejectsBrokenRequests() {
        assertNull(HttpRequest.read("".byteInputStream()))
        assertNull(HttpRequest.read("GET /\r\n\r\n".byteInputStream()))
        assertNull(HttpRequest.read("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".byteInputStream()))
    }
}
