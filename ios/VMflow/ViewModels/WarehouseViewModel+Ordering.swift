import Foundation

/// Ordering actions of the warehouse page: the order list (automatic +
/// manual entries), placing orders with a supplier, and booking or closing
/// the expected deliveries. Mirrors the web's `pages/warehouse/index.vue`
/// order handlers on top of `PurchaseOrderService`.
extension WarehouseViewModel {
    // MARK: - Load

    /// Settings, suppliers and product info (company-wide), then the selected
    /// warehouse's manual entries and open orders.
    func loadOrdering() async {
        do {
            if let companyId = selectedCompanyId {
                orderSettings = try await PurchaseOrderService.fetchSettings(companyId: companyId)
            }
            orderSuppliers = try await PurchaseOrderService.fetchSuppliers()
            productOrderInfo = try await PurchaseOrderService.fetchProductInfo(suppliers: orderSuppliers)
        } catch is CancellationError {
        } catch {
            // Best-effort: the stock list keeps working without ordering data
            // (e.g. a backend that has not been updated yet).
            print("[Warehouse] Ordering data failed: \(error)")
        }
        await loadWarehouseOrdering()
    }

    /// Manual entries and open orders of the selected warehouse.
    func loadWarehouseOrdering() async {
        guard let warehouseId = selectedWarehouseId else {
            manualEntries = []
            openOrders = []
            return
        }
        do {
            async let manual = PurchaseOrderService.fetchManualEntries(warehouseId: warehouseId)
            async let orders = PurchaseOrderService.fetchOpenOrders(warehouseId: warehouseId)
            (manualEntries, openOrders) = try await (manual, orders)
        } catch is CancellationError {
        } catch {
            print("[Warehouse] Order list failed: \(error)")
        }
    }

    // MARK: - Order list

    func addToOrderList(_ productId: UUID) async {
        guard let warehouseId = selectedWarehouseId else { return }
        do {
            try await PurchaseOrderService.addToOrderList(warehouseId: warehouseId, productId: productId)
            manualEntries = try await PurchaseOrderService.fetchManualEntries(warehouseId: warehouseId)
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    func removeFromOrderList(_ productId: UUID) async {
        guard let warehouseId = selectedWarehouseId else { return }
        do {
            try await PurchaseOrderService.removeFromOrderList(warehouseId: warehouseId, productId: productId)
            manualEntries = try await PurchaseOrderService.fetchManualEntries(warehouseId: warehouseId)
            orderOverrides[productId] = nil
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    func setCartons(_ productId: UUID, _ cartons: Int) {
        orderOverrides[productId] = max(0, cartons)
    }

    func resetCartons(_ productId: UUID) {
        orderOverrides[productId] = nil
    }

    // MARK: - Product ordering (admins)

    /// Moves a product to a supplier ("no supplier" group's inline picker,
    /// product detail). `nil` clears the main supplier.
    func assignSupplier(_ productId: UUID, supplierId: UUID?) async {
        do {
            try await PurchaseOrderService.updateProductOrdering(productId: productId, preferredSupplierId: .some(supplierId))
            productOrderInfo[productId, default: ProductOrderInfo(unitSize: 1)].preferredSupplierId = supplierId
            orderOverrides[productId] = nil
            if let supplierId, let name = orderSuppliers.first(where: { $0.id == supplierId })?.name {
                let product = productSummaries.first(where: { $0.productId == productId })?.productName ?? ""
                notice = String(localized: "\(product) now belongs to \(name).")
            }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    func saveUnitSize(_ productId: UUID, _ size: Int) async {
        let size = max(1, size)
        do {
            try await PurchaseOrderService.updateProductOrdering(productId: productId, unitSize: size)
            productOrderInfo[productId, default: ProductOrderInfo(unitSize: 1)].unitSize = size
            orderOverrides[productId] = nil
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Purchase orders

    /// "Mark as ordered" (admins): the lines with cartons become an open order
    /// and count as on the way; their manual entries are cleared.
    func placeOrder(_ group: OrderGroup) async {
        guard let warehouseId = selectedWarehouseId, let supplier = group.supplier else { return }
        let lines = group.lines.filter { $0.cartons > 0 }
        guard !lines.isEmpty else { return }
        busySupplierId = supplier.id
        defer { busySupplierId = nil }
        do {
            try await PurchaseOrderService.placeOrder(warehouseId: warehouseId, supplierId: supplier.id, lines: lines)
            for line in group.lines { orderOverrides[line.product.productId] = nil }
            await loadWarehouseOrdering()
            notice = String(localized: "\(supplier.name): marked as ordered. The goods now count as on the way.")
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Admins only.
    func cancelOrder(_ order: PurchaseOrder) async {
        busyOrderId = order.id
        defer { busyOrderId = nil }
        do {
            try await PurchaseOrderService.cancelOrder(id: order.id)
            await loadWarehouseOrdering()
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// "Already booked · mark as delivered": closes without booking anything.
    func closeOrder(_ order: PurchaseOrder) async {
        busyOrderId = order.id
        defer { busyOrderId = nil }
        do {
            try await PurchaseOrderService.closeOrder(id: order.id)
            await loadWarehouseOrdering()
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Books what was delivered into the warehouse as batches.
    @discardableResult
    func receiveOrder(_ order: PurchaseOrder, lines: [ReceiveLineInput]) async -> Bool {
        busyOrderId = order.id
        defer { busyOrderId = nil }
        do {
            let result = try await PurchaseOrderService.receiveOrder(id: order.id, lines: lines)
            async let orders: () = loadWarehouseOrdering()
            async let summaries: () = loadProductSummaries()
            async let intakes: () = loadRecentIntakes()
            _ = await (orders, summaries, intakes)
            notice = String(localized: "Delivery booked: \(result.units) units.")
            return true
        } catch is CancellationError {
            return false
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }
}
