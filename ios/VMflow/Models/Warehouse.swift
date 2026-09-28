import Foundation

/// A warehouse location. Maps to the `warehouses` table.
struct Warehouse: Codable, Identifiable, Equatable {
    let id: UUID
    let name: String
    let address: String?
    let notes: String?
    let companyId: UUID

    enum CodingKeys: String, CodingKey {
        case id, name, address, notes
        case companyId = "company_id"
    }
}

/// Stock batch in a warehouse (FIFO tracking). Maps to `warehouse_stock_batches`.
struct WarehouseStockBatch: Codable, Identifiable, Equatable {
    let id: UUID
    let warehouseId: UUID
    let productId: UUID
    let quantity: Int
    let batchNumber: String?
    let expirationDate: String?
    let supplierId: UUID?

    enum CodingKeys: String, CodingKey {
        case id, quantity
        case warehouseId = "warehouse_id"
        case productId = "product_id"
        case batchNumber = "batch_number"
        case expirationDate = "expiration_date"
        case supplierId = "supplier_id"
    }
}

/// Aggregated warehouse stock per product (sum of all batches).
struct WarehouseProductStock: Identifiable, Equatable {
    let productId: UUID
    let productName: String
    let totalQuantity: Int
    let imagePath: String?

    var id: UUID { productId }
}

/// Expiration severity of a product's earliest-expiring batch.
/// Mirrors the management frontend's `expirationStatus()` helper.
enum ExpirationStatus: String {
    case ok, warning, critical
}

/// Product stock summary for warehouse overview (with batch and expiration info).
struct WarehouseProductSummary: Identifiable, Equatable {
    let productId: UUID
    let productName: String
    let imagePath: String?
    let totalQuantity: Int
    let batchCount: Int
    let earliestExpiration: String?  // date string or nil
    let discontinued: Bool
    let expirationStatus: ExpirationStatus
    /// Fleet-wide average units sold per day (`get_product_sales_velocity`);
    /// 0 when the product didn't sell in the window.
    var avgDailySales: Double = 0

    var id: UUID { productId }

    var isLow: Bool { totalQuantity > 0 && totalQuantity < 10 }
    var isOutOfStock: Bool { totalQuantity == 0 }

    /// Reach above this is shown as ">90 days" — beyond that the exact figure is noise.
    static let reachDisplayCapDays = 90

    /// Days until this warehouse's stock runs out at the current sales rate,
    /// `round(quantity / avgDailySales)`; nil without sales. Same formula as
    /// the web warehouse page (`estimated_days_remaining`) and Android `StockReach`.
    var daysRemaining: Int? {
        guard avgDailySales > 0 else { return nil }
        return Int((Double(totalQuantity) / avgDailySales).rounded())
    }

    /// ≤ 7 days critical, ≤ 14 warning — the web page's thresholds.
    var reachLevel: ReachLevel {
        guard let days = daysRemaining else { return .unknown }
        if days <= 7 { return .critical }
        if days <= 14 { return .warning }
        return .ok
    }

    enum ReachLevel { case critical, warning, ok, unknown }

    /// Classifies a `yyyy-MM-dd` date string into an expiration severity.
    /// critical: < 7 days away (incl. already expired); warning: ≤ 30 days; else ok.
    static func expirationStatus(for dateStr: String?) -> ExpirationStatus {
        guard let dateStr else { return .ok }
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        guard let exp = f.date(from: dateStr) else { return .ok }
        let today = Calendar.current.startOfDay(for: Date())
        let days = Calendar.current.dateComponents([.day], from: today, to: Calendar.current.startOfDay(for: exp)).day ?? 0
        if days < 7 { return .critical }
        if days <= 30 { return .warning }
        return .ok
    }
}

/// A single stock intake entry for the recent intakes list.
struct IntakeEntry: Identifiable, Equatable {
    let id: UUID
    let productId: UUID
    let productName: String
    let imagePath: String?
    let quantity: Int
    let batchNumber: String?
    let expirationDate: String?
    let supplierName: String?
    let createdAt: Date
}

/// Codable wrapper for inserting a stock batch via Supabase.
struct InsertStockBatch: Codable {
    let warehouseId: UUID
    let productId: UUID
    let quantity: Int
    let batchNumber: String?
    let expirationDate: String?
    let companyId: UUID
    let supplierId: UUID?

    enum CodingKeys: String, CodingKey {
        case quantity
        case warehouseId = "warehouse_id"
        case productId = "product_id"
        case batchNumber = "batch_number"
        case expirationDate = "expiration_date"
        case companyId = "company_id"
        case supplierId = "supplier_id"
    }
}

/// Codable wrapper for inserting a warehouse transaction via Supabase.
struct InsertWarehouseTransaction: Codable {
    let warehouseId: UUID
    let productId: UUID
    let transactionType: String
    let quantityChange: Int
    let userId: UUID
    let batchId: UUID?
    let notes: String?
    let companyId: UUID
    // Web-parity columns — optional so existing `bookIntake` call sites work unchanged.
    let quantityBefore: Int?
    let quantityAfter: Int?
    let batchNumber: String?
    let expirationDate: String?
    let supplierId: UUID?

    enum CodingKeys: String, CodingKey {
        case notes
        case warehouseId = "warehouse_id"
        case productId = "product_id"
        case transactionType = "transaction_type"
        case quantityChange = "quantity_change"
        case userId = "user_id"
        case batchId = "batch_id"
        case companyId = "company_id"
        case quantityBefore = "quantity_before"
        case quantityAfter = "quantity_after"
        case batchNumber = "batch_number"
        case expirationDate = "expiration_date"
        case supplierId = "supplier_id"
    }
}

/// Codable response for a newly inserted stock batch (to capture the generated id).
struct InsertedBatchResponse: Codable {
    let id: UUID
}

/// Physical warehouse slot for a product. Maps to `warehouse_product_positions`.
/// Only the fields needed to compute pick order are decoded.
struct WarehouseProductPosition: Codable, Equatable {
    let productId: UUID
    let sortOrder: Int
    let groupId: UUID?

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case sortOrder = "sort_order"
        case groupId = "group_id"
    }
}

/// Folder-like group of warehouse positions. Groups can nest via `parentId`.
/// Maps to `warehouse_position_groups`. Only the fields needed for pick-order
/// traversal are decoded.
struct WarehousePositionGroup: Codable, Identifiable, Equatable {
    let id: UUID
    let parentId: UUID?
    let sortOrder: Int

    enum CodingKeys: String, CodingKey {
        case id
        case parentId = "parent_id"
        case sortOrder = "sort_order"
    }
}
