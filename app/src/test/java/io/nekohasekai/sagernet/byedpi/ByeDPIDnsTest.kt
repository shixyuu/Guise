package io.nekohasekai.sagernet.byedpi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeDPIDnsTest {

    private fun plan(
        main: Boolean = false, global: Boolean = false, mode: Int = ByeDPI.Mode.ALL_DIRECT,
        direct: Boolean = false, forTest: Boolean = false, running: Boolean = true,
        domains: String = "x.com\ntwitch.tv",
    ) = ByeDPI.dnsPlan(ByeDPI.DEFAULT_DNS, running, main, global, mode, domains, direct, forTest)

    @Test
    fun defaultServersAreIpLiteralDoh() {
        val servers = ByeDPI.parseDnsServers(ByeDPI.DEFAULT_DNS)
        assertEquals(listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"), servers)
        assertEquals(servers, ByeDPI.parseDnsServers("  \n# nothing\n"))
        assertEquals(
            listOf("https://dns.google/dns-query", "tcp://9.9.9.9"),
            ByeDPI.parseDnsServers("https://dns.google/dns-query, tcp://9.9.9.9 # quad9")
        )
    }

    @Test
    fun dnsServerHost() {
        assertEquals("1.1.1.1", ByeDPI.dnsServerHost("https://1.1.1.1/dns-query"))
        assertEquals("dns.google", ByeDPI.dnsServerHost("https://dns.google/dns-query"))
        assertEquals("1.1.1.1", ByeDPI.dnsServerHost("tcp://1.1.1.1:53"))
        assertEquals("2606:4700::1111", ByeDPI.dnsServerHost("https://[2606:4700::1111]/dns-query"))
        assertEquals("8.8.8.8", ByeDPI.dnsServerHost("8.8.8.8"))
    }

    @Test
    fun planPerMode() {
        // ByeDPI-only profile: replaces remote DNS (also for URL tests), not in route mode "direct"
        assertTrue(plan(main = true).run { active && replaceRemoteDns && domains.isEmpty() })
        assertTrue(plan(main = true, direct = true, forTest = true).replaceRemoteDns)
        assertFalse(plan(main = true, direct = true).active)
        // global "all direct traffic": only with route mode "direct"
        assertFalse(plan(global = true).active)
        assertTrue(plan(global = true, direct = true).run { active && replaceRemoteDns })
        // global "selected domains": dedicated servers for those domains
        plan(global = true, mode = ByeDPI.Mode.DOMAINS).let {
            assertTrue(it.active)
            assertFalse(it.replaceRemoteDns)
            assertEquals(listOf("domain:x.com", "domain:twitch.tv"), it.domains)
        }
        assertFalse(plan(global = true, mode = ByeDPI.Mode.DOMAINS, domains = "").active)
        // ByeDPI not running
        assertFalse(plan(main = true, global = true, running = false).active)
    }

    @Test
    fun dnsWireFormat() {
        val query = ByeDPIProbe.buildDnsQuery("x.com")
        val expected = byteArrayOf(
            0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 'x'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0, 1, 0, 1,
        )
        assertArrayEquals(expected, query)

        // response: header (1 question, 2 answers: CNAME + A), compressed names
        val response = byteArrayOf(
            0, 0, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0,
            *query.copyOfRange(12, query.size),
            0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 12, // CNAME
            0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 244.toByte(), 42, 1, // A
        )
        assertEquals(listOf("104.244.42.1"), ByeDPIProbe.parseDnsA(response))
        // NXDOMAIN / truncated
        assertEquals(emptyList<String>(), ByeDPIProbe.parseDnsA(response.copyOf(12).also { it[3] = 0x83.toByte() }))
        assertEquals(emptyList<String>(), ByeDPIProbe.parseDnsA(response.copyOf(30)))
    }

    @Test
    fun chunkedBodyIsDecoded() {
        val sink = java.io.ByteArrayOutputStream()
        val code = ByeDPIProbe.readResponse(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;x=y\r\nde\r\n0\r\n\r\n".byteInputStream(),
            sink = sink
        )
        assertEquals(200, code)
        assertEquals("abcde", sink.toString())
        assertEquals(
            null,
            ByeDPIProbe.readResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab".byteInputStream())
        )
    }
}
