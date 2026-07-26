import Foundation
import Supabase

/// Drives the Warehouse management page: stock overview and intake booking.
@MainActor
final class WarehouseViewModel: ObservableObject {
    // MARK: - Published State

    @Published var warehouses: [Warehouse] = []
    @Published var selectedWarehouseId: UUID?
    @Published var productSummaries: [WarehouseProductSummary] = []
    @Published var recentIntakes: [IntakeEntry] = []
    // Batch drilldown state (for ProductBatchesView)
    @Published var drilldownBatches: [WarehouseStockBatch] = []
    @Published var isLoadingBatches = false
    @Published var isAdjustingBatch = false
    @Published var products: [Product] = []  // All non-discontinued products for intake picker
    @Published var isLoading = false
    @Published var error: String?
    @Published var searchText = ""

    // Stock list filters (parity with management frontend)
    @Published var includeOutOfStock = false
    @Published var includeArchived = false
    @Published var expirationFilter: ExpirationFilterOption = .all

    /// Filter options for the stock list's expiration severity.
    enum ExpirationFilterOption: String, CaseIterable, Identifiable {
        case all, expiringSoon, critical
        var id: String { rawValue }
        var label: String {
            switch self {
            case .all: return String(localized: "All")
            case .expiringSoon: return String(localized: "Expiring soon")
            case .critical: return String(localized: "Critical / expired")
            }
        }
    }

    /// True when any filter deviates from the default (active in-stock) view.
    var hasActiveFilters: Bool {
        includeOutOfStock || includeArchived || expirationFilter != .all
    }

    // Intake form state
    @Published var intakeProductId: UUID?
    @Published var intakeQuantity: Int = 1
    @Published var intakeBatchNumber: String = ""
    /// Best-before date as the user-facing masked text (e.g. "15.06.26").
    /// Empty means no expiry; parsed to `yyyy-MM-dd` at submit time.
    @Published var intakeExpirationText: String = ""
    @Published var isBookingIntake = false
    /// Supplier for the batch being booked. Prefilled from the product's most
    /// recent purchase price (see `prefillSupplier(for:)`) but editable — a
    /// product's actual supplier can vary between deliveries, and tracking the
    /// real one per batch matters for traceability (e.g. investigating a
    /// quality issue back to a specific delivery).
    @Published var intakeSupplierId: UUID?
    @Published var intakeSupplierName: String = ""
    @Published var suppliers: [Supplier] = []

    private let client = SupabaseService.shared.client

    // MARK: - Computed

    /// Product summaries after search + filters, sorted: archived last, then
    /// out of stock first, then low, then by name.
    var filteredSummaries: [WarehouseProductSummary] {
        var items = productSummaries

        if !searchText.isEmpty {
            let query = searchText.lowercased()
            items = items.filter { $0.productName.lowercased().contains(query) }
        }
        if !includeArchived {
            items = items.filter { !$0.discontinued }
        }
        if !includeOutOfStock {
            items = items.filter { !$0.isOutOfStock }
        }
        switch expirationFilter {
        case .all:
            break
        case .expiringSoon:
            items = items.filter { $0.expirationStatus == .warning || $0.expirationStatus == .critical }
        case .critical:
            items = items.filter { $0.expirationStatus == .critical }
        }

        return items.sorted { lhs, rhs in
            if lhs.discontinued != rhs.discontinued { return !lhs.discontinued }
            if lhs.isOutOfStock != rhs.isOutOfStock { return lhs.isOutOfStock }
            if lhs.isLow != rhs.isLow { return lhs.isLow }
            return lhs.productName.localizedCaseInsensitiveCompare(rhs.productName) == .orderedAscending
        }
    }

    // MARK: - Load Warehouses

    func loadWarehouses() async {
        do {
            let result: [Warehouse] = try await client
                .from("warehouses")
                .select("id, name, address, notes, company_id")
                .order("name")
                .execute()
                .value

            warehouses = result

            // Auto-select first warehouse if none selected
            if selectedWarehouseId == nil, let first = result.first {
                selectedWarehouseId = first.id
            }
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Load Product Summaries

    /// Builds a summary for EVERY product (incl. zero-stock and discontinued),
    /// merged with the selected warehouse's batches. Mirrors the management
    /// frontend's `fetchProductSummaries` so out-of-stock / expired / archived
    /// products can be surfaced via the stock-list filters.
    func loadProductSummaries() async {
        guard let warehouseId = selectedWarehouseId else {
            productSummaries = []
            return
        }

        do {
            struct ProductRow: Decodable {
                let id: UUID
                let name: String?
                let imagePath: String?
                let discontinued: Bool?

                enum CodingKeys: String, CodingKey {
                    case id, name, discontinued
                    case imagePath = "image_path"
                }
            }
            struct BatchRow: Decodable {
                let productId: UUID
                let quantity: Int
                let expirationDate: String?

                enum CodingKeys: String, CodingKey {
                    case quantity
                    case productId = "product_id"
                    case expirationDate = "expiration_date"
                }
            }

            async let productsRes: [ProductRow] = client
                .from("products")
                .select("id, name, image_path, discontinued")
                .order("name")
                .execute()
                .value
            async let batchesRes: [BatchRow] = client
                .from("warehouse_stock_batches")
                .select("product_id, quantity, expiration_date")
                .eq("warehouse_id", value: warehouseId.uuidString)
                .gt("quantity", value: 0)
                .execute()
                .value

            let (productRows, batchRows) = try await (productsRes, batchesRes)

            // Aggregate batches per product
            var stock: [UUID: (total: Int, count: Int, earliest: String?)] = [:]
            for batch in batchRows {
                var entry = stock[batch.productId] ?? (total: 0, count: 0, earliest: nil)
                entry.total += batch.quantity
                entry.count += 1
                if let exp = batch.expirationDate {
                    if let current = entry.earliest {
                        if exp < current { entry.earliest = exp }
                    } else {
                        entry.earliest = exp
                    }
                }
                stock[batch.productId] = entry
            }

            // Every product gets a summary (even with 0 stock)
            productSummaries = productRows.map { p in
                let s = stock[p.id]
                return WarehouseProductSummary(
                    productId: p.id,
                    productName: p.name ?? "Unknown",
                    imagePath: p.imagePath,
                    totalQuantity: s?.total ?? 0,
                    batchCount: s?.count ?? 0,
                    earliestExpiration: s?.earliest,
                    discontinued: p.discontinued ?? false,
                    expirationStatus: WarehouseProductSummary.expirationStatus(for: s?.earliest)
                )
            }
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Load Products (for intake picker)

    func loadProducts() async {
        do {
            let result: [Product] = try await client
                .from("products")
                .select("id, name, image_path, discontinued, sellprice")
                .or("discontinued.is.null,discontinued.eq.false")
                .order("name")
                .execute()
                .value

            products = result
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Load Recent Intakes

    func loadRecentIntakes() async {
        guard let warehouseId = selectedWarehouseId else {
            recentIntakes = []
            return
        }

        do {
            struct TransactionWithProduct: Codable {
                let id: UUID
                let productId: UUID
                let quantityChange: Int
                let createdAt: Date
                let notes: String?
                let products: TransProduct?
                let suppliers: TransSupplier?

                struct TransProduct: Codable {
                    let name: String?
                    let imagePath: String?

                    enum CodingKeys: String, CodingKey {
                        case name
                        case imagePath = "image_path"
                    }
                }
                struct TransSupplier: Codable { let name: String }

                enum CodingKeys: String, CodingKey {
                    case id, notes, products, suppliers
                    case productId = "product_id"
                    case quantityChange = "quantity_change"
                    case createdAt = "created_at"
                }
            }

            let transactions: [TransactionWithProduct] = try await client
                .from("warehouse_transactions")
                .select("id, product_id, quantity_change, created_at, notes, products(name, image_path), suppliers(name)")
                .eq("warehouse_id", value: warehouseId.uuidString)
                .eq("transaction_type", value: "intake")
                .order("created_at", ascending: false)
                .limit(10)
                .execute()
                .value

            recentIntakes = transactions.map { tx in
                IntakeEntry(
                    id: tx.id,
                    productId: tx.productId,
                    productName: tx.products?.name ?? "Unknown",
                    imagePath: tx.products?.imagePath,
                    quantity: tx.quantityChange,
                    batchNumber: nil,
                    expirationDate: nil,
                    supplierName: tx.suppliers?.name,
                    createdAt: tx.createdAt
                )
            }
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Suppliers (intake form)

    func loadSuppliersForIntake() async {
        do {
            suppliers = try await client.from("suppliers")
                .select("id, name").order("name", ascending: true).execute().value
        } catch is CancellationError {} catch { /* non-critical for the intake form */ }
    }

    /// Prefills the intake supplier from the product's most recently recorded
    /// purchase price, if any — a convenience default the user can still change,
    /// since the actual supplier of a given delivery can differ.
    func prefillSupplier(for productId: UUID) async {
        struct Row: Decodable {
            let supplierId: UUID
            let suppliers: SupplierName?
            struct SupplierName: Decodable { let name: String }
            enum CodingKeys: String, CodingKey { case supplierId = "supplier_id", suppliers }
        }
        do {
            let rows: [Row] = try await client.from("product_purchase_prices")
                .select("supplier_id, suppliers(name)")
                .eq("product_id", value: productId.uuidString)
                .order("observed_on", ascending: false)
                .order("created_at", ascending: false)
                .limit(1)
                .execute().value
            if let r = rows.first, let name = r.suppliers?.name {
                intakeSupplierId = r.supplierId
                intakeSupplierName = name
            } else {
                intakeSupplierId = nil
                intakeSupplierName = ""
            }
        } catch {
            intakeSupplierId = nil
            intakeSupplierName = ""
        }
    }

    /// Finds an existing supplier by case-insensitive name match, or creates one.
    /// Used when the user types a name in the intake supplier picker that isn't
    /// in the list yet.
    func resolveOrCreateSupplier(named name: String) async -> Supplier? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let existing = suppliers.first(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }) {
            return existing
        }
        guard let warehouseId = selectedWarehouseId,
              let companyId = warehouses.first(where: { $0.id == warehouseId })?.companyId else { return nil }
        struct NewSupplier: Encodable { let name: String; let companyId: UUID
            enum CodingKeys: String, CodingKey { case name; case companyId = "company_id" }
        }
        do {
            let inserted: [Supplier] = try await client.from("suppliers")
                .insert(NewSupplier(name: trimmed, companyId: companyId))
                .select("id, name").execute().value
            if let s = inserted.first {
                suppliers.append(s)
                suppliers.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                return s
            }
            return nil
        } catch {
            // Likely a race with another client creating the same name concurrently
            // (unique constraint) — re-fetch and match instead of failing outright.
            await loadSuppliersForIntake()
            return suppliers.first(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame })
        }
    }

    // MARK: - Load All

    func loadAll() async {
        isLoading = true
        error = nil

        await loadWarehouses()
        // These depend on selectedWarehouseId being set
        async let summariesTask: () = loadProductSummaries()
        async let intakesTask: () = loadRecentIntakes()
        async let productsTask: () = loadProducts()
        async let suppliersTask: () = loadSuppliersForIntake()

        _ = await (summariesTask, intakesTask, productsTask, suppliersTask)

        isLoading = false
    }

    // MARK: - Warehouse Selection

    func selectWarehouse(_ id: UUID) async {
        selectedWarehouseId = id
        async let summariesTask: () = loadProductSummaries()
        async let intakesTask: () = loadRecentIntakes()
        _ = await (summariesTask, intakesTask)
    }

    // MARK: - Barcode Lookup

    /// Look up a product by barcode in the `product_barcodes` table.
    /// Returns the matching product_id or nil if not found.
    func lookupBarcode(_ barcode: String) async -> UUID? {
        do {
            struct BarcodeResult: Decodable {
                let productId: UUID
                enum CodingKeys: String, CodingKey { case productId = "product_id" }
            }
            let results: [BarcodeResult] = try await client
                .from("product_barcodes")
                .select("product_id")
                .eq("barcode", value: barcode)
                .limit(1)
                .execute()
                .value

            return results.first?.productId
        } catch {
            print("[Warehouse] Barcode lookup failed: \(error)")
            return nil
        }
    }

    // MARK: - Book Intake

    func bookIntake(productId: UUID, quantity: Int, batchNumber: String?, expirationDate: String?, supplierId: UUID? = nil) async {
        guard let warehouseId = selectedWarehouseId,
              let companyId = warehouses.first(where: { $0.id == warehouseId })?.companyId else { return }

        isBookingIntake = true
        error = nil

        do {
            // 1. Insert new stock batch
            let newBatch = InsertStockBatch(
                warehouseId: warehouseId,
                productId: productId,
                quantity: quantity,
                batchNumber: batchNumber,
                expirationDate: expirationDate,
                companyId: companyId,
                supplierId: supplierId
            )

            let insertedBatches: [InsertedBatchResponse] = try await client
                .from("warehouse_stock_batches")
                .insert(newBatch)
                .select("id")
                .execute()
                .value

            let batchId = insertedBatches.first?.id

            // 2. Insert warehouse transaction
            let userId = try await client.auth.session.user.id

            let transaction = InsertWarehouseTransaction(
                warehouseId: warehouseId,
                productId: productId,
                transactionType: "intake",
                quantityChange: quantity,
                userId: userId,
                batchId: batchId,
                notes: batchNumber.flatMap { $0.isEmpty ? nil : "Batch: \($0)" },
                companyId: companyId,
                quantityBefore: nil,
                quantityAfter: nil,
                batchNumber: batchNumber,
                expirationDate: expirationDate,
                supplierId: supplierId
            )

            try await client
                .from("warehouse_transactions")
                .insert(transaction)
                .execute()

            // 3. Reset form
            intakeProductId = nil
            intakeQuantity = 1
            intakeBatchNumber = ""
            intakeExpirationText = ""
            intakeSupplierId = nil
            intakeSupplierName = ""

            // 4. Reload data
            async let summariesTask: () = loadProductSummaries()
            async let intakesTask: () = loadRecentIntakes()
            _ = await (summariesTask, intakesTask)
        } catch is CancellationError {
            // Ignore — SwiftUI cancels refreshable tasks routinely
        } catch {
            self.error = error.localizedDescription
        }

        isBookingIntake = false
    }

    // MARK: - Batch drilldown

    /// Allowed reasons for `adjustBatch`. String-backed so the raw value maps directly to
    /// the `warehouse_transactions.transaction_type` column. Mirrors the TypeScript
    /// reason union in `management-frontend/app/composables/useWarehouse.ts:439`.
    enum AdjustReason: String {
        case refillReturn = "adjustment_refill_return"
        case correction = "adjustment_correction"
        case damage = "adjustment_damage"
        case expired = "adjustment_expired"
    }

    /// Loads all non-empty batches for a specific product in the current warehouse,
    /// ordered by expiration date ascending (oldest first).
    /// Reuses the existing `WarehouseStockBatch` model from `Models/Warehouse.swift`.
    func loadBatchesForProduct(_ productId: UUID) async {
        guard let warehouseId = selectedWarehouseId else {
            drilldownBatches = []
            return
        }

        // Clear previous drilldown state so re-visiting a different product
        // doesn't briefly render stale rows while the new query runs.
        drilldownBatches = []
        isLoadingBatches = true
        defer { isLoadingBatches = false }

        do {
            let batches: [WarehouseStockBatch] = try await client
                .from("warehouse_stock_batches")
                .select("id, warehouse_id, product_id, quantity, batch_number, expiration_date, supplier_id")
                .eq("warehouse_id", value: warehouseId.uuidString)
                .eq("product_id", value: productId.uuidString)
                .gt("quantity", value: 0)
                .order("expiration_date", ascending: true)
                .execute()
                .value

            drilldownBatches = batches
        } catch is CancellationError {
            // SwiftUI cancels refreshable tasks routinely — ignore
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Adjust the quantity of a specific batch by a signed delta.
    ///
    /// `reason` is constrained to the `AdjustReason` enum so the transaction_type
    /// column only ever gets one of the four adjustment_* strings. `intake`
    /// (iOS Wareneingang) and `incoming` (web legacy) are deliberately excluded
    /// from this enum — they belong to the Wareneingang flow, not adjustments.
    ///
    /// Quantity is clamped at zero so concurrent sales can't produce negative stock.
    /// On success, reloads batches + product summaries so callers see fresh data.
    func adjustBatch(
        batchId: UUID,
        quantityChange: Int,
        reason: AdjustReason,
        notes: String?
    ) async {
        guard let warehouseId = selectedWarehouseId,
              let companyId = warehouses.first(where: { $0.id == warehouseId })?.companyId else {
            return
        }

        isAdjustingBatch = true
        error = nil
        defer { isAdjustingBatch = false }

        do {
            // 1. Fetch current batch to get quantity_before + product_id
            struct CurrentBatch: Decodable {
                let productId: UUID
                let quantity: Int
                let batchNumber: String?
                let expirationDate: String?
                let supplierId: UUID?

                enum CodingKeys: String, CodingKey {
                    case quantity
                    case productId = "product_id"
                    case batchNumber = "batch_number"
                    case expirationDate = "expiration_date"
                    case supplierId = "supplier_id"
                }
            }

            let current: CurrentBatch = try await client
                .from("warehouse_stock_batches")
                .select("product_id, quantity, batch_number, expiration_date, supplier_id")
                .eq("id", value: batchId.uuidString)
                .single()
                .execute()
                .value

            let quantityBefore = current.quantity
            let quantityAfter = max(0, quantityBefore + quantityChange)

            // 2. Update batch quantity
            struct BatchUpdate: Encodable { let quantity: Int }
            try await client
                .from("warehouse_stock_batches")
                .update(BatchUpdate(quantity: quantityAfter))
                .eq("id", value: batchId.uuidString)
                .execute()

            // 3. Insert transaction row (web-parity: includes before/after + batch metadata)
            let userId = try await client.auth.session.user.id
            let transaction = InsertWarehouseTransaction(
                warehouseId: warehouseId,
                productId: current.productId,
                transactionType: reason.rawValue,
                quantityChange: quantityChange,
                userId: userId,
                batchId: batchId,
                notes: (notes?.isEmpty ?? true) ? nil : notes,
                companyId: companyId,
                quantityBefore: quantityBefore,
                quantityAfter: quantityAfter,
                batchNumber: current.batchNumber,
                expirationDate: current.expirationDate,
                supplierId: current.supplierId
            )

            try await client
                .from("warehouse_transactions")
                .insert(transaction)
                .execute()

            // 4. Reload affected state
            async let batchesTask: () = loadBatchesForProduct(current.productId)
            async let summariesTask: () = loadProductSummaries()
            _ = await (batchesTask, summariesTask)
        } catch is CancellationError {
            // ignore
        } catch {
            self.error = error.localizedDescription
        }
    }
}
