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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import libexclavecore.LanPeerAuthorizer
import libexclavecore.Libexclavecore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Guise: LAN Peer DualLink manager (mDNS discovery + authenticated SOCKS5 relay + quota control).
 * Runs in the :bg process alongside [DualNetworkManager].
 */
class LanPeerManager(
    private val context: Context,
    private val borrowEnabled: Boolean,
    private val shareEnabled: Boolean,
    private val cellularProvider: () -> Network?,
    private val onPeersChanged: () -> Unit,
) : LanPeerAuthorizer {

    companion object {
        const val TAG = "GuiseLanPeer"
        const val SERVICE_TYPE = "_guise-peer._tcp."
        const val ACTION_PEER_REQUEST = "io.nekohasekai.sagernet.LAN_PEER_REQUEST"
        const val ACTION_PEER_DECISION = "io.nekohasekai.sagernet.LAN_PEER_DECISION"
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_CLIENT_IP = "client_ip"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"

        val statusFile: File get() = File(SagerNet.deviceStorage.noBackupFilesDir, "lanpeer_status")

        fun clearStatus() {
            runCatching { statusFile.delete() }
        }

        fun ensureIdentity(): Pair<String, String> {
            var id = DataStore.lanPeerDeviceId
            if (id.isBlank()) {
                id = UUID.randomUUID().toString().replace("-", "").take(8)
                DataStore.lanPeerDeviceId = id
            }
            var token = DataStore.lanPeerToken
            if (token.isBlank()) {
                token = UUID.randomUUID().toString().replace("-", "").take(16)
                DataStore.lanPeerToken = token
            }
            return id to token
        }

        fun localDeviceName(): String {
            val raw = (Build.MODEL ?: "Android").replace("|", " ").replace(";", " ").trim()
            return raw.ifEmpty { "Android" }.take(24)
        }

        fun parseTrusted(raw: String = DataStore.lanPeerTrusted): MutableMap<String, String> {
            val map = LinkedHashMap<String, String>()
            if (raw.isBlank()) return map
            for (entry in raw.split(";")) {
                if (entry.isBlank()) continue
                val parts = entry.split("|", limit = 2)
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    map[parts[0]] = parts.getOrElse(1) { parts[0] }
                }
            }
            return map
        }

        fun saveTrusted(map: Map<String, String>) {
            DataStore.lanPeerTrusted = map.entries.joinToString(";") { "${it.key}|${it.value}" }
        }

        fun trustPeer(clientId: String, clientName: String) {
            val map = parseTrusted()
            map[clientId] = clientName.replace("|", " ").replace(";", " ")
            saveTrusted(map)
        }

        fun clearTrusted() {
            DataStore.lanPeerTrusted = ""
        }
    }

    data class DiscoveredPeer(
        val label: String,
        val serviceName: String,
        val host: String,
        val port: Int,
    )

    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val peers = ConcurrentHashMap<String, DiscoveredPeer>()
    private val serviceToLabel = ConcurrentHashMap<String, String>()
    private val trustedCache = ConcurrentHashMap<String, String>()
    private val deniedCache = ConcurrentHashMap<String, Long>()
    private val lastPromptAt = ConcurrentHashMap<String, Long>()

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    private val usedBytes = AtomicLong(DataStore.lanPeerShareUsedBytes)
    private val unflushedBytes = AtomicLong(0L)

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var decisionReceiver: BroadcastReceiver? = null
    private var registeredServiceName: String? = null
    private var myServicePrefix: String = ""
    private var myDeviceId: String = ""
    private var myToken: String = ""

    fun start() {
        val (id, token) = ensureIdentity()
        myDeviceId = id
        myToken = token
        myServicePrefix = "Guise-${localDeviceName()}-${id.take(4)}"
        trustedCache.clear()
        trustedCache.putAll(parseTrusted())

        registerDecisionReceiver()

        if (shareEnabled) {
            startHostRelay()
        }
        if (borrowEnabled) {
            startDiscovery()
        }
        writeStatus()
    }

    fun stop() {
        decisionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        decisionReceiver = null

        discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        discoveryListener = null

        registrationListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        registrationListener = null

        if (shareEnabled) {
            runCatching { Libexclavecore.stopLanPeerServer() }
        }
        flushUsedBytes()
        peers.clear()
        serviceToLabel.clear()
        clearStatus()
        Log.i(TAG, "stopped")
    }

    fun peerLabels(): List<String> = peers.keys.sorted()

    fun peerDialEndpoint(label: String): String {
        val p = peers[label] ?: return ""
        return "${p.host}:${p.port}|$myDeviceId|${localDeviceName()}|$myToken"
    }

    private fun registerDecisionReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PEER_DECISION) return
                val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return
                val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: clientId
                val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
                if (allow) {
                    deniedCache.remove(clientId)
                    trustedCache[clientId] = clientName
                    trustPeer(clientId, clientName)
                    Log.i(TAG, "trusted peer $clientName ($clientId)")
                } else {
                    trustedCache.remove(clientId)
                    deniedCache[clientId] = System.currentTimeMillis()
                    Log.i(TAG, "denied peer $clientName ($clientId)")
                }
                writeStatus()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_PEER_DECISION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        decisionReceiver = receiver
    }

    private fun startHostRelay() {
        val nsd = nsdManager ?: return
        try {
            val port = Libexclavecore.startLanPeerServer(0, this)
            val info = NsdServiceInfo().apply {
                serviceName = myServicePrefix
                serviceType = SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("id", myDeviceId)
                }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    registeredServiceName = serviceInfo.serviceName
                    Log.i(TAG, "registered mDNS service ${serviceInfo.serviceName} on port $port")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "startHostRelay failed", e)
        }
    }

    private fun startDiscovery() {
        val nsd = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                if (name == registeredServiceName || name.startsWith(myServicePrefix)) return
                resolveQueue.add(service)
                drainResolveQueue()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val label = serviceToLabel.remove(service.serviceName) ?: return
                if (peers.remove(label) != null) {
                    Log.i(TAG, "peer lost: $label")
                    onPeersChanged()
                    writeStatus()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "startDiscovery failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        val nsd = nsdManager ?: return
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        val svcName = serviceInfo.serviceName ?: "Peer"
                        if (!host.isNullOrBlank() && port > 0 && svcName != registeredServiceName) {
                            val display = svcName.removePrefix("Guise-").take(14)
                            val label = "Peer:$display"
                            serviceToLabel[svcName] = label
                            peers[label] = DiscoveredPeer(label, svcName, host, port)
                            Log.i(TAG, "peer resolved: $label -> $host:$port")
                            onPeersChanged()
                            writeStatus()
                        }
                    } finally {
                        resolving.set(false)
                        drainResolveQueue()
                    }
                }
            })
        } catch (e: Exception) {
            resolving.set(false)
        }
    }

    // ---- LanPeerAuthorizer (called from Go threads) ----

    override fun authorizePeer(clientId: String, clientName: String, clientIp: String, token: String): Int {
        if (!DataStore.effectiveLanShareEnabled) return -1
        val limitMb = DataStore.lanPeerShareLimitMb
        if (limitMb > 0 && usedBytes.get() >= limitMb * 1024L * 1024L) return -1

        if (trustedCache.containsKey(clientId)) return 1
        // Re-check persisted store in case user just approved from Settings
        val persisted = parseTrusted()
        if (persisted.containsKey(clientId)) {
            trustedCache.putAll(persisted)
            return 1
        }

        val deniedAt = deniedCache[clientId]
        if (deniedAt != null && System.currentTimeMillis() - deniedAt < 60_000L) {
            return -1
        }

        val now = System.currentTimeMillis()
        val prevPrompt = lastPromptAt[clientId] ?: 0L
        if (now - prevPrompt > 15_000L) {
            lastPromptAt[clientId] = now
            val intent = Intent(ACTION_PEER_REQUEST).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CLIENT_ID, clientId)
                putExtra(EXTRA_CLIENT_NAME, clientName)
                putExtra(EXTRA_CLIENT_IP, clientIp)
                putExtra(EXTRA_TOKEN, token)
            }
            context.sendBroadcast(intent)
        }
        return 0
    }

    override fun reportPeerTraffic(clientId: String, bytes: Long): Boolean {
        if (!DataStore.effectiveLanShareEnabled) return false
        val total = usedBytes.addAndGet(bytes)
        if (unflushedBytes.addAndGet(bytes) >= 256 * 1024L) {
            flushUsedBytes()
        }
        val limitMb = DataStore.lanPeerShareLimitMb
        if (limitMb > 0 && total >= limitMb * 1024L * 1024L) {
            flushUsedBytes()
            writeStatus()
            return false
        }
        return true
    }

    /** Anti-loop: bind outbound host relay socket strictly to physical cellular network. */
    override fun bindHostSocket(fd: Int): Boolean {
        val cellular = cellularProvider() ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { cellular.bindSocket(it.fileDescriptor) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindHostSocket to cellular failed: ${e.message}")
            false
        }
    }

    private fun flushUsedBytes() {
        unflushedBytes.set(0L)
        DataStore.lanPeerShareUsedBytes = usedBytes.get()
        writeStatus()
    }

    private fun writeStatus() {
        runCatching {
            val usedMb = usedBytes.get() / (1024L * 1024L)
            val limitMb = DataStore.lanPeerShareLimitMb
            val peerList = peers.keys.sorted().joinToString(",")
            val peerHosts = peers.values.sortedBy { it.label }.joinToString(",") { "${it.label}@${it.host}" }
            statusFile.writeText(
                "peers=${peers.size}|sharing=${if (shareEnabled) 1 else 0}|usedMb=$usedMb|limitMb=$limitMb|peerNames=$peerList|peerHosts=$peerHosts"
            )
        }
    }
}
