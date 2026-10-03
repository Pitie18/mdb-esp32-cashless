import SwiftUI

// Shared date helpers for the purchase-price views (file-scoped to avoid clashing
// with any app-wide formatter). DB dates are ISO "yyyy-MM-dd".
fileprivate enum EKDate {
    static let iso: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
    /// "2026-06-01" → medium-style "1 Jun 2026" (locale-aware); falls back to raw.
    static func display(_ s: String) -> String {
        guard let d = iso.date(from: s) else { return s }
        return d.formatted(.dateTime.day().month(.abbreviated).year())
    }
}

fileprivate let ekCurrency2: FloatingPointFormatStyle<Double>.Currency =
    .currency(code: "EUR").precision(.fractionLength(2))

/// Manage a product's purchase prices — native List + pushed editor.
/// - EDIT mode (`productId != nil`): rows persist via the RPCs; tap a row to edit,
///   toolbar "+" to add. Cheapest / "usual" badges + a margin footer.
/// - CREATE/BUFFER mode (`productId == nil`): entries are collected into the
///   `pending` binding; the parent flushes them after the product is created.
struct PurchasePricesSheet: View {
    let productId: UUID?
    let sellprice: Double?
    @Binding var pending: [PendingPurchasePrice]

    @StateObject private var vm = PurchasePricesViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var route: EditorRoute?
    @State private var pricePendingDelete: PurchasePrice?

    enum EditorRoute: Hashable, Identifiable {
        case add
        case edit(UUID)
        var id: String { switch self { case .add: "add"; case .edit(let i): i.uuidString } }
    }

    init(productId: UUID?, sellprice: Double?, pending: Binding<[PendingPurchasePrice]> = .constant([])) {
        self.productId = productId
        self.sellprice = sellprice
        self._pending = pending
    }

    private var isCreate: Bool { productId == nil }
    private var cheapestId: UUID? { vm.prices.min(by: { $0.priceGross < $1.priceGross })?.id }
    private var newest: PurchasePrice? { vm.prices.first }
    private var margin: (rohertrag: Double, spannePct: Double)? {
        guard let n = newest else { return nil }
        return PurchaseComparison.marginNet(sellpriceGross: sellprice, ekNet: n.priceNet, rate: n.taxRate)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if isCreate {
                        if pending.isEmpty { emptyRow }
                        ForEach(pending) { e in
                            PriceRow(supplier: e.supplierName, rawValue: e.price, basis: e.basis,
                                     priceNet: nil, priceGross: nil, observedOn: e.observedOn,
                                     isCheapest: false, isUsual: false)
                            .swipeActions(allowsFullSwipe: false) {
                                Button(role: .destructive) { pending.removeAll { $0.id == e.id } } label: {
                                    Label(String(localized: "Delete"), systemImage: "trash")
                                }
                            }
                        }
                    } else {
                        if vm.prices.isEmpty { emptyRow }
                        ForEach(vm.prices) { p in
                            Button { route = .edit(p.id) } label: {
                                PriceRow(supplier: p.supplierName,
                                         rawValue: p.priceBasis == "net" ? p.priceNet : p.priceGross,
                                         basis: p.priceBasis, priceNet: p.priceNet, priceGross: p.priceGross,
                                         observedOn: p.observedOn,
                                         isCheapest: p.id == cheapestId, isUsual: p.id == newest?.id)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pricePendingDelete = p
                                } label: { Label(String(localized: "Delete"), systemImage: "trash") }
                            }
                        }
                    }
                } header: {
                    Text(String(localized: "Recorded prices"))
                } footer: {
                    if !isCreate, let m = margin {
                        Text("\(String(localized: "Margin")): \(m.rohertrag.formatted(ekCurrency2)) · \(Int(m.spannePct.rounded()))%")
                    }
                }
            }
            .navigationTitle(String(localized: "Purchase prices"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { route = .add } label: { Image(systemName: "plus") }
                        .accessibilityLabel(String(localized: "Add purchase price"))
                }
            }
            .navigationDestination(item: $route) { r in
                PriceEditorView(mode: r, isCreate: isCreate, vm: vm, productId: productId,
                                seed: seed(for: r),
                                onCommitCreate: { pending.append($0) })
            }
            .task {
                await vm.loadSuppliers()
                if let pid = productId {
                    await vm.loadPrices(productId: pid)
                    await vm.resolveTaxRate(productId: pid)
                }
            }
            .confirmationDialog(
                Text("Delete Purchase Price", comment: "Confirmation dialog title before deleting a recorded purchase price"),
                isPresented: Binding(
                    get: { pricePendingDelete != nil },
                    set: { if !$0 { pricePendingDelete = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button(String(localized: "Delete", comment: "Confirm purchase price deletion button"), role: .destructive) {
                    if let p = pricePendingDelete {
                        Task { await vm.deletePrice(id: p.id, productId: p.productId) }
                    }
                    pricePendingDelete = nil
                }
                Button(String(localized: "Cancel", comment: "Cancel purchase price deletion button"), role: .cancel) {
                    pricePendingDelete = nil
                }
            } message: {
                if let p = pricePendingDelete {
                    Text(p.supplierName)
                }
            }
        }
    }

    private var emptyRow: some View {
        Text(String(localized: "No purchase prices recorded yet."))
            .font(.footnote).foregroundStyle(.secondary)
    }

    private func seed(for r: EditorRoute) -> PriceEditorSeed? {
        guard case .edit(let id) = r, let p = vm.prices.first(where: { $0.id == id }) else { return nil }
        return PriceEditorSeed(
            supplierName: p.supplierName,
            price: p.priceBasis == "net" ? p.priceNet : p.priceGross,
            basis: PriceBasis(rawValue: p.priceBasis) ?? .net,
            observedOn: EKDate.iso.date(from: p.observedOn) ?? Date(),
            note: p.note ?? "",
            taxRatePct: p.taxRate * 100)
    }
}

// MARK: - History row

private struct PriceRow: View {
    let supplier: String
    let rawValue: Double          // the figure the user entered
    let basis: String             // "net" | "gross"
    let priceNet: Double?         // resolved counterpart (nil in buffer mode)
    let priceGross: Double?
    let observedOn: String
    let isCheapest: Bool
    let isUsual: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(supplier.isEmpty ? String(localized: "Unknown supplier") : supplier)
                        .font(.subheadline.weight(.medium))
                    if isCheapest { badge(String(localized: "Cheapest"), .green) }
                    if isUsual { badge(String(localized: "Usual"), .blue) }
                }
                Text(priceLine).font(.footnote).foregroundStyle(.secondary)
                Text(EKDate.display(observedOn)).font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer(minLength: 8)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    private var priceLine: String {
        let main = rawValue.formatted(ekCurrency2)
        let mainLabel = basis == "net" ? String(localized: "net") : String(localized: "gross")
        if let other = basis == "net" ? priceGross : priceNet {
            let otherLabel = basis == "net" ? String(localized: "gross") : String(localized: "net")
            return "\(main) \(mainLabel) · \(other.formatted(ekCurrency2)) \(otherLabel)"
        }
        return "\(main) \(mainLabel)"
    }

    private func badge(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(Capsule().fill(color.opacity(0.15)))
            .foregroundStyle(color)
    }
}

// MARK: - Editor (add / edit, both modes)

struct PriceEditorSeed {
    var supplierName: String
    var price: Double
    var basis: PriceBasis
    var observedOn: Date
    var note: String
    var taxRatePct: Double?
}

private struct PriceEditorView: View {
    let mode: PurchasePricesSheet.EditorRoute
    let isCreate: Bool
    @ObservedObject var vm: PurchasePricesViewModel
    let productId: UUID?
    let seed: PriceEditorSeed?
    let onCommitCreate: (PendingPurchasePrice) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var supplierName = ""
    @State private var priceText: String = ""
    @State private var basis: PriceBasis = .net
    @State private var observedOn = Date()
    @State private var note = ""
    @State private var taxRatePctText = ""
    @State private var formError: String?
    @State private var saving = false

    private var price: Double { Double(priceText.replacingOccurrences(of: ",", with: ".")) ?? 0 }

    private var isEditing: Bool { if case .edit = mode { return true } else { return false } }
    private var needRateOverride: Bool { !isCreate && vm.resolvedRate == nil }
    private var effectiveRate: Double? {
        needRateOverride
            ? Double(taxRatePctText.replacingOccurrences(of: ",", with: ".")).map { $0 / 100 }
            : vm.resolvedRate
    }
    private var counterpartText: String? {
        guard price > 0, let r = effectiveRate else { return nil }
        let other = PurchaseComparison.counterpart(price, basis: basis, rate: r)
        let label = basis == .net ? String(localized: "gross") : String(localized: "net")
        return "= \(other.formatted(ekCurrency2)) \(label)"
    }
    private var canSave: Bool {
        !supplierName.trimmingCharacters(in: .whitespaces).isEmpty && price > 0
            && (!needRateOverride || effectiveRate != nil)
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    SupplierPickerView(suppliers: vm.suppliers, selected: $supplierName)
                } label: {
                    LabeledContent(String(localized: "Supplier")) {
                        Text(supplierName.isEmpty ? String(localized: "Required") : supplierName)
                            .foregroundStyle(supplierName.isEmpty ? .secondary : .primary)
                    }
                }
            }

            Section(String(localized: "Price per unit")) {
                HStack(spacing: 6) {
                    Text(String(localized: "Price per unit")).foregroundStyle(.secondary)
                    Spacer(minLength: 12)
                    TextField("0,00", text: $priceText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 140)
                        .font(.body.monospacedDigit())
                    Text("€").foregroundStyle(.secondary)
                }
                Picker(String(localized: "Basis"), selection: $basis) {
                    Text(String(localized: "net")).tag(PriceBasis.net)
                    Text(String(localized: "gross")).tag(PriceBasis.gross)
                }.pickerStyle(.segmented)
                if let c = counterpartText {
                    Text(c).font(.caption).foregroundStyle(.secondary)
                } else if isCreate {
                    Text(String(localized: "Net/gross is calculated after saving."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if needRateOverride {
                    TextField(String(localized: "Tax rate % (no rate on product)"), text: $taxRatePctText)
                        .keyboardType(.decimalPad)
                }
            }

            Section {
                DatePicker(String(localized: "Date"), selection: $observedOn, displayedComponents: .date)
                TextField(String(localized: "Note"), text: $note, axis: .vertical)
            }

            if let e = formError {
                Section { Text(e).font(.footnote).foregroundStyle(.red) }
            }
        }
        .navigationTitle(isEditing ? String(localized: "Edit purchase price") : String(localized: "Add purchase price"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isEditing ? String(localized: "Save") : String(localized: "Add")) {
                    Task { await submit() }
                }.disabled(!canSave || saving)
            }
        }
        .onAppear(perform: applySeed)
    }

    private func applySeed() {
        guard let s = seed else { return }
        supplierName = s.supplierName
        priceText = String(format: "%.2f", s.price)
        basis = s.basis
        observedOn = s.observedOn
        note = s.note
        taxRatePctText = (needRateOverride ? s.taxRatePct : nil).map { String(format: "%.2f", $0) } ?? ""
    }

    private func submit() async {
        let name = supplierName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, price > 0 else {
            formError = String(localized: "Supplier and price are required."); return
        }
        let dateStr = EKDate.iso.string(from: observedOn)

        if isCreate {
            onCommitCreate(PendingPurchasePrice(
                supplierName: name, price: price, basis: basis.rawValue,
                observedOn: dateStr, note: note.isEmpty ? nil : note))
            dismiss(); return
        }
        if needRateOverride && effectiveRate == nil {
            formError = String(localized: "Please provide a tax rate."); return
        }
        guard let pid = productId else { return }
        saving = true; defer { saving = false }
        let override = needRateOverride ? effectiveRate : nil
        let ok: Bool
        if case .edit(let id) = mode {
            ok = await vm.updatePrice(id: id, productId: pid, supplierName: name, price: price,
                                      basis: basis.rawValue, observedOn: dateStr,
                                      note: note.isEmpty ? nil : note, taxRateOverride: override)
        } else {
            ok = await vm.addPrice(productId: pid, supplierName: name, price: price,
                                   basis: basis.rawValue, observedOn: dateStr,
                                   note: note.isEmpty ? nil : note, taxRateOverride: override)
        }
        if ok { dismiss() } else { formError = vm.error }
    }
}

// MARK: - Supplier picker (existing or add-new), searchable

private struct SupplierPickerView: View {
    let suppliers: [Supplier]
    @Binding var selected: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    // Explicit "+" alongside the typed "Add …" row below: the search field
    // already doubles as an add-new field, but that isn't obvious before you've
    // typed something, so this gives an unambiguous, always-visible way in.
    @State private var showAddAlert = false
    @State private var newSupplierName = ""

    private var filtered: [Supplier] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return suppliers }
        return suppliers.filter { $0.name.localizedCaseInsensitiveContains(q) }
    }
    /// Offer "Add …" only for a non-empty query that isn't an exact existing name.
    private var addCandidate: String? {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty,
              !suppliers.contains(where: { $0.name.caseInsensitiveCompare(q) == .orderedSame })
        else { return nil }
        return q
    }

    var body: some View {
        List {
            if let new = addCandidate {
                Section {
                    Button { selected = new; dismiss() } label: {
                        Label(String(localized: "Add “\(new)”"), systemImage: "plus.circle.fill")
                    }
                }
            }
            Section(String(localized: "Suppliers")) {
                if filtered.isEmpty && addCandidate == nil {
                    Text(String(localized: "No suppliers yet.")).foregroundStyle(.secondary)
                }
                ForEach(filtered) { s in
                    Button { selected = s.name; dismiss() } label: {
                        HStack {
                            Text(s.name).foregroundStyle(.primary)
                            Spacer()
                            if s.name == selected {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(String(localized: "Supplier"))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: String(localized: "Search or add supplier"))
        .autocorrectionDisabled()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    newSupplierName = query.trimmingCharacters(in: .whitespaces)
                    showAddAlert = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(String(localized: "New Supplier"))
            }
        }
        .alert(String(localized: "New Supplier"), isPresented: $showAddAlert) {
            TextField(String(localized: "Supplier"), text: $newSupplierName)
            Button(String(localized: "Cancel"), role: .cancel) {}
            Button(String(localized: "Add")) {
                let trimmed = newSupplierName.trimmingCharacters(in: .whitespaces)
                guard !trimmed.isEmpty else { return }
                selected = trimmed
                dismiss()
            }
        }
    }
}

