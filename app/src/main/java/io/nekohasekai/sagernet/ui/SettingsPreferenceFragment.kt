/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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

package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.ULocale
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.Key.MODE_VPN
import io.nekohasekai.sagernet.byedpi.ByeDPI
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.dualnet.DualNet
import io.nekohasekai.sagernet.dualnet.DualNetworkManager
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ColorPickerPreference
import io.nekohasekai.sagernet.widget.LinkOrContentPreference
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private var isProxyApps: SwitchPreference? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        listView.setPadding(0, 0, 0, dp2px(64))
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom + dp2px(64),
            )
            insets
        }
    }

    val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        // Guise: migrate the old OOB-based ByeDPI default (see ByeDPI.DEFAULT_ARGS)
        if (DataStore.byedpiArgs.trim() == ByeDPI.LEGACY_DEFAULT_ARGS) {
            DataStore.byedpiArgs = ByeDPI.DEFAULT_ARGS
        }
        setPreferencesFromResource(R.xml.global_preferences, rootKey)

        when (rootKey) {
            null -> setupRootScreen()
            "screen_appearance_stats" -> setupAppearanceStatsScreen()
            "screen_byedpi" -> setupByeDPIScreen()
            "screen_dual_net" -> setupDualNetScreen()
            "screen_route_advanced" -> setupRouteAdvancedScreen()
            "screen_dns_advanced" -> setupDnsAdvancedScreen()
            "screen_tun_network" -> setupTunNetworkScreen()
            "screen_protocol" -> setupProtocolScreen()
            "screen_inbound" -> setupInboundScreen()
            "screen_debug" -> setupDebugScreen()
        }
    }

    private fun setupRootScreen() {
        findPreference<ColorPickerPreference>(Key.APP_THEME)?.setOnPreferenceChangeListener { _, newTheme ->
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                ActivityCompat.recreate(this)
            }
            true
        }

        findPreference<ListPreference>(Key.NIGHT_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            Theme.currentNightMode = (newValue as String).toInt()
            Theme.applyNightTheme()
            requireActivity().apply {
                ActivityCompat.recreate(this)
            }
            true
        }

        fun getLanguageDisplayName(code: String): String = when (code) {
            "" -> getString(R.string.language_system_default)
            "ar" -> getString(R.string.language_ar_display_name)
            "cs" -> getString(R.string.language_cs_display_name)
            "en-US" -> getString(R.string.language_en_display_name)
            "es" -> getString(R.string.language_es_display_name)
            "fa" -> getString(R.string.language_fa_display_name)
            "fr" -> getString(R.string.language_fr_display_name)
            "id" -> getString(R.string.language_id_display_name)
            "it" -> getString(R.string.language_it_display_name)
            "ja" -> getString(R.string.language_ja_display_name)
            "ko" -> getString(R.string.language_ko_display_name)
            "nb-NO" -> getString(R.string.language_nb_NO_display_name)
            "ru" -> getString(R.string.language_ru_display_name)
            "ta" -> getString(R.string.language_ta_display_name)
            "tr" -> getString(R.string.language_tr_display_name)
            "zh-Hans-CN" -> getString(R.string.language_zh_Hans_CN_display_name)
            "zh-Hant-TW" -> getString(R.string.language_zh_Hant_TW_display_name)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ULocale.forLanguageTag(code).displayName
            } else {
                Locale.forLanguageTag(code).displayName
            }
        }
        findPreference<ListPreference>(Key.APP_LANGUAGE)?.let { appLanguage ->
            val locale = when (val value = AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                // https://stackoverflow.com/questions/13291578/how-to-localize-an-android-app-in-indonesian-language
                // Some old Android versions still return "in".
                "in" -> "id"
                else -> value
            }
            appLanguage.summary = getLanguageDisplayName(locale)
            appLanguage.value = if (locale in resources.getStringArray(R.array.language_value)) locale else ""
            appLanguage.setOnPreferenceChangeListener { _, newValue ->
                newValue as String
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newValue))
                appLanguage.summary = getLanguageDisplayName(newValue)
                appLanguage.value = newValue
                true
            }
        }

        val proxyAppsPref = findPreference<SwitchPreference>(Key.PROXY_APPS)
        isProxyApps = proxyAppsPref
        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)
        val enableIPv6 = findPreference<SwitchPreference>(Key.ENABLE_VPN_INTERFACE_IPV6_ADDRESS)
        val isVpn = DataStore.serviceMode == MODE_VPN

        findPreference<ListPreference>(Key.SERVICE_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            val vpn = (newValue as String) == MODE_VPN
            proxyAppsPref?.isEnabled = vpn
            bypassLan?.isEnabled = vpn
            enableIPv6?.isEnabled = vpn
            if (SagerNet.started) {
                runOnDefaultDispatcher {
                    SagerNet.stopService()
                    delay(300) // FIXME: Why this is needed?
                    SagerNet.startService()
                }
            }
            true
        }

        findPreference<Preference>(Key.ROUTE_MODE)?.onPreferenceChangeListener = reloadListener
        proxyAppsPref?.apply {
            isEnabled = isVpn
            setOnPreferenceChangeListener { _, newValue ->
                startActivity(Intent(activity, AppManagerActivity::class.java))
                newValue as Boolean
            }
        }
        bypassLan?.apply {
            isEnabled = isVpn
            onPreferenceChangeListener = reloadListener
        }
        enableIPv6?.apply {
            isEnabled = isVpn
            onPreferenceChangeListener = reloadListener
        }

        // Core DNS settings on root
        findPreference<EditTextPreference>(Key.REMOTE_DNS)?.onPreferenceChangeListener = reloadListener
        val useLocalDnsAsDirectDns = findPreference<SwitchPreference>(Key.USE_LOCAL_DNS_AS_DIRECT_DNS)
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)
        directDns?.onPreferenceChangeListener = reloadListener
        useLocalDnsAsDirectDns?.setOnPreferenceChangeListener { _, newValue ->
            directDns?.isEnabled = newValue == false
            needReload()
            true
        }
        if (useLocalDnsAsDirectDns != null && directDns != null) {
            directDns.isEnabled = !useLocalDnsAsDirectDns.isChecked
        }
        findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)?.onPreferenceChangeListener = reloadListener

        findPreference<ListPreference>(Key.PROTECTION_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as String).toIntOrNull() ?: ProtectionMode.MANUAL
            updateGuiseScreenSummaries(mode)
            needReload()
            true
        }
        findPreference<ListPreference>(Key.UI_STYLE)?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.uiStyle = (newValue as String).toIntOrNull() ?: 0
            val restart = Intent(requireContext(), MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(restart)
            requireActivity().finish()
            true
        }

        updateGuiseScreenSummaries()
    }

    private fun setupAppearanceStatsScreen() {
        val isVpn = DataStore.serviceMode == MODE_VPN
        findPreference<SwitchPreference>(Key.APP_TRAFFIC_STATISTICS)?.apply {
            isEnabled = isVpn
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    PackageCache.awaitLoadSync()
                }
                needReload()
                true
            }
        }
        val profileTrafficStatistics = findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)
        val speedInterval = findPreference<Preference>(Key.SPEED_INTERVAL)
        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)
        profileTrafficStatistics?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            speedInterval?.isEnabled = enabled
            showDirectSpeed?.isEnabled = enabled
            needReload()
            true
        }
        val statsEnabled = profileTrafficStatistics?.isChecked ?: DataStore.profileTrafficStatistics
        speedInterval?.apply {
            isEnabled = statsEnabled
            onPreferenceChangeListener = reloadListener
        }
        showDirectSpeed?.apply {
            isEnabled = statsEnabled
            onPreferenceChangeListener = reloadListener
        }
        findPreference<SwitchPreference>(Key.SHOW_GROUP_NAME)?.onPreferenceChangeListener = reloadListener
        findPreference<ListPreference>(Key.FAB_STYLE)?.setOnPreferenceChangeListener { _, _ ->
            requireActivity().apply {
                finish()
                startActivity(intent)
            }
            true
        }
    }

    private fun setupByeDPIScreen() {
        val byedpiEnabled = findPreference<SwitchPreference>(Key.BYEDPI_ENABLED)!!
        val byedpiMode = findPreference<ListPreference>(Key.BYEDPI_MODE)!!
        val byedpiDomains = findPreference<EditTextPreference>(Key.BYEDPI_DOMAINS)!!
        val byedpiArgs = findPreference<EditTextPreference>(Key.BYEDPI_ARGS)!!
        val isSmartSplit = DataStore.isSmartSplitMode
        fun updateByeDPIVisibility(enabled: Boolean, mode: Int) {
            if (isSmartSplit) {
                byedpiEnabled.isChecked = true
                byedpiEnabled.isEnabled = false
                byedpiEnabled.setSummary(R.string.protection_takeover_byedpi_smart)
                byedpiMode.isVisible = false
                byedpiDomains.isVisible = true
            } else {
                byedpiMode.isVisible = enabled
                byedpiDomains.isVisible = enabled && mode == ByeDPI.Mode.DOMAINS
            }
        }
        byedpiEnabled.setOnPreferenceChangeListener { _, newValue ->
            updateByeDPIVisibility(newValue as Boolean, DataStore.byedpiMode)
            needReload()
            true
        }
        byedpiMode.setOnPreferenceChangeListener { _, newValue ->
            updateByeDPIVisibility(byedpiEnabled.isChecked, (newValue as String).toIntOrNull() ?: 0)
            needReload()
            true
        }
        updateByeDPIVisibility(byedpiEnabled.isChecked, DataStore.byedpiMode)
        byedpiDomains.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        byedpiDomains.onPreferenceChangeListener = reloadListener
        byedpiArgs.setOnPreferenceChangeListener { _, _ ->
            DataStore.byedpiStrategyTested = true
            needReload()
            true
        }
        findPreference<EditTextPreference>(Key.BYEDPI_DNS)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
            onPreferenceChangeListener = reloadListener
        }
        findPreference<SwitchPreference>(Key.BYEDPI_DNS_VIA_BYEDPI)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.BYEDPI_LEARNING_MODE)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("byedpiLearningManage")?.setOnPreferenceClickListener {
            val good = io.nekohasekai.sagernet.byedpi.ByeDPILearningStore.knownGoodDomains()
            val blocked = io.nekohasekai.sagernet.byedpi.ByeDPILearningStore.blockedDomains()
            val counts = io.nekohasekai.sagernet.byedpi.ByeDPILearningStore.failCounts()
            val msg = buildString {
                append("• 已知可行域名 (${good.size}):\n")
                if (good.isEmpty()) append("  (空)\n") else good.forEach { append("  ✓ $it\n") }
                append("\n• 试探失败计数 (${counts.size}):\n")
                if (counts.isEmpty()) append("  (空)\n") else counts.forEach { (d, c) -> append("  ! $d ($c/5)\n") }
                append("\n• 已知不可行域名 (${blocked.size}):\n")
                if (blocked.isEmpty()) append("  (空)\n") else blocked.forEach { append("  ✕ $it\n") }
            }.trim()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.byedpi_learning_manage)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("重置失败与拉黑") { _, _ ->
                    io.nekohasekai.sagernet.byedpi.ByeDPILearningStore.resetBlockedAndFailures()
                    needReload()
                }
                .show()
            true
        }

        findPreference<Preference>(Key.BYEDPI_PROBE)!!.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ByeDPIProbeActivity::class.java))
            true
        }
        findPreference<Preference>(Key.BYEDPI_CREATE_PROFILE)!!.setOnPreferenceClickListener {
            runOnDefaultDispatcher {
                ProfileManager.createProfile(DataStore.currentGroupId(), SOCKSBean().apply {
                    name = getString(R.string.byedpi_profile_name)
                    serverAddress = ByeDPI.PROFILE_MARKER_ADDRESS
                    serverPort = 1080
                })
                onMainDispatcher {
                    snackbar(R.string.byedpi_profile_created).show()
                }
            }
            true
        }
    }

    private fun setupDualNetScreen() {
        val dualNetEnabled = findPreference<SwitchPreference>(Key.DUAL_NET_ENABLED)!!
        val dualNetStrategy = findPreference<ListPreference>(Key.DUAL_NET_STRATEGY)!!
        val dualNetSampleKb = findPreference<EditTextPreference>(Key.DUAL_NET_SAMPLE_KB)!!
        val dualNetSingle = findPreference<ListPreference>(Key.DUAL_NET_SINGLE)!!
        val dualNetUdpProbe = findPreference<EditTextPreference>(Key.DUAL_NET_UDP_PROBE_INTERVAL)!!
        val dualNetStatus = findPreference<Preference>(Key.DUAL_NET_STATUS)!!
        fun updateDualNetVisibility(enabled: Boolean, strategy: Int) {
            dualNetStrategy.isVisible = enabled
            dualNetSampleKb.isVisible = enabled && strategy == DualNet.Strategy.AUTO
            dualNetUdpProbe.isVisible = enabled && strategy == DualNet.Strategy.AUTO
            dualNetSingle.isVisible = enabled
            dualNetStatus.isVisible = enabled
        }
        dualNetEnabled.setOnPreferenceChangeListener { _, newValue ->
            updateDualNetVisibility(newValue as Boolean, DataStore.dualNetStrategy)
            needReload()
            true
        }
        dualNetStrategy.setOnPreferenceChangeListener { _, newValue ->
            updateDualNetVisibility(dualNetEnabled.isChecked, (newValue as String).toIntOrNull() ?: 0)
            needReload()
            true
        }
        dualNetSampleKb.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        dualNetSampleKb.onPreferenceChangeListener = reloadListener
        dualNetSampleKb.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            "${it.text ?: DualNet.DEFAULT_SAMPLE_KB} KB\n" + getString(R.string.dual_net_sample_kb_sum)
        }
        dualNetSingle.onPreferenceChangeListener = reloadListener
        dualNetUdpProbe.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        dualNetUdpProbe.onPreferenceChangeListener = reloadListener
        dualNetUdpProbe.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val v = it.text ?: DualNet.DEFAULT_UDP_PROBE_INTERVAL.toString()
            (if (v == "0") "0 (off)" else "$v s") + "\n" + getString(R.string.dual_net_udp_probe_sum)
        }
        updateDualNetVisibility(dualNetEnabled.isChecked, DataStore.dualNetStrategy)
        updateDualNetStatus()

        val lanBorrow = findPreference<SwitchPreference>(Key.LAN_PEER_BORROW_ENABLED)
        val lanShare = findPreference<SwitchPreference>(Key.LAN_PEER_SHARE_ENABLED)
        val lanLimit = findPreference<EditTextPreference>(Key.LAN_PEER_SHARE_LIMIT_MB)
        val lanUsed = findPreference<Preference>(Key.LAN_PEER_SHARE_USED_BYTES)
        val lanTrusted = findPreference<Preference>(Key.LAN_PEER_TRUSTED)

        lanBorrow?.onPreferenceChangeListener = reloadListener
        lanShare?.onPreferenceChangeListener = reloadListener
        lanLimit?.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            onPreferenceChangeListener = reloadListener
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val v = it.text?.toIntOrNull() ?: 0
                (if (v <= 0) "0" else "$v MB") + "\n" + getString(R.string.lan_peer_limit_sum)
            }
        }
        fun refreshLanStats() {
            val usedMb = DataStore.lanPeerShareUsedBytes / (1024L * 1024L)
            lanUsed?.summary = getString(R.string.lan_peer_used_sum, usedMb)
            val trustedCount = io.nekohasekai.sagernet.dualnet.LanPeerManager.parseTrusted().size
            lanTrusted?.summary = getString(R.string.lan_peer_clear_trusted_sum, trustedCount)
        }
        refreshLanStats()
        lanUsed?.setOnPreferenceClickListener {
            DataStore.lanPeerShareUsedBytes = 0L
            refreshLanStats()
            snackbar(R.string.lan_peer_used_reset).show()
            true
        }
        lanTrusted?.setOnPreferenceClickListener {
            io.nekohasekai.sagernet.dualnet.LanPeerManager.clearTrusted()
            refreshLanStats()
            snackbar(R.string.lan_peer_trusted_cleared).show()
            needReload()
            true
        }
    }

    private fun setupRouteAdvancedScreen() {
        val isVpn = DataStore.serviceMode == MODE_VPN
        findPreference<SwitchPreference>(Key.ALLOW_APPS_BYPASS_VPN)?.apply {
            isEnabled = isVpn
            onPreferenceChangeListener = reloadListener
        }
        findPreference<Preference>(Key.DOMAIN_STRATEGY)?.onPreferenceChangeListener = reloadListener

        val trafficSniffing = findPreference<SwitchPreference>(Key.TRAFFIC_SNIFFING)!!
        val destinationOverride = findPreference<SwitchPreference>(Key.DESTINATION_OVERRIDE)!!
        val hijackDns = findPreference<SwitchPreference>(Key.HIJACK_DNS)!!
        trafficSniffing.setOnPreferenceChangeListener { _, newValue ->
            destinationOverride.isEnabled = newValue as Boolean
            hijackDns.isEnabled = newValue
            needReload()
            true
        }
        destinationOverride.isEnabled = trafficSniffing.isChecked
        destinationOverride.onPreferenceChangeListener = reloadListener
        hijackDns.isEnabled = trafficSniffing.isChecked
        hijackDns.onPreferenceChangeListener = reloadListener

        findPreference<ListPreference>(Key.OUTBOUND_DOMAIN_STRATEGY)?.onPreferenceChangeListener = reloadListener
        findPreference<ListPreference>(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_DIRECT)?.onPreferenceChangeListener = reloadListener
        findPreference<ListPreference>(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_SERVER)?.onPreferenceChangeListener = reloadListener

        val rulesProvider = findPreference<ListPreference>(Key.RULES_PROVIDER)!!
        val rulesGeositeUrl = findPreference<LinkOrContentPreference>(Key.RULES_GEOSITE_URL)!!
        val rulesGeoipUrl = findPreference<LinkOrContentPreference>(Key.RULES_GEOIP_URL)!!
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl.isVisible = provider == 3
            rulesGeoipUrl.isVisible = provider == 3
            true
        }
        rulesGeositeUrl.isVisible = DataStore.rulesProvider == 3
        rulesGeoipUrl.isVisible = DataStore.rulesProvider == 3
    }

    private fun setupDnsAdvancedScreen() {
        findPreference<ListPreference>(Key.REMOTE_DNS_QUERY_STRATEGY)?.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.EDNS_CLIENT_IP)?.onPreferenceChangeListener = reloadListener
        findPreference<ListPreference>(Key.DIRECT_DNS_QUERY_STRATEGY)?.onPreferenceChangeListener = reloadListener

        val useLocalDnsAsBootstrapDns = findPreference<SwitchPreference>(Key.USE_LOCAL_DNS_AS_BOOTSTRAP_DNS)!!
        val bootstrapDns = findPreference<EditTextPreference>(Key.BOOTSTRAP_DNS)!!
        bootstrapDns.onPreferenceChangeListener = reloadListener
        useLocalDnsAsBootstrapDns.setOnPreferenceChangeListener { _, newValue ->
            bootstrapDns.isEnabled = newValue == false
            needReload()
            true
        }
        bootstrapDns.isEnabled = !useLocalDnsAsBootstrapDns.isChecked

        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.dialogMessage = getString(R.string.one_per_line_format, "example.com 127.0.0.1\nwww.example.com 127.0.0.1 127.0.0.2")
        dnsHosts.onPreferenceChangeListener = reloadListener

        findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupTunNetworkScreen() {
        val isVpn = DataStore.serviceMode == MODE_VPN
        val tunImplementation = findPreference<ListPreference>(Key.TUN_IMPLEMENTATION)!!
        val mtu = findPreference<EditTextPreference>(Key.MTU)!!
        val meteredNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        val discardICMP = findPreference<SwitchPreference>(Key.DISCARD_ICMP)!!

        tunImplementation.isEnabled = isVpn
        tunImplementation.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as String).toInt() == TunImplementation.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.error_title)
                    setMessage(R.string.nearby_devices_permission_notice)
                    setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts("package", app.packageName, null)
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    setPositiveButton(android.R.string.ok, null)
                }.show()
            }
            if (SagerNet.started) {
                runOnDefaultDispatcher {
                    SagerNet.reloadService()
                }
            }
            true
        }
        mtu.isEnabled = isVpn
        mtu.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        mtu.onPreferenceChangeListener = reloadListener
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            meteredNetwork.remove()
        } else {
            meteredNetwork.isEnabled = isVpn
            meteredNetwork.onPreferenceChangeListener = reloadListener
        }
        discardICMP.isEnabled = isVpn
        discardICMP.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupProtocolScreen() {
        val enableFragment = findPreference<SwitchPreference>(Key.ENABLE_FRAGMENT)!!
        val enableFragmentForDirect = findPreference<SwitchPreference>(Key.ENABLE_FRAGMENT_FOR_DIRECT)!!
        val fragmentMethod = findPreference<ListPreference>(Key.FRAGMENT_METHOD)!!
        enableFragment.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            enableFragmentForDirect.isVisible = newValue
            fragmentMethod.isVisible = newValue
            true
        }
        enableFragmentForDirect.isVisible = enableFragment.isChecked
        fragmentMethod.isVisible = enableFragment.isChecked
    }

    private fun setupInboundScreen() {
        val isVpn = DataStore.serviceMode == MODE_VPN
        val requireHttp = findPreference<SwitchPreference>(Key.REQUIRE_HTTP)!!
        val httpUsername = findPreference<EditTextPreference>(Key.HTTP_USERNAME)!!
        val httpPassword = findPreference<EditTextPreference>(Key.HTTP_PASSWORD)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!
        val httpProxyException = findPreference<EditTextPreference>(Key.HTTP_PROXY_EXCEPTION)!!

        val allowAccess = findPreference<SwitchPreference>(Key.ALLOW_ACCESS)!!
        allowAccess.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            if (newValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.error_title)
                    setMessage(R.string.nearby_devices_permission_notice)
                    setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts("package", app.packageName, null)
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    setPositiveButton(android.R.string.ok, null)
                }.show()
            }
            needReload()
            true
        }

        val requireSocks = findPreference<SwitchPreference>(Key.REQUIRE_SOCKS)!!
        val requireTransproxy = findPreference<SwitchPreference>(Key.REQUIRE_TRANSPROXY)!!
        val requireDns = findPreference<SwitchPreference>(Key.REQUIRE_DNS_INBOUND)!!
        allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || requireTransproxy.isChecked || requireDns.isChecked

        val portSocks5 = findPreference<EditTextPreference>(Key.SOCKS_PORT)!!
        portSocks5.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portSocks5.isVisible = requireSocks.isChecked
        portSocks5.onPreferenceChangeListener = reloadListener
        val socks5UDP = findPreference<SwitchPreference>(Key.SOCKS_UDP)!!
        val socks5Username = findPreference<EditTextPreference>(Key.SOCKS_USERNAME)!!
        socks5Username.isVisible = requireSocks.isChecked
        socks5Username.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            if (newValue.isNotEmpty() && socks5UDP.isVisible && socks5UDP.isChecked && !DataStore.socksUDPWarningDisable) runOnMainDispatcher {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setMessage(R.string.socks5_udp_authentication_warning)
                    setPositiveButton(android.R.string.ok, null)
                    setNeutralButton(R.string.do_not_show_again) { _, _ ->
                        DataStore.socksUDPWarningDisable = true
                    }
                }.show()
            }
            needReload()
            true
        }
        val socks5Password = findPreference<EditTextPreference>(Key.SOCKS_PASSWORD)!!
        socks5Password.summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
        socks5Password.isVisible = requireSocks.isChecked
        socks5Password.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            if (newValue.isNotEmpty() && socks5UDP.isVisible && socks5UDP.isChecked && !DataStore.socksUDPWarningDisable) runOnMainDispatcher {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setMessage(R.string.socks5_udp_authentication_warning)
                    setPositiveButton(android.R.string.ok, null)
                    setNeutralButton(R.string.do_not_show_again) { _, _ ->
                        DataStore.socksUDPWarningDisable = true
                    }
                }.show()
            }
            needReload()
            true
        }
        socks5UDP.isVisible = requireSocks.isChecked
        socks5UDP.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            if (newValue
                && ((socks5Username.isVisible && !socks5Username.text.isNullOrEmpty()) || (socks5Password.isVisible && !socks5Password.text.isNullOrEmpty()))
                && !DataStore.socksUDPWarningDisable) runOnMainDispatcher {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setMessage(R.string.socks5_udp_authentication_warning)
                    setPositiveButton(android.R.string.ok, null)
                    setNeutralButton(R.string.do_not_show_again) { _, _ ->
                        DataStore.socksUDPWarningDisable = true
                    }
                }.show()
            }
            needReload()
            true
        }
        requireSocks.setOnPreferenceChangeListener { _, newValue ->
            portSocks5.isVisible = newValue as Boolean
            socks5Username.isVisible = newValue
            socks5Password.isVisible = newValue
            socks5UDP.isVisible = newValue
            allowAccess.isVisible = newValue || requireHttp.isChecked || requireTransproxy.isChecked || requireDns.isChecked
            needReload()
            true
        }

        val portHttp = findPreference<EditTextPreference>(Key.HTTP_PORT)!!
        portHttp.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portHttp.isVisible = requireHttp.isChecked
        portHttp.onPreferenceChangeListener = reloadListener
        httpUsername.isVisible = requireHttp.isChecked
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            httpUsername.onPreferenceChangeListener = reloadListener
        } else {
            httpUsername.setOnPreferenceChangeListener { _, newValue ->
                newValue as String
                appendHttpProxy.isVisible = isVpn && newValue.isEmpty() && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                httpProxyException.isVisible = isVpn && newValue.isEmpty()
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                needReload()
                true
            }
        }
        httpPassword.summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
        httpPassword.isVisible = requireHttp.isChecked
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            httpPassword.onPreferenceChangeListener = reloadListener
        } else {
            httpPassword.setOnPreferenceChangeListener { _, newValue ->
                newValue as String
                appendHttpProxy.isVisible = isVpn && newValue.isEmpty() && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                httpProxyException.isVisible = isVpn && newValue.isEmpty()
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                needReload()
                true
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requireHttp.setOnPreferenceChangeListener { _, newValue ->
                portHttp.isVisible = newValue as Boolean
                httpUsername.isVisible = newValue
                httpPassword.isVisible = newValue
                allowAccess.isVisible = requireSocks.isChecked || newValue || requireTransproxy.isChecked || requireDns.isChecked
                needReload()
                true
            }
            appendHttpProxy.remove()
            httpProxyException.remove()
        } else {
            requireHttp.setOnPreferenceChangeListener { _, newValue ->
                portHttp.isVisible = newValue as Boolean
                httpUsername.isVisible = newValue
                httpPassword.isVisible = newValue
                appendHttpProxy.isVisible = newValue && isVpn
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                httpProxyException.isVisible = newValue && isVpn
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                allowAccess.isVisible = requireSocks.isChecked || newValue || requireTransproxy.isChecked || requireDns.isChecked
                needReload()
                true
            }
            appendHttpProxy.isVisible = requireHttp.isChecked && isVpn
                    && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                    && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
            appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
                httpProxyException.isVisible = newValue as Boolean
                needReload()
                true
            }
            httpProxyException.isVisible = requireHttp.isChecked && isVpn
                    && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                    && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                    && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
            httpProxyException.onPreferenceChangeListener = reloadListener
        }

        val transproxyPort = findPreference<EditTextPreference>(Key.TRANSPROXY_PORT)!!
        requireTransproxy.setOnPreferenceChangeListener { _, newValue ->
            transproxyPort.isVisible = newValue as Boolean
            allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || newValue || requireDns.isChecked
            needReload()
            true
        }
        transproxyPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        transproxyPort.isVisible = requireTransproxy.isChecked
        transproxyPort.onPreferenceChangeListener = reloadListener

        val portLocalDns = findPreference<EditTextPreference>(Key.LOCAL_DNS_PORT)!!
        requireDns.setOnPreferenceChangeListener { _, newValue ->
            portLocalDns.isVisible = newValue as Boolean
            allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || requireTransproxy.isChecked || newValue
            needReload()
            true
        }
        portLocalDns.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portLocalDns.isVisible = requireDns.isChecked
        portLocalDns.onPreferenceChangeListener = reloadListener
    }

    private fun setupDebugScreen() {
        findPreference<ListPreference>(Key.LOG_LEVEL)?.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as String).toInt() == LogLevel.DEBUG && !DataStore.logLevelDebugWarningDisable) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setMessage(R.string.debug_log_sum)
                    setPositiveButton(android.R.string.ok, null)
                    setNeutralButton(R.string.do_not_show_again) { _, _ ->
                        DataStore.logLevelDebugWarningDisable = true
                    }
                }.show()
            }
            needReload()
            true
        }

        findPreference<SwitchPreference>(Key.ENABLE_PCAP)?.apply {
            isEnabled = DataStore.serviceMode == MODE_VPN && DataStore.tunImplementation == TunImplementation.GVISOR
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val path = File(
                        app.getExternalFilesDir(null)?.apply { mkdirs() } ?: app.filesDir,
                        "pcap"
                    ).canonicalPath
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle(R.string.pcap)
                        setMessage(resources.getString(R.string.pcap_notice, path))
                        setPositiveButton(android.R.string.ok) { _, _ ->
                            needReload()
                        }
                        setNegativeButton(android.R.string.copy) { _, _ ->
                            SagerNet.trySetPrimaryClip(path)
                            snackbar(R.string.copy_success).show()
                        }
                    }.show()
                }
                needReload()
                true
            }
        }

        findPreference<ListPreference>(Key.PROVIDER_ROOT_CA)?.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as String).toInt() == RootCAProvider.CUSTOM) {
                runOnMainDispatcher {
                    val context = requireContext()
                    MaterialAlertDialogBuilder(context)
                        .setMessage(getString(R.string.custom_root_ca_hint, context.packageName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            true
        }

        findPreference<EditTextPreference>(Key.PPROF_SERVER)?.isVisible = DataStore.enableDebug
        findPreference<EditTextPreference>(Key.GODEBUG)?.isVisible = DataStore.enableDebug
        findPreference<EditTextPreference>(Key.ENVIRONMENT_VARIABLES)?.isVisible = DataStore.enableDebug
        findPreference<EditTextPreference>(Key.EXPERIMENTAL_FLAGS)?.isVisible = DataStore.enableDebug
    }

    private fun updateGuiseScreenSummaries(mode: Int = DataStore.effectiveProtectionMode) {
        val effMode = if (mode == 4) ProtectionMode.STRICT else mode
        val isProtectionLocked = effMode == ProtectionMode.STRICT
        findPreference<Preference>("screen_byedpi")?.let { pref ->
            pref.isEnabled = !isProtectionLocked && effMode != ProtectionMode.TURBO
            pref.summary = when {
                effMode == ProtectionMode.SMART_SPLIT -> {
                    val args = DataStore.byedpiArgs.trim().ifEmpty { ByeDPI.DEFAULT_ARGS }
                    "${getString(R.string.protection_takeover_byedpi_smart)} · $args"
                }
                isProtectionLocked || effMode == ProtectionMode.TURBO -> getString(R.string.protection_takeover_byedpi_disabled)
                DataStore.byedpiEnabled -> {
                    val args = DataStore.byedpiArgs.trim().ifEmpty { ByeDPI.DEFAULT_ARGS }
                    "${getString(R.string.settings_status_enabled)} · $args"
                }
                else -> getString(R.string.settings_status_disabled)
            }
        }
        findPreference<Preference>("screen_dual_net")?.let { pref ->
            pref.isEnabled = !isProtectionLocked
            pref.summary = when {
                isProtectionLocked -> getString(R.string.protection_takeover_dualnet)
                effMode == ProtectionMode.TURBO -> getString(R.string.protection_mode_desc_turbo)
                DataStore.dualNetEnabled -> {
                    val strategy = if (DataStore.dualNetStrategy == DualNet.Strategy.AUTO) {
                        getString(R.string.dual_net_strategy_auto)
                    } else {
                        getString(R.string.dual_net_strategy_fixed)
                    }
                    "${getString(R.string.settings_status_enabled)} · $strategy"
                }
                else -> getString(R.string.settings_status_disabled)
            }
        }
    }

    private fun updateDualNetStatus() {
        val pref = findPreference<Preference>(Key.DUAL_NET_STATUS) ?: return
        val lines = runCatching { DualNetworkManager.statusFile.readLines() }.getOrNull()
        pref.summary = if (lines == null || lines.size < 2) {
            getString(R.string.dual_net_status_idle)
        } else {
            val mode = if (lines[0] == "split") R.string.dual_net_status_split else R.string.dual_net_status_single
            getString(mode) + "\n" + lines[1]
        }
    }

    override fun onResume() {
        super.onResume()

        isProxyApps?.isChecked = DataStore.proxyApps
        updateGuiseScreenSummaries()
        updateDualNetStatus()
        // Guise: the connectivity test may have changed the ByeDPI arguments.
        findPreference<EditTextPreference>(Key.BYEDPI_ARGS)?.let {
            if (it.text != DataStore.byedpiArgs) it.text = DataStore.byedpiArgs
        }
    }

}