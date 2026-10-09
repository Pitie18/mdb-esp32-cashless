import Foundation

// MARK: - Slot re-assignment ("Fächer umbelegen") — tour maths
//
// The office plans a new slot layout on the web (machine page → "Umbelegen")
// and saves it as a change request (`save_slot_change_request`), never as a
// tray update. The next refill tour shows the request as a change note: it
// packs what the new slots need (`rebuildPackNeeds`), suggests how to fill them
// at the machine (`fillPlan`) and books what is left over (`computeLeftovers`)
// through `apply_slot_change`.
//
// Bookkeeping rule: until the refiller quits the rebuild at the machine the old
// product stays in the slot, so sales keep decrementing it. The units actually
// taken out ("removed") are counted at the machine; they go into the new slots
// of the same product first, and only the rest comes from the van.
//
// Faithful port of `management-frontend/app/lib/slotChange.ts` (tour part) —
// change web, iOS and Android together. Pure Foundation, no Supabase/SwiftUI,
// so it compiles on its own: host tests in `ios/HostTests/SlotChange/run.sh`.

/// One changed slot, as the tour maths sees it (`ChangeItem` on the web).
struct SlotChangeLine: Equatable, Hashable, Codable {
    /// `slot_change_request_items.id`
    let id: UUID
    let trayId: UUID
    let itemNumber: Int
    let fromProductId: UUID?
    /// `nil` = the slot is left empty.
    let toProductId: UUID?
    let fromCapacity: Int
    let toCapacity: Int
}

/// Suggested fill of one rebuilt slot.
struct SlotFillSuggestion: Equatable {
    /// Units that came out of another changed slot.
    let moved: Int
    /// Units from the van.
    let van: Int
    let total: Int

    static let zero = SlotFillSuggestion(moved: 0, van: 0, total: 0)
}

/// What the refiller did with a slot at the machine.
enum SlotChangeAction: String, Codable, Equatable {
    case done
    case skip
}

struct SlotItemOutcome: Equatable {
    let action: SlotChangeAction
    let removed: Int
    let filled: Int
}

/// Left over per product after the rebuild, split by origin.
struct SlotLeftoverSplit: Equatable {
    /// Packed for the rebuild but not used (goes back to its batch).
    let van: Int
    /// Taken out of a slot and not put into another one (new batch).
    let machine: Int
}

enum SlotChange {
    /// Units to pack for the rebuild, per product: the capacity of its new
    /// slots minus what comes out of the changed slots that held it (that
    /// stock moves over). `stockByTray` is the stock known at packing time.
    /// Products whose new slots are fully covered by moved stock are listed
    /// with 0.
    static func rebuildPackNeeds(_ items: [SlotChangeLine], stockByTray: [UUID: Int]) -> [UUID: Int] {
        var need: [UUID: Int] = [:]
        for it in items {
            guard let to = it.toProductId else { continue }
            need[to, default: 0] += it.toCapacity
        }
        for it in items {
            guard let from = it.fromProductId, let current = need[from] else { continue }
            need[from] = current - (stockByTray[it.trayId] ?? 0)
        }
        for (k, v) in need { need[k] = max(0, v) }
        return need
    }

    /// Suggested fill per rebuilt slot, lowest slot number first: stock taken
    /// out of the changed slots goes in first, the van covers the rest.
    static func fillPlan(
        _ items: [SlotChangeLine],
        removedByItem: [UUID: Int],
        vanByProduct: [UUID: Int]
    ) -> [UUID: SlotFillSuggestion] {
        var freed: [UUID: Int] = [:]
        for it in items {
            guard let from = it.fromProductId else { continue }
            freed[from, default: 0] += removedByItem[it.id] ?? 0
        }
        var van = vanByProduct
        var out: [UUID: SlotFillSuggestion] = [:]
        for it in sortedBySlot(items) {
            guard let to = it.toProductId else {
                out[it.id] = .zero
                continue
            }
            let moved = min(it.toCapacity, freed[to] ?? 0)
            freed[to] = (freed[to] ?? 0) - moved
            let fromVan = min(it.toCapacity - moved, van[to] ?? 0)
            van[to] = (van[to] ?? 0) - fromVan
            out[it.id] = SlotFillSuggestion(moved: moved, van: fromVan, total: moved + fromVan)
        }
        return out
    }

    /// What is left over per product after the rebuild, split by origin:
    /// `van` = packed for the rebuild but not used (goes back to its batch),
    /// `machine` = taken out of a slot and not put into another one (new
    /// batch). Moved stock is used before van stock, matching `fillPlan`. Only
    /// products with something left are returned.
    static func computeLeftovers(
        _ items: [SlotChangeLine],
        outcome: [UUID: SlotItemOutcome],
        packedByProduct: [UUID: Int]
    ) -> [UUID: SlotLeftoverSplit] {
        var freed: [UUID: Int] = [:]
        var filledInto: [UUID: Int] = [:]
        for it in items {
            guard let o = outcome[it.id], o.action == .done else { continue }
            if let from = it.fromProductId { freed[from, default: 0] += max(0, o.removed) }
            if let to = it.toProductId { filledInto[to, default: 0] += max(0, o.filled) }
        }
        let products = Set(freed.keys).union(filledInto.keys).union(packedByProduct.keys)
        var out: [UUID: SlotLeftoverSplit] = [:]
        for pid in products {
            let f = freed[pid] ?? 0
            let filled = filledInto[pid] ?? 0
            let packed = packedByProduct[pid] ?? 0
            let moved = min(f, filled)
            let vanUsed = min(packed, filled - moved)
            let left = SlotLeftoverSplit(van: packed - vanUsed, machine: f - moved)
            if left.van + left.machine > 0 { out[pid] = left }
        }
        return out
    }

    /// The age restriction changes with the switch (nil = no restriction, so
    /// nil → 16 is a change and nil → nil is not). Mirrors `ageChanged` in
    /// the web's `lib/slotChange.ts`.
    static func ageChanged(from: Int?, to: Int?) -> Bool {
        from != to
    }

    /// Items by slot number; ties keep their input order (the web's stable sort).
    private static func sortedBySlot(_ items: [SlotChangeLine]) -> [SlotChangeLine] {
        items.enumerated()
            .sorted { a, b in
                if a.element.itemNumber != b.element.itemNumber { return a.element.itemNumber < b.element.itemNumber }
                return a.offset < b.offset
            }
            .map(\.element)
    }
}

// MARK: - Server rows

/// One pending slot of a machine's open change request
/// (`slot_change_request_items`, with the from/to product joined in).
/// Decodes the PostgREST row and round-trips through the persisted tour state.
struct SlotChangeRequestItem: Codable, Equatable, Identifiable {
    let id: UUID
    let requestId: UUID
    let trayId: UUID
    let itemNumber: Int
    let fromProductId: UUID?
    let toProductId: UUID?
    let fromCapacity: Int
    let toCapacity: Int
    let fromPrice: Double?
    let toPrice: Double?
    let skipCount: Int
    let fromName: String?
    let toName: String?
    let fromImagePath: String?
    let toImagePath: String?
    /// Age restriction (`product_category.min_age`) of the old / new product,
    /// snapshotted when the slot was planned; nil = no restriction.
    let fromMinAge: Int?
    let toMinAge: Int?

    /// Column list for `.select(...)` — the two product joins are named by
    /// their foreign keys because both point at `products`.
    static let selectColumns = """
        id, request_id, tray_id, item_number, from_product_id, to_product_id, \
        from_capacity, to_capacity, from_price, to_price, skip_count, \
        from_min_age, to_min_age, \
        from_product:products!slot_change_request_items_from_product_id_fkey(name, image_path), \
        to_product:products!slot_change_request_items_to_product_id_fkey(name, image_path)
        """

    init(
        id: UUID, requestId: UUID, trayId: UUID, itemNumber: Int,
        fromProductId: UUID?, toProductId: UUID?, fromCapacity: Int, toCapacity: Int,
        fromPrice: Double? = nil, toPrice: Double? = nil, skipCount: Int = 0,
        fromName: String? = nil, toName: String? = nil,
        fromImagePath: String? = nil, toImagePath: String? = nil,
        fromMinAge: Int? = nil, toMinAge: Int? = nil
    ) {
        self.id = id
        self.requestId = requestId
        self.trayId = trayId
        self.itemNumber = itemNumber
        self.fromProductId = fromProductId
        self.toProductId = toProductId
        self.fromCapacity = fromCapacity
        self.toCapacity = toCapacity
        self.fromPrice = fromPrice
        self.toPrice = toPrice
        self.skipCount = skipCount
        self.fromName = fromName
        self.toName = toName
        self.fromImagePath = fromImagePath
        self.toImagePath = toImagePath
        self.fromMinAge = fromMinAge
        self.toMinAge = toMinAge
    }

    private struct ProductRef: Codable {
        let name: String?
        let imagePath: String?
        enum CodingKeys: String, CodingKey {
            case name
            case imagePath = "image_path"
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case requestId = "request_id"
        case trayId = "tray_id"
        case itemNumber = "item_number"
        case fromProductId = "from_product_id"
        case toProductId = "to_product_id"
        case fromCapacity = "from_capacity"
        case toCapacity = "to_capacity"
        case fromPrice = "from_price"
        case toPrice = "to_price"
        case skipCount = "skip_count"
        case fromMinAge = "from_min_age"
        case toMinAge = "to_min_age"
        case fromProduct = "from_product"
        case toProduct = "to_product"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        requestId = try c.decode(UUID.self, forKey: .requestId)
        trayId = try c.decode(UUID.self, forKey: .trayId)
        itemNumber = try c.decode(Int.self, forKey: .itemNumber)
        fromProductId = try c.decodeIfPresent(UUID.self, forKey: .fromProductId)
        toProductId = try c.decodeIfPresent(UUID.self, forKey: .toProductId)
        fromCapacity = try c.decode(Int.self, forKey: .fromCapacity)
        toCapacity = try c.decode(Int.self, forKey: .toCapacity)
        fromPrice = try c.decodeIfPresent(Double.self, forKey: .fromPrice)
        toPrice = try c.decodeIfPresent(Double.self, forKey: .toPrice)
        skipCount = try c.decodeIfPresent(Int.self, forKey: .skipCount) ?? 0
        // Absent in tours saved by builds before the age restriction.
        fromMinAge = try c.decodeIfPresent(Int.self, forKey: .fromMinAge)
        toMinAge = try c.decodeIfPresent(Int.self, forKey: .toMinAge)
        let from = try c.decodeIfPresent(ProductRef.self, forKey: .fromProduct)
        let to = try c.decodeIfPresent(ProductRef.self, forKey: .toProduct)
        fromName = from?.name
        fromImagePath = from?.imagePath
        toName = to?.name
        toImagePath = to?.imagePath
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(requestId, forKey: .requestId)
        try c.encode(trayId, forKey: .trayId)
        try c.encode(itemNumber, forKey: .itemNumber)
        try c.encodeIfPresent(fromProductId, forKey: .fromProductId)
        try c.encodeIfPresent(toProductId, forKey: .toProductId)
        try c.encode(fromCapacity, forKey: .fromCapacity)
        try c.encode(toCapacity, forKey: .toCapacity)
        try c.encodeIfPresent(fromPrice, forKey: .fromPrice)
        try c.encodeIfPresent(toPrice, forKey: .toPrice)
        try c.encode(skipCount, forKey: .skipCount)
        try c.encodeIfPresent(fromMinAge, forKey: .fromMinAge)
        try c.encodeIfPresent(toMinAge, forKey: .toMinAge)
        if fromName != nil || fromImagePath != nil {
            try c.encode(ProductRef(name: fromName, imagePath: fromImagePath), forKey: .fromProduct)
        }
        if toName != nil || toImagePath != nil {
            try c.encode(ProductRef(name: toName, imagePath: toImagePath), forKey: .toProduct)
        }
    }

    /// The slot as the tour maths sees it.
    var line: SlotChangeLine {
        SlotChangeLine(
            id: id, trayId: trayId, itemNumber: itemNumber,
            fromProductId: fromProductId, toProductId: toProductId,
            fromCapacity: fromCapacity, toCapacity: toCapacity
        )
    }

    /// The selection gets a new price that has to be set at the machine.
    var changesPrice: Bool {
        guard toProductId != nil, let toPrice else { return false }
        return toPrice != fromPrice
    }

    var changesCapacity: Bool { toCapacity != fromCapacity }

    /// The age restriction of the selection changes, so the machine's age
    /// setting has to be changed and confirmed at the machine.
    var changesAge: Bool { SlotChange.ageChanged(from: fromMinAge, to: toMinAge) }
}

/// A machine's open change request with its pending slots.
struct SlotChangeRequest: Codable, Equatable, Identifiable {
    let id: UUID
    let machineId: UUID
    let note: String?
    var items: [SlotChangeRequestItem]
}
