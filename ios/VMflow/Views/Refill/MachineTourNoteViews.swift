import SwiftUI

// MARK: - Notes for the next tour ("Notiz für nächste Tour")
//
// Written at a machine from the refill step's "⋯" menu, shown at the top of
// the packing step of every tour until it is ticked off there. Same
// behaviour as the PWA's refill wizard and the Android app.

/// Refill step: write (or overwrite) this tour's note for a machine. Prefills
/// with the note already written for it in this tour; saving an empty text
/// deletes it.
struct MachineTourNoteSheet: View {
    @ObservedObject var viewModel: RefillWizardViewModel
    let machineId: UUID
    let machineName: String

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var errorMessage: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("e.g. new spirals, can risers …", text: $text, axis: .vertical)
                        .lineLimit(4...12)
                        .focused($focused)
                        .disabled(isLoading || isSaving)
                        .onChange(of: text) { _, newValue in
                            if newValue.count > MachineTourNotes.maxLength {
                                text = String(newValue.prefix(MachineTourNotes.maxLength))
                            }
                        }
                } header: {
                    Text(machineName)
                } footer: {
                    Text("Shown at the top of the packing step on the next tour.")
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Note for next tour")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button("Save") { Task { await save() } }
                            .disabled(isLoading)
                    }
                }
            }
            .overlay {
                if isLoading {
                    ProgressView()
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(isSaving)
        .task { await load() }
    }

    private func load() async {
        defer {
            isLoading = false
            focused = true
        }
        do {
            if let existing = try await viewModel.currentTourNote(machineId: machineId) {
                text = existing
            }
        } catch {
            // Not fatal: the refiller can still write a note (saving
            // overwrites this tour's note anyway).
            print("[MachineTourNoteSheet] loading the note failed: \(error)")
        }
    }

    private func save() async {
        isSaving = true
        errorMessage = nil
        do {
            try await viewModel.saveTourNote(machineId: machineId, text: text)
            isSaving = false
            HapticFeedback.light.fire()
            dismiss()
        } catch {
            isSaving = false
            errorMessage = error.localizedDescription
        }
    }
}

/// Packing step: the open notes from earlier tours, one row per note.
struct TourNotesCard: View {
    let notes: [MachineTourNote]
    var error: String? = nil
    /// Tick a note off; it stays on every tour until then.
    let onDone: (UUID) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Notes from last time", systemImage: "note.text")
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.blue)

            Text("Tick off once done. Until then they show on every tour.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 0) {
                ForEach(Array(notes.enumerated()), id: \.element.id) { index, note in
                    if index > 0 { Divider() }
                    noteRow(note)
                }
            }

            if let error {
                Text(verbatim: error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.blue.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.blue.opacity(0.35), lineWidth: 1))
    }

    private func noteRow(_ note: MachineTourNote) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Button {
                HapticFeedback.light.fire()
                onDone(note.id)
            } label: {
                Image(systemName: "circle")
                    .font(.title3)
                    .foregroundStyle(.blue)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Done"))

            noteText(note)
        }
        .padding(.vertical, 8)
    }

    private func noteText(_ note: MachineTourNote) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: note.machineName ?? "—")
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(verbatim: timeAgo(from: note.createdAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: note.note)
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
    }
}
