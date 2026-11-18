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

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ProtectionMode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.byedpi.ByeDPI
import io.nekohasekai.sagernet.byedpi.ByeDPILearningStore
import io.nekohasekai.sagernet.byedpi.ByeDPIProbe
import io.nekohasekai.sagernet.byedpi.CdnDpiScanner
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.FragmentHomeExpressiveBinding
import io.nekohasekai.sagernet.dualnet.DualNet
import io.nekohasekai.sagernet.dualnet.DualNetworkManager
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class HomeExpressiveFragment : Fragment(R.layout.fragment_home_expressive) {

    private var _binding: FragmentHomeExpressiveBinding? = null
    private val binding get() = _binding!!

    private var exitIpJob: Job? = null
    private var scanJob: Job? = null
    private var dualEngineTickerJob: Job? = null

    private val probeForSmartScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK || DataStore.byedpiStrategyTested) {
            if (_binding != null && isAdded) {
                updateModeAndCards()
                showSmartScanPicker()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeExpressiveBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.homeRoot)

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
        binding.modePill.setOnCloseIconClickListener {
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
            if (!DataStore.byedpiStrategyTested) {
                // Jump to ByeDPIProbeActivity first to test & apply a working ByeDPI strategy
                val intent = Intent(requireContext(), ByeDPIProbeActivity::class.java).apply {
                    putExtra(ByeDPIProbeActivity.EXTRA_FOR_SMART_SCAN, true)
                }
                probeForSmartScanLauncher.launch(intent)
            } else {
                showSmartScanPicker()
            }
        }

        binding.btnProbeStrategy.setOnClickListener {
            startActivity(Intent(requireContext(), ByeDPIProbeActivity::class.java))
        }

        refreshUi((activity as? MainActivity)?.state ?: BaseService.State.Idle)
    }

    override fun onResume() {
        super.onResume()
        refreshUi((activity as? MainActivity)?.state ?: BaseService.State.Idle)
    }

    override fun onPause() {
        dualEngineTickerJob?.cancel()
        super.onPause()
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

        // "VPN 运行中 · 已连接" / "准备就绪 · 未连接" / "正在连接……"
        binding.statusSubtitleTop.text = when {
            connected -> getString(
                R.string.home_status_line,
                getString(R.string.home_subtitle_running),
                getString(R.string.home_status_connected),
            )
            connecting -> getString(R.string.connecting)
            else -> getString(
                R.string.home_status_line,
                getString(R.string.home_subtitle_ready),
                getString(R.string.home_status_disconnected),
            )
        }

        // Hero status in Michroma (Latin only, so it uses dedicated English display strings)
        binding.statusDisplay.text = getString(
            when {
                connected -> R.string.home_hero_connected
                connecting -> R.string.home_hero_connecting
                else -> R.string.home_hero_disconnected
            }
        )

        // M3 color roles: primaryContainer hero when active, surfaceContainerHighest when idle
        fun attrColor(attr: Int) = MaterialColors.getColor(ctx, attr, 0)
        val heroBg = attrColor(
            if (active) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHighest
        )
        val heroFg = attrColor(
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface
        )
        val heroSubFg = attrColor(
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        binding.heroCard.setCardBackgroundColor(heroBg)
        binding.statusSubtitleTop.setTextColor(heroSubFg)
        binding.heroStatusIcon.imageTintList = ColorStateList.valueOf(heroSubFg)
        binding.statusDisplay.setTextColor(heroFg)
        binding.heroModeSubtitle.setTextColor(heroFg)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.heroModeSubtitle, ColorStateList.valueOf(heroFg)
        )
        binding.heroModeDesc.setTextColor(heroSubFg)
        binding.heroButtonLabel.setTextColor(heroSubFg)
        binding.heroButtonLabel.text = getString(if (active) R.string.home_tap_stop else R.string.home_tap_start)

        // Large FAB: primary / onPrimary; stop icon while running, power icon while idle
        binding.heroButton.backgroundTintList =
            ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        binding.heroButton.imageTintList =
            ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnPrimary))
        binding.heroButton.setImageResource(if (active) R.drawable.ic_home_stop else R.drawable.ic_expressive_power)
        binding.heroButton.alpha = if (connecting) 0.7f else 1f

        updateModeAndCards(connected)

        if (connected) {
            refreshExitIp()
        } else {
            exitIpJob?.cancel()
            binding.exitCardTitle.text = getString(R.string.home_exit_ip_label)
            binding.exitCountryText.text = getString(R.string.home_exit_not_connected)
            binding.exitIpText.text = getString(R.string.home_exit_tap_to_check)
        }
    }

    private fun updateModeAndCards(
        connected: Boolean = ((activity as? MainActivity)?.state == BaseService.State.Connected)
    ) {
        val mode = DataStore.effectiveProtectionMode
        val modeText = when (mode) {
            ProtectionMode.SMART_SPLIT -> getString(R.string.protection_mode_smart_split)
            ProtectionMode.STRICT -> getString(R.string.protection_mode_strict)
            ProtectionMode.TURBO -> getString(R.string.protection_mode_turbo)
            else -> getString(R.string.protection_mode_manual)
        }
        binding.modePill.text = modeText
        binding.heroModeSubtitle.text = modeText
        binding.heroModeDesc.text = getString(
            when (mode) {
                ProtectionMode.SMART_SPLIT -> R.string.protection_mode_desc_smart_split
                ProtectionMode.STRICT -> R.string.protection_mode_desc_strict
                ProtectionMode.TURBO -> R.string.protection_mode_desc_turbo
                else -> R.string.protection_mode_desc_manual
            }
        ).replace(" · ", "\n")

        val isSmartSplit = mode == ProtectionMode.SMART_SPLIT
        val showScanCard = isSmartSplit || mode == ProtectionMode.MANUAL
        val dualNetEnabled = DataStore.effectiveDualNetEnabled

        binding.smartSplitRow.visibility = if (isSmartSplit) View.VISIBLE else View.GONE
        binding.nodeMetaText.visibility = if (isSmartSplit) View.GONE else View.VISIBLE

        binding.dualEngineCard.visibility = if (dualNetEnabled) View.VISIBLE else View.GONE
        if (dualNetEnabled) {
            startDualEngineTicker(connected)
        } else {
            dualEngineTickerJob?.cancel()
        }

        binding.smartScanCard.visibility = if (showScanCard) View.VISIBLE else View.GONE
        binding.protectionInfoCard.visibility = if (showScanCard) View.GONE else View.VISIBLE

        if (showScanCard) {
            val learningOn = DataStore.byedpiLearningMode
            val strategyArgs = DataStore.byedpiArgs.trim().ifEmpty { ByeDPI.DEFAULT_ARGS }
            binding.learningStatusText.text = if (DataStore.byedpiStrategyTested) {
                "${if (learningOn) "自学习已开启" else "自学习已关闭"} · 当前策略: $strategyArgs"
            } else {
                "请先点击下方按钮完成 ByeDPI 策略测试"
            }

            val goodDomains = ByeDPILearningStore.knownGoodDomains()
            val blockedDomains = ByeDPILearningStore.blockedDomains()
            val failCounts = ByeDPILearningStore.failCounts()
            val goodCount = goodDomains.size
            val blockedCount = blockedDomains.size
            val totalKnown = goodCount + blockedCount

            binding.learningCountChip.text = getString(R.string.home_learning_counts, goodCount, blockedCount)

            if (scanJob?.isActive != true) {
                binding.btnSmartScan.text = if (DataStore.byedpiStrategyTested) {
                    getString(R.string.home_smart_scan)
                } else {
                    "先测 ByeDPI 策略再扫描 CDN"
                }
                binding.learningSplitProgress.max = 100
                val ratio = if (totalKnown > 0) ((goodCount * 100f) / totalKnown).toInt() else 0
                binding.learningSplitProgress.setProgressCompat(ratio, true)
                binding.learningProgressCaption.text = when {
                    totalKnown > 0 -> "✓ ByeDPI 免流直连 $goodCount 个域名 ($ratio%) · 🛡️ 代理兜底 $blockedCount 个"
                    !DataStore.byedpiStrategyTested -> "尚未测试策略 · 点击下方按钮将先进入策略测试"
                    else -> "尚未扫描 · 点击下方按钮检测哪些常用网站可走 ByeDPI 直连"
                }
            }

            populateLearnedDomainChips(goodDomains, failCounts, blockedDomains)
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

    private fun populateLearnedDomainChips(
        goodDomains: List<String>,
        failCounts: Map<String, Int>,
        blockedDomains: List<String>,
    ) {
        val ctx = context ?: return
        val row = binding.learningDomainsRow
        row.removeAllViews()

        val items = mutableListOf<Pair<String, Int>>() // text to style (0=good, 1=warn, 2=blocked, 3=more)
        goodDomains.take(8).forEach { items.add("✓ $it" to 0) }
        if (goodDomains.size > 8) {
            items.add("+${goodDomains.size - 8} 个可直连" to 3)
        }
        failCounts.entries.take(3).forEach { (d, c) ->
            items.add("! $d ($c/${ByeDPILearningStore.MAX_FAILURES})" to 1)
        }
        blockedDomains.take(3).forEach { items.add("✕ $it → 代理" to 2) }

        if (items.isEmpty()) {
            binding.learningDomainsScroll.visibility = View.GONE
            return
        }
        binding.learningDomainsScroll.visibility = View.VISIBLE

        items.forEachIndexed { idx, (label, kind) ->
            val chip = Chip(ctx).apply {
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        ctx, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Assist
                    )
                )
                isCheckable = false
                setTextAppearanceResource(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                text = label.removePrefix("✓ ").removePrefix("✕ ")
                chipIcon = when (kind) {
                    0 -> ContextCompat.getDrawable(ctx, R.drawable.ic_home_check)
                    2 -> ContextCompat.getDrawable(ctx, R.drawable.ic_home_block)
                    else -> null
                }
                isChipIconVisible = chipIcon != null
                chipIconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        ctx,
                        if (kind == 2) com.google.android.material.R.attr.colorOnSurfaceVariant
                        else androidx.appcompat.R.attr.colorPrimary,
                        0,
                    )
                )
                setOnClickListener { showCdnLearningDialog() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (idx > 0) marginStart = dp2px(8)
            }
            row.addView(chip, lp)
        }
    }

    private fun startDualEngineTicker(connected: Boolean) {
        dualEngineTickerJob?.cancel()
        val mode = DataStore.effectiveProtectionMode
        val badgeText = when {
            mode == ProtectionMode.STRICT -> "双路拆包 + 填充"
            mode == ProtectionMode.TURBO -> "极速多设备分流"
            DataStore.effectiveDualNetStrategy == DualNet.Strategy.AUTO -> "智能权重"
            else -> "1:1 并发轮询"
        }
        binding.dualEngineBadge.text = badgeText

        if (!connected) {
            binding.dualEngineSub.text = "未连接 · 启动后自动分配 Wi-Fi 与蜂窝流量"
            showDualEngineShares(flowing = false, wifiShare = 0f, cellShare = 0f, wifiDetail = "待命", cellDetail = "待命")
            return
        }

        dualEngineTickerJob = runOnDefaultDispatcher {
            while (isActive) {
                val parsed = parseDualNetStatus()
                val lanInfo = parseLanPeerSummary()
                val subText = if (lanInfo.isNotEmpty()) "${parsed.summaryLine} · $lanInfo" else parsed.summaryLine
                onMainDispatcher {
                    if (_binding != null) {
                        binding.dualEngineSub.text = subText
                        showDualEngineShares(
                            flowing = true,
                            wifiShare = parsed.wifiShare,
                            cellShare = parsed.cellShare,
                            wifiDetail = parsed.wifiDetail,
                            cellDetail = parsed.cellDetail,
                        )
                    }
                }
                delay(2000L)
            }
        }
    }

    /** M3 linear progress per network (Wi-Fi = primary, cellular = tertiary). */
    private fun showDualEngineShares(
        flowing: Boolean,
        wifiShare: Float,
        cellShare: Float,
        wifiDetail: String,
        cellDetail: String,
    ) {
        val wifiPct = (wifiShare.coerceIn(0f, 1f) * 100).toInt()
        val cellPct = (cellShare.coerceIn(0f, 1f) * 100).toInt()
        binding.wifiShareProgress.setProgressCompat(if (flowing) wifiPct else 0, true)
        binding.cellShareProgress.setProgressCompat(if (flowing) cellPct else 0, true)
        binding.wifiShareValue.text = if (flowing) "$wifiPct%" else "—"
        binding.cellShareValue.text = if (flowing) "$cellPct%" else "—"
        binding.wifiShareDetail.text = wifiDetail
        binding.cellShareDetail.text = cellDetail
    }

    private fun parseLanPeerSummary(): String {
        val raw = runCatching {
            io.nekohasekai.sagernet.dualnet.LanPeerManager.statusFile.readText()
        }.getOrNull() ?: return ""
        val map = raw.split("|").mapNotNull {
            val idx = it.indexOf('=')
            if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
        }.toMap()
        val peers = map["peers"]?.toIntOrNull() ?: 0
        val sharing = map["sharing"] == "1"
        val usedMb = map["usedMb"]?.toLongOrNull() ?: 0L
        return buildList {
            if (peers > 0) add("局域网设备 $peers 台")
            if (sharing) add("已共享 ${usedMb}MB")
        }.joinToString(" · ")
    }

    private data class DualEngineUiState(
        val wifiShare: Float,
        val cellShare: Float,
        val wifiDetail: String,
        val cellDetail: String,
        val coreLabel: String,
        val summaryLine: String,
    )

    private fun parseDualNetStatus(): DualEngineUiState {
        val lines = runCatching { DualNetworkManager.statusFile.readLines() }.getOrNull()
        if (lines == null || lines.size < 2) {
            return DualEngineUiState(
                wifiShare = 0.5f,
                cellShare = 0.5f,
                wifiDetail = "探测中",
                cellDetail = "探测中",
                coreLabel = "动态分配",
                summaryLine = "双网引擎已就绪 · 正在采样实时链路质量",
            )
        }
        val isSplit = lines[0].trim() == "split"
        val statusLine = lines[1].trim()
        var wifiPct = 0f
        var cellPct = 0f
        var wifiDetail = "未就绪"
        var cellDetail = "未就绪"

        val parts = statusLine.split("|").map { it.trim() }
        for (part in parts) {
            if (part.startsWith("WiFi")) {
                wifiPct = Regex("""(\d+)%""").find(part)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)
                    ?: if (isSplit) 0.5f else 1f
                val rtt = Regex("""rtt=(\d+ms)""").find(part)?.groupValues?.get(1)
                val conns = Regex("""conns=(\d+)""").find(part)?.groupValues?.get(1)
                wifiDetail = listOfNotNull(rtt, conns?.let { "${it}连接" }).joinToString(" · ").ifEmpty { "在线" }
            } else if (part.startsWith("LTE")) {
                cellPct = Regex("""(\d+)%""").find(part)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)
                    ?: if (isSplit) 0.5f else 0f
                val rtt = Regex("""rtt=(\d+ms)""").find(part)?.groupValues?.get(1)
                val conns = Regex("""conns=(\d+)""").find(part)?.groupValues?.get(1)
                cellDetail = listOfNotNull(rtt, conns?.let { "${it}连接" }).joinToString(" · ").ifEmpty { "在线" }
            }
        }
        if (wifiPct == 0f && cellPct == 0f) {
            if (statusLine.contains("WiFi") && statusLine.contains("LTE")) {
                wifiPct = 0.5f
                cellPct = 0.5f
            } else if (statusLine.contains("WiFi")) {
                wifiPct = 1f
            } else if (statusLine.contains("LTE")) {
                cellPct = 1f
            }
        }
        val coreLabel = if (isSplit) "双路并发" else "单网直通"
        return DualEngineUiState(
            wifiShare = wifiPct,
            cellShare = cellPct,
            wifiDetail = wifiDetail,
            cellDetail = cellDetail,
            coreLabel = coreLabel,
            summaryLine = statusLine,
        )
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
                        val (phaseIdx, phaseLabel) = when (progress.phase) {
                            CdnDpiScanner.Phase.RESOLVING_DNS -> 1 to "1/3 DoH DNS 解析"
                            CdnDpiScanner.Phase.PROBING_IPS -> 2 to "2/3 TCP:443 探测"
                            CdnDpiScanner.Phase.VERIFYING_BYEDPI -> 3 to "3/3 ByeDPI TLS 验证"
                        }
                        val total = progress.total.coerceAtLeast(1)
                        val pct = (((phaseIdx - 1) * 100 + (progress.done * 100 / total)) / 3).coerceIn(0, 100)
                        binding.learningSplitProgress.max = 100
                        binding.learningSplitProgress.setProgressCompat(pct, true)
                        binding.learningProgressCaption.text = "正在执行 $phaseLabel (${progress.done}/${progress.total})..."
                        binding.btnSmartScan.text = "$phaseLabel (${progress.done}/${progress.total})"
                    }
                }
            }
            onMainDispatcher {
                if (_binding != null && isAdded) {
                    binding.btnSmartScan.isEnabled = true
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
            val proxyBean = runCatching {
                SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.requireBean()
            }.getOrNull()
            val isPureByedpiNode = proxyBean is SOCKSBean && proxyBean.serverAddress == ByeDPI.PROFILE_MARKER_ADDRESS
            val isDualPath = DataStore.effectiveByedpiEnabled && !isPureByedpiNode

            if (isDualPath) {
                // Both ByeDPI and Proxy are active: fetch both Proxy Exit IP and Direct/DPI Exit IP concurrently
                val (proxyInfo, directInfo) = coroutineScope {
                    val pDeferred = async { fetchProxyExitIp() }
                    val dDeferred = async { fetchDirectExitIp() }
                    pDeferred.await() to dDeferred.await()
                }
                onMainDispatcher {
                    if (_binding != null) {
                        binding.exitCardTitle.text = "双路出口 (代理 + DPI)"
                        binding.exitCountryText.text = if (proxyInfo != null) {
                            "代理 ${proxyInfo.shortFlagAndCode} · ${proxyInfo.ip}"
                        } else {
                            "代理 检测失败"
                        }
                        binding.exitIpText.text = if (directInfo != null) {
                            "DPI  ${directInfo.shortFlagAndCode} · ${directInfo.ip}"
                        } else {
                            "DPI  检测失败"
                        }
                    }
                }
            } else {
                val info = if (isPureByedpiNode) fetchDirectExitIp() else fetchProxyExitIp()
                onMainDispatcher {
                    if (_binding != null) {
                        binding.exitCardTitle.text = getString(R.string.home_exit_ip_label)
                        if (info != null) {
                            binding.exitCountryText.text = info.fullCountryLine
                            binding.exitIpText.text = info.ip
                        } else {
                            binding.exitCountryText.text = getString(R.string.home_exit_unknown)
                            binding.exitIpText.text = getString(R.string.home_exit_tap_to_refresh)
                        }
                    }
                }
            }
        }
    }

    private data class ExitIpInfo(
        val ip: String,
        val countryCode: String,
        val countryName: String,
    ) {
        val flag: String
            get() {
                val code = countryCode.uppercase()
                if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
                val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
                val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
                return String(Character.toChars(first)) + String(Character.toChars(second))
            }

        val shortFlagAndCode: String
            get() = "$flag ${countryCode.ifBlank { "?" }}".trim()

        val fullCountryLine: String
            get() = "$flag ${countryName.ifBlank { countryCode }}".trim().ifEmpty { ip }
    }

    /**
     * Queries the Proxy Node's true exit IP by dialing 127.0.0.1:[DataStore.socksPort] with ATYP=0x03
     * domain name ("ipinfo.io" / "ipwho.is"), which ConfigBuilder explicitly routes to TAG_AGENT.
     */
    private fun fetchProxyExitIp(): ExitIpInfo? {
        val socksPort = DataStore.socksPort
        val endpoints = listOf(
            "ipinfo.io" to "/json",
            "ipwho.is" to "/",
        )
        for ((host, path) in endpoints) {
            var raw: Socket? = null
            try {
                raw = CdnDpiScanner.dialSocks5Domain(socksPort, host, 443, 6000)
                val body = httpsGetOverSocket(raw, host, path, 6000) ?: continue
                parseExitIpJson(body)?.let { return it }
            } catch (_: Exception) {
            } finally {
                runCatching { raw?.close() }
            }
        }
        return null
    }

    /**
     * Queries the Direct / ByeDPI exit IP over the physical network (bypassing the proxy outbound).
     */
    private suspend fun fetchDirectExitIp(): ExitIpInfo? {
        val vpnIncludesSelf = SagerNet.started &&
            DataStore.serviceMode == Key.MODE_VPN &&
            DataStore.tunImplementation == TunImplementation.SYSTEM
        val listenerKey = Any()
        val underlying = if (vpnIncludesSelf) {
            DefaultNetworkListener.start(listenerKey) {}
            withTimeoutOrNull(2500L) { runCatching { DefaultNetworkListener.get() }.getOrNull() }
        } else null
        try {
            val endpoints = listOf(
                Triple("api.ip.sb", "104.26.12.31", "/geoip"),
                Triple("1.1.1.1", "1.1.1.1", "/cdn-cgi/trace"),
            )
            for ((host, ip, path) in endpoints) {
                var raw: Socket? = null
                try {
                    raw = Socket()
                    underlying?.bindSocket(raw)
                    raw.soTimeout = 5000
                    raw.connect(InetSocketAddress(ip, 443), 5000)
                    val body = httpsGetOverSocket(raw, host, path, 5000) ?: continue
                    parseExitIpJson(body)?.let { return it }
                } catch (_: Exception) {
                } finally {
                    runCatching { raw?.close() }
                }
            }
            return null
        } finally {
            if (vpnIncludesSelf) DefaultNetworkListener.stop(listenerKey)
        }
    }

    private fun httpsGetOverSocket(raw: Socket, host: String, path: String, timeoutMs: Int): String? {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, 443, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !host.all { it.isDigit() || it == '.' }) {
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        }
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            return null
        }
        ssl.getOutputStream().apply {
            write(
                ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: curl/8.5.0\r\n" +
                    "Accept: application/json, text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            flush()
        }
        val sink = ByteArrayOutputStream()
        val code = ByeDPIProbe.readResponse(ssl.getInputStream(), sink = sink) ?: return null
        return if (code == 200) sink.toString("UTF-8") else null
    }

    private fun parseExitIpJson(raw: String): ExitIpInfo? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            // Cloudflare /cdn-cgi/trace key=value format
            val map = trimmed.lines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
            }.toMap()
            val ip = map["ip"]?.takeIf { it.isNotBlank() } ?: return null
            val cc = map["loc"].orEmpty().uppercase()
            return ExitIpInfo(ip = ip, countryCode = cc, countryName = cc)
        }
        return runCatching {
            val json = JSONObject(trimmed)
            val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
            val code = json.optString("country_code")
                .ifBlank { json.optString("country") }
                .take(2)
                .uppercase()
            val country = json.optString("country_name")
                .ifBlank { json.optString("country") }
            ExitIpInfo(ip = ip, countryCode = code, countryName = country)
        }.getOrNull()
    }

    override fun onDestroyView() {
        exitIpJob?.cancel()
        dualEngineTickerJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
