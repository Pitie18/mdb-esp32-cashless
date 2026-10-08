package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import xyz.vmflow.models.PersistedTourState
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillStep
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.TourLogEntry
import xyz.vmflow.models.Tray
import xyz.vmflow.models.VendingMachineWithEmbedded

/**
 * Port of the tour half of
 * `management-frontend/app/lib/__tests__/slotChange.test.ts`
 * (`rebuildPackNeeds`, `fillPlan`, `computeLeftovers`) plus the Android
 * tour glue in [SlotChange]. Same fixtures and numbers as the web tests, so
 * the three clients can't drift apart.
 */
class SlotChangeTest {

    private fun item(
        id: String,
        tray: String,
        from: String?,
        to: String?,
        fromCap: Int,
        toCap: Int
    ) = SlotChangeItem(
        id = id,
        trayId = tray,
        itemNumber = tray.drop(1).toInt(),
        fromProductId = from,
        toProductId = to,
        fromCapacity = fromCap,
        toCapacity = toCap
    )

    private fun done(removed: Int, filled: Int) = ItemOutcome(RebuildAction.DONE, removed, filled)

    // ── tour: packing ────────────────────────────────────────────────────

    @Test
    fun `packs the new slots minus what moves over from changed slots`() {
        // Doppelbelegung: schorle -> cola (10)
        val items = listOf(item("i12", "t12", "schorle", "cola", 10, 10))
        assertEquals(mapOf("cola" to 10), SlotChange.rebuildPackNeeds(items, mapOf("t12" to 8)))
    }

    @Test
    fun `a neighbour swap needs nothing from the warehouse when stock covers it`() {
        val items = listOf(
            item("i16", "t16", "balisto", "duplo", 12, 12),
            item("i17", "t17", "duplo", "balisto", 14, 14)
        )
        val stock = mapOf("t16" to 10, "t17" to 12)
        // duplo needs 12, 12 come out of slot 17 -> 0; balisto needs 14, 10 come out of 16 -> 4
        assertEquals(mapOf("duplo" to 0, "balisto" to 4), SlotChange.rebuildPackNeeds(items, stock))
    }

    @Test
    fun `a smaller spiral for the same product needs nothing extra`() {
        val items = listOf(item("i16", "t16", "pringles", "pringles", 8, 6))
        assertEquals(mapOf("pringles" to 1), SlotChange.rebuildPackNeeds(items, mapOf("t16" to 5)))
        assertEquals(mapOf("pringles" to 0), SlotChange.rebuildPackNeeds(items, mapOf("t16" to 8)))
    }

    @Test
    fun `emptying a slot packs nothing`() {
        val items = listOf(item("i14", "t14", "tictac", null, 15, 15))
        assertEquals(emptyMap<String, Int>(), SlotChange.rebuildPackNeeds(items, mapOf("t14" to 14)))
    }

    // ── tour: at the machine ─────────────────────────────────────────────

    @Test
    fun `fills moved stock first, then van stock`() {
        val items = listOf(
            item("i16", "t16", "balisto", "duplo", 12, 12),
            item("i17", "t17", "duplo", "balisto", 14, 14)
        )
        // a duplo sold since packing: 11 come out of 17
        val removed = mapOf("i16" to 10, "i17" to 11)
        val plan = SlotChange.fillPlan(items, removed, mapOf("balisto" to 4, "duplo" to 0))
        assertEquals(FillSuggestion(moved = 11, van = 0, total = 11), plan["i16"])
        assertEquals(FillSuggestion(moved = 10, van = 4, total = 14), plan["i17"])
    }

    @Test
    fun `an emptied slot gets no fill`() {
        val items = listOf(item("i14", "t14", "tictac", null, 15, 15))
        val plan = SlotChange.fillPlan(items, mapOf("i14" to 14), emptyMap())
        assertEquals(FillSuggestion(0, 0, 0), plan["i14"])
    }

    @Test
    fun `leftovers split into van goods and goods from the machine`() {
        val items = listOf(
            item("i12", "t12", "schorle", "cola", 10, 10),
            item("i13", "t13", "redbull", "redbull", 8, 6)
        )
        val outcome = mapOf("i12" to done(7, 7), "i13" to done(8, 6))
        val left = SlotChange.computeLeftovers(items, outcome, mapOf("cola" to 10))
        assertEquals(Leftover(van = 3, machine = 0), left["cola"])
        assertEquals(Leftover(van = 0, machine = 7), left["schorle"])
        assertEquals(Leftover(van = 0, machine = 2), left["redbull"])
    }

    @Test
    fun `a skipped slot returns everything packed for it`() {
        val items = listOf(item("i12", "t12", "schorle", "cola", 10, 10))
        val left = SlotChange.computeLeftovers(
            items,
            mapOf("i12" to ItemOutcome(RebuildAction.SKIP, 0, 0)),
            mapOf("cola" to 10)
        )
        assertEquals(Leftover(van = 10, machine = 0), left["cola"])
        assertFalse(left.containsKey("schorle"))
    }

    @Test
    fun `a swap where everything moves leaves nothing over`() {
        val items = listOf(
            item("i16", "t16", "balisto", "duplo", 12, 12),
            item("i17", "t17", "duplo", "balisto", 14, 14)
        )
        val outcome = mapOf("i16" to done(10, 11), "i17" to done(11, 14))
        val left = SlotChange.computeLeftovers(items, outcome, mapOf("balisto" to 4))
        assertTrue(left.isEmpty())
    }

    @Test
    fun `price change is flagged only when the new product costs something else`() {
        val base = item("i12", "t12", "schorle", "cola", 10, 10)
        assertTrue(base.copy(fromPrice = 1.8, toPrice = 2.0).hasPriceChange)
        assertFalse(base.copy(fromPrice = 2.5, toPrice = 2.5).hasPriceChange)
        assertFalse(base.copy(toProductId = null, fromPrice = 1.0, toPrice = null).hasPriceChange)
    }

    // ── tour glue ────────────────────────────────────────────────────────

    @Test
    fun `rebuild gets first claim on limited warehouse stock, in tour order`() {
        val committed = SlotChange.commitRebuild(
            needsByMachine = listOf(
                "m1" to mapOf("cola" to 10, "duplo" to 0),
                "m2" to mapOf("cola" to 6)
            ),
            warehouseStock = mapOf("cola" to 12),
            stockLoaded = true
        )
        assertEquals(mapOf("m1" to mapOf("cola" to 10), "m2" to mapOf("cola" to 2)), committed)
        assertEquals(
            mapOf("cola" to 0, "water" to 5),
            SlotChange.stockAfterRebuild(mapOf("cola" to 12, "water" to 5), committed)
        )
    }

    @Test
    fun `without loaded stock the rebuild is not capped`() {
        val committed = SlotChange.commitRebuild(
            needsByMachine = listOf("m1" to mapOf("cola" to 10)),
            warehouseStock = emptyMap(),
            stockLoaded = false
        )
        assertEquals(mapOf("m1" to mapOf("cola" to 10)), committed)
    }

    @Test
    fun `one deduction per machine and product, normal plus rebuild`() {
        // web useRefillWizard.rebuild.test: 8 cola for the refill + 10 for the rebuild = one deduction of 18
        val merged = SlotChange.mergeDeductions(
            normal = listOf(PackedDeduction("m1", "cola", 8), PackedDeduction("m2", "water", 3)),
            rebuildPacked = mapOf("m1" to mapOf("cola" to 10, "duplo" to 4))
        )
        assertEquals(
            listOf(
                PackedDeduction("m1", "cola", 18),
                PackedDeduction("m2", "water", 3),
                PackedDeduction("m1", "duplo", 4)
            ),
            merged
        )
    }

    @Test
    fun `rebuild cards default to the live stock and follow the fill plan`() {
        val items = listOf(
            item("i16", "t16", "balisto", "duplo", 12, 12),
            item("i17", "t17", "duplo", "balisto", 14, 14)
        )
        val slots = SlotChange.startRebuild(items, mapOf("t16" to 10, "t17" to 11), mapOf("balisto" to 4))
        assertEquals(listOf(10, 11), slots.map { it.removed })
        assertEquals(listOf(11, 14), slots.map { it.filled })

        // A typed fill survives a recompute; a skipped slot stops giving stock.
        val typed = slots.map { if (it.item.id == "i16") it.copy(filled = 9, filledTouched = true) else it }
        val skipped = typed.map { if (it.item.id == "i16") it.copy(action = RebuildAction.SKIP) else it }
        val again = SlotChange.recomputeFill(skipped, mapOf("balisto" to 4))
        assertEquals(9, again.first { it.item.id == "i16" }.filled)
        // i17 no longer gets the 10 balisto out of slot 16, only the van's 4
        assertEquals(4, again.first { it.item.id == "i17" }.filled)
    }

    @Test
    fun `leftover panel counts undecided slots as rebuilt`() {
        val items = listOf(item("i12", "t12", "schorle", "cola", 10, 10))
        val slots = SlotChange.startRebuild(items, mapOf("t12" to 8), mapOf("cola" to 10))
        val left = SlotChange.leftovers(slots, mapOf("cola" to 10))
        assertEquals(listOf(Triple("schorle", 0, 8)), left.map { Triple(it.productId, it.van, it.machine) })

        val skipped = SlotChange.leftovers(slots.map { it.copy(action = RebuildAction.SKIP) }, mapOf("cola" to 10))
        assertEquals(listOf(Triple("cola", 10, 0)), skipped.map { Triple(it.productId, it.van, it.machine) })
    }

    // ── persistence (resume) ─────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a resumed tour keeps the change note decisions and the rebuild units`() {
        val machine = RefillMachine(
            machine = VendingMachineWithEmbedded(id = "m1", name = "Foyer"),
            trays = listOf(RefillTray(tray = Tray(id = "t12", machineId = "m1", itemNumber = 12), fillAmount = 0)),
            isPacked = true,
            changeRequest = SlotChangeRequest(
                id = "r1",
                machineId = "m1",
                items = listOf(
                    item("i12", "t12", "schorle", "cola", 10, 10).copy(
                        toProduct = SlotChangeProductRef(name = "Cola", imagePath = "cola.png"),
                        toPrice = 2.0
                    ),
                    item("i13", "t13", "redbull", null, 8, 8).copy(accepted = false)
                )
            ),
            rebuildPacked = mapOf("cola" to 10)
        )
        val state = PersistedTourState(
            step = RefillStep.REFILL,
            machines = listOf(machine),
            currentMachineIndex = 0,
            selectedWarehouseId = "w1",
            tourId = "tour-1",
            tourLog = listOf(TourLogEntry("m0", "Lobby", 2, 9, skipped = false, slotsRebuilt = 1)),
            savedAt = "2026-10-08T12:00:00Z"
        )
        val restored = json.decodeFromString<PersistedTourState>(json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(setOf("t12"), restored.machines.single().rebuildTrayIds)
    }

    @Test
    fun `a tour saved before change notes existed still decodes`() {
        val old = """{"step":"REFILL","machines":[{"machine":{"id":"m1"},"trays":[]}],""" +
            """"currentMachineIndex":0,"selectedWarehouseId":null,"tourId":"t","savedAt":"x",""" +
            """"tourLog":[{"machineId":"m1","machineName":"A","traysRefilled":1,"totalAdded":2,"skipped":false}]}"""
        val restored = json.decodeFromString<PersistedTourState>(old)
        assertEquals(null, restored.machines.single().changeRequest)
        assertEquals(emptyMap<String, Int>(), restored.machines.single().rebuildPacked)
        assertEquals(0, restored.tourLog.single().slotsRebuilt)
    }

    @Test
    fun `change items decode from the PostgREST shape with the product joins`() {
        val row = """{"id":"i1","request_id":"r1","tray_id":"t1","item_number":12,""" +
            """"from_product_id":"p1","to_product_id":null,"from_capacity":10,"to_capacity":8,""" +
            """"from_price":1.5,"to_price":null,"skip_count":2,""" +
            """"from_product":{"name":"Schorle","image_path":"s.png"},"to_product":null}"""
        val decoded = json.decodeFromString<SlotChangeItem>(row)
        assertEquals("Schorle", decoded.fromName)
        assertEquals(null, decoded.toProductId)
        assertTrue(decoded.accepted)
        assertTrue(decoded.capacityChanges)
        assertFalse(decoded.hasPriceChange)
    }

    // ─── queueReplacement (useMachineAnalysis.applySwap merge) ───

    private fun pendingItem(trayId: String, toProductId: String?, toCapacity: Int) = SlotChangeItem(
        id = "i-$trayId", trayId = trayId, itemNumber = 10, fromProductId = "a", toProductId = toProductId,
        fromCapacity = 8, toCapacity = toCapacity, fromPrice = null, toPrice = null, skipCount = 0,
        fromProduct = null, toProduct = null
    )

    @Test
    fun `queueReplacement keeps other pending items and adds the slot with its tray capacity`() {
        val plan = SlotChange.queueReplacement(listOf(pendingItem("t1", "x", 6)), "t2", "y", 9)
        assertEquals(listOf(PlannedChange("t1", "x", 6), PlannedChange("t2", "y", 9)), plan)
    }

    @Test
    fun `queueReplacement replaces the product of a pending slot but keeps its planned capacity`() {
        val plan = SlotChange.queueReplacement(listOf(pendingItem("t1", "x", 6)), "t1", "y", 9)
        assertEquals(listOf(PlannedChange("t1", "y", 6)), plan)
    }

    @Test
    fun `queueReplacement without an open request plans just that slot`() {
        assertEquals(listOf(PlannedChange("t3", "z", 12)), SlotChange.queueReplacement(emptyList(), "t3", "z", 12))
    }
}
