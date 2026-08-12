package xyz.vmflow.ui.dashboard

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.data.DashboardDataSource
import xyz.vmflow.data.MachineStockHealth
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SaleWithMachine

/**
 * Covers the infinite-scroll state machine in [DashboardEngine] — the
 * subtlest part of phase 2. Drives [DashboardEngine] directly (not
 * [DashboardViewModel]) with a fake [DashboardDataSource]: the engine has no
 * dependency on `viewModelScope`/`Dispatchers.Main`, so plain JUnit +
 * `runBlocking` is enough — no Robolectric, no `kotlinx-coroutines-test`.
 */
class DashboardLoadMoreTest {

    // MARK: - nextDaysBack: pure function, no I/O.

    @Test
    fun `nextDaysBack starts at today-only, jumps 6, then steps of 7`() {
        assertEquals(6, nextDaysBack(0))
        assertEquals(13, nextDaysBack(6))
        assertEquals(20, nextDaysBack(13))
        assertEquals(27, nextDaysBack(20))
    }

    // MARK: - Fake data source

    private class FakeDashboardDataSource(
        var rawSaleCount: Int = 0,
        var activityRows: List<ActivityLogRow> = emptyList(),
        var intakeRows: List<IntakeTransactionRow> = emptyList(),
        var failRecentSaleItemsWith: Throwable? = null,
    ) : DashboardDataSource {
        var fetchRecentSaleItemsCalls = 0

        override suspend fun fetchSalesSince(since: Instant): List<Sale> = emptyList()

        override suspend fun fetchMachineStockHealth(): MachineStockHealth =
            MachineStockHealth(total = 0, online = 0, criticalMachines = 0, lowMachines = 0)

        override suspend fun fetchRecentSaleItems(windowStart: Instant): Pair<List<SaleWithMachine>, Int> {
            fetchRecentSaleItemsCalls++
            failRecentSaleItemsWith?.let { throw it }
            return emptyList<SaleWithMachine>() to rawSaleCount
        }

        override suspend fun fetchActivityRows(windowStart: Instant): List<ActivityLogRow> = activityRows

        override suspend fun fetchIntakeRows(windowStart: Instant): List<IntakeTransactionRow> = intakeRows

        override suspend fun resolveUserNames(ids: List<String>): Map<String, String> = emptyMap()

        override suspend fun fetchNewDealsCount(): Int = 0
    }

    // MARK: - State machine

    @Test
    fun `widening the window with no new raw rows marks history exhausted`() = runBlocking {
        val repo = FakeDashboardDataSource(rawSaleCount = 5)
        val engine = DashboardEngine(repo)
        engine.loadRecentActivity() // baseline at daysBack = 0

        engine.loadMoreActivity() // daysBack -> 6, same raw rows

        val state = engine.uiState.value
        assertEquals(6, state.activityDaysBack)
        assertFalse(state.hasMoreActivity)
        assertFalse(state.isLoadingMoreActivity)
        assertFalse(state.loadMoreFailed)
    }

    @Test
    fun `a reload that brings more raw rows revives an exhausted window`() = runBlocking {
        val repo = FakeDashboardDataSource(rawSaleCount = 5)
        val engine = DashboardEngine(repo)
        engine.loadRecentActivity()
        engine.loadMoreActivity() // exhausts at daysBack = 6
        assertFalse(engine.uiState.value.hasMoreActivity)

        repo.rawSaleCount = 9 // e.g. realtime delivery into the current window
        engine.loadRecentActivity()

        assertTrue(engine.uiState.value.hasMoreActivity)
        // Window itself is untouched by a plain reload.
        assertEquals(6, engine.uiState.value.activityDaysBack)
    }

    @Test
    fun `a real failure reverts the window and flags loadMoreFailed for manual retry`() = runBlocking {
        val repo = FakeDashboardDataSource(rawSaleCount = 5)
        val engine = DashboardEngine(repo)
        engine.loadRecentActivity() // baseline at daysBack = 0

        repo.failRecentSaleItemsWith = RuntimeException("network down")
        engine.loadMoreActivity()

        val state = engine.uiState.value
        assertEquals(0, state.activityDaysBack) // reverted, not left at 6
        assertTrue(state.loadMoreFailed)
        assertFalse(state.isLoadingMoreActivity)
        assertTrue(state.hasMoreActivity) // untouched by a failed attempt
    }

    @Test
    fun `does nothing once history is exhausted`() = runBlocking {
        val repo = FakeDashboardDataSource(rawSaleCount = 5)
        val engine = DashboardEngine(repo)
        engine.loadRecentActivity()

        engine.loadMoreActivity() // -> daysBack 6, exhausted (guard: hasMoreActivity)
        val callsAfterFirst = repo.fetchRecentSaleItemsCalls
        engine.loadMoreActivity() // hasMoreActivity is now false: must no-op

        assertEquals(callsAfterFirst, repo.fetchRecentSaleItemsCalls)
        assertEquals(6, engine.uiState.value.activityDaysBack)
    }
}
