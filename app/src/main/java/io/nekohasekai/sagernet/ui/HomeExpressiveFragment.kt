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

package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.ProtectionMode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.byedpi.ByeDPILearningStore
import io.nekohasekai.sagernet.byedpi.CdnDpiScanner
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.FragmentHomeExpressiveBinding
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class HomeExpressiveFragment : Fragment(R.layout.fragment_home_expressive) {

    private var _binding: FragmentHomeExpressiveBinding? = null
    private val binding get() = _binding!!

    private var orbitAnimator: ObjectAnimator? = null
    private var exitIpJob: Job? = null
    private var scanJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeExpressiveBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top)
            insets
        }

        val toggleClick = View.OnClickListener {
            binding.heroButton.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).withEndAction {
                if (_binding != null) {
                    binding.heroButton.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(1.8f))
                        .setDuration(260)
                        .start()
                }
            }.start()
            (activity as? MainActivity)?.toggleService()
        }
        binding.heroButton.setOnClickListener(toggleClick)
        binding.heroCard.setOnClickListener(toggleClick)

        binding.modePill.setOnClickListener {
            showProtectionModeDialog()
        }

        binding.nodeInfoCard.setOnClickListener {
            (activity as? MainActivity)?.let { main ->
                main.displayFragmentWithId(R.id.nav_nodes)
                main.binding.bottomNav.selectedItemId = R.id.nav_nodes
            }
        }

        binding.exitIpCard.setOnClickListener {
            val state = (activity as? MainActivity)?.state ?: BaseService.State.Idle
            if (state == BaseService.State.Connected) {
                refreshExitIp()
            } else {
                (activity as? MainActivity)?.toggleService()
            }
        }

        val learningClick = View.OnClickListener {
            showCdnLearningDialog()
        }
        binding.learningCountChip.setOnClickListener(learningClick)
        binding.learningHeaderRow.setOnClickListener(learningClick)

        binding.btnSmartScan.setOnClickListener {
            showSmartScanPicker()
        }

        refreshUi((activity as? MainActivity)?.state ?: BaseService.State.Idle)
    }

    override fun onResume() {
        super.onResume()
        refreshUi((activity as? MainActivity)?.state ?: BaseService.State.Idle)
    }

    fun onServiceStateChanged(state: BaseService.State) {
        if (_binding == null || !isAdded) return
        refreshUi(state)
    }

    private fun refreshUi(state: BaseService.State) {
        val ctx = context ?: return
        val connected = state == BaseService.State.Connected
        val connecting = state == BaseService.State.Connecting
        val active = connected || connecting

        binding.statusSubtitleTop.text = when {
            connected -> getString(R.string.home_subtitle_running)
            connecting -> getString(R.string.connecting)
            else -> getString(R.string.home_subtitle_ready)
        }

        binding.statusDisplay.text = when {
            connected -> getString(R.string.home_status_connected)
            connecting -> getString(R.string.connecting)
            else -> getString(R.string.home_status_disconnected)
        }

        val heroBg = MaterialColors.getColor(
            ctx,
            if (active) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainer,
            0,
        )
        val heroFg = MaterialColors.getColor(
            ctx,
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface,
            0,
        )
        binding.heroCard.setCardBackgroundColor(heroBg)
        binding.statusSubtitleTop.setTextColor(heroFg)
        binding.statusDisplay.setTextColor(heroFg)
        binding.heroModeSubtitle.setTextColor(heroFg)
        binding.heroOrbit.imageTintList = ColorStateList.valueOf(heroFg)

        // Expressive shape morph: circle (32dp radius on 64dp button) when idle <-> squircle (22dp) when connected
        val targetRadius = dp2px(if (active) 22 else 32).toFloat()
        if (binding.heroButton.radius != targetRadius) {
            ValueAnimator.ofFloat(binding.heroButton.radius, targetRadius).apply {
                duration = 360
                interpolator = OvershootInterpolator(1.4f)
                addUpdateListener {
                    if (_binding != null) {
                        binding.heroButton.radius = it.animatedValue as Float
                    }
                }
                start()
            }
        }

        val btnBg = MaterialColors.getColor(
            ctx,
            if (active) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorPrimaryContainer,
            0,
        )
        val btnFg = MaterialColors.getColor(
            ctx,
            if (active) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnPrimaryContainer,
            0,
        )
        binding.heroButton.setCardBackgroundColor(btnBg)
        binding.heroIcon.imageTintList = ColorStateList.valueOf(btnFg)

        updateOrbitAnimation(connected, connecting)
        updateModeAndCards()

        if (connected) {
            refreshExitIp()
        } else {
            exitIpJob?.cancel()
            binding.exitCountryText.text = getString(R.string.home_exit_not_connected)
            binding.exitIpText.text = getString(R.string.home_exit_tap_to_check)
        }
    }

    private fun updateOrbitAnimation(connected: Boolean, connecting: Boolean) {
        if (connected || connecting) {
            binding.heroOrbit.animate().alpha(if (connected) 0.90f else 0.65f).setDuration(250).start()
            val durationMs = if (connecting) 2200L else 6000L
            if (orbitAnimator == null || orbitAnimator?.duration != durationMs) {
                orbitAnimator?.cancel()
                orbitAnimator = ObjectAnimator.ofFloat(
                    binding.heroOrbit,
                    View.ROTATION,
                    binding.heroOrbit.rotation,
                    binding.heroOrbit.rotation + 360f,
                ).apply {
                    duration = durationMs
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            } else if (orbitAnimator?.isRunning != true) {
                orbitAnimator?.start()
            }
        } else {
            orbitAnimator?.cancel()
            orbitAnimator = null
            binding.heroOrbit.animate().alpha(0.25f).setDuration(250).start()
        }
    }

    private fun updateModeAndCards() {
        val mode = DataStore.protectionMode
        val modeText = when (mode) {
            ProtectionMode.SMART_SPLIT -> getString(R.string.protection_mode_smart_split)
            ProtectionMode.STANDARD -> getString(R.string.protection_mode_standard)
            ProtectionMode.BALANCED -> getString(R.string.protection_mode_balanced)
            ProtectionMode.STRICT -> getString(R.string.protection_mode_strict)
            else -> getString(R.string.protection_mode_manual)
        }
        binding.modeLabel.text = modeText
        binding.heroModeSubtitle.text = modeText

        val isSmartSplit = mode == ProtectionMode.SMART_SPLIT
        val showScanCard = isSmartSplit || mode == ProtectionMode.MANUAL

        binding.smartSplitRow.visibility = if (isSmartSplit) View.VISIBLE else View.GONE
        binding.nodeMetaText.visibility = if (isSmartSplit) View.GONE else View.VISIBLE

        binding.smartScanCard.visibility = if (showScanCard) View.VISIBLE else View.GONE
        binding.protectionInfoCard.visibility = if (showScanCard) View.GONE else View.VISIBLE

        if (showScanCard) {
            val learningOn = DataStore.byedpiLearningMode
            binding.learningStatusText.text = getString(
                if (learningOn) R.string.home_learning_on_sub else R.string.home_learning_off_sub
            )
            val goodCount = ByeDPILearningStore.knownGoodDomains().size
            val blockedCount = ByeDPILearningStore.blockedDomains().size
            binding.learningCountText.text = getString(R.string.home_learning_counts, goodCount, blockedCount)
        }

        runOnDefaultDispatcher {
            val proxy = runCatching { SagerDatabase.proxyDao.getById(DataStore.selectedProxy) }.getOrNull()
            val proxyName = proxy?.displayName() ?: getString(R.string.profile_empty)
            val meta = if (proxy != null) {
                val type = proxy.displayType()
                if (proxy.status == 1 && proxy.ping > 0) "$type · ${proxy.ping} ms" else type
            } else {
                getString(R.string.nav_nodes)
            }
            onMainDispatcher {
                if (_binding != null) {
                    binding.pillProxyName.text = proxyName
                    binding.nodeMetaText.text = meta
                }
            }
        }
    }

    private fun showProtectionModeDialog() {
        val entries = resources.getStringArray(R.array.protection_mode_entry)
        val current = DataStore.protectionMode.coerceIn(0, entries.lastIndex)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.protection_mode_title)
            .setSingleChoiceItems(entries, current) { dialog, which ->
                dialog.dismiss()
                if (which != DataStore.protectionMode) {
                    DataStore.protectionMode = which
                    updateModeAndCards()
                    if (SagerNet.started) {
                        SagerNet.reloadService()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCdnLearningDialog() {
        val ctx = context ?: return
        val good = ByeDPILearningStore.knownGoodDomains()
        val blocked = ByeDPILearningStore.blockedDomains()
        val counts = ByeDPILearningStore.failCounts()
        val learningOn = DataStore.byedpiLearningMode
        val msg = buildString {
            append("当前状态：").append(if (learningOn) "已开启" else "已关闭").append("\n\n")
            append("• 已知可行域名 (${good.size}):\n")
            if (good.isEmpty()) append("  (空)\n") else good.forEach { append("  ✓ $it\n") }
            append("\n• 试探失败计数 (${counts.size}):\n")
            if (counts.isEmpty()) append("  (空)\n") else counts.forEach { (d, c) -> append("  ! $d ($c/${ByeDPILearningStore.MAX_FAILURES})\n") }
            append("\n• 已知不可行域名 (${blocked.size}):\n")
            if (blocked.isEmpty()) append("  (空)\n") else blocked.forEach { append("  ✕ $it\n") }
        }.trim()

        val toggleLabel = if (learningOn) "关闭学习模式" else "开启学习模式"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.byedpi_learning_manage)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(toggleLabel) { _, _ ->
                DataStore.byedpiLearningMode = !learningOn
                updateModeAndCards()
                if (SagerNet.started) {
                    SagerNet.reloadService()
                }
            }
            .setNeutralButton("重置失败与拉黑") { _, _ ->
                ByeDPILearningStore.resetBlockedAndFailures()
                updateModeAndCards()
                if (SagerNet.started) {
                    SagerNet.reloadService()
                }
            }
            .show()
    }

    private fun showSmartScanPicker() {
        val services = CdnDpiScanner.SERVICES
        val labels = services.map { it.label }.toTypedArray()
        val checked = BooleanArray(services.size) { services[it].defaultSelected }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_cdn_scan_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = services.filterIndexed { index, _ -> checked[index] }
                    .flatMap { it.hosts }
                    .distinct()
                if (selected.isNotEmpty()) {
                    runSmartSplitScan(selected)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSmartSplitScan(domains: List<String> = CdnDpiScanner.candidateDomains()) {
        if (scanJob?.isActive == true) return
        binding.btnSmartScan.isEnabled = false
        scanJob = runOnDefaultDispatcher {
            val result = CdnDpiScanner.scanAndSave(domains) { progress ->
                activity?.runOnUiThread {
                    if (_binding != null) {
                        val phaseLabel = when (progress.phase) {
                            CdnDpiScanner.Phase.RESOLVING_DNS -> "正在扫描 DoH DNS"
                            CdnDpiScanner.Phase.PROBING_IPS -> "正在检测 TCP:443"
                            CdnDpiScanner.Phase.VERIFYING_BYEDPI -> "正在验证 ByeDPI TLS"
                        }
                        binding.btnSmartScan.text = "$phaseLabel (${progress.done}/${progress.total})..."
                    }
                }
            }
            onMainDispatcher {
                if (_binding != null && isAdded) {
                    binding.btnSmartScan.isEnabled = true
                    binding.btnSmartScan.text = getString(R.string.home_smart_scan)
                    updateModeAndCards()
                    showScanResultDialog(result)
                    if (SagerNet.started) {
                        SagerNet.reloadService()
                    }
                }
            }
        }
    }

    private fun showScanResultDialog(result: CdnDpiScanner.ScanResult) {
        val ctx = context ?: return
        val proxyTotal = result.strategyFailedDomains.size + result.ipBlockedDomains.size
        val sb = StringBuilder()

        sb.append("• 可走 ByeDPI (${result.byedpiDomains.size}):\n")
        if (result.byedpiDomains.isEmpty()) {
            sb.append("  (无)\n")
        } else {
            result.byedpiDomains.forEach { host ->
                val cdn = result.cdnProviders[host]?.let { " [${it.displayName} ${it.asn}]" }.orEmpty()
                sb.append("  ✓ $host$cdn\n")
            }
        }

        if (result.strategyFailedDomains.isNotEmpty()) {
            sb.append("\n• ByeDPI 策略无效 · 走代理或更换参数 (${result.strategyFailedDomains.size}):\n")
            result.strategyFailedDomains.forEach { host ->
                val cdn = result.cdnProviders[host]?.let { " [${it.displayName} ${it.asn}]" }.orEmpty()
                sb.append("  ⚡ $host$cdn\n")
            }
        }

        sb.append("\n• IP 阻断 · 必须走代理 (${result.ipBlockedDomains.size}):\n")
        if (result.ipBlockedDomains.isEmpty()) {
            sb.append("  (无)\n")
        } else {
            result.ipBlockedDomains.forEach { host ->
                val cdn = result.cdnProviders[host]?.let { " [${it.displayName} ${it.asn}]" }.orEmpty()
                sb.append("  ✕ $host$cdn\n")
            }
        }

        if (result.dnsFailedDomains.isNotEmpty()) {
            sb.append("\n• DoH DNS 解析失败 (${result.dnsFailedDomains.size}):\n")
            result.dnsFailedDomains.forEach { sb.append("  ? $it\n") }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.home_smart_scan_done, result.byedpiDomains.size, proxyTotal))
            .setMessage(sb.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshExitIp() {
        exitIpJob?.cancel()
        binding.exitCountryText.text = getString(R.string.home_exit_detecting)
        binding.exitIpText.text = "..."
        exitIpJob = runOnDefaultDispatcher {
            val info = fetchExitIp()
            onMainDispatcher {
                if (_binding != null) {
                    if (info != null) {
                        binding.exitCountryText.text = info.first
                        binding.exitIpText.text = info.second
                    } else {
                        binding.exitCountryText.text = getString(R.string.home_exit_unknown)
                        binding.exitIpText.text = getString(R.string.home_exit_tap_to_refresh)
                    }
                }
            }
        }
    }

    private fun fetchExitIp(): Pair<String, String>? {
        val proxy = if (DataStore.serviceMode != "vpn") {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.httpPort))
        } else null
        return runCatching {
            val url = URL("https://api.ip.sb/geoip")
            val conn = (proxy?.let { url.openConnection(it) } ?: url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Guise/2.0")
            try {
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
                val code = json.optString("country_code").uppercase()
                val country = json.optString("country")
                val flag = countryCodeToFlag(code)
                val countryLine = if (flag.isNotEmpty() || country.isNotBlank()) {
                    "$flag ${country.ifBlank { code }}".trim()
                } else {
                    ip
                }
                countryLine to ip
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun countryCodeToFlag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    override fun onDestroyView() {
        orbitAnimator?.cancel()
        orbitAnimator = null
        exitIpJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
