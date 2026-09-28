import SwiftUI

/// List of trays for a machine with stock bars, product info, and management actions.
struct TrayListView: View {
    let machineId: UUID
    let trays: [Tray]
    let products: [Product]
    let onRefresh: () async -> Void
    var linkedSelections: Bool = false

    @StateObject private var viewModel: TrayViewModel
    @State private var showAddSheet = false
    @State private var showBatchSheet = false
    @State private var editingTray: Tray?

    init(machineId: UUID, trays: [Tray], products: [Product], linkedSelections: Bool = false, onRefresh: @escaping () async -> Void) {
        self.machineId = machineId
        self.linkedSelections = linkedSelections
        self.trays = trays
        self.products = products
        self.onRefresh = onRefresh
        _viewModel = StateObject(wrappedValue: TrayViewModel(machineId: machineId))
    }

    /// Use the viewModel's trays if loaded, otherwise fall back to the passed-in trays.
    private var displayTrays: [Tray] {
        viewModel.trays.isEmpty ? trays : viewModel.trays
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Header with Add buttons
                HStack {
                    Text("\(displayTrays.count) Trays")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Spacer()

                    Menu {
                        Button {
                            showAddSheet = true
                        } label: {
                            Label("Add Single Tray", systemImage: "plus")
                        }
                        Button {
                            showBatchSheet = true
                        } label: {
                            Label("Batch Add Trays", systemImage: "plus.rectangle.on.rectangle")
                        }
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.title3)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)

                // Tray Rows
                if displayTrays.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "tray")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary)
                        Text("No trays configured")
                            .foregroundStyle(.secondary)
                        Button("Add Trays") {
                            showBatchSheet = true
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    }
                    .padding(.top, 40)
                } else {
                    TrayStockSection(
                        trays: displayTrays,
                        onAdjust: { tray, delta in
                            HapticFeedback.light.fire()
                            Task {
                                await viewModel.adjustStock(tray: tray, delta: delta)
                            }
                        },
                        onFill: { tray in
                            HapticFeedback.medium.fire()
                            Task {
                                await viewModel.fillToCapacity(tray)
                            }
                        },
                        onEdit: { tray in
                            editingTray = tray
                        },
                        linkedSelections: linkedSelections
                    )
                    .padding(.horizontal)
                }
            }
            .padding(.bottom, 20)
        }
        .dataRefreshable {
            await viewModel.loadTrays()
            await onRefresh()
        }
        .task {
            await viewModel.loadTrays()
        }
        .sheet(isPresented: $showAddSheet) {
            TrayEditSheet(
                machineId: machineId,
                tray: nil,
                products: products,
                onSave: { slot, productId, capacity, stock, minStock, fillBelow in
                    await viewModel.addTray(
                        itemNumber: slot,
                        productId: productId,
                        capacity: capacity,
                        currentStock: stock,
                        minStock: minStock,
                        fillWhenBelow: fillBelow
                    )
                }
            )
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showBatchSheet) {
            BatchAddTraySheet(machineId: machineId) { start, count, capacity in
                await viewModel.batchAddTrays(startSlot: start, count: count, capacity: capacity)
            }
            .presentationDetents([.medium])
        }
        .sheet(item: $editingTray) { tray in
            TrayEditSheet(
                machineId: machineId,
                tray: tray,
                products: products,
                onSave: { slot, productId, capacity, stock, minStock, fillBelow in
                    await viewModel.updateTray(
                        id: tray.id,
                        itemNumber: slot,
                        productId: productId,
                        capacity: capacity,
                        currentStock: stock,
                        minStock: minStock,
                        fillWhenBelow: fillBelow
                    )
                },
                onDelete: {
                    await viewModel.deleteTray(tray)
                }
            )
            .presentationDetents([.large])
        }
        .alert("Error", isPresented: .init(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.error = nil } }
        )) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
    }
}

// MARK: - Tray stock section

/// "By product" groups a product's slots under a summary header; "By slot"
/// is the plain slot-number order.
enum TrayListMode: Hashable {
    case byProduct
    case bySlot
}

/// Machine detail "trays & stock" body shared by `MachineDetailView` and
/// `TrayListView`: a compact stock map, the By product / By slot switch and
/// the tray rows. Every slot is judged by its **product** (all slots of the
/// product summed, `MachineStockHealth.trayGroupIndex`). Mirrors the PWA's
/// machine detail page (`trayView`, `TrayStockGrid`, `ProductGroupHeader`).
struct TrayStockSection: View {
    let trays: [Tray]
    let onAdjust: (Tray, Int) -> Void
    let onFill: (Tray) -> Void
    let onEdit: (Tray) -> Void
    /// `vendingMachine.linked_selections`: the machine vends from a sibling
    /// slot itself, so empty slots of a stocked product are not highlighted.
    var linkedSelections: Bool = false

    @State private var mode: TrayListMode = .byProduct
    @State private var selectedProductId: UUID?

    var body: some View {
        let index = MachineStockHealth.trayGroupIndex(trays, linkedSelections: linkedSelections)
        let rows = listRows(index: index)
        // Topping off (`fill`) is only highlighted when a product is sold
        // out/low anyway, like the PWA.
        let highlightFill = index.values.contains { $0.needsRefill && $0.group.state != .fill }

        VStack(spacing: 12) {
            stockMapCard(index: index)

            Picker(selection: $mode) {
                Text("By product").tag(TrayListMode.byProduct)
                Text("By slot").tag(TrayListMode.bySlot)
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)

            LazyVStack(spacing: 0) {
                ForEach(rows) { row in
                    VStack(spacing: 0) {
                        if let header = row.header {
                            ProductGroupHeader(group: header, isSelected: header.productId == selectedProductId)
                                .padding(.top, 8)
                                .padding(.bottom, 4)
                        }

                        HStack(spacing: 10) {
                            if row.inGroup {
                                RoundedRectangle(cornerRadius: 1.5)
                                    .fill(Color.accentColor.opacity(0.3))
                                    .frame(width: 3)
                                    .padding(.vertical, 4)
                            }
                            TrayRow(
                                tray: row.tray,
                                groupInfo: index[row.tray.id],
                                highlightFill: highlightFill,
                                isSelected: selectedProductId != nil && row.tray.productId == selectedProductId,
                                onAdjust: { delta in onAdjust(row.tray, delta) },
                                onFill: { onFill(row.tray) },
                                onEdit: { onEdit(row.tray) }
                            )
                        }
                        .padding(.leading, row.inGroup ? 6 : 0)

                        if row.id != rows.last?.id {
                            Divider()
                                .padding(.leading, row.inGroup ? 79 : 60)
                        }
                    }
                }
            }
        }
        .onChange(of: trays) { _, newTrays in
            if let id = selectedProductId, !newTrays.contains(where: { $0.productId == id }) {
                selectedProductId = nil
            }
        }
    }

    private func listRows(index: [UUID: TrayGroupInfo<Tray>]) -> [ProductListRow<Tray>] {
        switch mode {
        case .byProduct:
            return MachineStockHealth.productListRows(trays, index: index)
        case .bySlot:
            return trays
                .sorted { $0.itemNumber < $1.itemNumber }
                .map { ProductListRow(tray: $0, header: nil, inGroup: false) }
        }
    }

    private func stockMapCard(index: [UUID: TrayGroupInfo<Tray>]) -> some View {
        let selectedGroup = selectedProductId.flatMap { id in
            trays.first { $0.productId == id }.flatMap { index[$0.id]?.group }
        }

        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Layout")
                    .font(.subheadline.weight(.medium))
                Spacer()
                if selectedGroup != nil {
                    Button("Clear Selection") {
                        selectedProductId = nil
                    }
                    .font(.caption)
                }
            }

            if let group = selectedGroup {
                (Text(verbatim: "\(group.trays.first?.products?.name ?? "—") · ")
                    + Text("\(group.trays.count) slots")
                    + Text(verbatim: " · \(group.currentStock)/\(group.capacity)"))
                    .font(.caption.weight(.medium))
                    .monospacedDigit()
                    .lineLimit(1)
            }

            TrayStockGrid(
                trays: trays,
                index: index,
                selectedProductId: selectedProductId,
                onSelect: { id in
                    HapticFeedback.light.fire()
                    selectedProductId = id
                }
            )

            FlowLayout(spacing: 10) {
                ForEach(TrayStockGridStyle.legend.filter { !linkedSelections || $0 != .slotEmpty }, id: \.self) { style in
                    HStack(spacing: 4) {
                        TrayStockGridSwatch(style: style)
                        Text(style.legendLabel)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(.regularMaterial))
    }
}

// MARK: - Stock grid

/// Visual state of one stock-grid cell — its product's status, not its own.
enum TrayStockGridStyle: Hashable {
    case unassigned, soldOut, low, topOff, slotEmpty, ok

    /// Legend order, as in the PWA.
    static let legend: [TrayStockGridStyle] = [.soldOut, .low, .topOff, .slotEmpty, .ok]

    init(info: TrayGroupInfo<Tray>?) {
        guard let info else { self = .unassigned; return }
        if info.needsRefill {
            switch ProductStockStatus(info.group) {
            case .soldOut: self = .soldOut
            case .low: self = .low
            case .topOff, .ok: self = .topOff
            }
        } else {
            self = info.flag == .slotEmpty ? .slotEmpty : .ok
        }
    }

    var stroke: Color {
        switch self {
        case .unassigned: return Color.secondary.opacity(0.3)
        case .soldOut: return Color.red.opacity(0.8)
        case .low: return Color.orange.opacity(0.8)
        case .topOff: return Color.blue.opacity(0.7)
        case .slotEmpty: return Color.secondary.opacity(0.6)
        case .ok: return Color.green.opacity(0.4)
        }
    }

    var fill: Color {
        switch self {
        case .unassigned, .slotEmpty: return .clear
        case .soldOut: return Color.red.opacity(0.15)
        case .low: return Color.orange.opacity(0.15)
        case .topOff: return Color.blue.opacity(0.10)
        case .ok: return Color.green.opacity(0.10)
        }
    }

    var dashed: Bool { self == .unassigned || self == .slotEmpty }

    var legendLabel: String {
        switch self {
        case .soldOut: return ProductStockStatus.soldOut.label
        case .low: return ProductStockStatus.low.label
        case .topOff: return ProductStockStatus.topOff.label
        case .ok: return ProductStockStatus.ok.label
        case .slotEmpty: return String(localized: "Slot empty, product in another slot")
        case .unassigned: return ""
        }
    }
}

/// Small legend swatch matching a grid cell's look.
struct TrayStockGridSwatch: View {
    let style: TrayStockGridStyle

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(style.fill)
            .overlay {
                RoundedRectangle(cornerRadius: 2)
                    .strokeBorder(style.stroke, style: StrokeStyle(lineWidth: 1.5, dash: style.dashed ? [2, 1.5] : []))
            }
            .frame(width: 10, height: 10)
    }
}

/// Compact stock map of the machine, laid out like the Analysis grid (10
/// columns, `slotRowCol` / `computeSlotWidths`). A cell's colour is its
/// product's status, so all slots of a product light up together; an empty
/// slot of an otherwise stocked product only gets a dashed outline. Tapping a
/// cell selects its product (every slot holding it gets a ring, the rest
/// dims); tapping it again, or an unassigned slot, clears the selection.
///
/// Separate from `MachineLayoutGrid` (the refill wizard's image-based target
/// picker) — same layout math, different job.
struct TrayStockGrid: View {
    let trays: [Tray]
    let index: [UUID: TrayGroupInfo<Tray>]
    let selectedProductId: UUID?
    let onSelect: (UUID?) -> Void

    private let cellHeight: CGFloat = 40
    private let spacing: CGFloat = 4
    private let columns = 10

    private struct Cell: Identifiable {
        let tray: Tray
        let row: Int
        let column: Int
        let width: Int
        var id: UUID { tray.id }
    }

    private var cells: [Cell] {
        let widths = computeSlotWidths(trays.map(\.itemNumber))
        return trays.map { tray in
            let (row, column) = slotRowCol(tray.itemNumber)
            return Cell(tray: tray, row: row, column: column, width: widths[tray.itemNumber] ?? 1)
        }
    }

    var body: some View {
        let cells = self.cells
        let rowCount = (cells.map(\.row).max() ?? -1) + 1

        GeometryReader { geo in
            let cellWidth = max(0, (geo.size.width - CGFloat(columns - 1) * spacing) / CGFloat(columns))
            ZStack(alignment: .topLeading) {
                ForEach(cells) { cell in
                    Button {
                        toggle(cell.tray.productId)
                    } label: {
                        TrayStockGridCell(
                            tray: cell.tray,
                            style: TrayStockGridStyle(info: index[cell.tray.id]),
                            isSelected: selectedProductId != nil && cell.tray.productId == selectedProductId,
                            isDimmed: selectedProductId != nil && cell.tray.productId != selectedProductId
                        )
                        .frame(
                            width: cellWidth * CGFloat(cell.width) + spacing * CGFloat(cell.width - 1),
                            height: cellHeight
                        )
                    }
                    .buttonStyle(.plain)
                    .offset(
                        x: CGFloat(cell.column) * (cellWidth + spacing),
                        y: CGFloat(cell.row) * (cellHeight + spacing)
                    )
                }
            }
        }
        .frame(height: rowCount > 0 ? CGFloat(rowCount) * cellHeight + CGFloat(rowCount - 1) * spacing : 0)
    }

    private func toggle(_ productId: UUID?) {
        onSelect(productId != nil && productId != selectedProductId ? productId : nil)
    }
}

private struct TrayStockGridCell: View {
    let tray: Tray
    let style: TrayStockGridStyle
    let isSelected: Bool
    let isDimmed: Bool

    var body: some View {
        VStack(spacing: 1) {
            Text(verbatim: "\(tray.itemNumber)")
                .font(.system(size: 10, weight: .semibold).monospacedDigit())
                .foregroundStyle(style == .unassigned ? .secondary : .primary)
            if tray.productId != nil {
                Text(verbatim: "\(tray.currentStock)/\(tray.capacity)")
                    .font(.system(size: 9).monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .padding(.horizontal, 1)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: 6).fill(style.fill))
        .overlay {
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(style.stroke, style: StrokeStyle(lineWidth: 2, dash: style.dashed ? [3, 2] : []))
        }
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.accentColor, lineWidth: 2)
                    .padding(-2)
            }
        }
        .opacity(isDimmed ? 0.35 : 1)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var accessibilityText: String {
        let slot = String(localized: "Slot \(tray.itemNumber)")
        guard let name = tray.products?.name else { return slot }
        return "\(slot), \(name), \(tray.currentStock)/\(tray.capacity)"
    }
}

#Preview {
    NavigationStack {
        TrayListView(machineId: UUID(), trays: [], products: []) {}
    }
}
