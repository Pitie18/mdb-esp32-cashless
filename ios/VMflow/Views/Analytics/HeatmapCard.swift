import SwiftUI

/// Weekday x hour grid of sales volume. Answers "when is this actually bought",
/// which drives tour planning far more directly than a daily total does.
///
/// Hours are bucketed server-side in the caller's timezone; the cells arrive
/// sparse (only hours with sales) and are expanded into the full grid here.
struct HeatmapCard: View {
    let cells: [AnalyticsHeatCell]

    /// Hours are shown in 2-hour columns to stay readable on an iPhone.
    private let hourStep = 2

    private var byBucket: [Int: Int] {
        var result: [Int: Int] = [:]
        for cell in cells {
            let bucket = cell.dow * 100 + (cell.hour / hourStep) * hourStep
            result[bucket, default: 0] += cell.units
        }
        return result
    }

    private var maxUnits: Int { byBucket.values.max() ?? 0 }

    /// `Calendar.shortWeekdaySymbols` is Sunday-first; the RPC uses ISO
    /// weekdays (1 = Monday), so the symbols are rotated to match.
    private var weekdaySymbols: [String] {
        let symbols = Calendar.current.shortWeekdaySymbols
        guard symbols.count == 7 else { return symbols }
        return Array(symbols[1...6]) + [symbols[0]]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Peak hours")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if cells.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
            } else {
                VStack(spacing: 3) {
                    ForEach(1...7, id: \.self) { dow in
                        HStack(spacing: 3) {
                            Text(weekdaySymbols[dow - 1])
                                .font(.system(size: 9))
                                .foregroundStyle(.secondary)
                                .frame(width: 26, alignment: .trailing)
                            ForEach(Array(stride(from: 0, to: 24, by: hourStep)), id: \.self) { hour in
                                let units = byBucket[dow * 100 + hour] ?? 0
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color.accentColor.opacity(
                                        0.08 + heatIntensity(units: units, max: maxUnits) * 0.92))
                                    .frame(height: 13)
                            }
                        }
                    }
                    HStack(spacing: 3) {
                        Spacer().frame(width: 26)
                        ForEach(Array(stride(from: 0, to: 24, by: hourStep)), id: \.self) { hour in
                            Text(hour % 6 == 0 ? "\(hour)" : "")
                                .font(.system(size: 8))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }
}
