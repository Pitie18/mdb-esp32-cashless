package xyz.vmflow.data

import kotlin.math.roundToInt
import xyz.vmflow.models.CombinedPackingItem
import xyz.vmflow.models.MachineNeed
import xyz.vmflow.models.RefillMachine
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
     * if one was set, else the sum of deficits across this machine's trays
     * holding this product (deficit > 0 only — full trays contribute 0
     * anyway, since [xyz.vmflow.models.Tray.deficit] is already clamped at 0,
     * but the filter mirrors iOS exactly).
     */
    fun packingQuantity(
        machine: RefillMachine,
        productId: String,
        customQuantities: Map<String, Map<String, Int>>
    ): Int {
        val custom = customQuantities[machine.machine.id]?.get(productId)
        if (custom != null) return custom
        return machine.trays
            .filter { it.tray.productId == productId && it.deficit > 0 }
            .sumOf { it.deficit }
    }

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
        val trayMax = machine.trays
            .filter { it.tray.productId == productId }
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
     * builds:
     *  - Unpacked machine: every tray gets `isInTour = false`.
     *  - Packed machine, product-less tray: always stays in the tour with
     *    its existing `fillAmount` (the driver refills it manually).
     *  - Packed machine, tray whose product was NOT packed: `isInTour =
     *    false`, `fillAmount = 0`.
     *  - Packed machine, tray whose product WAS packed: `isInTour = true`;
     *    if a custom quantity is pinned for that product, its `fillAmount`
     *    is redistributed proportionally across every tray of that product
     *    with `deficit > 0` (`ratio = customQty / totalDeficit`, each tray's
     *    `deficit * ratio` rounded and clamped to `0..(capacity -
     *    currentStock)`); otherwise the tray keeps its existing `fillAmount`.
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
            val machineCustom = customQuantities[machine.machine.id] ?: emptyMap()

            // Precompute the proportional-fill override per tray, for every
            // packed product that has a pinned custom quantity.
            val overrides = mutableMapOf<String, Int>() // trayId -> fillAmount
            val productsWithCustom = machine.trays
                .mapNotNull { it.tray.productId }
                .toSet()
                .filter { it in packedProductIds && machineCustom[it] != null }
            for (productId in productsWithCustom) {
                val customQty = machineCustom.getValue(productId)
                val productTrays = machine.trays.filter { it.tray.productId == productId && it.deficit > 0 }
                val totalDeficit = productTrays.sumOf { it.deficit }
                if (totalDeficit <= 0) continue
                val ratio = customQty.toDouble() / totalDeficit.toDouble()
                for (pt in productTrays) {
                    val newAmount = (pt.deficit * ratio).roundToInt()
                    val maxFill = pt.tray.capacity - pt.tray.currentStock
                    overrides[pt.tray.id] = newAmount.coerceIn(0, maxOf(0, maxFill))
                }
            }

            val newTrays = machine.trays.map { rt ->
                val productId = rt.tray.productId
                when {
                    productId == null -> rt.copy(isInTour = true)
                    productId !in packedProductIds -> rt.copy(isInTour = false, fillAmount = 0)
                    else -> {
                        val override = overrides[rt.tray.id]
                        if (override != null) rt.copy(isInTour = true, fillAmount = override) else rt.copy(isInTour = true)
                    }
                }
            }
            machine.copy(trays = newTrays)
        }
    }

    /**
     * Products needed across ALL machines (independent of `isPacked`),
     * grouped by product into one [CombinedPackingItem] per product with a
     * [MachineNeed] per machine that needs it.
     *
     * Sorting is a total order so re-renders never swap row positions
     * (`Map`/`Set` iteration order isn't guaranteed):
     *  - No [pickOrder]: `totalQuantity` descending, then product name
     *    case-insensitively, then `productId` as the final tiebreaker.
     *  - With [pickOrder]: positioned products first in position order,
     *    unpositioned products after, then the same name/id tiebreakers.
     */
    fun buildCombinedPackingList(
        machines: List<RefillMachine>,
        pickOrder: Map<String, Int>
    ): List<CombinedPackingItem> {
        data class Accumulator(
            var productName: String,
            var imagePath: String?,
            var sellprice: Double?,
            var totalQuantity: Int,
            val needs: LinkedHashMap<String, MachineNeed>
        )

        val grouped = LinkedHashMap<String, Accumulator>()
        for (machine in machines) {
            for (rt in machine.trays) {
                if (rt.deficit <= 0) continue
                val productId = rt.tray.productId ?: continue
                val acc = grouped.getOrPut(productId) {
                    Accumulator(
                        productName = rt.tray.products?.name ?: "Slot ${rt.tray.itemNumber}",
                        imagePath = rt.tray.products?.imagePath,
                        sellprice = rt.tray.products?.sellprice,
                        totalQuantity = 0,
                        needs = LinkedHashMap()
                    )
                }
                acc.totalQuantity += rt.deficit

                val existing = acc.needs[machine.machine.id]
                acc.needs[machine.machine.id] = if (existing != null) {
                    existing.copy(
                        quantity = existing.quantity + rt.deficit,
                        capacity = existing.capacity + rt.tray.capacity
                    )
                } else {
                    MachineNeed(
                        machineId = machine.machine.id,
                        machineName = machine.machine.displayName,
                        quantity = rt.deficit,
                        capacity = rt.tray.capacity
                    )
                }
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
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.productName }
                    .thenBy { it.productId }
            )
        } else {
            items.sortedWith(
                compareBy<CombinedPackingItem> { pickOrder[it.productId] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.productName }
                    .thenBy { it.productId }
            )
        }
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
