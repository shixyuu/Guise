package io.nekohasekai.sagernet.byedpi

import org.junit.Assert.assertEquals
import org.junit.Test

class ByeDPITest {

    @Test
    fun defaultArgsWhenBlank() {
        val cmd = ByeDPI.buildCommand("/lib/libbyedpi.so", 12345, "  ", null)
        assertEquals(
            listOf("/lib/libbyedpi.so", "--ip", "127.0.0.1", "--port", "12345", "-o1", "-a1", "-r-5+se"),
            cmd,
        )
    }

    @Test
    fun guiseOwnedOptionsAreStripped() {
        val cmd = ByeDPI.buildCommand(
            "bin", 1000,
            "ciadpi -i 0.0.0.0 -p1080 --port=9 -D --protect-path /x -w pid -d1 -H \":a.com b.com\" -An --help",
            "/data/protect_path",
        )
        assertEquals(
            listOf(
                "bin", "--ip", "127.0.0.1", "--port", "1000", "--protect-path", "/data/protect_path",
                "-d1", "-H", ":a.com b.com", "-An",
            ),
            cmd,
        )
    }

    @Test
    fun domains() {
        assertEquals(
            listOf("domain:youtube.com", "full:x.com", "geosite:google", "domain:a.org"),
            ByeDPI.parseDomains("youtube.com\n full:x.com # comment\n\ngeosite:google,a.org\nyoutube.com"),
        )
    }
}
