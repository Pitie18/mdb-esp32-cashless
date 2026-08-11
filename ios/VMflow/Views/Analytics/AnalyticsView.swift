import Charts
import SwiftUI

/// Cross-fleet sales analytics. The page is a report frame (filters, KPIs,
/// trend) over a switchable breakdown block.
struct AnalyticsView: View {
    @StateObject private var viewModel = AnalyticsViewModel()
    @State private var selectedProduct: AnalyticsBreakdownRow?

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
                        AnalyticsMetricPicker(metric: $viewModel.metric)
                        trendChart
                        AnalyticsBreakdownList(viewModel: viewModel) { row in
                            selectedProduct = row
                        }
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

    // MARK: - Trend

    /// Weekend bars are drawn lighter. Beyond 60 days the daily bars become
    /// hairlines, so the points are folded into weekly buckets first.
    private var trendChart: some View {
        let points = bucketedPoints
        let prevAvg = previousDailyAverage
        return VStack(alignment: .leading, spacing: 8) {
            Text("Trend")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
            Chart {
                ForEach(points) { point in
                    BarMark(
                        x: .value("Date", point.day, unit: bucketUnit),
                        y: .value("Value", point.value(for: viewModel.metric))
                    )
                    .foregroundStyle(point.isWeekend && bucketUnit == .day
                                     ? Color.accentColor.opacity(0.4).gradient
                                     : Color.accentColor.gradient)
                    .cornerRadius(3)
                }
                if prevAvg > 0 {
                    RuleMark(y: .value("Previous average", prevAvg))
                        .foregroundStyle(.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                }
            }
            .frame(height: 150)
            .chartYAxis { AxisMarks(position: .leading) }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private var bucketUnit: Calendar.Component {
        chartBucket(forDays: viewModel.summary?.range.days ?? 30) == .week ? .weekOfYear : .day
    }

    private var bucketedPoints: [AnalyticsDailyPoint] {
        let daily = viewModel.summary?.daily ?? []
        guard bucketUnit == .weekOfYear else { return daily }
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: daily) { point in
            calendar.dateInterval(of: .weekOfYear, for: point.day)?.start ?? point.day
        }
        return grouped.map { start, group in
            AnalyticsDailyPoint(
                day: start,
                units: group.reduce(0) { $0 + $1.units },
                revenueGross: group.reduce(0) { $0 + $1.revenueGross },
                grossProfit: group.reduce(0) { $0 + $1.grossProfit })
        }
        .sorted { $0.day < $1.day }
    }

    /// The previous period's average per bucket — the dashed reference line.
    private var previousDailyAverage: Double {
        guard let summary = viewModel.summary, summary.range.days > 0 else { return 0 }
        let perDay = summary.previous.value(for: viewModel.metric) / summary.range.days
        return bucketUnit == .weekOfYear ? perDay * 7 : perDay
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
