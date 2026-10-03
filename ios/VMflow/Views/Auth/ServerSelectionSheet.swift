import SwiftUI

struct ServerSelectionSheet: View {
    @EnvironmentObject var auth: AuthService
    @ObservedObject var serverStore = ServerStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var showAddServer = false
    @State private var editingServer: ServerEntry?
    @State private var serverPendingDelete: ServerEntry?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(serverStore.allServers) { server in
                        serverRow(server)
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            let server = serverStore.allServers[index]
                            if !server.isDefault {
                                serverPendingDelete = server
                            }
                        }
                    }
                }

                Section {
                    Button {
                        showAddServer = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus.circle.dashed")
                                .font(.title3)
                                .foregroundStyle(.secondary)
                                .frame(width: 32, height: 32)
                            VStack(alignment: .leading) {
                                Text("Add Self-hosted", comment: "Button to add a self-hosted server configuration")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("Select Server", comment: "Title of the server selection sheet"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Done", comment: "Dismiss button")) {
                        dismiss()
                    }
                }
            }
            .sheet(isPresented: $showAddServer) {
                AddServerView()
            }
            .sheet(item: $editingServer) { server in
                AddServerView(editing: server)
            }
            .confirmationDialog(
                Text("Delete Server", comment: "Confirmation dialog title before deleting a saved server"),
                isPresented: Binding(
                    get: { serverPendingDelete != nil },
                    set: { if !$0 { serverPendingDelete = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button(String(localized: "Delete", comment: "Confirm server deletion button"), role: .destructive) {
                    if let server = serverPendingDelete {
                        serverStore.deleteServer(server)
                    }
                    serverPendingDelete = nil
                }
                Button(String(localized: "Cancel", comment: "Cancel server deletion button"), role: .cancel) {
                    serverPendingDelete = nil
                }
            } message: {
                if let server = serverPendingDelete {
                    Text(server.name)
                }
            }
        }
    }

    @ViewBuilder
    private func serverRow(_ server: ServerEntry) -> some View {
        let isSelected = server.id == serverStore.selectedServer.id

        Button {
            selectServer(server)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: server.isDefault ? "cloud.fill" : "server.rack")
                    .font(.title3)
                    .foregroundStyle(server.isDefault ? .blue : .secondary)
                    .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(server.name)
                        .font(.body)
                        .foregroundStyle(.primary)
                    Text(server.url)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.blue)
                        .fontWeight(.semibold)
                }
            }
        }
        .deleteDisabled(server.isDefault)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !server.isDefault {
                Button(role: .destructive) {
                    serverPendingDelete = server
                } label: {
                    Label(String(localized: "Delete", comment: "Delete server action"), systemImage: "trash")
                }
                Button {
                    editingServer = server
                } label: {
                    Label(String(localized: "Edit", comment: "Edit server action"), systemImage: "pencil")
                }
                .tint(.orange)
            }
        }
    }

    private func selectServer(_ server: ServerEntry) {
        guard server.id != serverStore.selectedServer.id else {
            dismiss()
            return
        }
        serverStore.selectServer(server)
        guard let url = URL(string: server.sanitizedURL) else { return }
        SupabaseService.shared.reconfigure(url: url, anonKey: server.anonKey)
        auth.restartAuthListener()
        dismiss()
    }
}
