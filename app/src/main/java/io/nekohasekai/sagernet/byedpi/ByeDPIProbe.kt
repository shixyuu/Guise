/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Guise contributors                                     *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.byedpi

/**
 * Guise: pure (Android-free) parts of the ByeDPI connectivity test.
 *
 * Strategy list handling ({sni} placeholder, one command per line) and the result model follow
 * romanvht/ByeByeDPI (GPL-3.0): services/TestService.kt (loadSettings) and data/TestResult.kt.
 */
object ByeDPIProbe {

    /** ByeByeDPI's default "byedpi_proxytest_sni". */
    const val DEFAULT_SNI = "google.com"
    const val DEFAULT_REQUESTS = 3
    const val DEFAULT_TIMEOUT_SECONDS = 5
    const val DEFAULT_CONCURRENCY = 3
    const val MAX_CONCURRENCY = 4

    /** Asset with the default strategy list (copied from ByeByeDPI). */
    const val STRATEGIES_ASSET = "byedpi/strategies.list"

    /**
     * One byedpi command line per line. Blank lines and # comments are ignored, {sni} is
     * replaced by the quoted fake SNI (as ByeByeDPI does, so it survives shell splitting), and
     * duplicates are dropped while keeping order.
     */
    fun parseStrategies(text: String, sni: String): List<String> {
        val fakeSni = sni.trim().ifEmpty { DEFAULT_SNI }
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.replace("{sni}", "\"$fakeSni\"") }
            .distinct()
    }

    /** Requests for one target: [requests] requests cycling through its URLs. */
    fun requestUrls(target: ByeDPIProbeTargets.Target, requests: Int): List<String> =
        List(requests) { target.urls[it % target.urls.size] }

    /**
     * Read an HTTP/1.x response (sent with `Connection: close`) and decide whether it arrived
     * intact: any status counts as reachable (like ByeByeDPI's SiteCheckUtils), but the body must
     * reach Content-Length, or for chunked/unsized bodies end cleanly; at most [limit] bytes are
     * read. A DPI box that kills the connection mid-way therefore counts as a failure.
     * @return the status code, or null on a broken/truncated response.
     */
    fun readResponse(input: java.io.InputStream, limit: Long = 1024L * 1024): Int? {
        val stream = java.io.BufferedInputStream(input)
        val statusLine = readLine(stream) ?: return null
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
        val code = parts[1].toIntOrNull() ?: return null
        var contentLength = -1L
        var chunked = false
        while (true) {
            val line = readLine(stream) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toLongOrNull() ?: -1L
                "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
            }
        }
        val buffer = ByteArray(8192)
        if (!chunked && contentLength >= 0) {
            val want = minOf(contentLength, limit)
            var got = 0L
            while (got < want) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), want - got).toInt())
                if (n < 0) return null
                got += n
            }
            return code
        }
        var total = 0L
        while (total < limit) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
        }
        return code
    }

    private fun readLine(stream: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = stream.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16384) return null
        }
    }

    /**
     * Rows to test: a "direct, no ByeDPI" baseline, the current args, then the list (without
     * repeating the current args).
     */
    fun buildPlan(currentArgs: String, strategies: List<String>): List<Result> {
        val current = ByeDPI.effectiveArgs(currentArgs)
        val rows = mutableListOf(Result(Kind.BASELINE, null), Result(Kind.CURRENT, current))
        strategies.filter { it != current }.forEach { rows.add(Result(Kind.STRATEGY, it)) }
        return rows
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Finished rows first, by success rate desc, then median latency asc; baseline stays first. */
    fun sorted(results: List<Result>): List<Result> {
        val baseline = results.filter { it.kind == Kind.BASELINE }
        val others = results.filter { it.kind != Kind.BASELINE }.sortedWith(
            compareByDescending<Result> { it.finished }
                .thenByDescending { it.successRate }
                .thenBy { it.medianLatency ?: Long.MAX_VALUE }
        )
        return baseline + others
    }

    enum class Kind { BASELINE, CURRENT, STRATEGY }

    class Result(val kind: Kind, val args: String?) {
        @Volatile var total = 0
        @Volatile var done = 0
        @Volatile var success = 0
        @Volatile var finished = false
        @Volatile var error: String? = null
        val latencies: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

        /** label -> [success, done] per target. */
        val perTarget: MutableMap<String, IntArray> = java.util.Collections.synchronizedMap(LinkedHashMap())

        @Synchronized
        fun record(target: String, latency: Long?) {
            done++
            val counts = perTarget.getOrPut(target) { IntArray(2) }
            counts[1]++
            if (latency != null) {
                success++
                counts[0]++
                latencies.add(latency)
            }
        }

        val successRate: Double get() = if (total > 0) success.toDouble() / total else 0.0
        val medianLatency: Long? get() = median(synchronized(latencies) { latencies.toList() })
    }
}
