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
                // The range always narrows the data, so it carries a permanent
                // tint rather than the "you have filtered" fill — otherwise
                // every chip would look set and the signal would say nothing.
                chip(viewModel.rangeLabel, systemImage: "calendar",
                     style: .always) { showRange = true }
                chip(machineLabel, systemImage: "storefront",
                     style: viewModel.selectedMachineIds.isEmpty ? .off : .on,
                     onClear: { viewModel.clearMachineFilter() }) {
                    machineSnapshot = viewModel.selectedMachineIds
                    showMachines = true
                }
                chip(categoryLabel, systemImage: "square.grid.2x2",
                     style: viewModel.selectedCategoryIds.isEmpty ? .off : .on,
                     onClear: { viewModel.clearCategoryFilter() }) {
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
        viewModel.activeMachineFilterLabel ?? String(localized: "All machines")
    }

    private var categoryLabel: String {
        viewModel.activeCategoryFilterLabel ?? String(localized: "All categories")
    }

    /// `off` — no restriction. `on` — the user narrowed the data here, drawn
    /// filled so it is unmistakable after a drill-down changed it without the
    /// user touching the bar. `always` — inherently set (the time range).
    private enum ChipStyle { case off, on, always }

    private func chip(_ text: String, systemImage: String, style: ChipStyle,
                      onClear: (() -> Void)? = nil,
                      action: @escaping () -> Void) -> some View {
        let background: Color = switch style {
        case .on: Color.accentColor
        case .always: Color.accentColor.opacity(0.15)
        case .off: Color(.secondarySystemBackground)
        }
        let foreground: Color = switch style {
        case .on: .white
        case .always: .accentColor
        case .off: .primary
        }
        return HStack(spacing: 5) {
            Button(action: action) {
                HStack(spacing: 5) {
                    Image(systemName: systemImage).font(.caption2)
                    Text(text).font(.caption.weight(style == .on ? .semibold : .regular)).lineLimit(1)
                    if style != .on {
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
                    }
                }
            }
            .buttonStyle(.plain)

            // Clearing an active filter without reopening the sheet — the
            // fastest way back out of a drill-down.
            if style == .on, let onClear {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 13))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 7)
        .background(background)
        .foregroundStyle(foreground)
        .clipShape(Capsule())
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
