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
import io.nekohasekai.sagernet.byedpi.ByeDPIProbe
import io.nekohasekai.sagernet.byedpi.ByeDPIProbeRunner
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutByedpiProbeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Guise: ByeDPI connectivity test. Tries a list of byedpi strategies against a few sites from
 * the phone's current network and ranks them. Flow and default strategy list follow
 * romanvht/ByeByeDPI (GPL-3.0) TestActivity/TestService.
 */
class ByeDPIProbeActivity : ThemedActivity() {

    private lateinit var binding: LayoutByedpiProbeBinding
    private val adapter = ResultAdapter()
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
        binding.settings.setOnClickListener { showSettings() }
        binding.strategies.setOnClickListener { showStrategies() }
        binding.start.setOnClickListener { if (job?.isActive == true) stop() else start() }
        updateSummary()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun loadStrategyText(): String {
        return DataStore.byedpiProbeStrategies.ifBlank {
            assets.open(ByeDPIProbe.STRATEGIES_ASSET).bufferedReader().use { it.readText() }
        }
    }

    private fun updateSummary() {
        val sites = ByeDPIProbe.parseSites(DataStore.byedpiProbeSites)
        val strategies = ByeDPIProbe.parseStrategies(loadStrategyText(), DataStore.byedpiProbeSni)
        binding.summary.text = getString(
            R.string.byedpi_probe_summary,
            sites.joinToString(", ") { it.removePrefix("https://") },
            strategies.size,
            DataStore.byedpiProbeRequests,
            DataStore.byedpiProbeTimeout,
            DataStore.byedpiProbeConcurrency,
        )
    }

    private fun setRunning(running: Boolean) {
        binding.start.setText(if (running) R.string.stop else R.string.start)
        binding.settings.isEnabled = !running
        binding.strategies.isEnabled = !running
        binding.progress.isVisible = running || rows.isNotEmpty()
        binding.progressText.isVisible = running || rows.isNotEmpty()
    }

    private fun start() {
        val sites = ByeDPIProbe.parseSites(DataStore.byedpiProbeSites)
        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.byedpi_probe_no_sites, Toast.LENGTH_SHORT).show()
            return
        }
        val strategies = ByeDPIProbe.parseStrategies(loadStrategyText(), DataStore.byedpiProbeSni)
        val plan = ByeDPIProbe.buildPlan(DataStore.byedpiArgs, strategies)
        rows = plan
        adapter.submit(ByeDPIProbe.sorted(plan))
        val runner = ByeDPIProbeRunner(
            sites,
            DataStore.byedpiProbeRequests.coerceIn(1, 20),
            DataStore.byedpiProbeTimeout.coerceIn(1, 60),
            DataStore.byedpiProbeConcurrency.coerceIn(1, ByeDPIProbe.MAX_CONCURRENCY),
        ) { scheduleRefresh() }
        binding.progress.max = plan.size
        binding.progress.progress = 0
        setRunning(true)
        refresh()
        job = lifecycleScope.launch {
            try {
                runner.run(plan)
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

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.layout_byedpi_probe_settings, null)
        val sites = view.findViewById<EditText>(R.id.sites)
        val requests = view.findViewById<EditText>(R.id.requests)
        val timeout = view.findViewById<EditText>(R.id.timeout)
        val concurrency = view.findViewById<EditText>(R.id.concurrency)
        val sni = view.findViewById<EditText>(R.id.sni)
        sites.setText(DataStore.byedpiProbeSites)
        requests.setText(DataStore.byedpiProbeRequests.toString())
        timeout.setText(DataStore.byedpiProbeTimeout.toString())
        concurrency.setText(DataStore.byedpiProbeConcurrency.toString())
        sni.setText(DataStore.byedpiProbeSni)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.byedpiProbeSites = sites.text.toString().trim()
                    .ifBlank { ByeDPIProbe.DEFAULT_SITES }
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
                DataStore.byedpiProbeSites = ByeDPIProbe.DEFAULT_SITES
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

    private fun onResultClicked(row: ByeDPIProbe.Result) {
        val args = row.args ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.byedpi_probe_apply_title)
            .setMessage(
                getString(R.string.byedpi_probe_apply_message, args) +
                        if (!DataStore.byedpiEnabled) "\n\n" + getString(R.string.byedpi_probe_apply_disabled_hint) else ""
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
        return parts.joinToString(" · ")
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
