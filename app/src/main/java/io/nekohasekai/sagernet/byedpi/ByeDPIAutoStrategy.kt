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

import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guise: automatic ByeDPI strategy selection while the service runs (in the :bg process).
 *
 * - When there is no pool yet, it is degraded, or it is older than a day: run the whole strategy
 *   list (plus the current args) against the selected test targets and keep the best 12 as the pool.
 * - Every [intervalMinutes]: re-test the pool, apply the best.
 * - Every 5 minutes in between: quick health check of the current strategy; on failure, re-test
 *   the pool and switch to the best working entry right away (or run the full list).
 * Tests use the connectivity-test runner (throw-away byedpi processes, protected sockets, DoH).
 * Applying a strategy stores it as the ByeDPI arguments and restarts the running byedpi
 * ([restart]); connections open through the old process are dropped.
 */
class ByeDPIAutoStrategy(
    private val intervalMinutes: Int,
    private val restart: suspend (String) -> Unit,
) {

    companion object {
        const val TAG = "Guise"

        object State {
            const val IDLE = 0
            const val FULL_TEST = 1
            const val POOL_TEST = 2
            const val HEALTH_CHECK = 3
            const val NO_TARGETS = 4
            const val FAILED = 5
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "byedpi-auto: stopped: ${e.message}", e)
                DataStore.byedpiAutoState = State.FAILED
            }
        }
    }

    fun stop() {
        scope.cancel()
        DataStore.byedpiAutoState = State.IDLE
    }

    private fun targets() = ByeDPIProbeTargets.selected(DataStore.byedpiProbeTargets, DataStore.byedpiProbeCustom)

    private suspend fun loop() {
        delay(3000) // let the service settle
        val interval = ByeDPIAuto.intervalMs(intervalMinutes)
        while (true) {
            if (targets().isEmpty()) {
                Log.w(TAG, "byedpi-auto: no test targets selected")
                DataStore.byedpiAutoState = State.NO_TARGETS
                return
            }
            val now = System.currentTimeMillis()
            val pool = ByeDPIAuto.decode(DataStore.byedpiAutoPool)
            if (pool.isEmpty() || now - DataStore.byedpiAutoLastTest >= interval) {
                if (ByeDPIAuto.needsFullTest(pool, now, DataStore.byedpiAutoLastFull)) {
                    fullTest()
                } else {
                    val retested = poolTest(pool, requests = DataStore.byedpiProbeRequests)
                    if (ByeDPIAuto.needsFullTest(retested, now, DataStore.byedpiAutoLastFull) || retested.size < 3) {
                        fullTest()
                    }
                }
            }
            DataStore.byedpiAutoState = State.IDLE
            // health checks until the next scheduled test
            while (true) {
                val left = DataStore.byedpiAutoLastTest + interval - System.currentTimeMillis()
                if (left <= 0) break
                delay(minOf(left, ByeDPIAuto.HEALTH_INTERVAL_MS))
                if (DataStore.byedpiAutoLastTest + interval - System.currentTimeMillis() > 0) healthCheck()
            }
        }
    }

    private fun runner(requests: Int) = ByeDPIProbeRunner(
        targets(),
        requests.coerceIn(1, 20),
        DataStore.byedpiProbeTimeout.coerceIn(1, 60),
        DataStore.byedpiProbeConcurrency.coerceIn(1, ByeDPIProbe.MAX_CONCURRENCY),
        DataStore.byedpiProbeDoh,
    ) {}

    private suspend fun test(rows: List<ByeDPIProbe.Result>, requests: Int): List<ByeDPIProbe.Result> {
        runner(requests).run(rows) { resolution ->
            if (resolution.failed.isNotEmpty()) Log.w(TAG, "byedpi-auto: could not resolve ${resolution.failed}")
        }
        return rows
    }

    private fun strategies(): List<String> {
        val text = DataStore.byedpiProbeStrategies.ifBlank {
            SagerNet.application.assets.open(ByeDPIProbe.STRATEGIES_ASSET).bufferedReader().use { it.readText() }
        }
        return ByeDPIProbe.parseStrategies(text, DataStore.byedpiProbeSni)
    }

    private suspend fun fullTest() {
        DataStore.byedpiAutoState = State.FULL_TEST
        val current = ByeDPI.effectiveArgs(DataStore.byedpiArgs)
        val rows = ByeDPIProbe.buildPlan(current, strategies()).filter { it.kind != ByeDPIProbe.Kind.BASELINE }
        Log.i(TAG, "byedpi-auto: full test of ${rows.size} strategies")
        test(rows, DataStore.byedpiProbeRequests)
        val now = System.currentTimeMillis()
        DataStore.byedpiAutoLastTest = now
        val pool = ByeDPIAuto.pickPool(rows)
        if (pool.isEmpty()) {
            Log.w(TAG, "byedpi-auto: no strategy worked (network down or targets unreachable); keeping current")
            return
        }
        DataStore.byedpiAutoLastFull = now
        DataStore.byedpiAutoPool = ByeDPIAuto.encode(pool)
        val currentEntry = rows.firstOrNull { it.kind == ByeDPIProbe.Kind.CURRENT }?.let {
            ByeDPIAuto.Entry(current, it.successRate, it.medianLatency)
        }
        applyIfBetter(current, currentEntry, pool.first())
    }

    /** Re-tests the pool (and the current args); stores and returns the refreshed pool. */
    private suspend fun poolTest(pool: List<ByeDPIAuto.Entry>, requests: Int): List<ByeDPIAuto.Entry> {
        DataStore.byedpiAutoState = State.POOL_TEST
        val current = ByeDPI.effectiveArgs(DataStore.byedpiArgs)
        val rows = mutableListOf<ByeDPIProbe.Result>()
        if (pool.none { it.args == current }) rows.add(ByeDPIProbe.Result(ByeDPIProbe.Kind.CURRENT, current))
        pool.forEach { rows.add(ByeDPIProbe.Result(ByeDPIProbe.Kind.STRATEGY, it.args)) }
        Log.i(TAG, "byedpi-auto: re-testing pool of ${pool.size}")
        test(rows, requests)
        DataStore.byedpiAutoLastTest = System.currentTimeMillis()
        val fresh = ByeDPIAuto.pickPool(rows.filter { it.kind == ByeDPIProbe.Kind.STRATEGY })
        if (fresh.isEmpty()) {
            Log.w(TAG, "byedpi-auto: whole pool failed")
            return fresh
        }
        DataStore.byedpiAutoPool = ByeDPIAuto.encode(fresh)
        val currentEntry = rows.firstOrNull { it.args == current }?.let {
            ByeDPIAuto.Entry(current, it.successRate, it.medianLatency)
        }
        applyIfBetter(current, currentEntry, fresh.first())
        return fresh
    }

    private suspend fun healthCheck() {
        DataStore.byedpiAutoState = State.HEALTH_CHECK
        val current = ByeDPI.effectiveArgs(DataStore.byedpiArgs)
        val row = ByeDPIProbe.Result(ByeDPIProbe.Kind.CURRENT, current)
        test(listOf(row), requests = 1)
        DataStore.byedpiAutoState = State.IDLE
        if (!ByeDPIAuto.healthFailed(row.successRate)) return
        Log.w(TAG, "byedpi-auto: current strategy failing (${(row.successRate * 100).toInt()}%), falling back")
        val pool = ByeDPIAuto.decode(DataStore.byedpiAutoPool).filter { it.args != current }
        val retested = if (pool.isEmpty()) emptyList() else poolTestNoApply(pool)
        val next = ByeDPIAuto.fallback(current, retested)
        if (next != null) {
            apply(next.args, "fallback")
        } else {
            fullTest()
        }
    }

    private suspend fun poolTestNoApply(pool: List<ByeDPIAuto.Entry>): List<ByeDPIAuto.Entry> {
        DataStore.byedpiAutoState = State.POOL_TEST
        val rows = pool.map { ByeDPIProbe.Result(ByeDPIProbe.Kind.STRATEGY, it.args) }
        test(rows, requests = 1)
        return rows.map { ByeDPIAuto.Entry(it.args!!, it.successRate, it.medianLatency) }
    }

    private suspend fun applyIfBetter(current: String, currentEntry: ByeDPIAuto.Entry?, best: ByeDPIAuto.Entry) {
        if (ByeDPIAuto.shouldSwitch(current, currentEntry, best)) apply(best.args, "best")
    }

    private suspend fun apply(args: String, reason: String) {
        Log.i(TAG, "byedpi-auto: switching to [$args] ($reason)")
        DataStore.byedpiArgs = args
        DataStore.byedpiAutoSwitchedAt = System.currentTimeMillis()
        restart(args)
    }
}
