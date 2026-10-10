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
    @State private var confirmFinishWithLeftovers = false

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

                // Slot change leftovers, booked back now that the tour is
                // over and the refiller is back at the warehouse.
                if !viewModel.tourLeftoverTotals.isEmpty {
                    TourLeftoversCard(viewModel: viewModel)
                        .padding(.horizontal)
                        .opacity(showStats ? 1 : 0)
                        .animation(.easeOut(duration: 0.5).delay(0.5), value: showStats)
                }

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
                    if viewModel.hasUnreturnedTourLeftovers {
                        confirmFinishWithLeftovers = true
                    } else {
                        finishTour()
                    }
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
        .alert("Leftover goods have not been booked back yet. Finish anyway?", isPresented: $confirmFinishWithLeftovers) {
            Button("Finish anyway", role: .destructive) { finishTour() }
            Button("Cancel", role: .cancel) {}
        }
        .task {
            await viewModel.loadTourExpirySuggestions()
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

    private func finishTour() {
        HapticFeedback.success.fire()
        viewModel.reset()
        Task { await viewModel.loadData() }
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

// MARK: - Tour Leftovers Card

/// "Leftover goods from rebuilds": what the slot rebuilds of the tour left
/// over, per product across the machines. Back at the warehouse the refiller
/// counts what is really there (defaults = computed), picks warehouse or
/// write-off and confirms the best-before date of goods from the machines,
/// then books it all at once (`return_slot_change_leftovers`).
private struct TourLeftoversCard: View {
    @ObservedObject var viewModel: RefillWizardViewModel

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Leftover goods from rebuilds", systemImage: "arrow.uturn.backward.circle")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.rebuildViolet)

            if viewModel.tourLeftoversReturned {
                Label("Leftover goods booked", systemImage: "checkmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.green)
                ForEach(viewModel.tourLeftoverTotals) { total in
                    HStack(spacing: 10) {
                        ProductImage(imagePath: total.imagePath, size: 28)
                        Text(total.name ?? "")
                            .font(.subheadline)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        Text(verbatim: "\(viewModel.leftoverVan(total) + viewModel.leftoverMachine(total))")
                            .font(.subheadline.bold())
                            .monospacedDigit()
                    }
                }
            } else {
                ForEach(viewModel.tourLeftoverTotals) { total in
                    Divider()
                    row(total)
                }
                Divider()
                Button {
                    HapticFeedback.medium.fire()
                    Task { await viewModel.returnTourLeftovers() }
                } label: {
                    Group {
                        if viewModel.isSaving {
                            ProgressView()
                        } else {
                            Label("Book back", systemImage: "tray.and.arrow.down.fill")
                                .font(.headline)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.rebuildVioletFill)
                .disabled(viewModel.isSaving)
            }
        }
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 14)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.06), radius: 6, y: 2)
        }
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.rebuildVioletFill.opacity(0.4), lineWidth: 1))
    }

    private func row(_ total: TourLeftoverTotal) -> some View {
        let van = viewModel.leftoverVan(total)
        let machine = viewModel.leftoverMachine(total)
        let destination = viewModel.leftoverDestination(productId: total.productId)
        let hasWarehouse = viewModel.selectedWarehouseId != nil

        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                ProductImage(imagePath: total.imagePath, size: 32)
                Text(total.name ?? "")
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }

            stepperRow(title: Text("Unused (van)"), value: van) {
                viewModel.setLeftoverVan(productId: total.productId, value: $0)
            }
            stepperRow(title: Text("Taken out of machines"), value: machine) {
                viewModel.setLeftoverMachine(productId: total.productId, value: $0)
            }

            // Without a warehouse only writing off makes sense.
            if hasWarehouse {
                Picker("Destination", selection: Binding(
                    get: { viewModel.leftoverDestination(productId: total.productId) },
                    set: { viewModel.setLeftoverDestination(productId: total.productId, destination: $0) }
                )) {
                    Text("To warehouse").tag(LeftoverDestination.warehouse)
                    Text("Write off").tag(LeftoverDestination.waste)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }

            if destination == .warehouse {
                if machine > 0 {
                    expiryRow(productId: total.productId)
                }
                if van > 0 {
                    Text("Unused goods go back to the batch they were packed from.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if machine > 0 {
                    Text("Goods from the machine become a new batch with this date.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// Count with large −/+ buttons (min 0).
    private func stepperRow(title: Text, value: Int, onChange: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 8) {
            title
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
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
                .frame(minWidth: 32)

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
        }
    }

    /// Best-before date of goods taken out of the machines (pre-filled from
    /// the batch the last refill came from; the refiller confirms it).
    @ViewBuilder
    private func expiryRow(productId: UUID) -> some View {
        if let stored = viewModel.leftoverExpiry[productId],
           let date = Self.dayFormatter.date(from: stored) {
            HStack(spacing: 8) {
                DatePicker(
                    "Best before",
                    selection: Binding(
                        get: { date },
                        set: { viewModel.setLeftoverExpiry(productId: productId, date: Self.dayFormatter.string(from: $0)) }
                    ),
                    displayedComponents: .date
                )
                .font(.subheadline)
                Button {
                    viewModel.setLeftoverExpiry(productId: productId, date: nil)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Clear"))
            }
        } else {
            Button {
                viewModel.setLeftoverExpiry(productId: productId, date: Self.dayFormatter.string(from: Date()))
            } label: {
                Label("Add best-before date", systemImage: "calendar.badge.plus")
                    .font(.subheadline)
            }
            .buttonStyle(.borderless)
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
