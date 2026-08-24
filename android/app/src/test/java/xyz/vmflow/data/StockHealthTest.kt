package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.vmflow.models.Tray

/**
 * Parity tests for [StockHealth], the port of the PWA's
 * `management-frontend/app/lib/stock-health.ts`. The cases mirror
 * `app/lib/__tests__/stock-health.test.ts` plus the two divergences this
 * port fixed: unassigned slots and non-refillable products used to make a
 * machine "critical" on Android but not in the PWA.
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
    ) = Tray(
        id = "t-$machineId-$currentStock-$productId",
        machineId = machineId,
        itemNumber = 1,
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
}
