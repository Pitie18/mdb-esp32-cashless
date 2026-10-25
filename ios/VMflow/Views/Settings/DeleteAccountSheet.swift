import SwiftUI

/// In-app account deletion (Apple Guideline 5.1.1(v)).
///
/// Presented from two entry points — `SettingsView` and `NoOrganizationView`
/// — because a user who registers in-app is org-less on first launch and
/// never reaches Settings.
///
/// Two-stage flow:
///   1. A plain destructive confirmation. Calls `deleteAccount(confirmCompanyName: nil)`.
///   2. Only shown if the server responds with `company_name_mismatch` — this
///      means the caller is the sole admin of their company. The sole-admin
///      rule is deliberately NOT duplicated here client-side; the 400 from
///      the edge function is what drives this stage's appearance.
struct DeleteAccountSheet: View {
    @EnvironmentObject private var auth: AuthService
    @Environment(\.dismiss) private var dismiss

    @State private var stage: Stage = .confirm
    @State private var companyNameInput = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private enum Stage {
        case confirm
        case requireCompanyName
    }

    var body: some View {
        NavigationStack {
            Group {
                switch stage {
                case .confirm:
                    confirmStage
                case .requireCompanyName:
                    companyNameStage
                }
            }
            .navigationTitle("Delete Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isSubmitting)
                }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
    }

    // MARK: - Stage 1: plain confirmation

    private var confirmStage: some View {
        VStack(spacing: 20) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.red)
                .padding(.top, 24)

            Text("Delete Your Account")
                .font(.title2.bold())

            Text("This will permanently delete your account. This action cannot be undone.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()

            Button(role: .destructive) {
                Task { await submit(confirmCompanyName: nil) }
            } label: {
                HStack {
                    Spacer()
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Text("Delete Account")
                            .fontWeight(.semibold)
                    }
                    Spacer()
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .controlSize(.large)
            .disabled(isSubmitting)
            .padding(.horizontal)
            .padding(.bottom, 24)
        }
    }

    // MARK: - Stage 2: sole-admin company-name confirmation

    private var companyNameStage: some View {
        VStack(spacing: 20) {
            Image(systemName: "building.2.fill")
                .font(.system(size: 48))
                .foregroundStyle(.red)
                .padding(.top, 24)

            Text("You're the Last Admin")
                .font(.title2.bold())

            Text("You are the only admin of \(companyNameDisplay). Deleting your account will also permanently delete the company and all of its data — machines, sales, warehouse stock, registered devices, and the cash book.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)

            VStack(alignment: .leading, spacing: 8) {
                Text("Type the company name to confirm:")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                TextField("Company name", text: $companyNameInput)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            .padding(.horizontal, 32)

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()

            Button(role: .destructive) {
                Task { await submit(confirmCompanyName: companyNameInput) }
            } label: {
                HStack {
                    Spacer()
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Text("Delete Company & Account")
                            .fontWeight(.semibold)
                    }
                    Spacer()
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .controlSize(.large)
            .disabled(isSubmitting || companyNameInput.isEmpty)
            .padding(.horizontal)
            .padding(.bottom, 24)
        }
    }

    private var companyNameDisplay: String {
        auth.organization?.name ?? String(localized: "your company")
    }

    // MARK: - Submit

    private func submit(confirmCompanyName: String?) async {
        errorMessage = nil
        isSubmitting = true
        let outcome = await auth.deleteAccount(confirmCompanyName: confirmCompanyName)
        isSubmitting = false

        switch outcome {
        case .success:
            // Server-side deletion succeeded — sign out locally so RootView
            // routes back to the login screen (this dismisses the sheet too).
            await auth.logout()
        case .companyNameMismatch:
            if stage == .requireCompanyName {
                errorMessage = "That name doesn't match. Please try again."
            }
            stage = .requireCompanyName
        case .failure(let message):
            errorMessage = message
        }
    }
}

#Preview {
    DeleteAccountSheet()
        .environmentObject(AuthService())
}
