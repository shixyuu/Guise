package io.nekohasekai.sagernet.dualnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualNetTest {

    private fun stats(vararg rtt: Long?) = DualNet.LinkStats().apply { rtt.forEach { add(it) } }

    @Test
    fun fixedAlternates() {
        val picker = DualNet.Picker()
        val picks = List(6) { picker.pickFixed(listOf("WiFi", "LTE")) }
        assertEquals(listOf("WiFi", "LTE", "WiFi", "LTE", "WiFi", "LTE"), picks)
        assertEquals("WiFi", picker.pickFixed(listOf("WiFi")))
        assertNull(picker.pickFixed(emptyList()))
    }

    @Test
    fun linkStatsWindow() {
        val s = DualNet.LinkStats(window = 4)
        listOf(10L, 30L, null, 20L, 40L).forEach { s.add(it) }
        assertEquals(4, s.sampleCount) // 10 dropped
        assertEquals(30.0, s.latency!!, 0.001) // median of 30,20,40
        assertEquals(15.0, s.jitter, 0.001) // |30-20|, |20-40|
        assertEquals(0.25, s.loss, 0.001)
        s.add(null); s.add(null); s.add(null)
        assertTrue(s.dead)
        s.add(25)
        assertEquals(0, s.consecutiveFailures)
    }

    @Test
    fun weightsFollowLatencyAndJitter() {
        val w = DualNet.weights(
            listOf("WiFi", "LTE"),
            mapOf("WiFi" to stats(20, 22, 21, 20), "LTE" to stats(60, 90, 50, 120))
        )
        assertEquals(1.0, w.values.sum(), 1e-9)
        assertTrue("$w", w["WiFi"]!! > 0.7)
        // equal links -> equal weights
        val eq = DualNet.weights(listOf("WiFi", "LTE"), mapOf("WiFi" to stats(40, 40), "LTE" to stats(40, 40)))
        assertEquals(0.5, eq["WiFi"]!!, 1e-9)
    }

    @Test
    fun deadOrMissingNetworks() {
        // dead network gets nothing
        val w = DualNet.weights(listOf("WiFi", "LTE"), mapOf("WiFi" to stats(30), "LTE" to stats(null, null, null)))
        assertEquals(1.0, w["WiFi"]!!, 1e-9)
        assertEquals(0.0, w["LTE"]!!, 1e-9)
        // only one network available
        assertEquals(mapOf("LTE" to 1.0), DualNet.weights(listOf("LTE"), emptyMap()))
        // no data yet -> equal
        assertEquals(0.5, DualNet.weights(listOf("WiFi", "LTE"), emptyMap())["LTE"]!!, 1e-9)
        // everything dead -> share equally instead of dropping all traffic
        val dead = DualNet.weights(
            listOf("WiFi", "LTE"),
            mapOf("WiFi" to stats(null, null, null), "LTE" to stats(null, null, null))
        )
        assertEquals(0.5, dead["WiFi"]!!, 1e-9)
        // new network without samples gets the average of known healthy ones
        val fresh = DualNet.weights(listOf("WiFi", "LTE"), mapOf("WiFi" to stats(30, 30)))
        assertEquals(0.5, fresh["LTE"]!!, 1e-9)
        assertTrue(DualNet.weights(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun smoothWeightedRoundRobin() {
        val picker = DualNet.Picker()
        val weights = mapOf("WiFi" to 0.75, "LTE" to 0.25)
        val picks = List(8) { picker.pickWeighted(weights) }
        assertEquals(6, picks.count { it == "WiFi" })
        assertEquals(2, picks.count { it == "LTE" })
        // spread out, never 4 LTE in a row etc.
        assertTrue(picks.windowed(4).all { win -> win.count { it == "WiFi" } >= 2 })
        assertEquals("WiFi", picker.pickWeighted(mapOf("WiFi" to 1.0, "LTE" to 0.0)))
        assertNull(picker.pickWeighted(mapOf("LTE" to 0.0)))
    }
}
