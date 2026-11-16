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
        repeat(3) { s.add(null as Long?) }
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
    fun passiveSamplesUseKernelJitterAndRetransmits() {
        val s = DualNet.LinkStats()
        s.add(DualNet.Sample(40.0, 5.0, 0.0))
        s.add(DualNet.Sample(60.0, 15.0, 0.1))
        s.add(DualNet.Sample(50.0, null, 0.0)) // connect() handshake sample, no rttvar
        assertEquals(50.0, s.latency!!, 1e-9)
        assertEquals(10.0, s.jitter, 1e-9) // median of kernel rttvar 5, 15
        assertEquals(0.1 / 3, s.loss, 1e-9)
        // lossy, jittery LTE vs clean Wi-Fi with similar RTT
        val wifi = DualNet.LinkStats().apply { repeat(5) { add(DualNet.Sample(50.0, 4.0, 0.0)) } }
        val lte = DualNet.LinkStats().apply { repeat(5) { add(DualNet.Sample(50.0, 30.0, 0.2)) } }
        val w = DualNet.weights(listOf("WiFi", "LTE"), mapOf("WiFi" to wifi, "LTE" to lte))
        assertTrue("$w", w["WiFi"]!! > 0.7)
    }

    @Test
    fun deadNetworkIsRetriedLater() {
        val lte = DualNet.LinkStats()
        repeat(3) { lte.add(null as DualNet.Sample?, now = 1_000) }
        val wifi = stats(30, 30)
        val map = mapOf("WiFi" to wifi, "LTE" to lte)
        assertEquals(0.0, DualNet.weights(listOf("WiFi", "LTE"), map, now = 1_000 + 29_000)["LTE"]!!, 1e-9)
        // after DEAD_RETRY_MS: exploration share (same as an unmeasured network)
        assertEquals(0.5, DualNet.weights(listOf("WiFi", "LTE"), map, now = 1_000 + 30_000)["LTE"]!!, 1e-9)
        // fails again -> back to 0 for another 30 s
        lte.add(null as DualNet.Sample?, now = 40_000)
        assertEquals(0.0, DualNet.weights(listOf("WiFi", "LTE"), map, now = 60_000)["LTE"]!!, 1e-9)
        // a success revives it
        lte.add(DualNet.Sample(35.0, 3.0, 0.0), now = 61_000)
        assertTrue(DualNet.weights(listOf("WiFi", "LTE"), map, now = 61_000)["LTE"]!! > 0.0)
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
