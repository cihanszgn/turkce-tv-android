package dev.turkce.olcum

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IstekHavuzuTest {
    @Test
    fun workBeyondTheWorkersAndTheQueueIsRefusedNotQueuedWithoutEnd() {
        val pool = BoundedWorkers(workers = 2, waiting = 1, name = "deneme")
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val done = AtomicInteger()
        val hold = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            done.incrementAndGet()
            Unit
        }
        assertTrue(pool.offer(hold))
        assertTrue(pool.offer(hold))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue("one waits in the queue", pool.offer(hold))
        assertFalse("full: refused at once", pool.offer(hold))
        assertFalse(pool.offer(hold))
        release.countDown()
        val end = System.currentTimeMillis() + 5_000
        while (done.get() < 3 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(3, done.get())
        val again = CountDownLatch(1)
        assertTrue("free again once the work ends", pool.offer { again.countDown() })
        assertTrue(again.await(5, TimeUnit.SECONDS))
    }
}
