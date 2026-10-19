import SwiftUI

/// Incoming › expected delivery: per line the delivered quantity (prefilled
/// with the ordered one), best-before date and batch, then "Book all"
/// (`receive_purchase_order`). "Already booked · mark as delivered" closes an
/// order whose goods were booked by hand, e.g. with an older app. Mirrors the
/// web's `WarehouseExpectedDeliveries.vue`.
struct DeliveryReceiveView: View {
    let order: PurchaseOrder
    @ObservedObject var viewModel: WarehouseViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var quantities: [UUID: String] = [:]
    @State private var expirations: [UUID: String] = [:]
    @State private var batches: [UUID: String] = [:]
    @State private var confirmClose = false

    private var busy: Bool { viewModel.busyOrderId == order.id }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 2) {
                    Text(order.supplierName ?? "—")
                        .font(.headline)
                    Text(String(localized: "ordered \(order.createdAt.formatted(date: .abbreviated, time: .omitted)) · \(order.lines.count) items · \(order.totalUnits) units"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            ForEach(order.lines) { line in
                Section {
                    HStack {
                        Text(String(localized: "Delivered"))
                        Spacer()
                        TextField(String(line.quantity), text: binding(.quantity, line.id))
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .font(.body.monospacedDigit())
                            .frame(maxWidth: 100)
                            .foregroundStyle(quantityInvalid(line) ? Color.red : Color.primary)
                    }
                    ExpiryDateField(title: "Best before", text: binding(.expiration, line.id))
                    HStack {
                        Text("Batch No.")
                        Spacer()
                        TextField("Optional", text: binding(.batch, line.id))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 200)
                    }
                } header: {
                    HStack {
                        Text(line.productName ?? "—")
                        Spacer()
                        Text(String(localized: "ordered \(line.quantity)"))
                    }
                }
            }

            Section {
                Button {
                    Task {
                        if await viewModel.receiveOrder(order, lines: inputs) { dismiss() }
                    }
                } label: {
                    HStack {
                        Spacer()
                        if busy {
                            ProgressView().tint(.white)
                        } else {
                            Label(String(localized: "Book all"), systemImage: "tray.and.arrow.down.fill")
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy || !isValid)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }

            Section {
                Button(String(localized: "Already booked · mark as delivered")) {
                    confirmClose = true
                }
                .disabled(busy)
            } footer: {
                Text(String(localized: "For goods that were already booked by hand, e.g. with an older app. Books nothing."))
            }
        }
        .navigationTitle(String(localized: "Check delivery"))
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .onAppear(perform: prefill)
        .confirmationDialog(
            String(localized: "Mark the order as delivered without booking anything?"),
            isPresented: $confirmClose,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Already booked · mark as delivered")) {
                Task {
                    await viewModel.closeOrder(order)
                    dismiss()
                }
            }
        }
    }

    // MARK: - Input

    private func prefill() {
        for line in order.lines where quantities[line.id] == nil {
            quantities[line.id] = "\(line.quantity)"
        }
    }

    private enum Field { case quantity, expiration, batch }

    private func binding(_ field: Field, _ id: UUID) -> Binding<String> {
        switch field {
        case .quantity:
            return Binding(get: { quantities[id] ?? "" }, set: { quantities[id] = $0 })
        case .expiration:
            return Binding(get: { expirations[id] ?? "" }, set: { expirations[id] = $0 })
        case .batch:
            return Binding(get: { batches[id] ?? "" }, set: { batches[id] = $0 })
        }
    }

    private func quantity(_ line: PurchaseOrderLine) -> Int? {
        let text = (quantities[line.id] ?? "").trimmingCharacters(in: .whitespaces)
        guard let n = Int(text), n >= 0 else { return nil }
        return n
    }

    private func quantityInvalid(_ line: PurchaseOrderLine) -> Bool {
        quantity(line) == nil
    }

    /// `yyyy-MM-dd`, nil when empty; `.some(nil)` marks an invalid date.
    private func expiration(_ line: PurchaseOrderLine) -> String?? {
        let text = expirations[line.id] ?? ""
        guard !text.isEmpty else { return .some(nil) }
        guard let iso = LocaleDateMask(locale: locale).parseISO(text, notBefore: Date()) else { return nil }
        return .some(iso)
    }

    private var isValid: Bool {
        order.lines.allSatisfy { quantity($0) != nil && expiration($0) != nil }
            && order.lines.contains { (quantity($0) ?? 0) > 0 }
    }

    private var inputs: [ReceiveLineInput] {
        order.lines.map { line in
            ReceiveLineInput(
                lineId: line.id,
                quantity: quantity(line) ?? 0,
                expirationDate: expiration(line) ?? nil,
                batchNumber: batches[line.id]
            )
        }
    }
}
