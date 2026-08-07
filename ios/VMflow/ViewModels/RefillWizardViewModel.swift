import Foundation
import Supabase

// MARK: - Refill Data Structures

/// A machine that needs refilling, with its trays and deficit info.
struct RefillMachine: Identifiable, Equatable, Codable {
    let machine: VendingMachine
    var trays: [RefillTray]
    var isPacked: Bool = false
    var isRefilled: Bool = false
    var isSkipped: Bool = false

    var id: UUID { machine.id }

    /// Total items needed across all trays.
    var totalDeficit: Int {
        trays.reduce(0) { $0 + $1.deficit }
    }

    /// Number of trays that need refilling.
    var traysNeedingRefill: Int {
        trays.filter { $0.deficit > 0 }.count
    }

    /// Total current stock across all trays.
    var totalCurrentStock: Int {
        trays.reduce(0) { $0 + $1.tray.currentStock }
    }

    /// Total capacity across all trays.
    var totalCapacity: Int {
        trays.reduce(0) { $0 + $1.tray.capacity }
    }

    /// Overall stock percentage.
    var stockPercent: Int {
        guard totalCapacity > 0 else { return 0 }
        return Int((Double(totalCurrentStock) / Double(totalCapacity) * 100).rounded())
    }

    /// Stock health derived from tray states.
    var stockHealth: StockHealth {
        if trays.contains(where: { $0.tray.isEmpty }) { return .critical }
        if trays.contains(where: { $0.tray.isBelowMinStock }) { return .low }
        return .ok
    }

    /// Products needed with quantities (aggregated for packing).
    var productsNeeded: [PackingItem] {
        var items: [UUID: PackingItem] = [:]
        for tray in trays where tray.deficit > 0 {
            if let productId = tray.tray.productId {
                if var existing = items[productId] {
                    existing.quantity += tray.deficit
                    items[productId] = existing
                } else {
                    items[productId] = PackingItem(
                        productId: productId,
                        productName: tray.tray.productName,
                        imagePath: tray.tray.products?.imagePath,
                        sellprice: tray.tray.products?.sellprice,
                        quantity: tray.deficit
                    )
                }
            }
        }
        return Array(items.values).sorted { $0.productName < $1.productName }
    }
}

/// A tray within the refill flow, tracking how much to add.
struct RefillTray: Identifiable, Equatable, Codable {
    let tray: Tray
    var fillAmount: Int  // How many items to add (user can adjust)
    /// Whether this tray is included in the currently active refill tour.
    /// Set in `startTour()` based on which products were packed:
    /// - Product-less trays: always `true` (user refills them manually)
    /// - Product trays: `true` only when the product was packed for this machine
    /// The RefillStepView filters by this flag, so reducing `fillAmount` to 0
    /// does not hide a tray that the user packed.
    var isInTour: Bool = true

    var id: UUID { tray.id }

    var deficit: Int { tray.deficit }
    var targetStock: Int { tray.currentStock + fillAmount }

    private enum CodingKeys: String, CodingKey {
        case tray, fillAmount, isInTour
    }

    init(tray: Tray, fillAmount: Int, isInTour: Bool = true) {
        self.tray = tray
        self.fillAmount = fillAmount
        self.isInTour = isInTour
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.tray = try container.decode(Tray.self, forKey: .tray)
        self.fillAmount = try container.decode(Int.self, forKey: .fillAmount)
        // Default to `true` so previously saved tour state (without this field)
        // decodes cleanly — all saved trays were part of the tour they belonged to.
        self.isInTour = try container.decodeIfPresent(Bool.self, forKey: .isInTour) ?? true
    }
}

/// An item to pack from the warehouse.
struct PackingItem: Identifiable, Equatable {
    let productId: UUID
    let productName: String
    let imagePath: String?
    let sellprice: Double?
    var quantity: Int

    var id: UUID { productId }
}

// MARK: - Combined Packing Structures

/// A product grouped across all machines that need it.
struct CombinedPackingItem: Identifiable, Equatable {
    let productId: UUID
    let productName: String
    let imagePath: String?
    let sellprice: Double?
    let totalQuantity: Int
    let machineNeeds: [MachineNeed]

    var id: UUID { productId }

    /// Formatted EUR price for display, or `nil` when no price is set.
    var formattedSellprice: String? {
        guard let price = sellprice else { return nil }
        return String(format: "%.2f \u{20AC}", price)
    }
}

/// One machine's need for a specific product.
struct MachineNeed: Identifiable, Equatable {
    let machineId: UUID
    let machineName: String
    let quantity: Int
    let capacity: Int

    var id: UUID { machineId }
}

// MARK: - Tour Log

/// Per-machine result entry for the tour log.
struct TourLogEntry: Equatable, Codable {
    let machineId: UUID
    let machineName: String
    let traysRefilled: Int
    let totalAdded: Int
    let skipped: Bool
}

// MARK: - Product Replacement

enum ReplacementReason: String, Codable {
    case discontinued
    case expired
    case noStock  // Product has zero warehouse stock AND tray is empty
    case unassigned  // Tray has no product assigned yet
}

/// A tray that should be reviewed before packing — product is discontinued,
/// expired, out of stock, or not assigned at all.
struct ReplacementSuggestion: Identifiable, Equatable, Codable {
    let trayId: UUID
    let machineId: UUID
    let machineName: String
    let slotNumber: Int
    /// `nil` when the tray has no product assigned (reason: `.unassigned`).
    let currentProductId: UUID?
    let currentProductName: String
    let currentProductImage: String?
    let currentStock: Int
    let reason: ReplacementReason
    var replacementProductId: UUID?
    var isSkipped: Bool = false

    var id: UUID { trayId }
}

// MARK: - Refill Steps

enum RefillStep: Int, CaseIterable {
    case review = 0
    case packing = 1
    case refill = 2
    case summary = 3

    var title: String {
        switch self {
        case .review: return String(localized: "Review")
        case .packing: return String(localized: "Pack")
        case .refill: return String(localized: "Refill")
        case .summary: return String(localized: "Summary")
        }
    }

    var icon: String {
        switch self {
        case .review: return "exclamationmark.triangle"
        case .packing: return "archivebox"
        case .refill: return "arrow.clockwise"
        case .summary: return "checkmark.circle"
        }
    }
}

// MARK: - Pack Chip Filter

/// Filter chip selection in the Pack step. `.all` shows the full
/// product-centric combined list (today's behavior); `.machine(id)` filters
/// the list to just the products needed for that one machine. In-memory
/// only — not persisted in `PersistedTourState` because the Pack step
/// itself is never persisted.
enum ChipFilter: Equatable, Hashable {
    case all
    case machine(UUID)
}

// MARK: - ViewModel

/// Multi-step refill wizard: packing -> refill per machine -> summary.
@MainActor
final class RefillWizardViewModel: ObservableObject {
    // MARK: - Published State

    @Published var currentStep: RefillStep = .review
    @Published var machines: [RefillMachine] = []
    /// Unfiltered tray list per machine, keyed by machine id. `machines[*].trays`
    /// only contains trays that need refill action (deficit > 0 + below threshold);
    /// the review picker needs every tray in the machine to render the
    /// "already in slot N" badge for products that are stocked elsewhere in
    /// the same machine. Populated alongside `machines` in `loadData()` and
    /// `refreshDuringPacking()`.
    @Published var allTraysByMachine: [UUID: [Tray]] = [:]
    @Published var replacements: [ReplacementSuggestion] = []
    /// All active (non-discontinued) products for the replacement picker.
    @Published var availableProducts: [Product] = []
    /// Product categories used to label/group products in the replacement
    /// picker. Loaded alongside `availableProducts` in `loadData()`.
    @Published var productCategories: [ProductCategory] = []
    /// Set after review step completes, so re-loading data doesn't re-trigger review.
    private var reviewCompleted = false
    @Published var warehouses: [Warehouse] = []
    @Published var selectedWarehouseId: UUID?
    @Published var warehouseStock: [WarehouseProductStock] = []
    /// product_id → 0-based index in the warehouse's physical pick order
    /// (depth-first through position groups). Empty when no positions are
    /// defined for the selected warehouse, in which case pack lists fall back
    /// to a quantity-based sort.
    @Published var warehouseProductOrder: [UUID: Int] = [:]
    @Published var currentMachineIndex: Int = 0
    /// Active filter chip in the Pack step. Resets to `.all` whenever
    /// `loadData()` runs (fresh start). Snap-back to `.all` if the active
    /// machine vanishes from `chipOrder` (defensive — shouldn't happen mid-tour).
    @Published var activeChip: ChipFilter = .all
    @Published var isLoading = false
    @Published var isSaving = false
    @Published var error: String?

    /// Packed state: machineId → Set of productIds that have been packed for that machine.
    @Published var packedItems: [UUID: Set<UUID>] = [:]

    /// Custom packing quantities: machineId → (productId → quantity).
    /// If not set, defaults to the tray deficit.
    @Published var customQuantities: [UUID: [UUID: Int]] = [:]

    /// Per-machine results recorded during the refill step.
    @Published var tourLog: [TourLogEntry] = []

    /// Tray IDs whose `currentStock` changed due to a sale AFTER the tour
    /// started. `refreshDuringRefill()` populates this; the RefillStepView
    /// renders a small "sold-during-tour" badge on flagged trays so the
    /// user notices the delta before confirming.
    @Published var staleStockTrayIds: Set<UUID> = []

    /// Unique tour identifier, used to group activity log entries.
    private(set) var tourId: String = ""

    /// Whether we found a saved tour that can be resumed.
    @Published var hasSavedTour: Bool = false

    /// Guards the wizard's one-time entry decision (resume-vs-load).
    ///
    /// SwiftUI ties `.task` to appear/disappear, and `TabView` fires those on
    /// every tab switch — so `RefillWizardView.task` re-runs each time the user
    /// returns to the Refill tab. Without this gate, a return mid-tour re-runs
    /// `checkForSavedTour()` and re-offers "Resume Tour?", and resuming
    /// overwrites the live tray stock with the saved tour-start snapshot while
    /// leaving the "sold during tour" badges set (the reported icon-present /
    /// stock-reverted-to-Anfangsbestand desync). Set once on first entry;
    /// cleared only by `reset()` so a brand-new tour reloads fresh.
    private(set) var didRunInitialLoad = false

    private let client = SupabaseService.shared.client

    // MARK: - Persistence

    /// Today's date as "YYYY-MM-DD" for expiry comparisons.
    private static func todayDateString() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date())
    }

    private static let storageKey = "refill-tour-state"
    /// Max age of persisted state: 24 hours (matches web).
    private static let maxAgeSeconds: TimeInterval = 24 * 60 * 60

    /// Codable snapshot of tour state for persistence.
    private struct PersistedTourState: Codable {
        let currentStep: String // "packing" | "refill" | "summary"
        let machines: [RefillMachine]
        let currentMachineIndex: Int
        let selectedWarehouseId: UUID?
        let tourId: String
        let tourLog: [TourLogEntry]
        let savedAt: Date
    }

    /// Lightweight static check: is there a valid saved tour?
    static var hasSavedTourState: Bool {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return false }
        guard let state = try? JSONDecoder().decode(PersistedTourState.self, from: data) else { return false }
        if Date().timeIntervalSince(state.savedAt) > maxAgeSeconds { return false }
        return state.currentStep == "refill" || state.currentStep == "summary"
    }

    /// Claim the wizard's one-time entry decision. Returns `true` exactly once
    /// per VM lifetime (until `reset()`); every later call — i.e. every tab
    /// re-selection that re-fires `.task` — returns `false` so the view skips
    /// the resume/load decision and leaves the in-memory tour untouched.
    func beginInitialLoadIfNeeded() -> Bool {
        guard !didRunInitialLoad else { return false }
        didRunInitialLoad = true
        return true
    }

    /// Check for a saved tour on launch.
    func checkForSavedTour() {
        guard let data = UserDefaults.standard.data(forKey: Self.storageKey) else {
            hasSavedTour = false
            return
        }
        do {
            let state = try JSONDecoder().decode(PersistedTourState.self, from: data)
            // Check TTL
            if Date().timeIntervalSince(state.savedAt) > Self.maxAgeSeconds {
                Self.clearSavedTour()
                hasSavedTour = false
                return
            }
            // Only resume if in refill or summary step
            hasSavedTour = (state.currentStep == "refill" || state.currentStep == "summary")
        } catch {
            hasSavedTour = false
        }
    }

    /// Resume a previously saved tour. Returns true if successful.
    func resumeTour() -> Bool {
        guard let data = UserDefaults.standard.data(forKey: Self.storageKey) else { return false }
        do {
            let state = try JSONDecoder().decode(PersistedTourState.self, from: data)
            if Date().timeIntervalSince(state.savedAt) > Self.maxAgeSeconds {
                Self.clearSavedTour()
                return false
            }
            guard state.currentStep == "refill" || state.currentStep == "summary" else { return false }

            // Restore state
            machines = state.machines
            currentMachineIndex = state.currentMachineIndex
            selectedWarehouseId = state.selectedWarehouseId
            tourId = state.tourId
            tourLog = state.tourLog
            // The snapshot holds tour-start stock; "sold during tour" badges
            // are not persisted. Start clean and let the caller's live refresh
            // (RefillWizardView) re-flag any trays that actually dropped, so a
            // resumed tour never shows a badge against a stale baseline.
            staleStockTrayIds = []

            switch state.currentStep {
            case "refill": currentStep = .refill
            case "summary": currentStep = .summary
            default: return false
            }

            hasSavedTour = false
            return true
        } catch {
            print("[RefillWizard] Failed to restore tour: \(error)")
            return false
        }
    }

    /// Save current tour state to UserDefaults.
    private func saveTourState() {
        guard currentStep == .refill || currentStep == .summary else { return }

        let stepString: String
        switch currentStep {
        case .review, .packing: return // nothing to persist before tour starts
        case .refill: stepString = "refill"
        case .summary: stepString = "summary"
        }

        let state = PersistedTourState(
            currentStep: stepString,
            machines: machines,
            currentMachineIndex: currentMachineIndex,
            selectedWarehouseId: selectedWarehouseId,
            tourId: tourId,
            tourLog: tourLog,
            savedAt: Date()
        )

        do {
            let data = try JSONEncoder().encode(state)
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        } catch {
            print("[RefillWizard] Failed to save tour state: \(error)")
        }
    }

    /// Clear any saved tour state.
    static func clearSavedTour() {
        UserDefaults.standard.removeObject(forKey: storageKey)
    }

    // MARK: - Tour Summary (computed from tourLog)

    var machinesVisited: Int { tourLog.filter { !$0.skipped }.count }
    var traysRefilled: Int { tourLog.reduce(0) { $0 + $1.traysRefilled } }
    var totalItemsAdded: Int { tourLog.reduce(0) { $0 + $1.totalAdded } }
    var machinesSkipped: Int { tourLog.filter { $0.skipped }.count }

    /// Look up warehouse stock for a product.
    func warehouseStockFor(productId: UUID) -> WarehouseProductStock? {
        warehouseStock.first { $0.productId == productId }
    }

    /// Total quantity already committed (packed) for a product across ALL machines.
    func committedQuantity(productId: UUID) -> Int {
        var total = 0
        for machine in machines {
            guard packedItems[machine.id]?.contains(productId) == true else { continue }
            total += packingQuantity(machineId: machine.id, productId: productId)
        }
        return total
    }

    /// Remaining warehouse stock for a product after subtracting committed quantities.
    func remainingWarehouseStock(productId: UUID) -> Int {
        guard let stock = warehouseStockFor(productId: productId) else { return 0 }
        return max(0, stock.totalQuantity - committedQuantity(productId: productId))
    }

    /// Look up the category UUID of the product currently in the tray being
    /// replaced. Returns `nil` if (a) no `ReplacementSuggestion` exists for
    /// this tray, (b) the suggestion has no current product (unassigned
    /// slot), (c) the current product isn't in `availableProducts`, or
    /// (d) the product is uncategorized. The replacement picker uses this
    /// to highlight the matching category first.
    func currentCategoryId(forTrayId trayId: UUID) -> UUID? {
        guard let pid = replacements.first(where: { $0.trayId == trayId })?.currentProductId
        else { return nil }
        return availableProducts.first(where: { $0.id == pid })?.category
    }

    /// Whether a product is completely out of warehouse stock (nothing left to pack).
    func isOutOfWarehouseStock(productId: UUID) -> Bool {
        // If no warehouse is selected or no stock data loaded, don't restrict
        guard selectedWarehouseId != nil, !warehouseStock.isEmpty else { return false }
        return remainingWarehouseStock(productId: productId) <= 0
    }

    /// Whether a specific machine-product pair is out of remaining warehouse stock.
    /// Takes into account stock already committed by other machines.
    func isOutOfStockForMachine(machineId: UUID, productId: UUID) -> Bool {
        guard selectedWarehouseId != nil, !warehouseStock.isEmpty else { return false }
        let isPacked = isMachinePacked(machineId: machineId, productId: productId)
        if isPacked {
            // Already committed — it has its allocation
            return false
        }
        return remainingWarehouseStock(productId: productId) <= 0
    }

    // MARK: - Combined Packing List

    /// Products grouped across all machines, sorted by warehouse pick order
    /// when available (see `warehouseProductOrder`), else by total quantity
    /// descending.
    var combinedPackingList: [CombinedPackingItem] {
        var grouped: [UUID: (name: String, image: String?, sellprice: Double?, total: Int, needs: [MachineNeed])] = [:]

        for machine in machines {
            for tray in machine.trays where tray.deficit > 0 {
                guard let productId = tray.tray.productId else { continue }
                let need = MachineNeed(
                    machineId: machine.id,
                    machineName: machine.machine.displayName,
                    quantity: tray.deficit,
                    capacity: tray.tray.capacity
                )
                if var existing = grouped[productId] {
                    // Check if this machine already has an entry (multiple trays same product)
                    if let idx = existing.needs.firstIndex(where: { $0.machineId == machine.id }) {
                        let old = existing.needs[idx]
                        existing.needs[idx] = MachineNeed(
                            machineId: machine.id,
                            machineName: machine.machine.displayName,
                            quantity: old.quantity + tray.deficit,
                            capacity: old.capacity + tray.tray.capacity
                        )
                    } else {
                        existing.needs.append(need)
                    }
                    existing.total += tray.deficit
                    grouped[productId] = existing
                } else {
                    grouped[productId] = (
                        name: tray.tray.productName,
                        image: tray.tray.products?.imagePath,
                        sellprice: tray.tray.products?.sellprice,
                        total: tray.deficit,
                        needs: [need]
                    )
                }
            }
        }

        let items = grouped.map { (productId, data) in
            CombinedPackingItem(
                productId: productId,
                productName: data.name,
                imagePath: data.image,
                sellprice: data.sellprice,
                totalQuantity: data.total,
                machineNeeds: data.needs.sorted { $0.machineName < $1.machineName }
            )
        }

        // Sort by physical warehouse pick order so the user walks the
        // warehouse front-to-back only once. Matches the web's combinedPickList
        // sorting: positioned products first (in position order), then
        // unpositioned products alphabetically. Falls back to total-quantity
        // descending when no positions are defined for the warehouse.
        //
        // IMPORTANT: every tap on +/- in the Pack screen re-publishes state and
        // recomputes this list. `grouped` is a Swift Dictionary whose iteration
        // order is not guaranteed to be stable across instances, so the sort
        // comparator MUST produce a total order — no ties — or rows with equal
        // primary keys will swap positions between renders.
        if warehouseProductOrder.isEmpty {
            return items.sorted { a, b in
                if a.totalQuantity != b.totalQuantity {
                    return a.totalQuantity > b.totalQuantity
                }
                let nameCompare = a.productName.localizedCaseInsensitiveCompare(b.productName)
                if nameCompare != .orderedSame {
                    return nameCompare == .orderedAscending
                }
                return a.productId.uuidString < b.productId.uuidString
            }
        }
        return items.sorted { a, b in
            let posA = warehouseProductOrder[a.productId]
            let posB = warehouseProductOrder[b.productId]
            switch (posA, posB) {
            case let (pa?, pb?) where pa != pb:
                return pa < pb
            case (_?, nil):
                return true  // a has a position — sort before unpositioned b
            case (nil, _?):
                return false // b has a position — sort before unpositioned a
            default:
                break        // same position, or both unpositioned — fall through
            }
            // Deterministic tiebreaker for equal positions or both unpositioned.
            let nameCompare = a.productName.localizedCaseInsensitiveCompare(b.productName)
            if nameCompare != .orderedSame {
                return nameCompare == .orderedAscending
            }
            return a.productId.uuidString < b.productId.uuidString
        }
    }

    /// Products to display in the Pack step: `combinedPackingList` filtered
    /// so rows the user can't act on (no warehouse stock for the product,
    /// nothing packed yet) are hidden instead of rendered greyed out —
    /// they just waste screen space. Partially-packed products stay visible
    /// so the user can still uncheck or adjust their own commitments.
    var visibleCombinedPackingList: [CombinedPackingItem] {
        combinedPackingList.filter { item in
            let anyPacked = item.machineNeeds.contains { need in
                isMachinePacked(machineId: need.machineId, productId: item.productId)
            }
            if anyPacked { return true }
            return !isOutOfWarehouseStock(productId: item.productId)
        }
    }

    // MARK: - Chip Filter Helpers

    /// Chips displayed in the Pack step, in order: `.all` first, then every
    /// machine in `machines` order (matches the order surfaces in the rest of
    /// the wizard).
    var chipOrder: [ChipFilter] {
        [.all] + machines.map { .machine($0.id) }
    }

    /// Display name for a chip. `.all` uses the localized "All" label; a
    /// machine chip uses the machine's `displayName`.
    func chipName(_ chip: ChipFilter) -> String {
        switch chip {
        case .all:
            return String(localized: "All")
        case .machine(let id):
            return machines.first(where: { $0.id == id })?.machine.displayName ?? ""
        }
    }

    /// Potential box size for a chip — sum of `displayQuantity` over every
    /// `(machine, product)` pair that has a need AND is visible in the
    /// picklist. For `.all` the sum is across every machine; for
    /// `.machine(id)` only that one machine.
    ///
    /// Skips pairs that are out-of-warehouse-stock AND not yet packed —
    /// those don't render in the picklist, so they must not be counted
    /// in the chip badge either.
    ///
    /// Intentionally distinct from `totalItemsToPack` (which only sums
    /// across `packedMachines`). The chip shows "how big the box would be
    /// if fully packed", the bottom bar shows "how many items the tour
    /// will actually deliver".
    func chipItemCount(_ chip: ChipFilter) -> Int {
        let allMachineIds: [UUID]
        switch chip {
        case .all:
            allMachineIds = machines.map(\.id)
        case .machine(let id):
            allMachineIds = [id]
        }
        var total = 0
        for item in combinedPackingList {
            for need in item.machineNeeds where allMachineIds.contains(need.machineId) {
                let packed = isMachinePacked(machineId: need.machineId, productId: item.productId)
                let outOfStock = isOutOfStockForMachine(machineId: need.machineId, productId: item.productId)
                if outOfStock && !packed { continue }
                total += displayQuantity(machineId: need.machineId, productId: item.productId)
            }
        }
        return total
    }

    /// True when every needed `(machine, product)` pair for the chip is
    /// both checked AND packed at the full required quantity. For `.all`
    /// this requires every machine chip to be fully packed.
    func chipIsFullyPacked(_ chip: ChipFilter) -> Bool {
        switch chip {
        case .all:
            let machineChips = chipOrder.dropFirst()
            guard !machineChips.isEmpty else { return false }
            return machineChips.allSatisfy(chipIsFullyPacked)
        case .machine(let id):
            var hadAnyNeed = false
            for item in combinedPackingList {
                guard let need = item.machineNeeds.first(where: { $0.machineId == id }) else { continue }
                let packed = isMachinePacked(machineId: id, productId: item.productId)
                let outOfStock = isOutOfStockForMachine(machineId: id, productId: item.productId)
                // Out-of-stock-and-unpacked pairs aren't in the picklist;
                // the user has no way to act on them, so they must not
                // block the chip from reaching "done".
                if outOfStock && !packed { continue }
                hadAnyNeed = true
                guard packed else { return false }
                // "Fully packed" = packed at the highest quantity currently
                // possible. If the warehouse can't satisfy the full deficit,
                // packing at maxPackingQuantity still counts as done — the
                // user has done all they can for this product. Without this,
                // any warehouse-capped pack flips the chip into a misleading
                // "alert" state on first pack.
                let qty = displayQuantity(machineId: id, productId: item.productId)
                let maxQty = maxPackingQuantity(machineId: id, productId: item.productId)
                guard qty >= min(need.quantity, maxQty) else { return false }
            }
            return hadAnyNeed
        }
    }

    /// Number of distinct products needed for a chip, filtered to what
    /// actually appears in the picklist (out-of-stock-and-unpacked pairs
    /// are excluded, matching `chipItemCount` and `chipIsFullyPacked`).
    /// - `.all`: count across all machines (sums per-machine needs, may double-count
    ///   a product needed in N machines — that's correct because the chip view
    ///   shows N machine-need rows for it).
    /// - `.machine(id)`: count just that machine's needs.
    func chipNeedsCount(_ chip: ChipFilter) -> Int {
        let allMachineIds: [UUID]
        switch chip {
        case .all:
            allMachineIds = machines.map(\.id)
        case .machine(let id):
            allMachineIds = [id]
        }
        var count = 0
        for item in combinedPackingList {
            for need in item.machineNeeds where allMachineIds.contains(need.machineId) {
                let packed = isMachinePacked(machineId: need.machineId, productId: item.productId)
                let outOfStock = isOutOfStockForMachine(machineId: need.machineId, productId: item.productId)
                if outOfStock && !packed { continue }
                count += 1
            }
        }
        return count
    }

    /// Number of (machine, product) pairs currently ticked for a chip.
    /// For `.all`, counts ticked pairs across all machines.
    /// For `.machine(id)`, counts ticked pairs scoped to that machine.
    func chipPackedCount(_ chip: ChipFilter) -> Int {
        let machineIds: [UUID]
        switch chip {
        case .all:        machineIds = machines.map(\.id)
        case .machine(let id): machineIds = [id]
        }
        var count = 0
        for item in combinedPackingList {
            for need in item.machineNeeds where machineIds.contains(need.machineId) {
                if isMachinePacked(machineId: need.machineId, productId: item.productId) {
                    count += 1
                }
            }
        }
        return count
    }

    /// Items to render in the Pack step's list, dispatched on `activeChip`.
    ///
    /// - `.all`: passes through `visibleCombinedPackingList` unchanged (today's
    ///   behavior — product cards with expandable per-machine sub-rows).
    /// - `.machine(id)`: filters and rewrites — each returned `CombinedPackingItem`
    ///   carries exactly one `MachineNeed` (the active machine's) and its
    ///   `totalQuantity` is that machine's deficit. The same "hide if out-of-
    ///   stock and nothing-packed-yet" rule as `visibleCombinedPackingList`
    ///   applies, scoped to this one machine.
    var visibleItemsForActiveChip: [CombinedPackingItem] {
        switch activeChip {
        case .all:
            return visibleCombinedPackingList
        case .machine(let id):
            return combinedPackingList.compactMap { item in
                guard let need = item.machineNeeds.first(where: { $0.machineId == id }) else { return nil }
                let packed = isMachinePacked(machineId: id, productId: item.productId)
                let outOfStock = isOutOfStockForMachine(machineId: id, productId: item.productId)
                if outOfStock && !packed { return nil }
                return CombinedPackingItem(
                    productId: item.productId,
                    productName: item.productName,
                    imagePath: item.imagePath,
                    sellprice: item.sellprice,
                    totalQuantity: need.quantity,
                    machineNeeds: [need]
                )
            }
        }
    }

    /// Whether a specific product is packed for a specific machine.
    func isMachinePacked(machineId: UUID, productId: UUID) -> Bool {
        packedItems[machineId]?.contains(productId) ?? false
    }

    /// Whether a product is fully packed for ALL machines that need it.
    func isProductFullyPacked(_ item: CombinedPackingItem) -> Bool {
        item.machineNeeds.allSatisfy { need in
            packedItems[need.machineId]?.contains(item.productId) ?? false
        }
    }

    /// Get the packing quantity for a machine-product pair (custom or default deficit).
    /// This is the "truth" used for commitment math and warehouse deduction. For
    /// UI display that should reflect the warehouse cap, use `displayQuantity`.
    func packingQuantity(machineId: UUID, productId: UUID) -> Int {
        if let custom = customQuantities[machineId]?[productId] {
            return custom
        }
        // Default: sum of deficits for this product in this machine's trays
        guard let machine = machines.first(where: { $0.id == machineId }) else { return 0 }
        return machine.trays
            .filter { $0.tray.productId == productId && $0.deficit > 0 }
            .reduce(0) { $0 + $1.deficit }
    }

    /// Quantity to show in the Pack UI for a machine-product pair.
    ///
    /// - For PACKED machines: returns `packingQuantity` (the actual committed value).
    /// - For UNCHECKED machines: caps to `maxPackingQuantity` so the UI never
    ///   advertises more items than the warehouse could actually deliver.
    ///
    /// Matches the web's `effectiveDeficit` behaviour. Keeps `packingQuantity`
    /// (used by `committedQuantity` / `maxPackingQuantity` / warehouse deduction)
    /// untouched so the commitment math stays precise.
    func displayQuantity(machineId: UUID, productId: UUID) -> Int {
        let current = packingQuantity(machineId: machineId, productId: productId)
        if isMachinePacked(machineId: machineId, productId: productId) {
            return current
        }
        let cap = maxPackingQuantity(machineId: machineId, productId: productId)
        return Swift.min(current, cap)
    }

    /// Max packing quantity for a machine-product pair.
    /// Capped by both tray capacity and remaining warehouse stock.
    func maxPackingQuantity(machineId: UUID, productId: UUID) -> Int {
        guard let machine = machines.first(where: { $0.id == machineId }) else { return 0 }
        let trayMax = machine.trays
            .filter { $0.tray.productId == productId }
            .reduce(0) { $0 + max(0, $1.tray.capacity - $1.tray.currentStock) }

        // If warehouse stock is loaded, also cap by remaining stock
        guard selectedWarehouseId != nil, !warehouseStock.isEmpty else { return trayMax }
        guard let stock = warehouseStockFor(productId: productId) else { return 0 }

        // Available = total warehouse stock minus what OTHER machines have committed
        let otherCommitted = machines
            .filter { $0.id != machineId && packedItems[$0.id]?.contains(productId) == true }
            .reduce(0) { $0 + packingQuantity(machineId: $1.id, productId: productId) }
        let available = max(0, stock.totalQuantity - otherCommitted)

        return min(trayMax, available)
    }

    /// Set a custom packing quantity for a machine-product pair.
    func setPackingQuantity(machineId: UUID, productId: UUID, quantity: Int) {
        let maxQty = maxPackingQuantity(machineId: machineId, productId: productId)
        let clamped = max(0, min(maxQty, quantity))
        var machineMap = customQuantities[machineId] ?? [:]
        machineMap[productId] = clamped
        customQuantities[machineId] = machineMap
    }

    /// Toggle packed state for one machine-product pair.
    /// Skips if out of warehouse stock when trying to pack.
    func togglePackedForMachine(productId: UUID, machineId: UUID) {
        var set = packedItems[machineId] ?? Set()
        if set.contains(productId) {
            set.remove(productId)
            // Clear custom quantity so it recalculates on re-pack
            customQuantities[machineId]?[productId] = nil
        } else {
            // Don't allow packing if out of stock
            guard !isOutOfStockForMachine(machineId: machineId, productId: productId) else { return }
            set.insert(productId)
            // Auto-cap the quantity to remaining warehouse stock
            pinPackingQuantity(machineId: machineId, productId: productId)
        }
        packedItems[machineId] = set
        syncMachinePackedState()
    }

    /// Toggle packed state for a product across ALL machines that need it.
    func togglePackedAll(productId: UUID) {
        guard let item = combinedPackingList.first(where: { $0.productId == productId }) else { return }
        let allPacked = isProductFullyPacked(item)

        if allPacked {
            // Unpack all
            for need in item.machineNeeds {
                var set = packedItems[need.machineId] ?? Set()
                set.remove(productId)
                packedItems[need.machineId] = set
                customQuantities[need.machineId]?[productId] = nil
            }
        } else {
            // Pack all (with stock-aware capping, in urgency order)
            for need in item.machineNeeds {
                guard !isOutOfStockForMachine(machineId: need.machineId, productId: productId) else { continue }
                var set = packedItems[need.machineId] ?? Set()
                set.insert(productId)
                packedItems[need.machineId] = set
                pinPackingQuantity(machineId: need.machineId, productId: productId)
            }
        }
        syncMachinePackedState()
    }

    /// Pack all products for all machines (stock-aware).
    func packEverything() {
        for item in combinedPackingList {
            for need in item.machineNeeds {
                guard !isOutOfStockForMachine(machineId: need.machineId, productId: item.productId) else { continue }
                var set = packedItems[need.machineId] ?? Set()
                set.insert(item.productId)
                packedItems[need.machineId] = set
                pinPackingQuantity(machineId: need.machineId, productId: item.productId)
            }
        }
        syncMachinePackedState()
    }

    /// Pin the packing quantity as an explicit `customQuantities` entry.
    ///
    /// Called at the moment the user packs a product (toggle-all, per-machine
    /// toggle, or "pack everything"). Without this, `packingQuantity` falls
    /// back to the tray deficit, which silently drifts under realtime
    /// refreshes: a sale widens the deficit and the displayed "packed"
    /// number moves under the user's fingers. Worse, if the warehouse can
    /// only partially satisfy the new default, the user walks away with
    /// fewer physical items than the UI implied.
    ///
    /// `setPackingQuantity` already clamps the input by `maxPackingQuantity`
    /// (tray-capacity + warehouse-remaining), so passing the current default
    /// pins exactly what the user intended at pack time. Any subsequent
    /// deficit/warehouse drift shows up via the `underpacked` border/badge
    /// rather than as an invisible adjustment.
    private func pinPackingQuantity(machineId: UUID, productId: UUID) {
        let currentQty = packingQuantity(machineId: machineId, productId: productId)
        setPackingQuantity(machineId: machineId, productId: productId, quantity: currentQty)
    }

    /// Sync machine isPacked flag: a machine is packed when at least one product is checked.
    private func syncMachinePackedState() {
        for i in machines.indices {
            let machine = machines[i]
            let packedProductIds = packedItems[machine.id] ?? Set()
            let neededProductIds = Set(machine.trays.compactMap { $0.tray.productId }.filter { pid in
                machine.trays.contains { $0.tray.productId == pid && $0.deficit > 0 }
            })
            machines[i].isPacked = !neededProductIds.isDisjoint(with: packedProductIds)
        }
    }

    /// Machines selected for the tour (all products packed).
    var packedMachines: [RefillMachine] {
        machines.filter { $0.isPacked }
    }

    /// Machines still remaining in the refill tour (packed, not yet done).
    var remainingMachines: [RefillMachine] {
        machines.filter { $0.isPacked && !$0.isRefilled && !$0.isSkipped }
    }

    /// Current machine being refilled.
    var currentMachine: RefillMachine? {
        let refillable = remainingMachines
        guard currentMachineIndex < refillable.count else { return nil }
        return refillable[currentMachineIndex]
    }

    /// Jump to a specific machine in the remaining list.
    func selectMachine(_ machineId: UUID) {
        let refillable = remainingMachines
        if let idx = refillable.firstIndex(where: { $0.id == machineId }) {
            currentMachineIndex = idx
        }
    }

    /// Progress fraction through machines.
    var machineProgress: (current: Int, total: Int) {
        let refillable = machines.filter { $0.isPacked }
        let done = machines.filter { $0.isPacked && ($0.isRefilled || $0.isSkipped) }.count
        return (min(done + 1, refillable.count), refillable.count)
    }

    /// Total items to pack across all packed machines (respects custom quantities).
    var totalItemsToPack: Int {
        var total = 0
        for machine in packedMachines {
            let productIds = Set(machine.trays.compactMap { $0.tray.productId })
            for productId in productIds {
                total += packingQuantity(machineId: machine.id, productId: productId)
            }
        }
        return total
    }

    // MARK: - Load Data

    /// Build the list of machines that need refilling from raw machine/tray data.
    ///
    /// Filter logic (mirrors the web app):
    /// 1. A machine is included only if it has at least one empty or below-min-stock tray.
    /// 2. Trays included: empty + below-min-stock + below-fill-threshold
    ///    (fill-when-below trays ride along only when the machine already has a critical tray).
    ///
    /// Static so both `loadData()` and `refreshDuringPacking()` share identical filtering.
    private static func buildRefillMachines(allMachines: [VendingMachine], allTrays: [Tray]) -> [RefillMachine] {
        let traysByMachine = Dictionary(grouping: allTrays, by: { $0.machineId })
        var refillMachines: [RefillMachine] = []

        for machine in allMachines {
            let machineTrays = traysByMachine[machine.id] ?? []

            let hasEmptyOrLow = machineTrays.contains { $0.isEmpty || $0.isBelowMinStock }
            guard hasEmptyOrLow else { continue }

            let refillTrays: [RefillTray] = machineTrays.compactMap { tray in
                guard tray.deficit > 0 else { return nil }
                let isCriticalOrLow = tray.isEmpty || tray.isBelowMinStock
                let isBelowFillThreshold = tray.isBelowFillThreshold
                guard isCriticalOrLow || isBelowFillThreshold else { return nil }
                return RefillTray(tray: tray, fillAmount: tray.deficit)
            }

            guard !refillTrays.isEmpty else { continue }
            refillMachines.append(RefillMachine(machine: machine, trays: refillTrays))
        }

        // Sort by urgency: machines with empty trays first, then by total deficit.
        refillMachines.sort { a, b in
            let aEmpty = a.trays.filter { $0.tray.isEmpty }.count
            let bEmpty = b.trays.filter { $0.tray.isEmpty }.count
            if aEmpty != bEmpty { return aEmpty > bEmpty }
            return a.totalDeficit > b.totalDeficit
        }

        return refillMachines
    }

    /// Re-fetch machines/trays and warehouse stock in response to realtime
    /// sale/tray changes, preserving the user's in-progress packing state.
    ///
    /// Only runs during the **packing** step — that's where the user can
    /// observe deficits and pick products. During review the user is handling
    /// product replacements (unrelated to stock levels). During refill and
    /// summary the tour is already in progress / done, so silently mutating
    /// `machines` would destroy the user's tray-level fill amounts and
    /// `confirmRefill()` already re-fetches fresh stock before writing.
    ///
    /// Preserves: `packedItems`, `customQuantities`, `selectedWarehouseId`,
    /// `currentStep`, `replacements`, `reviewCompleted`.
    /// `isPacked` flags are re-derived from `packedItems` via `syncMachinePackedState()`.
    func refreshDuringPacking() async {
        // Only refresh in packing step — other steps own state we must not trample.
        guard currentStep == .packing else { return }
        // Avoid colliding with an in-progress initial load or save.
        guard !isLoading, !isSaving else { return }

        do {
            let allMachines: [VendingMachine] = try await client
                .from("vendingMachine")
                .select("id, name, location_lat, location_lon, embedded, country_code, embeddeds(id, status, status_at, subdomain, mac_address, firmware_version)")
                .execute()
                .value

            let allTrays: [Tray] = try await client
                .from("machine_trays")
                .select("id, machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, discontinued, sellprice)")
                .order("item_number", ascending: true)
                .execute()
                .value

            self.machines = Self.buildRefillMachines(allMachines: allMachines, allTrays: allTrays)
            self.allTraysByMachine = Dictionary(grouping: allTrays, by: { $0.machineId })

            // `packedItems` keyed by machineId still applies to machines that are
            // still in the list; orphan entries for machines that no longer need
            // refilling are harmless (nothing reads them). Re-derive isPacked
            // flags from the preserved packedItems.
            syncMachinePackedState()

            // Warehouse stock may also have changed (another tour picking,
            // intake, adjustment). Refresh so the pack-quantity caps stay accurate.
            if let warehouseId = selectedWarehouseId {
                await loadWarehouseStock(warehouseId: warehouseId)
            }
        } catch {
            // Silent failure — the user's current UI is still usable, they
            // will just see stale data until the next event.
            print("[RefillWizard] refreshDuringPacking failed: \(error)")
        }

        // Snap-back: if the active machine vanished from chipOrder, drop to .all.
        // Cannot happen in normal mid-tour flow, but cheap insurance against
        // rendering a dangling chip selection.
        if case .machine(let id) = activeChip, !machines.contains(where: { $0.id == id }) {
            activeChip = .all
        }
    }

    /// Refresh the **display-only** tray stock during the refill step.
    ///
    /// Triggered by realtime sale/tray events. Updates each `tray.currentStock`
    /// in place so the user sees live values on the machine card — but
    /// deliberately leaves the rest of the tour state frozen:
    ///
    /// - `fillAmount` stays untouched. The warehouse was FIFO-deducted for
    ///   exactly this amount in `startTour()`; mutating it here would
    ///   desynchronise the warehouse ledger from what the user physically
    ///   packed into their cart.
    /// - `isInTour` stays untouched. A tray that newly crossed its
    ///   threshold mid-tour cannot be inserted, because no warehouse items
    ///   were packed for it.
    /// - Machine composition, order, and `isPacked`/`isRefilled`/`isSkipped`
    ///   flags stay untouched.
    ///
    /// Trays whose `currentStock` actually decreased get added to
    /// `staleStockTrayIds` so the UI can render a "sold during tour"
    /// badge. An increase (another user refilled this tray) is silently
    /// accepted — `confirmRefill` already clamps to capacity.
    func refreshDuringRefill() async {
        guard currentStep == .refill else { return }
        guard !isSaving else { return }

        // Only fetch stock for trays that are actually part of this tour.
        let tourTrayIds: [String] = machines
            .filter { !$0.isRefilled && !$0.isSkipped }
            .flatMap { m in m.trays.filter { $0.isInTour }.map { $0.tray.id.uuidString } }
        guard !tourTrayIds.isEmpty else { return }

        struct StockRow: Decodable {
            let id: UUID
            let currentStock: Int
            enum CodingKeys: String, CodingKey {
                case id
                case currentStock = "current_stock"
            }
        }

        do {
            let rows: [StockRow] = try await client
                .from("machine_trays")
                .select("id, current_stock")
                .in("id", values: tourTrayIds)
                .execute()
                .value

            let freshById: [UUID: Int] = Dictionary(
                uniqueKeysWithValues: rows.map { ($0.id, $0.currentStock) }
            )

            var didChange = false

            for mi in machines.indices {
                // Skip machines the user has already confirmed or skipped.
                guard !machines[mi].isRefilled, !machines[mi].isSkipped else { continue }

                for ti in machines[mi].trays.indices {
                    let rt = machines[mi].trays[ti]
                    guard let fresh = freshById[rt.tray.id] else { continue }
                    guard fresh != rt.tray.currentStock else { continue }
                    didChange = true

                    // Only flag *decreases* — a decrease means a sale happened.
                    // An increase means a competing refill already topped the
                    // tray up; no user attention needed.
                    if fresh < rt.tray.currentStock {
                        staleStockTrayIds.insert(rt.tray.id)
                    }

                    // Rebuild Tray with fresh currentStock only; preserve every
                    // other field. Tray properties are `let`, so we construct
                    // a new instance.
                    let oldTray = rt.tray
                    let newTray = Tray(
                        id: oldTray.id,
                        machineId: oldTray.machineId,
                        itemNumber: oldTray.itemNumber,
                        productId: oldTray.productId,
                        capacity: oldTray.capacity,
                        currentStock: fresh,
                        minStock: oldTray.minStock,
                        fillWhenBelow: oldTray.fillWhenBelow,
                        products: oldTray.products
                    )
                    // Preserve fillAmount and isInTour — the user's choice
                    // is already committed against the warehouse.
                    machines[mi].trays[ti] = RefillTray(
                        tray: newTray,
                        fillAmount: rt.fillAmount,
                        isInTour: rt.isInTour
                    )
                }
            }

            // Persist the live stock into the tour snapshot. `saveTourState()`
            // otherwise only runs at start/confirm/skip, so a resume (real app
            // kill mid-tour) would restore tour-start stock and silently drop
            // any sales that happened since. Only write when something changed.
            if didChange {
                saveTourState()
            }
        } catch {
            print("[RefillWizard] refreshDuringRefill failed: \(error)")
        }
    }

    /// Dispatcher: invoked by the view on every realtime tick and routes to
    /// the step-appropriate refresh (each step-specific method also guards
    /// on `currentStep`, so this is belt-and-braces).
    func refreshFromRealtime() async {
        switch currentStep {
        case .packing:
            await refreshDuringPacking()
        case .refill:
            await refreshDuringRefill()
        case .review, .summary:
            break
        }
    }

    func loadData() async {
        isLoading = true
        error = nil

        do {
            // Fetch machines with embeddeds
            print("[RefillWizard] Fetching machines...")
            let allMachines: [VendingMachine] = try await client
                .from("vendingMachine")
                .select("id, name, location_lat, location_lon, embedded, country_code, embeddeds(id, status, status_at, subdomain, mac_address, firmware_version)")
                .execute()
                .value
            print("[RefillWizard] Fetched \(allMachines.count) machines")

            // Fetch all trays
            print("[RefillWizard] Fetching trays...")
            let allTrays: [Tray] = try await client
                .from("machine_trays")
                .select("id, machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, discontinued, sellprice)")
                .order("item_number", ascending: true)
                .execute()
                .value
            print("[RefillWizard] Fetched \(allTrays.count) trays")

            self.machines = Self.buildRefillMachines(allMachines: allMachines, allTrays: allTrays)
            let traysByMachine = Dictionary(grouping: allTrays, by: { $0.machineId })
            self.allTraysByMachine = traysByMachine

            // Fetch warehouses first (needed for stock-based detection)
            print("[RefillWizard] Fetching warehouses...")
            warehouses = try await client
                .from("warehouses")
                .select("id, name, address, notes, company_id")
                .execute()
                .value
            print("[RefillWizard] Fetched \(warehouses.count) warehouses")

            if let firstWarehouse = warehouses.first {
                selectedWarehouseId = firstWarehouse.id
                await loadWarehouseStock(warehouseId: firstWarehouse.id)
            }

            // Detect trays needing product replacement (only on first load, not after review)
            if !reviewCompleted {
                // Fetch available (active) products for the replacement picker
                let activeProducts: [Product] = try await client
                    .from("products")
                    .select("id, name, image_path, discontinued, sellprice, category")
                    .or("discontinued.is.null,discontinued.eq.false")
                    .order("name", ascending: true)
                    .execute()
                    .value
                self.availableProducts = activeProducts

                // Load categories for the replacement picker's grouping UI.
                // Mirrors ProductsViewModel.loadCategories() — explicit column
                // list and alphabetical order so the decoder is safe against
                // future schema additions.
                let cats: [ProductCategory] = try await client
                    .from("product_category")
                    .select("id, name, company")
                    .order("name", ascending: true)
                    .execute()
                    .value
                self.productCategories = cats

                // Build warehouse stock lookup for "no stock" detection
                let warehouseProductIds = Set(warehouseStock.filter { $0.totalQuantity > 0 }.map(\.productId))

                // Detect expired products: all warehouse batches for a product have expired
                var expiredProductIds: Set<UUID> = []
                if let wId = selectedWarehouseId {
                    let allBatches: [WarehouseStockBatch] = try await client
                        .from("warehouse_stock_batches")
                        .select("id, warehouse_id, product_id, quantity, batch_number, expiration_date")
                        .eq("warehouse_id", value: wId.uuidString)
                        .gt("quantity", value: 0)
                        .execute()
                        .value

                    let today = Self.todayDateString()
                    let batchesByProduct = Dictionary(grouping: allBatches, by: { $0.productId })
                    for (productId, batches) in batchesByProduct {
                        let allHaveExpiry = batches.allSatisfy { $0.expirationDate != nil && !$0.expirationDate!.isEmpty }
                        let allExpired = batches.allSatisfy { batch in
                            guard let expDate = batch.expirationDate, !expDate.isEmpty else { return false }
                            return expDate < today
                        }
                        if allHaveExpiry && allExpired {
                            expiredProductIds.insert(productId)
                        }
                    }
                }

                // Scan ALL trays for replacement candidates
                var suggestions: [ReplacementSuggestion] = []
                var seenTrayIds: Set<UUID> = []

                for machine in allMachines {
                    let machineTrays = traysByMachine[machine.id] ?? []
                    for tray in machineTrays {
                        guard !seenTrayIds.contains(tray.id) else { continue }

                        var reason: ReplacementReason?

                        if let productId = tray.productId {
                            // 1) Discontinued + empty → must replace
                            if tray.isDiscontinued && tray.currentStock == 0 {
                                reason = .discontinued
                            }
                            // 2) Expired product (all warehouse batches expired) → any stock level
                            else if expiredProductIds.contains(productId) {
                                reason = .expired
                            }
                            // 3) No warehouse stock + empty tray → can't refill, suggest replacement
                            else if tray.currentStock == 0 && !warehouseProductIds.contains(productId) {
                                reason = .noStock
                            }
                        } else {
                            // 4) Unassigned tray → user should pick a product
                            reason = .unassigned
                        }

                        if let reason {
                            seenTrayIds.insert(tray.id)
                            suggestions.append(ReplacementSuggestion(
                                trayId: tray.id,
                                machineId: machine.id,
                                machineName: machine.displayName,
                                slotNumber: tray.itemNumber,
                                currentProductId: tray.productId,
                                currentProductName: tray.productName,
                                currentProductImage: tray.products?.imagePath,
                                currentStock: tray.currentStock,
                                reason: reason
                            ))
                        }
                    }
                }

                self.replacements = suggestions
                print("[RefillWizard] Found \(suggestions.count) replacement suggestions")

                // If no replacements needed, skip review step
                if suggestions.isEmpty {
                    currentStep = .packing
                } else {
                    currentStep = .review
                }
            }

            activeChip = .all
        } catch {
            print("[RefillWizard] Error: \(error)")
            self.error = error.localizedDescription
            // Initial load failed and established no state — re-arm the entry
            // gate so returning to the tab retries the load instead of being
            // permanently skipped. (No tour is in memory to protect here.)
            didRunInitialLoad = false
        }

        isLoading = false
    }

    /// Load stock for a specific warehouse. Also loads the physical pick
    /// order (warehouse_product_positions traversed depth-first via
    /// warehouse_position_groups) so the packing step can sort items to match
    /// the warehouse layout.
    func loadWarehouseStock(warehouseId: UUID) async {
        // Fetch physical pick order in parallel with stock. A missing or
        // failing positions fetch must not break stock loading — warehouses
        // without configured positions simply fall back to quantity-based
        // sorting in combinedPackingList.
        async let orderedIdsTask: [UUID] = fetchOrderedProductIdsOrEmpty(warehouseId: warehouseId)

        do {
            let batches: [WarehouseStockBatch] = try await client
                .from("warehouse_stock_batches")
                .select("id, warehouse_id, product_id, quantity, batch_number, expiration_date")
                .eq("warehouse_id", value: warehouseId.uuidString)
                .gt("quantity", value: 0)
                .execute()
                .value

            // Aggregate by product
            var stockMap: [UUID: Int] = [:]
            for batch in batches {
                stockMap[batch.productId, default: 0] += batch.quantity
            }

            // Fetch product details
            let productIds = Array(stockMap.keys)
            if productIds.isEmpty {
                warehouseStock = []
            } else {
                let products: [Product] = try await client
                    .from("products")
                    .select("id, name, image_path, discontinued, sellprice")
                    .in("id", values: productIds.map { $0.uuidString })
                    .execute()
                    .value

                warehouseStock = products.compactMap { product in
                    guard let qty = stockMap[product.id] else { return nil }
                    return WarehouseProductStock(
                        productId: product.id,
                        productName: product.name ?? "Unknown",
                        totalQuantity: qty,
                        imagePath: product.imagePath
                    )
                }.sorted { $0.productName < $1.productName }
            }
        } catch {
            self.error = error.localizedDescription
        }

        // Always apply whatever pick order we got (possibly empty).
        let orderedIds = await orderedIdsTask
        var orderMap: [UUID: Int] = [:]
        orderMap.reserveCapacity(orderedIds.count)
        for (i, pid) in orderedIds.enumerated() {
            orderMap[pid] = i
        }
        warehouseProductOrder = orderMap
    }

    /// Wrapper that swallows errors from fetchOrderedProductIds so stock
    /// loading can run even when the positions fetch fails (e.g. for a
    /// warehouse that has never had its layout configured).
    private func fetchOrderedProductIdsOrEmpty(warehouseId: UUID) async -> [UUID] {
        do {
            return try await fetchOrderedProductIds(warehouseId: warehouseId)
        } catch {
            print("[RefillWizard] fetchOrderedProductIds failed: \(error)")
            return []
        }
    }

    /// Returns product ids in the warehouse's physical pick order:
    /// depth-first through position groups (sorted by `sort_order` at every
    /// level), with any ungrouped positioned products appended at the end.
    /// Mirrors the web's `fetchOrderedProductIds` so iOS produces the same
    /// pack ordering the web UI does.
    private func fetchOrderedProductIds(warehouseId: UUID) async throws -> [UUID] {
        async let groupsResult: [WarehousePositionGroup] = client
            .from("warehouse_position_groups")
            .select("id, parent_id, sort_order")
            .eq("warehouse_id", value: warehouseId.uuidString)
            .order("sort_order", ascending: true)
            .execute()
            .value

        async let positionsResult: [WarehouseProductPosition] = client
            .from("warehouse_product_positions")
            .select("product_id, sort_order, group_id")
            .eq("warehouse_id", value: warehouseId.uuidString)
            .order("sort_order", ascending: true)
            .execute()
            .value

        let groups = try await groupsResult
        let positions = try await positionsResult

        // Use a reference-type node so we can mutate children/productIds via
        // dictionary lookups without fighting Swift's value-type copy rules.
        final class Node {
            let id: UUID
            let parentId: UUID?
            let sortOrder: Int
            var children: [Node] = []
            var productIds: [UUID] = []
            init(_ g: WarehousePositionGroup) {
                self.id = g.id
                self.parentId = g.parentId
                self.sortOrder = g.sortOrder
            }
        }

        var nodeMap: [UUID: Node] = [:]
        nodeMap.reserveCapacity(groups.count)
        for g in groups {
            nodeMap[g.id] = Node(g)
        }

        // Link children to parents; nodes without a known parent are roots.
        var roots: [Node] = []
        for node in nodeMap.values {
            if let parentId = node.parentId, let parent = nodeMap[parentId] {
                parent.children.append(node)
            } else {
                roots.append(node)
            }
        }

        // Sort every level by sort_order.
        func sortChildren(_ node: Node) {
            node.children.sort { $0.sortOrder < $1.sortOrder }
            for child in node.children {
                sortChildren(child)
            }
        }
        roots.sort { $0.sortOrder < $1.sortOrder }
        for root in roots {
            sortChildren(root)
        }

        // Assign positions to their group (or to the ungrouped bucket).
        // `positions` is already ordered by sort_order from the DB query.
        var ungrouped: [UUID] = []
        for p in positions {
            if let groupId = p.groupId, let node = nodeMap[groupId] {
                node.productIds.append(p.productId)
            } else {
                ungrouped.append(p.productId)
            }
        }

        // Depth-first flatten: group products, then recurse into children.
        var result: [UUID] = []
        result.reserveCapacity(positions.count)
        func traverse(_ nodes: [Node]) {
            for node in nodes {
                result.append(contentsOf: node.productIds)
                traverse(node.children)
            }
        }
        traverse(roots)
        result.append(contentsOf: ungrouped)

        return result
    }

    // MARK: - Packing Step Actions

    // MARK: - Review Step Actions

    /// Set a replacement product for a tray.
    func setReplacement(trayId: UUID, productId: UUID) {
        guard let idx = replacements.firstIndex(where: { $0.trayId == trayId }) else { return }
        replacements[idx].replacementProductId = productId
        replacements[idx].isSkipped = false
    }

    /// Skip replacing a specific tray.
    func skipReplacement(trayId: UUID) {
        guard let idx = replacements.firstIndex(where: { $0.trayId == trayId }) else { return }
        replacements[idx].isSkipped = true
        replacements[idx].replacementProductId = nil
    }

    /// Whether all replacements have been handled (replaced or skipped).
    var allReplacementsHandled: Bool {
        replacements.allSatisfy { $0.replacementProductId != nil || $0.isSkipped }
    }

    /// Apply replacements to the database and proceed to packing.
    func applyReplacementsAndContinue() async {
        let toReplace = replacements.filter { $0.replacementProductId != nil }
        guard allReplacementsHandled else { return }

        isSaving = true

        do {
            for suggestion in toReplace {
                guard let newProductId = suggestion.replacementProductId else { continue }
                // Reset current_stock to 0: the old product's stock is irrelevant
                // for the new product, and without this the tray won't show as
                // needing refill in the next steps (deficit would be 0).
                try await client
                    .from("machine_trays")
                    .update([
                        "product_id": AnyJSON.string(newProductId.uuidString),
                        "current_stock": AnyJSON.integer(0),
                    ])
                    .eq("id", value: suggestion.trayId.uuidString)
                    .execute()
            }

            // Reload machine data to reflect the new products
            reviewCompleted = true
            await loadData()
            currentStep = .packing
        } catch {
            self.error = error.localizedDescription
        }

        isSaving = false
    }

    /// Skip all remaining unhandled replacements, then apply any already-chosen replacements and continue.
    func skipReview() async {
        // Mark only unhandled entries as skipped — keep already-chosen replacements
        for idx in replacements.indices {
            if replacements[idx].replacementProductId == nil && !replacements[idx].isSkipped {
                replacements[idx].isSkipped = true
            }
        }
        await applyReplacementsAndContinue()
    }

    /// Navigate back to a previous step (tapped via step indicator).
    func navigateToStep(_ step: RefillStep) {
        guard step.rawValue < currentStep.rawValue else { return }
        if step == .review {
            reviewCompleted = false
        }
        currentStep = step
    }

    func toggleMachinePacked(machineId: UUID) {
        guard let index = machines.firstIndex(where: { $0.id == machineId }) else { return }
        machines[index].isPacked.toggle()
    }

    func packAllMachines() {
        packEverything()
    }

    /// Pack every product needed for one specific machine (stock-aware).
    /// Mirrors `packAllMachines` but scoped — drives the "Pack all for %@"
    /// button shown when the Pack step has a machine chip active.
    ///
    /// Note: does NOT call `saveTourState()`. Consistent with the existing
    /// pack-step helpers (`togglePackedForMachine`, `togglePackedAll`,
    /// `packEverything`/`packAllMachines`) which also skip it. `saveTourState()`
    /// is a no-op during `.packing` anyway (guard at line 361), but matching
    /// the existing pattern keeps future maintenance simple.
    func packAllForMachine(_ machineId: UUID) {
        for item in combinedPackingList {
            guard item.machineNeeds.contains(where: { $0.machineId == machineId }) else { continue }
            guard !isOutOfStockForMachine(machineId: machineId, productId: item.productId) else { continue }
            if !isMachinePacked(machineId: machineId, productId: item.productId) {
                togglePackedForMachine(productId: item.productId, machineId: machineId)
            }
        }
    }

    // MARK: - Step Navigation

    func startTour() async {
        guard packedMachines.count > 0 else { return }

        isSaving = true
        tourId = UUID().uuidString
        tourLog = []
        staleStockTrayIds = []

        // Apply custom packing quantities and mark which trays belong to this tour.
        // Trays for products that were NOT packed get fillAmount = 0 and isInTour = false.
        // Product-less trays always stay in the tour (user refills them manually).
        for mi in machines.indices {
            let machine = machines[mi]
            guard machine.isPacked else {
                // Unpacked machine: exclude all of its trays from the tour display.
                for ti in machines[mi].trays.indices {
                    machines[mi].trays[ti].isInTour = false
                }
                continue
            }
            let packedProductIds = packedItems[machine.id] ?? Set()

            for ti in machines[mi].trays.indices {
                let tray = machines[mi].trays[ti]

                // Product-less trays: always part of the tour (bug 1 follow-through).
                // Keep their initial fillAmount (= deficit) so the user sees something sensible.
                guard let productId = tray.tray.productId else {
                    machines[mi].trays[ti].isInTour = true
                    continue
                }

                // Product trays: in the tour only if the product was packed.
                guard packedProductIds.contains(productId) else {
                    machines[mi].trays[ti].isInTour = false
                    machines[mi].trays[ti].fillAmount = 0
                    continue
                }

                machines[mi].trays[ti].isInTour = true

                // Apply custom quantity if set
                guard let machineCustom = customQuantities[machine.id],
                      let customQty = machineCustom[productId] else { continue }

                // Distribute custom quantity across trays with same product
                let productTrays = machines[mi].trays.enumerated().filter {
                    $0.element.tray.productId == productId && $0.element.deficit > 0
                }
                let totalDeficit = productTrays.reduce(0) { $0 + $1.element.deficit }
                guard totalDeficit > 0 else { continue }

                // Proportional distribution
                let ratio = Double(customQty) / Double(totalDeficit)
                for (idx, pt) in productTrays {
                    let newAmount = Int((Double(pt.deficit) * ratio).rounded())
                    let maxFill = pt.tray.capacity - pt.tray.currentStock
                    machines[mi].trays[idx].fillAmount = max(0, min(maxFill, newAmount))
                }
            }
        }

        // Deduct warehouse stock (FIFO) for all packed products — matches web startTour()
        if let warehouseId = selectedWarehouseId {
            await deductWarehouseStock(warehouseId: warehouseId)
        }

        // Tour-started feed event — written after the warehouse deductions so an
        // aborted start never leaves an orphaned feed entry (spec §3.1). On iOS
        // deduction failures don't block the tour, so this runs on every start.
        // Field-compatible with the PWA's buildTourStartedEntry payload.
        let tourMachines = packedMachines
        var tourMeta: [String: AnyJSON] = [
            "machine_count": .integer(tourMachines.count),
            "machine_ids": .array(tourMachines.map { .string($0.id.uuidString) }),
            "machine_names": .array(tourMachines.map { .string($0.machine.displayName) }),
        ]
        if let warehouseName = warehouses.first(where: { $0.id == selectedWarehouseId })?.name {
            tourMeta["warehouse_name"] = .string(warehouseName)
        }
        await writeActivityLog(machineId: nil, machineName: nil, action: "tour_started", extraMetadata: tourMeta)

        currentMachineIndex = 0
        isSaving = false
        currentStep = .refill
        saveTourState()
    }

    /// Deduct warehouse stock via the `deduct_warehouse_stock_fifo` RPC for each packed product-machine pair.
    private func deductWarehouseStock(warehouseId: UUID) async {
        // Collect deductions from packed machines
        struct Deduction {
            let machineId: UUID
            let productId: UUID
            let quantity: Int
        }

        var deductions: [Deduction] = []
        for machine in machines where machine.isPacked {
            // Only products the user actually ticked off in the packing step.
            // `startTour()` gates tour inclusion on the same `packedItems` set
            // (products left unchecked get `fillAmount = 0` / `isInTour = false`),
            // so deducting every tray product instead billed the warehouse for
            // goods that never left the shelf.
            let trayProductIds = Set(machine.trays.compactMap { $0.tray.productId })
            let productIds = (packedItems[machine.id] ?? []).intersection(trayProductIds)
            for productId in productIds {
                let qty = packingQuantity(machineId: machine.id, productId: productId)
                guard qty > 0 else { continue }
                deductions.append(Deduction(machineId: machine.id, productId: productId, quantity: qty))
            }
        }

        guard !deductions.isEmpty else { return }

        // Get user info for audit trail
        let userId: String? = await {
            try? await client.auth.session.user.id.uuidString
        }()
        let userEmail: String? = await {
            try? await client.auth.session.user.email
        }()

        // Execute deductions (non-blocking errors — warehouse deduction failure shouldn't block the tour)
        for d in deductions {
            do {
                try await client.rpc(
                    "deduct_warehouse_stock_fifo",
                    params: [
                        "p_warehouse_id": AnyJSON.string(warehouseId.uuidString),
                        "p_product_id": AnyJSON.string(d.productId.uuidString),
                        "p_quantity": AnyJSON.integer(d.quantity),
                        "p_user_id": userId.map { AnyJSON.string($0) } ?? AnyJSON.null,
                        "p_reference_id": AnyJSON.string(d.machineId.uuidString),
                        "p_notes": AnyJSON.string("Refill tour"),
                        "p_metadata": AnyJSON.object([
                            "_user_email": userEmail.map { AnyJSON.string($0) } ?? AnyJSON.null,
                            "tour_id": AnyJSON.string(tourId)
                        ])
                    ]
                ).execute()
            } catch {
                print("[RefillWizard] Warehouse deduction failed for product \(d.productId): \(error)")
                // Non-critical: continue tour even if deduction fails
            }
        }
    }

    /// Adjust the fill amount for a tray in the current machine.
    func adjustFillAmount(machineId: UUID, trayId: UUID, amount: Int) {
        guard let mi = machines.firstIndex(where: { $0.id == machineId }),
              let ti = machines[mi].trays.firstIndex(where: { $0.id == trayId }) else { return }

        let tray = machines[mi].trays[ti]
        let maxFill = tray.tray.capacity - tray.tray.currentStock
        machines[mi].trays[ti].fillAmount = max(0, min(maxFill, amount))
    }

    /// Fill a single tray to capacity in the wizard.
    func fillTrayToCapacity(machineId: UUID, trayId: UUID) {
        guard let mi = machines.firstIndex(where: { $0.id == machineId }),
              let ti = machines[mi].trays.firstIndex(where: { $0.id == trayId }) else { return }

        let tray = machines[mi].trays[ti]
        machines[mi].trays[ti].fillAmount = tray.tray.capacity - tray.tray.currentStock
    }

    /// Fill all trays in the current machine to capacity.
    func fillAllTrays(machineId: UUID) {
        guard let mi = machines.firstIndex(where: { $0.id == machineId }) else { return }
        for ti in machines[mi].trays.indices {
            let tray = machines[mi].trays[ti]
            machines[mi].trays[ti].fillAmount = tray.tray.capacity - tray.tray.currentStock
        }
    }

    /// Server response row from the `refill_machine_trays` RPC.
    private struct TrayApplicationResult: Decodable {
        let trayId: UUID
        let oldStock: Int
        let newStock: Int
        let fillAmount: Int
        let wasAlreadyApplied: Bool

        enum CodingKeys: String, CodingKey {
            case trayId             = "tray_id"
            case oldStock           = "old_stock"
            case newStock           = "new_stock"
            case fillAmount         = "fill_amount"
            case wasAlreadyApplied  = "was_already_applied"
        }
    }

    /// Call the atomic `refill_machine_trays` RPC. The server runs all tray
    /// updates in one transaction and dedupes via `(tour_id, tray_id)`, so
    /// safe to retry blindly on network errors.
    private func applyRefillRPC(
        machineId: UUID,
        traysToRefill: [RefillTray]
    ) async throws -> [TrayApplicationResult] {
        let trayPayload: [AnyJSON] = traysToRefill.map { tray in
            AnyJSON.object([
                "tray_id":     .string(tray.tray.id.uuidString),
                "fill_amount": .integer(tray.fillAmount)
            ])
        }
        return try await client.rpc(
            "refill_machine_trays",
            params: [
                "p_machine_id": AnyJSON.string(machineId.uuidString),
                "p_tour_id":    AnyJSON.string(tourId),
                "p_trays":      AnyJSON.array(trayPayload)
            ]
        )
        .execute()
        .value
    }

    /// Confirm refill for the current machine. All tray updates run in one
    /// atomic RPC. On network failures we retry up to 3× with exponential
    /// backoff — the RPC is idempotent, so retries cannot double-apply.
    /// If all attempts fail, the machine is **not** marked refilled and the
    /// wizard stays put, letting the user retry the same machine cleanly.
    func confirmRefill(machineId: UUID) async {
        guard let mi = machines.firstIndex(where: { $0.id == machineId }) else { return }

        let traysToRefill = machines[mi].trays.filter { $0.fillAmount > 0 }

        // No tray needs a stock write — still record the visit so the tour
        // log and audit trail show this machine was opened.
        if traysToRefill.isEmpty {
            await recordRefillSuccess(
                mi: mi,
                machineId: machineId,
                traysSnapshot: [],
                traysCount: 0,
                itemsAdded: 0
            )
            return
        }

        isSaving = true
        defer { isSaving = false }

        let backoffSeconds: [Double] = [1.0, 3.0]   // before attempts 2 and 3
        var lastError: Error?

        for attempt in 1...3 {
            do {
                let results = try await applyRefillRPC(
                    machineId: machineId,
                    traysToRefill: traysToRefill
                )

                // Mirror server-authoritative stock values into local state.
                for r in results {
                    guard let ti = machines[mi].trays.firstIndex(where: { $0.tray.id == r.trayId }) else { continue }
                    let oldTray = machines[mi].trays[ti].tray
                    let newTray = Tray(
                        id: oldTray.id,
                        machineId: oldTray.machineId,
                        itemNumber: oldTray.itemNumber,
                        productId: oldTray.productId,
                        capacity: oldTray.capacity,
                        currentStock: r.newStock,
                        minStock: oldTray.minStock,
                        fillWhenBelow: oldTray.fillWhenBelow,
                        products: oldTray.products
                    )
                    machines[mi].trays[ti] = RefillTray(
                        tray: newTray,
                        fillAmount: machines[mi].trays[ti].fillAmount,
                        isInTour: machines[mi].trays[ti].isInTour
                    )
                }

                let itemsAdded = results.reduce(0) { $0 + max(0, $1.newStock - $1.oldStock) }
                await recordRefillSuccess(
                    mi: mi,
                    machineId: machineId,
                    traysSnapshot: traysToRefill,
                    traysCount: results.count,
                    itemsAdded: itemsAdded
                )
                return
            } catch {
                lastError = error
                print("[RefillWizard] confirmRefill attempt \(attempt)/3 failed: \(error)")
                if attempt < 3 {
                    let delaySeconds = backoffSeconds[attempt - 1]
                    try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
                }
            }
        }

        // All 3 attempts failed. Leave machine in the tour so the user can retry.
        self.error = "Refill could not be saved after 3 attempts: \(lastError?.localizedDescription ?? "unknown error"). Please try again."
    }

    /// Shared post-success bookkeeping: tour log, activity log, advance.
    private func recordRefillSuccess(
        mi: Int,
        machineId: UUID,
        traysSnapshot: [RefillTray],
        traysCount: Int,
        itemsAdded: Int
    ) async {
        machines[mi].isRefilled = true

        tourLog.append(TourLogEntry(
            machineId: machineId,
            machineName: machines[mi].machine.displayName,
            traysRefilled: traysCount,
            totalAdded: itemsAdded,
            skipped: false
        ))

        await writeActivityLog(
            machineId: machineId,
            machineName: machines[mi].machine.displayName,
            action: "stock_refill_tour",
            extraMetadata: [
                "trays_refilled": .integer(traysCount),
                "total_added": .integer(itemsAdded),
                "products": .array(traysSnapshot.map { tray in
                    AnyJSON.object([
                        "product_id": tray.tray.productId.map { .string($0.uuidString) } ?? .null,
                        "product_name": .string(tray.tray.productName),
                        "quantity": .integer(tray.fillAmount)
                    ])
                })
            ]
        )

        advanceToNextMachine()
        saveTourState()
    }

    /// Skip the current machine.
    func skipMachine(machineId: UUID) async {
        guard let mi = machines.firstIndex(where: { $0.id == machineId }) else { return }
        machines[mi].isSkipped = true

        // Record in tour log
        tourLog.append(TourLogEntry(
            machineId: machineId,
            machineName: machines[mi].machine.displayName,
            traysRefilled: 0,
            totalAdded: 0,
            skipped: true
        ))

        // Write skip activity log entry (non-blocking)
        await writeActivityLog(
            machineId: machineId,
            machineName: machines[mi].machine.displayName,
            action: "stock_refill_tour_skip",
            extraMetadata: [:]
        )

        advanceToNextMachine()
        saveTourState()
    }

    private func advanceToNextMachine() {
        let remaining = machines.filter { $0.isPacked && !$0.isRefilled && !$0.isSkipped }
        if remaining.isEmpty {
            currentStep = .summary
        }
        // currentMachineIndex stays at 0 since we always look at the first remaining
        currentMachineIndex = 0
    }

    // MARK: - Activity Log

    /// Write an activity log entry for a refill/skip action. Non-critical — failures are silently logged.
    private func writeActivityLog(machineId: UUID?, machineName: String?, action: String, extraMetadata: [String: AnyJSON]) async {
        do {
            let session = try await client.auth.session
            let user = session.user
            let firstName = user.userMetadata["first_name"]?.stringValue
            let lastName = user.userMetadata["last_name"]?.stringValue
            let fullName = [firstName, lastName].compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
            let userDisplay = fullName.isEmpty ? user.email : fullName

            // Fetch company_id from organization_members
            struct OrgMember: Decodable { let companyId: UUID; enum CodingKeys: String, CodingKey { case companyId = "company_id" } }
            let members: [OrgMember] = try await client
                .from("organization_members")
                .select("company_id")
                .eq("user_id", value: user.id.uuidString)
                .limit(1)
                .execute()
                .value
            guard let companyId = members.first?.companyId else { return }

            var metadata: [String: AnyJSON] = [
                "tour_id": .string(tourId),
                "_user_email": user.email.map { .string($0) } ?? .null,
                "_user_display": userDisplay.map { .string($0) } ?? .null,
            ]
            if let machineId { metadata["machine_id"] = .string(machineId.uuidString) }
            if let machineName { metadata["machine_name"] = .string(machineName) }
            if let warehouseId = selectedWarehouseId {
                metadata["warehouse_id"] = .string(warehouseId.uuidString)
            }
            for (key, value) in extraMetadata {
                metadata[key] = value
            }

            try await client
                .from("activity_log")
                .insert([
                    "company_id": AnyJSON.string(companyId.uuidString),
                    "user_id": AnyJSON.string(user.id.uuidString),
                    "entity_type": AnyJSON.string("stock"),
                    "entity_id": AnyJSON.string(machineId?.uuidString ?? tourId),
                    "action": AnyJSON.string(action),
                    "metadata": AnyJSON.object(metadata)
                ])
                .execute()
        } catch {
            print("[RefillWizard] Activity log write failed: \(error)")
            // Non-critical — don't block the refill flow
        }
    }

    // MARK: - Cash book integration

    /// Set of machine IDs visited during this tour (non-skipped).
    var visitedMachineIds: Set<UUID> {
        Set(tourLog.filter { !$0.skipped }.map { $0.machineId })
    }

    /// Resolves which Barkassen need cash collection from this tour. Issues
    /// one RPC per candidate Barkasse (typically 0–2). Does NOT mutate the
    /// VM's `theoreticalCash` — uses `fetchTheoreticalCash(for:)` for an
    /// isolated read.
    func resolveTourCash(using cashBookVM: CashBookViewModel) async -> TourCashResolution {
        let visited = visitedMachineIds
        let candidates = cashBookVM.barkassenForVisitedMachines(visited)
        var withCash: [CashBook] = []
        var cashMap: [UUID: Double] = [:]
        for cb in candidates {
            guard let tc = await cashBookVM.fetchTheoreticalCash(for: cb.id) else { continue }
            // Scope the expected cash to the machines actually visited on this
            // tour. `cashSalesSince` would count machines on the same Barkasse
            // that were never touched, so a partial tour would imply the
            // operator emptied all of them.
            let scoped = tc.expectedCash(forMachines: visited)
            if scoped > 0.001 {
                withCash.append(cb)
                cashMap[cb.id] = scoped
            }
        }
        return TourCashResolution(barkassen: withCash, cashByCashBookId: cashMap)
    }

    // MARK: - Reset

    func reset() {
        currentStep = .review
        replacements = []
        reviewCompleted = false
        currentMachineIndex = 0
        tourLog = []
        tourId = ""
        hasSavedTour = false
        packedItems = [:]
        customQuantities = [:]
        staleStockTrayIds = []
        // Re-arm the entry gate so the next Refill-tab appearance reloads
        // fresh machine data for a brand-new tour instead of being skipped.
        didRunInitialLoad = false
        Self.clearSavedTour()
        for i in machines.indices {
            machines[i].isPacked = false
            machines[i].isRefilled = false
            machines[i].isSkipped = false
            for j in machines[i].trays.indices {
                machines[i].trays[j].fillAmount = machines[i].trays[j].tray.deficit
            }
        }
    }
}

// MARK: - TourCashResolution

/// Result of resolving Barkassen-with-cash for a refill tour. Used by
/// `RefillSummaryView` to drive both the multi-Barkasse block and the
/// single-Barkasse auto-sheet.
struct TourCashResolution {
    let barkassen: [CashBook]                // those with cashSalesSince > 0
    let cashByCashBookId: [UUID: Double]     // map for O(1) lookup in the UI
}
