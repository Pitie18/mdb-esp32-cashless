package xyz.vmflow.data

import xyz.vmflow.models.Tray

/**
 * Warehouse-aware stock classification for a fleet of machines.
 *
 * Ported 1:1 from the PWA's `management-frontend/app/lib/stock-health.ts`
 * (`classifyTrayStock`, `computeStockHealthPerMachine`,
 * `countMachineStockBuckets`) and mirrored on iOS in
 * `ios/VMflow/Models/MachineStockHealth.swift`, so the dashboard's stock
 * numbers agree across all three clients. Before this existed the native
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

/** Per-machine roll-up of its trays. */
data class MachineStockSummary(
    /** Trays that are empty and whose product the warehouse can refill. */
    val refillableEmpty: Int = 0,
    /** Trays at or below `min_stock` whose product the warehouse can refill. */
    val refillableLow: Int = 0,
    /** Trays at or below `fill_when_below` whose product the warehouse can refill. */
    val refillableFill: Int = 0,
    /** Trays needing attention whose product the warehouse has no stock of. */
    val noStockCount: Int = 0,
    /** Subset of [noStockCount] that is empty — the swap candidates. */
    val noStockEmptyCount: Int = 0,
    val totalStock: Int = 0,
    val totalCapacity: Int = 0,
    /** Driven only by refillable trays: critical > low > fill > ok. */
    val tier: MachineStockTier = MachineStockTier.OK,
    /** Stock as a percentage of capacity; 100 for a machine without capacity. */
    val percent: Int = 100,
)

/** Disjoint fleet-wide counts — every machine lands in at most one bucket. */
data class MachineStockBuckets(
    val critical: Int = 0,
    val low: Int = 0,
    val fill: Int = 0,
    /** Machines that are otherwise fine but hold an empty tray the warehouse can't refill. */
    val swap: Int = 0,
    /** Machines in exactly one of the buckets above. */
    val needingAttention: Int = 0,
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
     * Roll trays up per machine, keyed by `machine_id`.
     *
     * - Trays without a product are ignored — there is nothing to refill.
     * - A `FILL`-tier tray already at capacity (misconfigured
     *   `fill_when_below >= capacity`) is ignored: refilling it moves nothing.
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
            var totalStock = 0
            var totalCapacity = 0
        }

        val accumulators = LinkedHashMap<String, Accumulator>()

        for (tray in trays) {
            val entry = accumulators.getOrPut(tray.machineId) { Accumulator() }

            entry.totalStock += tray.currentStock
            entry.totalCapacity += tray.capacity

            val productId = tray.productId ?: continue

            val state = classifyTray(tray.currentStock, tray.minStock, tray.fillWhenBelow)
            if (state == TrayStockState.OK) continue
            if (state == TrayStockState.FILL && tray.capacity - tray.currentStock <= 0) continue

            if (isProductRefillable(productId, warehouseProductIds, hasWarehouses)) {
                when (state) {
                    TrayStockState.CRITICAL -> entry.refillableEmpty++
                    TrayStockState.LOW -> entry.refillableLow++
                    else -> entry.refillableFill++
                }
            } else {
                entry.noStockCount++
                if (state == TrayStockState.CRITICAL) entry.noStockEmptyCount++
            }
        }

        return accumulators.mapValues { (_, entry) ->
            MachineStockSummary(
                refillableEmpty = entry.refillableEmpty,
                refillableLow = entry.refillableLow,
                refillableFill = entry.refillableFill,
                noStockCount = entry.noStockCount,
                noStockEmptyCount = entry.noStockEmptyCount,
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
