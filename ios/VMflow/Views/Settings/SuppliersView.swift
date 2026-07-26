import SwiftUI

/// Company-wide supplier management, reachable from the "More" tab: list, add,
/// edit (name + contact info), delete. Suppliers can also appear implicitly
/// from the purchase-price editor (see PurchasePricesSheet.SupplierPickerView)
/// or the warehouse intake supplier picker; this screen is the explicit way to
/// create one up front and to keep the list tidy afterwards.
struct SuppliersView: View {
    /// Drives the single edit sheet in both create and edit mode — two separate
    /// `.sheet` modifiers on one view are unreliable in SwiftUI.
    private enum ActiveSheet: Identifiable {
        case new
        case edit(Supplier)

        var id: String {
            switch self {
            case .new: return "new"
            case .edit(let supplier): return supplier.id.uuidString
            }
        }
    }

    @StateObject private var vm = SuppliersViewModel()
    @State private var activeSheet: ActiveSheet?
    @State private var supplierPendingDelete: Supplier?

    var body: some View {
        List {
            if vm.suppliers.isEmpty && !vm.isLoading {
                Text(String(localized: "No suppliers yet.")).foregroundStyle(.secondary)
            }
            ForEach(vm.suppliers) { supplier in
                Button {
                    activeSheet = .edit(supplier)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(supplier.name).foregroundStyle(.primary)
                        if let email = supplier.email, !email.isEmpty {
                            Text(email).font(.caption).foregroundStyle(.secondary)
                        } else if let phone = supplier.phone, !phone.isEmpty {
                            Text(phone).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .swipeActions(allowsFullSwipe: false) {
                    Button(role: .destructive) {
                        supplierPendingDelete = supplier
                    } label: {
                        Label(String(localized: "Delete"), systemImage: "trash")
                    }
                }
            }
        }
        .confirmationDialog(
            Text("Delete Supplier", comment: "Confirmation dialog title before deleting a supplier"),
            isPresented: Binding(
                get: { supplierPendingDelete != nil },
                set: { if !$0 { supplierPendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(String(localized: "Delete", comment: "Confirm supplier deletion button"), role: .destructive) {
                if let supplier = supplierPendingDelete {
                    Task { await vm.deleteSupplier(id: supplier.id) }
                }
                supplierPendingDelete = nil
            }
            Button(String(localized: "Cancel", comment: "Cancel supplier deletion button"), role: .cancel) {
                supplierPendingDelete = nil
            }
        } message: {
            if let supplier = supplierPendingDelete {
                Text(supplier.name)
            }
        }
        .navigationTitle(String(localized: "Suppliers"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    activeSheet = .new
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(String(localized: "New Supplier"))
            }
        }
        .task { await vm.loadSuppliers() }
        .refreshable { await vm.loadSuppliers() }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .new:
                SupplierEditSheet(supplier: nil) { name, email, phone, address, customerNumber in
                    await vm.createSupplier(name: name, email: email, phone: phone,
                                            address: address, customerNumber: customerNumber)
                    return takeError()
                }
            case .edit(let supplier):
                SupplierEditSheet(supplier: supplier) { name, email, phone, address, customerNumber in
                    var updated = supplier
                    updated.name = name
                    updated.email = email
                    updated.phone = phone
                    updated.address = address
                    updated.customerNumber = customerNumber
                    await vm.updateSupplier(updated)
                    return takeError()
                }
            }
        }
        .alert(
            String(localized: "Error"),
            isPresented: Binding(get: { vm.error != nil }, set: { if !$0 { vm.error = nil } })
        ) {
            Button(String(localized: "OK")) { vm.error = nil }
        } message: {
            Text(vm.error ?? "")
        }
    }

    /// Consumes `vm.error` and hands it to the edit sheet: an alert attached to
    /// this view can't be presented while the sheet is up, so save failures
    /// have to be shown from inside the sheet instead.
    private func takeError() -> String? {
        defer { vm.error = nil }
        return vm.error
    }
}

/// Create a supplier, or edit an existing one's name and contact/reference
/// info. `supplier == nil` means "create". `onSave` returns an error message
/// when the write failed (e.g. duplicate name); the sheet then stays open and
/// shows it, since the list's own alert can't surface from behind a sheet.
private struct SupplierEditSheet: View {
    let supplier: Supplier?
    let onSave: (_ name: String, _ email: String, _ phone: String,
                 _ address: String, _ customerNumber: String) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var email: String
    @State private var phone: String
    @State private var address: String
    @State private var customerNumber: String
    @State private var isSaving = false
    @State private var saveError: String?

    init(
        supplier: Supplier?,
        onSave: @escaping (_ name: String, _ email: String, _ phone: String,
                           _ address: String, _ customerNumber: String) async -> String?
    ) {
        self.supplier = supplier
        self.onSave = onSave
        _name = State(initialValue: supplier?.name ?? "")
        _email = State(initialValue: supplier?.email ?? "")
        _phone = State(initialValue: supplier?.phone ?? "")
        _address = State(initialValue: supplier?.address ?? "")
        _customerNumber = State(initialValue: supplier?.customerNumber ?? "")
    }

    private var canSave: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(String(localized: "Supplier"), text: $name)
                }
                Section {
                    TextField(String(localized: "Email"), text: $email)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField(String(localized: "Phone"), text: $phone)
                        .keyboardType(.phonePad)
                    TextField(String(localized: "Address"), text: $address, axis: .vertical)
                        .lineLimit(2...4)
                    TextField(String(localized: "Customer number"), text: $customerNumber)
                }
            }
            .navigationTitle(supplier == nil
                ? String(localized: "New Supplier")
                : String(localized: "Edit Supplier"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        isSaving = true
                        Task {
                            let failure = await onSave(name, email, phone, address, customerNumber)
                            isSaving = false
                            if let failure {
                                saveError = failure
                            } else {
                                dismiss()
                            }
                        }
                    }
                    .disabled(!canSave || isSaving)
                }
            }
            .alert(
                String(localized: "Error"),
                isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })
            ) {
                Button(String(localized: "OK")) { saveError = nil }
            } message: {
                Text(saveError ?? "")
            }
        }
    }
}
