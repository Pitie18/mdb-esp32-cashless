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
        product: Product? = null
    ) = Tray(
        id = id,
        machineId = machineId,
        itemNumber = itemNumber,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
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
        machineName: String = "Machine $machineId"
    ) = RefillMachine(
        machine = vm(machineId, machineName),
        trays = trays,
        isPacked = isPacked
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
    fun `applyTourInclusion keeps product-less trays in the tour with their initial fillAmount`() {
        val machine = refillMachine(
            "m1",
            listOf(refillTray(tray("t1", machineId = "m1", productId = null, capacity = 10, currentStock = 3), fillAmount = 7, isInTour = true)),
            isPacked = true
        )
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), emptyMap(), emptyMap())

        val t = result[0].trays[0]
        assertTrue(t.isInTour)
        assertEquals(7, t.fillAmount)
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
    fun `applyTourInclusion distributes a custom quantity proportionally across trays and clamps to capacity`() {
        // Two trays of product A, deficits 6 and 4 (total 10). Custom quantity is 5.
        // Expected proportional split: 6 * (5/10) = 3, 4 * (5/10) = 2.
        val trayLarge = tray("t1", machineId = "m1", productId = "A", capacity = 10, currentStock = 4) // deficit 6
        val traySmall = tray("t2", machineId = "m1", productId = "A", capacity = 5, currentStock = 1)  // deficit 4
        val machine = refillMachine(
            "m1",
            listOf(refillTray(trayLarge, fillAmount = 6), refillTray(traySmall, fillAmount = 4)),
            isPacked = true
        )
        val packedItems = mapOf("m1" to setOf("A"))
        val custom = mapOf("m1" to mapOf("A" to 5))
        val result = RefillTourLogic.applyTourInclusion(listOf(machine), packedItems, custom)

        val trays = result[0].trays.associateBy { it.tray.id }
        assertEquals(3, trays.getValue("t1").fillAmount)
        assertEquals(2, trays.getValue("t2").fillAmount)
        assertTrue(trays.getValue("t1").isInTour)
        assertTrue(trays.getValue("t2").isInTour)
    }

    @Test
    fun `applyTourInclusion clamps the proportional fill to remaining tray capacity`() {
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
