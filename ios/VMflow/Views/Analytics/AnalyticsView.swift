import SwiftUI

/// Cross-fleet sales analytics. The page is a report frame (filters, KPIs,
/// trend) over a switchable breakdown block.
struct AnalyticsView: View {
    @StateObject private var viewModel = AnalyticsViewModel()

    var body: some View {
        Group {
            if viewModel.backendUnsupported {
                ContentUnavailableView {
                    Label(String(localized: "Analytics not available"), systemImage: "server.rack")
                } description: {
                    Text("This server does not support analytics yet. Please update the backend.")
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        AnalyticsFilterBar(viewModel: viewModel)
                        kpiRow
                        costHint
                    }
                    .padding()
                }
            }
        }
        .navigationTitle(String(localized: "Analytics"))
        .navigationBarTitleDisplayMode(.large)
        .overlay {
            if viewModel.isLoading && viewModel.summary == nil {
                ProgressView()
            }
        }
        .alert(String(localized: "Error"), isPresented: .constant(viewModel.error != nil)) {
            Button(String(localized: "OK")) { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
        .dataRefreshable { await viewModel.load() }
        .task {
            // Tab roots re-run `.task` on every re-selection; load only once.
            guard !viewModel.didRunInitialLoad else { return }
            viewModel.didRunInitialLoad = true
            await viewModel.loadFilterOptions()
            await viewModel.load()
        }
    }

    // MARK: - KPI row

    private var kpiRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                kpiCard(.revenue, icon: "eurosign.circle.fill", title: "Revenue", color: .blue)
                kpiCard(.units, icon: "cart.fill", title: "Units", color: .green)
                kpiCard(.grossProfit, icon: "chart.line.uptrend.xyaxis", title: "Gross profit", color: .purple)
            }
            Text("vs. \(viewModel.previousRangeLabel)")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func kpiCard(_ metric: AnalyticsMetric, icon: String,
                         title: LocalizedStringKey, color: Color) -> some View {
        let totals = viewModel.summary?.totals ?? .empty
        let previous = viewModel.summary?.previous ?? .empty
        let delta = deltaPct(current: totals.value(for: metric),
                             previous: previous.value(for: metric))
        return KPICard(
            icon: icon,
            title: title,
            value: format(totals.value(for: metric), metric: metric),
            subtitle: delta.map { LocalizedStringKey(String(format: "%+.0f %%", $0)) },
            color: viewModel.metric == metric ? color : .secondary
        )
    }

    @ViewBuilder
    private var costHint: some View {
        if let missing = viewModel.summary?.missingCostProducts, missing > 0 {
            Label {
                Text("Gross profit is net of tax. \(missing) products have no purchase price and are excluded.")
            } icon: {
                Image(systemName: "info.circle")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func format(_ value: Double, metric: AnalyticsMetric) -> String {
        switch metric {
        case .units: return String(Int(value))
        case .revenue, .grossProfit: return String(format: "%.2f €", value)
        }
    }
}

#Preview {
    NavigationStack { AnalyticsView() }
}
