import SwiftUI

/// Packing step: a machine's change note ("Änderungsvermerk") — the slots the
/// office wants switched to another product or spiral. The refiller accepts
/// (default) or declines each slot; accepted slots leave the normal refill and
/// add their rebuild units to the packing list. Declined slots stay open for a
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
        let packLines = viewModel.rebuildPackLines(machineId: machine.id)

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

            if !packLines.isEmpty {
                Divider()
                Text("Pack for the rebuild")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                ForEach(packLines) { line in
                    packRow(line)
                }
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

                    if item.changesCapacity || item.changesPrice || !accepted {
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

    // MARK: - Pack Row

    private func packRow(_ line: RebuildPackLine) -> some View {
        HStack(spacing: 8) {
            ProductImage(imagePath: line.imagePath, size: 28)
            Text(line.name ?? "")
                .font(.subheadline)
                .lineLimit(1)
            Spacer(minLength: 4)
            if line.need > 0 {
                Text("\(line.packed)×")
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.blue)
            } else {
                Text("nothing, moves over")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if line.packed < line.need {
                Text("(\(line.need) needed)")
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.orange)
            }
        }
    }
}
