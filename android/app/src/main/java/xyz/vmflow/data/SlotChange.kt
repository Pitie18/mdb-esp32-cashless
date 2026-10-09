package xyz.vmflow.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Slot re-assignment ("Fächer umbelegen") — the refill-tour half.
 *
 * The office plans a new slot layout in the web app; saving it stores a
 * **change request** (`save_slot_change_request`), never a tray update. The
 * next refill tour shows the open request as a change note per machine: the
 * refiller accepts (default) or declines each slot while packing, packs what
 * the new slots need ([SlotChange.rebuildPackNeeds]), rebuilds them at the
 * machine ([SlotChange.fillPlan]) and books what is left over
 * ([SlotChange.computeLeftovers]) through `apply_slot_change`.
 *
 * Bookkeeping rule: until the refiller quits the rebuild at the machine the
 * old product stays in the slot, so sales keep decrementing it. The units
 * actually taken out ("removed") are counted at the machine; they go into the
 * new slots of the same product first, and only the rest comes from the van.
 *
 * Port of `management-frontend/app/lib/slotChange.ts` (tour functions only —
 * the planner is web-only) and of the wizard glue in
 * `composables/useRefillWizard.ts`. iOS has the same port in
 * `SlotChange.swift`; change all three together. Server side:
 * `Docker/supabase/migrations/20261008120000_slot_change_requests.sql`.
 *
 * Everything in [SlotChange] is pure — no coroutines, Supabase or Android.
 */

/** `products` FK join on a change item (`name, image_path`). */
@Serializable
data class SlotChangeProductRef(
    val name: String? = null,
    @SerialName("image_path") val imagePath: String? = null
)

/**
 * One pending slot of a machine's change request
 * (`slot_change_request_items`), with the from/to products joined. The
 * from/to snapshot is taken by the server when the plan is saved.
 *
 * [accepted] is local tour state, never read from the server: the refiller's
 * packing decision (accepted by default). It rides along in the persisted tour
 * snapshot, so a resumed tour keeps the decisions.
 */
@Serializable
data class SlotChangeItem(
    val id: String,
    @SerialName("request_id") val requestId: String = "",
    @SerialName("tray_id") val trayId: String,
    @SerialName("item_number") val itemNumber: Int,
    @SerialName("from_product_id") val fromProductId: String? = null,
    @SerialName("to_product_id") val toProductId: String? = null,
    @SerialName("from_capacity") val fromCapacity: Int,
    @SerialName("to_capacity") val toCapacity: Int,
    @SerialName("from_price") val fromPrice: Double? = null,
    @SerialName("to_price") val toPrice: Double? = null,
    @SerialName("skip_count") val skipCount: Int = 0,
    /** Age restriction of the old / new product's category (`product_category.min_age`), snapshotted by the server; `null` = none. */
    @SerialName("from_min_age") val fromMinAge: Int? = null,
    @SerialName("to_min_age") val toMinAge: Int? = null,
    @SerialName("from_product") val fromProduct: SlotChangeProductRef? = null,
    @SerialName("to_product") val toProduct: SlotChangeProductRef? = null,
    val accepted: Boolean = true
) {
    val fromName: String? get() = fromProduct?.name
    val toName: String? get() = toProduct?.name
    val fromImagePath: String? get() = fromProduct?.imagePath
    val toImagePath: String? get() = toProduct?.imagePath

    /** The selection's price has to be changed at the machine (web `priceChanges`). */
    val hasPriceChange: Boolean
        get() = toProductId != null && toPrice != null && toPrice != fromPrice

    val capacityChanges: Boolean get() = toCapacity != fromCapacity

    /**
     * The machine's age setting for this selection has to change (web
     * `ageChanged`): the restriction differs between the old and the new
     * product, null-aware — none → 18 is a change, none → none is not.
     */
    val ageChanged: Boolean get() = fromMinAge != toMinAge
}

/** A machine's open change request with its pending items, slot order. */
@Serializable
data class SlotChangeRequest(
    val id: String,
    @SerialName("machine_id") val machineId: String,
    val note: String? = null,
    val items: List<SlotChangeItem> = emptyList()
)

/** What the refiller did with one slot at the machine. */
enum class RebuildAction(val raw: String) { DONE("done"), SKIP("skip") }

/** Where left-over goods of one product go. */
enum class LeftoverDestination(val raw: String) { WAREHOUSE("warehouse"), WASTE("waste") }

/** Suggested fill of one rebuilt slot: [moved] came out of another changed slot, [van] from the van. */
data class FillSuggestion(val moved: Int, val van: Int, val total: Int)

/** Outcome of one slot, input to [SlotChange.computeLeftovers]. */
data class ItemOutcome(val action: RebuildAction, val removed: Int, val filled: Int)

/** Left over per product: [van] = packed but unused, [machine] = taken out and not re-filled. */
data class Leftover(val van: Int, val machine: Int)

/**
 * One slot of the change note as handled at the machine (web `RebuildSlot`).
 *
 * @property liveStock stock in the slot right now — sales since packing are
 *   already deducted.
 * @property removed units of the old product taken out (editable, defaults
 *   to [liveStock]).
 * @property filled units of the new product put in (editable, defaults to the
 *   fill plan, never above `toCapacity`).
 * @property action `null` until the refiller marks the slot rebuilt or not.
 */
data class RebuildSlot(
    val item: SlotChangeItem,
    val liveStock: Int,
    val removed: Int,
    val filled: Int = 0,
    val filledTouched: Boolean = false,
    val moved: Int = 0,
    val fromVan: Int = 0,
    val action: RebuildAction? = null,
    val priceSet: Boolean = false,
    /** The refiller confirmed the machine's age setting was changed (only meaningful when `item.ageChanged`). */
    val ageSet: Boolean = false
) {
    /**
     * "Rebuilt" is blocked until the age setting is confirmed — a selection
     * selling an 18+ product without the age check must not be quittable by
     * accident. "Not rebuilt" never needs it.
     */
    val needsAgeConfirmation: Boolean get() = item.ageChanged && !ageSet
}

/** One slot of a plan for `save_slot_change_request` (`p_items`). */
data class PlannedChange(val trayId: String, val toProductId: String?, val toCapacity: Int)

/** One product of the "left over" panel. */
data class RebuildLeftover(
    val productId: String,
    val name: String?,
    val imagePath: String?,
    val van: Int,
    val machine: Int
)

object SlotChange {

    /**
     * Units to pack for the rebuild, per product: the capacity of its new
     * slots minus what comes out of the changed slots that held it (that stock
     * moves over). [stockByTray] is the stock known at packing time. Products
     * whose new slots are fully covered by moved stock are listed with 0.
     */
    fun rebuildPackNeeds(items: List<SlotChangeItem>, stockByTray: Map<String, Int>): Map<String, Int> {
        val need = LinkedHashMap<String, Int>()
        for (it in items) {
            val to = it.toProductId ?: continue
            need[to] = (need[to] ?: 0) + it.toCapacity
        }
        for (it in items) {
            val from = it.fromProductId ?: continue
            val current = need[from] ?: continue
            need[from] = current - (stockByTray[it.trayId] ?: 0)
        }
        for (key in need.keys.toList()) need[key] = maxOf(0, need.getValue(key))
        return need
    }

    /**
     * Suggested fill per rebuilt slot, lowest slot number first: stock taken
     * out of the changed slots goes in first, the van covers the rest.
     */
    fun fillPlan(
        items: List<SlotChangeItem>,
        removedByItem: Map<String, Int>,
        vanByProduct: Map<String, Int>
    ): Map<String, FillSuggestion> {
        val freed = HashMap<String, Int>()
        for (it in items) {
            val from = it.fromProductId ?: continue
            freed[from] = (freed[from] ?: 0) + (removedByItem[it.id] ?: 0)
        }
        val van = HashMap(vanByProduct)
        val out = LinkedHashMap<String, FillSuggestion>()
        for (it in items.sortedBy { item -> item.itemNumber }) {
            val to = it.toProductId
            if (to == null) {
                out[it.id] = FillSuggestion(moved = 0, van = 0, total = 0)
                continue
            }
            val moved = minOf(it.toCapacity, freed[to] ?: 0)
            freed[to] = (freed[to] ?: 0) - moved
            val fromVan = minOf(it.toCapacity - moved, van[to] ?: 0)
            van[to] = (van[to] ?: 0) - fromVan
            out[it.id] = FillSuggestion(moved = moved, van = fromVan, total = moved + fromVan)
        }
        return out
    }

    /**
     * What is left over per product after the rebuild, split by origin:
     * `van` = packed for the rebuild but not used (goes back to its batch),
     * `machine` = taken out of a slot and not put into another one (new
     * batch). Moved stock is used before van stock, matching [fillPlan]. Only
     * products with something left are returned.
     */
    fun computeLeftovers(
        items: List<SlotChangeItem>,
        outcome: Map<String, ItemOutcome>,
        packedByProduct: Map<String, Int>
    ): Map<String, Leftover> {
        val freed = LinkedHashMap<String, Int>()
        val filledInto = LinkedHashMap<String, Int>()
        for (it in items) {
            val o = outcome[it.id] ?: continue
            if (o.action != RebuildAction.DONE) continue
            it.fromProductId?.let { from -> freed[from] = (freed[from] ?: 0) + maxOf(0, o.removed) }
            it.toProductId?.let { to -> filledInto[to] = (filledInto[to] ?: 0) + maxOf(0, o.filled) }
        }
        val products = LinkedHashSet<String>().apply {
            addAll(freed.keys)
            addAll(filledInto.keys)
            addAll(packedByProduct.keys)
        }
        val out = LinkedHashMap<String, Leftover>()
        for (pid in products) {
            val f = freed[pid] ?: 0
            val filled = filledInto[pid] ?: 0
            val packed = packedByProduct[pid] ?: 0
            val moved = minOf(f, filled)
            val vanUsed = minOf(packed, filled - moved)
            val left = Leftover(van = packed - vanUsed, machine = f - moved)
            if (left.van + left.machine > 0) out[pid] = left
        }
        return out
    }

    // ── Tour glue (web `useRefillWizard.ts`) ─────────────────────────────

    /**
     * Rebuild units committed per machine against the warehouse, in tour
     * order: an accepted slot cannot be rebuilt without its units, so the
     * rebuild gets first claim on limited stock, before any normal refill
     * (web `recalculateCommittedQuantities`). Without loaded stock nothing
     * caps the commitment — the same "only the tray cap applies" rule
     * [RefillTourLogic.maxPackingQuantity] follows.
     *
     * @param needsByMachine `machineId -> productId -> units needed`, in the
     *   order machines get served.
     * @return `machineId -> productId -> units committed`; machines and
     *   products with nothing committed are left out.
     */
    fun commitRebuild(
        needsByMachine: List<Pair<String, Map<String, Int>>>,
        warehouseStock: Map<String, Int>,
        stockLoaded: Boolean
    ): Map<String, Map<String, Int>> {
        val remaining = HashMap(warehouseStock)
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((machineId, needs) in needsByMachine) {
            val machineMap = LinkedHashMap<String, Int>()
            for ((pid, need) in needs) {
                if (need <= 0) continue
                val qty = if (stockLoaded) minOf(need, remaining[pid] ?: 0) else need
                if (qty > 0) {
                    machineMap[pid] = qty
                    if (stockLoaded) remaining[pid] = (remaining[pid] ?: 0) - qty
                }
            }
            if (machineMap.isNotEmpty()) out[machineId] = machineMap
        }
        return out
    }

    /**
     * [warehouseStock] minus what the rebuilds already committed — the stock
     * the normal packing list may still use. Keeps every key (a product
     * fully committed stays at 0 rather than vanishing), so "stock loaded"
     * checks on the result keep meaning what they meant on the input.
     */
    fun stockAfterRebuild(
        warehouseStock: Map<String, Int>,
        committed: Map<String, Map<String, Int>>
    ): Map<String, Int> {
        if (committed.isEmpty()) return warehouseStock
        val out = HashMap(warehouseStock)
        for (machineMap in committed.values) {
            for ((pid, qty) in machineMap) {
                val current = out[pid] ?: continue
                out[pid] = maxOf(0, current - qty)
            }
        }
        return out
    }

    /**
     * One deduction per (machine, product): the normal refill plus the
     * rebuild units, summed (web `startTour`). Rebuild products the machine
     * does not hold yet get a deduction of their own. Zero quantities are
     * dropped; the order is the normal deductions first, then new rebuild
     * products in machine order.
     */
    fun mergeDeductions(
        normal: List<PackedDeduction>,
        rebuildPacked: Map<String, Map<String, Int>>
    ): List<PackedDeduction> {
        val totals = LinkedHashMap<Pair<String, String>, Int>()
        for (d in normal) {
            if (d.quantity <= 0) continue
            val key = d.machineId to d.productId
            totals[key] = (totals[key] ?: 0) + d.quantity
        }
        for ((machineId, products) in rebuildPacked) {
            for ((pid, qty) in products) {
                if (qty <= 0) continue
                val key = machineId to pid
                totals[key] = (totals[key] ?: 0) + qty
            }
        }
        return totals.map { (key, qty) -> PackedDeduction(machineId = key.first, productId = key.second, quantity = qty) }
    }

    /**
     * Marks a slot rebuilt / not rebuilt / undecided. Refuses "rebuilt" while
     * the age setting is still unconfirmed (the slot is returned unchanged).
     */
    fun withAction(slot: RebuildSlot, action: RebuildAction?): RebuildSlot =
        if (action == RebuildAction.DONE && slot.needsAgeConfirmation) slot else slot.copy(action = action)

    /**
     * Ticks / unticks "age setting changed". Unticking a slot already marked
     * rebuilt takes that decision back — it can't stay rebuilt unconfirmed.
     */
    fun withAgeSet(slot: RebuildSlot, value: Boolean): RebuildSlot {
        val next = slot.copy(ageSet = value)
        return if (next.action == RebuildAction.DONE && next.needsAgeConfirmation) next.copy(action = null) else next
    }

    /** `age_set` for `apply_slot_change`: only a rebuilt slot whose age setting changed and was confirmed. */
    fun ageSetPayload(slot: RebuildSlot): Boolean =
        slot.action == RebuildAction.DONE && slot.item.ageChanged && slot.ageSet

    /** Fresh rebuild cards for a machine: `removed` defaults to the live stock. */
    fun startRebuild(items: List<SlotChangeItem>, liveStockByTray: Map<String, Int>, packed: Map<String, Int>): List<RebuildSlot> =
        recomputeFill(
            items.map { item ->
                val live = liveStockByTray[item.trayId] ?: 0
                RebuildSlot(item = item, liveStock = live, removed = live)
            },
            packed
        )

    /**
     * Refreshes the suggested fill of every slot whose fill the refiller has
     * not typed (web `recomputeRebuildFill`). Skipped slots neither give nor
     * take stock.
     */
    fun recomputeFill(slots: List<RebuildSlot>, packed: Map<String, Int>): List<RebuildSlot> {
        val active = slots.filter { it.action != RebuildAction.SKIP }
        val plan = fillPlan(
            items = active.map { it.item },
            removedByItem = active.associate { it.item.id to it.removed },
            vanByProduct = packed
        )
        return slots.map { s ->
            val p = plan[s.item.id] ?: FillSuggestion(0, 0, 0)
            s.copy(
                moved = p.moved,
                fromVan = p.van,
                filled = if (s.filledTouched) s.filled else p.total
            )
        }
    }

    /**
     * What is left over at the current machine. Slots not decided yet count as
     * rebuilt (the expected case), so the panel can be read early.
     */
    fun leftovers(slots: List<RebuildSlot>, packed: Map<String, Int>): List<RebuildLeftover> {
        if (slots.isEmpty()) return emptyList()
        val outcome = slots.associate { s ->
            s.item.id to ItemOutcome(
                action = if (s.action == RebuildAction.SKIP) RebuildAction.SKIP else RebuildAction.DONE,
                removed = s.removed,
                filled = s.filled
            )
        }
        val left = computeLeftovers(slots.map { it.item }, outcome, packed)
        val info = HashMap<String, Pair<String?, String?>>()
        for (s in slots) {
            s.item.fromProductId?.let { info[it] = s.item.fromName to s.item.fromImagePath }
            s.item.toProductId?.let { info[it] = s.item.toName to s.item.toImagePath }
        }
        return left.map { (pid, v) ->
            RebuildLeftover(
                productId = pid,
                name = info[pid]?.first,
                imagePath = info[pid]?.second,
                van = v.van,
                machine = v.machine
            )
        }
    }

    /**
     * The plan to save when one slot gets a replacement outside the planner
     * (analysis tab, refill review): every pending item of the open request
     * is kept, [trayId] gets [productId], and the slot keeps the capacity it
     * already has on the request, else [trayCapacity]. Port of the merge in
     * `useMachineAnalysis.applySwap`.
     */
    fun queueReplacement(
        openItems: List<SlotChangeItem>,
        trayId: String,
        productId: String,
        trayCapacity: Int
    ): List<PlannedChange> {
        val changes = LinkedHashMap<String, PlannedChange>()
        for (item in openItems) {
            changes[item.trayId] = PlannedChange(item.trayId, item.toProductId, item.toCapacity)
        }
        changes[trayId] = PlannedChange(trayId, productId, changes[trayId]?.toCapacity ?: trayCapacity)
        return changes.values.toList()
    }
}
