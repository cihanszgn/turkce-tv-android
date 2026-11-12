package dev.turkce.olcum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DenemeSatirlariTest {
    @Test
    fun theClipHasTwelveLinesOfFourSeconds() {
        assertEquals(12, DEMO_LINES.size)
        assertEquals(4, DEMO_SECONDS_PER_LINE)
        assertEquals(DEMO_LINES.size, DEMO_LINES.toSet().size)
    }

    /** Every line passes the same filters as a real subtitle, so deneme modu shows the real path at work. */
    @Test
    fun everyLineIsReadAsSpeech() {
        for (line in DEMO_LINES) {
            assertTrue(line, ScreenText.looksLikeSpeech(line))
            assertFalse(line, ScreenText.isIntro(line))
            assertFalse(line, ScreenText.isNoise(line))
        }
    }

    /** The clip script reads the lines between quotes, one per row. */
    @Test
    fun theClipScriptCanReadTheLines() {
        for (line in DEMO_LINES) {
            assertFalse(line, '"' in line || '\\' in line)
            assertTrue(line, line.length in 10..60)
        }
        val script = File("../arac/demo_klip_uret.py").readText()
        assertTrue(script.contains("DenemeSatirlari.kt"))
    }

    @Test
    fun theClipIsInTheAppAndSmall() {
        val clip = File("src/main/assets/demo/deneme.mp4")
        assertTrue(clip.isFile)
        assertTrue(clip.length() in 100_000L..3L * 1024 * 1024)
    }
}
