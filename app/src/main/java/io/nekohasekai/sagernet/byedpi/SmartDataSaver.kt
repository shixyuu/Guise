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
import android.os.Build
import android.util.Base64
import android.util.Log
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
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
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Guise: Smart Data-Saver Split ("智能省流分流").
 *
 * Pipeline:
 * 1. Resolve candidate domains via DoH (bypassing poisoned local DNS, pinning known DoH IPs).
 * 2. Deduplicate resolved IPv4 addresses so each unique IP is probed only once.
 * 3. TCP:443 connect check on each unique IP over the underlying network to exclude IP-blocked targets.
 * 4. Verify TLS handshake/HTTP response via local ByeDPI SOCKS5 (using ByeDPI.buildCommand + protectPath).
 * 5. Persist confirmed DPI-only domains into [DataStore.smartSplitDomains].
 */
object SmartDataSaver {

    private const val TAG = "SmartDataSaver"
    private const val TCP_TIMEOUT_MS = 2500
    private const val HTTPS_TIMEOUT_MS = 5000
    private const val CONCURRENCY = 6
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    data class ScanService(
        val id: String,
        val label: String,
        val hosts: List<String>,
        val defaultSelected: Boolean = true,
    )

    val SERVICES: List<ScanService> = listOf(
        ScanService(
            "github",
            "GitHub (Fastly CDN)",
            listOf("github.com", "gist.github.com", "raw.githubusercontent.com"),
        ),
        ScanService(
            "reddit",
            "Reddit (Fastly CDN)",
            listOf("www.reddit.com", "www.redditstatic.com"),
        ),
        ScanService(
            "wikipedia",
            "Wikipedia (Fastly CDN)",
            listOf("zh.wikipedia.org", "en.wikipedia.org", "upload.wikimedia.org"),
        ),
        ScanService(
            "twitch",
            "Twitch (Fastly CDN)",
            listOf("www.twitch.tv", "static-cdn.jtvnw.net"),
        ),
        ScanService(
            "discord",
            "Discord (Cloudflare CDN)",
            listOf("discord.com"),
        ),
        ScanService(
            "pixiv",
            "Pixiv (Cloudflare CDN)",
            listOf("www.pixiv.net", "s.pximg.net"),
        ),
        ScanService(
            "archive",
            "Internet Archive (Cloudflare)",
            listOf("archive.org"),
        ),
        ScanService(
            "cf_sites",
            "V2EX / Medium / Docker / npm (Cloudflare)",
            listOf("v2ex.com", "medium.com", "www.docker.com", "www.npmjs.com"),
        ),
        ScanService(
            "x",
            "X / Twitter",
            listOf("x.com", "abs.twimg.com", "pbs.twimg.com"),
        ),
        ScanService(
            "steam",
            "Steam Community",
            listOf("steamcommunity.com", "store.steampowered.com"),
        ),
    )

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

    fun candidateDomains(): List<String> {
        val defaults = SERVICES.filter { it.defaultSelected }.flatMap { it.hosts }
        val saved = DataStore.smartSplitDomains
            .lines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val customTargets = DataStore.byedpiProbeTargets
            .lines()
            .filter { it.startsWith(ByeDPIProbeTargets.CUSTOM_PREFIX) }
            .mapNotNull { ByeDPIProbeTargets.hostOf(it.removePrefix(ByeDPIProbeTargets.CUSTOM_PREFIX).trim()) }
        return (defaults + customTargets + saved).distinct()
    }

    suspend fun scanAndSave(
        domains: List<String> = candidateDomains(),
        onProgress: (Progress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val candidates = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val dohServers = ByeDPI.parseDnsServers(DataStore.byedpiDns).filter { it.startsWith("https://") }
        val sem = Semaphore(CONCURRENCY)

        val vpnActive = SagerNet.started && DataStore.serviceMode == Key.MODE_VPN
        val vpnIncludesSelf = vpnActive && DataStore.tunImplementation == TunImplementation.SYSTEM
        val protectPath = if (
            vpnActive && (DataStore.tunImplementation == TunImplementation.SYSTEM || DataStore.effectiveDualNetEnabled)
        ) {
            SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
        } else null

        val listenerKey = Any()
        val underlying: Network? = if (vpnIncludesSelf) {
            DefaultNetworkListener.start(listenerKey) {}
            withTimeoutOrNull(3000L) { runCatching { DefaultNetworkListener.get() }.getOrNull() }
        } else null

        try {
            // Step 1: DoH resolution
            val resolvedMap = mutableMapOf<String, InetAddress>()
            val failedDns = mutableListOf<String>()
            val dnsDone = AtomicInteger(0)
            onProgress(Progress(Phase.RESOLVING_DNS, 0, candidates.size))

            coroutineScope {
                candidates.map { host ->
                    async {
                        sem.withPermit {
                            val addr = resolveDoh(host, dohServers, underlying)
                                ?: resolveSystem(host, underlying)
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
                            val ok = checkTcp443(addr, underlying)
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

            // Step 4: Verify TLS via local ByeDPI
            val dpiConfirmed = mutableListOf<String>()
            if (tcpReachableHosts.isNotEmpty()) {
                val verifyDone = AtomicInteger(0)
                onProgress(Progress(Phase.VERIFYING_BYEDPI, 0, tcpReachableHosts.size))
                val byedpi = runCatching { startLocalByeDPI(protectPath) }.getOrNull()
                try {
                    if (byedpi != null) {
                        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", byedpi.second))
                        coroutineScope {
                            tcpReachableHosts.map { (host, addr) ->
                                async {
                                    sem.withPermit {
                                        val ok = verifyHttpsViaProxy(host, addr, socksProxy)
                                        synchronized(dpiConfirmed) {
                                            if (ok) dpiConfirmed.add(host) else ipBlocked.add(host)
                                        }
                                        onProgress(Progress(Phase.VERIFYING_BYEDPI, verifyDone.incrementAndGet(), tcpReachableHosts.size))
                                    }
                                }
                            }.awaitAll()
                        }
                    } else {
                        // Fallback if ByeDPI binary couldn't start: keep TCP-reachable hosts
                        dpiConfirmed.addAll(tcpReachableHosts.map { it.first })
                        onProgress(Progress(Phase.VERIFYING_BYEDPI, tcpReachableHosts.size, tcpReachableHosts.size))
                    }
                } finally {
                    byedpi?.first?.destroy()
                }
            }

            val sortedDpi = dpiConfirmed.distinct().sorted()
            DataStore.smartSplitDomains = sortedDpi.joinToString("\n")
            Log.i(
                TAG,
                "SmartDataSaver scan done: ${sortedDpi.size} DPI-only, ${ipBlocked.size} IP-blocked, " +
                    "${failedDns.size} DNS failed (${uniqueIps.size} unique IPs)"
            )
            ScanResult(
                dpiDomains = sortedDpi,
                ipBlockedDomains = ipBlocked.distinct().sorted(),
                failedDnsDomains = failedDns.distinct().sorted(),
                uniqueIpsTested = uniqueIps.size,
            )
        } finally {
            if (vpnIncludesSelf) DefaultNetworkListener.stop(listenerKey)
        }
    }

    private fun checkTcp443(address: InetAddress, network: Network?): Boolean {
        val socket = Socket()
        return try {
            network?.bindSocket(socket)
            socket.use {
                it.connect(InetSocketAddress(address, 443), TCP_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyHttpsViaProxy(host: String, address: InetAddress, proxy: Proxy): Boolean {
        return tlsExchange(
            host = host,
            port = 443,
            path = "/",
            address = address,
            proxy = proxy,
            network = null,
            method = "GET",
            accept = "*/*",
            timeout = HTTPS_TIMEOUT_MS,
            bodyNeeded = false,
        ) != null
    }

    private fun resolveSystem(host: String, network: Network?): InetAddress? = try {
        val all = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun resolveDoh(host: String, servers: List<String>, proxy: Proxy?, network: Network?): InetAddress? {
        val query = Base64.encodeToString(
            ByeDPIProbe.buildDnsQuery(host),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        for (server in servers) {
            try {
                val url = URL(server + (if (server.contains('?')) "&" else "?") + "dns=" + query)
                val serverHost = ByeDPI.dnsServerHost(server) ?: continue
                val pinned = ByeDPI.KNOWN_DOH_HOSTS[serverHost.lowercase()]?.firstOrNull()
                val address = if (pinned != null) {
                    InetAddress.getByName(pinned)
                } else {
                    resolveSystem(serverHost, network) ?: continue
                }
                val port = if (url.port > 0) url.port else 443
                val result = tlsExchange(
                    host = serverHost,
                    port = port,
                    path = url.file.ifEmpty { "/dns-query" },
                    address = address,
                    proxy = proxy,
                    network = if (proxy == null) network else null,
                    method = "GET",
                    accept = "application/dns-message",
                    timeout = 4000,
                    bodyNeeded = true,
                ) ?: continue
                if (result.first != 200 || result.second == null) continue
                val ip = ByeDPIProbe.parseDnsA(result.second!!).firstOrNull() ?: continue
                return InetAddress.getByName(ip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.d("SmartDataSaver DoH failed for $host via $server: ${e.message}")
            }
        }
        return null
    }

    private fun tlsExchange(
        host: String,
        port: Int,
        path: String,
        address: InetAddress,
        proxy: Proxy?,
        network: Network?,
        method: String,
        accept: String,
        timeout: Int,
        bodyNeeded: Boolean,
    ): Pair<Int, ByteArray?>? {
        var raw: Socket? = null
        try {
            raw = if (proxy != null) Socket(proxy) else Socket()
            if (proxy == null) network?.bindSocket(raw)
            raw.soTimeout = timeout
            raw.connect(InetSocketAddress(address, port), timeout)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                return null
            }
            ssl.getOutputStream().apply {
                write(
                    ("$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\n" +
                        "Accept: $accept\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .toByteArray()
                )
                flush()
            }
            val body = if (bodyNeeded) java.io.ByteArrayOutputStream() else null
            val code = ByeDPIProbe.readResponse(ssl.getInputStream(), sink = body) ?: return null
            return code to body?.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            try {
                raw?.close()
            } catch (_: IOException) {
            }
        }
    }

    private suspend fun startLocalByeDPI(protectPath: String?): Pair<Process, Int>? {
        val executable = File(SagerNet.application.applicationInfo.nativeLibraryDir, ByeDPI.EXECUTABLE)
        if (!executable.canExecute()) return null
        val port = ServerSocket(0).use { it.localPort }
        val args = ByeDPI.effectiveArgs(DataStore.byedpiArgs)
        val cmd = ByeDPI.buildCommand(executable.absolutePath, port, args, protectPath)
        val process = ProcessBuilder(cmd)
            .directory(SagerNet.deviceStorage.noBackupFilesDir)
            .redirectErrorStream(true)
            .start()
        kotlin.concurrent.thread(name = "smart-byedpi-$port", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    Log.d("libbyedpi", "[smart:$port] $it")
                }
            } catch (_: IOException) {
            }
        }
        if (!ByeDPI.waitForListening(port)) {
            process.destroy()
            return null
        }
        return process to port
    }
}
