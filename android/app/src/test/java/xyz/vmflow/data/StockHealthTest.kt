package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Tray

/**
 * Parity tests for [StockHealth], the port of the PWA's
 * `management-frontend/app/lib/stock-health.ts`. The cases mirror
 * `app/lib/__tests__/stock-health.test.ts` plus the two divergences this
 * port fixed: unassigned slots and non-refillable products used to make a
 * machine "critical" on Android but not in the PWA. The grouping cases
 * (one product in several slots) mirror the TS `groupTraysByProduct`,
 * `groupNeedsRefill`, `distributeAcrossSlots` and "one product in several
 * slots" describe blocks.
 */
class StockHealthTest {

    private val STOCKED = "p-stocked"
    private val MISSING = "p-missing"
    private val warehouse = setOf(STOCKED)

    private fun tray(
        machineId: String = "m1",
        productId: String? = STOCKED,
        capacity: Int = 10,
        currentStock: Int = 0,
        minStock: Int? = 2,
        fillWhenBelow: Int? = 5,
        itemNumber: Int = 1,
    ) = Tray(
        id = "t-$machineId-$itemNumber-$currentStock-$productId",
        machineId = machineId,
        itemNumber = itemNumber,
        productId = productId,
        capacity = capacity,
        currentStock = currentStock,
        minStock = minStock,
        fillWhenBelow = fillWhenBelow,
    )

    private fun tierOf(trays: List<Tray>, hasWarehouses: Boolean = true) =
        StockHealth.summaries(trays, warehouse, hasWarehouses)["m1"]?.tier

    // ── classifyTray ────────────────────────────────────────────────────

    @Test
    fun `empty tray is critical even without a min_stock threshold`() {
        assertEquals(TrayStockState.CRITICAL, StockHealth.classifyTray(0, null, null))
    }

    @Test
    fun `tray at min_stock is low`() {
        assertEquals(TrayStockState.LOW, StockHealth.classifyTray(5, 5, 10))
    }

    @Test
    fun `tray below fill_when_below but above min_stock is fill`() {
        assertEquals(TrayStockState.FILL, StockHealth.classifyTray(8, 5, 10))
    }

    @Test
    fun `disabled thresholds leave a tray ok`() {
        assertEquals(TrayStockState.OK, StockHealth.classifyTray(3, 0, 0))
    }

    // ── summaries ───────────────────────────────────────────────────────

    @Test
    fun `unassigned empty slot does not make a machine critical`() {
        assertEquals(MachineStockTier.OK, tierOf(listOf(tray(productId = null))))
    }

    @Test
    fun `empty tray the warehouse cannot refill is a swap candidate not an alert`() {
        val summary = StockHealth.summaries(listOf(tray(productId = MISSING)), warehouse, true)["m1"]!!
        assertEquals(MachineStockTier.OK, summary.tier)
        assertEquals(1, summary.noStockEmptyCount)
    }

    @Test
    fun `empty tray the warehouse can refill is critical`() {
        assertEquals(MachineStockTier.CRITICAL, tierOf(listOf(tray())))
    }

    @Test
    fun `without warehouse data every product counts as refillable`() {
        assertEquals(
            MachineStockTier.CRITICAL,
            StockHealth.summaries(listOf(tray(productId = MISSING)), emptySet(), false)["m1"]?.tier,
        )
    }

    @Test
    fun `a fill_when_below breach alone is the fill tier`() {
        assertEquals(MachineStockTier.FILL, tierOf(listOf(tray(currentStock = 5))))
    }

    @Test
    fun `a fill tray already at capacity is ignored`() {
        assertEquals(
            MachineStockTier.OK,
            tierOf(listOf(tray(currentStock = 10, minStock = 2, fillWhenBelow = 10))),
        )
    }

    @Test
    fun `percent covers every tray including unassigned ones`() {
        val summary = StockHealth.summaries(
            listOf(tray(currentStock = 5), tray(productId = null, currentStock = 0)),
            warehouse,
            true,
        )["m1"]!!
        assertEquals(25, summary.percent)
        assertEquals(100, StockHealth.summaries(listOf(tray(capacity = 0)), warehouse, true)["m1"]!!.percent)
    }

    @Test
    fun `prioritizes critical over low and fill on the same machine`() {
        val other = "p-other"
        val trays = listOf(
            tray(productId = STOCKED, capacity = 20, currentStock = 0, minStock = 5, fillWhenBelow = 10),
            tray(productId = other, capacity = 20, currentStock = 8, minStock = 5, fillWhenBelow = 10, itemNumber = 2),
        )
        assertEquals(
            MachineStockTier.CRITICAL,
            StockHealth.summaries(trays, setOf(STOCKED, other), true)["m1"]?.tier,
        )
    }

    // ── groupTraysByProduct ─────────────────────────────────────────────

    private val COLA = "cola"
    private fun cola(itemNumber: Int, currentStock: Int, machineId: String = "m1", productId: String? = COLA) =
        tray(machineId = machineId, productId = productId, capacity = 10, currentStock = currentStock,
            minStock = 2, fillWhenBelow = 5, itemNumber = itemNumber)

    @Test
    fun `sums stock, capacity and both thresholds across the slots of one product`() {
        val g = StockHealth.groupTraysByProduct(listOf(cola(12, 0), cola(13, 2), cola(14, 9))).single()
        assertEquals("m1", g.machineId)
        assertEquals(COLA, g.productId)
        assertEquals(11, g.currentStock)
        assertEquals(30, g.capacity)
        assertEquals(6, g.minStock)
        assertEquals(15, g.fillWhenBelow)
        assertEquals(19, g.deficit)
        assertEquals(listOf(12, 13, 14), g.trays.map { it.itemNumber })
    }

    @Test
    fun `classifies the product, not the slot - an empty slot next to full ones is not critical`() {
        val g = StockHealth.groupTraysByProduct(listOf(cola(12, 0), cola(13, 2), cola(14, 9))).single()
        assertEquals(TrayStockState.FILL, g.state)
        assertEquals(1, g.emptySlots)
    }

    @Test
    fun `is critical only when the product is sold out in every slot`() {
        val g = StockHealth.groupTraysByProduct(listOf(cola(10, 0), cola(11, 0))).single()
        assertEquals(TrayStockState.CRITICAL, g.state)
        assertEquals(0, g.emptySlots)
    }

    @Test
    fun `keeps products and machines apart and skips unassigned slots`() {
        val groups = StockHealth.groupTraysByProduct(
            listOf(
                cola(10, 10),
                cola(11, 10, productId = "fanta"),
                cola(10, 10, machineId = "m2"),
                cola(12, 10, productId = null),
            )
        )
        assertEquals(listOf("m1/cola", "m1/fanta", "m2/cola"), groups.map { "${it.machineId}/${it.productId}" })
    }

    @Test
    fun `behaves exactly like classifyTray for a product in a single slot`() {
        for (stock in listOf(0, 1, 2, 3, 5, 6, 10)) {
            val t = cola(10, stock)
            assertEquals(
                StockHealth.classifyTray(t.currentStock, t.minStock, t.fillWhenBelow),
                StockHealth.groupTraysByProduct(listOf(t)).single().state,
            )
        }
    }

    @Test
    fun `null thresholds contribute nothing to the summed thresholds`() {
        val g = StockHealth.groupTraysByProduct(
            listOf(
                tray(productId = COLA, currentStock = 3, minStock = null, fillWhenBelow = null, itemNumber = 1),
                tray(productId = COLA, currentStock = 3, minStock = 2, fillWhenBelow = 5, itemNumber = 2),
            )
        ).single()
        assertEquals(2, g.minStock)
        assertEquals(5, g.fillWhenBelow)
        assertEquals(TrayStockState.OK, g.state)
    }

    // ── groupNeedsRefill ────────────────────────────────────────────────

    @Test
    fun `groupNeedsRefill is true for critical, low and fill with a deficit`() {
        assertTrue(StockHealth.groupNeedsRefill(TrayStockState.CRITICAL, 5))
        assertTrue(StockHealth.groupNeedsRefill(TrayStockState.LOW, 5))
        assertTrue(StockHealth.groupNeedsRefill(TrayStockState.FILL, 5))
    }

    @Test
    fun `groupNeedsRefill is false for ok and for a fill tier that is already full`() {
        assertFalse(StockHealth.groupNeedsRefill(TrayStockState.OK, 5))
        assertFalse(StockHealth.groupNeedsRefill(TrayStockState.FILL, 0))
    }

    // ── distributeAcrossSlots ───────────────────────────────────────────

    private fun slot(itemNumber: Int, capacity: Int, currentStock: Int) =
        tray(capacity = capacity, currentStock = currentStock, itemNumber = itemNumber)

    @Test
    fun `fills the emptiest slot first so no selection stays sold out`() {
        val slots = listOf(slot(12, 10, 4), slot(13, 10, 0), slot(14, 10, 9))
        assertEquals(listOf(0, 8, 0), StockHealth.distributeAcrossSlots(slots, 8))
        assertEquals(listOf(3, 10, 0), StockHealth.distributeAcrossSlots(slots, 13))
        assertEquals(listOf(6, 10, 1), StockHealth.distributeAcrossSlots(slots, 100))
    }

    @Test
    fun `breaks ties by slot number and never returns negatives`() {
        val slots = listOf(slot(21, 5, 1), slot(20, 5, 1), slot(22, 5, 7))
        assertEquals(listOf(0, 2, 0), StockHealth.distributeAcrossSlots(slots, 2))
        assertEquals(listOf(0, 0, 0), StockHealth.distributeAcrossSlots(slots, 0))
        assertEquals(listOf(0, 0, 0), StockHealth.distributeAcrossSlots(slots, -3))
    }

    // ── summaries — one product in several slots ────────────────────────

    private fun colaSlots(vararg stocks: Int) = stocks.mapIndexed { i, s -> cola(12 + i, s) }
    private val colaWarehouse = setOf(COLA)

    @Test
    fun `does not mark the machine critical while the product is still in another slot`() {
        val s = StockHealth.summaries(colaSlots(0, 2, 9), colaWarehouse, true)["m1"]!!
        assertEquals(MachineStockTier.FILL, s.tier)
        assertEquals(0, s.refillableEmpty)
        assertEquals(1, s.refillableFill)
    }

    @Test
    fun `reports an empty slot of a well-stocked product only as a hint`() {
        val s = StockHealth.summaries(colaSlots(0, 10, 10), colaWarehouse, true)["m1"]!!
        assertEquals(MachineStockTier.OK, s.tier)
        assertEquals(1, s.emptySlotsWithStock)
    }

    @Test
    fun `counts a product once, however many of its slots are low`() {
        val s = StockHealth.summaries(colaSlots(1, 1, 1), colaWarehouse, true)["m1"]!!
        assertEquals(MachineStockTier.LOW, s.tier)
        assertEquals(1, s.refillableLow)
    }

    @Test
    fun `is a swap candidate only when the non-refillable product is empty everywhere`() {
        assertEquals(0, StockHealth.summaries(colaSlots(0, 4), emptySet(), true)["m1"]!!.noStockEmptyCount)
        assertEquals(1, StockHealth.summaries(colaSlots(0, 0), emptySet(), true)["m1"]!!.noStockEmptyCount)
    }

    // ── buckets ─────────────────────────────────────────────────────────

    @Test
    fun `each machine lands in exactly one bucket`() {
        val buckets = StockHealth.buckets(
            listOf(
                MachineStockSummary(tier = MachineStockTier.CRITICAL),
                MachineStockSummary(tier = MachineStockTier.LOW),
                MachineStockSummary(tier = MachineStockTier.FILL),
                MachineStockSummary(tier = MachineStockTier.OK, noStockEmptyCount = 1),
                MachineStockSummary(tier = MachineStockTier.OK),
            )
        )
        assertEquals(MachineStockBuckets(1, 1, 1, 1, 4), buckets)
    }

    @Test
    fun `a fill machine with a swap candidate counts once`() {
        val buckets = StockHealth.buckets(
            listOf(MachineStockSummary(tier = MachineStockTier.FILL, noStockEmptyCount = 1))
        )
        assertEquals(MachineStockBuckets(0, 0, 1, 0, 1), buckets)
    }

    @Test
    fun `buckets never exceed the number of machines`() {
        val machines = listOf(
            MachineStockSummary(tier = MachineStockTier.CRITICAL, noStockEmptyCount = 2),
            MachineStockSummary(tier = MachineStockTier.LOW, noStockEmptyCount = 1),
            MachineStockSummary(tier = MachineStockTier.FILL, noStockEmptyCount = 3),
        )
        val buckets = StockHealth.buckets(machines)
        assertEquals(machines.size, buckets.needingAttention)
        assertEquals(
            buckets.needingAttention,
            buckets.critical + buckets.low + buckets.fill + buckets.swap,
        )
    }

    @Test
    fun `a mixed fleet counts machines not trays`() {
        val trays = listOf(
            tray(machineId = "m1", currentStock = 5),                       // fill
            tray(machineId = "m1", productId = MISSING, currentStock = 0),  // swap candidate
            tray(machineId = "m2", currentStock = 9, minStock = 0, fillWhenBelow = 0),
            tray(machineId = "m3", currentStock = 1),                       // low
        )
        val buckets = StockHealth.buckets(StockHealth.summaries(trays, warehouse, true).values)
        assertEquals(MachineStockBuckets(critical = 0, low = 1, fill = 1, swap = 0, needingAttention = 2), buckets)
    }

    // ── buildTrayGroupIndex / productListRows (PWA trayGroups.test.ts) ──

    private fun slot(id: String, itemNumber: Int, productId: String?, currentStock: Int) = Tray(
        id = id,
        machineId = "m1",
        itemNumber = itemNumber,
        productId = productId,
        capacity = 10,
        currentStock = currentStock,
        minStock = 2,
        fillWhenBelow = 5,
    )

    @Test
    fun `flags only the slots with room when the product needs refill`() {
        val idx = StockHealth.buildTrayGroupIndex(
            listOf(slot("a", 12, "cola", 0), slot("b", 13, "cola", 1), slot("c", 14, "cola", 10))
        )
        // 11/30, min 6 → fill (11 > 6, ≤ 15)
        assertEquals(TrayStockFlag.FILL, idx["a"]!!.flag)
        assertEquals(TrayStockFlag.FILL, idx["b"]!!.flag)
        assertEquals(TrayStockFlag.OK, idx["c"]!!.flag)
        assertEquals(11, idx["a"]!!.group.currentStock)
    }

    @Test
    fun `marks an empty slot of a well-stocked product as slotEmpty, not low`() {
        val idx = StockHealth.buildTrayGroupIndex(
            listOf(slot("a", 12, "cola", 0), slot("b", 13, "cola", 10), slot("c", 14, "cola", 10))
        )
        assertEquals(TrayStockFlag.SLOT_EMPTY, idx["a"]!!.flag)
        assertFalse(idx["a"]!!.needsRefill)
    }

    @Test
    fun `flags low when the product total is at or below the summed min_stock`() {
        val idx = StockHealth.buildTrayGroupIndex(listOf(slot("a", 12, "cola", 1), slot("b", 13, "cola", 3)))
        assertEquals(TrayStockFlag.LOW, idx["a"]!!.flag)
        assertEquals(TrayStockFlag.LOW, idx["b"]!!.flag)
    }

    @Test
    fun `leaves unassigned slots out of the group index`() {
        assertFalse(StockHealth.buildTrayGroupIndex(listOf(slot("a", 12, null, 0))).containsKey("a"))
    }

    // ── linked selections (vendingMachine.linked_selections) ────────────

    @Test
    fun `linked selections turn an empty slot of a stocked product into ok`() {
        val trays = listOf(slot("a", 12, "cola", 0), slot("b", 13, "cola", 10), slot("c", 14, "cola", 10))
        val idx = StockHealth.buildTrayGroupIndex(trays, linkedSelections = true)
        assertEquals(TrayStockFlag.OK, idx["a"]!!.flag)
        assertEquals(0, idx["a"]!!.group.emptySlots)
        assertFalse(idx["a"]!!.needsRefill)
    }

    @Test
    fun `linked selections keep low and fill flags`() {
        val low = StockHealth.buildTrayGroupIndex(
            listOf(slot("a", 12, "cola", 0), slot("b", 13, "cola", 3)),
            linkedSelections = true,
        )
        assertEquals(TrayStockFlag.LOW, low["a"]!!.flag)
        val fill = StockHealth.buildTrayGroupIndex(
            listOf(slot("a", 12, "cola", 0), slot("b", 13, "cola", 1), slot("c", 14, "cola", 10)),
            linkedSelections = true,
        )
        assertEquals(TrayStockFlag.FILL, fill["a"]!!.flag)
    }

    @Test
    fun `linked machines report no empty slots with stock, others unchanged`() {
        val trays = colaSlots(0, 10, 10) + colaSlots(0, 10, 10).map {
            it.copy(id = "m2-${it.id}", machineId = "m2")
        }
        val s = StockHealth.summaries(trays, colaWarehouse, true, linkedMachineIds = setOf("m1"))
        assertEquals(0, s["m1"]!!.emptySlotsWithStock)
        assertEquals(MachineStockTier.OK, s["m1"]!!.tier)
        assertEquals(1, s["m2"]!!.emptySlotsWithStock)
    }

    @Test
    fun `linked selections do not change the tier of a sold-out product`() {
        val s = StockHealth.summaries(colaSlots(0, 0), colaWarehouse, true, linkedMachineIds = setOf("m1"))["m1"]!!
        assertEquals(MachineStockTier.CRITICAL, s.tier)
        assertEquals(1, s.refillableEmpty)
    }

    private val listTrays = listOf(
        slot("fanta", 11, "fanta", 5),
        slot("c14", 14, "cola", 9),
        slot("c12", 12, "cola", 0),
        slot("empty", 13, null, 0),
        slot("c20", 20, "cola", 2),
    )

    @Test
    fun `orders products by their first slot and keeps a product together`() {
        val rows = StockHealth.productListRows(listTrays, StockHealth.buildTrayGroupIndex(listTrays))
        assertEquals(listOf("fanta", "c12", "c14", "c20", "empty"), rows.map { it.tray.id })
    }

    @Test
    fun `puts a header on the first slot of multi-slot products only`() {
        val rows = StockHealth.productListRows(listTrays, StockHealth.buildTrayGroupIndex(listTrays))
        assertEquals(listOf(null, "cola", null, null, null), rows.map { it.header?.productId })
        assertEquals(listOf(false, true, true, true, false), rows.map { it.inGroup })
    }

    @Test
    fun `filters rows but keeps whole-product totals in the header`() {
        val rows = StockHealth.productListRows(listTrays, StockHealth.buildTrayGroupIndex(listTrays)) { it.id == "c14" }
        assertEquals(1, rows.size)
        assertEquals(11, rows[0].header?.currentStock)
    }

    // ─── trayMatchesSearch (PWA fuzzyFilter on product name + slot number) ─

    private fun named(itemNumber: Int, name: String?) = Tray(
        id = "s$itemNumber",
        machineId = "m1",
        itemNumber = itemNumber,
        productId = name?.let { "p-$it" },
        products = name?.let { xyz.vmflow.models.Product(id = "p-$it", name = it) },
    )

    @Test
    fun `tray search matches product names fuzzily and case-insensitively`() {
        assertTrue(StockHealth.trayMatchesSearch(named(12, "Coca-Cola Zero"), "cola"))
        assertTrue(StockHealth.trayMatchesSearch(named(12, "Coca-Cola Zero"), "CCZ"))
        assertFalse(StockHealth.trayMatchesSearch(named(12, "Coca-Cola Zero"), "fanta"))
    }

    @Test
    fun `tray search matches the slot number`() {
        assertTrue(StockHealth.trayMatchesSearch(named(12, "Fanta"), "12"))
        assertTrue(StockHealth.trayMatchesSearch(named(12, null), "1"))
        assertFalse(StockHealth.trayMatchesSearch(named(12, null), "3"))
    }

    @Test
    fun `a blank tray search matches everything`() {
        assertTrue(StockHealth.trayMatchesSearch(named(12, null), "   "))
    }
}
