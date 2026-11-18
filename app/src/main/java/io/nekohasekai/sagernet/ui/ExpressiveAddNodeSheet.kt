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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.ProtectionMode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.byedpi.ByeDPI
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.SheetExpressiveAddNodeBinding
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.*

class ExpressiveAddNodeSheet : BottomSheetDialogFragment() {

    private var _binding: SheetExpressiveAddNodeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetExpressiveAddNodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applySegmentedCorners(binding.cardClipboard, topOuter = true, bottomOuter = false)
        applySegmentedCorners(binding.cardQr, topOuter = false, bottomOuter = false)
        applySegmentedCorners(binding.cardManual, topOuter = false, bottomOuter = true)

        val host = parentFragment as? ConfigurationFragment

        binding.cardClipboard.setOnClickListener {
            dismiss()
            host?.importFromClipboard()
        }

        binding.cardQr.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), ScannerActivity::class.java))
        }

        binding.cardManual.setOnClickListener {
            dismiss()
            showProtocolPicker()
        }
    }

    private fun applySegmentedCorners(card: MaterialCardView, topOuter: Boolean, bottomOuter: Boolean) {
        val outer = dp2px(24).toFloat()
        val inner = dp2px(6).toFloat()
        val top = if (topOuter) outer else inner
        val bottom = if (bottomOuter) outer else inner
        card.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top)
            .setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom)
            .setBottomRightCornerSize(bottom)
            .build()
    }

    private fun showProtocolPicker() {
        val protocols = listOf(
            R.string.action_vless to VLESSSettingsActivity::class.java,
            R.string.action_vmess to VMessSettingsActivity::class.java,
            R.string.action_trojan to TrojanSettingsActivity::class.java,
            R.string.action_shadowsocks to ShadowsocksSettingsActivity::class.java,
            R.string.action_hysteria2 to Hysteria2SettingsActivity::class.java,
            R.string.action_anytls to AnyTLSSettingsActivity::class.java,
            R.string.action_tuic5 to Tuic5SettingsActivity::class.java,
            R.string.action_naive to NaiveSettingsActivity::class.java,
            R.string.action_wireguard to WireGuardSettingsActivity::class.java,
            R.string.action_socks to SocksSettingsActivity::class.java,
            R.string.action_http to HttpSettingsActivity::class.java,
            R.string.custom_config to ConfigSettingsActivity::class.java,
        )
        val labels = protocols.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.nodes_add_manual)
            .setItems(labels) { _, which ->
                startActivity(Intent(requireContext(), protocols[which].second))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
