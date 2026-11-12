package dev.turkce.olcum

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalkaTamponTest {
    @Test
    fun nothingAddedGivesTheEmptyMarks() {
        val samples = RecentSamples(4)
        assertEquals(0L, samples.count)
        assertEquals(-1L, samples.average())
        assertTrue(samples.toList().isEmpty())
        assertEquals(-1L, percentile(samples.toList(), .95))
    }

    @Test
    fun keepsOnlyTheLastValuesInOrderButCountsAll() {
        val samples = RecentSamples(3)
        samples.add(10)
        samples.add(20)
        assertEquals(listOf(10L, 20L), samples.toList())
        for (value in listOf(30L, 40L, 50L)) samples.add(value)
        assertEquals(listOf(30L, 40L, 50L), samples.toList())
        assertEquals(5L, samples.count)
        assertEquals(30L, samples.average())
    }

    @Test
    fun sizeStaysTheSameOverALongRun() {
        val samples = RecentSamples(1_000)
        for (value in 1L..100_000L) samples.add(value)
        assertEquals(1_000, samples.toList().size)
        assertEquals(99_001L, samples.toList().first())
        assertEquals(100_000L, samples.toList().last())
        assertEquals(100_000L, samples.count)
        assertEquals(50_000L, samples.average())
    }

    @Test
    fun workerThreadsLoseNothing() {
        val samples = RecentSamples(100)
        val workers = List(4) { thread { repeat(10_000) { samples.add(2) } } }
        workers.forEach { it.join() }
        assertEquals(40_000L, samples.count)
        assertEquals(2L, samples.average())
        assertEquals(100, samples.toList().size)
    }
}
