import Foundation

// Port of the tour cases of management-frontend/app/lib/__tests__/slotChange.test.ts
// (rebuildPackNeeds, fillPlan, computeLeftovers). Run via ./run.sh.

var failures = 0
var checks = 0

func expect<T: Equatable>(_ actual: T, _ expected: T, _ label: String, line: Int = #line) {
    checks += 1
    if actual != expected {
        failures += 1
        print("FAIL [\(label)] line \(line):\n  got      \(actual)\n  expected \(expected)")
    }
}

/// Deterministic UUID per readable name, so the cases read like the web ones.
var ids: [String: UUID] = [:]
func u(_ name: String) -> UUID {
    if let id = ids[name] { return id }
    let id = UUID()
    ids[name] = id
    return id
}

func item(_ id: String, _ tray: String, _ from: String?, _ to: String?, _ fromCap: Int, _ toCap: Int) -> SlotChangeLine {
    SlotChangeLine(
        id: u(id), trayId: u(tray), itemNumber: Int(tray.dropFirst())!,
        fromProductId: from.map(u), toProductId: to.map(u),
        fromCapacity: fromCap, toCapacity: toCap
    )
}

func byName(_ pairs: [(String, Int)]) -> [UUID: Int] {
    Dictionary(uniqueKeysWithValues: pairs.map { (u($0.0), $0.1) })
}

// ── tour: packing ────────────────────────────────────────────────────────────

do { // packs the new slots minus what moves over from changed slots
    let items = [item("i12", "t12", "schorle", "cola", 10, 10)]
    expect(SlotChange.rebuildPackNeeds(items, stockByTray: byName([("t12", 8)])), byName([("cola", 10)]), "double slot")
}

do { // a neighbour swap needs nothing from the warehouse when stock covers it
    let items = [
        item("i16", "t16", "balisto", "duplo", 12, 12),
        item("i17", "t17", "duplo", "balisto", 14, 14),
    ]
    let stock = byName([("t16", 10), ("t17", 12)])
    expect(SlotChange.rebuildPackNeeds(items, stockByTray: stock), byName([("duplo", 0), ("balisto", 4)]), "neighbour swap")
}

do { // a smaller spiral for the same product needs nothing extra
    let items = [item("i16", "t16", "pringles", "pringles", 8, 6)]
    expect(SlotChange.rebuildPackNeeds(items, stockByTray: byName([("t16", 5)])), byName([("pringles", 1)]), "smaller spiral, 5 left")
    expect(SlotChange.rebuildPackNeeds(items, stockByTray: byName([("t16", 8)])), byName([("pringles", 0)]), "smaller spiral, full")
}

do { // emptying a slot packs nothing
    let items = [item("i14", "t14", "tictac", nil, 15, 15)]
    expect(SlotChange.rebuildPackNeeds(items, stockByTray: byName([("t14", 14)])), [:], "empty slot")
}

// ── tour: at the machine ─────────────────────────────────────────────────────

do { // fills moved stock first, then van stock
    let items = [
        item("i16", "t16", "balisto", "duplo", 12, 12),
        item("i17", "t17", "duplo", "balisto", 14, 14),
    ]
    // a duplo sold since packing: 11 come out of 17
    let removed = byName([("i16", 10), ("i17", 11)])
    let plan = SlotChange.fillPlan(items, removedByItem: removed, vanByProduct: byName([("balisto", 4), ("duplo", 0)]))
    expect(plan[u("i16")], SlotFillSuggestion(moved: 11, van: 0, total: 11), "fill i16")
    expect(plan[u("i17")], SlotFillSuggestion(moved: 10, van: 4, total: 14), "fill i17")
}

do { // fill plan goes by slot number, not by input order
    let items = [
        item("i20", "t20", "a", "cola", 10, 6),
        item("i18", "t18", "b", "cola", 10, 6),
    ]
    let plan = SlotChange.fillPlan(items, removedByItem: [:], vanByProduct: byName([("cola", 8)]))
    expect(plan[u("i18")], SlotFillSuggestion(moved: 0, van: 6, total: 6), "lower slot first")
    expect(plan[u("i20")], SlotFillSuggestion(moved: 0, van: 2, total: 2), "higher slot gets the rest")
}

do { // leftovers split into van goods and goods from the machine
    let items = [
        item("i12", "t12", "schorle", "cola", 10, 10),
        item("i13", "t13", "redbull", "redbull", 8, 6),
    ]
    let outcome: [UUID: SlotItemOutcome] = [
        u("i12"): SlotItemOutcome(action: .done, removed: 7, filled: 7),
        u("i13"): SlotItemOutcome(action: .done, removed: 8, filled: 6),
    ]
    let left = SlotChange.computeLeftovers(items, outcome: outcome, packedByProduct: byName([("cola", 10)]))
    expect(left[u("cola")], SlotLeftoverSplit(van: 3, machine: 0), "cola leftovers")
    expect(left[u("schorle")], SlotLeftoverSplit(van: 0, machine: 7), "schorle leftovers")
    expect(left[u("redbull")], SlotLeftoverSplit(van: 0, machine: 2), "redbull leftovers")
}

do { // a skipped slot returns everything packed for it
    let items = [item("i12", "t12", "schorle", "cola", 10, 10)]
    let left = SlotChange.computeLeftovers(
        items,
        outcome: [u("i12"): SlotItemOutcome(action: .skip, removed: 0, filled: 0)],
        packedByProduct: byName([("cola", 10)])
    )
    expect(left[u("cola")], SlotLeftoverSplit(van: 10, machine: 0), "skip returns van")
    expect(left[u("schorle")] == nil, true, "skip takes nothing out")
}

do { // a swap where everything moves leaves nothing over
    let items = [
        item("i16", "t16", "balisto", "duplo", 12, 12),
        item("i17", "t17", "duplo", "balisto", 14, 14),
    ]
    let outcome: [UUID: SlotItemOutcome] = [
        u("i16"): SlotItemOutcome(action: .done, removed: 10, filled: 11),
        u("i17"): SlotItemOutcome(action: .done, removed: 11, filled: 14),
    ]
    let left = SlotChange.computeLeftovers(items, outcome: outcome, packedByProduct: byName([("balisto", 4)]))
    expect(left.isEmpty, true, "full swap leaves nothing")
}

// ── age restriction (ageChanged in slotChange.ts) ────────────────────────────

expect(SlotChange.ageChanged(from: nil, to: nil), false, "no restriction either side")
expect(SlotChange.ageChanged(from: nil, to: 18), true, "none → 18")
expect(SlotChange.ageChanged(from: 18, to: nil), true, "18 → none")
expect(SlotChange.ageChanged(from: 16, to: 18), true, "16 → 18")
expect(SlotChange.ageChanged(from: 18, to: 18), false, "18 → 18")

// ── server row decoding (PostgREST shape → persisted shape → back) ───────────

do {
    let json = """
    {"id":"\(u("i12"))","request_id":"\(u("r1"))","tray_id":"\(u("t12"))","item_number":12,
     "from_product_id":"\(u("schorle"))","to_product_id":null,"from_capacity":10,"to_capacity":8,
     "from_price":1.8,"to_price":null,"skip_count":2,
     "from_product":{"name":"Schorle","image_path":"s.png"},"to_product":null}
    """
    let decoded = try JSONDecoder().decode(SlotChangeRequestItem.self, from: Data(json.utf8))
    expect(decoded.fromName, "Schorle", "joined from name")
    expect(decoded.fromImagePath, "s.png", "joined from image")
    expect(decoded.toName, nil, "empty target has no name")
    expect(decoded.toProductId, nil, "empty target")
    expect(decoded.skipCount, 2, "skip count")
    expect(decoded.changesPrice, false, "emptying a slot sets no price")
    expect(decoded.changesCapacity, true, "capacity change")
    let roundTrip = try JSONDecoder().decode(SlotChangeRequestItem.self, from: JSONEncoder().encode(decoded))
    expect(roundTrip, decoded, "persisted round trip")
    expect(decoded.fromMinAge, nil, "row without age snapshot")
    expect(decoded.changesAge, false, "no age snapshot, no age change")

    let aged = """
    {"id":"\(u("i13"))","request_id":"\(u("r1"))","tray_id":"\(u("t13"))","item_number":13,
     "from_product_id":"\(u("cola"))","to_product_id":"\(u("beer"))","from_capacity":10,"to_capacity":10,
     "from_price":1.5,"to_price":2.5,"skip_count":0,"from_min_age":null,"to_min_age":18,
     "from_product":{"name":"Cola","image_path":null},"to_product":{"name":"Beer","image_path":null}}
    """
    let agedItem = try JSONDecoder().decode(SlotChangeRequestItem.self, from: Data(aged.utf8))
    expect(agedItem.fromMinAge, nil, "from age decoded")
    expect(agedItem.toMinAge, 18, "to age decoded")
    expect(agedItem.changesAge, true, "none → 18 changes the age")
    let agedRoundTrip = try JSONDecoder().decode(SlotChangeRequestItem.self, from: JSONEncoder().encode(agedItem))
    expect(agedRoundTrip, agedItem, "persisted round trip keeps the age snapshot")
} catch {
    failures += 1
    print("FAIL decoding threw: \(error)")
}

if failures > 0 {
    print("\(failures) of \(checks) checks failed")
    exit(1)
}
print("slot change: all \(checks) checks passed")
