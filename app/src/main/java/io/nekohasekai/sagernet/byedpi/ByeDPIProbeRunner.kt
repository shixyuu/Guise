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
import android.util.Log
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Guise: runs the ByeDPI connectivity test in the UI process.
 *
 * Host names are resolved once up front with "DNS for ByeDPI" (DoH, through a ByeDPI with the
 * current arguments when "Send DNS through ByeDPI" is on) — the same servers and route the core
 * uses for ByeDPI traffic — because in mainland China the sites worth testing are usually
 * DNS-poisoned as well; a poisoned answer would make every strategy fail. Each request then goes to that IP — directly for the
 * baseline row, or through a throw-away byedpi (SOCKS5 on 127.0.0.1:<free port>) per strategy —
 * with TLS SNI and certificate check on the real host name, so byedpi sees the ClientHello it
 * has to disguise. The pass/fail rule follows romanvht/ByeByeDPI utility/SiteCheckUtils.kt
 * (GPL-3.0): any HTTP status counts, a truncated body does not. Requests always face the phone's
 * current network, never a proxy node.
 */
class ByeDPIProbeRunner(
    private val targets: List<ByeDPIProbeTargets.Target>,
    private val requestsPerTarget: Int,
    private val timeoutSeconds: Int,
    private val concurrency: Int,
    private val useDoh: Boolean,
    private val onUpdate: (ByeDPIProbe.Result) -> Unit,
) {

    class Resolution(val addresses: Map<String, InetAddress>, val failed: List<String>, val viaDoh: Boolean)

    /**
     * Same rule as the URL test in ConfigurationFragment: only when Guise's VPN runs with the
     * "System" stack is the app UID routed through the tunnel, so then byedpi must protect its
     * sockets and direct connections must be bound to the underlying network.
     */
    private val vpnIncludesSelf = SagerNet.started && DataStore.serviceMode == Key.MODE_VPN &&
            DataStore.tunImplementation == TunImplementation.SYSTEM
    private val protectPath = if (
        SagerNet.started && DataStore.serviceMode == Key.MODE_VPN &&
        (DataStore.tunImplementation == TunImplementation.SYSTEM || DataStore.dualNetEnabled)
    ) {
        SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
    } else null

    private var addresses: Map<String, InetAddress> = emptyMap()

    suspend fun run(
        plan: List<ByeDPIProbe.Result>,
        onResolved: (Resolution) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val listenerKey = Any()
        val underlying: Network? = if (vpnIncludesSelf) {
            DefaultNetworkListener.start(listenerKey) {}
            withTimeoutOrNull(3000L) { runCatching { DefaultNetworkListener.get() }.getOrNull() }
        } else null
        try {
            val resolution = resolveAll(underlying)
            addresses = resolution.addresses
            onResolved(resolution)
            runPlan(plan, underlying)
        } finally {
            if (vpnIncludesSelf) DefaultNetworkListener.stop(listenerKey)
        }
    }

    // ---- DNS ----

    private suspend fun resolveAll(network: Network?): Resolution = coroutineScope {
        val hosts = targets.flatMap { it.hosts }.distinct()
        var viaDoh = useDoh
        val dohServers = ByeDPI.parseDnsServers(DataStore.byedpiDns).filter { it.startsWith("https://") }
        val dnsByeDPI = if (useDoh && dohServers.isNotEmpty() && DataStore.byedpiDnsViaByedpi) {
            runCatching { startByeDPI(DataStore.byedpiArgs) }.getOrNull()
        } else null
        val dnsProxy = dnsByeDPI?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it.second)) }
        val results = try {
            hosts.map { host ->
                async {
                    host to (if (useDoh) resolveDoh(host, dohServers, dnsProxy, network) else null)
                }
            }.awaitAll().toMap().toMutableMap()
        } finally {
            dnsByeDPI?.first?.destroy()
        }
        // If current ByeDPI args broke DoH via ByeDPI, retry direct DoH before falling back to system DNS.
        if (useDoh && dnsProxy != null && results.values.all { it == null }) {
            val direct = hosts.map { host ->
                async { host to resolveDoh(host, dohServers, null, network) }
            }.awaitAll()
            for ((host, address) in direct) {
                if (address != null) results[host] = address
            }
        }
        if (useDoh && results.values.all { it == null }) viaDoh = false // DoH unreachable
        for ((host, address) in results) {
            if (address == null) results[host] = resolveSystem(host, network)
        }
        val ok = results.filterValues { it != null }.mapValues { it.value!! }
        ok.forEach { (h, a) -> Log.i("libbyedpi", "probe: $h -> ${a.hostAddress}") }
        Resolution(ok, hosts.filter { it !in ok }, viaDoh)
    }

    private fun resolveSystem(host: String, network: Network?): InetAddress? = try {
        val all = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    } catch (e: Exception) {
        Log.d("libbyedpi", "probe: system DNS failed for $host: ${e.message}")
        null
    }

    /** RFC 8484 GET to each "DNS for ByeDPI" https server in turn; well-known hosts are pinned. */
    private fun resolveDoh(host: String, servers: List<String>, proxy: Proxy?, network: Network?): InetAddress? {
        val query = android.util.Base64.encodeToString(
            ByeDPIProbe.buildDnsQuery(host),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        for (server in servers) {
            try {
                val serverHost = ByeDPI.dnsServerHost(server) ?: continue
                val pinned = ByeDPI.KNOWN_DOH_HOSTS[serverHost.lowercase()]?.firstOrNull()
                val url = URL(server + (if (server.contains('?')) "&" else "?") + "dns=" + query)
                val answer = if (pinned != null) {
                    // keep SNI/certificate on the name, connect to the pinned IP
                    requestBytes(url, InetAddress.getByName(pinned), proxy, network)
                } else {
                    requestBytes(url, null, proxy, network)
                } ?: continue
                val ip = ByeDPIProbe.parseDnsA(answer).firstOrNull() ?: continue
                return InetAddress.getByName(ip)
            } catch (e: Exception) {
                Log.d("libbyedpi", "probe: DoH $server failed for $host: ${e.message}")
            }
        }
        return null
    }

    private fun requestBytes(url: URL, address: InetAddress?, proxy: Proxy?, network: Network?): ByteArray? {
        if (address == null) {
            val conn = (when {
                proxy != null -> url.openConnection(proxy)
                network != null -> network.openConnection(url)
                else -> url.openConnection()
            }) as HttpsURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/dns-message")
            try {
                if (conn.responseCode != 200) return null
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        val (code, body) = exchange(url, address, proxy, network, "application/dns-message", 4000) ?: return null
        return if (code == 200) body else null
    }

    // ---- strategies ----

    private suspend fun runPlan(plan: List<ByeDPIProbe.Result>, underlying: Network?) {
        plan.forEach {
            it.total = targets.size * requestsPerTarget
            onUpdate(it)
        }
        val semaphore = Semaphore(concurrency.coerceIn(1, ByeDPIProbe.MAX_CONCURRENCY))
        coroutineScope {
            plan.map { row ->
                async {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        try {
                            if (row.kind == ByeDPIProbe.Kind.BASELINE) {
                                checkTargets(row, null, underlying)
                            } else {
                                runStrategy(row)
                            }
                            row.finished = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            row.error = e.message ?: e.javaClass.simpleName
                            row.finished = true
                        } finally {
                            onUpdate(row)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun runStrategy(row: ByeDPIProbe.Result) {
        val (process, port) = startByeDPI(row.args ?: "")
        try {
            checkTargets(row, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)), null)
        } finally {
            process.destroy()
        }
    }

    /** Start a throw-away byedpi and wait until it listens; the caller destroys the process. */
    private suspend fun startByeDPI(args: String): Pair<Process, Int> {
        val executable = File(SagerNet.application.applicationInfo.nativeLibraryDir, ByeDPI.EXECUTABLE)
        check(executable.canExecute()) { "ByeDPI executable not found" }
        val port = mkPort()
        val cmd = ByeDPI.buildCommand(executable.absolutePath, port, args, protectPath)
        val process = ProcessBuilder(cmd)
            .directory(SagerNet.deviceStorage.noBackupFilesDir)
            .redirectErrorStream(true)
            .start()
        val output = StringBuffer()
        thread(name = "byedpi-probe-$port", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    Log.d("libbyedpi", "[probe:$port] $it")
                    if (output.length < 400) output.append(it).append('\n')
                }
            } catch (_: IOException) {
            }
        }
        try {
            if (!ByeDPI.waitForListening(port)) {
                val exited = try {
                    process.exitValue(); true
                } catch (_: IllegalThreadStateException) {
                    false
                }
                throw IOException(
                    if (exited) "byedpi exited: " + output.toString().trim().lineSequence().firstOrNull().orEmpty()
                    else "byedpi did not start"
                )
            }
        } catch (e: Exception) {
            process.destroy()
            throw e
        }
        return process to port
    }

    private suspend fun checkTargets(row: ByeDPIProbe.Result, proxy: Proxy?, network: Network?) = coroutineScope {
        targets.map { target ->
            async {
                for (url in ByeDPIProbe.requestUrls(target, requestsPerTarget)) {
                    coroutineContext.ensureActive()
                    row.record(target.label, checkOnce(url, proxy, network))
                    onUpdate(row)
                }
            }
        }.awaitAll()
    }

    /** @return latency in ms when the site answered with a complete response, null otherwise. */
    private fun checkOnce(url: String, proxy: Proxy?, network: Network?): Long? {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return null
        }
        val address = addresses[parsed.host.lowercase()] ?: return null
        val start = System.nanoTime()
        val result = exchange(parsed, address, proxy, network, "*/*", timeoutSeconds * 1000, bodyNeeded = false)
        return if (result != null) (System.nanoTime() - start) / 1_000_000 else null
    }

    /**
     * One HTTP/1.1 GET to [address] (directly, bound to [network], or through the SOCKS [proxy])
     * with TLS SNI and certificate check on the URL's host name.
     * @return status code and (if [bodyNeeded]) body, or null if the exchange broke.
     */
    private fun exchange(
        url: URL, address: InetAddress, proxy: Proxy?, network: Network?, accept: String, timeout: Int,
        bodyNeeded: Boolean = true,
    ): Pair<Int, ByteArray?>? {
        val host = url.host
        val https = url.protocol.equals("https", true)
        val port = if (url.port > 0) url.port else if (https) 443 else 80
        val path = url.file.ifEmpty { "/" }
        var raw: Socket? = null
        try {
            raw = if (proxy != null) Socket(proxy) else Socket()
            if (proxy == null) network?.bindSocket(raw)
            raw.soTimeout = timeout
            raw.connect(InetSocketAddress(address, port), timeout)
            val socket = if (https) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                }
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    Log.d("libbyedpi", "probe: certificate mismatch for $host via ${proxy ?: "direct"}")
                    return null
                }
                ssl
            } else raw
            socket.getOutputStream().apply {
                write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\n" +
                            "Accept: $accept\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .toByteArray()
                )
                flush()
            }
            val body = if (bodyNeeded) java.io.ByteArrayOutputStream() else null
            val code = ByeDPIProbe.readResponse(socket.getInputStream(), sink = body)
            if (code == null) {
                Log.d("libbyedpi", "probe: broken response from $url via ${proxy ?: "direct"}")
                return null
            }
            return code to body?.toByteArray()
        } catch (e: Exception) {
            Log.d("libbyedpi", "probe: $url via ${proxy ?: "direct"} failed: ${e.message}")
            return null
        } finally {
            try {
                raw?.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
