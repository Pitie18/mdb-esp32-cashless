import SwiftUI

/// Warehouse management page with stock overview and intake booking.
struct WarehouseView: View {
    @StateObject private var viewModel = WarehouseViewModel()
    @EnvironmentObject private var realtime: RealtimeService
    @Environment(\.locale) private var locale
    @State private var selectedTab = 0
    /// Raw text for the quantity field — supports expressions like "2*12", "100+50".
    @State private var quantityText = ""
    @State private var showScanner = false
    @State private var scanError: String?
    @State private var productDetailSelection: ProductDetailSelection?
    @FocusState private var quantityFieldFocused: Bool

    /// Canonical `yyyy-MM-dd` for the typed best-before date, or nil when empty,
    /// invalid, or already in the past (a best-before date can't be in the past).
    private var intakeExpirationISO: String? {
        viewModel.intakeExpirationText.isEmpty
            ? nil
            : LocaleDateMask(locale: locale).parseISO(viewModel.intakeExpirationText, notBefore: Date())
    }

    /// True when the user has typed something that is not yet a complete, valid date.
    private var intakeExpirationInvalid: Bool {
        !viewModel.intakeExpirationText.isEmpty && intakeExpirationISO == nil
    }

    /// Identifies the product whose detail sheet is presented (image tap).
    private struct ProductDetailSelection: Identifiable {
        let id: UUID
        let name: String
        let imagePath: String?
    }

    var body: some View {
        VStack(spacing: 0) {
            // Warehouse picker (if multiple)
            if viewModel.warehouses.count > 1 {
                warehousePicker
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(.bar)
                Divider()
            }

            // Tab selector
            Picker("Section", selection: $selectedTab) {
                Text("Stock").tag(0)
                Text("Incoming").tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            // Tab content
            if viewModel.isLoading && viewModel.productSummaries.isEmpty {
                Spacer()
                ProgressView("Loading warehouse...")
                Spacer()
            } else if viewModel.warehouses.isEmpty && !viewModel.isLoading {
                emptyWarehouseState
            } else {
                switch selectedTab {
                case 0:
                    stockOverviewTab
                case 1:
                    incomingTab
                default:
                    stockOverviewTab
                }
            }
        }
        .navigationTitle("Warehouse")
        .toolbar {
            if selectedTab == 0 {
                ToolbarItem(placement: .topBarTrailing) {
                    stockFilterMenu
                }
            }
        }
        .task {
            await viewModel.loadAll()
        }
        .onChange(of: realtime.warehouseVersion) { _, _ in
            Task { await viewModel.loadAll() }
        }
        .dataRefreshable {
            await viewModel.loadAll()
        }
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                BarcodeScannerView { code in
                    Task {
                        if let productId = await viewModel.lookupBarcode(code) {
                            viewModel.intakeProductId = productId
                            selectedTab = 1  // Switch to Incoming tab
                        } else {
                            scanError = "No product found for barcode \"\(code)\""
                        }
                    }
                }
                .navigationTitle("Scan Barcode")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Cancel") { showScanner = false }
                    }
                }
                .ignoresSafeArea(.all, edges: .bottom)
            }
        }
        .sheet(item: $productDetailSelection) { sel in
            ProductDetailSheet(
                productId: sel.id,
                fallbackName: sel.name,
                fallbackImagePath: sel.imagePath,
                fallbackSellprice: nil
            )
        }
        .alert("Barcode Not Found", isPresented: .init(
            get: { scanError != nil },
            set: { if !$0 { scanError = nil } }
        )) {
            Button("Try Again") { showScanner = true }
            Button("OK", role: .cancel) { scanError = nil }
        } message: {
            Text(scanError ?? "")
        }
        .alert("Error", isPresented: .init(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.error = nil } }
        )) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    // MARK: - Warehouse Picker

    private var warehousePicker: some View {
        Menu {
            ForEach(viewModel.warehouses) { warehouse in
                Button {
                    Task { await viewModel.selectWarehouse(warehouse.id) }
                } label: {
                    HStack {
                        Text(warehouse.name)
                        if warehouse.id == viewModel.selectedWarehouseId {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
        } label: {
            HStack {
                Image(systemName: "building.2")
                    .foregroundStyle(.blue)
                Text(selectedWarehouseName)
                    .font(.headline)
                Image(systemName: "chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }

    private var selectedWarehouseName: String {
        viewModel.warehouses.first(where: { $0.id == viewModel.selectedWarehouseId })?.name ?? "Select Warehouse"
    }

    // MARK: - Stock Filter Menu

    private var stockFilterMenu: some View {
        Menu {
            Toggle("Show out of stock", isOn: $viewModel.includeOutOfStock)
            Toggle("Show archived", isOn: $viewModel.includeArchived)

            Picker("Expiration", selection: $viewModel.expirationFilter) {
                ForEach(WarehouseViewModel.ExpirationFilterOption.allCases) { option in
                    Text(option.label).tag(option)
                }
            }

            if viewModel.hasActiveFilters {
                Divider()
                Button("Reset filters", systemImage: "arrow.counterclockwise") {
                    viewModel.includeOutOfStock = false
                    viewModel.includeArchived = false
                    viewModel.expirationFilter = .all
                }
            }
        } label: {
            Label("Filter", systemImage: viewModel.hasActiveFilters
                ? "line.3.horizontal.decrease.circle.fill"
                : "line.3.horizontal.decrease.circle")
        }
    }

    // MARK: - Empty State

    private var emptyWarehouseState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "shippingbox")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("No Warehouses")
                .font(.title2.bold())
            Text("Create a warehouse in the web dashboard to manage stock here.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 40)
            Spacer()
        }
    }

    // MARK: - Search Field

    /// Inline search field rendered as the first row of the stock list (instead
    /// of a nav-bar `.searchable`) so it scrolls with the content and never
    /// reconfigures the navigation bar when switching tabs.
    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search products", text: $viewModel.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !viewModel.searchText.isEmpty {
                Button {
                    viewModel.searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(.systemGray6))
        )
    }

    // MARK: - Stock Overview Tab

    private var stockOverviewTab: some View {
        Group {
            if viewModel.productSummaries.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "tray")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text("No stock in this warehouse")
                        .foregroundStyle(.secondary)
                    Text("Book incoming stock in the Incoming tab.")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                    Spacer()
                }
            } else {
                // Search field lives INSIDE the list (first row) rather than as
                // a nav-bar `.searchable`. It scrolls with the content and is
                // revealed by pulling the list down. Because the nav bar no
                // longer gains/loses a search field when switching between the
                // Stock and Incoming tabs, the segmented picker and the content
                // below it no longer jump on tab change.
                List {
                    Section {
                        searchField
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                            .listRowBackground(Color.clear)
                    }

                    if viewModel.filteredSummaries.isEmpty {
                        // No-match state as a list row so the search field above
                        // stays mounted and usable while the user types.
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 40))
                                .foregroundStyle(.secondary)
                            if viewModel.searchText.isEmpty {
                                Text("No products match the current filters")
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                            } else {
                                Text("No products matching \"\(viewModel.searchText)\"")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(viewModel.filteredSummaries) { summary in
                            NavigationLink {
                                ProductBatchesView(
                                    productId: summary.productId,
                                    productName: summary.productName,
                                    productImagePath: summary.imagePath
                                )
                                .environmentObject(viewModel)
                            } label: {
                                StockSummaryRow(summary: summary) {
                                    productDetailSelection = ProductDetailSelection(
                                        id: summary.productId,
                                        name: summary.productName,
                                        imagePath: summary.imagePath
                                    )
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollDismissesKeyboard(.interactively)
            }
        }
    }

    // MARK: - Incoming Tab

    private var incomingTab: some View {
        List {
            // Intake form section
            Section {
                intakeFormContent
            } header: {
                Text("Book Intake")
            }

            // Recent intakes section
            if !viewModel.recentIntakes.isEmpty {
                Section {
                    ForEach(viewModel.recentIntakes) { entry in
                        RecentIntakeRow(entry: entry)
                    }
                } header: {
                    Text("Recent Intakes")
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: - Intake Form

    @ViewBuilder
    private var intakeFormContent: some View {
        // Product picker
        HStack(spacing: 0) {
            NavigationLink {
                IntakeProductPickerView(
                    products: viewModel.products,
                    selectedProductId: $viewModel.intakeProductId
                )
            } label: {
                HStack {
                    Text("Product")
                    Spacer()
                    if let productId = viewModel.intakeProductId,
                       let product = viewModel.products.first(where: { $0.id == productId }) {
                        ProductImage(imagePath: product.imagePath, size: 28)
                        Text(product.name ?? "Unnamed")
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    } else {
                        Text("Select...")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .onChange(of: viewModel.intakeProductId) { _, newValue in
            guard let productId = newValue else { return }
            Task { await viewModel.prefillSupplier(for: productId) }
        }

        // Supplier picker — prefilled from the product's last purchase price,
        // but editable per batch (the actual supplier of a delivery can differ).
        NavigationLink {
            IntakeSupplierPickerView(
                suppliers: viewModel.suppliers,
                selectedId: $viewModel.intakeSupplierId,
                selectedName: $viewModel.intakeSupplierName,
                resolveOrCreate: { name in await viewModel.resolveOrCreateSupplier(named: name) }
            )
        } label: {
            HStack {
                Text(String(localized: "Supplier"))
                Spacer()
                Text(viewModel.intakeSupplierName.isEmpty ? String(localized: "Optional") : viewModel.intakeSupplierName)
                    .foregroundStyle(.secondary)
            }
        }

        // Barcode scan button
        Button {
            showScanner = true
        } label: {
            Label("Scan Barcode", systemImage: "barcode.viewfinder")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .tint(.blue)
        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))

        // Quantity (supports math expressions: 2*12, 100+50, 10-2, 48/6)
        HStack {
            Text("Quantity")
            Spacer()
            TextField("Quantity, e.g. 2*12", text: $quantityText)
                .keyboardType(.numbersAndPunctuation)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 140)
                .font(.body.monospacedDigit())
                .focused($quantityFieldFocused)
                .toolbar {
                    ToolbarItemGroup(placement: .keyboard) {
                        if quantityFieldFocused {
                            calculatorToolbar
                        }
                    }
                }
                .onChange(of: quantityText) { _, newValue in
                    // Live-evaluate and sync to viewModel
                    if let result = evaluateExpression(newValue), result > 0 {
                        viewModel.intakeQuantity = result
                    }
                }

            // Show computed result when expression is complex
            if quantityText.contains(where: { "+-*/x×".contains($0) }),
               let result = evaluateExpression(quantityText), result > 0 {
                Text("= \(result)")
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.blue)
                    .transition(.opacity)
            }
        }

        // Batch number
        HStack {
            Text("Batch No.")
            Spacer()
            TextField("Optional", text: $viewModel.intakeBatchNumber)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 200)
        }

        // Best-before date — typed, locale-formatted (de: TT.MM.JJ). Empty = no expiry.
        ExpiryDateField(title: "Best before", text: $viewModel.intakeExpirationText)

        // Book button
        Button {
            Task { await submitIntake() }
        } label: {
            HStack {
                Spacer()
                if viewModel.isBookingIntake {
                    ProgressView()
                        .tint(.white)
                } else {
                    Label("Book Intake", systemImage: "plus.circle.fill")
                }
                Spacer()
            }
        }
        .buttonStyle(.borderedProminent)
        .disabled(viewModel.intakeProductId == nil || viewModel.isBookingIntake || evaluateExpression(quantityText) == nil || intakeExpirationInvalid)
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
    }

    // MARK: - Calculator Toolbar

    @ViewBuilder
    private var calculatorToolbar: some View {
        HStack(spacing: 8) {
            ForEach(["×", "+", "-", "/"], id: \.self) { op in
                Button {
                    quantityText += op == "×" ? "*" : op
                } label: {
                    Text(op)
                        .font(.title3.weight(.semibold).monospacedDigit())
                        .frame(width: 44, height: 36)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
            Spacer()
            // Evaluate & close keyboard
            Button {
                if let result = evaluateExpression(quantityText), result > 0 {
                    viewModel.intakeQuantity = result
                    quantityText = "\(result)"
                }
                quantityFieldFocused = false
            } label: {
                Text("=")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 36)
                    .background(.blue, in: RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Expression Evaluator

    /// Evaluates simple math expressions like "2*12", "100+50", "10-2", "48/6".
    /// Supports chained operations left-to-right (no operator precedence beyond */±).
    private func evaluateExpression(_ text: String) -> Int? {
        // Normalize: replace × with *, strip spaces
        let cleaned = text
            .replacingOccurrences(of: "×", with: "*")
            .replacingOccurrences(of: "x", with: "*")
            .replacingOccurrences(of: " ", with: "")

        guard !cleaned.isEmpty else { return nil }

        // Simple case: just a number
        if let num = Int(cleaned) { return num }

        // Use NSExpression for safe math evaluation
        // Only allow digits and basic operators
        let allowed = CharacterSet(charactersIn: "0123456789+-*/.")
        guard cleaned.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        // Prevent empty sub-expressions or trailing operators
        guard let lastChar = cleaned.last, lastChar.isNumber else { return nil }

        do {
            let expression = NSExpression(format: cleaned)
            if let result = expression.expressionValue(with: nil, context: nil) as? NSNumber {
                let intResult = result.intValue
                return intResult > 0 ? intResult : nil
            }
        } catch {
            return nil
        }
        return nil
    }

    // MARK: - Submit Intake

    private func submitIntake() async {
        // Final evaluation of the quantity expression before submitting
        if let result = evaluateExpression(quantityText), result > 0 {
            viewModel.intakeQuantity = result
            quantityText = "\(result)"
        }

        guard let productId = viewModel.intakeProductId else { return }
        guard viewModel.intakeQuantity > 0 else {
            viewModel.error = "Quantity must be greater than 0"
            return
        }

        let batchNumber: String? = viewModel.intakeBatchNumber.isEmpty ? nil : viewModel.intakeBatchNumber

        let expirationDate: String? = intakeExpirationISO

        await viewModel.bookIntake(
            productId: productId,
            quantity: viewModel.intakeQuantity,
            batchNumber: batchNumber,
            expirationDate: expirationDate,
            supplierId: viewModel.intakeSupplierId
        )

        // Reset quantity field after successful booking
        quantityText = ""
    }
}

// MARK: - Stock Summary Row

struct StockSummaryRow: View {
    let summary: WarehouseProductSummary
    /// Tapping the product image opens the product detail (separate from the
    /// row's NavigationLink, which goes to the batches view).
    var onImageTap: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onImageTap) {
                ProductImage(imagePath: summary.imagePath, size: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(summary.productName)
                        .font(.body)
                        .lineLimit(1)

                    if summary.discontinued {
                        Text("Archived")
                            .font(.caption2.bold())
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(.systemGray5), in: Capsule())
                    }
                }

                HStack(spacing: 8) {
                    Text(String(format: NSLocalizedString("warehouse_batch_count", comment: ""), summary.batchCount))
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if let exp = summary.earliestExpiration {
                        expirationBadge(exp)
                    }
                }
            }

            Spacer()

            // Quantity + status badge
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(summary.totalQuantity)")
                    .font(.title3.bold().monospacedDigit())
                    .foregroundStyle(quantityColor)

                if summary.isOutOfStock {
                    Text("Out of Stock")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.red, in: Capsule())
                } else if summary.isLow {
                    Text("Low")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.orange, in: Capsule())
                }
            }
        }
        .padding(.vertical, 4)
        .opacity(summary.discontinued ? 0.55 : 1)
        // Data-dependent UI-test anchor: only exists once warehouse stock
        // summaries have loaded.
        .accessibilityIdentifier("warehouse-stock-row")
    }

    private var quantityColor: Color {
        if summary.isOutOfStock { return .red }
        if summary.isLow { return .orange }
        return .primary
    }

    @ViewBuilder
    private func expirationBadge(_ dateString: String) -> some View {
        HStack(spacing: 2) {
            Image(systemName: "calendar")
                .font(.caption2)
            Text(formatExpirationDate(dateString))
                .font(.caption)
        }
        .foregroundStyle(expirationColor)
    }

    private var expirationColor: Color {
        switch summary.expirationStatus {
        case .critical: return .red
        case .warning: return .orange
        case .ok: return .secondary
        }
    }

    private func formatExpirationDate(_ dateString: String) -> String {
        let inFormatter = DateFormatter()
        inFormatter.dateFormat = "yyyy-MM-dd"
        guard let date = inFormatter.date(from: dateString) else { return dateString }
        let outFormatter = DateFormatter()
        outFormatter.dateStyle = .short
        return outFormatter.string(from: date)
    }
}

// MARK: - Recent Intake Row

struct RecentIntakeRow: View {
    let entry: IntakeEntry

    var body: some View {
        HStack(spacing: 12) {
            ProductImage(imagePath: entry.imagePath, size: 36)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.productName)
                    .font(.body)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    if let batch = entry.batchNumber {
                        Text(batch)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(timeAgoText(entry.createdAt))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                    if let supplier = entry.supplierName, !supplier.isEmpty {
                        Text(verbatim: "· \(supplier)")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }

            Spacer()

            Text("+\(entry.quantity)")
                .font(.body.bold().monospacedDigit())
                .foregroundStyle(.green)
        }
        .padding(.vertical, 2)
    }

    private func timeAgoText(_ date: Date) -> String {
        let interval = Date().timeIntervalSince(date)
        if interval < 60 { return String(localized: "just now") }
        if interval < 3600 { return String(localized: "\(Int(interval / 60))m ago") }
        if interval < 86400 { return String(localized: "\(Int(interval / 3600))h ago") }
        let days = Int(interval / 86400)
        return String(localized: "\(days)d ago")
    }
}

// MARK: - Intake Product Picker

/// Searchable product list for selecting which product to intake.
struct IntakeProductPickerView: View {
    let products: [Product]
    @Binding var selectedProductId: UUID?
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    private var filteredProducts: [Product] {
        if searchText.isEmpty { return products }
        let query = searchText.lowercased()
        return products.filter { ($0.name ?? "").lowercased().contains(query) }
    }

    var body: some View {
        List {
            ForEach(filteredProducts) { product in
                Button {
                    selectedProductId = product.id
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        ProductImage(imagePath: product.imagePath, size: 36)
                        Text(product.name ?? "Unnamed")
                            .foregroundStyle(.primary)
                        Spacer()
                        if selectedProductId == product.id {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.blue)
                        }
                    }
                }
            }
        }
        .searchable(text: $searchText, prompt: "Search products")
        .navigationTitle("Select Product")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Intake Supplier Picker

/// Pick an existing supplier or type a new one, for the intake form. Mirrors
/// `PurchasePricesSheet.SupplierPickerView`'s search/add interaction, but binds
/// both the id (stored directly on the batch) and the name (for display), and
/// creates the row immediately via `resolveOrCreate` rather than deferring
/// creation to a server-side RPC — the intake flow inserts straight into
/// `warehouse_stock_batches`, it doesn't go through `add_purchase_price`.
struct IntakeSupplierPickerView: View {
    let suppliers: [Supplier]
    @Binding var selectedId: UUID?
    @Binding var selectedName: String
    let resolveOrCreate: (String) async -> Supplier?

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [Supplier] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return suppliers }
        return suppliers.filter { $0.name.localizedCaseInsensitiveContains(q) }
    }
    private var addCandidate: String? {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty,
              !suppliers.contains(where: { $0.name.caseInsensitiveCompare(q) == .orderedSame })
        else { return nil }
        return q
    }

    var body: some View {
        List {
            if selectedId != nil {
                Section {
                    Button(role: .destructive) {
                        selectedId = nil
                        selectedName = ""
                        dismiss()
                    } label: {
                        Text(String(localized: "None"))
                    }
                }
            }
            if let new = addCandidate {
                Section {
                    Button {
                        Task {
                            if let s = await resolveOrCreate(new) {
                                selectedId = s.id
                                selectedName = s.name
                            }
                            dismiss()
                        }
                    } label: {
                        Label(String(localized: "Add “\(new)”"), systemImage: "plus.circle.fill")
                    }
                }
            }
            Section(String(localized: "Suppliers")) {
                if filtered.isEmpty && addCandidate == nil {
                    Text(String(localized: "No suppliers yet.")).foregroundStyle(.secondary)
                }
                ForEach(filtered) { s in
                    Button {
                        selectedId = s.id
                        selectedName = s.name
                        dismiss()
                    } label: {
                        HStack {
                            Text(s.name).foregroundStyle(.primary)
                            Spacer()
                            if s.id == selectedId {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(String(localized: "Supplier"))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: String(localized: "Search or add supplier"))
        .autocorrectionDisabled()
    }
}

#Preview {
    NavigationStack {
        WarehouseView()
    }
}
