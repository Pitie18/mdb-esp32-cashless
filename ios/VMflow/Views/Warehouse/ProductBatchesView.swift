import SwiftUI

/// Drilldown view: shows all batches for one product in the currently selected
/// warehouse, ordered by expiration date. Tapping a batch opens `BatchAdjustSheet`.
struct ProductBatchesView: View {
    let productId: UUID
    let productName: String
    let productImagePath: String?

    @EnvironmentObject private var viewModel: WarehouseViewModel
    @State private var selectedBatch: WarehouseStockBatch?

    /// Admins edit the main supplier and carton size here.
    var isAdmin = false

    var body: some View {
        Group {
            if viewModel.isLoadingBatches && viewModel.drilldownBatches.isEmpty {
                ProgressView(String(localized: "Loading batches..."))
            } else {
                List {
                    if viewModel.drilldownBatches.isEmpty {
                        Section {
                            Label(String(localized: "No batches in stock"), systemImage: "shippingbox")
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Section {
                            ForEach(viewModel.drilldownBatches) { batch in
                                Button {
                                    selectedBatch = batch
                                } label: {
                                    BatchRow(batch: batch)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    ProductOrderingSection(productId: productId, isAdmin: isAdmin)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle(productName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.loadBatchesForProduct(productId)
        }
        .dataRefreshable {
            await viewModel.loadBatchesForProduct(productId)
        }
        .sheet(item: $selectedBatch) { batch in
            BatchAdjustSheet(
                batch: batch,
                productName: productName,
                imagePath: productImagePath
            ) { signedDelta, reason, notes in
                await viewModel.adjustBatch(
                    batchId: batch.id,
                    quantityChange: signedDelta,
                    reason: reason,
                    notes: notes
                )
            }
        }
    }
}

/// Ordering of one product: order-list state with add/remove, units on the
/// way, and for admins the main supplier and carton size (web parity with
/// `WarehouseProductSheet.vue`).
private struct ProductOrderingSection: View {
    let productId: UUID
    let isAdmin: Bool

    @EnvironmentObject private var viewModel: WarehouseViewModel
    @State private var unitSizeText = ""

    var body: some View {
        let manual = viewModel.manualEntry(for: productId)
        let onList = viewModel.orderListProductIds.contains(productId)
        let onOrder = viewModel.onOrder(productId)
        let info = viewModel.productOrderInfo[productId]
        Section {
            if let manual {
                Label(String(localized: "Manually on the order list · \(manual.addedByName ?? "—"), \(OrderBadge.viaLabel(manual.addedVia))"),
                      systemImage: "cart.fill")
                    .foregroundStyle(.purple)
                Button(String(localized: "Remove from order list"), systemImage: "cart.badge.minus", role: .destructive) {
                    Task { await viewModel.removeFromOrderList(productId) }
                }
            } else {
                if onList {
                    Label(String(localized: "On the order list automatically"), systemImage: "cart.fill")
                        .foregroundStyle(.blue)
                }
                Button(String(localized: "Add to order list"), systemImage: "cart.badge.plus") {
                    Task { await viewModel.addToOrderList(productId) }
                }
            }
            if onOrder > 0 {
                Label(String(localized: "\(onOrder) units are ordered and on the way."), systemImage: "shippingbox")
                    .foregroundStyle(.secondary)
            }

            if isAdmin {
                Picker(String(localized: "Main supplier"), selection: Binding(
                    get: { info?.preferredSupplierId },
                    set: { id in Task { await viewModel.assignSupplier(productId, supplierId: id) } }
                )) {
                    Text(String(localized: "None")).tag(UUID?.none)
                    ForEach(viewModel.orderSuppliers) { s in
                        Text(s.name).tag(UUID?.some(s.id))
                    }
                }
                HStack {
                    Text(String(localized: "Units per carton"))
                    Spacer()
                    TextField(String(info?.unitSize ?? 1), text: $unitSizeText)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 80)
                        .onSubmit(saveUnitSize)
                    if let n = Int(unitSizeText), n >= 1, n != (info?.unitSize ?? 1) {
                        Button(String(localized: "Save"), action: saveUnitSize)
                            .buttonStyle(.borderless)
                    }
                }
            } else if let id = info?.supplierId, let name = viewModel.orderSuppliers.first(where: { $0.id == id })?.name {
                LabeledContent(String(localized: "Supplier"), value: name)
            }
        } header: {
            Text(String(localized: "Order"))
        }
        .onAppear { unitSizeText = String(info?.unitSize ?? 1) }
    }

    private func saveUnitSize() {
        guard let n = Int(unitSizeText), n >= 1 else { return }
        Task { await viewModel.saveUnitSize(productId, n) }
    }
}

/// Single batch row in the drilldown list.
private struct BatchRow: View {
    let batch: WarehouseStockBatch

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(batch.batchNumber ?? String(localized: "No batch"))
                    .font(.body)
                if let exp = batch.expirationDate {
                    HStack(spacing: 4) {
                        Image(systemName: "calendar")
                            .font(.caption2)
                        Text(Self.displayDate(exp))
                            .font(.caption)
                    }
                    .foregroundStyle(expirationColor(exp))
                }
            }
            Spacer()
            Text("\(batch.quantity)")
                .font(.title3.bold().monospacedDigit())
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    /// Shared parser for `yyyy-MM-dd` DB strings, pinned to Gregorian / POSIX
    /// so non-Gregorian user locales don't silently fail to parse.
    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(secondsFromGMT: 0)
        return f
    }()

    /// Locale-aware short date (DD/MM/YYYY for fr/nl/de) from a raw ISO string.
    private static func displayDate(_ s: String) -> String {
        guard let d = isoFormatter.date(from: s) else { return s }
        return d.formatted(.dateTime.day().month(.twoDigits).year())
    }

    private func expirationColor(_ dateString: String) -> Color {
        guard let date = Self.isoFormatter.date(from: dateString) else { return .secondary }
        let days = Calendar.current.dateComponents([.day], from: Date(), to: date).day ?? 0
        if days < 7 { return .red }
        if days <= 30 { return .orange }
        return .secondary
    }
}
