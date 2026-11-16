package io.nekohasekai.sagernet.byedpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ByeDPIProbeTest {

    @Test
    fun parseStrategiesSkipsCommentsAndSubstitutesSni() {
        val text = """
            # comment
            -d1 -r1+s

            -f-1 -Qr -s1+sm -An -Ft -d1 -t5 -f1 -n {sni} -Qr -s1+sm -An
              -d1 -r1+s
        """.trimIndent()
        val parsed = ByeDPIProbe.parseStrategies(text, "example.com")
        assertEquals(
            listOf(
                "-d1 -r1+s",
                "-f-1 -Qr -s1+sm -An -Ft -d1 -t5 -f1 -n \"example.com\" -Qr -s1+sm -An",
            ),
            parsed
        )
        // the quoted SNI survives shell splitting as a single argument
        assertTrue(ByeDPI.shellSplit(parsed[1]).contains("example.com"))
    }

    @Test
    fun blankSniFallsBackToDefault() {
        assertEquals(
            listOf("-n \"google.com\""),
            ByeDPIProbe.parseStrategies("-n {sni}", "  ")
        )
    }

    @Test
    fun bundledListParses() {
        val asset = File("src/main/assets/byedpi/strategies.list")
        val parsed = ByeDPIProbe.parseStrategies(asset.readText(), ByeDPIProbe.DEFAULT_SNI)
        assertTrue(parsed.size >= 50)
        parsed.forEach {
            assertTrue(it, !it.contains("{sni}"))
            assertTrue(it, it.startsWith("-"))
        }
    }

    @Test
    fun parseSites() {
        assertEquals(
            listOf("https://www.google.com", "http://a.example", "https://b.example"),
            ByeDPIProbe.parseSites("www.google.com\nhttp://a.example, b.example\n# x\n\nwww.google.com")
        )
    }

    @Test
    fun planHasBaselineAndCurrentFirst() {
        val plan = ByeDPIProbe.buildPlan(ByeDPI.LEGACY_DEFAULT_ARGS, listOf("-s1", ByeDPI.DEFAULT_ARGS))
        assertEquals(ByeDPIProbe.Kind.BASELINE, plan[0].kind)
        assertNull(plan[0].args)
        assertEquals(ByeDPIProbe.Kind.CURRENT, plan[1].kind)
        assertEquals(ByeDPI.DEFAULT_ARGS, plan[1].args) // legacy default migrated
        assertEquals(listOf("-s1"), plan.drop(2).map { it.args })
    }

    @Test
    fun medianAndSorting() {
        assertNull(ByeDPIProbe.median(emptyList()))
        assertEquals(20L, ByeDPIProbe.median(listOf(30, 10, 20)))
        assertEquals(15L, ByeDPIProbe.median(listOf(10, 20)))

        fun row(args: String, success: Int, vararg latency: Long) =
            ByeDPIProbe.Result(ByeDPIProbe.Kind.STRATEGY, args).apply {
                total = 6; done = 6; this.success = success; finished = true
                latencies.addAll(latency.toList())
            }

        val baseline = ByeDPIProbe.Result(ByeDPIProbe.Kind.BASELINE, null).apply {
            total = 6; done = 6; finished = true
        }
        val slow = row("slow", 6, 900, 900)
        val fast = row("fast", 6, 100, 120)
        val half = row("half", 3, 50)
        val pending = ByeDPIProbe.Result(ByeDPIProbe.Kind.STRATEGY, "pending").apply { total = 6 }
        assertEquals(
            listOf(null, "fast", "slow", "half", "pending"),
            ByeDPIProbe.sorted(listOf(pending, half, slow, baseline, fast)).map { it.args }
        )
    }
}
