package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The machine-list card's stock roll-up, mirroring the PWA's
 * `useMachines.ts`: warehouse-aware product counts, the health that only
 * refillable products drive, the stock percentage, and the list order.
 */
class MachineWithStatsTest {

    private fun tray(
        id: String,
        productId: String?,
        currentStock: Int,
        capacity: Int = 10,
        minStock: Int? = 2,
        fillWhenBelow: Int? = 5,
        machineId: String = "m1",
    ) = Tray(
        id = id,
        machineId = machineId,
        itemNumber = id.filter { it.isDigit() }.toIntOrNull() ?: 1,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
        minStock = minStock,
        fillWhenBelow = fillWhenBelow,
    )

    private fun stats(
        trays: List<Tray>,
        warehouse: Set<String> = emptySet(),
        hasWarehouses: Boolean = warehouse.isNotEmpty(),
        id: String = "m1",
    ) = MachineWithStats(
        machine = VendingMachineWithEmbedded(id = id, name = id),
        trays = trays.map { it.copy(machineId = id) },
        warehouseProductIds = warehouse,
        hasWarehouses = hasWarehouses,
    )

    @Test
    fun `a sold-out product without warehouse stock does not make the machine critical`() {
        val s = stats(
            listOf(
                tray("t1", "cola", currentStock = 0),   // sold out, not in warehouse -> swap
                tray("t2", "fanta", currentStock = 4),  // top off, refillable
            ),
            warehouse = setOf("fanta"),
        )
        assertEquals(MachineWithStats.StockHealth.FILL, s.stockHealth)
        assertEquals(0, s.productStock.refillableEmpty)
        assertEquals(1, s.productStock.refillableFill)
        assertEquals(1, s.productStock.noStockEmptyCount)
    }

    @Test
    fun `without any warehouse stock every product is refillable`() {
        val s = stats(listOf(tray("t1", "cola", currentStock = 0)))
        assertEquals(MachineWithStats.StockHealth.CRITICAL, s.stockHealth)
    }

    @Test
    fun `top off is its own tier, not low`() {
        assertEquals(MachineWithStats.StockHealth.FILL, stats(listOf(tray("t1", "cola", currentStock = 4))).stockHealth)
        assertEquals(MachineWithStats.StockHealth.LOW, stats(listOf(tray("t1", "cola", currentStock = 2))).stockHealth)
    }

    @Test
    fun `unassigned slots count only towards the stock percentage`() {
        val s = stats(
            listOf(
                tray("t1", null, currentStock = 0),
                tray("t2", "cola", currentStock = 10),
            )
        )
        assertEquals(MachineWithStats.StockHealth.OK, s.stockHealth)
        assertEquals(0, s.productsNeedingRefill)
        assertEquals(50, s.stockPercent)
    }

    @Test
    fun `stock percent is 0 without capacity`() {
        assertEquals(0, stats(emptyList()).stockPercent)
    }

    @Test
    fun `list order is critical, low, fill, ok, then sold-out plus low count`() {
        val ok = stats(listOf(tray("t1", "a", currentStock = 10)), id = "ok")
        val fill = stats(listOf(tray("t1", "a", currentStock = 4)), id = "fill")
        val low = stats(listOf(tray("t1", "a", currentStock = 1)), id = "low")
        val critOne = stats(listOf(tray("t1", "a", currentStock = 0)), id = "crit1")
        val critTwo = stats(
            listOf(tray("t1", "a", currentStock = 0), tray("t2", "b", currentStock = 1)),
            id = "crit2",
        )

        val sorted = listOf(ok, fill, low, critOne, critTwo).sortedWith(MachineWithStats.STOCK_URGENCY)
        assertEquals(listOf("crit2", "crit1", "low", "fill", "ok"), sorted.map { it.machine.id })
    }

    @Test
    fun `a tray update on the detail screen keeps the warehouse-aware counts`() {
        // MachineDetailViewModel updates stock via copy(trays = ...); the roll-up
        // must be recomputed with the same warehouse data, not fall back to "all refillable".
        val s = stats(listOf(tray("t1", "cola", currentStock = 4)), warehouse = setOf("fanta"))
        val updated = s.copy(trays = listOf(tray("t1", "cola", currentStock = 0)))
        assertEquals(MachineWithStats.StockHealth.OK, updated.stockHealth)
        assertEquals(1, updated.productStock.noStockEmptyCount)
    }
}
