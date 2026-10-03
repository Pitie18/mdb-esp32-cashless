import SwiftUI
import Charts
import Supabase

/// Full product detail view, matching the management frontend's `/products/[id]`
/// page. Presented as a sheet from anywhere a product is tapped without an
/// existing handler (sales rows, deal sheets, etc.).
struct ProductDetailSheet: View {
    let productId: UUID
    let fallbackName: String
    let fallbackImagePath: String?
    let fallbackSellprice: Double?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = ProductDetailViewModel()
    @StateObject private var productsVM = ProductsViewModel()
    @StateObject private var purchaseVM = PurchasePricesViewModel()
    @State private var ekSummary: ProductPurchaseSummary?
    @State private var showPurchasePrices = false
    @State private var expandedWarehouseId: UUID?
    @State private var showEditSheet = false

    /// Shared scrubber position — drag on either chart updates both.
    @State private var selectedChartDate: Date?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    if !viewModel.barcodes.isEmpty {
                        barcodeChips
                    }
                    if let kpis = viewModel.kpis {
                        kpiSection(kpis)
                    }
                    chartSection
                    warehouseSection
                    machineSection
                    ekSection
                    if let kpis = viewModel.kpis, !kpis.topMachines.isEmpty {
                        topMachinesSection(kpis.topMachines)
                    }
                    salesSection
                    transactionSection
                }
                .padding(20)
            }
            .navigationTitle("Product")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showEditSheet = true
                    } label: {
                        Label("Edit", systemImage: "pencil")
                    }
                    .disabled(viewModel.product == nil)
                }
            }
            .task {
                async let detailTask: () = viewModel.load(productId: productId)
                async let categoriesTask: () = productsVM.loadCategories()
                _ = await (detailTask, categoriesTask)
                await loadEkSummary()
            }
            .dataRefreshable {
                await viewModel.load(productId: productId)
            }
            .sheet(isPresented: $showEditSheet) {
                if let info = viewModel.product {
                    ProductEditSheet(
                        product: info.asProduct(),
                        categories: productsVM.categories,
                        viewModel: productsVM,
                        onSave: { name, price, categoryId, discontinued in
                            await productsVM.updateProduct(
                                id: info.id,
                                name: name,
                                sellprice: price,
                                categoryId: categoryId,
                                discontinued: discontinued
                            )
                            await viewModel.load(productId: productId)
                            return info.id
                        },
                        onUploadImage: { pid, data in
                            await productsVM.uploadProductImage(productId: pid, imageData: data)
                            await viewModel.load(productId: productId)
                        },
                        onDeleteImage: { pid, path in
                            await productsVM.deleteProductImage(productId: pid, imagePath: path)
                            await viewModel.load(productId: productId)
                        },
                        onDelete: {
                            await productsVM.deleteProduct(id: info.id)
                            dismiss()
                        }
                    )
                }
            }
            .sheet(isPresented: $showPurchasePrices, onDismiss: { Task { await loadEkSummary() } }) {
                PurchasePricesSheet(productId: productId, sellprice: viewModel.product?.sellprice)
            }
        }
    }

    // MARK: - Header

    private var displayName: String {
        viewModel.product?.name ?? fallbackName
    }

    private var displayImagePath: String? {
        viewModel.product?.imagePath ?? fallbackImagePath
    }

    private var displaySellprice: Double? {
        viewModel.product?.sellprice ?? fallbackSellprice
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            ProductImage(imagePath: displayImagePath, size: 72)

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(displayName)
                        .font(.title3.weight(.bold))
                        .lineLimit(3)
                    if viewModel.product?.discontinued == true {
                        Text("Discontinued")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(.orange.opacity(0.15)))
                    }
                }

                if let category = viewModel.product?.categoryName {
                    Text(category)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let price = displaySellprice {
                    Text(formatCurrency(price))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)
        }
    }

    private var barcodeChips: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Image(systemName: "barcode")
                    .font(.caption2)
                Text("EAN / Barcode")
                    .font(.caption.weight(.medium))
            }
            .foregroundStyle(.secondary)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(viewModel.barcodes) { b in
                        HStack(spacing: 5) {
                            Image(systemName: "barcode")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            Text(b.barcode)
                                .font(.footnote.monospaced())
                                .foregroundStyle(.primary)
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background {
                            Capsule()
                                .fill(Color.secondary.opacity(0.10))
                                .overlay(Capsule().stroke(Color.secondary.opacity(0.18), lineWidth: 0.5))
                        }
                    }
                }
            }
        }
    }

    // MARK: - KPIs

    private var kpiColumns: [GridItem] {
        [GridItem(.flexible()), GridItem(.flexible())]
    }

    private func kpiSection(_ kpis: ProductDetailViewModel.ProductKpis) -> some View {
        LazyVGrid(columns: kpiColumns, spacing: 10) {
            kpiCard(
                title: "Warehouse Stock",
                value: "\(kpis.warehouseTotalQty)",
                subtitle: String(localized: "\(kpis.warehouseCount) warehouse"),
                color: .blue
            )
            kpiCard(
                title: "Machine Stock",
                value: "\(kpis.trayTotalStock)/\(kpis.trayTotalCapacity)",
                subtitle: String(localized: "\(kpis.machineCount) machine"),
                color: .green
            )
            kpiCard(
                title: "Sales Today",
                value: "\(kpis.salesTodayUnits)",
                subtitle: formatCurrency(kpis.salesTodayRevenue),
                color: .orange
            )
            kpiCard(
                title: "Velocity",
                value: String(format: "%.1f", kpis.velocityUnitsPerDay),
                subtitle: "\(String(localized: "units/day")) · \(String(localized: "\(kpis.velocityWindowDays)d"))",
                color: .purple
            )
        }
    }

    private func kpiCard(title: LocalizedStringKey, value: String, subtitle: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Text(value)
                .font(.title2.weight(.semibold))
                .foregroundStyle(color)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(subtitle)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background {
            RoundedRectangle(cornerRadius: 12)
                .fill(.regularMaterial)
        }
    }

    // MARK: - Charts

    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Sales (30 days)")

            if viewModel.chartRevenue.isEmpty && viewModel.chartUnits.isEmpty {
                Text("No sales in the last 30 days")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)
            } else {
                revenueChart
                unitsChart
            }
        }
    }

    private var revenueChart: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Revenue")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            Chart {
                ForEach(viewModel.chartRevenue) { point in
                    BarMark(
                        x: .value("Date", point.date, unit: .day),
                        y: .value("Revenue", point.value)
                    )
                    .foregroundStyle(point.isWeekend ? Color.blue.opacity(0.45).gradient : Color.blue.gradient)
                    .cornerRadius(2)
                }

                if viewModel.revenueAverage > 0 {
                    RuleMark(y: .value("Avg", viewModel.revenueAverage))
                        .foregroundStyle(.orange)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .annotation(
                            position: .top,
                            alignment: .trailing,
                            spacing: 2,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            Text("Ø \(formatCurrency(viewModel.revenueAverage))")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.orange)
                        }
                }

                if let selectedChartDate, let point = selectedRevenuePoint {
                    RuleMark(x: .value("Selected", point.date, unit: .day))
                        .foregroundStyle(.gray.opacity(0.35))
                        .annotation(
                            position: .top,
                            alignment: .center,
                            spacing: 4,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            chartTooltip(date: point.date, primary: formatCurrency(point.value), primaryLabel: "Revenue")
                        }
                }
            }
            .chartXSelection(value: $selectedChartDate)
            .chartXAxis {
                AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                    AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                    AxisGridLine()
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    if let v = value.as(Double.self) {
                        AxisValueLabel { Text(formatCurrencyCompact(v)) }
                    }
                    AxisGridLine()
                }
            }
            .frame(height: 160)
            .animation(.smooth, value: selectedChartDate)
        }
    }

    private var unitsChart: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Units")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            Chart {
                ForEach(viewModel.chartUnits) { point in
                    BarMark(
                        x: .value("Date", point.date, unit: .day),
                        y: .value("Units", point.value)
                    )
                    .foregroundStyle(point.isWeekend ? Color.purple.opacity(0.45).gradient : Color.purple.gradient)
                    .cornerRadius(2)
                }

                if viewModel.unitsAverage > 0 {
                    RuleMark(y: .value("Avg", viewModel.unitsAverage))
                        .foregroundStyle(.orange)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .annotation(
                            position: .top,
                            alignment: .trailing,
                            spacing: 2,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            Text(String(format: "Ø %.1f", viewModel.unitsAverage))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.orange)
                        }
                }

                if let selectedChartDate, let point = selectedUnitsPoint {
                    RuleMark(x: .value("Selected", point.date, unit: .day))
                        .foregroundStyle(.gray.opacity(0.35))
                        .annotation(
                            position: .top,
                            alignment: .center,
                            spacing: 4,
                            overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                        ) {
                            chartTooltip(date: point.date, primary: "\(Int(point.value))", primaryLabel: "Units")
                        }
                }
            }
            .chartXSelection(value: $selectedChartDate)
            .chartXAxis {
                AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                    AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                    AxisGridLine()
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    if let v = value.as(Double.self) {
                        AxisValueLabel { Text("\(Int(v))") }
                    }
                    AxisGridLine()
                }
            }
            .frame(height: 140)
            .animation(.smooth, value: selectedChartDate)
        }
    }

    // MARK: - Chart helpers

    private var selectedRevenuePoint: ProductDetailViewModel.DailyPoint? {
        guard let selectedChartDate else { return nil }
        return viewModel.chartRevenue.first {
            Calendar.current.isDate($0.date, inSameDayAs: selectedChartDate)
        }
    }

    private var selectedUnitsPoint: ProductDetailViewModel.DailyPoint? {
        guard let selectedChartDate else { return nil }
        return viewModel.chartUnits.first {
            Calendar.current.isDate($0.date, inSameDayAs: selectedChartDate)
        }
    }

    @ViewBuilder
    private func chartTooltip(date: Date, primary: String, primaryLabel: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                .font(.caption.weight(.semibold))
            HStack(spacing: 12) {
                Text(primaryLabel)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(primary)
                    .monospacedDigit()
            }
            .font(.caption2)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background {
            RoundedRectangle(cornerRadius: 8)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
        }
        .frame(minWidth: 120)
    }

    // MARK: - Warehouse stock

    private var warehouseSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Warehouse Stock", systemImage: "building.2.fill")

            if viewModel.warehouseStock.isEmpty && !viewModel.isLoading {
                emptyRow("Not in any warehouse")
            } else {
                VStack(spacing: 8) {
                    ForEach(viewModel.warehouseStock) { entry in
                        warehouseRow(entry)
                    }
                }
            }
        }
    }

    private func warehouseRow(_ entry: ProductDetailViewModel.WarehouseStockEntry) -> some View {
        let belowMin = entry.minQuantity.map { entry.totalQty < $0 } ?? false
        let isExpanded = expandedWarehouseId == entry.id

        return VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) {
                    expandedWarehouseId = isExpanded ? nil : entry.id
                }
            } label: {
                HStack {
                    Text(entry.warehouseName)
                        .font(.subheadline.weight(.medium))
                    if belowMin, let min = entry.minQuantity {
                        Text("min \(min)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.red)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(.red.opacity(0.12)))
                    }
                    Spacer()
                    Text("\(entry.totalQty)")
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(entry.totalQty > 0 ? .primary : .secondary)
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .buttonStyle(.plain)
            .contentShape(Rectangle())

            if isExpanded {
                if entry.batches.isEmpty {
                    Text("No batches")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 10)
                } else {
                    VStack(spacing: 6) {
                        ForEach(entry.batches) { batch in
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(batch.batchNumber ?? "—")
                                        .font(.caption.monospaced())
                                    HStack(spacing: 6) {
                                        if let expString = batch.expirationDate, let expDate = parseDateOnly(expString) {
                                            Label(formatDate(expDate), systemImage: "calendar")
                                                .font(.caption2)
                                                .foregroundStyle(expirationTint(expDate))
                                        }
                                        Text("intake \(formatDate(batch.createdAt))")
                                            .font(.caption2)
                                            .foregroundStyle(.tertiary)
                                    }
                                }
                                Spacer()
                                Text("\(batch.quantity)")
                                    .font(.caption.weight(.semibold))
                                    .monospacedDigit()
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                        }
                    }
                    .padding(.bottom, 6)
                }
            }
        }
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
    }

    private func expirationTint(_ date: Date) -> Color {
        let days = Calendar.current.dateComponents([.day], from: Date(), to: date).day ?? 0
        if days < 0 { return .red }
        if days <= 14 { return .orange }
        return .secondary
    }

    // MARK: - Machine trays

    private var machineSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("In Machines", systemImage: "shippingbox.fill")

            if viewModel.trays.isEmpty && !viewModel.isLoading {
                emptyRow("Not in any machine")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.trays) { entry in
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.machineName)
                                    .font(.subheadline)
                                    .lineLimit(1)
                                HStack(spacing: 6) {
                                    Text("Slot \(entry.itemNumber)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                    if let fwb = entry.fillWhenBelow {
                                        Text("· fill ≤ \(fwb)")
                                            .font(.caption2)
                                            .foregroundStyle(.tertiary)
                                    }
                                    if let last = entry.lastSaleAt {
                                        Text("· \(timeAgo(from: last))")
                                            .font(.caption2)
                                            .foregroundStyle(.tertiary)
                                    }
                                }
                            }
                            Spacer()
                            Text("\(entry.currentStock)/\(entry.capacity)")
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(entry.currentStock > 0 ? .primary : .secondary)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
                    }
                }
            }
        }
    }

    // MARK: - Purchasing (EK)

    private func loadEkSummary() async {
        ekSummary = await purchaseVM.fetchSummaries(productIds: [productId])[productId]
    }

    private var ekSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Purchasing")).font(.headline)
            if let s = ekSummary, s.ekCount > 0, let g = s.newestGross {
                HStack {
                    Text(String(localized: "usual cost")); Spacer()
                    Text(String(format: "%.2f \u{20AC}", g)).foregroundStyle(.secondary)
                }
                if let m = PurchaseComparison.marginNet(sellpriceGross: viewModel.product?.sellprice,
                                                        ekNet: s.newestNet, rate: s.effectiveTaxRate) {
                    HStack {
                        Text(String(localized: "Margin")); Spacer()
                        Text(String(format: "%.0f%%", m.spannePct)).foregroundStyle(.secondary)
                    }
                }
            } else {
                Text(String(localized: "No purchase prices recorded yet."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Button(String(localized: "Manage purchase prices")) { showPurchasePrices = true }
                .font(.subheadline)
        }
    }

    // MARK: - Top Machines

    private func topMachinesSection(_ machines: [ProductDetailViewModel.TopMachine]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Top Machines (30 days)", systemImage: "chart.bar.fill")
            VStack(spacing: 6) {
                ForEach(machines) { m in
                    HStack {
                        Text(m.machineName)
                            .font(.subheadline)
                            .lineLimit(1)
                        Spacer()
                        Text("\(m.units) units")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        Text(formatCurrency(m.revenue))
                            .font(.subheadline.weight(.semibold).monospacedDigit())
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
                }
            }
        }
    }

    // MARK: - Recent Sales

    private var salesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Recent Sales", systemImage: "cart.fill")

            if viewModel.recentSales.isEmpty && !viewModel.isLoading {
                emptyRow("No sales yet")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.recentSales) { sale in
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(sale.machineName ?? "—")
                                    .font(.subheadline)
                                    .lineLimit(1)
                                HStack(spacing: 6) {
                                    Text(formatDateTime(sale.createdAt))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                    if let channel = sale.channel {
                                        Text("· \(channelLabel(channel))")
                                            .font(.caption2)
                                            .foregroundStyle(channelColor(channel))
                                    }
                                }
                            }
                            Spacer()
                            Text(sale.itemPrice.map { formatCurrency($0) } ?? "—")
                                .font(.subheadline.weight(.semibold).monospacedDigit())
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
                    }
                }
            }
        }
    }

    private func channelColor(_ channel: String) -> Color {
        switch channel.lowercased() {
        case "card": return .blue
        case "cashless", "nfc": return .purple
        case "cash": return .green
        default: return .secondary
        }
    }

    private func channelLabel(_ channel: String) -> String {
        switch channel.lowercased() {
        case "card": return String(localized: "channel_card")
        case "cashless", "nfc": return String(localized: "channel_cashless")
        case "cash": return String(localized: "channel_cash")
        default: return channel.capitalized
        }
    }

    // MARK: - Transactions

    private var transactionSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Stock History", systemImage: "clock.arrow.circlepath")

            if viewModel.transactions.isEmpty && !viewModel.isLoading {
                emptyRow("No stock movements yet")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.transactions) { tx in
                        HStack(alignment: .top, spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(transactionLabel(tx.transactionType))
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(transactionTint(tx.transactionType))
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(Capsule().fill(transactionTint(tx.transactionType).opacity(0.12)))
                                    Text(tx.warehouseName)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                                Text("\(formatDateTime(tx.createdAt)) · \(tx.userDisplay)")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(tx.quantityChange > 0 ? "+\(tx.quantityChange)" : "\(tx.quantityChange)")
                                    .font(.subheadline.weight(.semibold).monospacedDigit())
                                    .foregroundStyle(tx.quantityChange >= 0 ? .green : .red)
                                if let after = tx.quantityAfter {
                                    Text("→ \(after)")
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
                    }
                }
            }
        }
    }

    /// `transaction_type` is a free-form DB column; these are the values
    /// actually written by the app (see WarehouseViewModel.bookIntake /
    /// AdjustReason, plus "incoming" as the web's legacy synonym for "intake").
    private func transactionLabel(_ type: String) -> LocalizedStringKey {
        switch type.lowercased() {
        case "intake", "incoming": return "stock_history_intake"
        case "outgoing_refill": return "stock_history_outgoing_refill"
        case "adjustment_correction": return "stock_history_adjustment_correction"
        case "adjustment_damage": return "stock_history_adjustment_damage"
        case "adjustment_expired": return "stock_history_adjustment_expired"
        case "adjustment_refill_return": return "stock_history_adjustment_refill_return"
        default: return LocalizedStringKey(type.capitalized)
        }
    }

    private func transactionTint(_ type: String) -> Color {
        switch type.lowercased() {
        case "intake", "incoming": return .green
        case "outgoing_refill": return .blue
        case "adjustment_correction", "adjustment_damage", "adjustment_expired", "adjustment_refill_return": return .orange
        default: return .secondary
        }
    }

    // MARK: - Section helpers

    private func sectionHeader(_ title: LocalizedStringKey, systemImage: String? = nil) -> some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(title)
                .font(.headline)
            Spacer()
            if viewModel.isLoading {
                ProgressView().controlSize(.small)
            }
        }
    }

    private func emptyRow(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .italic()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
    }

    // MARK: - Formatters

    private func formatCurrency(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "EUR"
        formatter.minimumFractionDigits = 2
        return formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
    }

    private func formatCurrencyCompact(_ amount: Double) -> String {
        if amount >= 1000 { return String(format: "%.0fk", amount / 1000) }
        return String(format: "%.0f", amount)
    }

    private func formatDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        return f.string(from: date)
    }

    /// Parses a date-only string in the form "yyyy-MM-dd" (PostgreSQL DATE column).
    private func parseDateOnly(_ s: String) -> Date? {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: s)
    }

    private func formatDateTime(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f.string(from: date)
    }

    private func timeAgo(from date: Date) -> String {
        let interval = Date().timeIntervalSince(date)
        if interval < 60 { return String(localized: "just now") }
        if interval < 3600 { return String(localized: "\(Int(interval / 60))m ago") }
        if interval < 86400 { return String(localized: "\(Int(interval / 3600))h ago") }
        if interval < 604800 { return String(localized: "\(Int(interval / 86400))d ago") }
        let f = DateFormatter()
        f.dateStyle = .short
        return f.string(from: date)
    }
}

// MARK: - ViewModel

@MainActor
final class ProductDetailViewModel: ObservableObject {
    @Published var product: ProductInfo?
    @Published var barcodes: [BarcodeRow] = []
    @Published var kpis: ProductKpis?
    @Published var warehouseStock: [WarehouseStockEntry] = []
    @Published var trays: [TrayRow] = []
    @Published var recentSales: [SaleRow] = []
    @Published var transactions: [TransactionRow] = []
    @Published var chartRevenue: [DailyPoint] = []
    @Published var chartUnits: [DailyPoint] = []
    @Published var isLoading = false

    // MARK: - Models

    struct ProductInfo: Equatable {
        let id: UUID
        let name: String?
        let imagePath: String?
        let sellprice: Double?
        let discontinued: Bool
        let category: UUID?
        let categoryName: String?

        /// Convert to the canonical `Product` model (used by `ProductEditSheet`).
        func asProduct() -> Product {
            Product(id: id, name: name, imagePath: imagePath, discontinued: discontinued, sellprice: sellprice, category: category)
        }
    }

    struct BarcodeRow: Identifiable, Equatable {
        let id: UUID
        let barcode: String
        let format: String?
    }

    struct ProductKpis: Equatable {
        let warehouseTotalQty: Int
        let warehouseCount: Int
        let trayTotalStock: Int
        let trayTotalCapacity: Int
        let machineCount: Int
        let salesTodayUnits: Int
        let salesTodayRevenue: Double
        let velocityUnitsPerDay: Double
        let velocityWindowDays: Int
        let topMachines: [TopMachine]
    }

    struct TopMachine: Identifiable, Equatable {
        let machineId: UUID
        let machineName: String
        let units: Int
        let revenue: Double
        var id: UUID { machineId }
    }

    struct WarehouseStockEntry: Identifiable, Equatable {
        let id: UUID
        let warehouseName: String
        let totalQty: Int
        let minQuantity: Int?
        let batches: [BatchRow]
    }

    struct BatchRow: Identifiable, Equatable {
        let id: UUID
        let batchNumber: String?
        /// DATE column — stored raw because Supabase Swift's default decoder
        /// expects ISO-8601 timestamps and chokes on bare `2026-04-15`.
        let expirationDate: String?
        let quantity: Int
        let createdAt: Date
    }

    struct TrayRow: Identifiable, Equatable {
        let id: UUID
        let machineId: UUID
        let machineName: String
        let itemNumber: Int
        let currentStock: Int
        let capacity: Int
        let fillWhenBelow: Int?
        var lastSaleAt: Date?
    }

    struct SaleRow: Identifiable, Equatable {
        let id: UUID
        let createdAt: Date
        let itemPrice: Double?
        let channel: String?
        let machineId: UUID?
        let machineName: String?
    }

    struct TransactionRow: Identifiable, Equatable {
        let id: UUID
        let createdAt: Date
        let transactionType: String
        let quantityChange: Int
        let quantityAfter: Int?
        let warehouseId: UUID
        let warehouseName: String
        let userDisplay: String
        let notes: String?
    }

    struct DailyPoint: Identifiable, Equatable {
        let date: Date
        let value: Double
        var id: Date { date }

        /// True when the date falls on Sat/Su per the user's calendar.
        var isWeekend: Bool {
            Calendar.current.isDateInWeekend(date)
        }
    }

    /// Average revenue per day across the loaded chart window (incl. zero days).
    var revenueAverage: Double {
        guard !chartRevenue.isEmpty else { return 0 }
        return chartRevenue.reduce(0) { $0 + $1.value } / Double(chartRevenue.count)
    }

    /// Average units per day across the loaded chart window (incl. zero days).
    var unitsAverage: Double {
        guard !chartUnits.isEmpty else { return 0 }
        return chartUnits.reduce(0) { $0 + $1.value } / Double(chartUnits.count)
    }

    private var client: SupabaseClient { SupabaseService.shared.client }

    // MARK: - Load

    func load(productId: UUID) async {
        isLoading = true
        defer { isLoading = false }

        async let productTask: () = loadProduct(productId: productId)
        async let barcodesTask: () = loadBarcodes(productId: productId)
        async let kpisTask: () = loadKpis(productId: productId)
        async let warehouseTask: () = loadWarehouseStock(productId: productId)
        async let traysTask: () = loadTrays(productId: productId)
        async let salesTask: () = loadRecentSales(productId: productId)
        async let txTask: () = loadTransactions(productId: productId)
        async let chartTask: () = loadCharts(productId: productId)

        _ = await (productTask, barcodesTask, kpisTask, warehouseTask, traysTask, salesTask, txTask, chartTask)
    }

    // MARK: - Queries

    private func loadProduct(productId: UUID) async {
        struct Row: Decodable {
            let id: UUID
            let name: String?
            let imagePath: String?
            let sellprice: Double?
            let discontinued: Bool?
            let category: UUID?
            let productCategory: Named?

            enum CodingKeys: String, CodingKey {
                case id, name, sellprice, discontinued, category
                case imagePath = "image_path"
                case productCategory = "product_category"
            }
        }
        struct Named: Decodable { let name: String? }

        do {
            let rows: [Row] = try await client
                .from("products")
                .select("id, name, image_path, sellprice, discontinued, category, product_category(name)")
                .eq("id", value: productId.uuidString)
                .limit(1)
                .execute()
                .value
            if let row = rows.first {
                product = ProductInfo(
                    id: row.id,
                    name: row.name,
                    imagePath: row.imagePath,
                    sellprice: row.sellprice,
                    discontinued: row.discontinued ?? false,
                    category: row.category,
                    categoryName: row.productCategory?.name
                )
            }
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadProduct failed: \(error.localizedDescription)")
        }
    }

    private func loadBarcodes(productId: UUID) async {
        struct Row: Decodable {
            let id: UUID
            let barcode: String
            let format: String?
        }
        do {
            let rows: [Row] = try await client
                .from("product_barcodes")
                .select("id, barcode, format")
                .eq("product_id", value: productId.uuidString)
                .order("created_at", ascending: true)
                .execute()
                .value
            barcodes = rows.map { BarcodeRow(id: $0.id, barcode: $0.barcode, format: $0.format) }
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadBarcodes failed: \(error.localizedDescription)")
        }
    }

    private func loadKpis(productId: UUID) async {
        struct TopMachineRow: Decodable {
            let machineId: UUID
            let machineName: String?
            let units: Int
            let revenue: Double

            enum CodingKeys: String, CodingKey {
                case units, revenue
                case machineId = "machine_id"
                case machineName = "machine_name"
            }
        }

        struct KpiPayload: Decodable {
            let warehouseTotalQty: Int
            let warehouseCount: Int
            let trayTotalStock: Int
            let trayTotalCapacity: Int
            let machineCount: Int
            let salesTodayUnits: Int
            let salesTodayRevenue: Double
            let velocityUnitsPerDay: Double
            let velocityWindowDays: Int
            let topMachines: [TopMachineRow]

            enum CodingKeys: String, CodingKey {
                case warehouseTotalQty = "warehouse_total_qty"
                case warehouseCount = "warehouse_count"
                case trayTotalStock = "tray_total_stock"
                case trayTotalCapacity = "tray_total_capacity"
                case machineCount = "machine_count"
                case salesTodayUnits = "sales_today_units"
                case salesTodayRevenue = "sales_today_revenue"
                case velocityUnitsPerDay = "velocity_units_per_day"
                case velocityWindowDays = "velocity_window_days"
                case topMachines = "top_machines"
            }
        }

        do {
            let payload: KpiPayload = try await client
                .rpc("get_product_detail_kpis", params: [
                    "p_product_id": AnyJSON.string(productId.uuidString),
                    "p_days": AnyJSON.integer(30)
                ])
                .execute()
                .value

            kpis = ProductKpis(
                warehouseTotalQty: payload.warehouseTotalQty,
                warehouseCount: payload.warehouseCount,
                trayTotalStock: payload.trayTotalStock,
                trayTotalCapacity: payload.trayTotalCapacity,
                machineCount: payload.machineCount,
                salesTodayUnits: payload.salesTodayUnits,
                salesTodayRevenue: payload.salesTodayRevenue,
                velocityUnitsPerDay: payload.velocityUnitsPerDay,
                velocityWindowDays: payload.velocityWindowDays,
                topMachines: payload.topMachines.map {
                    TopMachine(machineId: $0.machineId, machineName: $0.machineName ?? "—", units: $0.units, revenue: $0.revenue)
                }
            )
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadKpis failed: \(error.localizedDescription)")
        }
    }

    private func loadWarehouseStock(productId: UUID) async {
        struct BatchFetch: Decodable {
            let id: UUID
            let warehouseId: UUID
            let batchNumber: String?
            let expirationDate: String?
            let quantity: Int
            let createdAt: Date
            let warehouses: Named?

            enum CodingKeys: String, CodingKey {
                case id, quantity, warehouses
                case warehouseId = "warehouse_id"
                case batchNumber = "batch_number"
                case expirationDate = "expiration_date"
                case createdAt = "created_at"
            }
        }
        struct MinRow: Decodable {
            let warehouseId: UUID
            let minQuantity: Int

            enum CodingKeys: String, CodingKey {
                case warehouseId = "warehouse_id"
                case minQuantity = "min_quantity"
            }
        }
        struct Named: Decodable { let name: String? }

        do {
            async let batchesRes: [BatchFetch] = client
                .from("warehouse_stock_batches")
                .select("id, warehouse_id, batch_number, expiration_date, quantity, created_at, warehouses(name)")
                .eq("product_id", value: productId.uuidString)
                .gt("quantity", value: 0)
                .order("expiration_date", ascending: true)
                .execute()
                .value
            async let minRes: [MinRow] = client
                .from("product_min_stock")
                .select("warehouse_id, min_quantity")
                .eq("product_id", value: productId.uuidString)
                .execute()
                .value

            let (batches, mins) = try await (batchesRes, minRes)

            var minByWarehouse: [UUID: Int] = [:]
            for row in mins { minByWarehouse[row.warehouseId] = row.minQuantity }

            var grouped: [UUID: (name: String, total: Int, batches: [BatchRow])] = [:]
            for row in batches {
                let name = row.warehouses?.name ?? "—"
                var entry = grouped[row.warehouseId] ?? (name: name, total: 0, batches: [])
                entry.total += row.quantity
                entry.batches.append(BatchRow(
                    id: row.id,
                    batchNumber: row.batchNumber,
                    expirationDate: row.expirationDate,
                    quantity: row.quantity,
                    createdAt: row.createdAt
                ))
                grouped[row.warehouseId] = entry
            }

            warehouseStock = grouped
                .map { (id, v) in
                    WarehouseStockEntry(
                        id: id,
                        warehouseName: v.name,
                        totalQty: v.total,
                        minQuantity: minByWarehouse[id],
                        batches: v.batches
                    )
                }
                .sorted { $0.warehouseName.localizedCompare($1.warehouseName) == .orderedAscending }
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadWarehouseStock failed: \(error.localizedDescription)")
        }
    }

    private func loadTrays(productId: UUID) async {
        struct Fetch: Decodable {
            let id: UUID
            let machineId: UUID
            let itemNumber: Int
            let currentStock: Int
            let capacity: Int
            let fillWhenBelow: Int?
            let vendingMachine: Named?

            enum CodingKeys: String, CodingKey {
                case id, capacity
                case machineId = "machine_id"
                case itemNumber = "item_number"
                case currentStock = "current_stock"
                case fillWhenBelow = "fill_when_below"
                case vendingMachine = "vendingMachine"
            }
        }
        struct Named: Decodable { let name: String? }

        do {
            let rows: [Fetch] = try await client
                .from("machine_trays")
                .select("id, machine_id, item_number, current_stock, capacity, fill_when_below, vendingMachine(name)")
                .eq("product_id", value: productId.uuidString)
                .execute()
                .value

            trays = rows
                .map { r in
                    TrayRow(
                        id: r.id,
                        machineId: r.machineId,
                        machineName: r.vendingMachine?.name ?? "—",
                        itemNumber: r.itemNumber,
                        currentStock: r.currentStock,
                        capacity: r.capacity,
                        fillWhenBelow: r.fillWhenBelow,
                        lastSaleAt: nil
                    )
                }
                .sorted { a, b in
                    if a.machineName != b.machineName {
                        return a.machineName.localizedCompare(b.machineName) == .orderedAscending
                    }
                    return a.itemNumber < b.itemNumber
                }

            // Fill last_sale_at from already-loaded recentSales (best effort).
            applyLastSalePerMachine()
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadTrays failed: \(error.localizedDescription)")
        }
    }

    private func loadRecentSales(productId: UUID) async {
        struct Fetch: Decodable {
            let id: UUID
            let createdAt: Date
            let itemPrice: Double?
            let channel: String?
            let machineId: UUID?
            let vendingMachine: Named?

            enum CodingKeys: String, CodingKey {
                case id, channel
                case createdAt = "created_at"
                case itemPrice = "item_price"
                case machineId = "machine_id"
                case vendingMachine = "vendingMachine"
            }
        }
        struct Named: Decodable { let name: String? }

        do {
            let rows: [Fetch] = try await client
                .from("sales")
                .select("id, created_at, item_price, channel, machine_id, vendingMachine(name)")
                .eq("product_id", value: productId.uuidString)
                .order("created_at", ascending: false)
                .limit(50)
                .execute()
                .value

            recentSales = rows.map {
                SaleRow(
                    id: $0.id,
                    createdAt: $0.createdAt,
                    itemPrice: $0.itemPrice,
                    channel: $0.channel,
                    machineId: $0.machineId,
                    machineName: $0.vendingMachine?.name
                )
            }

            applyLastSalePerMachine()
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadRecentSales failed: \(error.localizedDescription)")
        }
    }

    private func applyLastSalePerMachine() {
        var lastByMachine: [UUID: Date] = [:]
        for s in recentSales {
            guard let mid = s.machineId else { continue }
            if let existing = lastByMachine[mid] {
                if s.createdAt > existing { lastByMachine[mid] = s.createdAt }
            } else {
                lastByMachine[mid] = s.createdAt
            }
        }
        trays = trays.map { tray in
            var t = tray
            t.lastSaleAt = lastByMachine[tray.machineId]
            return t
        }
    }

    private func loadTransactions(productId: UUID) async {
        struct Fetch: Decodable {
            let id: UUID
            let createdAt: Date
            let transactionType: String
            let quantityChange: Int
            let quantityAfter: Int?
            let warehouseId: UUID
            let userId: UUID?
            let notes: String?
            let warehouses: Named?

            enum CodingKeys: String, CodingKey {
                case id, notes, warehouses
                case createdAt = "created_at"
                case transactionType = "transaction_type"
                case quantityChange = "quantity_change"
                case quantityAfter = "quantity_after"
                case warehouseId = "warehouse_id"
                case userId = "user_id"
            }
        }
        struct Named: Decodable { let name: String? }
        struct UserRow: Decodable {
            let id: UUID
            let firstName: String?
            let lastName: String?
            let email: String?

            enum CodingKeys: String, CodingKey {
                case id, email
                case firstName = "first_name"
                case lastName = "last_name"
            }
        }

        do {
            let rows: [Fetch] = try await client
                .from("warehouse_transactions")
                .select("id, created_at, transaction_type, quantity_change, quantity_after, warehouse_id, user_id, notes, warehouses(name)")
                .eq("product_id", value: productId.uuidString)
                .order("created_at", ascending: false)
                .limit(50)
                .execute()
                .value

            // Resolve user display names (FK points to auth.users; embed not allowed)
            var users: [UUID: String] = [:]
            let userIds = Array(Set(rows.compactMap { $0.userId }))
            if !userIds.isEmpty {
                let userRows: [UserRow] = (try? await client
                    .from("users")
                    .select("id, first_name, last_name, email")
                    .in("id", values: userIds.map { $0.uuidString })
                    .execute()
                    .value) ?? []
                for u in userRows {
                    let full = [u.firstName, u.lastName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
                    users[u.id] = !full.isEmpty ? full : (u.email ?? "—")
                }
            }

            transactions = rows.map {
                TransactionRow(
                    id: $0.id,
                    createdAt: $0.createdAt,
                    transactionType: $0.transactionType,
                    quantityChange: $0.quantityChange,
                    quantityAfter: $0.quantityAfter,
                    warehouseId: $0.warehouseId,
                    warehouseName: $0.warehouses?.name ?? "—",
                    userDisplay: $0.userId.flatMap { users[$0] } ?? "—",
                    notes: $0.notes
                )
            }
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadTransactions failed: \(error.localizedDescription)")
        }
    }

    private func loadCharts(productId: UUID) async {
        struct Fetch: Decodable {
            let createdAt: Date
            let itemPrice: Double?

            enum CodingKeys: String, CodingKey {
                case createdAt = "created_at"
                case itemPrice = "item_price"
            }
        }

        let calendar = Calendar.current
        let now = Date()
        let startOfToday = calendar.startOfDay(for: now)
        guard let since = calendar.date(byAdding: .day, value: -29, to: startOfToday) else { return }

        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        do {
            let rows: [Fetch] = try await client
                .from("sales")
                .select("created_at, item_price")
                .eq("product_id", value: productId.uuidString)
                .gte("created_at", value: formatter.string(from: since))
                .execute()
                .value

            // Pre-populate 30 buckets
            var revenue: [Date: Double] = [:]
            var units: [Date: Int] = [:]
            for offset in 0..<30 {
                if let day = calendar.date(byAdding: .day, value: offset, to: since) {
                    let key = calendar.startOfDay(for: day)
                    revenue[key] = 0
                    units[key] = 0
                }
            }
            for row in rows {
                let key = calendar.startOfDay(for: row.createdAt)
                revenue[key, default: 0] += row.itemPrice ?? 0
                units[key, default: 0] += 1
            }

            chartRevenue = revenue
                .map { DailyPoint(date: $0.key, value: $0.value) }
                .sorted { $0.date < $1.date }
            chartUnits = units
                .map { DailyPoint(date: $0.key, value: Double($0.value)) }
                .sorted { $0.date < $1.date }
        } catch is CancellationError {
        } catch {
            print("[ProductDetailVM] loadCharts failed: \(error.localizedDescription)")
        }
    }
}

