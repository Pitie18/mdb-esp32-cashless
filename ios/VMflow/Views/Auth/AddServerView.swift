import SwiftUI

struct AddServerView: View {
    @ObservedObject var serverStore = ServerStore.shared
    @Environment(\.dismiss) private var dismiss

    var editing: ServerEntry?

    @State private var name: String = ""
    @State private var url: String = ""
    @State private var anonKey: String = ""
    @State private var showScanner = false
    @State private var scanError: String?

    private var isEditing: Bool { editing != nil }

    private var isFormValid: Bool {
        let entry = ServerEntry(id: UUID(), name: name, url: url, anonKey: anonKey, isDefault: false)
        return entry.isValid
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // QR Scanner Button
                    Button {
                        showScanner = true
                    } label: {
                        VStack(spacing: 12) {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.system(size: 48))
                                .foregroundStyle(.blue)
                            Text("Scan QR Code", comment: "QR scan button title")
                                .font(.headline)
                            Text("Scan the code from your web dashboard", comment: "QR scan button subtitle")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                        .background(.fill.tertiary)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)

                    // Divider
                    HStack {
                        Rectangle().fill(.secondary.opacity(0.3)).frame(height: 1)
                        Text("or enter manually", comment: "Divider between QR scan and manual entry")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Rectangle().fill(.secondary.opacity(0.3)).frame(height: 1)
                    }

                    // Manual entry fields
                    VStack(spacing: 16) {
                        formField(
                            label: "Name",
                            placeholder: String(localized: "My Server", comment: "Server name placeholder"),
                            text: $name
                        )
                        formField(
                            label: "Supabase URL",
                            placeholder: "https://supabase.example.com",
                            text: $url,
                            keyboardType: .URL,
                            autocapitalization: .never
                        )
                        formField(
                            label: "Anon Key",
                            placeholder: "eyJhbGciOi...",
                            text: $anonKey,
                            autocapitalization: .never
                        )
                    }

                    if let error = scanError {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                .padding(24)
            }
            .navigationTitle(Text(isEditing
                ? String(localized: "Edit Server", comment: "Navigation title when editing")
                : String(localized: "New Server", comment: "Navigation title when adding")))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel", comment: "Cancel button")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done", comment: "Save button")) {
                        save()
                    }
                    .fontWeight(.semibold)
                    .disabled(!isFormValid)
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerView { code in
                    handleQRCode(code)
                    showScanner = false
                }
                .ignoresSafeArea()
            }
            .onAppear {
                if let server = editing {
                    name = server.name
                    url = server.url
                    anonKey = server.anonKey
                }
            }
        }
    }

    @ViewBuilder
    private func formField(
        label: LocalizedStringKey,
        placeholder: String,
        text: Binding<String>,
        keyboardType: UIKeyboardType = .default,
        autocapitalization: TextInputAutocapitalization = .sentences
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            TextField(placeholder, text: text)
                .keyboardType(keyboardType)
                .textInputAutocapitalization(autocapitalization)
                .autocorrectionDisabled()
                .padding(12)
                .background(.fill.tertiary)
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
    }

    private func handleQRCode(_ code: String) {
        scanError = nil
        guard let data = code.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = json["v"] as? Int, version == 1,
              let scannedURL = json["url"] as? String,
              let scannedKey = json["anonKey"] as? String else {
            scanError = String(localized: "Invalid QR Code", comment: "Error when QR code is not a valid server config")
            return
        }
        url = scannedURL
        anonKey = scannedKey
    }

    private func save() {
        if let existing = editing {
            var updated = existing
            updated.name = name
            updated.url = url
            updated.anonKey = anonKey
            serverStore.updateServer(updated)
        } else {
            let entry = ServerEntry(
                id: UUID(),
                name: name,
                url: url,
                anonKey: anonKey,
                isDefault: false
            )
            serverStore.addServer(entry)
        }
        dismiss()
    }
}
