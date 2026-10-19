import Foundation
import Supabase

/// Data and actions behind the warehouse "Order" segment and the expected
/// deliveries under Incoming: suppliers with their ordering rhythm, the
/// company's calculation settings, manual order-list entries and open
/// purchase orders. The suggestion itself is `OrderSuggestion`. Mirrors the
/// PWA's `usePurchaseOrders`.
@MainActor
enum PurchaseOrderService {
    private static var client: SupabaseClient { SupabaseService.shared.client }

    // MARK: - Settings & suppliers

    static func fetchSettings(companyId: UUID) async throws -> OrderSettings {
        struct Row: Decodable {
            let bufferDays: Int?
            let countMachineStock: Bool?
            let defaultLeadDays: Int?
            let defaultIntervalDays: Int?
            enum CodingKeys: String, CodingKey {
                case bufferDays = "order_buffer_days"
                case countMachineStock = "order_count_machine_stock"
                case defaultLeadDays = "order_default_lead_days"
                case defaultIntervalDays = "order_default_interval_days"
            }
        }
        let rows: [Row] = try await client
            .from("companies")
            .select("order_buffer_days, order_count_machine_stock, order_default_lead_days, order_default_interval_days")
            .eq("id", value: companyId.uuidString)
            .execute()
            .value
        let d = OrderSettings.default
        guard let r = rows.first else { return d }
        return OrderSettings(
            bufferDays: r.bufferDays ?? d.bufferDays,
            countMachineStock: r.countMachineStock ?? d.countMachineStock,
            defaultLeadDays: r.defaultLeadDays ?? d.defaultLeadDays,
            defaultIntervalDays: r.defaultIntervalDays ?? d.defaultIntervalDays
        )
    }

    static func fetchSuppliers() async throws -> [OrderSupplier] {
        struct Row: Decodable {
            let id: UUID
            let name: String
            let email: String?
            let customerNumber: String?
            let leadTimeDays: Int?
            let orderIntervalDays: Int?
            let minOrderValue: Double?
            enum CodingKeys: String, CodingKey {
                case id, name, email
                case customerNumber = "customer_number"
                case leadTimeDays = "lead_time_days"
                case orderIntervalDays = "order_interval_days"
                case minOrderValue = "min_order_value"
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(UUID.self, forKey: .id)
                name = try c.decode(String.self, forKey: .name)
                email = try c.decodeIfPresent(String.self, forKey: .email)
                customerNumber = try c.decodeIfPresent(String.self, forKey: .customerNumber)
                leadTimeDays = try c.decodeIfPresent(Int.self, forKey: .leadTimeDays)
                orderIntervalDays = try c.decodeIfPresent(Int.self, forKey: .orderIntervalDays)
                minOrderValue = flexibleDouble(c, .minOrderValue)
            }
        }
        let rows: [Row] = try await client
            .from("suppliers")
            .select("id, name, email, customer_number, lead_time_days, order_interval_days, min_order_value")
            .order("name")
            .execute()
            .value
        return rows.map {
            OrderSupplier(id: $0.id, name: $0.name, customerNumber: $0.customerNumber, email: $0.email,
                          leadTimeDays: $0.leadTimeDays, orderIntervalDays: $0.orderIntervalDays,
                          minOrderValue: $0.minOrderValue)
        }
    }

    // MARK: - Products

    /// Preferred supplier, carton size and latest purchase price per product.
    /// The purchase summary names the newest supplier; names are unique per
    /// company, so it maps back to an id.
    static func fetchProductInfo(suppliers: [OrderSupplier]) async throws -> [UUID: ProductOrderInfo] {
        struct ProductRow: Decodable {
            let id: UUID
            let preferredSupplierId: UUID?
            let orderUnitSize: Int?
            enum CodingKeys: String, CodingKey {
                case id
                case preferredSupplierId = "preferred_supplier_id"
                case orderUnitSize = "order_unit_size"
            }
        }
        struct SummaryRow: Decodable {
            let productId: UUID
            let newestSupplier: String?
            let newestNet: Double?
            enum CodingKeys: String, CodingKey {
                case productId = "product_id"
                case newestSupplier = "newest_supplier"
                case newestNet = "newest_net"
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                productId = try c.decode(UUID.self, forKey: .productId)
                newestSupplier = try c.decodeIfPresent(String.self, forKey: .newestSupplier)
                newestNet = flexibleDouble(c, .newestNet)
            }
        }

        let products: [ProductRow] = try await client
            .from("products")
            .select("id, preferred_supplier_id, order_unit_size")
            .execute()
            .value
        var summaries: [UUID: SummaryRow] = [:]
        if !products.isEmpty {
            // Best-effort like on the web: without prices the list still works.
            let rows: [SummaryRow]? = try? await client
                .rpc("get_product_purchase_summary", params: [
                    "p_product_ids": AnyJSON.array(products.map { .string($0.id.uuidString) }),
                ])
                .execute()
                .value
            for r in rows ?? [] { summaries[r.productId] = r }
        }
        let byName = Dictionary(
            suppliers.map { ($0.name.trimmingCharacters(in: .whitespaces).lowercased(), $0.id) },
            uniquingKeysWith: { first, _ in first }
        )

        var info: [UUID: ProductOrderInfo] = [:]
        for p in products {
            let s = summaries[p.id]
            info[p.id] = ProductOrderInfo(
                preferredSupplierId: p.preferredSupplierId,
                unitSize: max(1, p.orderUnitSize ?? 1),
                latestSupplierId: s?.newestSupplier.flatMap { byName[$0.trimmingCharacters(in: .whitespaces).lowercased()] },
                latestPriceNet: s?.newestNet
            )
        }
        return info
    }

    /// Admins only (RLS): the product's main supplier and/or carton size.
    static func updateProductOrdering(productId: UUID, preferredSupplierId: UUID?? = nil, unitSize: Int? = nil) async throws {
        var updates: [String: AnyJSON] = [:]
        if let supplier = preferredSupplierId {
            updates["preferred_supplier_id"] = supplier.map { .string($0.uuidString) } ?? .null
        }
        if let size = unitSize { updates["order_unit_size"] = .integer(max(1, size)) }
        guard !updates.isEmpty else { return }
        try await client.from("products").update(updates).eq("id", value: productId.uuidString).execute()
    }

    /// Units sitting in machine slots per product (company-wide, RLS-scoped).
    static func fetchMachineStock() async throws -> [UUID: Int] {
        struct Row: Decodable {
            let productId: UUID?
            let currentStock: Int?
            enum CodingKeys: String, CodingKey {
                case productId = "product_id"
                case currentStock = "current_stock"
            }
        }
        let rows: [Row] = try await client
            .from("machine_trays")
            .select("product_id, current_stock")
            .not("product_id", operator: .is, value: "null")
            .execute()
            .value
        var stock: [UUID: Int] = [:]
        for r in rows {
            guard let id = r.productId else { continue }
            stock[id, default: 0] += max(0, r.currentStock ?? 0)
        }
        return stock
    }

    // MARK: - Order list (manual entries)

    static func fetchManualEntries(warehouseId: UUID) async throws -> [ManualOrderEntry] {
        try await client
            .from("order_list_items")
            .select("product_id, added_by_name, added_via, created_at")
            .eq("warehouse_id", value: warehouseId.uuidString)
            .execute()
            .value
    }

    static func addToOrderList(warehouseId: UUID, productId: UUID) async throws {
        try await client.rpc("add_to_order_list", params: [
            "p_warehouse_id": AnyJSON.string(warehouseId.uuidString),
            "p_product_id": AnyJSON.string(productId.uuidString),
            "p_via": AnyJSON.string("ios"),
        ]).execute()
    }

    static func removeFromOrderList(warehouseId: UUID, productId: UUID) async throws {
        try await client.rpc("remove_from_order_list", params: [
            "p_warehouse_id": AnyJSON.string(warehouseId.uuidString),
            "p_product_id": AnyJSON.string(productId.uuidString),
        ]).execute()
    }

    // MARK: - Purchase orders

    static func fetchOpenOrders(warehouseId: UUID) async throws -> [PurchaseOrder] {
        try await client
            .from("purchase_orders")
            .select("id, created_at, warehouse_id, supplier_id, supplier_name, status, note, ordered_by_name, purchase_order_lines(id, product_id, product_name, quantity, unit_size, unit_price_net, received_quantity, sort_order)")
            .eq("warehouse_id", value: warehouseId.uuidString)
            .eq("status", value: "ordered")
            .order("created_at")
            .execute()
            .value
    }

    /// Admins only. Clears the manual entries of the ordered products.
    @discardableResult
    static func placeOrder(warehouseId: UUID, supplierId: UUID, lines: [OrderLine]) async throws -> UUID {
        let payload: [AnyJSON] = lines.filter { $0.cartons > 0 }.map { l in
            .object([
                "product_id": .string(l.product.productId.uuidString),
                "quantity": .integer(l.units),
                "unit_size": .integer(max(1, l.product.unitSize)),
                "unit_price_net": l.product.unitPriceNet.map { AnyJSON.double($0) } ?? .null,
            ])
        }
        return try await client.rpc("place_purchase_order", params: [
            "p_warehouse_id": AnyJSON.string(warehouseId.uuidString),
            "p_supplier_id": AnyJSON.string(supplierId.uuidString),
            "p_lines": AnyJSON.array(payload),
            "p_note": AnyJSON.null,
        ]).execute().value
    }

    /// Admins only.
    static func cancelOrder(id: UUID) async throws {
        try await client.rpc("cancel_purchase_order", params: ["p_order_id": AnyJSON.string(id.uuidString)]).execute()
    }

    /// Marks an order delivered without booking anything (goods booked by hand).
    static func closeOrder(id: UUID) async throws {
        try await client.rpc("close_purchase_order", params: ["p_order_id": AnyJSON.string(id.uuidString)]).execute()
    }

    /// Books the delivery into the warehouse; a retry books nothing.
    static func receiveOrder(id: UUID, lines: [ReceiveLineInput]) async throws -> ReceiveResult {
        let payload: [AnyJSON] = lines.map { l in
            let batch = l.batchNumber?.trimmingCharacters(in: .whitespaces) ?? ""
            return .object([
                "line_id": .string(l.lineId.uuidString),
                "quantity": .integer(max(0, l.quantity)),
                "expiration_date": l.expirationDate.map { AnyJSON.string($0) } ?? .null,
                "batch_number": batch.isEmpty ? .null : .string(batch),
            ])
        }
        return try await client.rpc("receive_purchase_order", params: [
            "p_order_id": AnyJSON.string(id.uuidString),
            "p_lines": AnyJSON.array(payload),
        ]).execute().value
    }
}
