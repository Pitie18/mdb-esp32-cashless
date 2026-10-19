import SwiftUI
import Supabase

/// Warehouse › History (toolbar icon): every stock movement of one warehouse,
/// newest first, filterable by type and paged. Read-only; mirrors the web's
/// history tab.
struct WarehouseHistoryView: View {
    let warehouseId: UUID

    @State private var entries: [Entry] = []
    @State private var filter: TypeFilter = .all
    @State private var isLoading = false
    @State private var hasMore = false
    @State private var error: String?

    private static let pageSize = 50
    private var client: SupabaseClient { SupabaseService.shared.client }

    enum TypeFilter: String, CaseIterable, Identifiable {
        case all, incoming, refill, adjustment
        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: return String(localized: "All")
            case .incoming: return String(localized: "Incoming")
            case .refill: return String(localized: "Refill")
            case .adjustment: return String(localized: "Corrections")
            }
        }

        /// nil = no filter.
        var types: [String]? {
            switch self {
            case .all: return nil
            case .incoming: return ["intake", "incoming", "transfer_in"]
            case .refill: return ["outgoing_refill", "adjustment_refill_return"]
            case .adjustment: return ["adjustment_correction", "adjustment_damage", "adjustment_expired", "transfer_out"]
            }
        }
    }

    struct Entry: Decodable, Identifiable {
        let id: UUID
        let createdAt: Date
        let transactionType: String
        let quantityChange: Int
        let batchNumber: String?
        let notes: String?
        let products: ProductRef?
        let metadata: Metadata?

        struct ProductRef: Decodable {
            let name: String?
            let imagePath: String?
            enum CodingKeys: String, CodingKey { case name; case imagePath = "image_path" }
        }
        struct Metadata: Decodable {
            let userDisplay: String?
            let userEmail: String?
            enum CodingKeys: String, CodingKey {
                case userDisplay = "_user_display"
                case userEmail = "_user_email"
            }
        }

        enum CodingKeys: String, CodingKey {
            case id, notes, products, metadata
            case createdAt = "created_at"
            case transactionType = "transaction_type"
            case quantityChange = "quantity_change"
            case batchNumber = "batch_number"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(UUID.self, forKey: .id)
            createdAt = try c.decode(Date.self, forKey: .createdAt)
            transactionType = try c.decode(String.self, forKey: .transactionType)
            quantityChange = try c.decode(Int.self, forKey: .quantityChange)
            batchNumber = try c.decodeIfPresent(String.self, forKey: .batchNumber)
            notes = try c.decodeIfPresent(String.self, forKey: .notes)
            products = try c.decodeIfPresent(ProductRef.self, forKey: .products)
            // Free-form jsonb: never fail a row over its shape.
            metadata = try? c.decodeIfPresent(Metadata.self, forKey: .metadata)
        }

        var user: String? {
            let name = metadata?.userDisplay ?? metadata?.userEmail
            return (name?.isEmpty ?? true) ? nil : name
        }
    }

    var body: some View {
        List {
            Section {
                Picker(String(localized: "Type"), selection: $filter) {
                    ForEach(TypeFilter.allCases) { Text($0.label).tag($0) }
                }
            }

            if entries.isEmpty && !isLoading {
                Text(String(localized: "No movements yet"))
                    .foregroundStyle(.secondary)
            }

            Section {
                ForEach(entries) { entry in
                    row(entry)
                }
                if hasMore {
                    Button(String(localized: "Load more")) {
                        Task { await load(reset: false) }
                    }
                    .disabled(isLoading)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "History"))
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if isLoading && entries.isEmpty { ProgressView() }
        }
        .task(id: filter) { await load(reset: true) }
        .refreshable { await load(reset: true) }
        .alert("Error", isPresented: .init(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    @ViewBuilder
    private func row(_ entry: Entry) -> some View {
        HStack(spacing: 12) {
            ProductImage(imagePath: entry.products?.imagePath, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.products?.name ?? "—")
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(Self.typeLabel(entry.transactionType))
                        .font(.caption2.bold())
                        .foregroundStyle(Self.typeColor(entry.transactionType))
                    Text(entry.createdAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                let details = [entry.user, entry.batchNumber].compactMap { $0 }
                if !details.isEmpty {
                    Text(details.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
            Spacer()
            Text(verbatim: entry.quantityChange > 0 ? "+\(entry.quantityChange)" : "\(entry.quantityChange)")
                .font(.body.bold().monospacedDigit())
                .foregroundStyle(entry.quantityChange > 0 ? Color.green : (entry.quantityChange < 0 ? Color.red : Color.secondary))
        }
    }

    private func load(reset: Bool) async {
        isLoading = true
        defer { isLoading = false }
        let offset = reset ? 0 : entries.count
        do {
            var query = client
                .from("warehouse_transactions")
                .select("id, created_at, transaction_type, quantity_change, batch_number, notes, metadata, products(name, image_path)")
                .eq("warehouse_id", value: warehouseId.uuidString)
            if let types = filter.types {
                query = query.in("transaction_type", values: types)
            }
            let page: [Entry] = try await query
                .order("created_at", ascending: false)
                .range(from: offset, to: offset + Self.pageSize - 1)
                .execute()
                .value
            entries = reset ? page : entries + page
            hasMore = page.count >= Self.pageSize
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    static func typeLabel(_ type: String) -> String {
        switch type {
        case "intake", "incoming": return String(localized: "Incoming")
        case "outgoing_refill": return String(localized: "Refill")
        case "adjustment_damage": return String(localized: "Damaged")
        case "adjustment_expired": return String(localized: "Expired")
        case "adjustment_correction": return String(localized: "Correction")
        case "adjustment_refill_return": return String(localized: "Refill return")
        case "transfer_out": return String(localized: "Transfer out")
        case "transfer_in": return String(localized: "Transfer in")
        default: return type
        }
    }

    static func typeColor(_ type: String) -> Color {
        switch type {
        case "intake", "incoming", "transfer_in": return .green
        case "outgoing_refill", "adjustment_refill_return": return .blue
        case "adjustment_damage", "adjustment_expired": return .red
        default: return .orange
        }
    }
}
