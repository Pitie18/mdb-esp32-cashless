package xyz.vmflow.data

import xyz.vmflow.models.StockSeverity
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TrayDeficit
import xyz.vmflow.models.WarehouseAvailability

/**
 * Per-product warehouse-availability deficit list for a machine's card in
 * the machine list. Originally ported from the deficit-building algorithm in
 * `ios/VMflow/ViewModels/MachineListViewModel.swift`; since the multi-slot
 * rework it sits on top of [StockHealth.groupTraysByProduct], mirroring the
 * PWA's `management-frontend/app/composables/useMachines.ts` (which builds
 * on `app/lib/stock-health.ts`) and iOS `MachineStockHealth.swift`.
 *
 * This is a presence-only warehouse check (does the warehouse have *any*
 * positive-quantity stock of a product — yes/no), not a quantity/coverage
 * calculation; [warehouseProductIds] is deliberately a `Set<String>` rather
 * than a quantity map, matching iOS's actual behaviour.
 *
 * Stock is judged per PRODUCT, not per slot: a product spread across several
 * trays is one group whose stock, capacity and thresholds are the sums over
 * its slots, classified on those sums. Cola at 0/2/9 in three slots is 11
 * Cola — a `FILL` row with the group deficit, not a critical one. Only a
 * product sold out in every slot is `CRITICAL` (and, without warehouse
 * stock, `NEEDS_SWAP`). A group that needs nothing produces no row at all,
 * even if one of its slots is empty — that is the
 * [MachineStockSummary.emptySlotsWithStock] hint, not a deficit.
 *
 * Unassigned trays (no product) are not a product group; each keeps its own
 * per-slot row as before (iOS parity — the PWA skips them).
 *
 * Deliberately does not reuse `Tray.isLow`/`isCritical` (Android's existing
 * computed properties) — those use different, looser heuristics for the
 * Trays-tab row colouring.
 */
data class MachineDeficitSummary(
    val trayDeficits: List<TrayDeficit>,
    /** Products the warehouse can't refill that are sold out in every slot. */
    val swapNeededCount: Int,
    /** Products needing refill the warehouse can't refill that still have stock somewhere. */
    val noStockCount: Int,
)

object MachineDeficits {

    private fun severityOf(state: TrayStockState): StockSeverity? = when (state) {
        TrayStockState.CRITICAL -> StockSeverity.CRITICAL
        TrayStockState.LOW -> StockSeverity.LOW
        TrayStockState.FILL -> StockSeverity.FILL_BELOW
        TrayStockState.OK -> null
    }

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
     *   Kept as an injected function so this stays a pure, Context-free
     *   function — the caller resolves the localized
     *   `R.string.machine_card_unassigned_slot`.
     */
    fun computeDeficits(
        trays: List<Tray>,
        warehouseProductIds: Set<String>,
        hasWarehouses: Boolean,
        slotLabel: (Int) -> String,
    ): MachineDeficitSummary {
        val allDeficits = mutableListOf<TrayDeficit>()
        var swapNeededCount = 0
        var noStockCount = 0

        for (group in StockHealth.groupTraysByProduct(trays)) {
            if (!StockHealth.groupNeedsRefill(group)) continue
            val severity = severityOf(group.state) ?: continue
            val first = group.trays.first()
            val product = group.trays.firstNotNullOfOrNull { it.products }

            val availability = when {
                !hasWarehouses -> WarehouseAvailability.UNKNOWN
                group.productId in warehouseProductIds -> WarehouseAvailability.IN_STOCK
                group.state == TrayStockState.CRITICAL -> WarehouseAvailability.NEEDS_SWAP
                else -> WarehouseAvailability.NO_STOCK
            }
            when (availability) {
                WarehouseAvailability.NEEDS_SWAP -> swapNeededCount++
                WarehouseAvailability.NO_STOCK -> noStockCount++
                else -> Unit
            }

            allDeficits.add(
                TrayDeficit(
                    productName = product?.name ?: slotLabel(first.itemNumber),
                    imagePath = product?.imagePath,
                    deficit = group.deficit,
                    severity = severity,
                    isDiscontinued = product?.discontinued ?: false,
                    warehouseAvailability = availability,
                )
            )
        }

        // Unassigned slots: one row each, never merged, availability unknown.
        for (tray in trays) {
            if (tray.productId != null) continue
            val severity = severityOf(
                StockHealth.classifyTray(tray.currentStock, tray.minStock, tray.fillWhenBelow)
            ) ?: continue
            allDeficits.add(
                TrayDeficit(
                    productName = tray.products?.name ?: slotLabel(tray.itemNumber),
                    imagePath = null,
                    deficit = tray.deficit,
                    severity = severity,
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

        return MachineDeficitSummary(
            trayDeficits = sorted,
            swapNeededCount = swapNeededCount,
            noStockCount = noStockCount,
        )
    }
}
