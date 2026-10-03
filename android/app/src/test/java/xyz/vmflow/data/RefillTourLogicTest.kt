package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Product
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.Tray
import xyz.vmflow.models.VendingMachineWithEmbedded
import xyz.vmflow.models.WarehousePositionGroup
import xyz.vmflow.models.WarehouseProductPosition

/**
 * Ported 1:1 from `ios/VMflow/ViewModels/RefillWizardViewModel.swift`:
 *   - warehouse-remainder helpers: L440-500
 *   - `combinedPackingList` incl. sorting: L498-612
 *   - quantity math (packingQuantity/maxPackingQuantity/displayQuantity):
 *     L780-940
 *   - pick order flatten (`fetchOrderedProductIds`): L1456-1545
 *   - `startTour` distribution + `deductWarehouseStock`: L1652-1800
 *
 * The `buildDeductions` tests are the point of this whole phase: the iOS
 * app over-charged the warehouse by ~334 units across 53 refill tours
 * because it deducted stock for every product a machine's trays could
 * hold, not just the products the driver actually packed.
 */
class RefillTourLogicTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun tray(
        id: String,
        machineId: String = "m1",
        itemNumber: Int = 1,
        productId: String? = "p1",
        capacity: Int = 10,
        currentStock: Int = 0,
        product: Product? = null,
        minStock: Int? = null,
        // Default: "top off whenever not full", so a fixture slot with room
        // needs a refill (FILL tier) unless a test says otherwise. Without a
        // threshold only a sold-out product would need one.
        fillWhenBelow: Int? = capacity
    ) = Tray(
        id = id,
        machineId = machineId,
        itemNumber = itemNumber,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
        minStock = minStock,
        fillWhenBelow = fillWhenBelow,
        products = product
    )

    private fun refillTray(t: Tray, fillAmount: Int = 0, isInTour: Boolean = true) =
        RefillTray(tray = t, fillAmount = fillAmount, isInTour = isInTour)

    private fun vm(id: String, name: String = "Machine $id") =
        VendingMachineWithEmbedded(id = id, name = name)

    private fun refillMachine(
        machineId: String,
        trays: List<RefillTray>,
        isPacked: Boolean = false,
        machineName: String = "Machine $machineId",
        refillableProductIds: Set<String>? = null
    ) = RefillMachine(
        machine = vm(machineId, machineName),
        trays = trays,
        isPacked = isPacked,
        refillableProductIds = refillableProductIds
    )

    // ─── packingQuantity ─────────────────────────────────────────────────

    @Test
    fun `packingQuantity sums deficits of trays with the product when no custom quantity is set`() {
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", productId = "A", capacity = 10, currentStock = 4)), // deficit 6
                refillTray(tray("t2", productId = "A", capacity = 5, currentStock = 1)),  // deficit 4
                refillTray(tray("t3", productId = "B", capacity = 10, currentStock = 0))  // different product
            )
        )
        assertEquals(10, RefillTourLogic.packingQuantity(machine, "A", emptyMap()))
    }

    @Test
    fun `packingQuantity returns the custom quantity when set, ignoring tray deficits`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", productId = "A", capacity = 10, currentStock = 0)))
        )
        val custom = mapOf("m1" to mapOf("A" to 3))
        assertEquals(3, RefillTourLogic.packingQuantity(machine, "A", custom))
    }

    // ─── committedQuantity / remainingWarehouseStock ────────────────────

    @Test
    fun `committedQuantity sums packingQuantity only across machines that packed the product`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), isPacked = true)
        val m2 = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "A", capacity = 6, currentStock = 0))), isPacked = false)
        val packedItems = mapOf("m1" to setOf("A"))
        assertEquals(10, RefillTourLogic.committedQuantity(listOf(m1, m2), "A", packedItems, emptyMap()))
    }

    @Test
    fun `remainingWarehouseStock subtracts committed quantity and floors at zero`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), isPacked = true)
        val packedItems = mapOf("m1" to setOf("A"))
        val stock = mapOf("A" to 4)
        assertEquals(0, RefillTourLogic.remainingWarehouseStock(listOf(m1), "A", packedItems, emptyMap(), stock))
    }

    @Test
    fun `remainingWarehouseStock is zero when the product has no warehouse stock entry`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A"))))
        assertEquals(0, RefillTourLogic.remainingWarehouseStock(listOf(m1), "A", emptyMap(), emptyMap(), emptyMap()))
    }

    // ─── maxPackingQuantity ──────────────────────────────────────────────

    @Test
    fun `maxPackingQuantity caps to tray capacity only when stock is not loaded`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))))
        val result = RefillTourLogic.maxPackingQuantity(
            listOf(m1), "m1", "A", emptyMap(), emptyMap(), warehouseStock = mapOf("A" to 1), stockLoaded = false
        )
        assertEquals(10, result)
    }

    @Test
    fun `maxPackingQuantity returns 0 when stock is loaded but the product is missing from it`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))))
        val result = RefillTourLogic.maxPackingQuantity(
            listOf(m1), "m1", "A", emptyMap(), emptyMap(), warehouseStock = mapOf("B" to 5), stockLoaded = true
        )
        assertEquals(0, result)
    }

    @Test
    fun `maxPackingQuantity for one machine subtracts what another packed machine already committed`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), isPacked = true)
        val m2 = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "A", capacity = 10, currentStock = 0))), isPacked = false)
        val packedItems = mapOf("m1" to setOf("A"))
        // Warehouse has 6 total; machine 1 already committed 10 (its own deficit) — wait,
        // packingQuantity(m1) defaults to its own tray deficit (10) unless custom-quantified.
        // Pin machine 1's committed quantity via a custom value to isolate the "other
        // machine's commitment" subtraction from machine 1's own deficit-driven default.
        val custom = mapOf("m1" to mapOf("A" to 4))
        val stock = mapOf("A" to 6)
        // Machine 2 sees: trayMax=10, available = max(0, 6 - 4) = 2 -> min(10, 2) = 2
        val result = RefillTourLogic.maxPackingQuantity(
            listOf(m1, m2), "m2", "A", packedItems, custom, warehouseStock = stock, stockLoaded = true
        )
        assertEquals(2, result)
    }

    // ─── displayQuantity ─────────────────────────────────────────────────

    @Test
    fun `displayQuantity caps to maxPackingQuantity for an unpacked machine`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))))
        val result = RefillTourLogic.displayQuantity(
            listOf(m1), "m1", "A", emptyMap(), emptyMap(), warehouseStock = mapOf("A" to 3), stockLoaded = true
        )
        assertEquals(3, result)
    }

    @Test
    fun `displayQuantity does not cap for a machine that already packed the product`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), isPacked = true)
        val packedItems = mapOf("m1" to setOf("A"))
        // Warehouse only has 3 left, but the machine already committed the full 10-unit deficit.
        val result = RefillTourLogic.displayQuantity(
            listOf(m1), "m1", "A", packedItems, emptyMap(), warehouseStock = mapOf("A" to 3), stockLoaded = true
        )
        assertEquals(10, result)
    }

    // ─── isOutOfStockForMachine ──────────────────────────────────────────

    @Test
    fun `isOutOfStockForMachine is false when stock has not been loaded`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A"))))
        val result = RefillTourLogic.isOutOfStockForMachine(
            listOf(m1), "m1", "A", emptyMap(), emptyMap(), warehouseStock = emptyMap(), stockLoaded = false
        )
        assertFalse(result)
    }

    @Test
    fun `isOutOfStockForMachine is false for a machine that already packed the product`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), isPacked = true)
        val packedItems = mapOf("m1" to setOf("A"))
        val result = RefillTourLogic.isOutOfStockForMachine(
            listOf(m1), "m1", "A", packedItems, emptyMap(), warehouseStock = mapOf("A" to 0), stockLoaded = true
        )
        assertFalse(result)
    }

    @Test
    fun `isOutOfStockForMachine is true when remaining stock is zero and the machine has not packed`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))))
        val result = RefillTourLogic.isOutOfStockForMachine(
            listOf(m1), "m1", "A", emptyMap(), emptyMap(), warehouseStock = mapOf("A" to 0), stockLoaded = true
        )
        assertTrue(result)
    }

    // ─── buildDeductions — the regression suite this phase exists for ────

    @Test
    fun `buildDeductions ignores products the user never packed`() {
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0)), // deficit 10
                refillTray(tray("t2", machineId = "m1", productId = "B", capacity = 5, currentStock = 0))   // deficit 5
            ),
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A")) // only A was packed
        val result = RefillTourLogic.buildDeductions(listOf(machine), packedItems, emptyMap())

        assertEquals(1, result.size)
        assertEquals(PackedDeduction("m1", "A", 10), result[0])
    }

    @Test
    fun `buildDeductions uses the reduced custom quantity, not the tray deficit`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))), // deficit 10
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 3))
        val result = RefillTourLogic.buildDeductions(listOf(machine), packedItems, custom)

        assertEquals(1, result.size)
        assertEquals(PackedDeduction("m1", "A", 3), result[0])
    }

    @Test
    fun `buildDeductions skips machines that were never packed`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))),
            isPacked = false
        )
        // packedItems has an entry, but the machine itself is not marked isPacked.
        val packedItems = mapOf("m1" to setOf("A"))
        val result = RefillTourLogic.buildDeductions(listOf(machine), packedItems, emptyMap())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildDeductions drops zero quantities`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0))),
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 0))
        val result = RefillTourLogic.buildDeductions(listOf(machine), packedItems, custom)

        assertTrue(result.isEmpty())
    }

    // ─── applyTourInclusion ──────────────────────────────────────────────

    @Test
    fun `applyTourInclusion excludes every tray of an unpacked machine`() {
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0), fillAmount = 10, isInTour = true),
                refillTray(tray("t2", machineId = "m1", productId = null, capacity = 10, currentStock = 0), fillAmount = 10, isInTour = true)
            ),
            isPacked = false
        )
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), emptyMap(), emptyMap())

        assertTrue(result[0].trays.all { !it.isInTour })
    }

    @Test
    fun `applyTourInclusion never refills an unassigned slot`() {
        // PWA parity: a slot without a product is not a product group, so the
        // refill step neither shows nor books it.
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = null, capacity = 10, currentStock = 3), fillAmount = 7, isInTour = true)),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), emptyMap(), emptyMap())

        val t = result[0].trays[0]
        assertFalse(t.isInTour)
        assertEquals(0, t.fillAmount)
    }

    @Test
    fun `applyTourInclusion skips a packed product whose group does not need a refill`() {
        // Product A: 0 + 9 of 20 with min_stock 1 per slot and no fill
        // threshold -> group 9 > 2, state OK. The empty slot is only a hint.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0, minStock = 1, fillWhenBelow = null), fillAmount = 10),
                refillTray(tray("t2", itemNumber = 2, productId = "A", capacity = 10, currentStock = 9, minStock = 1, fillWhenBelow = null), fillAmount = 1),
            ),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), mapOf("m1" to setOf("A")), emptyMap())

        assertTrue(result[0].trays.none { it.isInTour })
        assertTrue(result[0].trays.all { it.fillAmount == 0 })
    }

    @Test
    fun `applyTourInclusion spreads the group deficit and hides slots that receive nothing`() {
        // No pinned quantity: the packed amount is the group deficit (4 + 0),
        // the full slot gets 0 and is hidden from the refill step.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 6)),
                refillTray(tray("t2", itemNumber = 2, productId = "A", capacity = 10, currentStock = 10)),
            ),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), mapOf("m1" to setOf("A")), emptyMap())

        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(4, trays.getValue("t1").fillAmount)
        assertTrue(trays.getValue("t1").isInTour)
        assertEquals(0, trays.getValue("t2").fillAmount)
        assertFalse(trays.getValue("t2").isInTour)
    }

    @Test
    fun `applyTourInclusion hides a slot a short-packed product does not reach`() {
        // Pinned 3 across slots at 0 and 5 (capacity 10): all 3 go to the
        // empty slot, the other one gets nothing and is hidden.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("t2", itemNumber = 2, productId = "A", capacity = 10, currentStock = 5)),
            ),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(
            listOf(machine), mapOf("m1" to setOf("A")), mapOf("m1" to mapOf("A" to 3))
        )
        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(3, trays.getValue("t1").fillAmount)
        assertTrue(trays.getValue("t1").isInTour)
        assertFalse(trays.getValue("t2").isInTour)
    }

    @Test
    fun `applyTourInclusion zeroes and excludes trays of a product that was not packed`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = "B", capacity = 10, currentStock = 2), fillAmount = 8, isInTour = true)),
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A")) // B was never packed
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, emptyMap())

        val t = result[0].trays[0]
        assertFalse(t.isInTour)
        assertEquals(0, t.fillAmount)
    }

    @Test
    fun `applyTourInclusion distributes a custom quantity emptiest slot first and clamps to capacity`() {
        // Two trays of product A, deficits 6 and 4 (total 10). Custom quantity is 5.
        // Emptiest first: t2 (stock 1) takes its full headroom 4, t1 (stock 4) gets the last 1.
        val trayLarge = tray("t1", machineId = "m1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 4) // deficit 6
        val traySmall = tray("t2", machineId = "m1", itemNumber = 2, productId = "A", capacity = 5, currentStock = 1)  // deficit 4
        val machine = refillMachine(
            "m1",
            listOf(refillTray(trayLarge, fillAmount = 6), refillTray(traySmall, fillAmount = 4)),
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 5))
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, custom)

        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(1, trays.getValue("t1").fillAmount)
        assertEquals(4, trays.getValue("t2").fillAmount)
        assertTrue(trays.getValue("t1").isInTour)
        assertTrue(trays.getValue("t2").isInTour)
    }

    @Test
    fun `applyTourInclusion clamps the pinned fill to remaining tray capacity`() {
        // Single tray, deficit 4 (capacity 10, currentStock 6). Custom quantity 100 (way
        // more than the warehouse could realistically hand this tray) must still clamp to
        // the tray's own remaining capacity (4), not overflow it.
        val t = tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 6) // deficit 4
        val machine = refillMachine("m1", listOf(refillTray(t, fillAmount = 4)), isPacked = true)
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 100))
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, custom)

        assertEquals(4, result[0].trays[0].fillAmount)
    }

    // ─── applyTourInclusion ↔ buildDeductions: the ledger invariant ──────

    /** One distribution shape: a machine's tray deficits for product "A" plus the pinned quantity. */
    private data class DistributionCase(val label: String, val deficits: List<Int>, val pin: Int)

    /**
     * A machine whose trays all hold product "A", one tray per entry in
     * [deficits] with `capacity = deficit` and `currentStock = 0` (so each
     * tray's deficit *is* its headroom), ids `t1..tN` in list order.
     */
    private fun machineWithDeficits(deficits: List<Int>) = refillMachine(
        "m1",
        deficits.mapIndexed { index, deficit ->
            refillTray(
                tray(
                    "t${index + 1}",
                    machineId = "m1",
                    itemNumber = index + 1,
                    productId = "A",
                    capacity = deficit,
                    currentStock = 0
                ),
                fillAmount = deficit
            )
        },
        isPacked = true
    )

    /**
     * **The invariant this whole phase exists to protect**, in the one
     * direction nothing else in this suite checks: for a packed
     * machine-product pair, the units [RefillTourLogic.applyTourInclusion]
     * books into the machine's trays must equal the units
     * [RefillTourLogic.buildDeductions] charges the warehouse for.
     *
     * Per-tray rounding breaks it in both directions. Two trays of deficit 5
     * with a pinned 7 rounded to 4 + 4 — eight units into the machine, seven
     * off the warehouse, one invented. Three trays of deficit 3 with a pinned
     * 4 rounded to 1 + 1 + 1 — four charged, three filled. Asserting the
     * *sum* is the point: the individual amounts can be split several
     * defensible ways, the total cannot.
     *
     * The one legitimate exception is a pin that exceeds the trays' combined
     * headroom (the last case): no tray may be overfilled, so the fills sum
     * to the headroom and the machine is credited *less* than the warehouse is
     * charged. Hence the expectation is `min(charged, headroom)` — never more
     * than charged, in any shape.
     */
    @Test
    fun `applyTourInclusion books exactly the units buildDeductions charges`() {
        val cases = listOf(
            // 5 + 5 with a pinned 7: half-up per tray gave 4 + 4 = 8 for 7 charged.
            DistributionCase("two equal trays, reduced pin", listOf(5, 5), pin = 7),
            // 3 + 3 + 3 with a pinned 4: half-up per tray gave 1 + 1 + 1 = 3 for 4 charged.
            DistributionCase("pin smaller than the tray count", listOf(3, 3, 3), pin = 4),
            // Exact division — the case the pre-existing test pinned; must not regress.
            DistributionCase("exact division", listOf(6, 4), pin = 5),
            DistributionCase("pin equals the full deficit", listOf(6, 4), pin = 10),
            DistributionCase("pin of 1 across three trays", listOf(4, 4, 4), pin = 1),
            // More than every tray can hold together.
            DistributionCase("pin above the combined headroom", listOf(4, 6), pin = 20)
        )

        for (case in cases) {
            val machine = machineWithDeficits(case.deficits)
            val packedItems = mapOf("m1" to setOf("A"))
            val custom = mapOf("m1" to mapOf("A" to case.pin))

            val charged = RefillTourLogic.buildDeductions(listOf(machine), packedItems, custom)
                .filter { it.productId == "A" }
                .sumOf { it.quantity }
            val booked = RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, custom)[0]
                .trays
                .filter { it.tray.productId == "A" }
                .sumOf { it.fillAmount }

            val headroom = case.deficits.sum()
            assertEquals(
                "${case.label}: booked units must equal the charged quantity, capped by headroom",
                minOf(charged, headroom).toLong(),
                booked.toLong()
            )
            assertTrue(
                "${case.label}: booked $booked > charged $charged — units invented from nothing",
                booked <= charged
            )
            if (case.pin <= headroom) {
                assertEquals(
                    "${case.label}: machine credited exactly what the warehouse is charged",
                    charged.toLong(),
                    booked.toLong()
                )
            }
        }
    }

    @Test
    fun `applyTourInclusion fills the emptiest slot first so no selection stays sold out`() {
        // Slots 12/13/14 of product A at 4/0/9 (capacity 10 each), pinned 8:
        // everything goes into the empty slot 13 instead of topping up 12 and 14.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t12", itemNumber = 12, productId = "A", capacity = 10, currentStock = 4), fillAmount = 6),
                refillTray(tray("t13", itemNumber = 13, productId = "A", capacity = 10, currentStock = 0), fillAmount = 10),
                refillTray(tray("t14", itemNumber = 14, productId = "A", capacity = 10, currentStock = 9), fillAmount = 1),
            ),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(
            listOf(machine),
            mapOf("m1" to setOf("A")),
            mapOf("m1" to mapOf("A" to 8))
        )
        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(0, trays.getValue("t12").fillAmount)
        assertEquals(8, trays.getValue("t13").fillAmount)
        assertEquals(0, trays.getValue("t14").fillAmount)
    }

    @Test
    fun `applyTourInclusion breaks equal-stock ties by slot number`() {
        // Deficits 5 + 5 (both empty), pinned 7: slot 1 is filled first.
        val machine = machineWithDeficits(listOf(5, 5))
        val result = RefillTourLogic.applyTourInclusion(
            listOf(machine),
            mapOf("m1" to setOf("A")),
            mapOf("m1" to mapOf("A" to 7))
        )
        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(5, trays.getValue("t1").fillAmount)
        assertEquals(2, trays.getValue("t2").fillAmount)
    }

    @Test
    fun `applyTourInclusion distribution is deterministic regardless of tray order`() {
        // Equal stock means only the slot-number tiebreaker decides. Feeding
        // the trays in reverse must not move anything: a tour that
        // redistributes differently on a resume would book different numbers
        // for the same physical box of goods.
        val forward = machineWithDeficits(listOf(3, 3, 3))
        val reversed = forward.copy(trays = forward.trays.reversed())
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 4))

        fun fills(machine: RefillMachine) =
            RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, custom)[0]
                .trays
                .associate { it.tray.id to it.fillAmount }

        assertEquals(fills(forward), fills(reversed))
        assertEquals(mapOf("t1" to 3, "t2" to 1, "t3" to 0), fills(forward))
    }

    // ─── buildCombinedPackingList ────────────────────────────────────────

    @Test
    fun `buildCombinedPackingList aggregates deficits per product across machines into MachineNeed entries`() {
        val m1 = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 4, product = Product(id = "A", name = "Cola"))))
        )
        val m2 = refillMachine(
            "m2",
            listOf(refillTray(tray("t2", machineId = "m2", productId = "A", capacity = 8, currentStock = 2, product = Product(id = "A", name = "Cola"))))
        )
        val list = RefillTourLogic.buildCombinedPackingList(listOf(m1, m2), emptyMap())

        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("A", item.productId)
        assertEquals(6 + 6, item.totalQuantity) // deficits: 10-4=6, 8-2=6
        assertEquals(2, item.machineNeeds.size)
    }

    @Test
    fun `buildCombinedPackingList sorts machineNeeds within a product by machine name`() {
        val zeta = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 5, currentStock = 0))), machineName = "Zeta")
        val alpha = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "A", capacity = 5, currentStock = 0))), machineName = "Alpha")
        // Machines added in "Zeta, then Alpha" order; the packing list groups
        // by product across machines regardless of insertion order, so the
        // per-product machineNeeds list must itself be sorted by name rather
        // than reflecting whatever order machines happened to be iterated in.
        val list = RefillTourLogic.buildCombinedPackingList(listOf(zeta, alpha), emptyMap())

        assertEquals(listOf("Alpha", "Zeta"), list[0].machineNeeds.map { it.machineName })
    }

    @Test
    fun `buildCombinedPackingList leaves productName null when the tray has no product name`() {
        // No synthesized "Slot N" fallback here — that's a user-visible string, and pure
        // logic must not bake one in. The model field is nullable; the UI layer resolves
        // it to the localized R.string.machine_card_unassigned_slot fallback instead.
        val m1 = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", itemNumber = 7, productId = "A", capacity = 10, currentStock = 0, product = null)))
        )
        val list = RefillTourLogic.buildCombinedPackingList(listOf(m1), emptyMap())

        assertEquals(null, list[0].productName)
    }

    @Test
    fun `buildCombinedPackingList without a pick order sorts by totalQuantity descending`() {
        val big = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0, product = Product(id = "A", name = "A-Product")))))
        val small = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "B", capacity = 3, currentStock = 0, product = Product(id = "B", name = "B-Product")))))
        val list = RefillTourLogic.buildCombinedPackingList(listOf(big, small), emptyMap())

        assertEquals(listOf("A", "B"), list.map { it.productId })
    }

    @Test
    fun `buildCombinedPackingList ties on quantity and name are separated by productId`() {
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "zzz", capacity = 5, currentStock = 0, product = Product(id = "zzz", name = "Same Name")))))
        val m2 = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "aaa", capacity = 5, currentStock = 0, product = Product(id = "aaa", name = "Same Name")))))
        val list = RefillTourLogic.buildCombinedPackingList(listOf(m1, m2), emptyMap())

        // Equal totalQuantity (5) and equal name -> productId ascending is the final tiebreaker.
        assertEquals(listOf("aaa", "zzz"), list.map { it.productId })
    }

    @Test
    fun `buildCombinedPackingList sorting is a total order — reversed input yields the same order`() {
        // Grouping uses a LinkedHashMap, so feeding the same machines twice in the same
        // order would pass even without any tiebreaker (insertion order alone would do it).
        // Reversing the input list is what actually exercises the comparator: if the
        // productId tiebreaker were missing, insertion order would leak through and the
        // reversed run would come back reversed too.
        val m1 = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 0, product = Product(id = "A", name = "Same")))))
        val m2 = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "B", capacity = 10, currentStock = 0, product = Product(id = "B", name = "Same")))))
        val m3 = refillMachine("m3", listOf(refillTray(tray("t3", machineId = "m3", productId = "C", capacity = 10, currentStock = 0, product = Product(id = "C", name = "Same")))))

        val forward = RefillTourLogic.buildCombinedPackingList(listOf(m1, m2, m3), emptyMap()).map { it.productId }
        val reversed = RefillTourLogic.buildCombinedPackingList(listOf(m3, m2, m1), emptyMap()).map { it.productId }

        assertEquals(forward, reversed)
        assertEquals(listOf("A", "B", "C"), forward)
    }

    @Test
    fun `buildCombinedPackingList name tiebreaker uses locale-aware collation, matching iOS`() {
        // Ordinal String.CASE_INSENSITIVE_ORDER sorts "Ö" (U+00D6) after every plain ASCII
        // letter, so "Öl" would land after "Zucker". A German collator treats "Ö" as a
        // variant near "O", sorting "Öl" before "Zucker" — matching iOS's
        // localizedCaseInsensitiveCompare. Equal totalQuantity (5) on both forces the name
        // comparator to decide, so this pins the collation choice, not the quantity sort.
        val oel = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "A", capacity = 5, currentStock = 0, product = Product(id = "A", name = "Öl")))))
        val zucker = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "B", capacity = 5, currentStock = 0, product = Product(id = "B", name = "Zucker")))))
        val germanCollator = java.text.Collator.getInstance(java.util.Locale.GERMANY).apply { strength = java.text.Collator.SECONDARY }
        val nameComparator: Comparator<String?> = nullsLast(Comparator(germanCollator::compare))

        val list = RefillTourLogic.buildCombinedPackingList(listOf(oel, zucker), emptyMap(), nameComparator)

        assertEquals(listOf("A", "B"), list.map { it.productId })
    }

    @Test
    fun `buildCombinedPackingList with a pick order sorts positioned products before unpositioned ones`() {
        val positioned = refillMachine("m1", listOf(refillTray(tray("t1", machineId = "m1", productId = "late", capacity = 100, currentStock = 0, product = Product(id = "late", name = "Late")))))
        val unpositioned = refillMachine("m2", listOf(refillTray(tray("t2", machineId = "m2", productId = "unpositioned", capacity = 1, currentStock = 0, product = Product(id = "unpositioned", name = "Unpositioned")))))
        // "late" has a huge deficit (100) but a late pick-order position (5); "unpositioned"
        // has a tiny deficit (1) and no pick-order entry at all. Without a pick order the
        // huge deficit would sort first; with one, position wins.
        val pickOrder = mapOf("late" to 5)
        val list = RefillTourLogic.buildCombinedPackingList(listOf(positioned, unpositioned), pickOrder)

        assertEquals(listOf("late", "unpositioned"), list.map { it.productId })
    }

    // ─── sortByVisitOrder ────────────────────────────────────────────────

    @Test
    fun `sortByVisitOrder puts a machine with a sold-out product before a top-off-only one`() {
        // "few" has the larger total deficit but no sold-out product (its
        // empty slot's product is still in the other slot), so the sold-out
        // count must outrank the deficit — same precedence as iOS.
        val many = refillMachine(
            "many",
            listOf(
                refillTray(tray("t1", machineId = "many", capacity = 5, currentStock = 0)),
                refillTray(tray("t2", machineId = "many", capacity = 5, currentStock = 0))
            )
        )
        val few = refillMachine(
            "few",
            listOf(
                refillTray(tray("t3", machineId = "few", capacity = 50, currentStock = 0)),
                refillTray(tray("t4", machineId = "few", capacity = 50, currentStock = 49))
            )
        )

        val result = RefillTourLogic.sortByVisitOrder(listOf(few, many))
        assertEquals(listOf("many", "few"), result.map { it.machine.id })
    }

    @Test
    fun `sortByVisitOrder falls back to total deficit descending at equal tier and low count`() {
        // The ids are chosen so the id tiebreaker would put them the OTHER way
        // round ("a_small" < "z_big"). Without the totalDeficit key this test
        // fails instead of passing by coincidence.
        val small = refillMachine(
            "a_small",
            listOf(refillTray(tray("t1", machineId = "a_small", capacity = 10, currentStock = 8)))
        )
        val big = refillMachine(
            "z_big",
            listOf(refillTray(tray("t2", machineId = "z_big", capacity = 10, currentStock = 2)))
        )

        val result = RefillTourLogic.sortByVisitOrder(listOf(small, big))
        assertEquals(listOf("z_big", "a_small"), result.map { it.machine.id })
    }

    @Test
    fun `sortByVisitOrder breaks ties by machine id so the order never jumps`() {
        fun equallyUrgent(id: String) = refillMachine(
            id,
            listOf(refillTray(tray("t-$id", machineId = id, capacity = 10, currentStock = 4)))
        )
        val a = equallyUrgent("aaa")
        val b = equallyUrgent("bbb")
        val c = equallyUrgent("ccc")

        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            RefillTourLogic.sortByVisitOrder(listOf(c, a, b)).map { it.machine.id }
        )
        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            RefillTourLogic.sortByVisitOrder(listOf(b, c, a)).map { it.machine.id }
        )
    }

    @Test
    fun `sortByVisitOrder keeps unpacked machines in the list`() {
        val packed = refillMachine(
            "packed",
            listOf(refillTray(tray("t1", machineId = "packed", capacity = 10, currentStock = 9))),
            isPacked = true
        )
        val unpacked = refillMachine(
            "unpacked",
            listOf(refillTray(tray("t2", machineId = "unpacked", capacity = 10, currentStock = 0)))
        )

        val result = RefillTourLogic.sortByVisitOrder(listOf(packed, unpacked))
        assertEquals(listOf("unpacked", "packed"), result.map { it.machine.id })
    }

    @Test
    fun `sortByVisitOrder does not rank an empty slot whose product is stocked elsewhere as sold out`() {
        // "slots" has two empty slots, but product A still sits in a third one;
        // "product" has a single product sold out everywhere. Only the latter
        // counts as sold out, despite "slots" having the larger deficit.
        val slots = refillMachine(
            "a_slots",
            listOf(
                refillTray(tray("s1", machineId = "a_slots", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("s2", machineId = "a_slots", itemNumber = 2, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("s3", machineId = "a_slots", itemNumber = 3, productId = "A", capacity = 10, currentStock = 5)),
            )
        )
        val product = refillMachine(
            "z_product",
            listOf(refillTray(tray("p1", machineId = "z_product", productId = "B", capacity = 5, currentStock = 0)))
        )

        val result = RefillTourLogic.sortByVisitOrder(listOf(slots, product))
        assertEquals(listOf("z_product", "a_slots"), result.map { it.machine.id })
    }

    @Test
    fun `sortByVisitOrder ranks critical, low, fill and then by sold-out plus low count`() {
        fun m(id: String, vararg trays: Tray) = refillMachine(id, trays.map { refillTray(it) })
        // fill only
        val fill = m("a_fill", tray("f1", machineId = "a_fill", capacity = 10, currentStock = 5))
        // low: stock 2 at/below min_stock 3
        val low = m("b_low", tray("l1", machineId = "b_low", capacity = 10, currentStock = 2, minStock = 3))
        // critical with one sold-out product
        val critOne = m("c_crit1", tray("c1", machineId = "c_crit1", productId = "A", capacity = 10, currentStock = 0))
        // critical with a sold-out and a low product -> ranks above critOne
        val critTwo = m(
            "d_crit2",
            tray("d1", machineId = "d_crit2", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0),
            tray("d2", machineId = "d_crit2", itemNumber = 2, productId = "B", capacity = 10, currentStock = 1, minStock = 2),
        )

        val result = RefillTourLogic.sortByVisitOrder(listOf(fill, low, critOne, critTwo))
        assertEquals(listOf("d_crit2", "c_crit1", "b_low", "a_fill"), result.map { it.machine.id })
    }

    // ─── tour scope (PWA initTour / effectiveStockHealth) ────────────────

    @Test
    fun `machineTier ignores products the warehouse cannot refill`() {
        // A is sold out but has no warehouse stock; B only needs a top-off and is refillable.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("t1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("t2", itemNumber = 2, productId = "B", capacity = 10, currentStock = 5)),
            ),
            refillableProductIds = setOf("B")
        )
        assertEquals(MachineStockTier.FILL, RefillTourLogic.machineTier(machine))
        assertEquals(0, RefillTourLogic.lowOrEmptyProductCount(machine))
    }

    @Test
    fun `tourMachines keeps machines with a refillable need, including fill-only ones`() {
        val vms = listOf(vm("crit"), vm("fill"), vm("nostock"), vm("ok"), vm("unassigned"), vm("empty"))
        val trays = listOf(
            tray("c", machineId = "crit", productId = "A", currentStock = 0),
            tray("f", machineId = "fill", productId = "A", currentStock = 5),
            // sold out, but the warehouse has none of C
            tray("n", machineId = "nostock", productId = "C", currentStock = 0),
            tray("o", machineId = "ok", productId = "A", currentStock = 10),
            // an empty unassigned slot never puts a machine on the tour
            tray("u", machineId = "unassigned", productId = null, currentStock = 0),
        )
        val all = RefillTourLogic.buildRefillMachines(vms, trays, warehouseProductIds = setOf("A"), hasWarehouses = true)
        assertEquals(listOf("crit", "fill", "nostock", "ok", "unassigned"), all.map { it.machine.id })

        val tour = RefillTourLogic.tourMachines(all)
        assertEquals(listOf("crit", "fill"), tour.map { it.machine.id })
    }

    @Test
    fun `without warehouse stock every product counts as refillable`() {
        val trays = listOf(tray("n", machineId = "m1", productId = "C", currentStock = 0))
        val all = RefillTourLogic.buildRefillMachines(listOf(vm("m1")), trays, emptySet(), hasWarehouses = false)
        assertEquals(null, all.single().refillableProductIds)
        assertEquals(listOf("m1"), RefillTourLogic.tourMachines(all).map { it.machine.id })
    }

    @Test
    fun `buildRefillMachines starts slots outside a group needing refill at zero`() {
        val trays = listOf(
            // A: 0 + 9 with min 1 each and no fill threshold -> OK group, empty slot is only a hint
            tray("a1", machineId = "m1", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0, minStock = 1, fillWhenBelow = null),
            tray("a2", machineId = "m1", itemNumber = 2, productId = "A", capacity = 10, currentStock = 9, minStock = 1, fillWhenBelow = null),
            tray("b1", machineId = "m1", itemNumber = 3, productId = "B", capacity = 10, currentStock = 0),
            tray("u1", machineId = "m1", itemNumber = 4, productId = null, capacity = 10, currentStock = 0),
        )
        val machine = RefillTourLogic.buildRefillMachines(listOf(vm("m1")), trays, emptySet(), false).single()
        assertEquals(mapOf("a1" to 0, "a2" to 0, "b1" to 10, "u1" to 0), machine.trays.associate { it.tray.id to it.fillAmount })
    }

    @Test
    fun `buildCombinedPackingList lists every product group needing refill with its group deficit`() {
        // A (no warehouse stock) is still listed; D is fine and is not; the
        // unassigned slot is not; B's two slots are one need with the group deficit.
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("a", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("b1", itemNumber = 2, productId = "B", capacity = 10, currentStock = 0)),
                refillTray(tray("b2", itemNumber = 3, productId = "B", capacity = 10, currentStock = 7)),
                refillTray(tray("d", itemNumber = 4, productId = "D", capacity = 10, currentStock = 8, fillWhenBelow = null)),
                refillTray(tray("u", itemNumber = 5, productId = null, capacity = 10, currentStock = 0)),
            ),
            refillableProductIds = setOf("B")
        )
        val list = RefillTourLogic.buildCombinedPackingList(listOf(machine), emptyMap())
        assertEquals(mapOf("B" to 13, "A" to 10), list.associate { it.productId to it.totalQuantity })
        assertEquals(20, list.first { it.productId == "B" }.machineNeeds.single().capacity)
    }

    @Test
    fun `displayTier downgrades to OK when the selected warehouse has none of the needed products`() {
        val machine = refillMachine(
            "m1",
            listOf(
                refillTray(tray("a", itemNumber = 1, productId = "A", capacity = 10, currentStock = 0)),
                refillTray(tray("b", itemNumber = 2, productId = "B", capacity = 10, currentStock = 4)),
            )
        )
        assertEquals(MachineStockTier.CRITICAL, RefillTourLogic.displayTier(machine, emptyMap(), stockLoaded = false))
        assertEquals(MachineStockTier.OK, RefillTourLogic.displayTier(machine, mapOf("X" to 5), stockLoaded = true))
        // One listed product in stock is enough to keep the tier.
        assertEquals(MachineStockTier.CRITICAL, RefillTourLogic.displayTier(machine, mapOf("B" to 1), stockLoaded = true))
    }

    // ─── flattenPickOrder ────────────────────────────────────────────────

    @Test
    fun `flattenPickOrder flattens nested groups depth-first ordered by sortOrder per level`() {
        // root (sortOrder 0)
        //   child (sortOrder 0) -> products: p2
        //   child (sortOrder 1) -> products: p3
        // products directly in root: p1
        val root = WarehousePositionGroup(id = "root", parentId = null, sortOrder = 0)
        val child0 = WarehousePositionGroup(id = "child0", parentId = "root", sortOrder = 0)
        val child1 = WarehousePositionGroup(id = "child1", parentId = "root", sortOrder = 1)
        val groups = listOf(root, child0, child1)

        val positions = listOf(
            WarehouseProductPosition(productId = "p1", sortOrder = 0, groupId = "root"),
            WarehouseProductPosition(productId = "p2", sortOrder = 0, groupId = "child0"),
            WarehouseProductPosition(productId = "p3", sortOrder = 0, groupId = "child1")
        )

        val result = RefillTourLogic.flattenPickOrder(groups, positions)
        assertEquals(listOf("p1", "p2", "p3"), result)
    }

    @Test
    fun `flattenPickOrder appends ungrouped positions at the end`() {
        val root = WarehousePositionGroup(id = "root", parentId = null, sortOrder = 0)
        val positions = listOf(
            WarehouseProductPosition(productId = "grouped", sortOrder = 1, groupId = "root"),
            WarehouseProductPosition(productId = "ungrouped", sortOrder = 0, groupId = null)
        )

        val result = RefillTourLogic.flattenPickOrder(listOf(root), positions)
        assertEquals(listOf("grouped", "ungrouped"), result)
    }

    @Test
    fun `flattenPickOrder treats a group with an unknown parentId as a root`() {
        val orphan = WarehousePositionGroup(id = "orphan", parentId = "does-not-exist", sortOrder = 0)
        val positions = listOf(WarehouseProductPosition(productId = "p1", sortOrder = 0, groupId = "orphan"))

        val result = RefillTourLogic.flattenPickOrder(listOf(orphan), positions)
        assertEquals(listOf("p1"), result)
    }
}
