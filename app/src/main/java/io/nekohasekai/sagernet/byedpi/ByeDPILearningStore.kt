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

import io.nekohasekai.sagernet.database.DataStore

/**
 * Guise: Persistent state for CDN Learning Mode.
 *
 * - Known-good CDN domains are stored in [DataStore.smartSplitDomains].
 * - Per-domain failure counts are stored in [DataStore.byedpiLearningFailCounts].
 * - Domains that fail [MAX_FAILURES] (5) times across VPN sessions are moved into
 *   [DataStore.byedpiLearningBlocked] ("已知不可行域名") and removed from known-good.
 */
object ByeDPILearningStore {

    const val MAX_FAILURES = 5

    private fun normalize(domain: String): String =
        domain.trim().lowercase().trimEnd('.')

    private fun parseLines(raw: String?): LinkedHashSet<String> {
        val set = LinkedHashSet<String>()
        raw.orEmpty().lines().forEach { line ->
            val d = normalize(line)
            if (d.isNotEmpty() && !d.startsWith("#") && !CdnAsnMatcher.isIpLiteral(d)) {
                set.add(d)
            }
        }
        return set
    }

    private fun parseFailCounts(raw: String?): LinkedHashMap<String, Int> {
        val map = LinkedHashMap<String, Int>()
        raw.orEmpty().lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.lastIndexOf('=')
            if (idx <= 0) return@forEach
            val domain = normalize(trimmed.substring(0, idx))
            val count = trimmed.substring(idx + 1).toIntOrNull() ?: return@forEach
            if (domain.isNotEmpty() && count > 0) {
                map[domain] = count
            }
        }
        return map
    }

    private fun serializeFailCounts(map: Map<String, Int>): String =
        map.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

    @Synchronized
    fun knownGoodDomains(): List<String> =
        parseLines(DataStore.smartSplitDomains).sorted()

    @Synchronized
    fun blockedDomains(): List<String> =
        parseLines(DataStore.byedpiLearningBlocked).sorted()

    @Synchronized
    fun failCounts(): Map<String, Int> =
        parseFailCounts(DataStore.byedpiLearningFailCounts)

    @Synchronized
    fun isBlocked(domain: String): Boolean {
        val d = normalize(domain)
        if (d.isEmpty() || CdnAsnMatcher.isIpLiteral(d)) return false
        return parseLines(DataStore.byedpiLearningBlocked).contains(d)
    }

    @Synchronized
    fun recordSuccess(domain: String) {
        val d = normalize(domain)
        if (d.isEmpty() || CdnAsnMatcher.isIpLiteral(d)) return

        val blocked = parseLines(DataStore.byedpiLearningBlocked)
        if (blocked.contains(d)) return

        val counts = parseFailCounts(DataStore.byedpiLearningFailCounts)
        if (counts.remove(d) != null) {
            DataStore.byedpiLearningFailCounts = serializeFailCounts(counts)
        }

        val known = parseLines(DataStore.smartSplitDomains)
        if (known.add(d)) {
            DataStore.smartSplitDomains = known.sorted().joinToString("\n")
        }
    }

    /**
     * Increments failure count for [domain]. If it reaches [MAX_FAILURES],
     * moves [domain] into [DataStore.byedpiLearningBlocked] and removes it from
     * [DataStore.smartSplitDomains].
     *
     * @return updated failure count (or [MAX_FAILURES] if newly/already blocked).
     */
    @Synchronized
    fun recordFailure(domain: String): Int {
        val d = normalize(domain)
        if (d.isEmpty() || CdnAsnMatcher.isIpLiteral(d)) return 0

        val blocked = parseLines(DataStore.byedpiLearningBlocked)
        if (blocked.contains(d)) return MAX_FAILURES

        val counts = parseFailCounts(DataStore.byedpiLearningFailCounts)
        val next = (counts[d] ?: 0) + 1
        if (next >= MAX_FAILURES) {
            counts.remove(d)
            DataStore.byedpiLearningFailCounts = serializeFailCounts(counts)

            blocked.add(d)
            DataStore.byedpiLearningBlocked = blocked.sorted().joinToString("\n")

            val known = parseLines(DataStore.smartSplitDomains)
            if (known.remove(d)) {
                DataStore.smartSplitDomains = known.sorted().joinToString("\n")
            }
            return MAX_FAILURES
        } else {
            counts[d] = next
            DataStore.byedpiLearningFailCounts = serializeFailCounts(counts)
            return next
        }
    }

    @Synchronized
    fun resetBlockedAndFailures() {
        DataStore.byedpiLearningBlocked = ""
        DataStore.byedpiLearningFailCounts = ""
    }

    @Synchronized
    fun resetAll() {
        DataStore.smartSplitDomains = ""
        DataStore.byedpiLearningBlocked = ""
        DataStore.byedpiLearningFailCounts = ""
    }
}
