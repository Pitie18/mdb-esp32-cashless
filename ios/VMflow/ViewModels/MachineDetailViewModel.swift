import Foundation
import Supabase

/// Drives the machine detail view: overview, sales history, and tray data.
@MainActor
final class MachineDetailViewModel: ObservableObject {
    @Published var machine: VendingMachine
    @Published var stats: MachineStats
    @Published var trays: [Tray] = []
    @Published var recentSales: [Sale] = []
    @Published var products: [Product] = []
    @Published var suppressedSales: [SuppressedSale] = []
    @Published var isLoading = false
    @Published var error: String?

    private let client = SupabaseService.shared.client

    init(machine: VendingMachine, stats: MachineStats) {
        self.machine = machine
        self.stats = stats
    }

    // MARK: - Load Detail

    func loadDetail() async {
        isLoading = true
        error = nil

        do {
            async let traysTask: () = loadTrays()
            async let salesTask: () = loadSales()
            async let productsTask: () = loadProducts()

            _ = try await (traysTask, salesTask, productsTask)
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }

        // Best-effort supplemental load — never blocks or fails the main detail load.
        try? await loadSuppressedSales()

        isLoading = false
    }

    // MARK: - Trays

    private func loadTrays() async throws {
        trays = try await client
            .from("machine_trays")
            .select("id, machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, discontinued, sellprice)")
            .eq("machine_id", value: machine.id.uuidString)
            .order("item_number", ascending: true)
            .execute()
            .value
    }

    // MARK: - Sales

    private func loadSales() async throws {
        recentSales = try await client
            .from("sales")
            .select("id, created_at, item_price, item_number, machine_id, embedded_id, channel, product_id, products(name, image_path)")
            .eq("machine_id", value: machine.id.uuidString)
            .order("created_at", ascending: false)
            .limit(50)
            .execute()
            .value
    }

    // MARK: - Products (for tray editing)

    private func loadProducts() async throws {
        products = try await client
            .from("products")
            .select("id, name, image_path, discontinued, sellprice")
            .eq("discontinued", value: false)
            .order("name", ascending: true)
            .execute()
            .value
    }

    // MARK: - Suppressed sales (auto-removed brownout duplicates)

    private func loadSuppressedSales() async throws {
        guard let embeddedId = machine.embedded?.uuidString else {
            suppressedSales = []   // no embedded device → no suppressed sales possible
            return
        }
        suppressedSales = try await client
            .from("suppressed_sales")
            .select("id, embedded_id, item_number, item_price, channel, sale_seq, device_created_at, received_at, matched_sale_id, reason, product_id, products(name, image_path), matched:sales!matched_sale_id(created_at)")
            .eq("embedded_id", value: embeddedId)
            .order("received_at", ascending: false)
            .limit(100)
            .execute()
            .value
    }

    // MARK: - Restore suppressed sale

    /// Promote an auto-removed (suppressed) sale back into a real sale via the
    /// restore_suppressed_sale RPC, then reload so it leaves Duplicates and
    /// appears under Sales (stock −1). Admin-only; the RPC enforces it too.
    func restoreSuppressed(_ id: UUID) async {
        struct Params: Encodable { let p_suppressed_id: UUID }
        do {
            try await client
                .rpc("restore_suppressed_sale", params: Params(p_suppressed_id: id))
                .execute()
            await loadDetail()   // refreshes trays, sales, AND suppressedSales
        } catch is CancellationError {
            // Ignore — SwiftUI cancels routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Stock Actions

    /// Adjust stock for a tray by a delta amount.
    func adjustStock(tray: Tray, delta: Int) async {
        let newStock = max(0, min(tray.capacity, tray.currentStock + delta))
        guard newStock != tray.currentStock else { return }

        do {
            try await client
                .from("machine_trays")
                .update(["current_stock": newStock])
                .eq("id", value: tray.id.uuidString)
                .execute()

            // Reload trays
            try await loadTrays()
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Fill a tray to its capacity.
    func fillTray(_ tray: Tray) async {
        do {
            try await client
                .from("machine_trays")
                .update(["current_stock": tray.capacity])
                .eq("id", value: tray.id.uuidString)
                .execute()

            try await loadTrays()
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Send Credit

    /// Sends free credit to the machine's device via the `send-credit` edge
    /// function (MQTT-published, XOR-encrypted like all device payloads). No
    /// sale is recorded here — the device reports the actual vend over MQTT
    /// when it happens, same as any coin/card payment.
    func sendCredit(amount: Double) async -> Bool {
        guard let embeddedId = machine.embedded else {
            error = String(localized: "This machine has no linked device.")
            return false
        }
        struct Body: Encodable {
            let device_id: String
            let amount: Double
        }
        struct Response: Decodable { let status: String? }
        do {
            let _: Response = try await client.functions.invoke(
                "send-credit",
                options: .init(body: Body(device_id: embeddedId.uuidString, amount: amount))
            )
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    // MARK: - Settings

    /// Persists machine settings edited via `MachineSettingsSheet` (location,
    /// address, country, Nayax ID, public listing). Mirrors the web's
    /// `updateMachineSettings` — a single update on `vendingMachine`.
    func updateSettings(
        locationLat: Double?, locationLon: Double?,
        addressStreet: String?, addressHouseNumber: String?, addressPostalCode: String?,
        addressCity: String?, formattedAddress: String?,
        countryCode: String?, nayaxMachineId: String?, publicListing: Bool
    ) async -> Bool {
        struct Patch: Encodable {
            let location_lat: Double?
            let location_lon: Double?
            let address_street: String?
            let address_house_number: String?
            let address_postal_code: String?
            let address_city: String?
            let formatted_address: String?
            let country_code: String?
            let nayax_machine_id: String?
            let public_listing: Bool
        }
        do {
            try await client
                .from("vendingMachine")
                .update(Patch(
                    location_lat: locationLat, location_lon: locationLon,
                    address_street: addressStreet, address_house_number: addressHouseNumber,
                    address_postal_code: addressPostalCode, address_city: addressCity,
                    formatted_address: formattedAddress, country_code: countryCode,
                    nayax_machine_id: nayaxMachineId, public_listing: publicListing
                ))
                .eq("id", value: machine.id.uuidString)
                .execute()

            // VendingMachine has no mutating setters — memberwise-reconstruct it
            // with the new values so the view reflects the save immediately.
            machine = VendingMachine(
                id: machine.id, name: machine.name,
                locationLat: locationLat, locationLon: locationLon,
                embedded: machine.embedded, countryCode: countryCode, embeddeds: machine.embeddeds,
                addressStreet: addressStreet, addressHouseNumber: addressHouseNumber,
                addressPostalCode: addressPostalCode, addressCity: addressCity,
                formattedAddress: formattedAddress, nayaxMachineId: nayaxMachineId,
                publicListing: publicListing,
                linkedSelections: machine.linkedSelections
            )
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    // MARK: - Revenue Helpers

    /// Today's revenue for this machine.
    var todayRevenue: Double {
        let startOfToday = Calendar.current.startOfDay(for: Date())
        return recentSales
            .filter { $0.createdAt >= startOfToday }
            .reduce(0) { $0 + ($1.itemPrice ?? 0) }
    }

    /// Formatted stock health summary. Counts **products** (all slots of a
    /// product summed, see `MachineStockHealth`), not slots — matching the
    /// machine card.
    var stockSummary: String {
        let groups = MachineStockHealth.groupTraysByProduct(trays).filter(\.needsRefill)
        let empty = groups.filter { $0.state == .critical }.count
        let low = groups.filter { $0.state == .low }.count

        if empty > 0 { return String(localized: "\(empty) empty, \(low) low") }
        if low > 0 { return String(localized: "\(low) low") }
        return String(localized: "All good")
    }
}
