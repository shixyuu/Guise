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

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Guise: Local ASN / IPv4 CIDR matcher for major CDN providers (Cloudflare AS13335, Fastly AS54113).
 *
 * Used by both [CdnDpiScanner] and [ByeDPILearningRelay] to identify CDN-backed targets locally
 * without external API calls.
 */
object CdnAsnMatcher {

    enum class CdnProvider(val asn: String, val displayName: String) {
        CLOUDFLARE("AS13335", "Cloudflare"),
        FASTLY("AS54113", "Fastly"),
    }

    val CLOUDFLARE_CIDRS: List<String> = listOf(
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
    )

    val FASTLY_CIDRS: List<String> = listOf(
        "23.235.32.0/20",
        "43.249.72.0/22",
        "103.244.50.0/24",
        "103.245.222.0/23",
        "103.245.224.0/24",
        "104.156.80.0/20",
        "140.248.64.0/18",
        "140.248.128.0/17",
        "146.75.0.0/17",
        "151.101.0.0/16",
        "157.52.64.0/18",
        "167.82.0.0/17",
        "167.82.128.0/20",
        "167.82.160.0/20",
        "167.82.224.0/20",
        "172.111.64.0/18",
        "185.31.16.0/22",
        "199.27.72.0/21",
        "199.232.0.0/16",
    )

    val ALL_CIDRS: List<String> = CLOUDFLARE_CIDRS + FASTLY_CIDRS

    private data class CidrRange(val network: Int, val mask: Int, val provider: CdnProvider)

    private val ranges: List<CidrRange> by lazy {
        buildList {
            CLOUDFLARE_CIDRS.forEach { parseCidr(it, CdnProvider.CLOUDFLARE)?.let(::add) }
            FASTLY_CIDRS.forEach { parseCidr(it, CdnProvider.FASTLY)?.let(::add) }
        }
    }

    private fun parseCidr(cidr: String, provider: CdnProvider): CidrRange? {
        val parts = cidr.split('/')
        if (parts.size != 2) return null
        val ipInt = ipv4ToInt(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return CidrRange(ipInt and mask, mask, provider)
    }

    private fun ipv4ToInt(ip: String): Int? {
        val octets = ip.trim().split('.')
        if (octets.size != 4) return null
        var acc = 0
        for (o in octets) {
            val v = o.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            acc = (acc shl 8) or v
        }
        return acc
    }

    fun matchAsn(ip: String): CdnProvider? {
        val ipInt = ipv4ToInt(ip) ?: return null
        for (range in ranges) {
            if ((ipInt and range.mask) == range.network) {
                return range.provider
            }
        }
        return null
    }

    fun matchAsn(addr: InetAddress): CdnProvider? {
        val v4 = addr as? Inet4Address ?: return null
        val b = v4.address
        val ipInt = ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
        for (range in ranges) {
            if ((ipInt and range.mask) == range.network) {
                return range.provider
            }
        }
        return null
    }

    fun isCdnIp(ip: String): Boolean = matchAsn(ip) != null

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty()) return false
        if (h.contains(':')) return true // IPv6 literal
        return ipv4ToInt(h) != null
    }
}
