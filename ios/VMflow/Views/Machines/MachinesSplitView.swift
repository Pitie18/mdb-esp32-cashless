import SwiftUI

/// Two-column machine view for iPad: scrollable card list on the left, detail on the right.
/// Avoids nested NavigationSplitView — uses a plain HStack layout instead.
struct MachinesSplitView: View {
    @StateObject private var viewModel = MachineListViewModel()
    @EnvironmentObject private var realtime: RealtimeService
    @State private var selectedMachineId: UUID?

    private var realtimeVersion: Int {
        realtime.salesVersion + realtime.traysVersion + realtime.machinesVersion + realtime.embeddedVersion
    }

    private var selectedStats: MachineStats? {
        guard let id = selectedMachineId else { return nil }
        return viewModel.filteredMachines.first { $0.id == id }
    }

    var body: some View {
        HStack(spacing: 0) {
            // Left column: machine list
            machineList
                .frame(width: 340)

            Divider()

            // Right column: detail or placeholder
            detailColumn
                .frame(maxWidth: .infinity)
        }
        .navigationTitle("Machines")
        .task {
            await viewModel.loadMachines()
        }
        .onChange(of: realtimeVersion) { _, _ in
            Task { await viewModel.loadMachines() }
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

    // MARK: - Machine List (Left Column)

    private var machineList: some View {
        ScrollView {
            if viewModel.filteredMachines.isEmpty && !viewModel.isLoading {
                ContentUnavailableView(
                    "No Machines",
                    systemImage: "vending.machine",
                    description: Text("Machines will appear here once registered.")
                )
                .padding(.top, 60)
            } else {
                LazyVStack(spacing: 10) {
                    ForEach(viewModel.filteredMachines) { stats in
                        Button {
                            selectedMachineId = stats.id
                        } label: {
                            MachineCard(stats: stats)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(selectedMachineId == stats.id ? Color.accentColor : .clear, lineWidth: 2.5)
                                )
                        }
                        .buttonStyle(.plain)
                        // Same anchor MachineListView uses on iPhone, so the
                        // screenshot UITest taps the first machine the same way
                        // in both layouts.
                        .accessibilityIdentifier("machine-cell")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
        }
        .searchable(text: $viewModel.searchText, prompt: "Search machines")
        .refreshable {
            await viewModel.loadMachines()
        }
        .overlay {
            if viewModel.isLoading && viewModel.machines.isEmpty {
                ProgressView("Loading machines...")
            }
        }
    }

    // MARK: - Detail (Right Column)

    @ViewBuilder
    private var detailColumn: some View {
        if let stats = selectedStats {
            NavigationStack {
                MachineDetailView(machine: stats.machine, initialStats: stats)
            }
            .id(stats.id) // Force recreation when selection changes
        } else {
            ContentUnavailableView(
                "Select a Machine",
                systemImage: "storefront",
                description: Text("Choose a machine from the list to see details.")
            )
        }
    }
}
