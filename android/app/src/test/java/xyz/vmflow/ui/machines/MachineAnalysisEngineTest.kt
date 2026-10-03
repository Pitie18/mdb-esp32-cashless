package xyz.vmflow.ui.machines

import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.data.MachineAnalysisDataSource
import xyz.vmflow.data.ProductKpiRow
import xyz.vmflow.data.SlotTier
import xyz.vmflow.data.SwapLogContext
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

/**
 * Covers the KPI-row -> [ProductAnalysis] merge (tenure -> tier per product,
 * the subtlest part of task 24) plus the [MachineAnalysisEngine] state
 * machine that wraps it. Mirrors `DashboardLoadMoreTest`'s approach: the
 * engine has no dependency on `viewModelScope`/`Dispatchers.Main`, so plain
 * JUnit + `runBlocking` against a fake [MachineAnalysisDataSource] is enough.
 */
class MachineAnalysisEngineTest {

    private val fixedNow = Instant.fromEpochSeconds(1_700_000_000)

    private fun kpiRow(
        productId: String = "p1",
        productName: String? = "Product",
        unitsSold: Int = 0,
        totalCapacity: Int? = 10,
        totalStock: Int? = 5,
        slots: List<Int> = emptyList(),
        offeredSince: Instant? = null,
        revenueEur: Double = 0.0,
    ) = ProductKpiRow(
        productId = productId,
        productName = productName,
        unitsSold = unitsSold,
        totalCapacity = totalCapacity,
        totalStock = totalStock,
        slots = slots,
        offeredSince = offeredSince,
        revenueEur = revenueEur,
    )

    private fun tray(id: String, itemNumber: Int, productId: String? = null, product: Product? = null) =
        Tray(id = id, machineId = "m1", itemNumber = itemNumber, productId = productId, products = product)

    // ─── buildProductAnalyses: pure merge, no I/O ───────────────────────

    @Test
    fun `a product offered 14 days ago no longer gets the grace period`() {
        val row = kpiRow(unitsSold = 0, offeredSince = fixedNow - 14.days)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(14, analyses.single().tenureDays)
        assertEquals(SlotTier.DEAD, analyses.single().tier)
    }

    @Test
    fun `a product offered 13 days ago still gets the grace period`() {
        val row = kpiRow(unitsSold = 0, offeredSince = fixedNow - 13.days)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(13, analyses.single().tenureDays)
        assertEquals(SlotTier.TESTING, analyses.single().tier)
    }

    @Test
    fun `no offeredSince gives a null tenure and no grace period`() {
        val row = kpiRow(unitsSold = 0, offeredSince = null)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertNull(analyses.single().tenureDays)
        assertEquals(SlotTier.DEAD, analyses.single().tier)
    }

    @Test
    fun `moving a product between slots does not appear in this merge - tenure is carried in unchanged from the KPI row`() {
        // The RPC (not Android) is responsible for keeping offered_since keyed
        // by (machine, product) across slot moves; this merge just trusts it.
        val row = kpiRow(unitsSold = 5, totalCapacity = 10, totalStock = 5, offeredSince = fixedNow - 40.days)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(40, analyses.single().tenureDays)
    }

    @Test
    fun `sell-through is units sold over weekly-normalized capacity, capped at 100`() {
        // capacity 10, days 7 -> weekly capacity = 10. 5 sold -> 50%.
        val row = kpiRow(unitsSold = 5, totalCapacity = 10, totalStock = 5)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 7, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(50.0, analyses.single().sellThroughPct, 0.001)
    }

    @Test
    fun `sell-through never exceeds 100 percent`() {
        val row = kpiRow(unitsSold = 1000, totalCapacity = 10, totalStock = 5)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 7, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(100.0, analyses.single().sellThroughPct, 0.001)
    }

    @Test
    fun `zero capacity gives zero sell-through instead of dividing by zero`() {
        val row = kpiRow(unitsSold = 5, totalCapacity = 0, totalStock = 0)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(0.0, analyses.single().sellThroughPct, 0.001)
    }

    @Test
    fun `avg daily units is units sold over the window, rounded to 2 decimals`() {
        val row = kpiRow(unitsSold = 10)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 3, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(3.33, analyses.single().avgDailyUnits, 0.001)
    }

    @Test
    fun `days until empty is null when nothing has sold, even with stock left`() {
        val row = kpiRow(unitsSold = 0, totalStock = 5)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertNull(analyses.single().daysUntilEmpty)
    }

    @Test
    fun `days until empty is 0 once stock is empty, regardless of sales`() {
        val row = kpiRow(unitsSold = 10, totalStock = 0)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(0, analyses.single().daysUntilEmpty)
    }

    @Test
    fun `days until empty projects remaining stock at the current sales rate`() {
        // 10 sold over 30 days = 1 every 3 days; 6 left -> 18 days.
        val row = kpiRow(unitsSold = 10, totalStock = 6)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(18, analyses.single().daysUntilEmpty)
    }

    @Test
    fun `trayIds are grouped by product across every slot it occupies`() {
        val trays = listOf(
            tray("t1", 10, productId = "p1"),
            tray("t2", 20, productId = "p1"),
            tray("t3", 21, productId = "p2"),
        )
        val row = kpiRow(productId = "p1")
        val analyses = buildProductAnalyses(listOf(row), trays, emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals(setOf("t1", "t2"), analyses.single().trayIds.toSet())
    }

    @Test
    fun `image path is resolved from the catalogue, not the KPI row`() {
        val catalogue = listOf(Product(id = "p1", name = "Cola", imagePath = "p1.png"))
        val row = kpiRow(productId = "p1")
        val analyses = buildProductAnalyses(listOf(row), emptyList(), catalogue, days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals("p1.png", analyses.single().imagePath)
    }

    @Test
    fun `a missing product name falls back to Unknown`() {
        val row = kpiRow(productName = null)
        val analyses = buildProductAnalyses(listOf(row), emptyList(), emptyList(), days = 30, now = fixedNow, sharedSuggestions = emptyList())
        assertEquals("Unknown", analyses.single().name)
    }

    @Test
    fun `only dead or weak products carry replacement suggestions, and never suggest themselves`() {
        val strongRow = kpiRow(productId = "p1", unitsSold = 100, totalCapacity = 10, totalStock = 5)
        // p2 also appears in the shared suggestion pool (e.g. it's a newcomer
        // in a different machine) — it must never suggest replacing itself.
        val deadRow = kpiRow(productId = "p2", unitsSold = 0, totalStock = 5)
        val suggestions = listOf(
            xyz.vmflow.data.Suggestion("p2", "Self", null, xyz.vmflow.data.SuggestionKind.BESTSELLER, 5.0),
            xyz.vmflow.data.Suggestion("p3", "Other", null, xyz.vmflow.data.SuggestionKind.BESTSELLER, 3.0),
        )
        val analyses = buildProductAnalyses(
            listOf(strongRow, deadRow), emptyList(), emptyList(), days = 7, now = fixedNow, sharedSuggestions = suggestions,
        )
        val strong = analyses.first { it.productId == "p1" }
        val dead = analyses.first { it.productId == "p2" }
        assertTrue("a strong product must not carry review suggestions", strong.suggestions.isEmpty())
        assertEquals(listOf("p3"), dead.suggestions.map { it.productId })
    }

    // ─── weakProducts: worst-first ordering ─────────────────────────────

    @Test
    fun `weakProducts sorts dead before weak, then by lowest sell-through first`() {
        val state = MachineAnalysisUiState(
            products = listOf(
                ProductAnalysis("p1", "Weak-high", null, emptyList(), emptyList(), 5, 0.0, 10, 5, 20.0, 0.0, null, null, SlotTier.WEAK, emptyList()),
                ProductAnalysis("p2", "Dead", null, emptyList(), emptyList(), 0, 0.0, 10, 5, 0.0, 0.0, null, null, SlotTier.DEAD, emptyList()),
                ProductAnalysis("p3", "Weak-low", null, emptyList(), emptyList(), 3, 0.0, 10, 5, 5.0, 0.0, null, null, SlotTier.WEAK, emptyList()),
                ProductAnalysis("p4", "Strong", null, emptyList(), emptyList(), 50, 0.0, 10, 5, 80.0, 0.0, null, null, SlotTier.STRONG, emptyList()),
            ),
        )
        assertEquals(listOf("p2", "p3", "p1"), state.weakProducts.map { it.productId })
    }

    // ─── MachineAnalysisEngine: orchestration with a fake data source ───

    private class FakeMachineAnalysisDataSource(
        var companyId: String = "company-1",
        var kpiRows: List<ProductKpiRow> = emptyList(),
        var velocity: Map<String, Double> = emptyMap(),
        var failKpiWith: Throwable? = null,
        var failUpdateWith: Throwable? = null,
        var failLogWith: Throwable? = null,
    ) : MachineAnalysisDataSource {
        var updateCalls = mutableListOf<Pair<String, String>>()
        var logCalls = mutableListOf<SwapLogContext>()

        override suspend fun fetchCompanyId(): String = companyId

        override suspend fun fetchProductKpis(machineId: String, companyId: String, days: Int): List<ProductKpiRow> {
            failKpiWith?.let { throw it }
            return kpiRows
        }

        override suspend fun fetchVelocity(companyId: String, days: Int): Map<String, Double> = velocity

        override suspend fun updateTrayProduct(trayId: String, productId: String) {
            failUpdateWith?.let { throw it }
            updateCalls.add(trayId to productId)
        }

        override suspend fun logProductSwap(context: SwapLogContext) {
            failLogWith?.let { throw it }
            logCalls.add(context)
        }
    }

    @Test
    fun `analyze populates products, slots and row count from the fake repository`() = runBlocking {
        val trays = listOf(tray("t1", 10, productId = "p1"))
        val repo = FakeMachineAnalysisDataSource(kpiRows = listOf(kpiRow(productId = "p1", unitsSold = 5, totalStock = 5)))
        val engine = MachineAnalysisEngine(repo)

        engine.analyze("m1", trays, emptyList(), now = fixedNow)

        val state = engine.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.products.size)
        assertEquals(1, state.slots.size)
        assertEquals(1, state.rowCount)
        assertNull(state.error)
    }

    @Test
    fun `analyze with no trays gives an empty grid`() = runBlocking {
        val repo = FakeMachineAnalysisDataSource()
        val engine = MachineAnalysisEngine(repo)

        engine.analyze("m1", emptyList(), emptyList(), now = fixedNow)

        assertEquals(0, engine.uiState.value.rowCount)
        assertTrue(engine.uiState.value.slots.isEmpty())
    }

    @Test
    fun `a KPI fetch failure surfaces as an error and clears the loading flag`() = runBlocking {
        val repo = FakeMachineAnalysisDataSource(failKpiWith = RuntimeException("network down"))
        val engine = MachineAnalysisEngine(repo)

        engine.analyze("m1", emptyList(), emptyList(), now = fixedNow)

        val state = engine.uiState.value
        assertFalse(state.isLoading)
        assertEquals("network down", state.error)
    }

    @Test
    fun `a cancellation during analyze propagates instead of being swallowed as an error`() {
        val repo = object : MachineAnalysisDataSource {
            override suspend fun fetchCompanyId(): String = "company-1"
            override suspend fun fetchProductKpis(machineId: String, companyId: String, days: Int): List<ProductKpiRow> =
                throw CancellationException("cancelled")
            override suspend fun fetchVelocity(companyId: String, days: Int): Map<String, Double> = emptyMap()
            override suspend fun updateTrayProduct(trayId: String, productId: String) = Unit
            override suspend fun logProductSwap(context: SwapLogContext) = Unit
        }
        val engine = MachineAnalysisEngine(repo)

        var caught: Throwable? = null
        try {
            runBlocking { engine.analyze("m1", emptyList(), emptyList(), now = fixedNow) }
        } catch (e: CancellationException) {
            caught = e
        }
        assertTrue(caught is CancellationException)
    }

    @Test
    fun `applySwap writes the update and a best-effort log entry naming the old and new product`() = runBlocking {
        val oldProduct = Product(id = "old-id", name = "Old Product")
        val trays = listOf(tray("t1", 12, productId = "old-id", product = oldProduct))
        val repo = FakeMachineAnalysisDataSource(kpiRows = emptyList())
        val engine = MachineAnalysisEngine(repo)
        engine.analyze("m1", trays, emptyList(), now = fixedNow) // populates lastSlots/lastMachineId

        val ok = engine.applySwap("t1", "new-id")

        assertTrue(ok)
        assertEquals(listOf("t1" to "new-id"), repo.updateCalls)
        assertEquals(1, repo.logCalls.size)
        val logged = repo.logCalls.single()
        assertEquals("m1", logged.machineId)
        assertEquals(12, logged.itemNumber)
        assertEquals("new-id", logged.newProductId)
        assertEquals("old-id", logged.oldProductId)
        assertEquals("Old Product", logged.oldProductName)
    }

    @Test
    fun `a failed update reports an error and never reaches the log call`() = runBlocking {
        val repo = FakeMachineAnalysisDataSource(failUpdateWith = RuntimeException("write failed"))
        val engine = MachineAnalysisEngine(repo)
        engine.analyze("m1", emptyList(), emptyList(), now = fixedNow)

        val ok = engine.applySwap("t1", "new-id")

        assertFalse(ok)
        assertEquals("write failed", engine.uiState.value.error)
        assertTrue(repo.logCalls.isEmpty())
    }

    @Test
    fun `a failed audit log entry does not undo an already-successful swap`() = runBlocking {
        val repo = FakeMachineAnalysisDataSource(failLogWith = RuntimeException("log table missing"))
        val engine = MachineAnalysisEngine(repo)
        engine.analyze("m1", emptyList(), emptyList(), now = fixedNow)

        val ok = engine.applySwap("t1", "new-id")

        assertTrue("the swap write already succeeded; a logging failure must stay non-fatal", ok)
        assertEquals(listOf("t1" to "new-id"), repo.updateCalls)
    }
}
