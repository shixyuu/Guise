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
    fun readResponseChecksBodyLength() {
        fun read(raw: String) = ByeDPIProbe.readResponse(raw.byteInputStream())
        assertEquals(200, read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"))
        assertEquals(403, read("HTTP/1.1 403 Forbidden\r\ncontent-length: 2\r\n\r\nno"))
        assertNull(read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello")) // truncated
        assertEquals(204, read("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"))
        assertEquals(200, read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"))
        assertNull(read("HTTP/1.1 200 OK\r\nContent-Le")) // cut inside headers
        assertNull(read("\u0015\u0003\u0003\u0000\u0002\u0002\u0046")) // garbage / TLS alert
        assertNull(read(""))
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

class ByeDPIProbeTargetsTest {

    @Test
    fun presetsAreValid() {
        val ids = ByeDPIProbeTargets.PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ByeDPIProbeTargets.PRESETS.forEach { target ->
            assertTrue(target.id, target.urls.isNotEmpty())
            target.urls.forEach { assertTrue(it, it.startsWith("https://") && ByeDPIProbeTargets.hostOf(it) != null) }
        }
        val defaults = ByeDPIProbeTargets.selected(ByeDPIProbeTargets.DEFAULT_SELECTION, "").map { it.id }
        assertTrue(defaults.containsAll(listOf("x", "twitch")))
        assertTrue("google" !in defaults && "youtube" !in defaults)
        assertEquals(
            listOf("x.com", "abs.twimg.com", "pbs.twimg.com", "twitter.com"),
            ByeDPIProbeTargets.PRESETS.first { it.id == "x" }.hosts
        )
    }

    @Test
    fun customEntriesAndSelection() {
        val custom = ByeDPIProbeTargets.parseCustom(
            "Example.com\n# comment\nhttps://cdn.example.org/a.png  # logo\n\nexample.com\nnot a url"
        )
        assertEquals(
            listOf("https://Example.com/", "https://cdn.example.org/a.png", "https://example.com/"),
            custom.map { it.urls.single() }
        )
        assertEquals("example.com", custom[0].label)
        assertEquals(ByeDPIProbeTargets.Tag.CUSTOM, custom[0].tag)

        val text = "cdn.example.org\nfoo.test"
        val selection = ByeDPIProbeTargets.encodeSelection(
            listOf("twitch", ByeDPIProbeTargets.CUSTOM_PREFIX + "https://foo.test/", "gone")
        )
        assertEquals(
            listOf("twitch", "custom:https://foo.test/"),
            ByeDPIProbeTargets.selected(selection, text).map { it.id }
        )
    }

    @Test
    fun requestsCycleThroughUrls() {
        val x = ByeDPIProbeTargets.PRESETS.first { it.id == "x" }
        assertEquals(
            listOf(x.urls[0], x.urls[1], x.urls[2], x.urls[3], x.urls[0]),
            ByeDPIProbe.requestUrls(x, 5)
        )
    }
}
