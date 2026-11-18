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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Guise: Local SOCKS5 Learning Relay for CDN traffic.
 *
 * Sits between Xray-core's `byedpi` outbound and the actual ByeDPI process:
 * - Buffers the client's first payload (e.g. TLS ClientHello) and probes via ByeDPI.
 * - On server response: records domain as known-good in [ByeDPILearningStore] and splices.
 * - On failure/reset/timeout: records failure in [ByeDPILearningStore] (blocking after 5
 *   failures across sessions), marks the domain in [sessionFallbackSet] for the rest of
 *   this VPN session, and seamlessly replays the buffered ClientHello to [fallbackPort]
 *   (Xray-core's `byedpi-fallback-in` -> `proxy`).
 */
class ByeDPILearningRelay(
    private val relayPort: Int,
    private val byedpiPort: Int,
    private val fallbackPort: Int,
) : Closeable {

    companion object {
        private const val TAG = "ByeDPILearningRelay"
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val FIRST_PACKET_TIMEOUT_MS = 3000
        private const val BUFFER_SIZE = 16384
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionFallbackSet = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var closed = false

    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", relayPort))
        serverSocket = server
        Log.i(TAG, "Learning relay listening on 127.0.0.1:$relayPort (byedpi=$byedpiPort, fallback=$fallbackPort)")

        scope.launch {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch {
                    handleClient(client)
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = client.getOutputStream()

            // 1. SOCKS5 greeting
            val ver = clientIn.readUnsignedByte()
            if (ver != 0x05) {
                client.close()
                return
            }
            val nMethods = clientIn.readUnsignedByte()
            val methods = ByteArray(nMethods)
            clientIn.readFully(methods)
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // 2. SOCKS5 CONNECT request
            val reqVer = clientIn.readUnsignedByte()
            val cmd = clientIn.readUnsignedByte()
            clientIn.readUnsignedByte() // RSV
            val atyp = clientIn.readUnsignedByte()
            if (reqVer != 0x05 || cmd != 0x01) {
                clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                client.close()
                return
            }

            val dstHost = when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    clientIn.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                0x03 -> {
                    val len = clientIn.readUnsignedByte()
                    val bytes = ByteArray(len)
                    clientIn.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    clientIn.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                else -> {
                    clientOut.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()
                    client.close()
                    return
                }
            }
            val dstPort = clientIn.readUnsignedShort()
            if (dstHost.isEmpty() || dstPort <= 0) {
                clientOut.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                client.close()
                return
            }

            // Reply SOCKS5 success immediately so Xray-core forwards the first payload
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            val hostKey = dstHost.trim().lowercase().trimEnd('.')
            val isIp = CdnAsnMatcher.isIpLiteral(hostKey)

            // 3. If already failed this session or permanently blocked (>=5 fails), go straight to proxy
            if (sessionFallbackSet.contains(hostKey) || (!isIp && ByeDPILearningStore.isBlocked(hostKey))) {
                val fallback = dialSocks5(fallbackPort, dstHost, dstPort)
                splice(client, clientIn, clientOut, fallback)
                return
            }

            // 4. Buffer first client packet (e.g. TLS ClientHello)
            val firstBuf = ByteArray(BUFFER_SIZE)
            client.soTimeout = FIRST_PACKET_TIMEOUT_MS
            val firstLen = try {
                clientIn.read(firstBuf)
            } catch (_: IOException) {
                -1
            }
            if (firstLen <= 0) {
                client.close()
                return
            }
            client.soTimeout = 0

            // 5. Try ByeDPI and wait for first response byte (ServerHello / HTTP)
            var byedpiSock: Socket? = null
            var respLen = -1
            val respBuf = ByteArray(BUFFER_SIZE)
            try {
                val s = dialSocks5(byedpiPort, dstHost, dstPort)
                byedpiSock = s
                val bOut = s.getOutputStream()
                bOut.write(firstBuf, 0, firstLen)
                bOut.flush()

                s.soTimeout = FIRST_PACKET_TIMEOUT_MS
                respLen = s.getInputStream().read(respBuf)
                s.soTimeout = 0
            } catch (_: IOException) {
                respLen = -1
            }

            if (respLen > 0 && byedpiSock != null) {
                clientOut.write(respBuf, 0, respLen)
                clientOut.flush()
                if (!isIp) {
                    ByeDPILearningStore.recordSuccess(hostKey)
                }
                splice(client, clientIn, clientOut, byedpiSock)
            } else {
                try {
                    byedpiSock?.close()
                } catch (_: IOException) {
                }
                sessionFallbackSet.add(hostKey)
                if (!isIp) {
                    val fails = ByeDPILearningStore.recordFailure(hostKey)
                    Log.i(TAG, "ByeDPI failed for $hostKey -> fallback to proxy (fails=$fails/${ByeDPILearningStore.MAX_FAILURES})")
                } else {
                    Log.i(TAG, "ByeDPI failed for IP $hostKey -> fallback to proxy for this session")
                }

                // Seamless fallback: replay buffered first packet to Xray proxy inbound
                val fallback = dialSocks5(fallbackPort, dstHost, dstPort)
                val fOut = fallback.getOutputStream()
                fOut.write(firstBuf, 0, firstLen)
                fOut.flush()
                splice(client, clientIn, clientOut, fallback)
            }
        } catch (_: IOException) {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun dialSocks5(socksPort: Int, host: String, port: Int): Socket {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
        sock.soTimeout = CONNECT_TIMEOUT_MS
        val out = sock.getOutputStream()
        val input = DataInputStream(sock.getInputStream())

        // Greeting
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val gVer = input.readUnsignedByte()
        val gMethod = input.readUnsignedByte()
        if (gVer != 0x05 || gMethod != 0x00) {
            sock.close()
            throw IOException("SOCKS5 greeting failed")
        }

        // CONNECT request
        val addrBytes: ByteArray
        val atyp: Byte
        if (CdnAsnMatcher.isIpLiteral(host)) {
            val inet = InetAddress.getByName(host)
            when (inet) {
                is Inet4Address -> {
                    atyp = 0x01
                    addrBytes = inet.address
                }
                is Inet6Address -> {
                    atyp = 0x04
                    addrBytes = inet.address
                }
                else -> {
                    val b = host.toByteArray(Charsets.UTF_8)
                    atyp = 0x03
                    addrBytes = byteArrayOf(b.size.toByte()) + b
                }
            }
        } else {
            val b = host.toByteArray(Charsets.UTF_8)
            atyp = 0x03
            addrBytes = byteArrayOf(b.size.toByte()) + b
        }

        val req = ByteArray(4 + addrBytes.size + 2)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = atyp
        System.arraycopy(addrBytes, 0, req, 4, addrBytes.size)
        req[4 + addrBytes.size] = ((port ushr 8) and 0xFF).toByte()
        req[5 + addrBytes.size] = (port and 0xFF).toByte()
        out.write(req)
        out.flush()

        val rVer = input.readUnsignedByte()
        val rRep = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val rAtyp = input.readUnsignedByte()
        when (rAtyp) {
            0x01 -> input.readFully(ByteArray(4))
            0x03 -> {
                val len = input.readUnsignedByte()
                input.readFully(ByteArray(len))
            }
            0x04 -> input.readFully(ByteArray(16))
            else -> throw IOException("Unknown SOCKS5 reply ATYP: $rAtyp")
        }
        input.readUnsignedShort() // BND.PORT
        if (rVer != 0x05 || rRep != 0x00) {
            sock.close()
            throw IOException("SOCKS5 CONNECT failed: rep=$rRep")
        }
        sock.soTimeout = 0
        return sock
    }

    private fun splice(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket,
    ) {
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()

        val t = scope.launch {
            copyStream(upIn, clientOut)
            try {
                client.shutdownOutput()
            } catch (_: IOException) {
            }
        }
        try {
            copyStream(clientIn, upOut)
            try {
                upstream.shutdownOutput()
            } catch (_: IOException) {
            }
        } finally {
            t.invokeOnCompletion {
                try { client.close() } catch (_: IOException) {}
                try { upstream.close() } catch (_: IOException) {}
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
        } catch (_: EOFException) {
        }
    }

    override fun close() {
        closed = true
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        scope.cancel()
    }
}
