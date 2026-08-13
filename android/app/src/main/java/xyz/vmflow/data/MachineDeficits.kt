package xyz.vmflow.data

import xyz.vmflow.models.StockSeverity
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TrayDeficit
import xyz.vmflow.models.WarehouseAvailability

/**
 * Per-product warehouse-availability deficit list for a machine's card in
 * the machine list — ported 1:1 from the deficit-building algorithm in
 * `ios/VMflow/ViewModels/MachineListViewModel.swift` (lines 137-239), using
 * the exact severity predicates from `ios/VMflow/Models/Tray.swift` (lines
 * 59-90: `isEmpty`, `isBelowMinStock`, `isBelowFillThreshold`, `deficit`).
 *
 * This is a presence-only warehouse check (does the warehouse have *any*
 * positive-quantity stock of a product — yes/no), not a quantity/coverage
 * calculation; [warehouseProductIds] is deliberately a `Set<String>` rather
 * than a quantity map, matching iOS's actual behaviour despite the feature
 * plan's looser "how much can be covered" prose.
 *
 * Performance here is a property of the PRODUCT, not the slot: a product
 * spread across multiple trays is aggregated into a single [TrayDeficit]
 * with the summed deficit and the worst (not best) severity across its
 * trays — aggregating per-tray instead would double-count it.
 *
 * Deliberately does not reuse `Tray.isLow`/`isCritical` (Android's existing
 * computed properties) — those use different, looser heuristics for a
 * different purpose (the Trays-tab row colouring and the Overview tab's
 * stock-summary card) and are out of scope for this task.
 */
data class MachineDeficitSummary(
    val trayDeficits: List<TrayDeficit>,
    val swapNeededCount: Int,
    val noStockCount: Int,
)

object MachineDeficits {

    private class ProductAccumulator(
        val productName: String,
        val imagePath: String?,
        var totalDeficit: Int,
        var worstSeverity: StockSeverity,
        val isDiscontinued: Boolean,
        var hasEmptyTray: Boolean,
    )

    /**
     * Builds the per-product deficit list plus the two warehouse-aware
     * summary counts for one machine's trays.
     *
     * @param warehouseProductIds product ids with any positive-quantity
     *   warehouse stock, fetched once for all machines (not per-machine).
     * @param hasWarehouses whether the warehouse system has any stock data
     *   at all — when false every row is [WarehouseAvailability.UNKNOWN]
     *   regardless of [warehouseProductIds] (mirrors iOS: no warehouse data
     *   means "we can't say", not "nothing available").
     * @param slotLabel formats the fallback row label for a tray with no
     *   resolvable product name (unassigned slot, or an assigned slot whose
     *   `products` relation wasn't joined), given the tray's `itemNumber`.
     *   Kept as an injected function rather than a hardcoded `"Slot $n"`
     *   string so this stays a pure, Android/Context-free function — the
     *   caller resolves the localized `R.string.machine_card_unassigned_slot`
     *   (same split as [parseRestartReason] returning a raw enum for the UI
     *   layer to localize in `DeviceHealthSheet.kt`).
     */
    fun computeDeficits(
        trays: List<Tray>,
        warehouseProductIds: Set<String>,
        hasWarehouses: Boolean,
        slotLabel: (Int) -> String,
    ): MachineDeficitSummary {
        val byProduct = LinkedHashMap<String, ProductAccumulator>()
        val unassigned = mutableListOf<ProductAccumulator>()

        for (tray in trays) {
            val minStock = tray.minStock ?: 0
            val fillWhenBelow = tray.fillWhenBelow ?: 0
            val isEmpty = tray.currentStock == 0
            val isBelowMinStock = minStock > 0 && tray.currentStock <= minStock
            val isBelowFillThreshold = fillWhenBelow > 0 && tray.currentStock <= fillWhenBelow

            val severity = when {
                isEmpty -> StockSeverity.CRITICAL
                isBelowMinStock -> StockSeverity.LOW
                isBelowFillThreshold -> StockSeverity.FILL_BELOW
                else -> null
            } ?: continue

            val productId = tray.productId
            if (productId != null) {
                val existing = byProduct[productId]
                if (existing != null) {
                    existing.totalDeficit += tray.deficit
                    if (severity < existing.worstSeverity) existing.worstSeverity = severity
                    if (isEmpty) existing.hasEmptyTray = true
                } else {
                    byProduct[productId] = ProductAccumulator(
                        productName = tray.products?.name ?: slotLabel(tray.itemNumber),
                        imagePath = tray.products?.imagePath,
                        totalDeficit = tray.deficit,
                        worstSeverity = severity,
                        isDiscontinued = tray.products?.discontinued ?: false,
                        hasEmptyTray = isEmpty,
                    )
                }
            } else {
                unassigned.add(
                    ProductAccumulator(
                        productName = tray.products?.name ?: slotLabel(tray.itemNumber),
                        imagePath = null,
                        totalDeficit = tray.deficit,
                        worstSeverity = severity,
                        isDiscontinued = false,
                        hasEmptyTray = isEmpty,
                    )
                )
            }
        }

        fun availability(productId: String?, hasEmptyTray: Boolean): WarehouseAvailability {
            if (!hasWarehouses || productId == null) return WarehouseAvailability.UNKNOWN
            if (productId in warehouseProductIds) return WarehouseAvailability.IN_STOCK
            return if (hasEmptyTray) WarehouseAvailability.NEEDS_SWAP else WarehouseAvailability.NO_STOCK
        }

        val allDeficits = mutableListOf<TrayDeficit>()
        for ((productId, accum) in byProduct) {
            allDeficits.add(
                TrayDeficit(
                    productName = accum.productName,
                    imagePath = accum.imagePath,
                    deficit = accum.totalDeficit,
                    severity = accum.worstSeverity,
                    isDiscontinued = accum.isDiscontinued,
                    warehouseAvailability = availability(productId, accum.hasEmptyTray),
                )
            )
        }
        for (accum in unassigned) {
            allDeficits.add(
                TrayDeficit(
                    productName = accum.productName,
                    imagePath = accum.imagePath,
                    deficit = accum.totalDeficit,
                    severity = accum.worstSeverity,
                    isDiscontinued = false,
                    warehouseAvailability = WarehouseAvailability.UNKNOWN,
                )
            )
        }

        val sorted = allDeficits.sortedWith(
            compareBy<TrayDeficit> { if (it.warehouseAvailability == WarehouseAvailability.NEEDS_SWAP) 0 else 1 }
                .thenBy { it.severity }
                .thenByDescending { it.deficit }
        )

        val swapNeededCount = byProduct.count { (productId, accum) ->
            hasWarehouses && productId !in warehouseProductIds && accum.hasEmptyTray
        }
        val noStockCount = byProduct.count { (productId, accum) ->
            hasWarehouses && productId !in warehouseProductIds && !accum.hasEmptyTray
        }

        return MachineDeficitSummary(
            trayDeficits = sorted,
            swapNeededCount = swapNeededCount,
            noStockCount = noStockCount,
        )
    }
}
