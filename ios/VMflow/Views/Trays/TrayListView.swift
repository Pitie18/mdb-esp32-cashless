import SwiftUI

/// List of trays for a machine with stock bars, product info, and management actions.
struct TrayListView: View {
    let machineId: UUID
    let trays: [Tray]
    let products: [Product]
    let onRefresh: () async -> Void

    @StateObject private var viewModel: TrayViewModel
    @State private var showAddSheet = false
    @State private var showBatchSheet = false
    @State private var editingTray: Tray?

    init(machineId: UUID, trays: [Tray], products: [Product], onRefresh: @escaping () async -> Void) {
        self.machineId = machineId
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
                    LazyVStack(spacing: 0) {
                        ForEach(displayTrays) { tray in
                            TrayRow(
                                tray: tray,
                                onAdjust: { delta in
                                    HapticFeedback.light.fire()
                                    Task {
                                        await viewModel.adjustStock(tray: tray, delta: delta)
                                    }
                                },
                                onFill: {
                                    HapticFeedback.medium.fire()
                                    Task {
                                        await viewModel.fillToCapacity(tray)
                                    }
                                },
                                onEdit: {
                                    editingTray = tray
                                }
                            )
                            .padding(.horizontal)

                            if tray.id != displayTrays.last?.id {
                                Divider()
                                    .padding(.leading, 52)
                            }
                        }
                    }
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

#Preview {
    NavigationStack {
        TrayListView(machineId: UUID(), trays: [], products: []) {}
    }
}
