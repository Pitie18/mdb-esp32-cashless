import SwiftUI

/// Operator inbox screen — mirrors the web `/inbox` page.
/// Customer-submitted problem reports, feedback and product wishes,
/// with mark-reviewed / dismiss / delete actions.
struct InboxView: View {
    @StateObject private var viewModel = InboxViewModel()
    @StateObject private var notificationService = NotificationService.shared

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.items.isEmpty {
                ProgressView("Loading inbox…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.filteredItems.isEmpty {
                emptyState
            } else {
                List {
                    Section {
                        ForEach(viewModel.filteredItems) { item in
                            InboxRow(
                                item: item,
                                isUpdating: viewModel.updatingId == item.id,
                                onMarkReviewed: { Task { await viewModel.markReviewed(item) } },
                                onDismiss: { Task { await viewModel.markDismissed(item) } },
                                onReopen: { Task { await viewModel.reopen(item) } },
                                onDelete: { Task { await viewModel.delete(item) } }
                            )
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        }
                    } header: {
                        filterPills
                            .padding(.bottom, 4)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("Inbox")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Picker("", selection: $viewModel.showOnlyOpen) {
                    Text("Open").tag(true)
                    Text("All").tag(false)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 160)
            }
        }
        .refreshable { await viewModel.load() }
        .task { await viewModel.load() }
        .alert("Error", isPresented: .init(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.error = nil } }
        )) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    // MARK: - Filter pills

    private var filterPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                pill(
                    label: "All",
                    count: viewModel.items.count,
                    isSelected: viewModel.kindFilter == nil,
                    color: .blue
                ) {
                    viewModel.kindFilter = nil
                }

                pill(
                    label: "Problem",
                    count: viewModel.openCount.problem,
                    isSelected: viewModel.kindFilter == .problem,
                    color: .red
                ) {
                    viewModel.kindFilter = .problem
                }

                pill(
                    label: "Feedback",
                    count: viewModel.openCount.feedback,
                    isSelected: viewModel.kindFilter == .feedback,
                    color: .blue
                ) {
                    viewModel.kindFilter = .feedback
                }

                pill(
                    label: "Wish",
                    count: viewModel.openCount.wish,
                    isSelected: viewModel.kindFilter == .wish,
                    color: .orange
                ) {
                    viewModel.kindFilter = .wish
                }
            }
            .padding(.vertical, 4)
        }
        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
    }

    private func pill(label: LocalizedStringKey, count: Int, isSelected: Bool, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(label)
                    .font(.callout.weight(.medium))
                if count > 0 {
                    Text("\(count)")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(isSelected ? .white.opacity(0.25) : color.opacity(0.2))
                        .foregroundStyle(isSelected ? .white : color)
                        .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(isSelected ? AnyShapeStyle(color) : AnyShapeStyle(Color(uiColor: .secondarySystemBackground)))
            .foregroundStyle(isSelected ? .white : .primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text(viewModel.showOnlyOpen ? "No open items" : "Inbox is empty")
                .font(.title3.weight(.semibold))
            Text("When customers report a problem, leave feedback or submit a product wish from a machine page, it will appear here.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Row

private struct InboxRow: View {
    let item: InboxItem
    let isUpdating: Bool
    let onMarkReviewed: () -> Void
    let onDismiss: () -> Void
    let onReopen: () -> Void
    let onDelete: () -> Void

    @State private var confirmDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                kindBadge
                Text(item.machineName ?? "Unknown machine")
                    .font(.subheadline.weight(.semibold))
                if !item.isOpen {
                    Text(item.status.rawValue.capitalized)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color(uiColor: .tertiarySystemBackground))
                        .clipShape(Capsule())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(item.createdAt, style: .relative)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Text(item.message)
                .font(.body)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            if let email = item.email {
                Link(destination: URL(string: "mailto:\(email)?subject=\(emailSubject)") ?? URL(string: "https://example.com")!) {
                    Label(email, systemImage: "envelope")
                        .font(.caption)
                }
            }
        }
        .opacity(item.isOpen ? 1.0 : 0.55)
        .swipeActions(edge: .trailing, allowsFullSwipe: item.isOpen) {
            if item.isOpen {
                Button(role: .destructive, action: onDismiss) {
                    Label("Dismiss", systemImage: "xmark.circle")
                }
                Button(action: onMarkReviewed) {
                    Label("Done", systemImage: "checkmark.circle")
                }
                .tint(.green)
            } else {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                Button(action: onReopen) {
                    Label("Reopen", systemImage: "arrow.uturn.left")
                }
                .tint(.blue)
            }
        }
        .disabled(isUpdating)
        .confirmationDialog(
            Text("Delete Notification", comment: "Confirmation dialog title before deleting an inbox notification"),
            isPresented: $confirmDelete,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Delete", comment: "Confirm inbox item deletion button"), role: .destructive, action: onDelete)
            Button(String(localized: "Cancel", comment: "Cancel inbox item deletion button"), role: .cancel) {}
        }
    }

    private var emailSubject: String {
        let raw = "Re: " + item.kind.rawValue.capitalized
        return raw.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? raw
    }

    private var kindBadge: some View {
        let (label, color): (LocalizedStringKey, Color) = {
            switch item.kind {
            case .problem:  return ("inbox_kind_problem", .red)
            case .feedback: return ("inbox_kind_feedback", .blue)
            case .wish:     return ("inbox_kind_wish", .orange)
            }
        }()

        return Text(label)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

#Preview {
    NavigationStack {
        InboxView()
    }
}
