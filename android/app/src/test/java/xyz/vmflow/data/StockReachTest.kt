package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.datetime.LocalDate
import xyz.vmflow.ui.warehouse.WarehouseUiState

/** Stock reach must match the web warehouse page (`estimated_days_remaining`). */
class StockReachTest {

    @Test
    fun `reach is quantity over daily sales, rounded`() {
        assertEquals(10, StockReach.daysRemaining(12, 1.2))
        assertEquals(12, StockReach.daysRemaining(48, 4.1)) // 11.7 -> 12
        assertEquals(0, StockReach.daysRemaining(0, 2.0))
    }

    @Test
    fun `no sales means unknown reach`() {
        assertNull(StockReach.daysRemaining(22, 0.0))
        assertEquals(ReachLevel.UNKNOWN, StockReach.level(null))
    }

    @Test
    fun `levels use the web thresholds`() {
        assertEquals(ReachLevel.CRITICAL, StockReach.level(7))
        assertEquals(ReachLevel.WARNING, StockReach.level(8))
        assertEquals(ReachLevel.WARNING, StockReach.level(14))
        assertEquals(ReachLevel.OK, StockReach.level(15))
    }

    @Test
    fun `summaries carry velocity and sort by reach with unsold products last`() {
        val products = listOf(
            WarehouseIntakeLogic.ProductSummaryInput("a", "Apple", null, discontinued = false),
            WarehouseIntakeLogic.ProductSummaryInput("b", "Banana", null, discontinued = false),
            WarehouseIntakeLogic.ProductSummaryInput("c", "Cherry", null, discontinued = false),
        )
        val batches = listOf(
            WarehouseIntakeLogic.BatchSummaryInput("a", 30, null),
            WarehouseIntakeLogic.BatchSummaryInput("b", 6, null),
            WarehouseIntakeLogic.BatchSummaryInput("c", 22, null),
        )
        val summaries = WarehouseIntakeLogic.buildProductSummaries(
            products, batches, LocalDate(2026, 9, 28), velocity = mapOf("a" to 1.0, "b" to 2.0)
        )
        assertEquals(30, summaries[0].daysRemaining)
        assertEquals(3, summaries[1].daysRemaining)
        assertNull(summaries[2].daysRemaining)

        val byName = WarehouseUiState(productSummaries = summaries).filteredSummaries.map { it.productId }
        assertEquals(listOf("a", "b", "c"), byName)
        val byReach = WarehouseUiState(productSummaries = summaries, sortByReach = true).filteredSummaries.map { it.productId }
        assertEquals(listOf("b", "a", "c"), byReach)
    }
}
