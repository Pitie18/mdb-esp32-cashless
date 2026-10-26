import Foundation

/// An order placed with a supplier (`purchase_orders` with its
/// `purchase_order_lines`). Open ("ordered") orders count as on the way in the
/// order suggestion and show as expected deliveries under Incoming.
struct PurchaseOrder: Identifiable, Equatable, Decodable {
    let id: UUID
    let createdAt: Date
    let warehouseId: UUID
    let supplierId: UUID?
    let supplierName: String?
    /// ordered | received | closed | cancelled
    let status: String
    let note: String?
    let orderedByName: String?
    let lines: [PurchaseOrderLine]

    var totalUnits: Int { lines.reduce(0) { $0 + $1.quantity } }

    enum CodingKeys: String, CodingKey {
        case id, status, note
        case createdAt = "created_at"
        case warehouseId = "warehouse_id"
        case supplierId = "supplier_id"
        case supplierName = "supplier_name"
        case orderedByName = "ordered_by_name"
        case lines = "purchase_order_lines"
    }

    init(id: UUID, createdAt: Date, warehouseId: UUID, supplierId: UUID?, supplierName: String?,
         status: String, note: String?, orderedByName: String?, lines: [PurchaseOrderLine]) {
        self.id = id
        self.createdAt = createdAt
        self.warehouseId = warehouseId
        self.supplierId = supplierId
        self.supplierName = supplierName
        self.status = status
        self.note = note
        self.orderedByName = orderedByName
        self.lines = lines
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        warehouseId = try c.decode(UUID.self, forKey: .warehouseId)
        supplierId = try c.decodeIfPresent(UUID.self, forKey: .supplierId)
        supplierName = try c.decodeIfPresent(String.self, forKey: .supplierName)
        status = try c.decode(String.self, forKey: .status)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        orderedByName = try c.decodeIfPresent(String.self, forKey: .orderedByName)
        lines = (try c.decodeIfPresent([PurchaseOrderLine].self, forKey: .lines) ?? [])
            .sorted { $0.sortOrder < $1.sortOrder }
    }
}

struct PurchaseOrderLine: Identifiable, Equatable, Decodable {
    let id: UUID
    let productId: UUID?
    let productName: String?
    /// Units (not cartons).
    let quantity: Int
    let unitSize: Int
    let unitPriceNet: Double?
    let receivedQuantity: Int?
    let sortOrder: Int

    enum CodingKeys: String, CodingKey {
        case id, quantity
        case productId = "product_id"
        case productName = "product_name"
        case unitSize = "unit_size"
        case unitPriceNet = "unit_price_net"
        case receivedQuantity = "received_quantity"
        case sortOrder = "sort_order"
    }

    init(id: UUID, productId: UUID?, productName: String?, quantity: Int, unitSize: Int,
         unitPriceNet: Double?, receivedQuantity: Int?, sortOrder: Int) {
        self.id = id
        self.productId = productId
        self.productName = productName
        self.quantity = quantity
        self.unitSize = unitSize
        self.unitPriceNet = unitPriceNet
        self.receivedQuantity = receivedQuantity
        self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        productId = try c.decodeIfPresent(UUID.self, forKey: .productId)
        productName = try c.decodeIfPresent(String.self, forKey: .productName)
        quantity = try c.decode(Int.self, forKey: .quantity)
        unitSize = try c.decodeIfPresent(Int.self, forKey: .unitSize) ?? 1
        unitPriceNet = flexibleDouble(c, .unitPriceNet)
        receivedQuantity = try c.decodeIfPresent(Int.self, forKey: .receivedQuantity)
        sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
    }
}

/// What the refiller enters per line when a delivery arrives.
struct ReceiveLineInput: Equatable {
    let lineId: UUID
    let quantity: Int
    /// `yyyy-MM-dd`, nil = no best-before date.
    let expirationDate: String?
    let batchNumber: String?
}

/// `receive_purchase_order`'s answer. A retry books nothing (`booked == 0`).
struct ReceiveResult: Decodable, Equatable {
    let status: String
    let booked: Int
    let units: Int
}

/// Per product: preferred supplier, carton size and the latest purchase price.
struct ProductOrderInfo: Equatable {
    var preferredSupplierId: UUID?
    var unitSize: Int
    var latestSupplierId: UUID?
    var latestPriceNet: Double?

    /// The supplier the product is ordered from.
    var supplierId: UUID? { preferredSupplierId ?? latestSupplierId }
}

/// Postgres `numeric` arrives as a JSON number or a string depending on the
/// PostgREST version; never fail a whole list over it.
func flexibleDouble<K: CodingKey>(_ c: KeyedDecodingContainer<K>, _ key: K) -> Double? {
    if let d = (try? c.decodeIfPresent(Double.self, forKey: key)) ?? nil { return d }
    if let s = (try? c.decodeIfPresent(String.self, forKey: key)) ?? nil { return Double(s) }
    return nil
}
