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

    /** Must equal libexclavecore GuiseSplitMark. */
    const val SPLIT_MARK = 0x47554953

    object Strategy {
        /** 1:1 round-robin per new proxy-server connection. */
        const val FIXED = 0

        /** Weighted by probed latency / jitter / loss. */
        const val AUTO = 1
    }

    object Single {
        /** Keep non-split traffic on the system default network (normally Wi-Fi). */
        const val WIFI = 0

        /** Keep non-split traffic on cellular when it is up. */
        const val CELLULAR = 1
    }

    const val DEFAULT_PROBE_INTERVAL = 15
    const val WINDOW = 10

    /** A network is considered dead after this many probe failures in a row. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** Sliding window of probe results (RTT in ms, null = failure). */
    class LinkStats(private val window: Int = WINDOW) {
        private val samples = ArrayDeque<Long?>()
        var consecutiveFailures = 0
            private set

        @Synchronized
        fun add(rtt: Long?) {
            samples.addLast(rtt)
            while (samples.size > window) samples.removeFirst()
            consecutiveFailures = if (rtt == null) consecutiveFailures + 1 else 0
        }

        @Synchronized
        fun successes(): List<Long> = samples.filterNotNull()

        val sampleCount: Int @Synchronized get() = samples.size

        val loss: Double
            @Synchronized get() = if (samples.isEmpty()) 0.0 else samples.count { it == null }.toDouble() / samples.size

        val latency: Double?
            get() {
                val ok = successes().sorted()
                if (ok.isEmpty()) return null
                val mid = ok.size / 2
                return if (ok.size % 2 == 1) ok[mid].toDouble() else (ok[mid - 1] + ok[mid]) / 2.0
            }

        /** Mean absolute difference between consecutive successful samples. */
        val jitter: Double
            get() {
                val ok = successes()
                if (ok.size < 2) return 0.0
                return ok.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
            }

        val dead: Boolean get() = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
    }

    /**
     * Weights for the available networks (sum 1). Score = (1 - loss)^2 / (latency + 2 * jitter + 10);
     * dead networks get 0. Networks without data yet get the mean score of the others (or equal
     * weights when nothing is known); if every network looks dead, they share equally (the
     * probe target may simply not answer TCP, e.g. a UDP-only server behind a firewall).
     */
    fun weights(available: Collection<String>, stats: Map<String, LinkStats>): Map<String, Double> {
        if (available.isEmpty()) return emptyMap()
        if (available.size == 1) return mapOf(available.first() to 1.0)
        val scores = LinkedHashMap<String, Double?>()
        for (id in available) {
            val s = stats[id]
            scores[id] = when {
                s == null || s.sampleCount == 0 -> null
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
