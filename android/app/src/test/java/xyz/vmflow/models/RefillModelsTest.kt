package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Focused coverage for the derived values on the Phase 5 refill-tour models
 * (`Models.kt` L397-435) — [RefillTray.maxFill]/[RefillTray.targetStock] and
 * [RefillMachine.totalDeficit]/[RefillMachine.traysNeedingRefill]/
 * [RefillMachine.totalCurrentStock]/[RefillMachine.totalCapacity]/
 * [RefillMachine.stockPercent]. `RefillTourLogicTest.kt` covers the pure
 * `RefillTourLogic` functions built on top of these; this file covers the
 * model properties themselves, which that suite never exercised directly.
 */
class RefillModelsTest {

    private fun tray(
        id: String = "t1",
        capacity: Int = 10,
        currentStock: Int = 0,
    ) = Tray(id = id, machineId = "m1", itemNumber = 1, capacity = capacity, currentStock = currentStock)

    private fun refillTray(t: Tray, fillAmount: Int = 0) = RefillTray(tray = t, fillAmount = fillAmount)

    private fun refillMachine(trays: List<RefillTray>) = RefillMachine(
        machine = VendingMachineWithEmbedded(id = "m1", name = "Machine 1"),
        trays = trays
    )

    // ─── RefillTray ──────────────────────────────────────────────────────

    @Test
    fun `maxFill is capacity minus currentStock`() {
        val t = refillTray(tray(capacity = 10, currentStock = 3))
        assertEquals(7, t.maxFill)
    }

    @Test
    fun `maxFill is negative for an overstocked tray, matching capacity minus currentStock unclamped`() {
        // No coercion here (unlike Tray.deficit) — mirrors iOS RefillTray.maxFill exactly,
        // which also doesn't clamp.
        val t = refillTray(tray(capacity = 10, currentStock = 12))
        assertEquals(-2, t.maxFill)
    }

    @Test
    fun `targetStock is currentStock plus fillAmount`() {
        val t = refillTray(tray(capacity = 10, currentStock = 3), fillAmount = 5)
        assertEquals(8, t.targetStock)
    }

    @Test
    fun `targetStock for an overstocked tray still adds fillAmount on top of the over-count`() {
        val t = refillTray(tray(capacity = 10, currentStock = 12), fillAmount = 0)
        assertEquals(12, t.targetStock)
    }

    // ─── RefillMachine ───────────────────────────────────────────────────

    @Test
    fun `totalDeficit sums each tray's clamped deficit`() {
        val machine = refillMachine(
            listOf(
                refillTray(tray("t1", capacity = 10, currentStock = 4)), // deficit 6
                refillTray(tray("t2", capacity = 5, currentStock = 5)),  // deficit 0
                refillTray(tray("t3", capacity = 8, currentStock = 10))  // deficit 0 (clamped, not negative)
            )
        )
        assertEquals(6, machine.totalDeficit)
    }

    @Test
    fun `traysNeedingRefill counts only trays with a positive deficit`() {
        val machine = refillMachine(
            listOf(
                refillTray(tray("t1", capacity = 10, currentStock = 4)), // deficit 6 -> counts
                refillTray(tray("t2", capacity = 5, currentStock = 5)),  // deficit 0 -> doesn't
                refillTray(tray("t3", capacity = 8, currentStock = 2))   // deficit 6 -> counts
            )
        )
        assertEquals(2, machine.traysNeedingRefill)
    }

    @Test
    fun `totalCurrentStock sums currentStock across trays`() {
        val machine = refillMachine(
            listOf(
                refillTray(tray("t1", capacity = 10, currentStock = 4)),
                refillTray(tray("t2", capacity = 5, currentStock = 2))
            )
        )
        assertEquals(6, machine.totalCurrentStock)
    }

    @Test
    fun `totalCapacity sums capacity across trays`() {
        val machine = refillMachine(
            listOf(
                refillTray(tray("t1", capacity = 10, currentStock = 4)),
                refillTray(tray("t2", capacity = 5, currentStock = 2))
            )
        )
        assertEquals(15, machine.totalCapacity)
    }

    @Test
    fun `stockPercent is the rounded percentage of currentStock over capacity`() {
        val machine = refillMachine(
            listOf(
                refillTray(tray("t1", capacity = 10, currentStock = 3)),
                refillTray(tray("t2", capacity = 10, currentStock = 4))
            )
        )
        // totalCurrentStock 7 / totalCapacity 20 = 35%
        assertEquals(35, machine.stockPercent)
    }

    @Test
    fun `stockPercent is zero for a machine with no trays, guarding the zero-capacity division`() {
        val machine = refillMachine(emptyList())
        assertEquals(0, machine.stockPercent)
    }

    @Test
    fun `stockPercent is zero when every tray has zero capacity, guarding the zero-capacity division`() {
        val machine = refillMachine(
            listOf(refillTray(tray("t1", capacity = 0, currentStock = 0)))
        )
        assertEquals(0, machine.stockPercent)
    }
}
