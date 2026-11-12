package dev.turkce.olcum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CozucuGozcusuTest {
    private val ms = 1_000_000L

    /** Samples every 16 ms from [from] to [to] (ms), as a 60 fps stream sends them. */
    private fun StallWatch.feed(from: Long, to: Long) {
        var at = from
        while (at <= to) {
            fed(at * ms)
            at += 16
        }
    }

    @Test
    fun aDecoderTakingSamplesWithoutFramesForFiveSecondsIsStuck() {
        val watch = StallWatch()
        watch.feed(0, 4_900)
        assertFalse(watch.stalled(4_900 * ms))
        watch.feed(4_916, 5_100)
        assertTrue(watch.stalled(5_100 * ms))
    }

    @Test
    fun framesComingOutKeepItHealthy() {
        val watch = StallWatch()
        var at = 0L
        while (at <= 20_000) {
            watch.fed(at * ms)
            if (at % 100 == 0L) watch.output()
            assertFalse("at $at ms", watch.stalled(at * ms))
            at += 16
        }
    }

    @Test
    fun aQuietSourceIsNotADecoderFault() {
        val watch = StallWatch()
        watch.feed(0, 500)
        // The PS5 sends nothing for a while: the decoder has nothing to work on.
        assertFalse(watch.stalled(6_000 * ms))
        assertFalse(watch.stalled(60_000 * ms))
        // Samples come back: the wait starts over from here, not from the first sample.
        watch.feed(60_000, 63_000)
        assertFalse(watch.stalled(63_000 * ms))
        watch.feed(63_016, 65_100)
        assertTrue(watch.stalled(65_100 * ms))
    }

    @Test
    fun shortGapsInTheStreamDoNotHideAStuckDecoder() {
        val watch = StallWatch()
        // Bursts with 500 ms pauses, shorter than the quiet limit: the decoder still holds input the whole time.
        for (burst in 0 until 6) watch.feed(burst * 1_000L, burst * 1_000L + 500)
        assertTrue(watch.stalled(5_500 * ms))
    }

    @Test
    fun aDecoderRefusingInputWithFullBuffersIsStuckToo() {
        val watch = StallWatch()
        watch.fed(0)
        var at = 10L
        while (at <= 5_100) {
            watch.refused(at * ms)
            at += 10
        }
        assertTrue(watch.stalled(5_100 * ms))
    }

    @Test
    fun refusalsAloneBeforeAnyInputMeanNothing() {
        val watch = StallWatch()
        var at = 0L
        while (at <= 10_000) {
            watch.refused(at * ms)
            at += 10
        }
        assertFalse(watch.stalled(10_000 * ms))
    }

    @Test
    fun aNewDecoderStartsFromScratch() {
        val watch = StallWatch()
        watch.feed(0, 5_100)
        assertTrue(watch.stalled(5_100 * ms))
        watch.reset()
        assertFalse(watch.stalled(5_100 * ms))
        watch.feed(5_116, 9_000)
        assertFalse(watch.stalled(9_000 * ms))
    }

    @Test
    fun aDecoderThatThrowsHandsOverToTheNextAndSkipsTheFailedOnes() {
        val failover = DecoderFailover(3)
        assertEquals(1, failover.threw(0))
        assertEquals(setOf(0), failover.skipped)
        assertEquals(2, failover.threw(1))
        assertNull("every decoder failed in a row", failover.threw(2))
    }

    @Test
    fun theOrderWrapsAroundFromTheLastDecoder() {
        val failover = DecoderFailover(3)
        assertEquals(0, failover.threw(2))
        assertEquals(1, failover.threw(0))
    }

    @Test
    fun aFrameMakesEveryDecoderWorthTryingAgain() {
        val failover = DecoderFailover(2)
        assertEquals(1, failover.threw(0))
        failover.worked()
        assertTrue(failover.skipped.isEmpty())
        assertEquals(0, failover.threw(1))
    }

    @Test
    fun aStuckDecoderIsRestartedOnceThenReplaced() {
        val failover = DecoderFailover(2)
        assertEquals("restart the same one first", 0, failover.stuck(0))
        assertEquals(1, failover.stuck(0))
        assertEquals(1, failover.stuck(1))
        assertNull(failover.stuck(1))
    }

    @Test
    fun aStuckDecoderThatRecoversGetsItsRestartBack() {
        val failover = DecoderFailover(2)
        assertEquals(0, failover.stuck(0))
        failover.worked()
        assertEquals(0, failover.stuck(0))
    }

    @Test
    fun withOneDecoderTheRunEndsAfterItFails() {
        val failover = DecoderFailover(1)
        assertEquals(0, failover.stuck(0))
        assertNull(failover.stuck(0))
        assertNull(DecoderFailover(1).threw(0))
    }
}
