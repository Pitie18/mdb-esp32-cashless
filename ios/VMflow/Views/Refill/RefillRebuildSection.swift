import SwiftUI

/// Refill step, at the machine: rebuild the slots of the change note. The
/// refiller counts what comes out (sales since packing are already in the live
/// stock), fills the new product, sets the price and marks each slot rebuilt or
/// not. Nothing is booked until the machine is confirmed (`apply_slot_change`).
/// What is left over rides along in the van and is booked back at the end of
/// the tour, on the summary step. Mirrors the PWA's `RefillRebuildSection.vue`.
struct RefillRebuildSection: View {
    @ObservedObject var viewModel: RefillWizardViewModel
    let machineId: UUID

    private var isPrepared: Bool { viewModel.currentRebuildMachineId == machineId }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Rebuild slots", systemImage: "arrow.left.arrow.right")
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.orange)
                .padding(.horizontal, 4)

            if !isPrepared {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .padding(.vertical, 12)
            } else {
                ForEach(viewModel.currentRebuild) { slot in
                    slotCard(slot)
                }
                // Without a warehouse nothing was deducted, so nothing is
                // booked back.
                if viewModel.selectedWarehouseId != nil {
                    Label("Take removed goods with you – they are booked back at the end of the tour.", systemImage: "shippingbox")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                }
            }
        }
    }

    // MARK: - Slot Card

    private func slotCard(_ slot: RebuildSlot) -> some View {
        let item = slot.item
        let emptyLabel = String(localized: "Empty")
        let borderColor: Color = {
            switch slot.action {
            case .done: return .green.opacity(0.6)
            case .skip: return Color(.systemGray4)
            case nil: return .orange.opacity(0.5)
            }
        }()

        return VStack(alignment: .leading, spacing: 12) {
            // Slot: old → new
            HStack(alignment: .center, spacing: 10) {
                Text(verbatim: "#\(item.itemNumber)")
                    .font(.caption.bold())
                    .monospacedDigit()
                    .frame(minWidth: 36, minHeight: 28)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Color(.systemGray5)))

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        if item.fromProductId != nil {
                            ProductImage(imagePath: item.fromImagePath, size: 28)
                        }
                        Text(item.fromName ?? emptyLabel)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.turn.down.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if item.toProductId != nil {
                            ProductImage(imagePath: item.toImagePath, size: 28)
                        }
                        Text(item.toName ?? emptyLabel)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }

            // Live stock + spiral change
            VStack(alignment: .leading, spacing: 2) {
                Text("In the slot now: \(slot.liveStock) of \(item.fromCapacity) (sales since packing included)")
                    .foregroundStyle(.secondary)
                if item.changesCapacity {
                    Text("Change spiral (\(item.fromCapacity) → \(item.toCapacity))")
                        .foregroundStyle(.orange)
                }
            }
            .font(.caption)
            .monospacedDigit()
            .fixedSize(horizontal: false, vertical: true)

            if slot.action != .skip {
                // Taken out / filled in
                HStack(alignment: .top, spacing: 12) {
                    countField(
                        title: Text("Taken out"),
                        value: slot.removed,
                        maxValue: nil,
                        onChange: { viewModel.setRebuildRemoved(itemId: item.id, value: $0) }
                    )
                    if item.toProductId != nil {
                        VStack(alignment: .leading, spacing: 4) {
                            countField(
                                title: Text("Filled in (max \(item.toCapacity))"),
                                value: slot.filled,
                                maxValue: item.toCapacity,
                                onChange: { viewModel.setRebuildFilled(itemId: item.id, value: $0) }
                            )
                            Text("\(slot.moved) moved over · \(slot.fromVan) from the van")
                                .font(.caption2)
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // New price at the selection
                if item.changesPrice, let price = item.toPrice {
                    Button {
                        HapticFeedback.light.fire()
                        viewModel.setRebuildPriceSet(itemId: item.id, value: !slot.priceSet)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: slot.priceSet ? "checkmark.square.fill" : "square")
                                .font(.title3)
                                .foregroundStyle(slot.priceSet ? Color.blue : Color.secondary)
                            Text("Price at selection \(item.itemNumber) set to")
                                + Text(verbatim: " ")
                                + Text(verbatim: String(format: "%.2f \u{20AC}", price)).bold()
                            Spacer(minLength: 0)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.orange.opacity(0.1)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                // New age restriction at the selection
                if item.changesAge {
                    Button {
                        HapticFeedback.light.fire()
                        viewModel.setRebuildAgeSet(itemId: item.id, value: !slot.ageSet)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: slot.ageSet ? "checkmark.square.fill" : "square")
                                .font(.title3)
                                .foregroundStyle(slot.ageSet ? Color.blue : Color.secondary)
                            Text("Age setting changed on the machine")
                                + Text(verbatim: " ")
                                + Text(verbatim: item.ageChangeLabel).bold()
                            Spacer(minLength: 0)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.red.opacity(0.1)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            // Rebuilt / not rebuilt
            HStack(spacing: 8) {
                actionButton(
                    title: Text("Rebuilt"),
                    systemImage: "checkmark",
                    isSelected: slot.action == .done,
                    tint: .green,
                    isDisabled: slot.action != .done && slot.needsAgeConfirmation
                ) {
                    viewModel.setRebuildAction(itemId: item.id, action: slot.action == .done ? nil : .done)
                }
                actionButton(
                    title: Text("Not rebuilt"),
                    systemImage: "xmark",
                    isSelected: slot.action == .skip,
                    tint: .gray
                ) {
                    viewModel.setRebuildAction(itemId: item.id, action: slot.action == .skip ? nil : .skip)
                }
            }

            if slot.action == nil && slot.needsAgeConfirmation {
                Text("Confirm the age setting first")
                    .font(.caption)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if slot.action == .skip {
                Text("Stays as it is and comes up again on the next tour.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .background {
            RoundedRectangle(cornerRadius: 14)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.06), radius: 6, y: 2)
        }
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(borderColor, lineWidth: 2))
        .opacity(slot.action == .skip ? 0.75 : 1)
    }

    /// Number with large −/+ buttons (one-handed use, like the refill cards).
    private func countField(
        title: Text,
        value: Int,
        maxValue: Int?,
        onChange: @escaping (Int) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            title
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 6) {
                Button {
                    HapticFeedback.light.fire()
                    onChange(value - 1)
                } label: {
                    Image(systemName: "minus")
                        .font(.body.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(.fill.tertiary))
                }
                .buttonStyle(.plain)
                .disabled(value <= 0)

                Text(verbatim: "\(value)")
                    .font(.title3.bold())
                    .monospacedDigit()
                    .frame(minWidth: 36)

                Button {
                    HapticFeedback.light.fire()
                    onChange(value + 1)
                } label: {
                    Image(systemName: "plus")
                        .font(.body.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.blue.opacity(0.12)))
                        .foregroundStyle(.blue)
                }
                .buttonStyle(.plain)
                .disabled(maxValue.map { value >= $0 } ?? false)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func actionButton(
        title: Text,
        systemImage: String,
        isSelected: Bool,
        tint: Color,
        isDisabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            HapticFeedback.light.fire()
            action()
        } label: {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                title
            }
            .font(.subheadline.weight(.medium))
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(isSelected ? Color.white : Color.primary)
            .background(RoundedRectangle(cornerRadius: 10).fill(isSelected ? tint : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(isSelected ? tint : Color(.systemGray3), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.4 : 1)
    }
}
