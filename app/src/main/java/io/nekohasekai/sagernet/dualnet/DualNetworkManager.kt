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
import libexclavecore.SocketBinder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Guise: dual-network (Wi-Fi + cellular) per-connection split, running in the :bg process.
 *
 * Keeps both a Wi-Fi and a cellular network requested (so Android keeps LTE up while Wi-Fi is
 * connected) and is installed as libexclavecore's SocketBinder:
 *  - sockets of outbounds that dial a remote proxy server (marked by ConfigBuilder) are bound
 *    to Wi-Fi or cellular by the balancer (1:1, or weighted by latency/jitter/loss measured
 *    passively on those live connections: libexclavecore reads their TCP_INFO once every
 *    [sampleKb] KB sent per network and calls [reportSample]; no probe traffic is sent);
 *  - every other socket (direct, DNS, ByeDPI via protect_path) stays on one network: the
 *    default network, or cellular when "single network = mobile data".
 */
class DualNetworkManager(
    private val strategy: Int,
    private val singleNetwork: Int,
    private val sampleKb: Int,
    private val splitAllowed: Boolean,
    private val udpProbeInterval: Int = DualNet.DEFAULT_UDP_PROBE_INTERVAL,
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
    private val samplesSeen = ConcurrentHashMap<String, AtomicLong>()
    private val udpProbeStates = ConcurrentHashMap<String, String>()

    /** Minimum seconds between active UDP probe rounds handed to libexclavecore (0 = off). */
    val udpProbeIntervalSeconds: Int
        get() = if (sampleBytes == 0L || udpProbeInterval <= 0) 0
        else udpProbeInterval.coerceIn(DualNet.MIN_UDP_PROBE_INTERVAL, DualNet.MAX_UDP_PROBE_INTERVAL)
    @Volatile
    private var lastStatusWrite = 0L
    @Volatile
    private var lastLog = 0L

    /** Byte threshold handed to libexclavecore; 0 = no passive sampling (1:1 or single). */
    val sampleBytes: Long
        get() = if (splitAllowed && strategy == DualNet.Strategy.AUTO) {
            sampleKb.coerceIn(DualNet.MIN_SAMPLE_KB, DualNet.MAX_SAMPLE_KB) * 1024L
        } else 0L

    private fun callback(label: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(this@DualNetworkManager) {
                networks[label] = network
                Log.i(TAG, "dual-network: $label available ($network)")
                recompute()
            }
        }

        override fun onLost(network: Network) {
            synchronized(this@DualNetworkManager) {
                if (networks[label] == network) {
                    networks.remove(label)
                    stats.remove(label)
                    samplesSeen.remove(label)
                }
                Log.i(TAG, "dual-network: $label lost")
                recompute()
            }
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
                    "single=${if (singleNetwork == DualNet.Single.CELLULAR) CELLULAR else WIFI}" +
                    (if (sampleBytes > 0) ", sample every ${sampleBytes / 1024} KB sent" else "")
        )
        writeStatus()
    }

    fun stop() {
        val cm = SagerNet.connectivity
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        networks.clear()
        clearStatus()
        Log.i(TAG, "dual-network: stopped")
    }

    private fun available(): List<String> = listOf(WIFI, CELLULAR).filter { networks.containsKey(it) }

    @Synchronized
    private fun recompute(forceStatus: Boolean = true) {
        val available = available()
        weights = if (strategy == DualNet.Strategy.AUTO) {
            DualNet.weights(available, stats)
        } else {
            available.associateWith { 1.0 / available.size }
        }
        val now = System.currentTimeMillis()
        if (forceStatus || now - lastStatusWrite >= 2000) writeStatus()
        if (!forceStatus && now - lastLog >= 10_000) {
            lastLog = now
            Log.i(TAG, "dual-network: ${statusLine()}")
        }
    }

    // ---- SocketBinder ----

    override fun bindSocket(fd: Int, destination: String, udp: Boolean, split: Boolean): String {
        val label = if (split && splitAllowed) {
            if (strategy == DualNet.Strategy.AUTO) {
                if (stats.values.any { it.dead }) recompute(forceStatus = false) // dead-network retry timer
                picker.pickWeighted(weights)
            } else picker.pickFixed(available())
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

    /**
     * Passive sample from libexclavecore (sampler.go), taken from a live split connection
     * (null sample = connect error/timeout or a stalled connection).
     */
    @Synchronized
    override fun reportSample(network: String, rttMicros: Long, rttVarMicros: Long, loss: Double, failed: Boolean) {
        if (!networks.containsKey(network)) return
        val sample = if (failed) null else DualNet.Sample(
            rttMicros / 1000.0,
            if (rttVarMicros >= 0) rttVarMicros / 1000.0 else null,
            loss.coerceIn(0.0, 1.0),
        )
        stats.getOrPut(network) { DualNet.LinkStats() }.add(sample)
        samplesSeen.getOrPut(network) { AtomicLong() }.incrementAndGet()
        if (failed) Log.w(TAG, "dual-network: $network connection failed/stalled")
        recompute(forceStatus = failed)
    }

    override fun activeNetworks(): String = available().joinToString(",")

    override fun bindSocketTo(fd: Int, network: String): Boolean {
        val target = networks[network] ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { target.bindSocket(it.fileDescriptor) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "dual-network: probe bind to $network failed: ${e.message}")
            false
        }
    }

    override fun udpProbeState(destination: String, method: String, proven: Boolean) {
        val state = if (method == "off") "off (no reply)" else if (proven) method else "$method?"
        udpProbeStates[destination] = state
        Log.i(TAG, "dual-network: UDP probe $destination -> $state")
        writeStatus()
    }

    // ---- status ----

    private fun statusLine(): String {
        val available = available()
        if (available.isEmpty()) return "no Wi-Fi / cellular network"
        val links = available.joinToString(" | ") { label ->
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
                samplesSeen[label]?.let { append(" samples=").append(it.get()) }
                if (s != null && s.dead) append(" DOWN")
            }
        }
        val udp = udpProbeStates.entries.take(2).joinToString(", ") { "${it.key} ${it.value}" }
        return if (udp.isEmpty()) links else "$links | UDP probe: $udp"
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
