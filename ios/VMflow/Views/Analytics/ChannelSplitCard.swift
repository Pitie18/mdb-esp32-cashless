import SwiftUI

/// Cash vs. cashless for the selected window — relevant for reconciling the
/// cash book and for judging where card payment actually pays off.
struct ChannelSplitCard: View {
    let channels: [AnalyticsChannel]

    private var total: Double { channels.reduce(0) { $0 + $1.revenueGross } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Payment methods")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if channels.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
            } else {
                ForEach(channels) { channel in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(displayName(channel.channel)).font(.subheadline)
                            Spacer()
                            Text(String(format: "%.2f €", channel.revenueGross))
                                .font(.subheadline.weight(.semibold)).monospacedDigit()
                            Text(total > 0
                                 ? String(format: "%.0f %%", channel.revenueGross / total * 100)
                                 : "0 %")
                                .font(.caption).foregroundStyle(.secondary)
                                .frame(width: 42, alignment: .trailing)
                        }
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(color(for: channel.channel))
                                .frame(width: total > 0
                                       ? geo.size.width * channel.revenueGross / total : 0)
                        }
                        .frame(height: 6)
                        Text(String(localized: "\(channel.units) pc · ⌀ \(String(format: "%.2f €", channel.avgTicket))"))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private func displayName(_ raw: String) -> String {
        switch raw.lowercased() {
        case "cash": return String(localized: "Cash")
        case "cashless", "card": return String(localized: "Cashless")
        default: return String(localized: "Unknown")
        }
    }

    private func color(for raw: String) -> Color {
        switch raw.lowercased() {
        case "cash": return .green
        case "cashless", "card": return .blue
        default: return .gray
        }
    }
}
