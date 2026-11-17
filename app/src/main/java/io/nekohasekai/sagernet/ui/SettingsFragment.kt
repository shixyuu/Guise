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

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import io.nekohasekai.sagernet.R

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private val subScreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.settings)

        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment())
                .commitAllowingStateLoss()
        }

        childFragmentManager.addOnBackStackChangedListener {
            val count = childFragmentManager.backStackEntryCount
            subScreenBackCallback.isEnabled = count > 0
            if (count == 0) {
                toolbar.setTitle(R.string.settings)
            } else {
                val top = childFragmentManager.getBackStackEntryAt(count - 1)
                toolbar.title = top.breadCrumbTitle ?: getString(R.string.settings)
            }
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, subScreenBackCallback)
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        val fragment = SettingsPreferenceFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .setBreadCrumbTitle(pref.title)
            .addToBackStack(pref.key)
            .commit()
        return true
    }

}