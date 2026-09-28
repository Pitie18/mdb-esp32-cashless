package xyz.vmflow.data

import xyz.vmflow.models.Tray

/**
 * Warehouse-aware stock classification for a fleet of machines.
 *
 * Ported 1:1 from the PWA's `management-frontend/app/lib/stock-health.ts`
 * (`classifyTrayStock`, `groupTraysByProduct`, `groupNeedsRefill`,
 * `distributeAcrossSlots`, `computeStockHealthPerMachine`,
 * `countMachineStockBuckets`) and mirrored on iOS in
 * `ios/VMflow/Models/MachineStockHealth.swift`, so the dashboard's stock
 * numbers agree across all three clients — change all three together.
 *
 * Stock is judged per **product within a machine**, not per slot: a product
 * that sits in several spirals is one [ProductStockGroup] whose stock,
 * capacity and thresholds are the sums over its slots. Cola in slots
 * 12/13/14 at 0/2/9 is 11 Cola, not one sold-out slot. A slot that is empty
 * while its product is still in another slot is only a hint
 * ([MachineStockSummary.emptySlotsWithStock]), never a critical machine. Before this existed the native
 * dashboards counted any empty tray as "critical" — including unassigned
 * slots and products the warehouse cannot refill — and reported more red
 * machines than the PWA.
 *
 * Like [MachineDeficits], warehouse availability is a presence check (does
 * any positive-quantity batch of that product exist), not a coverage
 * calculation. Deliberately does not reuse `Tray.isLow`/`isCritical`: those
 * are looser heuristics for the Trays tab's row colouring.
 */
enum class TrayStockState { CRITICAL, LOW, FILL, OK }

/**
 * Machine-level stock tier. Deliberately separate from
 * [xyz.vmflow.models.StockHealth] — that enum drives machine-card colouring
 * and has no `FILL` case; widening it is a UI change, not a counting one.
 */
enum class MachineStockTier { CRITICAL, LOW, FILL, OK }

/**
 * All slots of one product in one machine. The summed fields carry the same
 * names as a tray's so [StockHealth.classifyTray] applies unchanged.
 */
data class ProductStockGroup(
    val machineId: String,
    val productId: String,
    /** The slots holding this product, in input order. */
    val trays: List<Tray>,
    val currentStock: Int,
    val capacity: Int,
    val minStock: Int,
    val fillWhenBelow: Int,
    val state: TrayStockState,
    /** Units needed to fill every slot of the product. */
    val deficit: Int,
    /** Slots at 0 while the product still has stock in another slot. */
    val emptySlots: Int,
)

/** Per-machine roll-up of its trays. Counts are **products** (groups), not slots. */
data class MachineStockSummary(
    /** Products sold out in every slot whose product the warehouse can refill. */
    val refillableEmpty: Int = 0,
    /** Products at or below their summed `min_stock` that the warehouse can refill. */
    val refillableLow: Int = 0,
    /** Products at or below their summed `fill_when_below` that the warehouse can refill. */
    val refillableFill: Int = 0,
    /** Products needing attention that the warehouse has no stock of. */
    val noStockCount: Int = 0,
    /** Subset of [noStockCount] sold out in every slot — the swap candidates. */
    val noStockEmptyCount: Int = 0,
    /** Empty slots whose product is otherwise fine in this machine — a hint, not a health input. */
    val emptySlotsWithStock: Int = 0,
    val totalStock: Int = 0,
    val totalCapacity: Int = 0,
    /** Driven only by refillable products: critical > low > fill > ok. */
    val tier: MachineStockTier = MachineStockTier.OK,
    /** Stock as a percentage of capacity; 100 for a machine without capacity. */
    val percent: Int = 100,
)

/** Disjoint fleet-wide counts — every machine lands in at most one bucket. */
data class MachineStockBuckets(
    val critical: Int = 0,
    val low: Int = 0,
    val fill: Int = 0,
    /** Machines that are otherwise fine but hold a sold-out product the warehouse can't refill. */
    val swap: Int = 0,
    /** Machines in exactly one of the buckets above. */
    val needingAttention: Int = 0,
)

/**
 * How one slot is highlighted in the machine-detail tray list, judged on its
 * **product** group rather than on the slot alone. Mirrors the PWA's
 * `TrayStockFlag` (`management-frontend/app/lib/trayGroups.ts`).
 */
enum class TrayStockFlag {
    /** The product is sold out or at/below its summed min_stock, and this slot has room. */
    LOW,
    /** The product is below its summed fill threshold, and this slot has room. */
    FILL,
    /** This slot is empty, but the product is fine thanks to other slots. */
    SLOT_EMPTY,
    /** Nothing to do. */
    OK,
}

/** Products (not slots) needing refill in one machine, by severity. */
data class ProductRefillCounts(val soldOut: Int = 0, val low: Int = 0, val fill: Int = 0)

/** A slot's product group plus the derived per-slot flag. */
data class TrayGroupInfo(
    val group: ProductStockGroup,
    val needsRefill: Boolean,
    val flag: TrayStockFlag,
)

/** One row of the "by product" tray list. */
data class ProductListRow(
    val tray: Tray,
    /** Set on the first shown slot of a product that sits in two or more slots. */
    val header: ProductStockGroup? = null,
    /** True for every slot of such a multi-slot product (for indentation). */
    val inGroup: Boolean = false,
)

object StockHealth {

    /**
     * Classify one tray against its two independent thresholds.
     * A threshold of 0 (or null) means "disabled" and is skipped.
     */
    fun classifyTray(currentStock: Int, minStock: Int?, fillWhenBelow: Int?): TrayStockState {
        val min = minStock ?: 0
        val fill = fillWhenBelow ?: 0
        if (currentStock == 0) return TrayStockState.CRITICAL
        if (min > 0 && currentStock <= min) return TrayStockState.LOW
        if (fill > 0 && currentStock <= fill) return TrayStockState.FILL
        return TrayStockState.OK
    }

    /**
     * Whether a product can be refilled from the warehouse.
     *
     * With no warehouse data at all every product counts as refillable, so
     * operators who don't use the warehouse feature keep the old behaviour.
     */
    fun isProductRefillable(
        productId: String?,
        warehouseProductIds: Set<String>,
        hasWarehouses: Boolean,
    ): Boolean {
        if (productId == null) return false
        return !hasWarehouses || productId in warehouseProductIds
    }

    /**
     * Group assigned trays by (machine, product) and classify each group on
     * the summed values. Thresholds add up, so a product in one slot
     * classifies exactly like before and no per-product configuration is
     * needed; a disabled (0/null) threshold on one slot contributes nothing
     * to the sum. Unassigned trays are skipped. Groups keep first-appearance
     * order.
     */
    fun groupTraysByProduct(trays: List<Tray>): List<ProductStockGroup> {
        val grouped = LinkedHashMap<Pair<String, String>, MutableList<Tray>>()
        for (tray in trays) {
            val productId = tray.productId ?: continue
            grouped.getOrPut(tray.machineId to productId) { mutableListOf() }.add(tray)
        }
        return grouped.map { (key, slots) ->
            val currentStock = slots.sumOf { it.currentStock }
            val capacity = slots.sumOf { it.capacity }
            val minStock = slots.sumOf { it.minStock ?: 0 }
            val fillWhenBelow = slots.sumOf { it.fillWhenBelow ?: 0 }
            ProductStockGroup(
                machineId = key.first,
                productId = key.second,
                trays = slots,
                currentStock = currentStock,
                capacity = capacity,
                minStock = minStock,
                fillWhenBelow = fillWhenBelow,
                state = classifyTray(currentStock, minStock, fillWhenBelow),
                deficit = maxOf(0, capacity - currentStock),
                emptySlots = if (currentStock > 0) slots.count { it.currentStock == 0 } else 0,
            )
        }
    }

    /**
     * Whether a product group should be refilled. A `FILL`-tier group that is
     * already full (misconfigured `fill_when_below >= capacity`) moves nothing.
     */
    fun groupNeedsRefill(state: TrayStockState, deficit: Int): Boolean {
        if (state == TrayStockState.OK) return false
        if (state == TrayStockState.FILL && deficit <= 0) return false
        return true
    }

    fun groupNeedsRefill(group: ProductStockGroup): Boolean = groupNeedsRefill(group.state, group.deficit)

    /**
     * Per-tray view of [groupTraysByProduct], keyed by tray id. Unassigned
     * slots have no entry. Port of the PWA's `buildTrayGroupIndex`.
     */
    fun buildTrayGroupIndex(trays: List<Tray>): Map<String, TrayGroupInfo> {
        val index = LinkedHashMap<String, TrayGroupInfo>()
        for (group in groupTraysByProduct(trays)) {
            val needsRefill = groupNeedsRefill(group)
            for (tray in group.trays) {
                index[tray.id] = TrayGroupInfo(group, needsRefill, trayStockFlag(tray, group, needsRefill))
            }
        }
        return index
    }

    private fun trayStockFlag(tray: Tray, group: ProductStockGroup, needsRefill: Boolean): TrayStockFlag {
        val hasRoom = tray.capacity - tray.currentStock > 0
        if (needsRefill && hasRoom) {
            return if (group.state == TrayStockState.FILL) TrayStockFlag.FILL else TrayStockFlag.LOW
        }
        if (!needsRefill && tray.currentStock == 0 && group.currentStock > 0) return TrayStockFlag.SLOT_EMPTY
        return TrayStockFlag.OK
    }

    /**
     * List order for the "by product" view: products ordered by their lowest
     * slot number, a product's slots right after each other, unassigned slots
     * in their own place. [visible] (e.g. a search result) filters rows, but
     * the headers keep the totals of the whole product. Port of the PWA's
     * `productListRows`.
     */
    fun productListRows(
        trays: List<Tray>,
        index: Map<String, TrayGroupInfo>,
        visible: (Tray) -> Boolean = { true },
    ): List<ProductListRow> {
        val rows = mutableListOf<ProductListRow>()
        val done = HashSet<String>()
        for (tray in trays.sortedBy { it.itemNumber }) {
            if (tray.id in done) continue
            val group = index[tray.id]?.group
            val members = group?.trays?.sortedBy { it.itemNumber } ?: listOf(tray)
            members.forEach { done += it.id }
            val multi = members.size > 1
            members.filter(visible).forEachIndexed { i, member ->
                rows += ProductListRow(
                    tray = member,
                    header = if (multi && i == 0) group else null,
                    inGroup = multi,
                )
            }
        }
        return rows
    }

    /**
     * Summary counts for the machine-detail Overview card, in **products**:
     * products needing refill split by severity (sold out / low / top off).
     */
    fun productRefillCounts(trays: List<Tray>): ProductRefillCounts {
        var soldOut = 0
        var low = 0
        var fill = 0
        for (group in groupTraysByProduct(trays)) {
            if (!groupNeedsRefill(group)) continue
            when (group.state) {
                TrayStockState.CRITICAL -> soldOut++
                TrayStockState.LOW -> low++
                else -> fill++
            }
        }
        return ProductRefillCounts(soldOut, low, fill)
    }

    /**
     * Split [amount] units of one product across its slots, emptiest slot
     * first (ties by slot number), so no selection stays sold out while
     * another slot of the same product gets topped up. Each slot gets at most
     * its headroom (`capacity - currentStock`); an amount above the combined
     * headroom is capped there. Returns fill amounts aligned with [slots].
     */
    fun distributeAcrossSlots(slots: List<Tray>, amount: Int): List<Int> {
        val result = IntArray(slots.size)
        val order = slots.indices.sortedWith(
            compareBy<Int> { slots[it].currentStock }.thenBy { slots[it].itemNumber }
        )
        var remaining = maxOf(0, amount)
        for (i in order) {
            if (remaining <= 0) break
            val take = minOf(maxOf(0, slots[i].capacity - slots[i].currentStock), remaining)
            result[i] = take
            remaining -= take
        }
        return result.toList()
    }

    /**
     * Roll trays up per machine, keyed by `machine_id`.
     *
     * - Trays are grouped per product ([groupTraysByProduct]); every count is a product.
     * - Trays without a product only count towards the fill percentage.
     * - Groups needing refill ([groupNeedsRefill]) are split into refillable
     *   (product available in the warehouse) and no-stock.
     * - A group that needs nothing contributes its empty slots to
     *   [MachineStockSummary.emptySlotsWithStock].
     * - The tier is determined only by refillable groups, critical > low > fill.
     */
    fun summaries(
        trays: List<Tray>,
        warehouseProductIds: Set<String>,
        hasWarehouses: Boolean,
    ): Map<String, MachineStockSummary> {
        class Accumulator {
            var refillableEmpty = 0
            var refillableLow = 0
            var refillableFill = 0
            var noStockCount = 0
            var noStockEmptyCount = 0
            var emptySlotsWithStock = 0
            var totalStock = 0
            var totalCapacity = 0
        }

        val accumulators = LinkedHashMap<String, Accumulator>()

        for (tray in trays) {
            val entry = accumulators.getOrPut(tray.machineId) { Accumulator() }
            entry.totalStock += tray.currentStock
            entry.totalCapacity += tray.capacity
        }

        for (group in groupTraysByProduct(trays)) {
            val entry = accumulators.getOrPut(group.machineId) { Accumulator() }
            if (!groupNeedsRefill(group)) {
                entry.emptySlotsWithStock += group.emptySlots
                continue
            }

            if (isProductRefillable(group.productId, warehouseProductIds, hasWarehouses)) {
                when (group.state) {
                    TrayStockState.CRITICAL -> entry.refillableEmpty++
                    TrayStockState.LOW -> entry.refillableLow++
                    else -> entry.refillableFill++
                }
            } else {
                entry.noStockCount++
                if (group.state == TrayStockState.CRITICAL) entry.noStockEmptyCount++
            }
        }

        return accumulators.mapValues { (_, entry) ->
            MachineStockSummary(
                refillableEmpty = entry.refillableEmpty,
                refillableLow = entry.refillableLow,
                refillableFill = entry.refillableFill,
                noStockCount = entry.noStockCount,
                noStockEmptyCount = entry.noStockEmptyCount,
                emptySlotsWithStock = entry.emptySlotsWithStock,
                totalStock = entry.totalStock,
                totalCapacity = entry.totalCapacity,
                tier = when {
                    entry.refillableEmpty > 0 -> MachineStockTier.CRITICAL
                    entry.refillableLow > 0 -> MachineStockTier.LOW
                    entry.refillableFill > 0 -> MachineStockTier.FILL
                    else -> MachineStockTier.OK
                },
                percent = if (entry.totalCapacity > 0) {
                    Math.round(entry.totalStock.toDouble() / entry.totalCapacity * 100).toInt()
                } else {
                    100
                },
            )
        }
    }

    /**
     * Fold per-machine summaries into disjoint fleet-wide buckets, so the
     * counts sum to the number of machines needing attention and can never
     * exceed the fleet size.
     */
    fun buckets(summaries: Collection<MachineStockSummary>): MachineStockBuckets {
        var critical = 0
        var low = 0
        var fill = 0
        var swap = 0
        var needingAttention = 0

        for (summary in summaries) {
            when (summary.tier) {
                MachineStockTier.CRITICAL -> critical++
                MachineStockTier.LOW -> low++
                MachineStockTier.FILL -> fill++
                MachineStockTier.OK -> {
                    if (summary.noStockEmptyCount == 0) continue
                    swap++
                }
            }
            needingAttention++
        }

        return MachineStockBuckets(critical, low, fill, swap, needingAttention)
    }
}
