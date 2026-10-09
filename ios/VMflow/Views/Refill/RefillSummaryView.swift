import SwiftUI

/// Summary screen shown after completing a refill tour with statistics and success animation.
struct RefillSummaryView: View {
    @ObservedObject var viewModel: RefillWizardViewModel
    @EnvironmentObject var cashBookVM: CashBookViewModel
    @State private var showCheckmark = false
    @State private var showStats = false
    @State private var showButton = false
    @State private var tourCash: TourCashResolution?
    @State private var autoSheetBarkasse: CashBook?

    private var barkassenWithCash: [CashBook] { tourCash?.barkassen ?? [] }

    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                Spacer(minLength: 40)

                // Success Animation
                ZStack {
                    Circle()
                        .fill(.green.opacity(0.1))
                        .frame(width: 120, height: 120)
                        .scaleEffect(showCheckmark ? 1 : 0.3)
                        .opacity(showCheckmark ? 1 : 0)

                    Circle()
                        .fill(.green.opacity(0.2))
                        .frame(width: 90, height: 90)
                        .scaleEffect(showCheckmark ? 1 : 0.3)
                        .opacity(showCheckmark ? 1 : 0)

                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(.green)
                        .scaleEffect(showCheckmark ? 1 : 0)
                        .rotationEffect(showCheckmark ? .zero : .degrees(-90))
                }
                .animation(.spring(duration: 0.6, bounce: 0.4), value: showCheckmark)

                // Title
                VStack(spacing: 8) {
                    Text("Tour Complete!")
                        .font(.title.bold())
                        .opacity(showStats ? 1 : 0)
                        .offset(y: showStats ? 0 : 20)

                    Text(viewModel.machinesSkipped > 0
                         ? "\(viewModel.machinesVisited) of \(viewModel.machinesVisited + viewModel.machinesSkipped) machines refilled"
                         : "All machines have been refilled")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .opacity(showStats ? 1 : 0)
                        .offset(y: showStats ? 0 : 20)
                }
                .animation(.easeOut(duration: 0.4).delay(0.2), value: showStats)

                // Stats Cards
                VStack(spacing: 12) {
                    statCard(
                        icon: "storefront.fill",
                        label: "Machines Visited",
                        value: "\(viewModel.machinesVisited)",
                        color: .blue
                    )

                    statCard(
                        icon: "tray.fill",
                        label: "Trays Refilled",
                        value: "\(viewModel.traysRefilled)",
                        color: .teal
                    )

                    statCard(
                        icon: "shippingbox.fill",
                        label: "Total Items Added",
                        value: "\(viewModel.totalItemsAdded)",
                        color: .green
                    )

                    // Slots switched to a new product (change notes)
                    if viewModel.slotsRebuilt > 0 {
                        statCard(
                            icon: "arrow.left.arrow.right",
                            label: "Slots Rebuilt",
                            value: "\(viewModel.slotsRebuilt)",
                            color: .purple
                        )
                    }

                    // Skipped machines
                    if viewModel.machinesSkipped > 0 {
                        statCard(
                            icon: "forward.fill",
                            label: "Machines Skipped",
                            value: "\(viewModel.machinesSkipped)",
                            color: .orange
                        )
                    }
                }
                .padding(.horizontal)
                .opacity(showStats ? 1 : 0)
                .offset(y: showStats ? 0 : 30)
                .animation(.easeOut(duration: 0.5).delay(0.4), value: showStats)

                // Multi-Barkasse cash collection block (only when count >= 2)
                if barkassenWithCash.count >= 2 {
                    MultiBarkasseCashBlock(
                        barkassen: barkassenWithCash,
                        expectedCashFor: { id in tourCash?.cashByCashBookId[id] ?? 0 },
                        onSelect: { autoSheetBarkasse = $0 }
                    )
                    .padding(.horizontal)
                    .opacity(showButton ? 1 : 0)
                    .offset(y: showButton ? 0 : 16)
                    .animation(.easeOut(duration: 0.4), value: showButton)
                }

                // Done Button
                Button {
                    HapticFeedback.success.fire()
                    viewModel.reset()
                    Task { await viewModel.loadData() }
                } label: {
                    Text("Done")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal)
                .opacity(showButton ? 1 : 0)
                .offset(y: showButton ? 0 : 20)
                .animation(.easeOut(duration: 0.4).delay(0.7), value: showButton)

                Spacer(minLength: 40)
            }
        }
        .sheet(item: $autoSheetBarkasse) { barkasse in
            WithdrawalSheet(
                cashBook: barkasse,
                fromTour: true,
                tourMachineIds: viewModel.visitedMachineIds
            )
            .environmentObject(cashBookVM)
        }
        .task {
            // 1. Refresh the cash-book VM (fetch books + machines)
            await cashBookVM.refresh()
            // 2. Resolve which Barkassen need cash collection. Each candidate
            //    triggers one isolated RPC; total is typically 0–2 calls.
            let resolution = await viewModel.resolveTourCash(using: cashBookVM)
            tourCash = resolution

            // 3. If exactly one Barkasse needs cash, auto-present its sheet
            //    AFTER the existing Done-button reveal completes (~+1.7 s
            //    from view appearance). We sleep here instead of using a
            //    separate asyncAfter, so the trigger fires only after
            //    resolution is done — no race.
            if resolution.barkassen.count == 1 {
                let elapsedNs = UInt64(1.8 * 1_000_000_000)
                try? await Task.sleep(nanoseconds: elapsedNs)
                autoSheetBarkasse = resolution.barkassen.first
            }
        }
        .onAppear {
            // Trigger haptic
            HapticFeedback.success.fire()

            // Stagger animations (existing behaviour — unchanged)
            withAnimation { showCheckmark = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                withAnimation { showStats = true }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                withAnimation { showButton = true }
            }
            // Auto-sheet trigger lives in `.task` above — DO NOT add a
            // +1.8 s asyncAfter here. The Task.sleep approach gates the
            // trigger on resolution completion, eliminating the race.
        }
    }

    private func statCard(icon: String, label: LocalizedStringKey, value: String, color: Color) -> some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 44, height: 44)
                .background(color.opacity(0.1))
                .clipShape(Circle())

            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Spacer()

            Text(value)
                .font(.title2.bold())
                .monospacedDigit()
        }
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 14)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.06), radius: 6, y: 2)
        }
    }
}

#Preview {
    let vm = RefillWizardViewModel()
    // Populate tourLog to simulate completed tour stats
    vm.tourLog = [
        TourLogEntry(machineId: UUID(), machineName: "Machine A", traysRefilled: 4, totalAdded: 30, skipped: false),
        TourLogEntry(machineId: UUID(), machineName: "Machine B", traysRefilled: 5, totalAdded: 42, skipped: false),
        TourLogEntry(machineId: UUID(), machineName: "Machine C", traysRefilled: 3, totalAdded: 15, skipped: false),
    ]

    return NavigationStack {
        RefillSummaryView(viewModel: vm)
    }
}
