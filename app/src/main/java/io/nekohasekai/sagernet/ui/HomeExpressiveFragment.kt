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
import io.nekohasekai.sagernet.byedpi.SmartDataSaver
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
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.heroButton.setOnClickListener {
            it.animate().scaleX(0.91f).scaleY(0.91f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
                    .setDuration(260)
                    .start()
            }.start()
            (activity as? MainActivity)?.toggleService()
        }

        binding.modePill.setOnClickListener {
            showProtectionModeDialog()
        }

        binding.exitIpCard.setOnClickListener {
            val state = (activity as? MainActivity)?.state ?: BaseService.State.Idle
            if (state == BaseService.State.Connected) {
                refreshExitIp()
            }
        }

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

        binding.statusDisplay.text = when {
            connected -> getString(R.string.home_status_connected)
            connecting -> getString(R.string.connecting)
            else -> getString(R.string.home_status_disconnected)
        }
        binding.statusDisplay.setTextColor(
            MaterialColors.getColor(
                ctx,
                if (connected) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface,
                0,
            )
        )

        binding.heroActionLabel.text = when {
            state.canStop -> getString(R.string.home_tap_stop)
            else -> getString(R.string.home_tap_start)
        }

        // Expressive shape morph: circle (78dp) when idle -> rounded squircle (36dp) when connected
        val targetRadius = dp2px(if (connected || connecting) 36 else 78).toFloat()
        if (binding.heroButton.radius != targetRadius) {
            ValueAnimator.ofFloat(binding.heroButton.radius, targetRadius).apply {
                duration = 320
                interpolator = android.view.animation.OvershootInterpolator(1.2f)
                addUpdateListener { binding.heroButton.radius = it.animatedValue as Float }
                start()
            }
        }

        val bgColor = MaterialColors.getColor(
            ctx,
            if (connected) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorPrimaryContainer,
            0,
        )
        val fgColor = MaterialColors.getColor(
            ctx,
            if (connected) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnPrimaryContainer,
            0,
        )
        binding.heroButton.setCardBackgroundColor(bgColor)
        binding.heroIcon.imageTintList = ColorStateList.valueOf(fgColor)
        binding.heroActionLabel.setTextColor(fgColor)

        updateOrbitAnimation(connected, connecting)
        updateModeAndSmartSplit()

        if (connected) {
            refreshExitIp()
        } else {
            exitIpJob?.cancel()
            binding.exitIpText.text = getString(R.string.home_exit_unknown)
        }
    }

    private fun updateOrbitAnimation(connected: Boolean, connecting: Boolean) {
        if (connected || connecting) {
            binding.heroOrbit.animate().alpha(if (connected) 0.9f else 0.6f).setDuration(250).start()
            val durationMs = if (connecting) 2200L else 6000L
            if (orbitAnimator == null || orbitAnimator?.duration != durationMs) {
                orbitAnimator?.cancel()
                orbitAnimator = ObjectAnimator.ofFloat(binding.heroOrbit, View.ROTATION, binding.heroOrbit.rotation, binding.heroOrbit.rotation + 360f).apply {
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
            binding.heroOrbit.animate().alpha(0.22f).setDuration(250).start()
        }
    }

    private fun updateModeAndSmartSplit() {
        val mode = DataStore.protectionMode
        binding.modeLabel.text = when (mode) {
            ProtectionMode.SMART_SPLIT -> getString(R.string.protection_mode_smart_split)
            ProtectionMode.STANDARD -> getString(R.string.protection_mode_standard)
            ProtectionMode.BALANCED -> getString(R.string.protection_mode_balanced)
            ProtectionMode.STRICT -> getString(R.string.protection_mode_strict)
            else -> getString(R.string.protection_mode_manual)
        }

        val isSmartSplit = mode == ProtectionMode.SMART_SPLIT
        binding.smartSplitRow.visibility = if (isSmartSplit) View.VISIBLE else View.GONE
        binding.btnSmartScan.visibility = if (isSmartSplit) View.VISIBLE else View.GONE
        if (isSmartSplit) {
            runOnDefaultDispatcher {
                val proxyName = runCatching {
                    SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.displayName()
                }.getOrNull() ?: getString(R.string.profile_empty)
                onMainDispatcher {
                    if (_binding != null) {
                        binding.pillProxyName.text = proxyName
                    }
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
                    updateModeAndSmartSplit()
                    if (SagerNet.started) {
                        SagerNet.reloadService()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSmartScanPicker() {
        val services = SmartDataSaver.SERVICES
        val labels = services.map { it.label }.toTypedArray()
        val checked = BooleanArray(services.size) { services[it].defaultSelected }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_smart_scan)
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

    private fun runSmartSplitScan(domains: List<String> = SmartDataSaver.candidateDomains()) {
        if (scanJob?.isActive == true) return
        binding.btnSmartScan.isEnabled = false
        scanJob = runOnDefaultDispatcher {
            val result = SmartDataSaver.scanAndSave(domains) { progress ->
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.btnSmartScan.text = getString(
                            R.string.home_smart_scanning,
                            progress.done,
                            progress.total,
                        )
                    }
                }
            }
            onMainDispatcher {
                if (_binding != null) {
                    binding.btnSmartScan.isEnabled = true
                    binding.btnSmartScan.text = getString(R.string.home_smart_scan)
                    (activity as? MainActivity)?.snackbar(
                        getString(
                            R.string.home_smart_scan_done,
                            result.dpiDomains.size,
                            result.ipBlockedDomains.size,
                        )
                    )?.show()
                    if (SagerNet.started) {
                        SagerNet.reloadService()
                    }
                }
            }
        }
    }

    private fun refreshExitIp() {
        exitIpJob?.cancel()
        binding.exitIpText.text = getString(R.string.home_exit_detecting)
        exitIpJob = runOnDefaultDispatcher {
            val info = fetchExitIp()
            onMainDispatcher {
                if (_binding != null) {
                    binding.exitIpText.text = info ?: getString(R.string.home_exit_unknown)
                }
            }
        }
    }

    private fun fetchExitIp(): String? {
        val proxy = if (DataStore.serviceMode != "vpn") {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.httpPort))
        } else null
        return runCatching {
            val url = URL("https://api.ip.sb/geoip")
            val conn = (proxy?.let { url.openConnection(it) } ?: url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Guise/1.0")
            try {
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
                val code = json.optString("country_code").uppercase()
                val country = json.optString("country")
                val flag = countryCodeToFlag(code)
                if (flag.isNotEmpty() || country.isNotBlank()) {
                    "$flag ${country.ifBlank { code }} · $ip".trim()
                } else {
                    ip
                }
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
