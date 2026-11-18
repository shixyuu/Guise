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
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.SheetExpressiveAddRuleBinding
import io.nekohasekai.sagernet.ktx.dp2px

class ExpressiveAddRuleSheet : BottomSheetDialogFragment() {

    private var _binding: SheetExpressiveAddRuleBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetExpressiveAddRuleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applySegmentedCorners(binding.cardNewRule, topOuter = true, bottomOuter = false)
        applySegmentedCorners(binding.cardImportRules, topOuter = false, bottomOuter = true)

        binding.cardNewRule.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), RouteSettingsActivity::class.java))
        }

        binding.cardImportRules.setOnClickListener {
            dismiss()
            (parentFragment as? RouteFragment)?.importFromClipboard()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
