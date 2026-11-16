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

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libexclavecore.SocketBinder
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Guise: dual-network (Wi-Fi + cellular) per-connection split, running in the :bg process.
 *
 * Keeps both a Wi-Fi and a cellular network requested (so Android keeps LTE up while Wi-Fi is
 * connected) and is installed as libexclavecore's SocketBinder:
 *  - sockets of outbounds that dial a remote proxy server (marked by ConfigBuilder) are bound
 *    to Wi-Fi or cellular by the balancer (1:1 or latency/jitter weighted);
 *  - every other socket (direct, DNS, ByeDPI via protect_path) stays on one network: the
 *    default network, or cellular when "single network = mobile data".
 */
class DualNetworkManager(
    private val strategy: Int,
    private val singleNetwork: Int,
    private val probeIntervalSeconds: Int,
    private val splitAllowed: Boolean,
    private val probeTargets: List<Pair<String, Int>>,
) : SocketBinder {

    companion object {
        const val TAG = "Guise"
        const val WIFI = "WiFi"
        const val CELLULAR = "LTE"
        val statusFile: File get() = File(SagerNet.deviceStorage.noBackupFilesDir, "dualnet_status")

        fun clearStatus() {
            runCatching { statusFile.delete() }
        }
    }

    private val networks = ConcurrentHashMap<String, Network>()
    private val stats = ConcurrentHashMap<String, DualNet.LinkStats>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val picker = DualNet.Picker()
    @Volatile
    private var weights: Map<String, Double> = emptyMap()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null

    private fun callback(label: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks[label] = network
            Log.i(TAG, "dual-network: $label available ($network)")
            recompute()
        }

        override fun onLost(network: Network) {
            if (networks[label] == network) networks.remove(label)
            stats.remove(label)
            Log.i(TAG, "dual-network: $label lost")
            recompute()
        }
    }

    fun start() {
        val cm = SagerNet.connectivity
        for ((label, transport) in listOf(
            WIFI to NetworkCapabilities.TRANSPORT_WIFI,
            CELLULAR to NetworkCapabilities.TRANSPORT_CELLULAR,
        )) {
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = callback(label)
            try {
                cm.requestNetwork(request, cb)
                callbacks.add(cb)
            } catch (e: Exception) {
                Log.w(TAG, "dual-network: requestNetwork($label) failed", e)
            }
        }
        Log.i(
            TAG, "dual-network: started, split=${if (splitAllowed) "on" else "off (ByeDPI/direct profile)"}, " +
                    "strategy=${if (strategy == DualNet.Strategy.AUTO) "auto" else "1:1"}, " +
                    "single=${if (singleNetwork == DualNet.Single.CELLULAR) CELLULAR else WIFI}"
        )
        if (splitAllowed && strategy == DualNet.Strategy.AUTO && probeTargets.isNotEmpty()) {
            probeJob = scope.launch { probeLoop() }
        }
        writeStatus()
    }

    fun stop() {
        probeJob?.cancel()
        scope.cancel()
        val cm = SagerNet.connectivity
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        networks.clear()
        clearStatus()
        Log.i(TAG, "dual-network: stopped")
    }

    private fun available(): List<String> = listOf(WIFI, CELLULAR).filter { networks.containsKey(it) }

    @Synchronized
    private fun recompute() {
        val available = available()
        weights = if (strategy == DualNet.Strategy.AUTO) {
            DualNet.weights(available, stats)
        } else {
            available.associateWith { 1.0 / available.size }
        }
        writeStatus()
    }

    // ---- SocketBinder ----

    override fun bindSocket(fd: Int, destination: String, udp: Boolean, split: Boolean): String {
        val label = if (split && splitAllowed) {
            if (strategy == DualNet.Strategy.AUTO) picker.pickWeighted(weights) else picker.pickFixed(available())
        } else {
            if (singleNetwork == DualNet.Single.CELLULAR && networks.containsKey(CELLULAR)) CELLULAR else null
        } ?: return ""
        val network = networks[label] ?: return ""
        return try {
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            if (split) counters.getOrPut(label) { AtomicLong() }.incrementAndGet()
            label
        } catch (e: Exception) {
            Log.w(TAG, "dual-network: bind to $label failed: ${e.message}")
            ""
        }
    }

    // ---- probing ----

    private suspend fun probeLoop() {
        val interval = probeIntervalSeconds.coerceIn(5, 300) * 1000L
        while (scope.isActive) {
            val current = networks.toMap()
            current.map { (label, network) ->
                scope.async {
                    val rtt = probe(network)
                    if (networks[label] == network) {
                        stats.getOrPut(label) { DualNet.LinkStats() }.add(rtt)
                    }
                }
            }.awaitAll()
            recompute()
            Log.i(TAG, "dual-network: ${statusLine()}")
            delay(interval)
        }
    }

    /** TCP connect RTT to the proxy server(s) over [network]; a refused connection still measures RTT. */
    private fun probe(network: Network): Long? {
        val results = probeTargets.take(2).mapNotNull { (host, port) ->
            try {
                val address = network.getAllByName(host).let { all ->
                    all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
                } ?: return@mapNotNull null
                val start = System.nanoTime()
                try {
                    network.socketFactory.createSocket().use {
                        it.connect(InetSocketAddress(address, port), 3000)
                    }
                } catch (e: ConnectException) {
                    // ECONNREFUSED (e.g. UDP-only server): the RST still took one round trip
                    if (e.message?.contains("ECONNREFUSED") != true) throw e
                }
                (System.nanoTime() - start) / 1_000_000
            } catch (_: Exception) {
                null
            }
        }
        return if (results.isEmpty()) null else results.average().toLong()
    }

    // ---- status ----

    private fun statusLine(): String {
        val available = available()
        if (available.isEmpty()) return "no Wi-Fi / cellular network"
        return available.joinToString(" | ") { label ->
            val s = stats[label]
            buildString {
                append(label)
                if (splitAllowed) append(" ").append(((weights[label] ?: 0.0) * 100).toInt()).append("%")
                if (s != null && s.sampleCount > 0) {
                    append(" rtt=").append(s.latency?.toInt()?.toString() ?: "-").append("ms")
                    append(" jitter=").append(s.jitter.toInt()).append("ms")
                    append(" loss=").append((s.loss * 100).toInt()).append("%")
                }
                counters[label]?.let { append(" conns=").append(it.get()) }
            }
        }
    }

    private fun writeStatus() {
        val text = buildString {
            append(if (splitAllowed) "split" else "single").append('\n')
            append(statusLine()).append('\n')
            append(System.currentTimeMillis())
        }
        runCatching { statusFile.writeText(text) }
    }
}
