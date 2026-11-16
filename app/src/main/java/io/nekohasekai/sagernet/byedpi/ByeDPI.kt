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

/**
 * Guise: helpers for the bundled ByeDPI (https://github.com/hufrea/byedpi, MIT) executable.
 *
 * ByeDPI runs as a separate process (`libbyedpi.so` in nativeLibraryDir), listening as a
 * SOCKS5 server on 127.0.0.1:<random port>. The v2ray core reaches it through a regular
 * `socks` outbound tagged [TAG_BYEDPI].
 *
 * Argument handling (shell splitting, ignoring --help/--version, letting Guise own
 * --ip/--port) is adapted from romanvht/ByeByeDPI (GPL-3.0):
 * app/src/main/java/io/github/romanvht/byedpi/core/ByeDpiProxyPreferences.kt and
 * app/src/main/java/io/github/romanvht/byedpi/utility/ArgumentsUtils.kt.
 *
 * This file has no Android dependencies so it can be unit tested on the JVM.
 */
object ByeDPI {

    const val TAG_BYEDPI = "byedpi"

    /** File name of the executable inside nativeLibraryDir (built by bin/lib/byedpi.sh). */
    const val EXECUTABLE = "libbyedpi.so"

    /**
     * Placeholder server address of the built-in "ByeDPI" profile. A SOCKS profile with this
     * address is rewritten by ConfigBuilder to 127.0.0.1:<byedpi port>, which lets Guise run
     * "ByeDPI only" without any remote proxy server.
     */
    const val PROFILE_MARKER_ADDRESS = "byedpi.guise.invalid"

    /**
     * Default desync arguments: `--disorder 1 --tlsrec 1+s`, the first example in byedpi's
     * README (minus the empty --auto group): send the first byte out of order (TTL trick) and
     * split the TLS ClientHello into two records right after the SNI start.
     *
     * Guise 0.17.68-guise.1 used ByeByeDPI's default `-o1 -a1 -r-5+se`. `-o` sends a TCP urgent
     * (OOB) byte that the receiving kernel is supposed to drop; when any middlebox/NAT/proxy on
     * the path clears the URG flag the byte stays in the stream, the TLS record header becomes
     * `16 61 03 ..` and servers abort with `protocol_version` / `decode_error` alerts
     * ("remote error: tls: protocol version not supported"). OOB-free strategies don't have that
     * failure mode.
     */
    const val DEFAULT_ARGS = "-d1 -r1+s"

    /** Default of 0.17.68-guise.1; transparently replaced by [DEFAULT_ARGS]. */
    const val LEGACY_DEFAULT_ARGS = "-o1 -a1 -r-5+se"

    /** User args to use: blank or the untouched legacy default map to [DEFAULT_ARGS]. */
    fun effectiveArgs(userArgs: String): String {
        val trimmed = userArgs.trim()
        return if (trimmed.isEmpty() || trimmed == LEGACY_DEFAULT_ARGS) DEFAULT_ARGS else trimmed
    }

    /** Tag of the DNS servers that resolve names for ByeDPI traffic ("DNS for ByeDPI"). */
    const val TAG_DNS_BYEDPI = "dns-byedpi"

    /**
     * Default "DNS for ByeDPI": DoH to IP literals, so no (poisonable) bootstrap lookup is
     * needed. Both endpoints answer RFC 8484 wire-format queries on /dns-query.
     */
    const val DEFAULT_DNS = "https://1.1.1.1/dns-query\nhttps://8.8.8.8/dns-query"

    /** Well-known DoH host names, pinned so they are not looked up through polluted DNS. */
    val KNOWN_DOH_HOSTS = mapOf(
        "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
        "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
    )

    /**
     * ByeDPI only handles the TLS/HTTP stream; the address it connects to must already be real.
     * Its outbound therefore resolves domains in the core ("UseIPv4", through the DNS servers
     * above) instead of handing them to byedpi, which would use the polluted system resolver.
     * IPv4 only: mainland IPv6 paths to these sites are less predictable, and it matches the
     * connectivity test.
     */
    const val OUTBOUND_DOMAIN_STRATEGY = "UseIPv4"
    const val DNS_QUERY_STRATEGY = "UseIPv4"

    // ---- Guise: ByeDPI in front of TCP proxy servers ("pre-layer") ----

    /** Outbound protocols that reach their server over TCP (unless their transport says otherwise). */
    val PRE_LAYER_PROTOCOLS = setOf(
        "vmess", "vless", "trojan", "shadowsocks", "socks", "http", "anytls", "ssh", "snell", "mieru", "trusttunnel",
    )

    /** Stream transports that run over TCP. kcp, quic, hysteria2 (and unknown ones) are left alone. */
    val PRE_LAYER_NETWORKS = setOf(
        "tcp", "raw", "ws", "websocket", "http", "h2", "grpc", "gun", "httpupgrade", "splithttp", "xhttp", "meek", "mekya",
    )

    /**
     * Whether an outbound dialing a proxy server should be sent through ByeDPI's SOCKS server.
     * [network] null = plain TCP; [alpn] of its TLS settings (XHTTP with h3 runs over QUIC);
     * [udpOnly] for beans whose own setting selects a UDP/QUIC transport (mieru UDP,
     * TrustTunnel over QUIC); [serverAddress] loopback/marker servers (plugins, ByeDPI itself)
     * are never wrapped.
     */
    fun preLayerApplies(
        protocol: String?, network: String?, alpn: List<String>?, udpOnly: Boolean, serverAddress: String?,
    ): Boolean {
        if (protocol == null || protocol !in PRE_LAYER_PROTOCOLS || udpOnly) return false
        val address = serverAddress?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        if (address.isNullOrEmpty() || address == PROFILE_MARKER_ADDRESS || address == "localhost" ||
            address.startsWith("127.") || address == "::1"
        ) return false
        val net = network?.lowercase() ?: "tcp"
        if (net !in PRE_LAYER_NETWORKS) return false
        if ((net == "splithttp" || net == "xhttp") && alpn?.any { it.equals("h3", true) } == true) return false
        return true
    }

    /** "DNS for ByeDPI" list: one server per line or comma separated, '#' comments allowed. */
    fun parseDnsServers(text: String): List<String> {
        val servers = text.lines()
            .map { it.substringBefore('#') }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return servers.ifEmpty { parseDnsServers(DEFAULT_DNS) }
    }

    /** Host part of a DNS server address such as `https://dns.google/dns-query` or `tcp://1.1.1.1:53`. */
    fun dnsServerHost(server: String): String? {
        val rest = server.substringAfter("://", server)
        val authority = rest.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) return authority.substringAfter('[').substringBefore(']')
        return if (authority.count { it == ':' } == 1) authority.substringBefore(':') else authority
    }

    /**
     * Which DNS servers resolve names for ByeDPI traffic.
     * @property replaceRemoteDns "DNS for ByeDPI" takes the place of the remote DNS servers
     * @property domains when non-empty, extra "DNS for ByeDPI" servers just for these domains
     */
    data class DnsPlan(val servers: List<String>, val replaceRemoteDns: Boolean, val domains: List<String>) {
        val active get() = servers.isNotEmpty() && (replaceRemoteDns || domains.isNotEmpty())
    }

    /**
     * - ByeDPI-only profile as the main outbound (URL tests included): replaces the remote DNS
     *   (unless route mode "direct" keeps the profile unused);
     * - global ByeDPI "all direct traffic" + route mode "direct": replaces the (direct) DNS;
     * - global ByeDPI "selected domains": dedicated servers for those domains.
     * Global "all direct traffic" with rule-based routing keeps the direct DNS for bypassed
     * (mostly domestic) names.
     */
    fun dnsPlan(
        dnsSetting: String,
        byedpiRunning: Boolean,
        profileIsMain: Boolean,
        globalEnabled: Boolean,
        mode: Int,
        domainsSetting: String,
        routeModeDirect: Boolean,
        forTest: Boolean,
    ): DnsPlan {
        if (!byedpiRunning) return DnsPlan(emptyList(), false, emptyList())
        val servers = parseDnsServers(dnsSetting)
        val replace = (profileIsMain && (forTest || !routeModeDirect)) ||
                (globalEnabled && mode == Mode.ALL_DIRECT && routeModeDirect)
        val domains = if (globalEnabled && mode == Mode.DOMAINS) parseDomains(domainsSetting) else emptyList()
        return DnsPlan(servers, replace, domains)
    }

    object Mode {
        /** Route all traffic that would go "direct"/bypass through ByeDPI. */
        const val ALL_DIRECT = 0

        /** Route only domains from the user's list through ByeDPI. */
        const val DOMAINS = 1
    }

    /** Options Guise manages itself or that would break a supervised child process. */
    private val optionsWithValueOwnedByGuise = setOf(
        "-i", "--ip", "-p", "--port", "-P", "--protect-path", "-w", "--pidfile",
    )
    private val flagsOwnedByGuise = setOf(
        "-D", "--daemon", "-h", "--help", "-v", "--version",
    )

    /**
     * Strip options that Guise controls. Handles both "-p 1080" and the attached forms
     * "-p1080" / "--port=1080".
     */
    fun sanitizeArgs(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var skipNext = false
        for (arg in args) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (arg in flagsOwnedByGuise) continue
            if (arg in optionsWithValueOwnedByGuise) {
                skipNext = true
                continue
            }
            if (arg.startsWith("--") && arg.substringBefore('=') in optionsWithValueOwnedByGuise) continue
            if (!arg.startsWith("--") && arg.length > 2 && arg.substring(0, 2) in optionsWithValueOwnedByGuise) continue
            result.add(arg)
        }
        // ByeByeDPI: drop anything before the first option (e.g. a pasted "ciadpi" binary name).
        return result.dropWhile { !it.startsWith("-") }
    }

    /**
     * Build the full command line.
     *
     * @param protectPath unix socket path of the protect server (shadowsocks-android protocol,
     *   served by libexclavecore). Pass it only when the app's own UID is routed through the VPN
     *   (TUN implementation "System"); otherwise the app is excluded from the VPN and byedpi's
     *   sockets bypass the tunnel without protection. byedpi fails the connection if the path is
     *   given but unreachable, so never pass a path that is not being served.
     */
    fun buildCommand(
        executable: String,
        port: Int,
        userArgs: String,
        protectPath: String?,
    ): List<String> {
        val args = sanitizeArgs(shellSplit(effectiveArgs(userArgs)))
        return buildList {
            add(executable)
            add("--ip"); add("127.0.0.1")
            add("--port"); add(port.toString())
            if (!protectPath.isNullOrEmpty()) {
                add("--protect-path"); add(protectPath)
            }
            addAll(args)
        }
    }

    /** Poll until something accepts TCP connections on 127.0.0.1:[port]. */
    suspend fun waitForListening(port: Int, timeoutMillis: Long = 1500L): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            while (true) {
                try {
                    java.net.Socket().use {
                        it.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                    }
                    break
                } catch (_: java.io.IOException) {
                    kotlinx.coroutines.delay(30L)
                }
            }
            true
        } ?: false
    }

    /** Parse the user domain list (one per line or comma separated, '#' comments). */
    fun parseDomains(text: String): List<String> {
        return text.split('\n', ',')
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map {
                // bare "example.com" means the domain and its subdomains, like ByeByeDPI host lists
                if (it.contains(':')) it else "domain:$it"
            }
            .distinct()
    }

    // Based on https://gist.github.com/raymyers/8077031, via romanvht/ByeByeDPI ArgumentsUtils.kt
    fun shellSplit(string: CharSequence): List<String> {
        val tokens: MutableList<String> = ArrayList()
        var quoteChar = ' '
        var escaping = false
        var quoting = false
        var lastCloseQuoteIndex = Int.MIN_VALUE
        var current = StringBuilder()

        for (i in string.indices) {
            val c = string[i]

            if (escaping) {
                current.append(c)
                escaping = false
            } else if (c == '\\' && quoting) {
                if (i + 1 < string.length && string[i + 1] == quoteChar) {
                    escaping = true
                } else {
                    current.append(c)
                }
            } else if (quoting && c == quoteChar) {
                quoting = false
                lastCloseQuoteIndex = i
            } else if (!quoting && (c == '\'' || c == '"')) {
                quoting = true
                quoteChar = c
            } else if (!quoting && Character.isWhitespace(c)) {
                if (current.isNotEmpty() || lastCloseQuoteIndex == i - 1) {
                    tokens.add(current.toString())
                    current = StringBuilder()
                }
            } else {
                current.append(c)
            }
        }

        if (current.isNotEmpty() || lastCloseQuoteIndex == string.length - 1) {
            tokens.add(current.toString())
        }

        return tokens
    }
}
