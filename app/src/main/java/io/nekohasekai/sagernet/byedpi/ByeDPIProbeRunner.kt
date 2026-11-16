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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Guise: runs the ByeDPI connectivity test in the UI process.
 *
 * Host names are resolved once up front, by default over DNS-over-HTTPS to IP-literal resolvers,
 * because in mainland China the sites worth testing are usually DNS-poisoned as well; a poisoned
 * answer would make every strategy fail. Each request then goes to that IP — directly for the
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
    private val protectPath = if (vpnIncludesSelf) {
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
        val results = hosts.map { host ->
            async {
                host to (if (useDoh) resolveDoh(host, network) else null)
            }
        }.awaitAll().toMap().toMutableMap()
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

    private fun resolveDoh(host: String, network: Network?): InetAddress? {
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val url = URL(endpoint + "?name=" + URLEncoder.encode(host, "UTF-8") + "&type=A")
                val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Accept", "application/dns-json")
                try {
                    if (conn.responseCode != 200) continue
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val answers = json.optJSONArray("Answer") ?: continue
                    for (i in 0 until answers.length()) {
                        val answer = answers.getJSONObject(i)
                        if (answer.optInt("type") == 1) {
                            return InetAddress.getByName(answer.getString("data"))
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d("libbyedpi", "probe: DoH $endpoint failed for $host: ${e.message}")
            }
        }
        return null
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
        val executable = File(SagerNet.application.applicationInfo.nativeLibraryDir, ByeDPI.EXECUTABLE)
        check(executable.canExecute()) { "ByeDPI executable not found" }
        val port = mkPort()
        val cmd = ByeDPI.buildCommand(executable.absolutePath, port, row.args ?: "", protectPath)
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
            checkTargets(row, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)), null)
        } finally {
            process.destroy()
        }
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
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val address = addresses[host.lowercase()] ?: return null
        val https = uri.scheme.equals("https", true)
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = (uri.rawPath ?: "").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val timeout = timeoutSeconds * 1000
        val start = System.nanoTime()
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
                    Log.d("libbyedpi", "probe: certificate mismatch for $host via $proxy")
                    return null
                }
                ssl
            } else raw
            socket.getOutputStream().apply {
                write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\n" +
                            "Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .toByteArray()
                )
                flush()
            }
            val code = ByeDPIProbe.readResponse(socket.getInputStream())
            if (code == null) {
                Log.d("libbyedpi", "probe: broken response from $url via $proxy")
                return null
            }
            return (System.nanoTime() - start) / 1_000_000
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
        /** IP-literal DoH JSON endpoints, so the lookup itself needs no (poisonable) DNS. */
        val DOH_ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
            "https://8.8.8.8/resolve",
        )
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
