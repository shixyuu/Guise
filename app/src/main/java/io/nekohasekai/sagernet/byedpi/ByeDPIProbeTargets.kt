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

import java.net.URI

/**
 * Guise: preset targets for the ByeDPI connectivity test.
 *
 * Presets favour sites that, from mainland China, are reported to be blocked by DNS poisoning plus
 * SNI-based DPI (TLS RST) while their IPs stay reachable, i.e. the case byedpi-style desync can fix.
 * Evidence: inclusion in the SNI-bypass rule sets of URenko/Accesser (accesser/rules.toml, pac) and
 * SpaceTimee/Cealing-Host (Cealing-Host.json, used by Sheas Cealer), both maintained for users in
 * mainland China. See GUISE.md for details. Each preset hits small static files (favicons/logos).
 */
object ByeDPIProbeTargets {

    enum class Tag {
        /** Listed by both Accesser and Cealing-Host (or one plus well-known community reports). */
        SNI,
        /** Listed by a bypass project, but the IPs are often blocked too / results vary. */
        UNCERTAIN,
        /** Usually IP-blocked in mainland China: desync alone rarely helps. */
        IP_BLOCKED,
        /** Normally not blocked; useful as a reference. */
        CONTROL,
        CUSTOM,
    }

    class Target(
        val id: String,
        val label: String,
        val urls: List<String>,
        val tag: Tag,
        val defaultEnabled: Boolean = false,
    ) {
        val hosts: List<String> get() = urls.mapNotNull { hostOf(it) }.distinct()
    }

    const val CUSTOM_PREFIX = "custom:"

    val PRESETS = listOf(
        Target(
            "x", "X / Twitter", listOf(
                "https://x.com/favicon.ico",
                "https://abs.twimg.com/favicons/twitter.3.ico",
                "https://pbs.twimg.com/favicon.ico",
                "https://twitter.com/favicon.ico",
            ), Tag.SNI, true
        ),
        Target(
            "twitch", "Twitch", listOf(
                "https://www.twitch.tv/favicon.ico",
                "https://static-cdn.jtvnw.net/ttv-static/404_preview-320x180.jpg",
            ), Tag.SNI, true
        ),
        Target(
            "pixiv", "Pixiv", listOf(
                "https://www.pixiv.net/favicon.ico",
                "https://s.pximg.net/www/images/pixiv_logo.png",
            ), Tag.SNI, true
        ),
        Target(
            "wikipedia", "Wikipedia", listOf(
                "https://zh.wikipedia.org/static/favicon/wikipedia.ico",
                "https://upload.wikimedia.org/wikipedia/commons/6/63/Wikipedia-logo.png",
            ), Tag.SNI, true
        ),
        Target(
            "reddit", "Reddit", listOf(
                "https://www.reddit.com/favicon.ico",
                "https://www.redditstatic.com/shreddit/assets/favicon/64x64.png",
            ), Tag.SNI
        ),
        Target("steam", "Steam Community", listOf("https://steamcommunity.com/favicon.ico"), Tag.SNI),
        Target("archive", "Internet Archive", listOf("https://archive.org/favicon.ico"), Tag.SNI),
        Target("discord", "Discord", listOf("https://discord.com/assets/favicon.ico"), Tag.UNCERTAIN),
        Target("pinterest", "Pinterest", listOf("https://www.pinterest.com/favicon.ico"), Tag.UNCERTAIN),
        Target(
            "instagram", "Instagram",
            listOf("https://www.instagram.com/static/images/ico/favicon.ico/36b3ee2d91ed.ico"), Tag.UNCERTAIN
        ),
        Target(
            "google", "Google", listOf(
                "https://www.google.com/favicon.ico",
                "https://www.gstatic.com/generate_204",
            ), Tag.IP_BLOCKED
        ),
        Target("youtube", "YouTube", listOf("https://www.youtube.com/favicon.ico"), Tag.IP_BLOCKED),
        Target("github", "GitHub", listOf("https://github.com/favicon.ico"), Tag.CONTROL),
    )

    val DEFAULT_SELECTION: String = PRESETS.filter { it.defaultEnabled }.joinToString(",") { it.id }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** One domain or URL per line ('#' comments allowed); bare domains get https://<domain>/. */
    fun parseCustom(text: String): List<Target> {
        return text.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it/" }
            .filter { hostOf(it) != null }
            .distinct()
            .map { url -> Target(CUSTOM_PREFIX + url, hostOf(url)!!, listOf(url), Tag.CUSTOM) }
    }

    fun parseSelection(text: String): Set<String> =
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun encodeSelection(ids: Collection<String>): String = ids.joinToString(",")

    /** Presets then custom entries, in display order. */
    fun all(customText: String): List<Target> = PRESETS + parseCustom(customText)

    fun selected(selection: String, customText: String): List<Target> {
        val ids = parseSelection(selection)
        return all(customText).filter { it.id in ids }
    }
}
