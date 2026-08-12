import SwiftUI

/// Three chips — time range, machines, categories — each opening its own sheet.
/// Selections are committed on dismiss rather than on every tap, so a
/// multi-select does not fire one RPC round trip per checkbox.
struct AnalyticsFilterBar: View {
    @ObservedObject var viewModel: AnalyticsViewModel

    @State private var showRange = false
    @State private var showMachines = false
    @State private var showCategories = false

    /// Selection as it was when the sheet opened. Dismissing without changing
    /// anything must not cost a round trip.
    @State private var machineSnapshot: Set<UUID> = []
    @State private var categorySnapshot: Set<UUID> = []

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(viewModel.rangeLabel, systemImage: "calendar", isActive: true) { showRange = true }
                chip(machineLabel, systemImage: "storefront",
                     isActive: !viewModel.selectedMachineIds.isEmpty) {
                    machineSnapshot = viewModel.selectedMachineIds
                    showMachines = true
                }
                chip(categoryLabel, systemImage: "square.grid.2x2",
                     isActive: !viewModel.selectedCategoryIds.isEmpty) {
                    categorySnapshot = viewModel.selectedCategoryIds
                    showCategories = true
                }
            }
            .padding(.horizontal, 2)
        }
        .sheet(isPresented: $showRange) {
            AnalyticsRangeSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $showMachines, onDismiss: {
            guard viewModel.selectedMachineIds != machineSnapshot else { return }
            viewModel.filtersCommitted()
        }) {
            AnalyticsMultiSelectSheet(
                title: String(localized: "Machines"),
                options: viewModel.machines.map { ($0.id, $0.name ?? String(localized: "Unnamed")) },
                selection: $viewModel.selectedMachineIds,
                allLabel: String(localized: "All machines"))
        }
        .sheet(isPresented: $showCategories, onDismiss: {
            guard viewModel.selectedCategoryIds != categorySnapshot else { return }
            viewModel.filtersCommitted()
        }) {
            AnalyticsMultiSelectSheet(
                title: String(localized: "Categories"),
                options: viewModel.categories.map { ($0.id, $0.name) },
                selection: $viewModel.selectedCategoryIds,
                allLabel: String(localized: "All categories"))
        }
    }

    private var machineLabel: String {
        let n = viewModel.selectedMachineIds.count
        if n == 0 { return String(localized: "All machines") }
        if n == 1, let id = viewModel.selectedMachineIds.first,
           let m = viewModel.machines.first(where: { $0.id == id }) {
            return m.name ?? String(localized: "Unnamed")
        }
        return String(localized: "\(n) machine")
    }

    private var categoryLabel: String {
        let n = viewModel.selectedCategoryIds.count
        if n == 0 { return String(localized: "All categories") }
        if n == 1, let id = viewModel.selectedCategoryIds.first,
           let c = viewModel.categories.first(where: { $0.id == id }) {
            return c.name
        }
        return String(localized: "\(n) category")
    }

    private func chip(_ text: String, systemImage: String, isActive: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage).font(.caption2)
                Text(text).font(.caption).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(isActive ? Color.accentColor.opacity(0.15) : Color(.secondarySystemBackground))
            .foregroundStyle(isActive ? Color.accentColor : Color.primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Preset list plus a custom two-date range.
private struct AnalyticsRangeSheet: View {
    @ObservedObject var viewModel: AnalyticsViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(AnalyticsRangePreset.allCases.filter { $0 != .custom }) { preset in
                        Button {
                            viewModel.preset = preset
                            dismiss()
                        } label: {
                            HStack {
                                Text(label(for: preset))
                                Spacer()
                                if viewModel.preset == preset {
                                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }

                Section(String(localized: "Custom range")) {
                    DatePicker(String(localized: "From"), selection: $viewModel.customFrom,
                               displayedComponents: .date)
                    DatePicker(String(localized: "To"), selection: $viewModel.customTo,
                               in: viewModel.customFrom..., displayedComponents: .date)
                    Button(String(localized: "Apply custom range")) {
                        viewModel.preset = .custom
                        dismiss()
                    }
                }
            }
            .navigationTitle(String(localized: "Time range"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func label(for preset: AnalyticsRangePreset) -> String {
        switch preset {
        case .days7: return String(localized: "Last 7 days")
        case .days30: return String(localized: "Last 30 days")
        case .days90: return String(localized: "Last 90 days")
        case .thisMonth: return String(localized: "This month")
        case .lastMonth: return String(localized: "Last month")
        case .custom: return String(localized: "Custom range")
        }
    }
}

/// Generic multi-select with an explicit "all" state (empty selection).
private struct AnalyticsMultiSelectSheet: View {
    let title: String
    let options: [(UUID, String)]
    @Binding var selection: Set<UUID>
    let allLabel: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button {
                    selection.removeAll()
                } label: {
                    HStack {
                        Text(allLabel)
                        Spacer()
                        if selection.isEmpty {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .foregroundStyle(.primary)

                ForEach(options, id: \.0) { id, name in
                    Button {
                        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
                    } label: {
                        HStack {
                            Text(name)
                            Spacer()
                            if selection.contains(id) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
