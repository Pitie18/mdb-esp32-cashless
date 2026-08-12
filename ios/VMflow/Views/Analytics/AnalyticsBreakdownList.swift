import SwiftUI

/// The shared metric segment. Rendered twice — above the chart and above the
/// breakdown — bound to the same view-model property, so switching one
/// switches the other.
struct AnalyticsMetricPicker: View {
    @Binding var metric: AnalyticsMetric

    var body: some View {
        Picker("", selection: $metric) {
            Text("Units").tag(AnalyticsMetric.units)
            Text("Revenue").tag(AnalyticsMetric.revenue)
            Text("Profit").tag(AnalyticsMetric.grossProfit)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

/// Dimension segment plus the sorted rows. Each row carries a share bar drawn
/// from the SELECTED metric — deliberately different from `share_pct`, which
/// is always revenue-based because it backs the ABC class.
struct AnalyticsBreakdownList: View {
    @ObservedObject var viewModel: AnalyticsViewModel
    var onSelectProduct: (AnalyticsBreakdownRow) -> Void

    private var rows: [AnalyticsBreakdownRow] { viewModel.sortedRows }

    var body: some View {
        // Computed once per render pass rather than once per row — accessing it
        // from inside the row builder made the list O(n²) in the row count.
        let maxValue = rows.map { $0.value(for: viewModel.metric) }.max() ?? 0
        // Share of the window total for the selected metric — so switching to
        // units reports unit shares, not the revenue-based sharePct.
        let shares = metricShares(rows, by: viewModel.metric)
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Breakdown")
                    .font(.caption).textCase(.uppercase)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    viewModel.sortDirection = viewModel.sortDirection.toggled
                } label: {
                    HStack(spacing: 3) {
                        Image(systemName: viewModel.sortDirection == .descending
                              ? "arrow.down" : "arrow.up")
                        Text(viewModel.sortDirection == .descending
                             ? "Highest first" : "Lowest first")
                    }
                    .font(.caption2)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }

            Picker("", selection: $viewModel.dimension) {
                Text("Products").tag(AnalyticsDimension.product)
                Text("Categories").tag(AnalyticsDimension.category)
                Text("Machines").tag(AnalyticsDimension.machine)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            AnalyticsMetricPicker(metric: $viewModel.metric)

            if viewModel.isLoadingRows && rows.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
            } else if rows.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 24)
            } else {
                LazyVStack(spacing: 2) {
                    ForEach(rows) { row in
                        Button {
                            guard viewModel.dimension == .product, row.key != nil else { return }
                            onSelectProduct(row)
                        } label: {
                            rowView(row, maxValue: maxValue, share: shares[row.id])
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.dimension != .product || row.key == nil)
                    }
                }
            }
        }
        .padding(14)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.regularMaterial)
        }
    }

    private func rowView(_ row: AnalyticsBreakdownRow, maxValue: Double,
                         share: Double?) -> some View {
        let value = row.value(for: viewModel.metric)
        let delta = deltaPct(current: value, previous: row.previousValue(for: viewModel.metric))
        return HStack(spacing: 9) {
            if viewModel.dimension == .product {
                ProductImage(imagePath: row.imagePath, size: 30)
            }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 5) {
                    if viewModel.dimension == .product {
                        Text(row.abcClass)
                            .font(.system(size: 9, weight: .bold))
                            .padding(.horizontal, 4).padding(.vertical, 1)
                            .background(abcColor(row.abcClass).opacity(0.18))
                            .foregroundStyle(abcColor(row.abcClass))
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                    Text(row.label).font(.subheadline.weight(.medium)).lineLimit(1)
                }
                Text(subtitle(row, share: share))
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 1) {
                Text(format(value))
                    .font(.subheadline.weight(.semibold)).monospacedDigit()
                if let delta {
                    Text(String(format: "%+.0f %%", delta))
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(delta >= 0 ? .green : .red)
                }
            }
        }
        .padding(.horizontal, 7).padding(.vertical, 7)
        .background(alignment: .leading) {
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 7)
                    .fill(Color.accentColor.opacity(0.10))
                    .frame(width: maxValue > 0 ? geo.size.width * value / maxValue : 0)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 7))
        .contentShape(Rectangle())
    }

    private func subtitle(_ row: AnalyticsBreakdownRow, share: Double?) -> String {
        var parts: [String] = []
        if let share { parts.append(String(format: "%.1f %%", share)) }
        parts.append(String(format: String(localized: "%@ /day"), format(row.avgDaily(for: viewModel.metric))))
        if viewModel.metric != .units {
            parts.append(String(localized: "\(row.units) pc"))
        }
        if !row.hasCost && viewModel.metric == .grossProfit {
            parts.append(String(localized: "no purchase price"))
        }
        switch viewModel.dimension {
        case .product where row.machineCount > 0:
            parts.append(String(localized: "\(row.machineCount) machine"))
        case .category, .machine:
            if row.productCount > 0 {
                parts.append(String(localized: "\(row.productCount) product"))
            }
        default: break
        }
        return parts.joined(separator: " · ")
    }

    private func format(_ value: Double) -> String {
        switch viewModel.metric {
        case .units: return value < 10 ? String(format: "%.1f", value) : String(Int(value.rounded()))
        case .revenue, .grossProfit: return String(format: "%.2f €", value)
        }
    }

    private func abcColor(_ cls: String) -> Color {
        switch cls {
        case "A": return .green
        case "B": return .orange
        default: return .red
        }
    }
}
