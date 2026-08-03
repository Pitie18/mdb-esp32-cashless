import SwiftUI

/// Drilldown view: shows all batches for one product in the currently selected
/// warehouse, ordered by expiration date. Tapping a batch opens `BatchAdjustSheet`.
struct ProductBatchesView: View {
    let productId: UUID
    let productName: String
    let productImagePath: String?

    @EnvironmentObject private var viewModel: WarehouseViewModel
    @State private var selectedBatch: WarehouseStockBatch?

    var body: some View {
        Group {
            if viewModel.isLoadingBatches && viewModel.drilldownBatches.isEmpty {
                ProgressView(String(localized: "Loading batches..."))
            } else if viewModel.drilldownBatches.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "shippingbox")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text(String(localized: "No batches in stock"))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            } else {
                List {
                    ForEach(viewModel.drilldownBatches) { batch in
                        Button {
                            selectedBatch = batch
                        } label: {
                            BatchRow(batch: batch)
                        }
                        .buttonStyle(.plain)
                    }
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
