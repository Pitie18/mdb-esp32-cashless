import Charts
import SwiftUI

/// Cross-fleet sales analytics. The page is a report frame (filters, KPIs,
/// trend) over a switchable breakdown block.
struct AnalyticsView: View {
    @StateObject private var viewModel = AnalyticsViewModel()
    @State private var selectedProduct: AnalyticsBreakdownRow?
    @State private var selectedBucket: Date?

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
                        HeatmapCard(cells: viewModel.summary?.heatmap ?? [])
                        ChannelSplitCard(channels: viewModel.summary?.channels ?? [])
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
        .sheet(item: $selectedProduct) { row in
            ProductAnalyticsSheet(row: row, viewModel: viewModel)
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
        let average = bucketAverage
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Trend")
                    .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
                Spacer()
                if average > 0 {
                    Text(verbatim: "⌀ \(format(average, metric: viewModel.metric))")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.orange)
                }
            }
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

                // Same treatment as the dashboard's revenue chart: orange,
                // dashed, with the value spelled out on the line.
                if average > 0 {
                    RuleMark(y: .value("Average", average))
                        .foregroundStyle(.orange)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .annotation(
                            position: .top,
                            alignment: .trailing,
                            spacing: 2,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            Text(verbatim: "⌀ \(format(average, metric: viewModel.metric))")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.orange)
                        }
                }

                if selectedBucket != nil, let point = selectedPoint {
                    RuleMark(x: .value("Selected", point.day, unit: bucketUnit))
                        .foregroundStyle(.gray.opacity(0.35))
                        .annotation(
                            position: .top,
                            alignment: .center,
                            spacing: 4,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            tooltip(for: point)
                        }
                }
            }
            .frame(height: 150)
            .chartXSelection(value: $selectedBucket)
            .chartYAxis { AxisMarks(position: .leading) }
            .animation(.smooth, value: selectedBucket)
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    /// Average per rendered bucket, so the line sits where the bars are: with
    /// weekly bars it is the weekly average, not the daily one.
    private var bucketAverage: Double {
        let points = bucketedPoints
        guard !points.isEmpty else { return 0 }
        return points.reduce(0) { $0 + $1.value(for: viewModel.metric) } / Double(points.count)
    }

    private var selectedPoint: AnalyticsDailyPoint? {
        guard let selectedBucket else { return nil }
        let unit: Calendar.Component = bucketUnit == .weekOfYear ? .weekOfYear : .day
        return bucketedPoints.first {
            Calendar.current.isDate($0.day, equalTo: selectedBucket, toGranularity: unit)
        }
    }

    private func tooltip(for point: AnalyticsDailyPoint) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(point.day, format: .dateTime.day().month(.abbreviated).year())
                .font(.caption.weight(.semibold))
            tooltipRow("Revenue", String(format: "%.2f €", point.revenueGross))
            tooltipRow("Units", "\(point.units)")
            tooltipRow("Gross profit", String(format: "%.2f €", point.grossProfit))
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background {
            RoundedRectangle(cornerRadius: 8)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
        }
        .frame(minWidth: 150)
    }

    private func tooltipRow(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack(spacing: 12) {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit()
        }
        .font(.caption2)
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
