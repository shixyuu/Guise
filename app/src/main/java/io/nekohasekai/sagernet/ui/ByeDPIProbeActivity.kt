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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.byedpi.ByeDPI
import io.nekohasekai.sagernet.byedpi.ByeDPIProbe
import io.nekohasekai.sagernet.byedpi.ByeDPIProbeRunner
import io.nekohasekai.sagernet.byedpi.ByeDPIProbeTargets
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutByedpiProbeBinding
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Guise: ByeDPI connectivity test. Tries a list of byedpi strategies against a few sites from
 * the phone's current network and ranks them. Flow and default strategy list follow
 * romanvht/ByeByeDPI (GPL-3.0) TestActivity/TestService.
 */
class ByeDPIProbeActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: LayoutByedpiProbeBinding
    private val adapter = ResultAdapter()
    private val connection = SagerConnection()
    private var job: Job? = null
    private var rows: List<ByeDPIProbe.Result> = emptyList()
    private var refreshPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutByedpiProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.results) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.byedpi_probe)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.targets.setOnClickListener { showTargets() }
        binding.settings.setOnClickListener { showSettings() }
        binding.strategies.setOnClickListener { showStrategies() }
        binding.start.setOnClickListener { if (job?.isActive == true) stop() else start() }
        connection.connect(this, this)
        updateSummary()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        job?.cancel()
        super.onDestroy()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        SagerNet.started = state.canStop
    }

    override fun onServiceConnected(service: ISagerNetService) {
        SagerNet.started = try {
            BaseService.State.entries[service.state].canStop
        } catch (_: Exception) {
            false
        }
    }

    private fun loadStrategyText(): String {
        return DataStore.byedpiProbeStrategies.ifBlank {
            assets.open(ByeDPIProbe.STRATEGIES_ASSET).bufferedReader().use { it.readText() }
        }
    }

    private fun updateSummary() {
        val targets = selectedTargets()
        val strategies = ByeDPIProbe.parseStrategies(loadStrategyText(), DataStore.byedpiProbeSni)
        binding.summary.text = getString(
            R.string.byedpi_probe_summary,
            targets.joinToString(", ") { it.label }.ifEmpty { "—" },
            strategies.size,
            DataStore.byedpiProbeRequests,
            DataStore.byedpiProbeTimeout,
            DataStore.byedpiProbeConcurrency,
        )
    }

    private fun setRunning(running: Boolean) {
        binding.start.setText(if (running) R.string.stop else R.string.start)
        binding.settings.isEnabled = !running
        binding.targets.isEnabled = !running
        binding.strategies.isEnabled = !running
        binding.progress.isVisible = running || rows.isNotEmpty()
        binding.progressText.isVisible = running || rows.isNotEmpty()
    }

    private fun start() {
        val targets = selectedTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.byedpi_probe_no_sites, Toast.LENGTH_SHORT).show()
            return
        }
        val strategies = ByeDPIProbe.parseStrategies(loadStrategyText(), DataStore.byedpiProbeSni)
        val plan = ByeDPIProbe.buildPlan(DataStore.byedpiArgs, strategies)
        rows = plan
        adapter.submit(ByeDPIProbe.sorted(plan))
        val runner = ByeDPIProbeRunner(
            targets,
            DataStore.byedpiProbeRequests.coerceIn(1, 20),
            DataStore.byedpiProbeTimeout.coerceIn(1, 60),
            DataStore.byedpiProbeConcurrency.coerceIn(1, ByeDPIProbe.MAX_CONCURRENCY),
            DataStore.byedpiProbeDoh,
        ) { scheduleRefresh() }
        binding.dns.isVisible = true
        binding.dns.setText(R.string.byedpi_probe_dns_resolving)
        binding.progress.max = plan.size
        binding.progress.progress = 0
        setRunning(true)
        refresh()
        job = lifecycleScope.launch {
            try {
                runner.run(plan) { resolution ->
                    runOnUiThread { showResolution(resolution) }
                }
            } finally {
                setRunning(false)
                refresh()
            }
        }
    }

    private fun stop() {
        job?.cancel()
    }

    /** Throttle UI refreshes coming from worker threads. */
    private fun scheduleRefresh() {
        synchronized(this) {
            if (refreshPending) return
            refreshPending = true
        }
        binding.root.postDelayed({
            synchronized(this) { refreshPending = false }
            refresh()
        }, 250)
    }

    private fun refresh() {
        val current = rows
        val finished = current.count { it.finished }
        binding.progress.progress = finished
        val running = job?.isActive == true
        binding.progressText.text = when {
            running -> getString(R.string.byedpi_probe_progress, finished, current.size)
            finished < current.size -> getString(R.string.byedpi_probe_cancelled, finished, current.size)
            else -> getString(R.string.byedpi_probe_done, current.size)
        }
        adapter.submit(ByeDPIProbe.sorted(current))
    }

    private fun selectedTargets(): List<ByeDPIProbeTargets.Target> =
        ByeDPIProbeTargets.selected(DataStore.byedpiProbeTargets, DataStore.byedpiProbeCustom)

    private fun showResolution(resolution: ByeDPIProbeRunner.Resolution) {
        val source = getString(
            if (resolution.viaDoh) R.string.byedpi_probe_dns_doh else R.string.byedpi_probe_dns_system
        )
        binding.dns.text = if (resolution.failed.isEmpty()) {
            getString(R.string.byedpi_probe_dns_ok, source, resolution.addresses.size)
        } else {
            getString(
                R.string.byedpi_probe_dns_failed, source, resolution.addresses.size,
                resolution.failed.joinToString(", ")
            )
        }
    }

    private fun tagText(tag: ByeDPIProbeTargets.Tag): String = getString(
        when (tag) {
            ByeDPIProbeTargets.Tag.SNI -> R.string.byedpi_probe_tag_sni
            ByeDPIProbeTargets.Tag.UNCERTAIN -> R.string.byedpi_probe_tag_uncertain
            ByeDPIProbeTargets.Tag.IP_BLOCKED -> R.string.byedpi_probe_tag_ip_blocked
            ByeDPIProbeTargets.Tag.CONTROL -> R.string.byedpi_probe_tag_control
            ByeDPIProbeTargets.Tag.CUSTOM -> R.string.byedpi_probe_tag_custom
        }
    )

    private fun showTargets() {
        val all = ByeDPIProbeTargets.all(DataStore.byedpiProbeCustom)
        val selected = ByeDPIProbeTargets.parseSelection(DataStore.byedpiProbeTargets).toMutableSet()
        val checked = BooleanArray(all.size) { all[it].id in selected }
        val secondary = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).run {
            val color = getColor(0, 0x88888888.toInt()); recycle(); color
        }
        val labels = all.map { target ->
            SpannableStringBuilder(target.label).apply {
                val start = length
                append("\n").append(tagText(target.tag)).append(" · ")
                    .append(target.hosts.joinToString(", "))
                setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(secondary), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_targets)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = all.indices.filter { checked[it] }.map { all[it].id }
                if (ids.isEmpty()) {
                    Toast.makeText(this, R.string.byedpi_probe_no_sites, Toast.LENGTH_SHORT).show()
                } else {
                    DataStore.byedpiProbeTargets = ByeDPIProbeTargets.encodeSelection(ids)
                }
                updateSummary()
            }
            .setNeutralButton(R.string.byedpi_probe_custom) { _, _ ->
                // keep the ticks made so far, then edit the custom list
                val ids = all.indices.filter { checked[it] }.map { all[it].id }
                if (ids.isNotEmpty()) DataStore.byedpiProbeTargets = ByeDPIProbeTargets.encodeSelection(ids)
                showCustomTargets()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomTargets() {
        val view = layoutInflater.inflate(R.layout.layout_byedpi_probe_strategies, null)
        val text = view.findViewById<EditText>(R.id.strategies)
        text.setText(DataStore.byedpiProbeCustom)
        text.setHint(R.string.byedpi_probe_custom_hint_example)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_custom)
            .setMessage(R.string.byedpi_probe_custom_hint)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val before = ByeDPIProbeTargets.parseCustom(DataStore.byedpiProbeCustom).map { it.id }.toSet()
                DataStore.byedpiProbeCustom = text.text.toString().trim()
                val custom = ByeDPIProbeTargets.parseCustom(DataStore.byedpiProbeCustom)
                // newly added entries start ticked; removed ones drop out of the selection
                val valid = ByeDPIProbeTargets.all(DataStore.byedpiProbeCustom).map { it.id }.toSet()
                val ids = ByeDPIProbeTargets.parseSelection(DataStore.byedpiProbeTargets)
                    .filter { it in valid }.toMutableList()
                custom.filter { it.id !in before && it.id !in ids }.forEach { ids.add(it.id) }
                if (ids.isNotEmpty()) DataStore.byedpiProbeTargets = ByeDPIProbeTargets.encodeSelection(ids)
                updateSummary()
                showTargets()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showTargets() }
            .show()
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.layout_byedpi_probe_settings, null)
        val requests = view.findViewById<EditText>(R.id.requests)
        val timeout = view.findViewById<EditText>(R.id.timeout)
        val concurrency = view.findViewById<EditText>(R.id.concurrency)
        val sni = view.findViewById<EditText>(R.id.sni)
        val doh = view.findViewById<CheckBox>(R.id.doh)
        doh.isChecked = DataStore.byedpiProbeDoh
        requests.setText(DataStore.byedpiProbeRequests.toString())
        timeout.setText(DataStore.byedpiProbeTimeout.toString())
        concurrency.setText(DataStore.byedpiProbeConcurrency.toString())
        sni.setText(DataStore.byedpiProbeSni)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.byedpiProbeDoh = doh.isChecked
                DataStore.byedpiProbeRequests = requests.text.toString().toIntOrNull()
                    ?.coerceIn(1, 20) ?: ByeDPIProbe.DEFAULT_REQUESTS
                DataStore.byedpiProbeTimeout = timeout.text.toString().toIntOrNull()
                    ?.coerceIn(1, 60) ?: ByeDPIProbe.DEFAULT_TIMEOUT_SECONDS
                DataStore.byedpiProbeConcurrency = concurrency.text.toString().toIntOrNull()
                    ?.coerceIn(1, ByeDPIProbe.MAX_CONCURRENCY) ?: ByeDPIProbe.DEFAULT_CONCURRENCY
                DataStore.byedpiProbeSni = sni.text.toString().trim()
                    .ifBlank { ByeDPIProbe.DEFAULT_SNI }
                updateSummary()
            }
            .setNeutralButton(R.string.byedpi_probe_reset) { _, _ ->
                DataStore.byedpiProbeDoh = true
                DataStore.byedpiProbeRequests = ByeDPIProbe.DEFAULT_REQUESTS
                DataStore.byedpiProbeTimeout = ByeDPIProbe.DEFAULT_TIMEOUT_SECONDS
                DataStore.byedpiProbeConcurrency = ByeDPIProbe.DEFAULT_CONCURRENCY
                DataStore.byedpiProbeSni = ByeDPIProbe.DEFAULT_SNI
                updateSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStrategies() {
        val view = layoutInflater.inflate(R.layout.layout_byedpi_probe_strategies, null)
        val text = view.findViewById<EditText>(R.id.strategies)
        text.setText(loadStrategyText())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_strategies)
            .setMessage(R.string.byedpi_probe_strategies_hint)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.byedpiProbeStrategies = text.text.toString()
                updateSummary()
            }
            .setNeutralButton(R.string.byedpi_probe_reset) { _, _ ->
                DataStore.byedpiProbeStrategies = ""
                updateSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isByeDPIActive(): Boolean {
        if (DataStore.effectiveByedpiEnabled) return true
        val bean = runCatching {
            SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.requireBean()
        }.getOrNull()
        return bean is SOCKSBean && bean.serverAddress == ByeDPI.PROFILE_MARKER_ADDRESS
    }

    private fun onResultClicked(row: ByeDPIProbe.Result) {
        val args = row.args ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_apply_title)
            .setMessage(
                getString(R.string.byedpi_probe_apply_message, args) +
                        if (!isByeDPIActive()) "\n\n" + getString(R.string.byedpi_probe_apply_disabled_hint) else ""
            )
            .setPositiveButton(R.string.byedpi_probe_apply) { _, _ ->
                DataStore.byedpiArgs = args
                if (SagerNet.started) SagerNet.reloadService()
                Toast.makeText(this, R.string.byedpi_probe_applied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.byedpi_probe_copy) { _, _ ->
                if (SagerNet.trySetPrimaryClip(args)) {
                    Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusText(row: ByeDPIProbe.Result): String {
        val parts = mutableListOf<String>()
        when (row.kind) {
            ByeDPIProbe.Kind.BASELINE -> parts.add(getString(R.string.byedpi_probe_baseline_tag))
            ByeDPIProbe.Kind.CURRENT -> parts.add(getString(R.string.byedpi_probe_current_tag))
            else -> {}
        }
        if (row.done == 0 && !row.finished) {
            parts.add(getString(R.string.byedpi_probe_pending))
        } else {
            parts.add(
                getString(
                    R.string.byedpi_probe_result,
                    row.success, row.total, (row.successRate * 100).toInt()
                )
            )
            row.medianLatency?.let { parts.add(getString(R.string.byedpi_probe_latency, it)) }
            if (!row.finished) parts.add(getString(R.string.byedpi_probe_running))
        }
        row.error?.let { parts.add(it) }
        val perTarget = synchronized(row.perTarget) { row.perTarget.entries.map { it.key to it.value.copyOf() } }
        val detail = perTarget.joinToString("  ") { (label, c) -> "$label ${c[0]}/${c[1]}" }
        return parts.joinToString(" · ") + if (detail.isNotEmpty()) "\n" + detail else ""
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultHolder>() {
        private var items: List<ByeDPIProbe.Result> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<ByeDPIProbe.Result>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            return ResultHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.layout_byedpi_probe_item, parent, false)
            )
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class ResultHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val status: TextView = view.findViewById(R.id.status)

        fun bind(row: ByeDPIProbe.Result) {
            title.text = row.args ?: getString(R.string.byedpi_probe_baseline)
            status.text = statusText(row)
            if (row.args != null) {
                itemView.setOnClickListener { onResultClicked(row) }
            } else {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            }
        }
    }
}
