package dev.turkce.olcum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    private val hint = ScreenBlock(40, 40, 500, 110, "Go to the marked place")

    @Test fun `two reads of one line count as the same line`() {
        assertTrue(ScreenText.sameLine("Expand D Co", "Expand O Co"))
        assertTrue(ScreenText.sameLine("Get in the car", "GET IN THE CAR"))
        assertFalse(ScreenText.sameLine("Follow me", "Get in the car"))
        assertFalse(ScreenText.sameLine("", "Follow me"))
    }

    @Test fun `plates and scores are noise, speech is not`() {
        assertTrue(ScreenText.isNoise("N FC1988"))
        assertTrue(ScreenText.isNoise("12:45"))
        assertFalse(ScreenText.isNoise("Who you trying to impress?"))
    }

    @Test fun `film credits are noise, a shouted line with punctuation is not`() {
        assertTrue(ScreenText.isNoise("ADAM COCHRANE MICHAEL KANE"))
        assertTrue(ScreenText.isNoise("FELIPE BUSQUETS"))
        assertFalse(ScreenText.isNoise("GET DOWN!"))
        assertFalse(ScreenText.isNoise("FRANKLIN: Follow me"))
    }

    @Test fun `the console's own Turkish notification is not game text`() {
        assertTrue(ScreenText.isNoise("Uzaktan Oynama bağlandı."))
        assertFalse(ScreenText.isInfoText("Uzaktan Oynama bağlandı artık."))
        assertFalse(ScreenText.isInfoText("Uzaktan Oynama bağlandi."))
    }

    @Test fun `one misread letter does not make an English line Turkish`() {
        assertFalse(ScreenText.isNoise("a car lıke this"))
        assertTrue(ScreenText.isInfoText("Save vehıcles by parking them in the Garage."))
    }

    @Test fun `menus and credits are not info boxes, a hint with a button is`() {
        assertFalse(ScreenText.isInfoText("ONLINE GTA+ STORY"))
        assertFalse(ScreenText.isInfoText("Cane Dzsign LESLIE BENZIES IMRAN SARWAR"))
        assertFalse(ScreenText.isInfoText("Ctatene Dinecto ROD EDGE"))
        assertTrue(ScreenText.isInfoText("Press O to toggle focus on Lamar while driving."))
    }

    @Test fun `a near copy of the source means the model could not read it`() {
        assertTrue(ScreenText.barelyChanged("Asdociate Ant Dineetord", "Asdosiate Ant Dinetord"))
        assertTrue(ScreenText.barelyChanged("ADAM COCHRANE MICHAEL KANE", "Adam cochrane michael kane"))
        assertFalse(ScreenText.barelyChanged("Follow Lamar.", "Lamar'ı takip et."))
    }

    @Test fun `a box needs three words but speech needs two`() {
        assertTrue(ScreenText.looksLikeSpeech("Follow me"))
        assertFalse(ScreenText.isInfoText("Vinewood Hills"))
        assertTrue(ScreenText.isInfoText("Go to the marked place"))
    }

    @Test fun `stacked lines with one left edge become one box`() {
        val second = ScreenBlock(42, 120, 450, 190, "and wait there")
        val elsewhere = ScreenBlock(1400, 40, 1800, 110, "Press pause to open the map")
        val merged = ScreenText.mergeBlocks(listOf(second, elsewhere, hint))
        assertEquals(2, merged.size)
        assertEquals(ScreenBlock(40, 40, 500, 190, "Go to the marked place and wait there"), merged.first { it.left == 40 })
    }

    @Test fun `a high box gets its panel below and a low box above`() {
        assertEquals(PanelPlace(40, 118, false, 520), ScreenText.placePanel(hint, 1920, 1080))
        val low = ScreenBlock(40, 900, 700, 960, "Hold the button to run")
        assertEquals(PanelPlace(40, 188, true, 660), ScreenText.placePanel(low, 1920, 1080))
    }

    @Test fun `a panel at the right edge stays on the picture`() {
        val right = ScreenBlock(1700, 40, 1900, 90, "Open the map now")
        assertEquals(1384, ScreenText.placePanel(right, 1920, 1080).x)
    }

    @Test fun `a box shows on its second scan and goes after two misses`() {
        val tracker = BoxTracker()
        assertTrue(tracker.update(listOf(hint)).isEmpty())
        assertEquals(1, tracker.update(listOf(hint.copy(text = "Go to the marked plaee"))).size)
        assertEquals(1, tracker.update(emptyList()).size)
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    @Test fun `a scan of the corner says nothing about a box elsewhere`() {
        val corner = ScreenBlock(0, 0, 960, 389, "")
        val far = ScreenBlock(1300, 500, 1800, 560, "Press pause to open the map")
        val tracker = BoxTracker()
        tracker.update(listOf(far))
        tracker.update(listOf(far))
        tracker.update(emptyList(), corner)
        assertEquals(1, tracker.update(emptyList(), corner).size)
    }

    @Test fun `text cut by the edge of a scan is not inside it`() {
        val corner = ScreenBlock(0, 0, 960, 389, "")
        assertTrue(hint.inside(corner))
        assertFalse(ScreenBlock(700, 40, 960, 90, "A long line going on").inside(corner))
    }

    @Test fun `text seen only once never shows`() {
        val tracker = BoxTracker()
        tracker.update(listOf(hint))
        assertTrue(tracker.update(emptyList()).isEmpty())
        assertTrue(tracker.update(listOf(hint)).isEmpty())
    }
}
