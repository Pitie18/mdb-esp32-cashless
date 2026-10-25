import SwiftUI
import PhotosUI

/// Form sheet for creating or editing a product.
struct ProductEditSheet: View {
    let product: Product?  // nil = creating new product
    let categories: [ProductCategory]
    let viewModel: ProductsViewModel
    let onSave: (String, Double?, UUID?, Bool) async -> UUID?
    let onUploadImage: (UUID, Data) async -> Void
    let onDeleteImage: (UUID, String) async -> Void
    let onDelete: (() async -> Void)?

    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var priceText: String
    @State private var selectedCategoryId: UUID?
    @State private var discontinued: Bool
    @State private var isSaving = false
    @State private var showDeleteConfirm = false

    // Image picker
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var isUploadingImage = false

    // Image search
    @State private var suggestedImages: [ProductsViewModel.SuggestedImage] = []
    @State private var isSearchingImages = false
    @State private var isLoadingMoreImages = false
    @State private var hasMoreImages = false
    @State private var imageOffset = 0
    @State private var selectedSuggestedImageUrl: String?
    @State private var selectedThumbnailUrl: String?
    @State private var searchTask: Task<Void, Never>?
    /// Restrict suggestions to groceries. On by default — this is a vending
    /// catalogue, and a plain web search returns the planet for "Mars".
    @AppStorage("vmflow-image-search-food-only") private var foodOnlyImages = true

    // Barcode management
    @State private var barcodes: [ProductBarcode] = []
    /// Barcodes added locally before save (for new products without an ID yet).
    @State private var pendingBarcodes: [(code: String, format: String)] = []
    @State private var showScanner = false
    @State private var showManualBarcodeEntry = false
    @State private var manualBarcodeText = ""
    @State private var isLoadingBarcodes = false

    // Purchase prices buffered for a NEW product (flushed after create).
    @State private var pendingPurchasePrices: [PendingPurchasePrice] = []
    @State private var showPurchasePrices = false
    @StateObject private var purchaseVM = PurchasePricesViewModel()

    init(
        product: Product?,
        categories: [ProductCategory],
        viewModel: ProductsViewModel,
        onSave: @escaping (String, Double?, UUID?, Bool) async -> UUID?,
        onUploadImage: @escaping (UUID, Data) async -> Void,
        onDeleteImage: @escaping (UUID, String) async -> Void,
        onDelete: (() async -> Void)?
    ) {
        self.product = product
        self.categories = categories
        self.viewModel = viewModel
        self.onSave = onSave
        self.onUploadImage = onUploadImage
        self.onDeleteImage = onDeleteImage
        self.onDelete = onDelete

        _name = State(initialValue: product?.name ?? "")
        _priceText = State(initialValue: {
            if let price = product?.sellprice {
                return String(format: "%.2f", price)
            }
            return ""
        }())
        _selectedCategoryId = State(initialValue: product?.category)
        _discontinued = State(initialValue: product?.discontinued ?? false)
    }

    var isNew: Bool { product == nil }

    private var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                // Product Image
                Section("Image") {
                    imageSection
                }

                // Basic Info
                Section("Details") {
                    TextField("Product Name", text: $name)

                    TextField("Price (EUR)", text: $priceText)
                        .keyboardType(.decimalPad)

                    Picker("Category", selection: $selectedCategoryId) {
                        Text("None").tag(UUID?.none)
                        ForEach(categories) { category in
                            Text(category.name).tag(UUID?.some(category.id))
                        }
                    }
                }

                // Barcodes
                Section {
                    barcodeSection
                } header: {
                    Text("Barcodes")
                }

                // Purchase prices. Edit mode manages the existing product's prices
                // directly (RPC); a new product buffers locally and flushes after create.
                Section(String(localized: "Purchase prices")) {
                    Button { showPurchasePrices = true } label: {
                        HStack {
                            Label(String(localized: "Manage purchase prices"), systemImage: "eurosign.circle")
                            Spacer()
                            if isNew && !pendingPurchasePrices.isEmpty {
                                Text("\(pendingPurchasePrices.count)").foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                // Status
                Section("Status") {
                    Toggle("Discontinued", isOn: $discontinued)
                }

                // Delete
                if !isNew, let onDelete {
                    Section {
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            HStack {
                                Spacer()
                                Label("Delete Product", systemImage: "trash")
                                Spacer()
                            }
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "New Product" : "Edit Product")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        saveProduct()
                    }
                    .disabled(!isValid || isSaving)
                }
            }
            .confirmationDialog("Delete Product", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    Task {
                        await onDelete?()
                        dismiss()
                    }
                }
            } message: {
                Text("Are you sure you want to delete \"\(name)\"? This action cannot be undone.")
            }
            .onChange(of: selectedPhoto) { _, newItem in
                guard let newItem else { return }
                // Manual photo pick clears any suggested image selection
                selectedSuggestedImageUrl = nil
                selectedThumbnailUrl = nil
                Task {
                    await handleImageSelection(newItem)
                }
            }
            .onChange(of: name) { _, _ in
                triggerImageSearch()
            }
            .onChange(of: foodOnlyImages) { _, _ in
                triggerImageSearch()
            }
            .task {
                if let productId = product?.id {
                    isLoadingBarcodes = true
                    barcodes = await viewModel.loadBarcodes(productId: productId)
                    isLoadingBarcodes = false
                }
                // Auto-search if product has no image yet
                if product?.imagePath == nil, name.count >= 2 {
                    isSearchingImages = true
                    imageOffset = 0
                    let page = await viewModel.searchImages(query: name, foodOnly: foodOnlyImages)
                    suggestedImages = page.images
                    hasMoreImages = page.hasMore
                    isSearchingImages = false
                }
            }
            .sheet(isPresented: $showScanner) {
                NavigationStack {
                    BarcodeScannerView { code in
                        Task {
                            await addBarcode(code)
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
            .alert("Add Barcode", isPresented: $showManualBarcodeEntry) {
                TextField("Barcode", text: $manualBarcodeText)
                Button("Add") {
                    let code = manualBarcodeText.trimmingCharacters(in: .whitespaces)
                    guard !code.isEmpty else { return }
                    Task { await addBarcode(code) }
                    manualBarcodeText = ""
                }
                Button("Cancel", role: .cancel) { manualBarcodeText = "" }
            } message: {
                Text("Enter the barcode number manually.")
            }
            .sheet(isPresented: $showPurchasePrices) {
                PurchasePricesSheet(
                    productId: product?.id,
                    sellprice: Double(priceText.replacingOccurrences(of: ",", with: ".")) ?? product?.sellprice,
                    pending: $pendingPurchasePrices
                )
            }
        }
    }

    // MARK: - Image Section

    @ViewBuilder
    private var imageSection: some View {
        // Current image / selected suggestion preview
        HStack {
            Spacer()
            VStack(spacing: 12) {
                if let thumbUrl = selectedThumbnailUrl, let url = URL(string: thumbUrl) {
                    // Show selected suggested image preview
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                        default:
                            Color(.systemGray5)
                        }
                    }
                    .frame(width: 100, height: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    Button("Clear Selection") {
                        selectedSuggestedImageUrl = nil
                        selectedThumbnailUrl = nil
                    }
                    .font(.caption)
                } else {
                    ProductImage(imagePath: product?.imagePath, size: 100)
                }

                HStack(spacing: 16) {
                    PhotosPicker(
                        selection: $selectedPhoto,
                        matching: .images,
                        photoLibrary: .shared()
                    ) {
                        Label(
                            product?.imagePath != nil ? "Change" : "Upload",
                            systemImage: "photo"
                        )
                        .font(.subheadline)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isNew || isUploadingImage)

                    if product?.imagePath != nil && selectedThumbnailUrl == nil {
                        Button(role: .destructive) {
                            Task {
                                guard let prod = product, let path = prod.imagePath else { return }
                                isUploadingImage = true
                                await onDeleteImage(prod.id, path)
                                isUploadingImage = false
                            }
                        } label: {
                            Label("Remove", systemImage: "trash")
                                .font(.subheadline)
                        }
                        .buttonStyle(.bordered)
                        .disabled(isUploadingImage)
                    }
                }

                if isUploadingImage {
                    ProgressView("Uploading...")
                        .font(.caption)
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)

        // Suggested images from web search
        suggestedImagesSection
    }

    // MARK: - Suggested Images

    /// True while the name is long enough for the automatic lookup to run. Used
    /// to keep the food toggle on screen even when a search comes back empty —
    /// otherwise a too-narrow filter would hide its own off switch.
    private var imageSearchApplicable: Bool {
        product?.imagePath == nil
            && name.trimmingCharacters(in: .whitespaces).count >= 2
    }

    @ViewBuilder
    private var suggestedImagesSection: some View {
        if imageSearchApplicable || isSearchingImages || !suggestedImages.isEmpty {
            Toggle(isOn: $foodOnlyImages) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Food products only")
                        .font(.subheadline)
                    Text("Prefers grocery packshots over unrelated matches.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }

        if isSearchingImages {
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                Text("Searching for images...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else if !suggestedImages.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Suggested images")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(suggestedImages) { img in
                        Button {
                            selectSuggestedImage(img)
                        } label: {
                            AsyncImage(url: URL(string: img.thumbnail)) { phase in
                                switch phase {
                                case .success(let image):
                                    image.resizable().scaledToFill()
                                default:
                                    Color(.systemGray5)
                                        .overlay {
                                            ProgressView().controlSize(.small)
                                        }
                                }
                            }
                            .frame(width: 70, height: 70)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay {
                                if selectedSuggestedImageUrl == img.image {
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(.blue, lineWidth: 3)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                if hasMoreImages || isLoadingMoreImages {
                    Button {
                        loadMoreImages()
                    } label: {
                        HStack {
                            Spacer()
                            if isLoadingMoreImages {
                                ProgressView().controlSize(.small)
                            } else {
                                Text("Show 8 more")
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(isLoadingMoreImages)
                    .padding(.top, 4)
                }
            }
        }
    }

    private func selectSuggestedImage(_ img: ProductsViewModel.SuggestedImage) {
        selectedSuggestedImageUrl = img.image
        selectedThumbnailUrl = img.thumbnail
        selectedPhoto = nil
    }

    /// Debounced image search triggered by product name changes.
    private func triggerImageSearch() {
        searchTask?.cancel()
        let query = name.trimmingCharacters(in: .whitespaces)
        guard query.count >= 2, product?.imagePath == nil, selectedThumbnailUrl == nil else { return }

        searchTask = Task {
            try? await Task.sleep(nanoseconds: 600_000_000) // 600ms debounce
            guard !Task.isCancelled else { return }
            isSearchingImages = true
            imageOffset = 0
            let page = await viewModel.searchImages(query: query, foodOnly: foodOnlyImages)
            guard !Task.isCancelled else { return }
            suggestedImages = page.images
            hasMoreImages = page.hasMore
            isSearchingImages = false
        }
    }

    /// Loads the next page of 8 suggestions and appends them.
    private func loadMoreImages() {
        let query = name.trimmingCharacters(in: .whitespaces)
        guard query.count >= 2, hasMoreImages, !isLoadingMoreImages else { return }

        Task {
            isLoadingMoreImages = true
            let nextOffset = imageOffset + 8
            let page = await viewModel.searchImages(query: query, offset: nextOffset, foodOnly: foodOnlyImages)
            imageOffset = nextOffset
            let existing = Set(suggestedImages.map(\.image))
            suggestedImages.append(contentsOf: page.images.filter { !existing.contains($0.image) })
            hasMoreImages = page.hasMore
            isLoadingMoreImages = false
        }
    }

    // MARK: - Barcode Section

    @ViewBuilder
    private var barcodeSection: some View {
        if isLoadingBarcodes {
            HStack {
                Spacer()
                ProgressView()
                Spacer()
            }
        } else {
            // Saved barcodes (existing product)
            ForEach(barcodes) { bc in
                barcodeRow(code: bc.barcode, format: bc.format) {
                    Task {
                        await viewModel.deleteBarcode(id: bc.id)
                        barcodes.removeAll { $0.id == bc.id }
                    }
                }
            }

            // Pending barcodes (not yet saved to DB)
            ForEach(Array(pendingBarcodes.enumerated()), id: \.offset) { index, pending in
                barcodeRow(code: pending.code, format: pending.format) {
                    pendingBarcodes.remove(at: index)
                }
            }

            // Add buttons
            Button {
                showScanner = true
            } label: {
                Label("Scan Barcode", systemImage: "barcode.viewfinder")
            }

            Button {
                showManualBarcodeEntry = true
            } label: {
                Label("Enter Manually", systemImage: "keyboard")
            }
        }
    }

    private func barcodeRow(code: String, format: String, onDelete: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "barcode")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(code)
                    .font(.body.monospaced())
                Text(format)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(role: .destructive, action: onDelete) {
                Image(systemName: "trash")
                    .font(.subheadline)
            }
            .buttonStyle(.borderless)
        }
    }

    private func addBarcode(_ code: String) async {
        let allCodes = barcodes.map(\.barcode) + pendingBarcodes.map(\.code)
        guard !allCodes.contains(code) else {
            viewModel.error = String(localized: "Barcode \"\(code)\" is already added.")
            return
        }

        let format = detectBarcodeFormat(code)

        if let productId = product?.id {
            // Existing product → save to DB immediately
            let success = await viewModel.addBarcode(productId: productId, barcode: code, format: format)
            if success {
                barcodes = await viewModel.loadBarcodes(productId: productId)
            }
        } else {
            // New product → store locally, save after create
            pendingBarcodes.append((code: code, format: format))
        }
        showScanner = false

        // Auto-fill the name from the barcode, same as typing it manually —
        // setting `name` triggers the existing .onChange(of: name) photo
        // search, so no separate image lookup is needed here. Never
        // overwrites a name the user already typed.
        if name.trimmingCharacters(in: .whitespaces).isEmpty,
           let suggested = await lookupBarcodeProduct(code) {
            name = suggested
        }
    }

    /// Looks up a barcode against Open Food Facts and returns a suggested
    /// product name combining its name and quantity (e.g. "Coca-Cola 330ml"),
    /// or nil if the barcode isn't found or has no usable name.
    private func lookupBarcodeProduct(_ code: String) async -> String? {
        guard let url = URL(string: "https://world.openfoodfacts.org/api/v2/product/\(code).json?fields=product_name,quantity") else {
            return nil
        }
        struct Response: Decodable {
            let status: Int
            let product: OFFProduct?
            struct OFFProduct: Decodable {
                let productName: String?
                let quantity: String?
                enum CodingKeys: String, CodingKey {
                    case productName = "product_name"
                    case quantity
                }
            }
        }
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 8
            let (data, _) = try await URLSession.shared.data(for: request)
            let decoded = try JSONDecoder().decode(Response.self, from: data)
            guard decoded.status == 1,
                  let name = decoded.product?.productName?.trimmingCharacters(in: .whitespaces),
                  !name.isEmpty else { return nil }
            if let quantity = decoded.product?.quantity?.trimmingCharacters(in: .whitespaces), !quantity.isEmpty {
                return "\(name) \(normalizeQuantityUnit(quantity))"
            }
            return name
        } catch {
            return nil
        }
    }

    /// Open Food Facts quantities are free text ("500 ml", "1,5 l", "33cl"). Just
    /// normalize the common spelled-out/abbreviated units; pass anything else through.
    private func normalizeQuantityUnit(_ raw: String) -> String {
        var q = raw
        let replacements: [(String, String)] = [
            ("milliliters", "ml"), ("millilitres", "ml"), ("milliliter", "ml"), ("millilitre", "ml"),
            ("centiliters", "cl"), ("centilitres", "cl"), ("centiliter", "cl"), ("centilitre", "cl"),
            ("liters", "L"), ("litres", "L"), ("liter", "L"), ("litre", "L"),
        ]
        for (pattern, unit) in replacements {
            q = q.replacingOccurrences(of: pattern, with: unit, options: [.caseInsensitive])
        }
        return q
    }

    /// Simple heuristic to detect barcode format from the string length.
    private func detectBarcodeFormat(_ code: String) -> String {
        switch code.count {
        case 8: return "EAN-8"
        case 12: return "UPC-A"
        case 13: return "EAN-13"
        default: return code.allSatisfy(\.isNumber) ? "EAN-13" : "CODE-128"
        }
    }

    // MARK: - Actions

    private func saveProduct() {
        isSaving = true
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let price = Double(priceText.replacingOccurrences(of: ",", with: "."))
        let suggestedUrl = selectedSuggestedImageUrl
        let barcodesToSave = pendingBarcodes
        let pricesToSave = pendingPurchasePrices

        Task {
            let returnedId = await onSave(trimmedName, price, selectedCategoryId, discontinued)

            // Use returned ID (new product) or existing product ID
            let productId = returnedId ?? product?.id

            // Save pending barcodes for newly created product
            if let productId {
                for pending in barcodesToSave {
                    _ = await viewModel.addBarcode(productId: productId, barcode: pending.code, format: pending.format)
                }
            }

            // Save pending purchase prices for newly created product (net/gross +
            // tax rate resolve server-side now that the product + category exist).
            if let productId {
                for e in pricesToSave {
                    _ = await purchaseVM.addPrice(
                        productId: productId, supplierName: e.supplierName, price: e.price,
                        basis: e.basis, observedOn: e.observedOn, note: e.note, taxRateOverride: nil)
                }
            }

            // If a suggested image was selected, download and upload it
            if let imageUrl = suggestedUrl, let productId {
                await viewModel.downloadAndUploadImage(productId: productId, imageUrl: imageUrl)
            }

            isSaving = false
            dismiss()
        }
    }

    private func handleImageSelection(_ item: PhotosPickerItem) async {
        guard let product else { return }

        isUploadingImage = true
        do {
            if let data = try await item.loadTransferable(type: Data.self) {
                await onUploadImage(product.id, data)
            }
        } catch {
            print("[ProductEditSheet] Failed to load image: \(error)")
        }
        isUploadingImage = false
        selectedPhoto = nil
    }
}

#Preview {
    ProductEditSheet(
        product: nil,
        categories: [],
        viewModel: ProductsViewModel(),
        onSave: { _, _, _, _ in return nil },
        onUploadImage: { _, _ in },
        onDeleteImage: { _, _ in },
        onDelete: nil
    )
}
