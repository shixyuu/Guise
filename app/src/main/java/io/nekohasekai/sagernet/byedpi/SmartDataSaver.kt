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

import android.net.Network
import android.util.Base64
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Guise: Smart Data-Saver Split ("智能省流分流").
 *
 * Classifies high-value candidate domains into:
 * - DPI-only blocked (IP reachable on TCP:443 and TLS succeeds via ByeDPI) -> routed via ByeDPI
 * - IP-blocked / unreachable -> routed via encrypted proxy
 *
 * Pipeline:
 * 1. Resolve candidate domains via DoH (bypassing poisoned local DNS).
 * 2. Deduplicate resolved IPv4 addresses so each unique IP is probed only once.
 * 3. TCP:443 connect check on each unique IP to filter out IP-blocked targets.
 * 4. Verify TLS handshake/HTTP response via local ByeDPI SOCKS5 on surviving domains.
 * 5. Persist confirmed DPI-only domains into [DataStore.smartSplitDomains].
 */
object SmartDataSaver {

    private const val TAG = "SmartDataSaver"
    private const val TCP_TIMEOUT_MS = 2500
    private const val HTTPS_TIMEOUT_MS = 4500
    private const val CONCURRENCY = 8

    data class Progress(
        val phase: Phase,
        val done: Int,
        val total: Int,
    )

    enum class Phase {
        RESOLVING_DNS,
        PROBING_IPS,
        VERIFYING_BYEDPI,
    }

    data class ScanResult(
        val dpiDomains: List<String>,
        val ipBlockedDomains: List<String>,
        val failedDnsDomains: List<String>,
        val uniqueIpsTested: Int,
    )

    val DEFAULT_CANDIDATE_DOMAINS: List<String> by lazy {
        val fromPresets = ByeDPIProbeTargets.PRESETS
            .filter { it.tag == ByeDPIProbeTargets.Tag.SNI || it.tag == ByeDPIProbeTargets.Tag.UNCERTAIN }
            .flatMap { it.hosts }
        val extra = listOf(
            "github.com",
            "gist.github.com",
            "raw.githubusercontent.com",
            "en.wikipedia.org",
            "store.steampowered.com",
            "v2ex.com",
        )
        (fromPresets + extra).distinct()
    }

    fun candidateDomains(): List<String> {
        val saved = DataStore.smartSplitDomains
            .lines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val customTargets = DataStore.byedpiProbeTargets
            .lines()
            .filter { it.startsWith(ByeDPIProbeTargets.CUSTOM_PREFIX) }
            .mapNotNull { ByeDPIProbeTargets.hostOf(it.removePrefix(ByeDPIProbeTargets.CUSTOM_PREFIX).trim()) }
        return (DEFAULT_CANDIDATE_DOMAINS + customTargets + saved).distinct()
    }

    suspend fun scanAndSave(
        domains: List<String> = candidateDomains(),
        underlyingNetwork: Network? = null,
        onProgress: (Progress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val candidates = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val dohServers = ByeDPI.parseDnsServers(DataStore.byedpiDns).filter { it.startsWith("https://") }
        val sem = Semaphore(CONCURRENCY)

        // Step 1: DoH resolution
        val resolvedMap = mutableMapOf<String, InetAddress>()
        val failedDns = mutableListOf<String>()
        val dnsDone = AtomicInteger(0)
        onProgress(Progress(Phase.RESOLVING_DNS, 0, candidates.size))

        coroutineScope {
            candidates.map { host ->
                async {
                    sem.withPermit {
                        val addr = resolveDoh(host, dohServers, underlyingNetwork)
                            ?: resolveSystem(host, underlyingNetwork)
                        synchronized(resolvedMap) {
                            if (addr != null) resolvedMap[host] = addr else failedDns.add(host)
                        }
                        onProgress(Progress(Phase.RESOLVING_DNS, dnsDone.incrementAndGet(), candidates.size))
                    }
                }
            }.awaitAll()
        }

        // Step 2: Deduplicate by IPv4 address
        val ipToHosts = resolvedMap.entries.groupBy(
            keySelector = { it.value.hostAddress ?: "" },
            valueTransform = { it.key to it.value },
        ).filterKeys { it.isNotEmpty() }

        val uniqueIps = ipToHosts.keys.toList()
        val ipReachable = mutableMapOf<String, Boolean>()
        val ipDone = AtomicInteger(0)
        onProgress(Progress(Phase.PROBING_IPS, 0, uniqueIps.size))

        // Step 3: TCP:443 pre-filter on unique IPs
        coroutineScope {
            uniqueIps.map { ip ->
                async {
                    sem.withPermit {
                        val addr = ipToHosts[ip]!!.first().second
                        val ok = checkTcp443(addr, underlyingNetwork)
                        synchronized(ipReachable) { ipReachable[ip] = ok }
                        onProgress(Progress(Phase.PROBING_IPS, ipDone.incrementAndGet(), uniqueIps.size))
                    }
                }
            }.awaitAll()
        }

        val tcpReachableHosts = mutableListOf<Pair<String, InetAddress>>()
        val ipBlocked = mutableListOf<String>()
        for ((ip, pairs) in ipToHosts) {
            if (ipReachable[ip] == true) {
                tcpReachableHosts.addAll(pairs)
            } else {
                ipBlocked.addAll(pairs.map { it.first })
            }
        }

        // Step 4: Verify TLS via ByeDPI
        val dpiConfirmed = mutableListOf<String>()
        if (tcpReachableHosts.isNotEmpty()) {
            val verifyDone = AtomicInteger(0)
            onProgress(Progress(Phase.VERIFYING_BYEDPI, 0, tcpReachableHosts.size))
            val byedpi = runCatching { startLocalByeDPI() }.getOrNull()
            try {
                if (byedpi != null) {
                    val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", byedpi.second))
                    coroutineScope {
                        tcpReachableHosts.map { (host, _) ->
                            async {
                                sem.withPermit {
                                    val ok = verifyHttpsViaProxy(host, socksProxy)
                                    synchronized(dpiConfirmed) {
                                        if (ok) dpiConfirmed.add(host) else ipBlocked.add(host)
                                    }
                                    onProgress(Progress(Phase.VERIFYING_BYEDPI, verifyDone.incrementAndGet(), tcpReachableHosts.size))
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    // Fallback if ByeDPI binary couldn't start during scan: trust TCP-reachable list
                    dpiConfirmed.addAll(tcpReachableHosts.map { it.first })
                    onProgress(Progress(Phase.VERIFYING_BYEDPI, tcpReachableHosts.size, tcpReachableHosts.size))
                }
            } finally {
                byedpi?.first?.destroy()
            }
        }

        val sortedDpi = dpiConfirmed.distinct().sorted()
        DataStore.smartSplitDomains = sortedDpi.joinToString("\n")
        Log.i(TAG, "SmartDataSaver scan done: ${sortedDpi.size} DPI-only, ${ipBlocked.size} IP-blocked, ${failedDns.size} DNS failed (${uniqueIps.size} unique IPs)")
        ScanResult(
            dpiDomains = sortedDpi,
            ipBlockedDomains = ipBlocked.distinct().sorted(),
            failedDnsDomains = failedDns.distinct().sorted(),
            uniqueIpsTested = uniqueIps.size,
        )
    }

    private fun checkTcp443(address: InetAddress, network: Network?): Boolean {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        return try {
            socket.use {
                it.connect(InetSocketAddress(address, 443), TCP_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyHttpsViaProxy(host: String, proxy: Proxy): Boolean {
        return try {
            val url = URL("https://$host/")
            val conn = url.openConnection(proxy) as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = HTTPS_TIMEOUT_MS
            conn.readTimeout = HTTPS_TIMEOUT_MS
            conn.requestMethod = "HEAD"
            try {
                val code = conn.responseCode
                code in 100..599
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveSystem(host: String, network: Network?): InetAddress? = try {
        val all = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun resolveDoh(host: String, servers: List<String>, network: Network?): InetAddress? {
        val query = Base64.encodeToString(
            ByeDPIProbe.buildDnsQuery(host),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        for (server in servers) {
            try {
                val serverHost = ByeDPI.dnsServerHost(server) ?: continue
                val pinned = ByeDPI.KNOWN_DOH_HOSTS[serverHost.lowercase()]?.firstOrNull()
                val url = URL(server + (if (server.contains('?')) "&" else "?") + "dns=" + query)
                val targetUrl = if (pinned != null) {
                    URL(url.protocol, pinned, url.port, url.file)
                } else {
                    url
                }
                val conn = (network?.openConnection(targetUrl) ?: targetUrl.openConnection()) as HttpsURLConnection
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                conn.setRequestProperty("Accept", "application/dns-message")
                if (pinned != null) conn.setRequestProperty("Host", serverHost)
                try {
                    if (conn.responseCode != 200) continue
                    val body = conn.inputStream.use { it.readBytes() }
                    val ip = ByeDPIProbe.parseDnsA(body).firstOrNull() ?: continue
                    return InetAddress.getByName(ip)
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.d("SmartDataSaver DoH failed for $host via $server: ${e.message}")
            }
        }
        return null
    }

    private fun startLocalByeDPI(): Pair<Process, Int>? {
        val libDir = SagerNet.application.applicationInfo.nativeLibraryDir
        val bin = File(libDir, "libbyedpi.so")
        if (!bin.exists()) return null
        val port = java.net.ServerSocket(0).use { it.localPort }
        val cmd = mutableListOf(bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString())
        val rawArgs = ByeDPI.effectiveArgs(DataStore.byedpiArgs)
        cmd.addAll(ByeDPI.sanitizeArgs(rawArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        Thread.sleep(150)
        return process to port
    }
}
