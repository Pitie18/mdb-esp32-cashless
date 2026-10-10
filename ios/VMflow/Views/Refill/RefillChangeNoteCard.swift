import SwiftUI

/// Packing step: a machine's change note ("Änderungsvermerk") — the slots the
/// office wants switched to another product or spiral. The refiller accepts
/// (default) or declines each slot; accepted slots leave the normal refill and
/// their rebuild units join the normal packing list below, marked violet (a
/// "+N rebuild" badge or a row of their own). Declined slots stay open for a
/// later tour. Mirrors the PWA's `RefillChangeNote.vue`.
struct RefillChangeNoteCard: View {
    @ObservedObject var viewModel: RefillWizardViewModel
    let machine: RefillMachine
    /// Show the machine name — the "All" chip lists the notes of several machines.
    var showsMachineName: Bool = true

    private var items: [SlotChangeRequestItem] { machine.changeRequest?.items ?? [] }

    private var acceptedCount: Int {
        items.filter { viewModel.isRebuildAccepted($0.id) }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if showsMachineName {
                Text(machine.machine.displayName)
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 8) {
                Label("Change note", systemImage: "arrow.left.arrow.right")
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(.orange)
                Spacer()
                Text("\(acceptedCount) of \(items.count) accepted")
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }

            Text("Requested by the office. Untick a slot to leave it as it is this time; it stays open for a later tour.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 0) {
                ForEach(items) { item in
                    itemRow(item)
                }
            }

            if acceptedCount > 0 {
                Divider()
                Text("The goods for it are marked violet in the packing list.")
                    .font(.caption)
                    .foregroundStyle(Color.rebuildViolet)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.orange.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.orange.opacity(0.4), lineWidth: 1))
    }

    // MARK: - Slot Row

    private func itemRow(_ item: SlotChangeRequestItem) -> some View {
        let accepted = viewModel.isRebuildAccepted(item.id)
        let emptyLabel = String(localized: "Empty")

        return Button {
            HapticFeedback.light.fire()
            viewModel.toggleRebuildItem(machineId: machine.id, itemId: item.id)
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: accepted ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundStyle(accepted ? Color.blue : Color.secondary)

                Text(verbatim: "#\(item.itemNumber)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 30, alignment: .leading)
                    .padding(.top, 2)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(item.fromName ?? emptyLabel)
                            .foregroundStyle(.secondary)
                        Image(systemName: "arrow.right")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(item.toName ?? emptyLabel)
                            .fontWeight(.semibold)
                    }
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)

                    if item.changesCapacity || item.changesPrice || item.changesAge || !accepted {
                        HStack(spacing: 6) {
                            if item.changesCapacity {
                                Text("Change spiral (\(item.fromCapacity) → \(item.toCapacity))")
                                    .foregroundStyle(.orange)
                            }
                            if item.changesPrice, let price = item.toPrice {
                                Text("new price \(String(format: "%.2f \u{20AC}", price))")
                                    .foregroundStyle(.orange)
                                    .padding(.horizontal, 4)
                                    .background(RoundedRectangle(cornerRadius: 4).fill(Color.orange.opacity(0.15)))
                            }
                            if item.changesAge {
                                Text(verbatim: item.ageChangeLabel)
                                    .foregroundStyle(.red)
                                    .padding(.horizontal, 4)
                                    .background(RoundedRectangle(cornerRadius: 4).fill(Color.red.opacity(0.15)))
                            }
                            if !accepted {
                                Text("not this tour")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .font(.caption2.monospacedDigit())
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .opacity(accepted ? 1 : 0.5)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Age Restriction Labels

extension SlotChangeRequestItem {
    /// "18+" / "no age limit" (de: "ab 18" / "ohne Altersgrenze").
    static func ageLabel(_ minAge: Int?) -> String {
        guard let minAge else { return String(localized: "no age limit") }
        return String(localized: "\(minAge)+")
    }

    /// The change spelled out, e.g. "no age limit → 18+".
    var ageChangeLabel: String {
        "\(Self.ageLabel(fromMinAge)) → \(Self.ageLabel(toMinAge))"
    }
}

// MARK: - Rebuild Accent

extension Color {
    /// Violet of slot rebuild goods in the packing list (text / icons) —
    /// the PWA's violet-700 in light mode, violet-300 in dark mode.
    static let rebuildViolet = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.769, green: 0.710, blue: 0.992, alpha: 1)
            : UIColor(red: 0.427, green: 0.157, blue: 0.851, alpha: 1)
    })
    /// Violet-500, for tinted backgrounds and borders (use with opacity).
    static let rebuildVioletFill = Color(red: 0.545, green: 0.361, blue: 0.965)
}
