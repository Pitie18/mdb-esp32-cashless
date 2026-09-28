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
/// `TrayListView`: the By product / By slot switch and the tray rows. Every
/// slot is judged by its **product** (all slots of the product summed,
/// `MachineStockHealth.trayGroupIndex`). Mirrors the PWA's machine detail
/// page (`trayView`, `ProductGroupHeader`); the PWA's stock map is
/// deliberately not ported.
struct TrayStockSection: View {
    let trays: [Tray]
    let onAdjust: (Tray, Int) -> Void
    let onFill: (Tray) -> Void
    let onEdit: (Tray) -> Void
    /// `vendingMachine.linked_selections`: the machine vends from a sibling
    /// slot itself, so empty slots of a stocked product are not highlighted.
    var linkedSelections: Bool = false

    @State private var mode: TrayListMode = .byProduct

    var body: some View {
        let index = MachineStockHealth.trayGroupIndex(trays, linkedSelections: linkedSelections)
        let rows = listRows(index: index)
        // Topping off (`fill`) is only highlighted when a product is sold
        // out/low anyway, like the PWA.
        let highlightFill = index.values.contains { $0.needsRefill && $0.group.state != .fill }

        VStack(spacing: 12) {
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
                            ProductGroupHeader(group: header)
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
}

#Preview {
    NavigationStack {
        TrayListView(machineId: UUID(), trays: [], products: []) {}
    }
}
