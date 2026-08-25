import Foundation

/// Warehouse-aware stock classification for a fleet of machines.
///
/// Ported 1:1 from the PWA's `management-frontend/app/lib/stock-health.ts`
/// (`classifyTrayStock`, `computeStockHealthPerMachine`,
/// `countMachineStockBuckets`) so the dashboard's stock numbers agree across
/// all three clients. Before this existed the native dashboards counted any
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

/// Per-machine roll-up of its trays.
struct MachineStockSummary {
    /// Trays that are empty and whose product the warehouse can refill.
    var refillableEmpty = 0
    /// Trays at or below `min_stock` whose product the warehouse can refill.
    var refillableLow = 0
    /// Trays at or below `fill_when_below` whose product the warehouse can refill.
    var refillableFill = 0
    /// Trays needing attention whose product the warehouse has no stock of.
    var noStockCount = 0
    /// Subset of `noStockCount` that is empty — the swap candidates.
    var noStockEmptyCount = 0
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
    /// Machines that are otherwise fine but hold an empty tray the warehouse can't refill.
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

    /// Roll trays up per machine.
    ///
    /// - Trays without a product are ignored — there is nothing to refill.
    /// - A `fill`-tier tray that is already at capacity (misconfigured
    ///   `fill_when_below >= capacity`) is ignored: refilling it moves nothing.
    static func summaries<T: StockCountableTray>(
        trays: [T],
        warehouseProductIds: Set<UUID>,
        hasWarehouses: Bool
    ) -> [UUID: MachineStockSummary] {
        var map: [UUID: MachineStockSummary] = [:]

        for tray in trays {
            var entry = map[tray.machineId] ?? MachineStockSummary()

            entry.totalStock += tray.currentStock
            entry.totalCapacity += tray.capacity

            defer { map[tray.machineId] = entry }

            guard tray.productId != nil else { continue }

            let state = classifyTray(
                currentStock: tray.currentStock,
                minStock: tray.minStock,
                fillWhenBelow: tray.fillWhenBelow
            )
            if state == .ok { continue }
            if state == .fill && tray.capacity - tray.currentStock <= 0 { continue }

            let refillable = isProductRefillable(
                productId: tray.productId,
                warehouseProductIds: warehouseProductIds,
                hasWarehouses: hasWarehouses
            )
            if refillable {
                switch state {
                case .critical: entry.refillableEmpty += 1
                case .low: entry.refillableLow += 1
                default: entry.refillableFill += 1
                }
            } else {
                entry.noStockCount += 1
                if state == .critical { entry.noStockEmptyCount += 1 }
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
