package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray
import xyz.vmflow.models.VendingMachineWithEmbedded
import xyz.vmflow.models.WarehouseStockBatch

/**
 * Ported 1:1 from `ios/VMflow/ViewModels/RefillWizardViewModel.swift`
 * L1258-1351 (the suggestion-detection block inside `loadData`).
 */
class RefillReviewLogicTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun product(id: String, discontinued: Boolean = false, name: String = "Product $id") =
        Product(id = id, name = name, discontinued = discontinued)

    private fun tray(
        id: String,
        machineId: String = "m1",
        itemNumber: Int = 1,
        productId: String? = "p1",
        capacity: Int = 10,
        currentStock: Int = 0,
        product: Product? = null,
    ) = Tray(
        id = id,
        machineId = machineId,
        itemNumber = itemNumber,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
        products = product,
    )

    private fun vm(id: String, name: String = "Machine $id") =
        VendingMachineWithEmbedded(id = id, name = name)

    private fun batch(
        productId: String,
        quantity: Int,
        expirationDate: String? = null,
        warehouseId: String = "w1",
        id: String = "b-${productId}-$quantity-${expirationDate ?: "none"}",
    ) = WarehouseStockBatch(
        id = id,
        warehouseId = warehouseId,
        productId = productId,
        quantity = quantity,
        expirationDate = expirationDate,
    )

    // ─── buildReplacementSuggestions: priority order ──────────────────────

    @Test
    fun `expired beats no-stock when the tray is empty and the warehouse has none`() {
        // The discriminating case for rule (b) over rule (c): every input that
        // rule (c) needs is also true here, so this test fails if the two
        // branches are swapped. Without it, the branch order is unpinned.
        val p = product("p1")
        val trays = mapOf("m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = p)))

        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines = listOf(vm("m1")),
            traysByMachine = trays,
            stockedProductIds = emptySet(),
            expiredProductIds = setOf("p1"),
            stockLoaded = true,
        )

        assertEquals(1, result.size)
        assertEquals(ReplacementReason.EXPIRED, result.first().reason)
    }

    @Test
    fun `a batch with an empty-string date is not treated as expired`() {
        val result = RefillReviewLogic.expiredProductIds(
            batches = listOf(
                batch("p1", quantity = 5, expirationDate = "2020-01-01"),
                batch("p1", quantity = 5, expirationDate = ""),
            ),
            today = "2026-08-24",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a tray whose product row is missing still yields a suggestion by id`() {
        // `products` is null (a narrower select, or a deleted product row) while
        // productId is set: the id-based rules must still fire, and the name and
        // image come through as null for the UI to resolve.
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = null))
        )

        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines = listOf(vm("m1")),
            traysByMachine = trays,
            stockedProductIds = emptySet(),
            expiredProductIds = emptySet(),
            stockLoaded = true,
        )

        assertEquals(1, result.size)
        assertEquals(ReplacementReason.NO_STOCK, result.first().reason)
        assertEquals(null, result.first().currentProductName)
    }

    @Test
    fun `discontinued product with zero tray stock is DISCONTINUED`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = product("p1", discontinued = true)))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.DISCONTINUED, result[0].reason)
    }

    @Test
    fun `discontinued product with remaining tray stock produces no suggestion`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 3, product = product("p1", discontinued = true)))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `expired product is EXPIRED even with a full tray`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(
                tray("t1", productId = "p1", capacity = 10, currentStock = 10, product = product("p1"))
            )
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), setOf("p1"), stockLoaded = true)
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.EXPIRED, result[0].reason)
    }

    @Test
    fun `discontinued and empty takes priority over expired`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(
                tray("t1", productId = "p1", currentStock = 0, product = product("p1", discontinued = true))
            )
        )
        // Product is both discontinued+empty AND expired: rule (a) must win over rule (b).
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), setOf("p1"), stockLoaded = true)
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.DISCONTINUED, result[0].reason)
    }

    @Test
    fun `zero stock and no warehouse stock is NO_STOCK`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = product("p1")))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.NO_STOCK, result[0].reason)
    }

    @Test
    fun `zero stock but warehouse still has stock produces no suggestion`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = product("p1")))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, setOf("p1"), emptySet(), stockLoaded = true)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `tray without a product is UNASSIGNED`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = null, currentStock = 0))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.UNASSIGNED, result[0].reason)
    }

    @Test
    fun `tray without a product and without warehouse stock is UNASSIGNED not NO_STOCK`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = null, currentStock = 0))
        )
        // stockedProductIds/expiredProductIds are irrelevant once productId is null: this
        // must never fall through to NO_STOCK.
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertEquals(ReplacementReason.UNASSIGNED, result[0].reason)
    }

    @Test
    fun `machine with no trays produces nothing`() {
        val machines = listOf(vm("m1"))
        val trays = emptyMap<String, List<Tray>>()
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), emptySet(), stockLoaded = true)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `each tray produces at most one suggestion`() {
        // Discontinued AND out of warehouse stock AND expired: only one reason should surface.
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(
                tray("t1", productId = "p1", currentStock = 0, product = product("p1", discontinued = true))
            )
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(machines, trays, emptySet(), setOf("p1"), stockLoaded = true)
        assertEquals(1, result.size)
    }

    // ─── buildReplacementSuggestions: stockLoaded ──────────────────────────

    @Test
    fun `empty slot produces no suggestion when stock has not loaded`() {
        // A failed or not-yet-run warehouse-stock query leaves stockedProductIds
        // empty — indistinguishable from "the warehouse stocks nothing" unless
        // stockLoaded says which. Without the suppression this fires NO_STOCK
        // for every empty slot in the fleet on one transient failure; this test
        // fails if the `stockLoaded &&` guard on rule 3 is removed.
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = product("p1")))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines, trays, emptySet(), emptySet(), stockLoaded = false
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `discontinued and empty still suggests when stock has not loaded`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(
                tray("t1", productId = "p1", currentStock = 0, product = product("p1", discontinued = true))
            )
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines, trays, emptySet(), emptySet(), stockLoaded = false
        )
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.DISCONTINUED, result[0].reason)
    }

    @Test
    fun `expired product still suggests when stock has not loaded`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = "p1", currentStock = 0, product = product("p1")))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines, trays, emptySet(), setOf("p1"), stockLoaded = false
        )
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.EXPIRED, result[0].reason)
    }

    @Test
    fun `unassigned slot still suggests when stock has not loaded`() {
        val machines = listOf(vm("m1"))
        val trays = mapOf(
            "m1" to listOf(tray("t1", productId = null, currentStock = 0))
        )
        val result = RefillReviewLogic.buildReplacementSuggestions(
            machines, trays, emptySet(), emptySet(), stockLoaded = false
        )
        assertEquals(1, result.size)
        assertEquals(ReplacementReason.UNASSIGNED, result[0].reason)
    }

    // ─── buildReplacementSuggestions: deterministic order ─────────────────

    @Test
    fun `result order is deterministic by machine name then slot number, independent of input order`() {
        val machineA = vm("mA", name = "Alpha")
        val machineZ = vm("mZ", name = "Zulu")
        val traysA = listOf(
            tray("t-a-2", machineId = "mA", itemNumber = 2, productId = null),
            tray("t-a-1", machineId = "mA", itemNumber = 1, productId = null),
        )
        val traysZ = listOf(
            tray("t-z-1", machineId = "mZ", itemNumber = 1, productId = null),
        )

        val forward = RefillReviewLogic.buildReplacementSuggestions(
            listOf(machineA, machineZ),
            mapOf("mA" to traysA, "mZ" to traysZ),
            emptySet(),
            emptySet(),
            stockLoaded = true,
        )
        val reversed = RefillReviewLogic.buildReplacementSuggestions(
            listOf(machineZ, machineA),
            mapOf("mZ" to traysZ, "mA" to traysA.reversed()),
            emptySet(),
            emptySet(),
            stockLoaded = true,
        )

        val expectedOrder = listOf("t-a-1", "t-a-2", "t-z-1")
        assertEquals(expectedOrder, forward.map { it.trayId })
        assertEquals(expectedOrder, reversed.map { it.trayId })
    }

    // ─── expiredProductIds ─────────────────────────────────────────────────

    @Test
    fun `product with all past-dated positive-quantity batches is expired`() {
        val batches = listOf(
            batch("p1", quantity = 5, expirationDate = "2026-01-01"),
            batch("p1", quantity = 2, expirationDate = "2026-02-01"),
        )
        val result = RefillReviewLogic.expiredProductIds(batches, today = "2026-08-24")
        assertEquals(setOf("p1"), result)
    }

    @Test
    fun `one dateless positive-quantity batch excludes the product from expired`() {
        val batches = listOf(
            batch("p1", quantity = 5, expirationDate = "2026-01-01"),
            batch("p1", quantity = 3, expirationDate = null),
        )
        val result = RefillReviewLogic.expiredProductIds(batches, today = "2026-08-24")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `one future-dated positive-quantity batch excludes the product from expired`() {
        val batches = listOf(
            batch("p1", quantity = 5, expirationDate = "2026-01-01"),
            batch("p1", quantity = 3, expirationDate = "2099-01-01"),
        )
        val result = RefillReviewLogic.expiredProductIds(batches, today = "2026-08-24")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `zero-quantity dateless batch does not block expiry`() {
        val batches = listOf(
            batch("p1", quantity = 5, expirationDate = "2026-01-01"),
            batch("p1", quantity = 0, expirationDate = null),
        )
        val result = RefillReviewLogic.expiredProductIds(batches, today = "2026-08-24")
        assertEquals(setOf("p1"), result)
    }

    @Test
    fun `batch dated exactly today is not expired`() {
        val batches = listOf(batch("p1", quantity = 5, expirationDate = "2026-08-24"))
        val result = RefillReviewLogic.expiredProductIds(batches, today = "2026-08-24")
        assertTrue(result.isEmpty())
    }
}
