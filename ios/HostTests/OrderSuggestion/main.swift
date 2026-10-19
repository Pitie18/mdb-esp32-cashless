import Foundation

// OrderSuggestion: same cases as the web's orderSuggestion.test.ts. Run via ./run.sh.

var failures = 0
var checks = 0

func expect<T: Equatable>(_ actual: T, _ expected: T, _ label: String, line: Int = #line) {
    checks += 1
    if actual != expected {
        failures += 1
        print("FAIL [\(label)] line \(line):\n  got      \(actual)\n  expected \(expected)")
    }
}

let s1 = UUID(), s2 = UUID()
let p1 = UUID(), p2 = UUID(), p3 = UUID(), p4 = UUID()

let becker = OrderSupplier(id: s1, name: "Becker", customerNumber: "40817", leadTimeDays: 2, orderIntervalDays: 14, minOrderValue: 50)
let hahn = OrderSupplier(id: s2, name: "Hahn", leadTimeDays: nil, orderIntervalDays: nil, minOrderValue: nil)
let settings = OrderSettings.default

func product(
    id: UUID = p1, name: String = "Cola", discontinued: Bool = false,
    warehouse: Int = 12, machines: Int = 18, avg: Double = 3.1, onOrder: Int = 0,
    unitSize: Int = 24, supplier: UUID? = s1, price: Double? = 0.52
) -> OrderProductInput {
    OrderProductInput(
        productId: id, productName: name, imagePath: nil, discontinued: discontinued,
        warehouseQty: warehouse, machineQty: machines, avgDailySales: avg, onOrder: onOrder,
        unitSize: unitSize, supplierId: supplier, unitPriceNet: price
    )
}

// ── targetDays ───────────────────────────────────────────────────────────────

expect(OrderSuggestion.targetDays(becker, settings), 14 + 2 + 3, "interval + lead + buffer")
expect(OrderSuggestion.targetDays(hahn, settings), 14 + 3 + 3, "company defaults")
expect(OrderSuggestion.targetDays(nil, settings), 20, "no supplier uses the defaults")

// ── suggestedCartons ─────────────────────────────────────────────────────────

// 3.1 × 19 = 58.9 needed, 30 available → 28.9 missing → 2 cartons of 24
expect(OrderSuggestion.suggestedCartons(product(), days: 19, settings), 2, "rounds up to cartons")
expect(OrderSuggestion.suggestedCartons(product(onOrder: 24), days: 19, settings), 1, "subtracts on order")
expect(OrderSuggestion.suggestedCartons(product(onOrder: 48), days: 19, settings), 0, "enough on order")
var noMachines = settings
noMachines.countMachineStock = false
expect(OrderSuggestion.suggestedCartons(product(machines: 100), days: 19, noMachines), 2, "machine stock ignored when off")
expect(OrderSuggestion.suggestedCartons(product(machines: 100), days: 19, settings), 0, "machine stock counted")
expect(OrderSuggestion.suggestedCartons(product(discontinued: true, warehouse: 0), days: 19, settings), 0, "discontinued")
expect(OrderSuggestion.suggestedCartons(product(warehouse: 0, avg: 0), days: 19, settings), 0, "unsold")
// 3.1 × 10 = 31.000000000000004; 7 available → exactly 24 missing → 1 carton
expect(OrderSuggestion.suggestedCartons(product(warehouse: 7, machines: 0), days: 10, settings), 1, "float noise")

// ── reachDays ────────────────────────────────────────────────────────────────

expect(OrderSuggestion.reachDays(product(), settings), 10, "warehouse + machines")
expect(OrderSuggestion.reachDays(product(avg: 0), settings), nil, "no sales, no reach")

// ── buildGroups ──────────────────────────────────────────────────────────────

let manual = ManualOrderEntry(productId: p3, addedByName: "Lucien", addedVia: "ios", createdAt: nil)
let groups = OrderSuggestion.buildGroups(
    products: [
        product(),
        product(id: p2, name: "Fanta", warehouse: 100),
        product(id: p3, name: "Snickers", warehouse: 80, avg: 1, supplier: s2),
        product(id: p4, name: "Nuts", warehouse: 0, machines: 0, unitSize: 1, supplier: nil),
    ],
    suppliers: [hahn, becker],
    manual: [manual],
    settings: settings
)
expect(groups.map { $0.supplier?.name }, ["Becker", "Hahn", nil], "group order")
if groups.count == 3 {
    let b = groups[0], h = groups[1], none = groups[2]
    expect(b.lines.map(\.product.productId), [p1], "Becker lines")
    expect(b.others.map(\.productId), [p2], "Becker others")
    expect(b.lines.first?.auto, true, "auto line")
    expect(b.lines.first?.units, 48, "units")
    expect(abs(b.value - 48 * 0.52) < 1e-9, true, "group value")
    expect(b.belowMinOrder, true, "below minimum order value")

    expect(h.lines.first?.auto, false, "manual line is not auto")
    expect(h.lines.first?.manual?.addedByName, "Lucien", "manual entry kept")
    expect(h.lines.first?.cartons, 1, "manual entry gets one carton")

    expect(none.supplier == nil, true, "no-supplier group")
    expect(none.hasEmpty, true, "contains an empty product")
    expect(none.lines.first?.cartons, 62, "3.1 × 20 days, cartons of 1")
}

let overridden = OrderSuggestion.buildGroups(
    products: [product()], suppliers: [becker], manual: [], overrides: [p1: 5], settings: settings
)
expect(overridden.first?.lines.first?.cartons, 5, "override cartons")
expect(overridden.first?.lines.first?.units, 120, "override units")
expect(overridden.first?.lines.first?.overridden, true, "override flagged")
expect(overridden.first?.belowMinOrder, false, "override reaches minimum")

let zeroed = OrderSuggestion.buildGroups(
    products: [product()], suppliers: [becker], manual: [], overrides: [p1: 0], settings: settings
)
expect(zeroed.first?.lines.first?.cartons, 0, "overridden zero stays on the list")
expect(zeroed.first?.belowMinOrder, false, "zero is not below minimum")

let plenty = OrderSuggestion.buildGroups(
    products: [product(warehouse: 500)], suppliers: [becker, hahn], manual: [], settings: settings
)
expect(plenty.map { $0.supplier?.id }, [s1], "empty groups dropped")
expect(plenty.first?.lines.isEmpty, true, "nothing to order")
expect(OrderSuggestion.lineCount(groups), 3, "line count")

// ── orderText ────────────────────────────────────────────────────────────────

let single = OrderSuggestion.buildGroups(products: [product()], suppliers: [becker], manual: [], settings: settings)
if let g = single.first {
    expect(
        OrderSuggestion.orderText(g, customerNumberLabel: "Kd.-Nr.", carton: { n, s in "\(n) × \(s)" }),
        "Becker\nKd.-Nr.: 40817\n\n48 × Cola (2 × 24)",
        "order text"
    )
}

if failures > 0 {
    print("\(failures) of \(checks) checks failed")
    exit(1)
}
print("order suggestion: all \(checks) checks passed")
