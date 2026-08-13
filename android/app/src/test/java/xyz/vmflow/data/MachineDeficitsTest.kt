package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Product
import xyz.vmflow.models.StockSeverity
import xyz.vmflow.models.Tray
import xyz.vmflow.models.WarehouseAvailability

/**
 * Ported 1:1 from `ios/VMflow/ViewModels/MachineListViewModel.swift` (lines
 * 137-239, the per-machine deficit-building algorithm) and the severity
 * predicates in `ios/VMflow/Models/Tray.swift` (lines 59-90: `isEmpty`,
 * `isBelowMinStock`, `isBelowFillThreshold`, `deficit`). Deliberately does
 * NOT reuse Android's existing `Tray.isLow`/`isCritical` — those are looser
 * heuristics for a different purpose (Trays-tab row colouring, Overview
 * tab's stock-summary card) and must keep disagreeing with iOS's exact
 * `<=` boundary predicates used here.
 */
class MachineDeficitsTest {

    private fun tray(
        id: String,
        itemNumber: Int = 1,
        productId: String? = null,
        capacity: Int = 10,
        currentStock: Int = 0,
        minStock: Int? = null,
        fillWhenBelow: Int? = null,
        product: Product? = null,
    ) = Tray(
        id = id,
        machineId = "m1",
        itemNumber = itemNumber,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
        minStock = minStock,
        fillWhenBelow = fillWhenBelow,
        products = product,
    )

    private fun product(id: String, name: String = "Product $id", discontinued: Boolean = false) =
        Product(id = id, name = name, discontinued = discontinued)

    // ─── Severity classification ────────────────────────────────────────

    @Test
    fun `an empty tray is critical regardless of minStock and fillWhenBelow`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 0, minStock = 5, fillWhenBelow = 8, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertEquals(StockSeverity.CRITICAL, summary.trayDeficits.single().severity)
    }

    @Test
    fun `minStock of 0 never triggers low`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 3, minStock = 0, fillWhenBelow = null, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertTrue(summary.trayDeficits.isEmpty())
    }

    @Test
    fun `fillWhenBelow of 0 never triggers fillBelow`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 3, minStock = null, fillWhenBelow = 0, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertTrue(summary.trayDeficits.isEmpty())
    }

    @Test
    fun `currentStock exactly equal to minStock is low`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 5, minStock = 5, fillWhenBelow = null, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertEquals(StockSeverity.LOW, summary.trayDeficits.single().severity)
    }

    @Test
    fun `currentStock exactly equal to fillWhenBelow is fillBelow`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 8, minStock = null, fillWhenBelow = 8, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertEquals(StockSeverity.FILL_BELOW, summary.trayDeficits.single().severity)
    }

    @Test
    fun `a tray with currentStock above every threshold produces no deficit row`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 9, minStock = 5, fillWhenBelow = 8, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertTrue(summary.trayDeficits.isEmpty())
    }

    // ─── Aggregation across multiple trays of the same product ───────────

    @Test
    fun `two trays of the same product merge into one row with summed deficit`() {
        val t1 = tray("t1", itemNumber = 1, productId = "p1", capacity = 10, currentStock = 5, minStock = 5, product = product("p1"))
        val t2 = tray("t2", itemNumber = 2, productId = "p1", capacity = 10, currentStock = 3, minStock = 5, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t1, t2), emptySet(), hasWarehouses = false)
        assertEquals(1, summary.trayDeficits.size)
        assertEquals(5 + 7, summary.trayDeficits.single().deficit)
    }

    @Test
    fun `two trays of the same product take the worse of the two severities`() {
        // t1 is low (below minStock but not empty), t2 is critical (empty) — worst wins.
        val t1 = tray("t1", itemNumber = 1, productId = "p1", capacity = 10, currentStock = 4, minStock = 5, product = product("p1"))
        val t2 = tray("t2", itemNumber = 2, productId = "p1", capacity = 10, currentStock = 0, minStock = 5, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t1, t2), emptySet(), hasWarehouses = false)
        assertEquals(StockSeverity.CRITICAL, summary.trayDeficits.single().severity)
    }

    @Test
    fun `a tray with null productId gets its own row named Slot N`() {
        val t = tray("t1", itemNumber = 7, productId = null, capacity = 10, currentStock = 0)
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = false)
        assertEquals("Slot 7", summary.trayDeficits.single().productName)
    }

    @Test
    fun `two unassigned trays never merge even at the same severity`() {
        val t1 = tray("t1", itemNumber = 7, productId = null, capacity = 10, currentStock = 0)
        val t2 = tray("t2", itemNumber = 8, productId = null, capacity = 10, currentStock = 0)
        val summary = MachineDeficits.computeDeficits(listOf(t1, t2), emptySet(), hasWarehouses = false)
        assertEquals(2, summary.trayDeficits.size)
    }

    // ─── Warehouse availability classification ────────────────────────────

    @Test
    fun `hasWarehouses false makes every row unknown regardless of warehouse stock`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), setOf("p1"), hasWarehouses = false)
        assertEquals(WarehouseAvailability.UNKNOWN, summary.trayDeficits.single().warehouseAvailability)
    }

    @Test
    fun `product present in warehouseProductIds is inStock`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), setOf("p1"), hasWarehouses = true)
        assertEquals(WarehouseAvailability.IN_STOCK, summary.trayDeficits.single().warehouseAvailability)
    }

    @Test
    fun `product absent from warehouse with an empty contributing tray needs swap`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = true)
        assertEquals(WarehouseAvailability.NEEDS_SWAP, summary.trayDeficits.single().warehouseAvailability)
    }

    @Test
    fun `product absent from warehouse with no empty contributing tray is noStock`() {
        val t = tray("t1", productId = "p1", capacity = 10, currentStock = 4, minStock = 5, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t), emptySet(), hasWarehouses = true)
        assertEquals(WarehouseAvailability.NO_STOCK, summary.trayDeficits.single().warehouseAvailability)
    }

    @Test
    fun `a null-productId row is always unknown even with warehouses present`() {
        val t = tray("t1", itemNumber = 3, productId = null, capacity = 10, currentStock = 0)
        val summary = MachineDeficits.computeDeficits(listOf(t), setOf("anything"), hasWarehouses = true)
        assertEquals(WarehouseAvailability.UNKNOWN, summary.trayDeficits.single().warehouseAvailability)
    }

    // ─── swapNeededCount / noStockCount count distinct products ──────────

    @Test
    fun `two empty trays of the same out-of-stock product count once, not twice`() {
        val t1 = tray("t1", itemNumber = 1, productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        val t2 = tray("t2", itemNumber = 2, productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t1, t2), emptySet(), hasWarehouses = true)
        assertEquals(1, summary.swapNeededCount)
        assertEquals(0, summary.noStockCount)
    }

    @Test
    fun `noStockCount counts distinct low but non-empty out-of-stock products`() {
        val t1 = tray("t1", itemNumber = 1, productId = "p1", capacity = 10, currentStock = 4, minStock = 5, product = product("p1"))
        val t2 = tray("t2", itemNumber = 2, productId = "p1", capacity = 10, currentStock = 3, minStock = 5, product = product("p1"))
        val summary = MachineDeficits.computeDeficits(listOf(t1, t2), emptySet(), hasWarehouses = true)
        assertEquals(0, summary.swapNeededCount)
        assertEquals(1, summary.noStockCount)
    }

    // ─── Sort order ────────────────────────────────────────────────────────

    @Test
    fun `needsSwap rows sort first, then by severity, then by deficit descending`() {
        // needsSwap requires a contributing empty tray, and an empty tray is
        // itself always CRITICAL severity — so needsSwap rows are always
        // CRITICAL. Differentiate the two needsSwap rows by deficit instead.
        // p3: needs swap, critical, deficit 10 (empty tray, capacity 10)
        val pSwapBigDeficit = tray("t3", itemNumber = 3, productId = "p3", capacity = 10, currentStock = 0, product = product("p3"))
        // p4: needs swap, critical, deficit 6 (smaller than p3's, same severity)
        val pSwapSmallDeficit = tray("t4", itemNumber = 4, productId = "p4", capacity = 6, currentStock = 0, product = product("p4"))
        // p1: in stock (non-swap group), critical severity, deficit 10 — must
        // still sort after both swap rows despite matching p3's severity/deficit.
        val pInStock = tray("t1", itemNumber = 1, productId = "p1", capacity = 10, currentStock = 0, product = product("p1"))
        // p2: no stock (non-swap group), low severity, deficit 4 — sorts after
        // p1 within the non-swap group, since CRITICAL sorts before LOW.
        val pNonSwapLow = tray("t2", itemNumber = 2, productId = "p2", capacity = 10, currentStock = 6, minStock = 8, product = product("p2"))

        val summary = MachineDeficits.computeDeficits(
            listOf(pInStock, pNonSwapLow, pSwapBigDeficit, pSwapSmallDeficit),
            warehouseProductIds = setOf("p1"),
            hasWarehouses = true,
        )

        val order = summary.trayDeficits.map { it.productName }
        assertEquals(listOf("Product p3", "Product p4", "Product p1", "Product p2"), order)
    }
}
