package xyz.vmflow.ui.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.data.CashBookSummary
import xyz.vmflow.data.DashboardDataSource
import xyz.vmflow.data.MachineStockHealth
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SaleWithMachine

/**
 * Covers [DashboardEngine.loadIsolated] — the wrapper [DashboardEngine.loadDashboard]
 * puts around each of its seven sibling sub-fetches. Before this fix, those
 * seven ran as bare `launch { ... }` children of one `coroutineScope`:
 * Kotlin's structured concurrency means an uncaught exception in any ONE of
 * them cancels every other in-flight `launch`, so a single broken widget
 * (this actually happened: a `products(...)` select missing a required `id`
 * column) could blank the entire dashboard — KPIs, chart, activity that
 * fetched fine included — instead of just leaving its own section empty.
 *
 * These tests exercise [DashboardEngine.loadIsolated] and the
 * `coroutineScope`/`launch` pattern directly, not the full
 * [DashboardEngine.loadDashboard] — two of its seven loaders
 * (`loadOrganization`/`loadLegacyMachinesAndSales`) call the real
 * `AuthRepository`/`MachineRepository` singletons, which are legitimately
 * uninitializable in a plain-JVM test (`VMflowApp.instance` is a `lateinit
 * var`, never set outside a real Android runtime) and would poison the
 * whole test JVM via `ExceptionInInitializerError`/`NoClassDefFoundError`
 * if touched — not a flaw in the fix, just outside what this test
 * environment can safely exercise. `loadIsolated` is the actual unit
 * carrying the fix, so it's what's under test.
 */
class DashboardResilienceTest {

    private class FakeDashboardDataSource : DashboardDataSource {
        override suspend fun fetchSalesSince(since: Instant): List<Sale> = emptyList()
        override suspend fun fetchMachineStockHealth(): MachineStockHealth =
            MachineStockHealth(total = 0, online = 0, criticalMachines = 0, lowMachines = 0)
        override suspend fun fetchRecentSaleItems(windowStart: Instant): Pair<List<SaleWithMachine>, Int> =
            emptyList<SaleWithMachine>() to 0
        override suspend fun fetchActivityRows(windowStart: Instant): List<ActivityLogRow> = emptyList()
        override suspend fun fetchIntakeRows(windowStart: Instant): List<IntakeTransactionRow> = emptyList()
        override suspend fun resolveUserNames(ids: List<String>): Map<String, String> = emptyMap()
        override suspend fun fetchNewDealsCount(): Int = 0
        override suspend fun fetchCashBookSummary(): CashBookSummary = CashBookSummary(hasCashBook = false)
    }

    @Test
    fun `loadIsolated catches a failure and records it on error instead of rethrowing`() = runBlocking {
        val engine = DashboardEngine(FakeDashboardDataSource())

        engine.loadIsolated { throw RuntimeException("missing id column") }

        assertEquals("missing id column", engine.uiState.value.error)
    }

    @Test
    fun `loadIsolated keeps the first recorded error when a second loader also fails`() = runBlocking {
        val engine = DashboardEngine(FakeDashboardDataSource())

        engine.loadIsolated { throw RuntimeException("first failure") }
        engine.loadIsolated { throw RuntimeException("second failure") }

        assertEquals("first failure", engine.uiState.value.error)
    }

    @Test
    fun `loadIsolated rethrows CancellationException instead of swallowing it`() = runBlocking {
        val engine = DashboardEngine(FakeDashboardDataSource())
        var caughtCancellation = false

        val job = launch {
            try {
                engine.loadIsolated { throw CancellationException("leaving the screen") }
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }
        job.join()

        assertTrue(caughtCancellation)
    }

    @Test
    fun `wrapping sibling launches in loadIsolated stops one failure from cancelling the others`() = runBlocking {
        val engine = DashboardEngine(FakeDashboardDataSource())
        var siblingCompleted = false

        // Mirrors loadDashboard()'s actual shape: several `launch { loadIsolated { ... } }`
        // children under one coroutineScope. Without the wrapper, the first
        // launch's uncaught exception would cancel the coroutineScope and the
        // second launch's body would never run.
        coroutineScope {
            launch { engine.loadIsolated { throw RuntimeException("boom") } }
            launch {
                engine.loadIsolated {
                    delay(10) // give the failing sibling a chance to run first
                    siblingCompleted = true
                }
            }
        }

        assertTrue("a sibling launch must still complete despite another one failing", siblingCompleted)
        assertEquals("boom", engine.uiState.value.error)
    }
}
