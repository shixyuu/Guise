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
 * Guise: pure logic of automatic ByeDPI strategy selection (pool of the best strategies,
 * when to re-test, when to fall back). The service side is ByeDPIAutoStrategy.
 */
object ByeDPIAuto {

    const val POOL_SIZE = 12
    const val DEFAULT_INTERVAL_MINUTES = 60
    const val MIN_INTERVAL_MINUTES = 10
    const val MAX_INTERVAL_MINUTES = 24 * 60

    /** Health check of the current strategy between full/pool tests. */
    const val HEALTH_INTERVAL_MS = 5 * 60_000L

    /** Re-run the whole strategy list at least this often. */
    const val FULL_TEST_MAX_AGE_MS = 24 * 60 * 60_000L

    /** Pool considered degraded (→ full test) when its best success rate drops below this. */
    const val DEGRADED_RATE = 0.5

    /** Health check: the current strategy failed when its success rate is below this. */
    const val HEALTH_FAIL_RATE = 0.34

    data class Entry(val args: String, val successRate: Double, val latency: Long?)

    /** One entry per line: rate \t latency(-1) \t args. Lines that don't parse are skipped. */
    fun encode(pool: List<Entry>): String = pool.joinToString("\n") {
        "${"%.3f".format(java.util.Locale.ROOT, it.successRate)}\t${it.latency ?: -1}\t${it.args}"
    }

    fun decode(text: String?): List<Entry> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3 || parts[2].isBlank()) return@mapNotNull null
        val rate = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val latency = parts[1].toLongOrNull()?.takeIf { it >= 0 }
        Entry(parts[2], rate, latency)
    }

    /**
     * The best [size] strategies of a finished test: rows with at least one success, by success
     * rate then median latency (same order as the connectivity test). The baseline row is never
     * a candidate.
     */
    fun pickPool(results: List<ByeDPIProbe.Result>, size: Int = POOL_SIZE): List<Entry> =
        ByeDPIProbe.sorted(results)
            .filter { it.kind != ByeDPIProbe.Kind.BASELINE && it.finished && it.error == null && it.success > 0 && it.args != null }
            .distinctBy { it.args }
            .take(size)
            .map { Entry(it.args!!, it.successRate, it.medianLatency) }

    fun needsFullTest(pool: List<Entry>, now: Long, lastFull: Long): Boolean =
        pool.isEmpty() || pool.first().successRate < DEGRADED_RATE || now - lastFull >= FULL_TEST_MAX_AGE_MS

    fun intervalMs(minutes: Int): Long =
        minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L

    fun healthFailed(rate: Double): Boolean = rate < HEALTH_FAIL_RATE

    /**
     * Strategy to switch to after a failed health check: the first pool entry (other than
     * [current]) whose fresh re-test succeeded at least [DEGRADED_RATE], else null.
     */
    fun fallback(current: String, retested: List<Entry>): Entry? =
        retested.filter { it.args != current && it.successRate >= DEGRADED_RATE }
            .sortedWith(compareByDescending<Entry> { it.successRate }.thenBy { it.latency ?: Long.MAX_VALUE })
            .firstOrNull()

    /**
     * Whether to switch from [current] (its fresh rate, null if not measured) to the pool's
     * best: only for a strictly better success rate, or equal rate and clearly (>20%) faster.
     */
    fun shouldSwitch(current: String, currentEntry: Entry?, best: Entry?): Boolean {
        if (best == null || best.args == current) return false
        if (currentEntry == null) return true
        if (best.successRate > currentEntry.successRate + 1e-9) return true
        if (best.successRate + 1e-9 < currentEntry.successRate) return false
        val a = best.latency ?: return false
        val b = currentEntry.latency ?: return true
        return a < b * 0.8
    }
}
