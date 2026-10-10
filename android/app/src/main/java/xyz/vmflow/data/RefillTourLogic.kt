package xyz.vmflow.data

import java.text.Collator
import xyz.vmflow.models.CombinedPackingItem
import xyz.vmflow.models.MachineNeed
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.Tray
import xyz.vmflow.models.VendingMachineWithEmbedded
import xyz.vmflow.models.WarehousePositionGroup
import xyz.vmflow.models.WarehouseProductPosition

/**
 * Pure refill-tour math: packing list, quantity capping, the deduction set
 * charged to the warehouse, and warehouse pick order. Ported 1:1 from
 * `ios/VMflow/ViewModels/RefillWizardViewModel.swift`:
 *   - warehouse-remainder helpers ([committedQuantity], [remainingWarehouseStock],
 *     [isOutOfStockForMachine]): L440-500
 *   - [buildCombinedPackingList] incl. sorting: L498-612
 *   - quantity math ([packingQuantity], [displayQuantity], [maxPackingQuantity]): L780-940
 *   - pick order flatten ([flattenPickOrder], iOS `fetchOrderedProductIds`): L1456-1545
 *   - tour visit order ([sortByVisitOrder], iOS `buildRefillMachines`' sort): L1017-1022
 *   - `startTour` distribution ([applyTourInclusion]) + `deductWarehouseStock`
 *     ([buildDeductions]): L1652-1800
 *
 * [buildDeductions] is the fix for the bug that motivated this whole phase:
 * the iOS app used to deduct warehouse stock for every product a machine's
 * trays *could* hold, not just the products the driver actually packed —
 * over-charging the warehouse by ~334 units across 53 refill tours. It
 * deducts only the intersection of `packedItems` and the machine's actual
 * tray products, never "every tray product".
 *
 * **Which machines and slots the tour covers follows the PWA refill wizard**
 * (`management-frontend/app/composables/useRefillWizard.ts`, on top of
 * `app/lib/stock-health.ts`): stock is judged per product group
 * ([StockHealth.groupTraysByProduct]); a machine joins the tour when at
 * least one product the warehouse can refill needs a refill
 * ([StockHealth.groupNeedsRefill], fill-only machines included); its packing
 * needs are every product group needing refill with the group deficit
 * (including products without warehouse stock, which the pack step shows as
 * out of stock); and the refill step only covers the slots of those groups,
 * with the packed amount spread emptiest slot first. Unassigned slots are
 * never part of a tour.
 *
 * Nothing here touches coroutines, Supabase, Android, a clock, or
 * randomness — everything it needs comes in as parameters, same split as
 * `WarehouseIntakeLogic.kt` / `MachineAnalysis.kt`.
 */

/** One product-machine deduction to charge against warehouse stock (FIFO), post-tour-start. */
data class PackedDeduction(val machineId: String, val productId: String, val quantity: Int)

/** One tray's resolved fill amount, for the RPC payload a later task builds. */
data class TrayFill(val trayId: String, val fillAmount: Int)

object RefillTourLogic {

    /**
     * Packing quantity for a machine-product pair: the pinned custom quantity
     * if one was set, else the product's **group deficit** (its slots'
     * capacity minus their stock, summed) — but only while the product group
     * needs a refill ([StockHealth.groupNeedsRefill]); a product that is fine
     * in this machine needs nothing. PWA `effectiveDeficit` before the
     * warehouse cap.
     */
    fun packingQuantity(
        machine: RefillMachine,
        productId: String,
        customQuantities: Map<String, Map<String, Int>>
    ): Int {
        val custom = customQuantities[machine.machine.id]?.get(productId)
        if (custom != null) return custom
        return refillGroups(machine).firstOrNull { it.productId == productId }?.deficit ?: 0
    }

    // ── Tour scope (PWA `initTour` / `effectiveStockHealth`) ─────────────

    /**
     * Product groups of [machine] that need a refill, judged on the trays'
     * current stock. Slots with an accepted change-note item
     * ([RefillMachine.rebuildTrayIds]) are left out: they get the rebuild
     * instead of a normal refill (PWA `buildRefillMachine`).
     */
    fun refillGroups(machine: RefillMachine): List<ProductStockGroup> {
        val excluded = machine.rebuildTrayIds
        return StockHealth.groupTraysByProduct(
            machine.trays.map { it.tray }.filter { it.id !in excluded }
        ).filter { StockHealth.groupNeedsRefill(it) }
    }

    /** Whether the warehouse can refill [productId] for [machine] (see [RefillMachine.refillableProductIds]). */
    fun isRefillable(machine: RefillMachine, productId: String): Boolean =
        machine.refillableProductIds?.contains(productId) ?: true

    /**
     * The machine's tour health, driven only by refillable product groups:
     * critical if one is sold out in every slot, else low, else fill, else ok.
     */
    fun machineTier(machine: RefillMachine): MachineStockTier {
        var low = false
        var fill = false
        for (group in refillGroups(machine)) {
            if (!isRefillable(machine, group.productId)) continue
            when (group.state) {
                TrayStockState.CRITICAL -> return MachineStockTier.CRITICAL
                TrayStockState.LOW -> low = true
                else -> fill = true
            }
        }
        return when {
            low -> MachineStockTier.LOW
            fill -> MachineStockTier.FILL
            else -> MachineStockTier.OK
        }
    }

    /** Refillable products sold out or low — the PWA's `low_trays`, the tour order's tie-breaker. */
    fun lowOrEmptyProductCount(machine: RefillMachine): Int =
        refillGroups(machine).count {
            isRefillable(machine, it.productId) &&
                (it.state == TrayStockState.CRITICAL || it.state == TrayStockState.LOW)
        }

    /**
     * The tier the pack step shows for a machine, PWA `effectiveStockHealth`:
     * [machineTier], downgraded to OK once the selected warehouse's stock is
     * known and **none** of the machine's products needing refill has stock
     * there. Uses the raw warehouse totals, not what is left after other
     * machines' commitments, so packing doesn't make a machine flip to OK.
     *
     * While no stock is loaded ([stockLoaded] false — no warehouse selected,
     * still loading, or a failed load) the tier is shown as is.
     */
    fun displayTier(
        machine: RefillMachine,
        warehouseStock: Map<String, Int>,
        stockLoaded: Boolean
    ): MachineStockTier {
        val tier = machineTier(machine)
        if (tier == MachineStockTier.OK || !stockLoaded) return tier
        val anyInStock = refillGroups(machine).any { (warehouseStock[it.productId] ?: 0) > 0 }
        return if (anyInStock) tier else MachineStockTier.OK
    }

    /**
     * Builds a [RefillMachine] for every machine that has trays, resolving
     * which of its products the warehouse can refill. Initial fill amounts
     * are the slot deficits of product groups that need a refill, 0 for every
     * other slot (including unassigned ones).
     *
     * @param warehouseProductIds product ids with any positive batch, company-wide.
     * @param hasWarehouses false when there is no warehouse stock at all —
     *   then every product counts as refillable.
     */
    fun buildRefillMachines(
        machines: List<VendingMachineWithEmbedded>,
        trays: List<Tray>,
        warehouseProductIds: Set<String>,
        hasWarehouses: Boolean
    ): List<RefillMachine> {
        val traysByMachine = trays.groupBy { it.machineId }
        return machines.mapNotNull { machine ->
            val machineTrays = traysByMachine[machine.id]
            if (machineTrays.isNullOrEmpty()) return@mapNotNull null
            val needing = StockHealth.groupTraysByProduct(machineTrays)
                .filter { StockHealth.groupNeedsRefill(it) }
                .flatMapTo(HashSet()) { group -> group.trays.map { it.id } }
            RefillMachine(
                machine = machine,
                trays = machineTrays.map { tray ->
                    RefillTray(tray = tray, fillAmount = if (tray.id in needing) tray.deficit else 0)
                },
                refillableProductIds = if (hasWarehouses) {
                    machineTrays.mapNotNullTo(HashSet()) { it.productId }.filterTo(HashSet()) { it in warehouseProductIds }
                } else {
                    null
                }
            )
        }
    }

    /**
     * The tour's machines, PWA `initTour`: every machine with at least one
     * refillable product group needing refill (fill-only machines included)
     * or an open slot change request, in [sortByVisitOrder] order.
     */
    fun tourMachines(machines: List<RefillMachine>): List<RefillMachine> =
        sortByVisitOrder(
            machines.filter {
                machineTier(it) != MachineStockTier.OK || it.changeRequest?.items?.isNotEmpty() == true
            }
        )

    /** Total quantity already committed (packed) for a product, across all machines that packed it. */
    fun committedQuantity(
        machines: List<RefillMachine>,
        productId: String,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>
    ): Int {
        return machines
            .filter { packedItems[it.machine.id]?.contains(productId) == true }
            .sumOf { packingQuantity(it, productId, customQuantities) }
    }

    /** Warehouse stock left for a product after subtracting what's already committed. Never negative. */
    fun remainingWarehouseStock(
        machines: List<RefillMachine>,
        productId: String,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>,
        warehouseStock: Map<String, Int>
    ): Int {
        val stock = warehouseStock[productId] ?: return 0
        val committed = committedQuantity(machines, productId, packedItems, customQuantities)
        return maxOf(0, stock - committed)
    }

    /**
     * Max quantity a machine could pack for a product: capped by tray
     * capacity and, once warehouse stock is loaded, by what's left after
     * every OTHER packed machine's commitment.
     *
     * `stockLoaded == false`, or an empty [warehouseStock] map, means only
     * the tray-capacity cap applies. Once stock is loaded, a product that's
     * simply absent from the warehouse map caps at 0.
     */
    fun maxPackingQuantity(
        machines: List<RefillMachine>,
        machineId: String,
        productId: String,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>,
        warehouseStock: Map<String, Int>,
        stockLoaded: Boolean
    ): Int {
        val machine = machines.find { it.machine.id == machineId } ?: return 0
        val rebuildTrayIds = machine.rebuildTrayIds
        val trayMax = machine.trays
            .filter { it.tray.productId == productId && it.tray.id !in rebuildTrayIds }
            .sumOf { maxOf(0, it.tray.capacity - it.tray.currentStock) }

        if (!stockLoaded || warehouseStock.isEmpty()) return trayMax
        val stock = warehouseStock[productId] ?: return 0

        val otherCommitted = machines
            .filter { it.machine.id != machineId && packedItems[it.machine.id]?.contains(productId) == true }
            .sumOf { packingQuantity(it, productId, customQuantities) }
        val available = maxOf(0, stock - otherCommitted)
        return minOf(trayMax, available)
    }

    /**
     * Quantity to show in the UI: the committed [packingQuantity] for an
     * already-packed machine (never re-capped — that's the truth used for
     * deduction), else [packingQuantity] capped by [maxPackingQuantity] so
     * an unpacked row never advertises more than the warehouse can deliver.
     */
    fun displayQuantity(
        machines: List<RefillMachine>,
        machineId: String,
        productId: String,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>,
        warehouseStock: Map<String, Int>,
        stockLoaded: Boolean
    ): Int {
        val machine = machines.find { it.machine.id == machineId } ?: return 0
        val current = packingQuantity(machine, productId, customQuantities)
        val isPacked = packedItems[machineId]?.contains(productId) == true
        if (isPacked) return current
        val cap = maxPackingQuantity(
            machines, machineId, productId, packedItems, customQuantities, warehouseStock, stockLoaded
        )
        return minOf(current, cap)
    }

    /**
     * Whether a machine-product pair is out of remaining warehouse stock.
     * `false` while stock isn't loaded, and `false` for a machine that
     * already packed the product (it has its allocation — a later stock
     * change shouldn't retroactively flag it).
     */
    fun isOutOfStockForMachine(
        machines: List<RefillMachine>,
        machineId: String,
        productId: String,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>,
        warehouseStock: Map<String, Int>,
        stockLoaded: Boolean
    ): Boolean {
        if (!stockLoaded || warehouseStock.isEmpty()) return false
        val isPacked = packedItems[machineId]?.contains(productId) == true
        if (isPacked) return false
        return remainingWarehouseStock(machines, productId, packedItems, customQuantities, warehouseStock) <= 0
    }

    /**
     * The set of warehouse deductions to charge for a tour: for every packed
     * machine, over the INTERSECTION of its `packedItems` entry and the
     * productIds its trays actually hold — never over every tray product,
     * which is the bug this whole phase exists to fix. Zero-quantity
     * deductions are dropped (no RPC call with quantity 0).
     */
    fun buildDeductions(
        machines: List<RefillMachine>,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>
    ): List<PackedDeduction> {
        val deductions = mutableListOf<PackedDeduction>()
        for (machine in machines) {
            if (!machine.isPacked) continue
            val trayProductIds = machine.trays.mapNotNull { it.tray.productId }.toSet()
            val packedProductIds = packedItems[machine.machine.id] ?: emptySet()
            val productIds = packedProductIds.intersect(trayProductIds).sorted()
            for (productId in productIds) {
                val qty = packingQuantity(machine, productId, customQuantities)
                if (qty > 0) {
                    deductions.add(PackedDeduction(machine.machine.id, productId, qty))
                }
            }
        }
        return deductions
    }

    /**
     * Applies the same packed/product gating as [buildDeductions] to the
     * trays themselves, producing the tour-scoped machine list `startTour()`
     * builds — the PWA refill step (`loadTraysForCurrentMachine`):
     *  - Unpacked machine: every tray gets `isInTour = false`.
     *  - Unassigned slot, or a slot whose product was not packed or does not
     *    need a refill in this machine ([refillGroups]): `isInTour = false`,
     *    `fillAmount = 0`.
     *  - Slots of a packed product group needing refill: the packed quantity
     *    ([packingQuantity] — the pinned custom quantity, else the group
     *    deficit) is split with [StockHealth.distributeAcrossSlots],
     *    **emptiest slot first** (ties by slot number), so a short-packed
     *    product never leaves one selection sold out while another slot of
     *    the same product gets topped up. A slot that receives 0 is hidden
     *    (`isInTour = false`), exactly like the PWA.
     *
     * Ledger invariant: the packed quantity is the number the driver
     * physically carried and the number [buildDeductions] charges the
     * warehouse, so the fills sum to **exactly** that quantity — the one
     * exception being a pin above the slots' combined headroom
     * (`capacity - currentStock`), where no slot may be overfilled and the
     * machine is credited that headroom instead (the safe direction).
     * Deterministic: the order depends only on stock and slot number, never
     * on input order, so a resume distributes the same way.
     */
    fun applyTourInclusion(
        machines: List<RefillMachine>,
        packedItems: Map<String, Set<String>>,
        customQuantities: Map<String, Map<String, Int>>
    ): List<RefillMachine> {
        return machines.map { machine ->
            if (!machine.isPacked) {
                return@map machine.copy(trays = machine.trays.map { it.copy(isInTour = false) })
            }

            val packedProductIds = packedItems[machine.machine.id] ?: emptySet()

            val fills = mutableMapOf<String, Int>() // trayId -> fillAmount
            for (group in refillGroups(machine)) {
                if (group.productId !in packedProductIds) continue
                val amount = packingQuantity(machine, group.productId, customQuantities)
                val amounts = StockHealth.distributeAcrossSlots(group.trays, amount)
                group.trays.forEachIndexed { index, tray -> fills[tray.id] = amounts[index] }
            }

            val newTrays = machine.trays.map { rt ->
                val fill = fills[rt.tray.id] ?: 0
                rt.copy(isInTour = fill > 0, fillAmount = fill)
            }
            machine.copy(trays = newTrays)
        }
    }

    /**
     * The tour's visit order, PWA `initTour`'s sort: by tour health
     * ([machineTier] — critical, low, fill, ok), then by the number of
     * refillable products sold out or low ([lowOrEmptyProductCount])
     * descending. Two extra tie-breakers keep it a total order so the same
     * tour never lists machines differently on a resume or a recomposition:
     * [RefillMachine.totalDeficit] descending, then machine id ascending.
     *
     * Sorts the whole list, packed and unpacked alike, so `machines` stays
     * the single ordered source the refill step walks; unpacked machines are
     * filtered out by the caller, not by this sort.
     */
    fun sortByVisitOrder(machines: List<RefillMachine>): List<RefillMachine> =
        machines.sortedWith(
            compareBy<RefillMachine> { machineTier(it).ordinal }
                .thenByDescending { lowOrEmptyProductCount(it) }
                .thenByDescending { it.totalDeficit }
                .thenBy { it.machine.id }
        )

    /**
     * Products needed across ALL machines (independent of `isPacked`): every
     * product group needing refill ([refillGroups]) with its group deficit,
     * grouped by product into one [CombinedPackingItem] per product with a
     * [MachineNeed] per machine that needs it.
     *
     * Sorting is a total order so re-renders never swap row positions
     * (`Map`/`Set` iteration order isn't guaranteed):
     *  - No [pickOrder]: `totalQuantity` descending, then product name via
     *    [nameComparator] (locale-aware, case-insensitive; a missing name
     *    sorts last), then `productId` as the final tiebreaker.
     *  - With [pickOrder]: positioned products first in position order,
     *    unpositioned products after, then the same name/id tiebreakers.
     *
     * @param nameComparator comparator for the product-name tiebreaker.
     *   Defaults to a [Collator]-backed comparator resolved fresh on every
     *   call that omits it (never cached in a top-level `val`, so it always
     *   reflects the JVM's current default locale rather than a value
     *   frozen at class-load time) — still ambient state, but the default
     *   can be overridden with an explicit comparator, which is how the
     *   tests pin a specific locale's ordering deterministically.
     */
    fun buildCombinedPackingList(
        machines: List<RefillMachine>,
        pickOrder: Map<String, Int>,
        nameComparator: Comparator<String?> = defaultProductNameComparator()
    ): List<CombinedPackingItem> {
        data class Accumulator(
            var productName: String?,
            var imagePath: String?,
            var sellprice: Double?,
            var totalQuantity: Int,
            val needs: LinkedHashMap<String, MachineNeed>
        )

        val grouped = LinkedHashMap<String, Accumulator>()
        for (machine in machines) {
            // One need per product group needing refill, with the group deficit
            // (PWA `tray_summary`) — products without warehouse stock included.
            for (group in refillGroups(machine)) {
                if (group.deficit <= 0) continue
                val product = group.trays.firstNotNullOfOrNull { it.products }
                val acc = grouped.getOrPut(group.productId) {
                    Accumulator(
                        productName = product?.name,
                        imagePath = product?.imagePath,
                        sellprice = product?.sellprice,
                        totalQuantity = 0,
                        needs = LinkedHashMap()
                    )
                }
                acc.totalQuantity += group.deficit
                acc.needs[machine.machine.id] = MachineNeed(
                    machineId = machine.machine.id,
                    machineName = machine.machine.displayName,
                    quantity = group.deficit,
                    capacity = group.capacity
                )
            }
        }

        val items = grouped.map { (productId, acc) ->
            CombinedPackingItem(
                productId = productId,
                productName = acc.productName,
                imagePath = acc.imagePath,
                sellprice = acc.sellprice,
                totalQuantity = acc.totalQuantity,
                machineNeeds = acc.needs.values.sortedBy { it.machineName }
            )
        }

        return if (pickOrder.isEmpty()) {
            items.sortedWith(
                compareByDescending<CombinedPackingItem> { it.totalQuantity }
                    .thenBy(nameComparator) { it.productName }
                    .thenBy { it.productId }
            )
        } else {
            items.sortedWith(
                compareBy<CombinedPackingItem> { pickOrder[it.productId] ?: Int.MAX_VALUE }
                    .thenBy(nameComparator) { it.productName }
                    .thenBy { it.productId }
            )
        }
    }

    /**
     * Locale-aware, case-insensitive comparator for the packing-list name
     * tiebreaker: a [Collator] at [Collator.SECONDARY] strength ignores case
     * but keeps accents significant ("o" and "ö" stay distinct, while "Ö"
     * sorts right next to "o" instead of far behind "z"), matching iOS's
     * `localizedCaseInsensitiveCompare` (`RefillWizardViewModel.swift` L624)
     * far more closely than ordinal `String.CASE_INSENSITIVE_ORDER`, which
     * sorts an umlaut like "Ö" after every plain ASCII letter instead of
     * near "O". Null names sort last (`nullsLast`) — an unresolved/unnamed
     * product is deprioritized under a real product name rather than
     * interleaved with them — and a [Collator] can return 0 for two
     * genuinely different strings, so callers must keep running the
     * `productId` tiebreaker after this one to stay a total order.
     */
    internal fun defaultProductNameComparator(): Comparator<String?> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return nullsLast(Comparator(collator::compare))
    }

    /**
     * Flattens the warehouse position-group tree into a walk order: groups
     * nest via `parentId` (a group with an unknown/missing parent is a
     * root), each level sorted by `sortOrder`, depth-first — a node's own
     * product positions first, then its children recursively. Positions
     * whose `groupId` doesn't resolve to a known group are appended,
     * ungrouped, at the very end.
     */
    fun flattenPickOrder(
        groups: List<WarehousePositionGroup>,
        positions: List<WarehouseProductPosition>
    ): List<String> {
        class Node(val group: WarehousePositionGroup) {
            val children = mutableListOf<Node>()
            val productIds = mutableListOf<String>()
        }

        val nodeMap = groups.associateBy({ it.id }) { Node(it) }

        val roots = mutableListOf<Node>()
        for (node in nodeMap.values) {
            val parent = node.group.parentId?.let { nodeMap[it] }
            if (parent != null) parent.children.add(node) else roots.add(node)
        }

        fun sortChildren(node: Node) {
            node.children.sortBy { it.group.sortOrder }
            node.children.forEach { sortChildren(it) }
        }
        roots.sortBy { it.group.sortOrder }
        roots.forEach { sortChildren(it) }

        val ungrouped = mutableListOf<String>()
        for (p in positions.sortedBy { it.sortOrder }) {
            val node = p.groupId?.let { nodeMap[it] }
            if (node != null) node.productIds.add(p.productId) else ungrouped.add(p.productId)
        }

        val result = mutableListOf<String>()
        fun traverse(nodes: List<Node>) {
            for (node in nodes) {
                result.addAll(node.productIds)
                traverse(node.children)
            }
        }
        traverse(roots)
        result.addAll(ungrouped)
        return result
    }
}
