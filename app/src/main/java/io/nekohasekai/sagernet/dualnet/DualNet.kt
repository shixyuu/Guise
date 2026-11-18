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

package io.nekohasekai.sagernet.dualnet

/**
 * Guise: pure (Android-free) balancing logic for the dual-network (Wi-Fi + cellular) split.
 */
object DualNet {

    /** Must equal libexclavecore GuiseSplitMark / GuiseShapeMark / GuiseSplitShapeMark. */
    const val SPLIT_MARK = 0x47554953
    const val SHAPE_MARK = 0x47554954
    const val SPLIT_SHAPE_MARK = 0x47554955

    object Strategy {
        /** 1:1 round-robin per new proxy-server connection. */
        const val FIXED = 0

        /** Weighted by passively measured latency / jitter / loss of the live connections. */
        const val AUTO = 1
    }

    object Single {
        /** Keep non-split traffic on the system default network (normally Wi-Fi). */
        const val WIFI = 0

        /** Keep non-split traffic on cellular when it is up. */
        const val CELLULAR = 1
    }

    /** Auto mode: take one passive sample per this many KB sent on a network. */
    const val DEFAULT_SAMPLE_KB = 256
    const val MIN_SAMPLE_KB = 16
    const val MAX_SAMPLE_KB = 65536

    /** Auto mode: minimum seconds between active UDP probe rounds (0 = off). */
    const val DEFAULT_UDP_PROBE_INTERVAL = 10
    const val MIN_UDP_PROBE_INTERVAL = 3
    const val MAX_UDP_PROBE_INTERVAL = 600

    const val WINDOW = 10

    /** A network is considered dead after this many failures in a row (connect errors, stalls). */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** A dead network is offered new connections again (exploration) after this long. */
    const val DEAD_RETRY_MS = 30_000L

    /**
     * One passive measurement of a live proxy-server connection.
     * [rttMs] kernel smoothed RTT (or connect() handshake time), [rttVarMs] kernel RTT variance
     * (null if unknown), [loss] retransmitted share of segments since the previous sample.
     */
    data class Sample(val rttMs: Double, val rttVarMs: Double?, val loss: Double)

    /** Sliding window of passive samples (null = failure) for one network. */
    class LinkStats(private val window: Int = WINDOW) {
        private val samples = ArrayDeque<Sample?>()
        var consecutiveFailures = 0
            private set
        var deadSince = 0L
            private set

        @Synchronized
        fun add(sample: Sample?, now: Long = System.currentTimeMillis()) {
            samples.addLast(sample)
            while (samples.size > window) samples.removeFirst()
            if (sample == null) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) deadSince = now
            } else {
                consecutiveFailures = 0
                deadSince = 0
            }
        }

        /** Convenience for tests: RTT-only sample. */
        fun add(rttMs: Long?, now: Long = System.currentTimeMillis()) =
            add(rttMs?.let { Sample(it.toDouble(), null, 0.0) }, now)

        @Synchronized
        fun successes(): List<Sample> = samples.filterNotNull()

        val sampleCount: Int @Synchronized get() = samples.size

        /** Failures count as fully lost; successful samples contribute their retransmit share. */
        val loss: Double
            @Synchronized get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it?.loss ?: 1.0 } / samples.size

        val latency: Double?
            get() {
                val ok = successes().map { it.rttMs }.sorted()
                if (ok.isEmpty()) return null
                return median(ok)
            }

        /**
         * Median kernel RTT variance when known; otherwise the mean absolute difference between
         * consecutive RTT samples.
         */
        val jitter: Double
            get() {
                val ok = successes()
                val vars = ok.mapNotNull { it.rttVarMs }.sorted()
                if (vars.isNotEmpty()) return median(vars)
                if (ok.size < 2) return 0.0
                return ok.zipWithNext { a, b -> kotlin.math.abs(a.rttMs - b.rttMs) }.average()
            }

        val dead: Boolean
            @Synchronized get() = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

        /** Dead, but long enough ago that it should be tried again. */
        @Synchronized
        fun retryDue(now: Long) = dead && now - deadSince >= DEAD_RETRY_MS

        private fun median(sorted: List<Double>): Double {
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Weights for the available networks (sum 1). Score = (1 - loss)^2 / (latency + 2 * jitter + 10);
     * dead networks get 0 until [DEAD_RETRY_MS] has passed, then they are treated like a network
     * without data. Networks without data (no traffic yet) get the mean score of the healthy
     * ones, so they still receive connections and get measured; if every network looks dead,
     * they share equally.
     */
    fun weights(
        available: Collection<String>,
        stats: Map<String, LinkStats>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, Double> {
        if (available.isEmpty()) return emptyMap()
        if (available.size == 1) return mapOf(available.first() to 1.0)
        val scores = LinkedHashMap<String, Double?>()
        for (id in available) {
            val s = stats[id]
            scores[id] = when {
                s == null || s.sampleCount == 0 -> null
                s.retryDue(now) -> null
                s.dead -> 0.0
                else -> {
                    val latency = s.latency
                    if (latency == null) 0.0
                    else (1 - s.loss) * (1 - s.loss) / (latency + 2 * s.jitter + 10.0)
                }
            }
        }
        val known = scores.values.filterNotNull()
        val fill = if (known.any { it > 0 }) known.filter { it > 0 }.average() else 1.0
        val filled = scores.mapValues { it.value ?: fill }
        val total = filled.values.sum()
        if (total <= 0.0) return available.associateWith { 1.0 / available.size }
        return filled.mapValues { it.value / total }
    }

    /**
     * Picks a network per new connection. FIXED alternates over the available networks; AUTO uses
     * smooth weighted round-robin (as in nginx) so the split follows the weights closely without
     * randomness.
     */
    class Picker {
        private val current = HashMap<String, Double>()
        private var rr = 0

        @Synchronized
        fun pickFixed(available: List<String>): String? {
            if (available.isEmpty()) return null
            val id = available[Math.floorMod(rr, available.size)]
            rr++
            return id
        }

        @Synchronized
        fun pickWeighted(weights: Map<String, Double>): String? {
            val live = weights.filterValues { it > 0 }
            if (live.isEmpty()) return null
            current.keys.retainAll(live.keys)
            val total = live.values.sum()
            var best: String? = null
            for ((id, w) in live) {
                val c = (current[id] ?: 0.0) + w
                current[id] = c
                if (best == null || c > current[best]!!) best = id
            }
            current[best!!] = current[best]!! - total
            return best
        }
    }

    fun formatWeights(weights: Map<String, Double>): String =
        weights.entries.joinToString(" ") { "${it.key}=${(it.value * 100).toInt()}%" }
}
