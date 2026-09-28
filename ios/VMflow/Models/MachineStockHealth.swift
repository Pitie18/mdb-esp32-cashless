import Foundation

/// Warehouse-aware stock classification for a fleet of machines.
///
/// Ported 1:1 from the PWA's `management-frontend/app/lib/stock-health.ts`
/// (`classifyTrayStock`, `groupTraysByProduct`, `groupNeedsRefill`,
/// `distributeAcrossSlots`, `computeStockHealthPerMachine`,
/// `countMachineStockBuckets`) and mirrored by Android's `StockHealth.kt`, so
/// the stock numbers agree across all three clients — change all three
/// together.
///
/// Stock is judged per **product within a machine**, not per slot: a product
/// that sits in several spirals is one ``ProductStockGroup`` whose stock,
/// capacity and thresholds are the sums over its slots. Cola in slots
/// 12/13/14 at 0/2/9 is 11 Cola (`fill`), not one sold-out slot. A slot that
/// is empty while its product is still in another slot is only a hint
/// (``MachineStockSummary/emptySlotsWithStock``), never a critical machine. Before this existed the native dashboards counted any
/// empty tray as "critical" — including unassigned slots and products the
/// warehouse cannot refill — and reported more red machines than the PWA.
///
/// Like the machine list's deficit check (`MachineDeficits` on Android,
/// `MachineListViewModel` here), warehouse availability is a presence check
/// (does any positive-quantity batch of that product exist), not a coverage
/// calculation.
enum TrayStockState {
    case critical
    case low
    case fill
    case ok
}

/// Machine-level stock tier. Deliberately separate from ``StockHealth`` —
/// that enum drives machine-card colouring and has no `fill` case; widening
/// it is a UI change, not a counting one.
enum MachineStockTier {
    case critical
    case low
    case fill
    case ok
}

/// Per-machine roll-up of its trays. Every count is a **product** (a
/// ``ProductStockGroup``), not a slot.
struct MachineStockSummary {
    /// Products sold out in every slot whose product the warehouse can refill.
    var refillableEmpty = 0
    /// Products at or below their summed `min_stock` the warehouse can refill.
    var refillableLow = 0
    /// Products at or below their summed `fill_when_below` the warehouse can refill.
    var refillableFill = 0
    /// Products needing attention whose product the warehouse has no stock of.
    var noStockCount = 0
    /// Subset of `noStockCount` that is sold out everywhere — the swap candidates.
    var noStockEmptyCount = 0
    /// Empty slots whose product is otherwise fine in this machine — a hint,
    /// not a health input.
    var emptySlotsWithStock = 0
    var totalStock = 0
    var totalCapacity = 0
    /// Driven only by refillable trays: critical > low > fill > ok.
    var tier: MachineStockTier = .ok
    /// Stock as a percentage of capacity; 100 for a machine without capacity.
    var percent = 100
}

/// Disjoint fleet-wide counts — every machine lands in at most one bucket.
struct MachineStockBuckets: Equatable {
    var critical = 0
    var low = 0
    var fill = 0
    /// Machines that are otherwise fine but hold a product that is sold out in every slot and the warehouse can't refill.
    var swap = 0
    /// Machines in exactly one of the buckets above.
    var needingAttention = 0
}

/// The tray fields the classification needs, so the pure logic doesn't depend
/// on the full ``Tray`` model (and stays compilable on its own for tests).
protocol StockCountableTray {
    var machineId: UUID { get }
    var productId: UUID? { get }
    var capacity: Int { get }
    var currentStock: Int { get }
    var minStock: Int { get }
    var fillWhenBelow: Int { get }
}

/// All slots of one product in one machine, classified on the summed values.
///
/// Thresholds add up, so a product in a single slot classifies exactly like
/// that slot and no per-product configuration is needed. A disabled (0)
/// threshold on one slot simply contributes nothing to the sum.
struct ProductStockGroup<T: StockCountableTray> {
    let machineId: UUID
    let productId: UUID
    /// The slots holding this product, in input order.
    var trays: [T] = []
    /// Σ over the slots.
    var currentStock = 0
    var capacity = 0
    var minStock = 0
    var fillWhenBelow = 0
    var state: TrayStockState = .ok
    /// Units needed to fill every slot of the product.
    var deficit = 0
    /// Slots at 0 while the product still has stock in another slot.
    var emptySlots = 0

    /// See ``MachineStockHealth/groupNeedsRefill(state:deficit:)``.
    var needsRefill: Bool {
        MachineStockHealth.groupNeedsRefill(state: state, deficit: deficit)
    }
}

/// Identity of a ``ProductStockGroup``: one product in one machine.
struct ProductGroupKey: Hashable {
    let machineId: UUID
    let productId: UUID
}

enum MachineStockHealth {

    /// Classify one tray against its two independent thresholds.
    /// A threshold of 0 means "disabled" and is skipped.
    static func classifyTray(currentStock: Int, minStock: Int, fillWhenBelow: Int) -> TrayStockState {
        if currentStock == 0 { return .critical }
        if minStock > 0 && currentStock <= minStock { return .low }
        if fillWhenBelow > 0 && currentStock <= fillWhenBelow { return .fill }
        return .ok
    }

    /// Whether a product can be refilled from the warehouse.
    ///
    /// With no warehouse data at all every product counts as refillable, so
    /// operators who don't use the warehouse feature keep the old behaviour.
    static func isProductRefillable(
        productId: UUID?,
        warehouseProductIds: Set<UUID>,
        hasWarehouses: Bool
    ) -> Bool {
        guard let productId else { return false }
        return !hasWarehouses || warehouseProductIds.contains(productId)
    }

    // MARK: - Product groups

    /// Group assigned trays by (machine, product) and classify each group on
    /// the summed values. Unassigned trays are skipped. Groups keep
    /// first-appearance order.
    static func groupTraysByProduct<T: StockCountableTray>(_ trays: [T]) -> [ProductStockGroup<T>] {
        var order: [ProductGroupKey] = []
        var groups: [ProductGroupKey: ProductStockGroup<T>] = [:]

        for tray in trays {
            guard let productId = tray.productId else { continue }
            let key = ProductGroupKey(machineId: tray.machineId, productId: productId)
            var group = groups[key] ?? {
                order.append(key)
                return ProductStockGroup<T>(machineId: tray.machineId, productId: productId)
            }()
            group.trays.append(tray)
            group.currentStock += tray.currentStock
            group.capacity += tray.capacity
            group.minStock += tray.minStock
            group.fillWhenBelow += tray.fillWhenBelow
            groups[key] = group
        }

        return order.compactMap { key in
            guard var group = groups[key] else { return nil }
            group.state = classifyTray(
                currentStock: group.currentStock,
                minStock: group.minStock,
                fillWhenBelow: group.fillWhenBelow
            )
            group.deficit = max(0, group.capacity - group.currentStock)
            group.emptySlots = group.currentStock > 0
                ? group.trays.filter { $0.currentStock == 0 }.count
                : 0
            return group
        }
    }

    /// Whether a product group should be refilled. A `fill`-tier group that is
    /// already full (misconfigured `fill_when_below >= capacity`) moves nothing.
    static func groupNeedsRefill(state: TrayStockState, deficit: Int) -> Bool {
        switch state {
        case .ok: return false
        case .fill: return deficit > 0
        case .critical, .low: return true
        }
    }

    /// Split `amount` units of one product across its slots, emptiest slot
    /// first (ties by slot number), so no selection stays sold out while
    /// another slot of the same product gets topped up. Each slot takes at
    /// most its headroom. Returns fill amounts aligned with `slots`.
    static func distributeAcrossSlots(
        _ slots: [(itemNumber: Int, capacity: Int, currentStock: Int)],
        amount: Int
    ) -> [Int] {
        var result = Array(repeating: 0, count: slots.count)
        let order = slots.indices.sorted { a, b in
            slots[a].currentStock != slots[b].currentStock
                ? slots[a].currentStock < slots[b].currentStock
                : slots[a].itemNumber < slots[b].itemNumber
        }
        var remaining = max(0, amount)
        for i in order {
            guard remaining > 0 else { break }
            let take = min(max(0, slots[i].capacity - slots[i].currentStock), remaining)
            result[i] = take
            remaining -= take
        }
        return result
    }

    // MARK: - Machine roll-up

    /// Roll trays up per machine.
    ///
    /// - Trays are grouped per product (``groupTraysByProduct(_:)``); every
    ///   count is a product.
    /// - Trays without a product only count towards the fill percentage.
    /// - Groups that don't need a refill only contribute their empty slots to
    ///   ``MachineStockSummary/emptySlotsWithStock``.
    /// - Groups needing refill are split into refillable (product in the
    ///   warehouse) and no-stock; `tier` is driven by refillable groups only.
    static func summaries<T: StockCountableTray>(
        trays: [T],
        warehouseProductIds: Set<UUID>,
        hasWarehouses: Bool
    ) -> [UUID: MachineStockSummary] {
        var map: [UUID: MachineStockSummary] = [:]

        for tray in trays {
            map[tray.machineId, default: MachineStockSummary()].totalStock += tray.currentStock
            map[tray.machineId, default: MachineStockSummary()].totalCapacity += tray.capacity
        }

        for group in groupTraysByProduct(trays) {
            var entry = map[group.machineId] ?? MachineStockSummary()
            defer { map[group.machineId] = entry }

            guard group.needsRefill else {
                entry.emptySlotsWithStock += group.emptySlots
                continue
            }

            let refillable = isProductRefillable(
                productId: group.productId,
                warehouseProductIds: warehouseProductIds,
                hasWarehouses: hasWarehouses
            )
            if refillable {
                switch group.state {
                case .critical: entry.refillableEmpty += 1
                case .low: entry.refillableLow += 1
                default: entry.refillableFill += 1
                }
            } else {
                entry.noStockCount += 1
                if group.state == .critical { entry.noStockEmptyCount += 1 }
            }
        }

        for (machineId, var entry) in map {
            if entry.refillableEmpty > 0 { entry.tier = .critical }
            else if entry.refillableLow > 0 { entry.tier = .low }
            else if entry.refillableFill > 0 { entry.tier = .fill }
            else { entry.tier = .ok }

            entry.percent = entry.totalCapacity > 0
                ? Int((Double(entry.totalStock) / Double(entry.totalCapacity) * 100).rounded())
                : 100

            map[machineId] = entry
        }

        return map
    }

    /// Fold per-machine summaries into disjoint fleet-wide buckets, so the
    /// counts sum to the number of machines needing attention and can never
    /// exceed the fleet size.
    static func buckets(_ summaries: some Collection<MachineStockSummary>) -> MachineStockBuckets {
        var buckets = MachineStockBuckets()

        for summary in summaries {
            switch summary.tier {
            case .critical: buckets.critical += 1
            case .low: buckets.low += 1
            case .fill: buckets.fill += 1
            case .ok:
                guard summary.noStockEmptyCount > 0 else { continue }
                buckets.swap += 1
            }
            buckets.needingAttention += 1
        }

        return buckets
    }
}

// MARK: - Machine detail view helpers

/// A tray that also knows its identity and slot number — what the machine
/// detail list and stock grid need on top of ``StockCountableTray``.
protocol SlottedStockTray: StockCountableTray {
    var id: UUID { get }
    var itemNumber: Int { get }
}

/// How one slot is highlighted in the machine detail tray list and stock
/// grid. Judged by the slot's **product group**, not by the slot alone.
/// Ported from the PWA's `trayGroups.ts` (`TrayStockFlag`).
enum TrayStockFlag: Equatable {
    /// The product is sold out or at/below its summed `min_stock`, and this slot has room.
    case low
    /// The product is below its summed `fill_when_below`, and this slot has room.
    case fill
    /// This slot is empty, but the product is fine thanks to other slots.
    case slotEmpty
    /// Nothing to do.
    case ok
}

/// A slot's product group plus the slot's own highlight.
struct TrayGroupInfo<T: SlottedStockTray> {
    let group: ProductStockGroup<T>
    let flag: TrayStockFlag

    var needsRefill: Bool { group.needsRefill }
}

/// One row of the "by product" tray list.
struct ProductListRow<T: SlottedStockTray>: Identifiable {
    let tray: T
    /// Set on the first slot of a product that sits in two or more slots.
    let header: ProductStockGroup<T>?
    /// True for every slot of such a multi-slot product (for indentation).
    let inGroup: Bool

    var id: UUID { tray.id }
}

extension MachineStockHealth {

    /// Index every assigned tray by id to its product group and highlight
    /// flag. Unassigned trays have no entry. Mirrors `buildTrayGroupIndex`.
    static func trayGroupIndex<T: SlottedStockTray>(_ trays: [T]) -> [UUID: TrayGroupInfo<T>] {
        var index: [UUID: TrayGroupInfo<T>] = [:]
        for group in groupTraysByProduct(trays) {
            for tray in group.trays {
                index[tray.id] = TrayGroupInfo(group: group, flag: trayStockFlag(tray, in: group))
            }
        }
        return index
    }

    /// See ``TrayStockFlag``. A slot that is already full is never flagged
    /// for refill even when its product is — there is nothing to put in it.
    static func trayStockFlag<T: SlottedStockTray>(_ tray: T, in group: ProductStockGroup<T>) -> TrayStockFlag {
        let needsRefill = group.needsRefill
        let hasRoom = tray.capacity - tray.currentStock > 0
        if needsRefill && hasRoom { return group.state == .fill ? .fill : .low }
        if !needsRefill && tray.currentStock == 0 && group.currentStock > 0 { return .slotEmpty }
        return .ok
    }

    /// List order for the "by product" view: products ordered by their lowest
    /// slot number, a product's slots right after each other, unassigned
    /// slots in their own place. `visible` (e.g. a search) filters rows, but
    /// headers keep the totals of the whole product. Mirrors `productListRows`.
    static func productListRows<T: SlottedStockTray>(
        _ trays: [T],
        index: [UUID: TrayGroupInfo<T>],
        visible: (T) -> Bool = { _ in true }
    ) -> [ProductListRow<T>] {
        let bySlot = trays.sorted { $0.itemNumber < $1.itemNumber }
        var rows: [ProductListRow<T>] = []
        var done = Set<UUID>()
        for tray in bySlot where !done.contains(tray.id) {
            let group = index[tray.id]?.group
            let members = group.map { $0.trays.sorted { $0.itemNumber < $1.itemNumber } } ?? [tray]
            members.forEach { done.insert($0.id) }
            let multi = members.count > 1
            for (i, member) in members.filter(visible).enumerated() {
                rows.append(ProductListRow(tray: member, header: multi && i == 0 ? group : nil, inGroup: multi))
            }
        }
        return rows
    }
}
