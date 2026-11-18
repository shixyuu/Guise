package io.nekohasekai.sagernet.byedpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeDPIAutoTest {

    private fun row(args: String, ok: Int, total: Int, latency: Long = 100, kind: ByeDPIProbe.Kind = ByeDPIProbe.Kind.STRATEGY) =
        ByeDPIProbe.Result(kind, args).apply {
            this.total = total
            repeat(total) { record("t", if (it < ok) latency + it else null) }
            finished = true
        }

    @Test
    fun poolKeepsBest12() {
        val rows = (1..20).map { row("-s$it", ok = it % 5, total = 4, latency = 1000L - it) } +
                listOf(ByeDPIProbe.Result(ByeDPIProbe.Kind.BASELINE, null).apply { total = 4; finished = true; repeat(4) { record("t", 5) } })
        val pool = ByeDPIAuto.pickPool(rows)
        assertEquals(12, pool.size)
        assertTrue(pool.none { it.args.isEmpty() })
        // 4/4 first, faster first among equals
        assertEquals("-s19", pool[0].args)
        assertEquals(1.0, pool[0].successRate, 1e-9)
        assertTrue(pool.zipWithNext().all { (a, b) -> a.successRate >= b.successRate })
        // zero-success rows never enter
        assertTrue(pool.all { it.successRate > 0 })
        // working CURRENT row is eligible for pool selection
        val withCurrent = ByeDPIAuto.pickPool(
            listOf(row("-d1", ok = 4, total = 4, latency = 20, kind = ByeDPIProbe.Kind.CURRENT)) + rows
        )
        assertEquals("-d1", withCurrent[0].args)
    }

    @Test
    fun encodeDecode() {
        val pool = listOf(ByeDPIAuto.Entry("-d1 -r1+s", 0.75, 120), ByeDPIAuto.Entry("-f-1 -Qr \"x y\"", 0.5, null))
        assertEquals(pool, ByeDPIAuto.decode(ByeDPIAuto.encode(pool)))
        assertTrue(ByeDPIAuto.decode("garbage\n\n1\t2").isEmpty())
    }

    @Test
    fun scheduling() {
        val day = 24 * 60 * 60_000L
        val good = listOf(ByeDPIAuto.Entry("a", 0.9, 100))
        assertTrue(ByeDPIAuto.needsFullTest(emptyList(), 0, 0))
        assertFalse(ByeDPIAuto.needsFullTest(good, day, 1))
        assertTrue(ByeDPIAuto.needsFullTest(good, day + 1, 1))
        assertTrue(ByeDPIAuto.needsFullTest(listOf(ByeDPIAuto.Entry("a", 0.4, 100)), 10, 5))
        assertEquals(60 * 60_000L, ByeDPIAuto.intervalMs(60))
        assertEquals(10 * 60_000L, ByeDPIAuto.intervalMs(1))
        assertTrue(ByeDPIAuto.healthFailed(0.0))
        assertFalse(ByeDPIAuto.healthFailed(0.5))
    }

    @Test
    fun switchingAndFallback() {
        val cur = ByeDPIAuto.Entry("cur", 0.75, 200)
        assertFalse(ByeDPIAuto.shouldSwitch("cur", cur, ByeDPIAuto.Entry("cur", 1.0, 10)))
        assertTrue(ByeDPIAuto.shouldSwitch("cur", cur, ByeDPIAuto.Entry("b", 1.0, 300)))
        assertFalse(ByeDPIAuto.shouldSwitch("cur", cur, ByeDPIAuto.Entry("b", 0.5, 10)))
        assertTrue(ByeDPIAuto.shouldSwitch("cur", cur, ByeDPIAuto.Entry("b", 0.75, 150)))
        assertFalse(ByeDPIAuto.shouldSwitch("cur", cur, ByeDPIAuto.Entry("b", 0.75, 190)))
        assertTrue(ByeDPIAuto.shouldSwitch("cur", null, ByeDPIAuto.Entry("b", 0.5, 190)))
        val retested = listOf(
            ByeDPIAuto.Entry("cur", 1.0, 10), ByeDPIAuto.Entry("x", 0.25, 10),
            ByeDPIAuto.Entry("y", 0.5, 300), ByeDPIAuto.Entry("z", 0.5, 100),
        )
        assertEquals("z", ByeDPIAuto.fallback("cur", retested)!!.args)
        assertNull(ByeDPIAuto.fallback("cur", listOf(ByeDPIAuto.Entry("x", 0.25, 10))))
    }
}
