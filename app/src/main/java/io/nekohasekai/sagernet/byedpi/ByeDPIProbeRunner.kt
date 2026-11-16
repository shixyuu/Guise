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
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Guise: runs the ByeDPI connectivity test in the UI process.
 *
 * For each strategy a throw-away byedpi process listens on 127.0.0.1:<free port>; HTTPS
 * requests go through it as SOCKS5 and the process is killed afterwards. The request/
 * verification logic is adapted from romanvht/ByeByeDPI utility/SiteCheckUtils.kt (GPL-3.0):
 * follow redirects, read the body up to Content-Length (or 1 MB) and treat a truncated body as
 * blocked. Requests always face the phone's current network directly, never a proxy node.
 */
class ByeDPIProbeRunner(
    private val sites: List<String>,
    private val requestsPerSite: Int,
    private val timeoutSeconds: Int,
    private val concurrency: Int,
    private val onUpdate: (ByeDPIProbe.Result) -> Unit,
) {

    /**
     * Same rule as the URL test in ConfigurationFragment: only when Guise's VPN runs with the
     * "System" stack is the app UID routed through the tunnel, so then byedpi must protect its
     * sockets and direct requests must be bound to the underlying network.
     */
    private val vpnIncludesSelf = SagerNet.started && DataStore.serviceMode == Key.MODE_VPN &&
            DataStore.tunImplementation == TunImplementation.SYSTEM
    private val protectPath = if (vpnIncludesSelf) {
        SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
    } else null

    suspend fun run(plan: List<ByeDPIProbe.Result>) = withContext(Dispatchers.IO) {
        val listenerKey = Any()
        val underlying: Network? = if (vpnIncludesSelf) {
            DefaultNetworkListener.start(listenerKey) {}
            withTimeoutOrNull(3000L) { runCatching { DefaultNetworkListener.get() }.getOrNull() }
        } else null
        try {
            runPlan(plan, underlying)
        } finally {
            if (vpnIncludesSelf) DefaultNetworkListener.stop(listenerKey)
        }
    }

    private suspend fun runPlan(plan: List<ByeDPIProbe.Result>, underlying: Network?) {
        plan.forEach {
            it.total = sites.size * requestsPerSite
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
                                checkSites(row, Proxy.NO_PROXY, underlying)
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
            checkSites(row, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)), null)
        } finally {
            process.destroy()
        }
    }

    private suspend fun checkSites(row: ByeDPIProbe.Result, proxy: Proxy, network: Network?) = coroutineScope {
        sites.map { site ->
            async {
                repeat(requestsPerSite) {
                    coroutineContext.ensureActive()
                    val latency = checkOnce(site, proxy, network)
                    synchronized(row) {
                        row.done++
                        if (latency != null) {
                            row.success++
                            row.latencies.add(latency)
                        }
                    }
                    onUpdate(row)
                }
            }
        }.awaitAll()
    }

    /** @return latency in ms when the site answered with a complete body, null otherwise. */
    private fun checkOnce(site: String, proxy: Proxy, network: Network?): Long? {
        val url = try {
            URL(site)
        } catch (_: Exception) {
            return null
        }
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = (if (network != null && proxy == Proxy.NO_PROXY) {
                network.openConnection(url)
            } else {
                url.openConnection(proxy)
            }) as HttpURLConnection
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")
            val code = connection.responseCode
            val declared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            var actual = 0L
            try {
                val input = if (code in 200..299) connection.inputStream else connection.errorStream
                if (input != null) {
                    val buffer = ByteArray(8192)
                    val limit = if (declared > 0) declared else 1024L * 1024
                    while (actual < limit) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - actual).toInt())
                        if (n == -1) break
                        actual += n
                    }
                }
            } catch (_: IOException) {
            }
            return if (declared <= 0L || actual >= declared) {
                (System.nanoTime() - start) / 1_000_000
            } else {
                Log.d("libbyedpi", "probe: truncated body from $site ($actual/$declared)")
                null
            }
        } catch (e: Exception) {
            Log.d("libbyedpi", "probe: $site via $proxy failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
