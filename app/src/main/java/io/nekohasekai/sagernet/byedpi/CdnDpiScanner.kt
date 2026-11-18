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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Guise v2.0: 3-Phase CDN / DPI Smart Scanner ("CDN / DPI 智能扫描").
 *
 * Replaces legacy SmartDataSaver with:
 * 1. Pure Kotlin lightweight SOCKS5 dialer (no java.net.Proxy(SOCKS) compatibility quirks).
 * 2. Pure DoH resolution against pinned DoH IPs (1.1.1.1, 1.0.0.1, 8.8.8.8, 9.9.9.9) — never
 *    falls back to polluted system DNS.
 * 3. Underlying TCP:443 reachability check + TLS Handshake & Hostname verification via local
 *    ByeDPI (no HTTP GET, eliminating HTTP 403 / WAF / redirect false negatives).
 * 4. 4-category classification:
 *    - ✓ 可走 ByeDPI ([ScanResult.byedpiDomains])
 *    - ⚡ ByeDPI 策略无效 ([ScanResult.strategyFailedDomains])
 *    - ✕ IP 阻断 ([ScanResult.ipBlockedDomains])
 *    - ? DNS 失败 ([ScanResult.dnsFailedDomains])
 */
object CdnDpiScanner {

    private const val TAG = "CdnDpiScanner"
    private const val TCP_TIMEOUT_MS = 2500
    private const val DOH_TIMEOUT_MS = 3800
    private const val TLS_TIMEOUT_MS = 4500
    private const val CONCURRENCY = 6
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private data class DohEndpoint(
        val host: String,
        val ip: String,
        val port: Int = 443,
        val path: String = "/dns-query",
    )

    private val PINNED_DOH_ENDPOINTS = listOf(
        DohEndpoint("1.1.1.1", "1.1.1.1"),
        DohEndpoint("1.0.0.1", "1.0.0.1"),
        DohEndpoint("8.8.8.8", "8.8.8.8"),
        DohEndpoint("9.9.9.9", "9.9.9.9"),
    )

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
        val byedpiDomains: List<String>,
        val strategyFailedDomains: List<String>,
        val ipBlockedDomains: List<String>,
        val dnsFailedDomains: List<String>,
        val uniqueIpsTested: Int,
        val cdnProviders: Map<String, CdnAsnMatcher.CdnProvider> = emptyMap(),
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
        val candidates = domains.map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.distinct()
        val dohEndpoints = buildDohEndpoints(DataStore.byedpiDns)
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

        val byedpi = runCatching { startLocalByeDPI(protectPath) }.getOrNull()
        val byedpiPort = byedpi?.second

        try {
            // Phase 1: Pure DoH resolution (no fallback to polluted system DNS)
            val resolvedMap = mutableMapOf<String, InetAddress>()
            val dnsFailed = mutableListOf<String>()
            val dnsDone = AtomicInteger(0)
            onProgress(Progress(Phase.RESOLVING_DNS, 0, candidates.size))

            coroutineScope {
                candidates.map { host ->
                    async {
                        sem.withPermit {
                            val addr = resolvePureDoh(
                                host = host,
                                endpoints = dohEndpoints,
                                byedpiPort = byedpiPort,
                                preferByeDPI = DataStore.byedpiDnsViaByedpi,
                                network = underlying,
                            )
                            synchronized(resolvedMap) {
                                if (addr != null) resolvedMap[host] = addr else dnsFailed.add(host)
                            }
                            onProgress(Progress(Phase.RESOLVING_DNS, dnsDone.incrementAndGet(), candidates.size))
                        }
                    }
                }.awaitAll()
            }

            // Tag resolved hosts with local CDN ASN (Cloudflare AS13335 / Fastly AS54113)
            val cdnProviders = mutableMapOf<String, CdnAsnMatcher.CdnProvider>()
            for ((host, addr) in resolvedMap) {
                CdnAsnMatcher.matchAsn(addr)?.let { cdnProviders[host] = it }
            }

            // Phase 2: Deduplicate by IPv4 address & probe underlying TCP:443 reachability
            val ipToHosts = resolvedMap.entries.groupBy(
                keySelector = { it.value.hostAddress ?: "" },
                valueTransform = { it.key to it.value },
            ).filterKeys { it.isNotEmpty() }

            val uniqueIps = ipToHosts.keys.toList()
            val ipReachable = mutableMapOf<String, Boolean>()
            val ipDone = AtomicInteger(0)
            onProgress(Progress(Phase.PROBING_IPS, 0, uniqueIps.size))

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

            // Phase 3: Verify TLS Handshake + Certificate Hostname via local ByeDPI (no HTTP GET)
            val byedpiConfirmed = mutableListOf<String>()
            val strategyFailed = mutableListOf<String>()
            if (tcpReachableHosts.isNotEmpty()) {
                val verifyDone = AtomicInteger(0)
                onProgress(Progress(Phase.VERIFYING_BYEDPI, 0, tcpReachableHosts.size))
                if (byedpiPort != null) {
                    coroutineScope {
                        tcpReachableHosts.map { (host, addr) ->
                            async {
                                sem.withPermit {
                                    val ok = verifyTlsViaByeDPI(host, addr, byedpiPort)
                                    synchronized(byedpiConfirmed) {
                                        if (ok) byedpiConfirmed.add(host) else strategyFailed.add(host)
                                    }
                                    onProgress(Progress(Phase.VERIFYING_BYEDPI, verifyDone.incrementAndGet(), tcpReachableHosts.size))
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    strategyFailed.addAll(tcpReachableHosts.map { it.first })
                    onProgress(Progress(Phase.VERIFYING_BYEDPI, tcpReachableHosts.size, tcpReachableHosts.size))
                }
            }

            val sortedByedpi = byedpiConfirmed.distinct().sorted()
            val sortedStrategyFailed = strategyFailed.distinct().sorted()
            val sortedIpBlocked = ipBlocked.distinct().sorted()
            val sortedDnsFailed = dnsFailed.distinct().sorted()

            val blockedSet = ByeDPILearningStore.blockedDomains().toSet()
            val merged = DataStore.smartSplitDomains
                .lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toMutableSet()
            merged.removeAll(sortedIpBlocked.toSet())
            merged.removeAll(sortedStrategyFailed.toSet())
            merged.removeAll(blockedSet)
            sortedByedpi.forEach { host ->
                if (host !in blockedSet) {
                    merged.add(host)
                    ByeDPILearningStore.recordSuccess(host)
                }
            }
            DataStore.smartSplitDomains = merged.sorted().joinToString("\n")

            Log.i(
                TAG,
                "CdnDpiScanner done: ${sortedByedpi.size} ByeDPI OK, ${sortedStrategyFailed.size} strategy failed, " +
                    "${sortedIpBlocked.size} IP-blocked, ${sortedDnsFailed.size} DNS failed (${uniqueIps.size} unique IPs)",
            )
            ScanResult(
                byedpiDomains = sortedByedpi,
                strategyFailedDomains = sortedStrategyFailed,
                ipBlockedDomains = sortedIpBlocked,
                dnsFailedDomains = sortedDnsFailed,
                uniqueIpsTested = uniqueIps.size,
                cdnProviders = cdnProviders,
            )
        } finally {
            byedpi?.first?.destroy()
            if (vpnIncludesSelf) DefaultNetworkListener.stop(listenerKey)
        }
    }

    /**
     * Pure Kotlin lightweight SOCKS5 dialer (~30 lines).
     * Connects to 127.0.0.1:[socksPort] and requests CONNECT to [targetAddr]:[targetPort].
     */
    fun dialSocks5(
        socksPort: Int,
        targetAddr: InetAddress,
        targetPort: Int,
        timeoutMs: Int,
    ): Socket {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            val out = sock.getOutputStream()
            val input = DataInputStream(sock.getInputStream())

            // 1. Greeting: VER=5, NMETHODS=1, NO AUTH=0
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            if (input.readUnsignedByte() != 0x05 || input.readUnsignedByte() != 0x00) {
                throw IOException("SOCKS5 auth negotiation failed")
            }

            // 2. CONNECT: VER=5, CMD=1, RSV=0, ATYP=1(IPv4)/4(IPv6), ADDR, PORT
            val rawAddr = targetAddr.address
            val atyp: Byte = if (targetAddr is Inet6Address) 0x04.toByte() else 0x01.toByte()
            val req = ByteArray(4 + rawAddr.size + 2)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = atyp
            System.arraycopy(rawAddr, 0, req, 4, rawAddr.size)
            req[4 + rawAddr.size] = ((targetPort ushr 8) and 0xFF).toByte()
            req[5 + rawAddr.size] = (targetPort and 0xFF).toByte()
            out.write(req)
            out.flush()

            // 3. Reply: VER=5, REP=0, RSV, ATYP, BND.ADDR, BND.PORT
            val ver = input.readUnsignedByte()
            val rep = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            when (val rAtyp = input.readUnsignedByte()) {
                0x01 -> input.readFully(ByteArray(4))
                0x03 -> input.readFully(ByteArray(input.readUnsignedByte()))
                0x04 -> input.readFully(ByteArray(16))
                else -> throw IOException("SOCKS5 unknown ATYP=$rAtyp")
            }
            input.readUnsignedShort()
            if (ver != 0x05 || rep != 0x00) {
                throw IOException("SOCKS5 connect failed: rep=$rep")
            }
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun checkTcp443(address: InetAddress, network: Network?): Boolean {
        repeat(2) {
            val socket = Socket()
            try {
                network?.bindSocket(socket)
                socket.use {
                    it.connect(InetSocketAddress(address, 443), TCP_TIMEOUT_MS)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Phase 3: Only tests TLS 1.2/1.3 handshake + X.509 hostname verification via local ByeDPI.
     * Does NOT issue HTTP GET, preventing false negatives from HTTP 403 / WAF / redirects.
     */
    private fun verifyTlsViaByeDPI(host: String, address: InetAddress, byedpiPort: Int): Boolean {
        repeat(2) {
            var raw: Socket? = null
            try {
                raw = dialSocks5(byedpiPort, address, 443, TLS_TIMEOUT_MS)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, 443, true) as SSLSocket
                ssl.soTimeout = TLS_TIMEOUT_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                }
                ssl.startHandshake()
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)
                if (verified) return true
            } catch (_: Exception) {
            } finally {
                runCatching { raw?.close() }
            }
        }
        return false
    }

    private fun buildDohEndpoints(dnsSetting: String): List<DohEndpoint> {
        val custom = ByeDPI.parseDnsServers(dnsSetting)
            .filter { it.startsWith("https://", ignoreCase = true) }
            .flatMap { server ->
                runCatching {
                    val u = URL(server)
                    val host = ByeDPI.dnsServerHost(server) ?: return@runCatching emptyList()
                    val port = if (u.port > 0) u.port else 443
                    val path = u.path.ifEmpty { "/dns-query" }
                    val ips = if (CdnAsnMatcher.isIpLiteral(host)) {
                        listOf(host)
                    } else {
                        ByeDPI.KNOWN_DOH_HOSTS[host.lowercase()].orEmpty()
                    }
                    ips.map { ip -> DohEndpoint(host, ip, port, path) }
                }.getOrDefault(emptyList())
            }
        return (custom + PINNED_DOH_ENDPOINTS).distinctBy { "${it.ip}:${it.port}${it.path}" }
    }

    private fun resolvePureDoh(
        host: String,
        endpoints: List<DohEndpoint>,
        byedpiPort: Int?,
        preferByeDPI: Boolean,
        network: Network?,
    ): InetAddress? {
        if (CdnAsnMatcher.isIpLiteral(host)) {
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        val query = Base64.encodeToString(
            ByeDPIProbe.buildDnsQuery(host),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val modes = if (byedpiPort != null) {
            if (preferByeDPI) listOf(byedpiPort, null) else listOf(null, byedpiPort)
        } else {
            listOf(null)
        }
        for (socksPort in modes) {
            for (ep in endpoints) {
                try {
                    val addr = InetAddress.getByName(ep.ip)
                    val body = dohExchange(
                        host = ep.host,
                        port = ep.port,
                        path = "${ep.path}${if (ep.path.contains('?')) "&" else "?"}dns=$query",
                        address = addr,
                        socksPort = socksPort,
                        network = if (socksPort == null) network else null,
                    ) ?: continue
                    val ip = ByeDPIProbe.parseDnsA(body).firstOrNull() ?: continue
                    return InetAddress.getByName(ip)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.d("CdnDpiScanner DoH failed for $host via ${ep.ip}: ${e.message}")
                }
            }
        }
        return null
    }

    private fun dohExchange(
        host: String,
        port: Int,
        path: String,
        address: InetAddress,
        socksPort: Int?,
        network: Network?,
    ): ByteArray? {
        var raw: Socket? = null
        try {
            if (socksPort != null) {
                raw = dialSocks5(socksPort, address, port, DOH_TIMEOUT_MS)
            } else {
                raw = Socket()
                network?.bindSocket(raw)
                raw.soTimeout = DOH_TIMEOUT_MS
                raw.connect(InetSocketAddress(address, port), DOH_TIMEOUT_MS)
            }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = DOH_TIMEOUT_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !CdnAsnMatcher.isIpLiteral(host)) {
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                return null
            }
            ssl.getOutputStream().apply {
                write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\n" +
                        "Accept: application/dns-message\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .toByteArray()
                )
                flush()
            }
            val body = ByteArrayOutputStream()
            val code = ByeDPIProbe.readResponse(ssl.getInputStream(), sink = body) ?: return null
            return if (code == 200) body.toByteArray() else null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { raw?.close() }
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
        kotlin.concurrent.thread(name = "scanner-byedpi-$port", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    Log.d("libbyedpi", "[scanner:$port] $it")
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
