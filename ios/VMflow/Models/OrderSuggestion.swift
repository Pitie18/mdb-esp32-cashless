import Foundation

/// Order suggestion for the warehouse "Order" segment: how much of each
/// product to order so that it lasts until the delivery after next.
///
///     target days = order interval + lead time + buffer
///     need        = avg daily sales × target days − (warehouse + machines + on order)
///     cartons     = ceil(need / units per carton)
///
/// Machines count only when the company setting says so. A product sits on
/// the list automatically while it needs cartons; a manual entry keeps it
/// there (with at least one carton) until it is ordered or removed.
/// 1:1 port of the web's `lib/orderSuggestion.ts` (and Android's
/// `OrderSuggestion.kt`). Pure Foundation, host-tested in
/// `HostTests/OrderSuggestion`.

/// Company-wide calculation settings (`companies.order_*`).
struct OrderSettings: Equatable {
    var bufferDays: Int = 3
    var countMachineStock: Bool = true
    var defaultLeadDays: Int = 3
    var defaultIntervalDays: Int = 14

    static let `default` = OrderSettings()
}

/// A supplier with its ordering rhythm; nil values fall back to the company defaults.
struct OrderSupplier: Identifiable, Equatable, Hashable {
    let id: UUID
    var name: String
    var customerNumber: String?
    var email: String?
    var leadTimeDays: Int?
    var orderIntervalDays: Int?
    var minOrderValue: Double?
}

/// Everything the suggestion needs to know about one product in one warehouse.
struct OrderProductInput: Identifiable, Equatable {
    let productId: UUID
    var productName: String
    var imagePath: String?
    var discontinued: Bool
    var warehouseQty: Int
    var machineQty: Int
    var avgDailySales: Double
    var onOrder: Int
    var unitSize: Int
    /// Preferred supplier, else the supplier of the latest purchase price.
    var supplierId: UUID?
    var unitPriceNet: Double?

    var id: UUID { productId }
}

/// A product someone put on the order list by hand (`order_list_items`).
struct ManualOrderEntry: Equatable, Decodable {
    let productId: UUID
    let addedByName: String?
    /// "web" | "ios" | "android"
    let addedVia: String
    let createdAt: Date?

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case addedByName = "added_by_name"
        case addedVia = "added_via"
        case createdAt = "created_at"
    }
}

struct OrderLine: Identifiable, Equatable {
    let product: OrderProductInput
    let available: Int
    let reachDays: Int?
    let targetDays: Int
    let suggestedCartons: Int
    let cartons: Int
    let units: Int
    let value: Double?
    /// On the list because of stock and reach.
    let auto: Bool
    let manual: ManualOrderEntry?
    /// The quantity was changed by hand.
    let overridden: Bool

    var id: UUID { product.productId }
}

struct OrderGroup: Identifiable, Equatable {
    /// nil = "no supplier".
    let supplier: OrderSupplier?
    var lines: [OrderLine]
    /// Products of this supplier that are not on the list (to add by hand).
    var others: [OrderProductInput]
    var value: Double
    var belowMinOrder: Bool
    var hasEmpty: Bool

    var id: String { supplier?.id.uuidString ?? "none" }
}

enum OrderSuggestion {
    static func leadDays(_ s: OrderSupplier?, _ settings: OrderSettings) -> Int {
        s?.leadTimeDays ?? settings.defaultLeadDays
    }

    static func intervalDays(_ s: OrderSupplier?, _ settings: OrderSettings) -> Int {
        s?.orderIntervalDays ?? settings.defaultIntervalDays
    }

    static func targetDays(_ s: OrderSupplier?, _ settings: OrderSettings) -> Int {
        intervalDays(s, settings) + leadDays(s, settings) + settings.bufferDays
    }

    static func availableUnits(_ p: OrderProductInput, _ settings: OrderSettings) -> Int {
        p.warehouseQty + (settings.countMachineStock ? p.machineQty : 0)
    }

    static func reachDays(_ p: OrderProductInput, _ settings: OrderSettings) -> Int? {
        guard p.avgDailySales > 0 else { return nil }
        return Int((Double(availableUnits(p, settings)) / p.avgDailySales).rounded())
    }

    static func suggestedCartons(_ p: OrderProductInput, days: Int, _ settings: OrderSettings) -> Int {
        if p.discontinued || p.avgDailySales <= 0 { return 0 }
        let need = p.avgDailySales * Double(days) - Double(availableUnits(p, settings) + p.onOrder)
        // Tolerate float noise (3.1 × 17 is 52.699999…).
        if need <= 1e-9 { return 0 }
        return Int((need / Double(max(1, p.unitSize)) - 1e-9).rounded(.up))
    }

    static func buildGroups(
        products: [OrderProductInput],
        suppliers: [OrderSupplier],
        manual: [ManualOrderEntry],
        overrides: [UUID: Int] = [:],
        settings: OrderSettings
    ) -> [OrderGroup] {
        let manualById = Dictionary(manual.map { ($0.productId, $0) }, uniquingKeysWith: { first, _ in first })
        let supplierById = Dictionary(suppliers.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        // Keyed by supplier id; nil = no supplier. `order` keeps insertion order.
        var buckets: [UUID?: OrderGroup] = [:]
        func ensure(_ s: OrderSupplier?) {
            if buckets[s?.id] == nil {
                buckets[s?.id] = OrderGroup(supplier: s, lines: [], others: [], value: 0, belowMinOrder: false, hasEmpty: false)
            }
        }
        for s in suppliers { ensure(s) }

        for p in products {
            let supplier = p.supplierId.flatMap { supplierById[$0] }
            let days = targetDays(supplier, settings)
            let suggested = suggestedCartons(p, days: days, settings)
            let manualEntry = manualById[p.productId]
            ensure(supplier)

            if suggested == 0 && manualEntry == nil {
                if !p.discontinued { buckets[supplier?.id]?.others.append(p) }
                continue
            }

            let base = manualEntry != nil ? max(1, suggested) : suggested
            let override = overrides[p.productId]
            let cartons = override.map { max(0, $0) } ?? base
            let units = cartons * max(1, p.unitSize)
            buckets[supplier?.id]?.lines.append(OrderLine(
                product: p,
                available: availableUnits(p, settings),
                reachDays: reachDays(p, settings),
                targetDays: days,
                suggestedCartons: suggested,
                cartons: cartons,
                units: units,
                value: p.unitPriceNet.map { Double(units) * $0 },
                auto: suggested > 0,
                manual: manualEntry,
                overridden: override != nil && override != base
            ))
        }

        var groups: [OrderGroup] = buckets.values.map { group in
            var g = group
            // Emptiest first: shortest reach, unknown reach (no sales) last.
            g.lines.sort { a, b in
                let ra = a.reachDays ?? Int.max
                let rb = b.reachDays ?? Int.max
                if ra != rb { return ra < rb }
                return a.product.productName.localizedCaseInsensitiveCompare(b.product.productName) == .orderedAscending
            }
            g.others.sort { $0.productName.localizedCaseInsensitiveCompare($1.productName) == .orderedAscending }
            g.value = g.lines.reduce(0) { $0 + ($1.value ?? 0) }
            let min = g.supplier?.minOrderValue ?? 0
            g.belowMinOrder = min > 0 && g.lines.contains { $0.cartons > 0 } && g.value < min
            g.hasEmpty = g.lines.contains { $0.product.warehouseQty == 0 }
            return g
        }

        // Suppliers with something to order first (alphabetical), then the
        // rest; "no supplier" last and only when it has lines.
        groups = groups.filter { g in
            g.supplier != nil ? (!g.lines.isEmpty || !g.others.isEmpty) : !g.lines.isEmpty
        }
        groups.sort { a, b in
            guard let sa = a.supplier else { return false }
            guard let sb = b.supplier else { return true }
            if a.lines.isEmpty != b.lines.isEmpty { return !a.lines.isEmpty }
            return sa.name.localizedCaseInsensitiveCompare(sb.name) == .orderedAscending
        }
        return groups
    }

    static func lineCount(_ groups: [OrderGroup]) -> Int {
        groups.reduce(0) { $0 + $1.lines.count }
    }

    /// Plain-text order for e-mail or a supplier portal.
    static func orderText(
        _ group: OrderGroup,
        customerNumberLabel: String,
        carton: (_ count: Int, _ size: Int) -> String
    ) -> String {
        let rows = group.lines.filter { $0.cartons > 0 }.map { l -> String in
            let size = max(1, l.product.unitSize)
            return size > 1
                ? "\(l.units) × \(l.product.productName) (\(carton(l.cartons, size)))"
                : "\(l.units) × \(l.product.productName)"
        }
        var head: [String] = []
        if let s = group.supplier {
            head.append(s.name)
            if let nr = s.customerNumber, !nr.isEmpty { head.append("\(customerNumberLabel): \(nr)") }
        }
        return (head + (head.isEmpty ? [] : [""]) + rows).joined(separator: "\n")
    }
}
