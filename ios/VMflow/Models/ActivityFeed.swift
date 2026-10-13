import Foundation

// MARK: - Raw rows (PostgREST decoding)

/// Row from `activity_log` for the dashboard feed.
/// Only the columns/metadata keys the feed renders; metadata is decoded
/// tolerantly because old rows may carry fewer fields.
struct ActivityLogRow: Decodable, Equatable {
    let id: UUID
    let createdAt: Date
    /// Author; only `slot_change_applied` needs it (the RPC that writes that
    /// row stores no `_user_display`).
    var userId: UUID? = nil
    let action: String
    let metadata: ActivityLogMetadata?

    enum CodingKeys: String, CodingKey {
        case id, action, metadata
        case createdAt = "created_at"
        case userId = "user_id"
    }
}

struct ActivityLogMetadata: Decodable, Equatable {
    let tourId: String?
    let machineName: String?
    let traysRefilled: Int?
    let totalAdded: Int?
    let machineCount: Int?
    let machineNames: [String]?
    let warehouseName: String?
    let userDisplay: String?
    let products: [ActivityProductLine]?
    // Cash-book (Barkasse) entry metadata — only present on cash_book_entry_created rows.
    let cashType: String?
    let amount: Double?
    let category: String?
    let note: String?
    // Slot change (Umbau) metadata — only present on slot_change_applied rows,
    // written by the apply_slot_change RPC.
    var done: [SlotChangeDoneLine]? = nil
    var skipped: [SlotChangeSkippedLine]? = nil

    enum CodingKeys: String, CodingKey {
        case products
        case done, skipped
        case amount
        case category
        case tourId = "tour_id"
        case machineName = "machine_name"
        case traysRefilled = "trays_refilled"
        case totalAdded = "total_added"
        case machineCount = "machine_count"
        case machineNames = "machine_names"
        case warehouseName = "warehouse_name"
        case userDisplay = "_user_display"
        case cashType = "type"
        case note = "description"
    }
}

struct ActivityProductLine: Decodable, Equatable {
    let productName: String?
    let quantity: Int?

    enum CodingKeys: String, CodingKey {
        case quantity
        case productName = "product_name"
    }
}

/// One rebuilt slot of a `slot_change_applied` row. Every field optional:
/// a decode failure here would take the whole feed down.
struct SlotChangeDoneLine: Decodable, Equatable {
    let itemNumber: Int?
    let toProductId: String?
    let filled: Int?

    enum CodingKeys: String, CodingKey {
        case filled
        case itemNumber = "item_number"
        case toProductId = "to_product_id"
    }
}

struct SlotChangeSkippedLine: Decodable, Equatable {
    let itemNumber: Int?

    enum CodingKeys: String, CodingKey {
        case itemNumber = "item_number"
    }
}

/// Row from `warehouse_transactions` (transaction_type 'incoming'/'intake') with
/// FK-joined product and warehouse names.
struct IntakeTransactionRow: Decodable, Equatable {
    let id: UUID
    let createdAt: Date
    let warehouseId: UUID?
    let userId: UUID?
    let quantityChange: Int
    let products: NameOnly?
    let warehouses: NameOnly?

    struct NameOnly: Decodable, Equatable { let name: String? }

    enum CodingKeys: String, CodingKey {
        case id, products, warehouses
        case createdAt = "created_at"
        case warehouseId = "warehouse_id"
        case userId = "user_id"
        case quantityChange = "quantity_change"
    }
}

// MARK: - Feed items

struct RefillActivity: Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let machineName: String
    let traysRefilled: Int
    let totalAdded: Int
    let userDisplay: String?
    let tourId: String?
    let products: [(name: String, quantity: Int)]

    static func == (lhs: RefillActivity, rhs: RefillActivity) -> Bool {
        lhs.id == rhs.id && lhs.createdAt == rhs.createdAt
    }
}

/// Slots rebuilt at a machine on a refill tour — a `slot_change_applied` row
/// (same entry the web history shows as "Slots rebuilt").
struct SlotChangeActivity: Identifiable, Equatable {
    struct Slot: Equatable {
        let itemNumber: Int
        let productName: String?
        let filled: Int
    }

    let id: UUID
    let createdAt: Date
    let machineName: String
    let userDisplay: String?
    /// Rebuilt slots, by slot number.
    let slots: [Slot]
    /// Slots of the change note left as they were this tour.
    let skippedCount: Int
}

struct TourActivity: Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let userDisplay: String?
    let machineCount: Int
    let machineNames: [String]
    let warehouseName: String?
    let tourId: String?
}

/// One cash-book (Barkasse) money movement — a `cash_book_entry_created` row.
/// Deposits/withdrawals/expenses etc.; the `type` drives icon/tint/label.
struct CashBookActivity: Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let type: CashBookEntryType
    let amount: Double
    let category: String?
    let note: String?
    let userDisplay: String?
}

/// One intake "session": consecutive incoming warehouse transactions by the
/// same user into the same warehouse with ≤15 min between bookings.
struct IntakeGroup: Identifiable, Equatable {
    /// Deterministic: id of the OLDEST transaction in the group — stable
    /// across reloads so row expansion state survives (spec §1).
    let id: UUID
    /// Newest transaction time in the group (used for sorting/day grouping).
    let date: Date
    let userId: UUID?
    var userDisplay: String?
    let warehouseName: String?
    let totalUnits: Int
    let products: [(name: String, quantity: Int)]

    var productCount: Int { products.count }

    static func == (lhs: IntakeGroup, rhs: IntakeGroup) -> Bool {
        lhs.id == rhs.id && lhs.date == rhs.date && lhs.userDisplay == rhs.userDisplay
    }
}

/// One entry in the dashboard "Recent Activity" timeline.
enum ActivityFeedItem: Identifiable, Equatable {
    case sale(SaleWithMachine)
    case machineRefilled(RefillActivity)
    case tourStarted(TourActivity)
    case stockIntake(IntakeGroup)
    case cashBookEntry(CashBookActivity)
    case slotsRebuilt(SlotChangeActivity)

    var id: String {
        switch self {
        case .sale(let s): return "sale-\(s.id.uuidString)"
        case .machineRefilled(let r): return "refill-\(r.id.uuidString)"
        case .tourStarted(let t): return "tour-\(t.id.uuidString)"
        case .stockIntake(let g): return "intake-\(g.id.uuidString)"
        case .cashBookEntry(let c): return "cashentry-\(c.id.uuidString)"
        case .slotsRebuilt(let r): return "slotchange-\(r.id.uuidString)"
        }
    }

    var date: Date {
        switch self {
        case .sale(let s): return s.sale.createdAt
        case .machineRefilled(let r): return r.createdAt
        case .tourStarted(let t): return t.createdAt
        case .stockIntake(let g): return g.date
        case .cashBookEntry(let c): return c.createdAt
        case .slotsRebuilt(let r): return r.createdAt
        }
    }
}

// MARK: - Pure builders (no I/O — kept testable)

enum ActivityFeedBuilder {

    /// Max gap between two bookings that still counts as one intake session.
    static let intakeSessionGap: TimeInterval = 15 * 60

    /// Group incoming warehouse transactions into intake sessions.
    /// `rows` may arrive in any order; they are sorted ascending internally.
    /// A new group starts when the user or warehouse changes, or the gap to
    /// the previous transaction exceeds `intakeSessionGap`.
    static func groupIntakes(_ rows: [IntakeTransactionRow]) -> [IntakeGroup] {
        let sorted = rows.sorted { $0.createdAt < $1.createdAt }
        var groups: [IntakeGroup] = []
        var current: [IntakeTransactionRow] = []

        func flush() {
            guard let first = current.first, let last = current.last else { return }
            // Aggregate quantities per product name, preserving first-seen order.
            var order: [String] = []
            var qty: [String: Int] = [:]
            for row in current {
                let name = row.products?.name ?? "—"
                if qty[name] == nil { order.append(name) }
                qty[name, default: 0] += row.quantityChange
            }
            groups.append(IntakeGroup(
                id: first.id,
                date: last.createdAt,
                userId: first.userId,
                userDisplay: nil,
                warehouseName: first.warehouses?.name,
                totalUnits: current.reduce(0) { $0 + $1.quantityChange },
                products: order.map { (name: $0, quantity: qty[$0] ?? 0) }
            ))
            current = []
        }

        for row in sorted {
            if let prev = current.last {
                let sameSession = prev.userId == row.userId
                    && prev.warehouseId == row.warehouseId
                    && row.createdAt.timeIntervalSince(prev.createdAt) <= intakeSessionGap
                if !sameSession { flush() }
            }
            current.append(row)
        }
        flush()
        return groups
    }

    /// Authors and target products of the `slot_change_applied` rows, which
    /// carry ids only — resolved to names before `makeActivityItems`.
    static func slotChangeLookupIds(_ rows: [ActivityLogRow]) -> (users: [UUID], products: [String]) {
        let changes = rows.filter { $0.action == "slot_change_applied" }
        let users = Set(changes.compactMap(\.userId))
        let products = Set(changes.flatMap { $0.metadata?.done ?? [] }.compactMap { $0.toProductId?.lowercased() })
        return (Array(users), Array(products))
    }

    /// Map activity_log rows to feed items. Unknown actions are skipped.
    /// `userNames` / `productNames` (product id lowercased) name the authors
    /// and products of `slot_change_applied` rows.
    static func makeActivityItems(
        _ rows: [ActivityLogRow],
        userNames: [UUID: String] = [:],
        productNames: [String: String] = [:]
    ) -> [ActivityFeedItem] {
        rows.compactMap { row in
            switch row.action {
            case "slot_change_applied":
                let slots = (row.metadata?.done ?? []).compactMap { line -> SlotChangeActivity.Slot? in
                    guard let n = line.itemNumber else { return nil }
                    return SlotChangeActivity.Slot(
                        itemNumber: n,
                        productName: line.toProductId.flatMap { productNames[$0.lowercased()] },
                        filled: line.filled ?? 0
                    )
                }.sorted { $0.itemNumber < $1.itemNumber }
                return .slotsRebuilt(SlotChangeActivity(
                    id: row.id,
                    createdAt: row.createdAt,
                    machineName: row.metadata?.machineName ?? "—",
                    userDisplay: row.userId.flatMap { userNames[$0] } ?? row.metadata?.userDisplay,
                    slots: slots,
                    skippedCount: row.metadata?.skipped?.count ?? 0
                ))
            case "stock_refill_tour":
                return .machineRefilled(RefillActivity(
                    id: row.id,
                    createdAt: row.createdAt,
                    machineName: row.metadata?.machineName ?? "—",
                    traysRefilled: row.metadata?.traysRefilled ?? 0,
                    totalAdded: row.metadata?.totalAdded ?? 0,
                    userDisplay: row.metadata?.userDisplay,
                    tourId: row.metadata?.tourId,
                    products: (row.metadata?.products ?? []).compactMap { line in
                        guard let name = line.productName else { return nil }
                        return (name: name, quantity: line.quantity ?? 0)
                    }
                ))
            case "tour_started":
                let names = row.metadata?.machineNames ?? []
                return .tourStarted(TourActivity(
                    id: row.id,
                    createdAt: row.createdAt,
                    userDisplay: row.metadata?.userDisplay,
                    machineCount: row.metadata?.machineCount ?? names.count,
                    machineNames: names,
                    warehouseName: row.metadata?.warehouseName,
                    tourId: row.metadata?.tourId
                ))
            case "cash_book_entry_created":
                return .cashBookEntry(CashBookActivity(
                    id: row.id,
                    createdAt: row.createdAt,
                    type: CashBookEntryType(rawValue: row.metadata?.cashType ?? "") ?? .unknown,
                    amount: row.metadata?.amount ?? 0,
                    category: row.metadata?.category,
                    note: row.metadata?.note,
                    userDisplay: row.metadata?.userDisplay
                ))
            default:
                return nil
            }
        }
    }

    /// Merge all sources into one timeline, newest first.
    static func mergeFeed(
        sales: [SaleWithMachine],
        activityRows: [ActivityLogRow],
        intakeGroups: [IntakeGroup],
        userNames: [UUID: String] = [:],
        productNames: [String: String] = [:]
    ) -> [ActivityFeedItem] {
        var items: [ActivityFeedItem] = sales.map { .sale($0) }
        items.append(contentsOf: makeActivityItems(activityRows, userNames: userNames, productNames: productNames))
        items.append(contentsOf: intakeGroups.map { .stockIntake($0) })
        return items.sorted { $0.date > $1.date }
    }
}
