import SwiftUI

/// Single tray row showing slot, product image, name, stock bar, and quick actions.
///
/// With `groupInfo` set, the slot is judged by its **product** (all slots of
/// the product summed, see `MachineStockHealth.trayGroupIndex`): colours and
/// highlighting follow the product, and an empty slot of a product that is
/// still stocked elsewhere gets a hint instead of a red alarm. Without it the
/// slot is unassigned and shown muted.
struct TrayRow: View {
    let tray: Tray
    var groupInfo: TrayGroupInfo<Tray>? = nil
    /// Tint `fill` slots too — only when the machine has a product that is
    /// sold out/low anyway, mirroring the PWA (topping off is only worth it
    /// on a trip that happens regardless).
    var highlightFill: Bool = false
    /// Selected via the stock grid: gets an accent ring.
    var isSelected: Bool = false
    let onAdjust: (Int) -> Void
    let onFill: () -> Void
    let onEdit: () -> Void

    private var flag: TrayStockFlag { groupInfo?.flag ?? .ok }

    var body: some View {
        rowContent
            .padding(.horizontal, 8)
            .background {
                if let tint = highlightTint {
                    RoundedRectangle(cornerRadius: 10).fill(tint.opacity(0.10))
                }
            }
            .overlay {
                if isSelected {
                    RoundedRectangle(cornerRadius: 10).stroke(Color.accentColor, lineWidth: 2)
                } else if let tint = highlightTint {
                    RoundedRectangle(cornerRadius: 10).stroke(tint.opacity(0.45), lineWidth: 1)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { onEdit() }
            // Data-dependent UI-test anchor: only exists once trays have loaded.
            .accessibilityIdentifier("tray-row")
    }

    private var highlightTint: Color? {
        switch flag {
        case .low: return .orange
        case .fill: return highlightFill ? .blue : nil
        case .slotEmpty, .ok: return nil
        }
    }

    private var rowContent: some View {
        HStack(spacing: 12) {
            // Slot Number
            Text("\(tray.itemNumber)")
                .font(.caption.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(slotColor))

            // Product Image
            ProductImage(imagePath: tray.products?.imagePath, size: 40)

            // Name + Stock Bar
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(tray.productName)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)

                    if tray.isDiscontinued {
                        Text("DC")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(.orange.opacity(0.15)))
                    }
                }

                StockBar(
                    current: tray.currentStock,
                    capacity: tray.capacity,
                    showLabel: false,
                    height: 6,
                    minStock: tray.minStock,
                    fillWhenBelow: tray.fillWhenBelow,
                    tint: groupInfo.map { ProductStockStatus($0.group).color }
                )

                HStack {
                    Text("\(tray.currentStock)/\(tray.capacity)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                    if let price = tray.formattedSellprice {
                        Spacer()
                        Text(price)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }

                if flag == .slotEmpty {
                    Text("Slot empty, product in another slot")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 4)

            // Quick Actions
            HStack(spacing: 6) {
                // Minus
                Button {
                    onAdjust(-1)
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .disabled(tray.currentStock <= 0)

                // Plus
                Button {
                    onAdjust(1)
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.blue)
                }
                .buttonStyle(.plain)
                .disabled(tray.currentStock >= tray.capacity)
            }
        }
        .padding(.vertical, 8)
    }

    private var slotColor: Color {
        // No group: an unassigned slot — nothing to judge.
        guard let groupInfo else { return .gray }
        return groupInfo.flag == .slotEmpty ? .gray : ProductStockStatus(groupInfo.group).color
    }
}

// MARK: - Product status

/// Status of a product group as shown in the tray list and stock grid:
/// Sold out (red) / Low (orange) / Top off (blue) / OK (green). A group that
/// doesn't need a refill is OK, whatever its raw state (e.g. a `fill` group
/// that is already full). Mirrors the PWA's `ProductGroupHeader.vue`.
enum ProductStockStatus: Equatable {
    case soldOut, low, topOff, ok

    init<T>(_ group: ProductStockGroup<T>) {
        guard group.needsRefill else { self = .ok; return }
        switch group.state {
        case .critical: self = .soldOut
        case .low: self = .low
        case .fill, .ok: self = .topOff
        }
    }

    var color: Color {
        switch self {
        case .soldOut: return .red
        case .low: return .orange
        case .topOff: return .blue
        case .ok: return .green
        }
    }

    var label: String {
        switch self {
        case .soldOut: return String(localized: "Sold out")
        case .low: return String(localized: "Low")
        case .topOff: return String(localized: "Top off")
        case .ok: return String(localized: "OK")
        }
    }
}

// MARK: - Product group header

/// Summary line for a product that sits in two or more slots: summed stock,
/// missing units, empty slots, a status pill, and one bar segment per slot.
/// The slots follow as normal (indented) rows below it. Mirrors the PWA's
/// `ProductGroupHeader.vue`.
struct ProductGroupHeader: View {
    let group: ProductStockGroup<Tray>
    var isSelected: Bool = false

    private var status: ProductStockStatus { ProductStockStatus(group) }
    private var sortedTrays: [Tray] { group.trays.sorted { $0.itemNumber < $1.itemNumber } }
    private var first: Tray? { sortedTrays.first }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                ProductImage(imagePath: first?.products?.imagePath, size: 32)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(first?.products?.name ?? "—")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text("\(group.trays.count) slots")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    HStack(spacing: 10) {
                        (Text("\(group.currentStock)").fontWeight(.semibold).foregroundStyle(.primary)
                            + Text(verbatim: " / \(group.capacity)"))
                            .monospacedDigit()
                        if group.deficit > 0 {
                            Text("\(group.deficit) missing")
                                .monospacedDigit()
                        }
                        if group.emptySlots > 0 && !group.needsRefill {
                            Text("\(group.emptySlots) slots empty")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }

                Spacer(minLength: 4)

                Text(status.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(status.color)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(status.color.opacity(0.12)))
            }

            HStack(spacing: 2) {
                ForEach(sortedTrays) { tray in
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 2).fill(Color(.systemGray5))
                            RoundedRectangle(cornerRadius: 2)
                                .fill(status.color)
                                .frame(width: geo.size.width * min(1, tray.fillRatio))
                        }
                    }
                    .frame(height: 6)
                    .accessibilityHidden(true)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: 12).stroke(Color.accentColor, lineWidth: 2)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    let tray = Tray(
        id: UUID(),
        machineId: UUID(),
        itemNumber: 1,
        productId: nil,
        capacity: 10,
        currentStock: 3,
        minStock: 2,
        fillWhenBelow: 5,
        products: nil
    )

    List {
        TrayRow(tray: tray, onAdjust: { _ in }, onFill: {}, onEdit: {})
    }
}
