import Supabase
import SwiftUI

/// Drill-down for one product under the currently active filters: its KPIs,
/// how it splits across machines, and what it costs to buy.
struct ProductAnalyticsSheet: View {
    let row: AnalyticsBreakdownRow
    @ObservedObject var viewModel: AnalyticsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var machineRows: [AnalyticsBreakdownRow] = []
    @State private var purchase: ProductPurchaseSummary?
    @State private var isLoading = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    kpiRow
                    machineDistribution
                    stockCard
                    purchaseCard
                }
                .padding()
            }
            .navigationTitle(row.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .task {
            guard let productId = row.key else { isLoading = false; return }
            machineRows = await viewModel.loadProductMachines(productId: productId)
            purchase = await loadPurchaseSummary(productId: productId)
            isLoading = false
        }
    }

    /// Latest purchase price and observed spread — the "why is the margin what
    /// it is" half of the sheet. Non-fatal: the rest of the sheet stands alone.
    private func loadPurchaseSummary(productId: UUID) async -> ProductPurchaseSummary? {
        do {
            let rows: [ProductPurchaseSummary] = try await SupabaseService.shared.client
                .rpc("get_product_purchase_summary",
                     params: ["p_product_ids": AnyJSON.array([.string(productId.uuidString)])])
                .execute()
                .value
            return rows.first
        } catch {
            return nil
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            ProductImage(imagePath: row.imagePath, size: 52)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.label).font(.headline)
                Text(String(format: String(localized: "Class %@ · %.1f %% of revenue"),
                            row.abcClass, row.sharePct))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var kpiRow: some View {
        HStack(spacing: 8) {
            KPICard(icon: "cart.fill", title: "Units", value: String(row.units),
                    subtitle: LocalizedStringKey(String(format: String(localized: "⌀ %.1f/day"),
                                                        row.avgDailyUnits)),
                    color: .green)
            KPICard(icon: "eurosign.circle.fill", title: "Revenue",
                    value: String(format: "%.2f €", row.revenue),
                    subtitle: deltaSubtitle, color: .blue)
            KPICard(icon: "chart.line.uptrend.xyaxis", title: "Gross profit",
                    value: row.hasCost ? String(format: "%.2f €", row.grossProfit) : "—",
                    subtitle: row.hasCost ? nil : LocalizedStringKey("no purchase price"),
                    color: .purple)
        }
    }

    private var deltaSubtitle: LocalizedStringKey? {
        guard let delta = deltaPct(current: row.revenue, previous: row.prevRevenue) else { return nil }
        return LocalizedStringKey(String(format: "%+.0f %%", delta))
    }

    private var machineDistribution: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Per machine")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if isLoading {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 16)
            } else if machineRows.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                let maxUnits = machineRows.map(\.units).max() ?? 0
                ForEach(machineRows) { machine in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(machine.label).font(.subheadline)
                            Spacer()
                            Text(String(machine.units))
                                .font(.subheadline.weight(.semibold)).monospacedDigit()
                        }
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color.accentColor)
                                .frame(width: maxUnits > 0
                                       ? geo.size.width * Double(machine.units) / Double(maxUnits) : 0)
                        }
                        .frame(height: 6)
                        Text(machineSubtitle(machine))
                            .font(.caption2).foregroundStyle(machine.totalStock == 0 ? .red : .secondary)
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private func machineSubtitle(_ machine: AnalyticsBreakdownRow) -> String {
        var parts = [String(format: String(localized: "⌀ %.1f/day"), machine.avgDailyUnits)]
        if machine.totalCapacity > 0 {
            parts.append(String(format: String(localized: "stock %d/%d"),
                                machine.totalStock, machine.totalCapacity))
        }
        if machine.totalStock == 0 && machine.totalCapacity > 0 {
            parts.append(String(localized: "empty"))
        }
        return parts.joined(separator: " · ")
    }

    private var stockCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Stock")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
            Text(String(format: String(localized: "%d of %d slots filled"),
                        row.totalStock, row.totalCapacity))
                .font(.subheadline)
            if let sellThrough = row.sellThroughPct {
                Text(String(format: String(localized: "Sell-through %.1f %%"), sellThrough))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let days = row.daysOfSupply {
                Text(String(format: String(localized: "Lasts about %.0f more days"), days))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    @ViewBuilder
    private var purchaseCard: some View {
        if let purchase, purchase.ekCount > 0 {
            VStack(alignment: .leading, spacing: 6) {
                Text("Purchase")
                    .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
                if let net = purchase.newestNet {
                    Text(String(format: String(localized: "Latest %.2f € net"), net))
                        .font(.subheadline)
                }
                Text(purchaseDetail(purchase))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
        }
    }

    private func purchaseDetail(_ purchase: ProductPurchaseSummary) -> String {
        var parts: [String] = []
        if let supplier = purchase.newestSupplier { parts.append(supplier) }
        if let date = purchase.newestOn { parts.append(date) }
        if let low = purchase.minGross, let high = purchase.maxGross, low != high {
            parts.append(String(format: String(localized: "range %.2f – %.2f € gross"), low, high))
        }
        parts.append(String(localized: "\(purchase.ekCount) quote"))
        return parts.joined(separator: " · ")
    }
}
