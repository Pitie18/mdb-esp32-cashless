import SwiftUI

/// Horizontal progress bar showing stock level with color coding.
/// Green (> 50%), Yellow (20-50%), Red (< 20%) — unless `tint` overrides it
/// (the machine detail list colours a slot by its product's status).
struct StockBar: View {
    let current: Int
    let capacity: Int
    var showLabel: Bool = true
    var height: CGFloat = 8
    /// Optional min_stock threshold marker (amber line).
    var minStock: Int? = nil
    /// Optional fill_when_below threshold marker (blue line).
    var fillWhenBelow: Int? = nil
    /// Fixed bar colour instead of the ratio-based one.
    var tint: Color? = nil

    private var ratio: Double {
        guard capacity > 0 else { return 0 }
        return Double(current) / Double(capacity)
    }

    private var color: Color {
        if let tint { return tint }
        if ratio > 0.5 { return .green }
        if ratio > 0.2 { return .yellow }
        return .red
    }

    private func markerPercent(_ value: Int) -> Double? {
        guard capacity > 0, value > 0, value < capacity else { return nil }
        return Double(value) / Double(capacity)
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    // Background track
                    Capsule()
                        .fill(Color(.systemGray5))
                        .frame(height: height)

                    // Fill bar
                    Capsule()
                        .fill(color)
                        .frame(width: max(0, geo.size.width * ratio), height: height)
                        .animation(.spring(duration: 0.4), value: ratio)

                    // Min stock marker (amber)
                    if let pct = markerPercent(minStock ?? 0) {
                        RoundedRectangle(cornerRadius: 0.5)
                            .fill(Color.orange)
                            .frame(width: 2, height: height)
                            .offset(x: geo.size.width * pct - 1)
                    }

                    // Fill when below marker (blue)
                    if let pct = markerPercent(fillWhenBelow ?? 0) {
                        RoundedRectangle(cornerRadius: 0.5)
                            .fill(Color.blue)
                            .frame(width: 2, height: height)
                            .offset(x: geo.size.width * pct - 1)
                    }
                }
            }
            .frame(height: height)

            if showLabel {
                Text("\(current)/\(capacity)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }
}

/// Compact stock indicator for use in cards/lists.
struct StockHealthIndicator: View {
    let health: StockHealth

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: iconName)
                .font(.caption)
            Text(localizedLabel)
                .font(.caption.weight(.medium))
        }
        .foregroundStyle(color)
    }

    private var localizedLabel: LocalizedStringKey {
        switch health {
        case .ok: return "Ok"
        case .fill: return "Top off"
        case .low: return "Low"
        case .critical: return "Critical"
        }
    }

    private var color: Color {
        switch health {
        case .ok: return .green
        case .fill: return .blue
        case .low: return .yellow
        case .critical: return .red
        }
    }

    private var iconName: String {
        switch health {
        case .ok: return "checkmark.circle.fill"
        case .fill: return "arrow.up.circle.fill"
        case .low: return "exclamationmark.triangle.fill"
        case .critical: return "xmark.octagon.fill"
        }
    }
}

#Preview {
    VStack(spacing: 20) {
        StockBar(current: 8, capacity: 10)
        StockBar(current: 3, capacity: 10)
        StockBar(current: 1, capacity: 10)
        StockBar(current: 0, capacity: 10)

        HStack(spacing: 16) {
            StockHealthIndicator(health: .ok)
            StockHealthIndicator(health: .fill)
            StockHealthIndicator(health: .low)
            StockHealthIndicator(health: .critical)
        }
    }
    .padding()
}
