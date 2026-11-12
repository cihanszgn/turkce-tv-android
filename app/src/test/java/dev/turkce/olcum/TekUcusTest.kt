package dev.turkce.olcum

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TekUcusTest {
    /** Runs [task] on another thread inside [gate] and holds it there until the returned latch is counted down. */
    private fun holdInside(gate: SingleFlight, task: String): Pair<CountDownLatch, Thread> {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            gate.run(task) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        return release to worker
    }

    @Test
    fun aSecondPairingFailsAtOnceWithTheTurkishMessage() {
        val gate = SingleFlight()
        val (release, worker) = holdInside(gate, ChiakiGate.PAIRING)
        assertTrue(gate.busy)
        assertEquals(ChiakiGate.PAIRING, gate.current)
        val started = System.nanoTime()
        try {
            gate.run(ChiakiGate.PAIRING) { fail("must not run beside the first") }
            fail("expected the busy error")
        } catch (error: IllegalStateException) {
            assertEquals("Eşleştirme sürüyor, bitmesini bekle.", error.message)
        }
        assertTrue("fails without waiting", System.nanoTime() - started < 1_000_000_000L)
        release.countDown()
        worker.join(5_000)
        assertFalse(gate.busy)
        assertNull(gate.current)
        assertEquals(7, gate.run(ChiakiGate.PAIRING) { 7 })
    }

    @Test
    fun theMessageNamesTheCallInProgress() {
        val gate = SingleFlight()
        val (release, worker) = holdInside(gate, ChiakiGate.PAIRING)
        val error = runCatching { gate.run(ChiakiGate.WAKING) { } }.exceptionOrNull()
        assertEquals(busyMessage(ChiakiGate.PAIRING), error?.message)
        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun aFailingCallFreesTheGate() {
        val gate = SingleFlight()
        val error = runCatching { gate.run(ChiakiGate.PAIRING) { error("PlayStation eşleşmeyi kabul etmedi.") } }.exceptionOrNull()
        assertEquals("PlayStation eşleşmeyi kabul etmedi.", error?.message)
        assertFalse(gate.busy)
        assertEquals("tamam", gate.run(ChiakiGate.PAIRING) { "tamam" })
    }

    @Test
    fun aCallWithAWaitRunsOnceTheShortOneEnds() {
        val gate = SingleFlight()
        val (release, worker) = holdInside(gate, ChiakiGate.WAKING)
        val result = AtomicReference<String>()
        val waiter = thread { result.set(gate.run(ChiakiGate.CONNECTING, waitMs = 5_000) { "baglandi" }) }
        Thread.sleep(100)
        assertNull("waits while the other call runs", result.get())
        release.countDown()
        waiter.join(5_000)
        worker.join(5_000)
        assertEquals("baglandi", result.get())
    }

    @Test
    fun aWaitThatRunsOutFailsWithTheMessage() {
        val gate = SingleFlight()
        val (release, worker) = holdInside(gate, ChiakiGate.PAIRING)
        val error = runCatching { gate.run(ChiakiGate.CONNECTING, waitMs = 50) { } }.exceptionOrNull()
        assertEquals(busyMessage(ChiakiGate.PAIRING), error?.message)
        release.countDown()
        worker.join(5_000)
    }
}
