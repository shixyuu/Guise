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
     * Default desync arguments: romanvht/ByeByeDPI's default command line
     * (utility/PreferencesUtils.kt, DEFAULT_CMD_ARGS): split as OOB at 1st byte, 1 UDP fake,
     * TLS record split 5 bytes before the end of SNI.
     */
    const val DEFAULT_ARGS = "-o1 -a1 -r-5+se"

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
        val args = sanitizeArgs(shellSplit(userArgs.ifBlank { DEFAULT_ARGS }))
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
