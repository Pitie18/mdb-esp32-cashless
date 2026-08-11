import Foundation
import Supabase

/// Drives the Analytics page: filter state, the two RPC round trips, and the
/// distinction between "this failed" and "this backend is too old".
@MainActor
final class AnalyticsViewModel: ObservableObject {
    // MARK: - Filter state

    @Published var preset: AnalyticsRangePreset = .days30 { didSet { rangeDidChange() } }
    @Published var customFrom: Date = Calendar.current.date(byAdding: .day, value: -29, to: Date())!
    @Published var customTo: Date = Date()
    @Published var selectedMachineIds: Set<UUID> = []
    @Published var selectedCategoryIds: Set<UUID> = []

    /// Shared by the trend chart and the breakdown list — switching either
    /// segment switches both, by design.
    @Published var metric: AnalyticsMetric = .revenue
    @Published var dimension: AnalyticsDimension = .product { didSet { dimensionDidChange() } }

    // MARK: - Data

    @Published var summary: AnalyticsSummary?
    @Published var rows: [AnalyticsBreakdownRow] = []
    @Published var machines: [VendingMachine] = []
    @Published var categories: [ProductCategory] = []

    @Published var isLoading = false
    @Published var isLoadingRows = false
    @Published var error: String?

    /// True when the connected server predates the analytics migrations. The
    /// whole page depends on the RPCs, so this must be visible rather than
    /// silently swallowed the way the dashboard treats get_new_deals_count.
    @Published var backendUnsupported = false

    /// Guards the tab-root `.task` against re-firing on every tab re-selection.
    @Published var didRunInitialLoad = false

    private let client = SupabaseService.shared.client
    private var companyId: UUID?

    // MARK: - Derived

    var sortedRows: [AnalyticsBreakdownRow] { sortRows(rows, by: metric) }

    var range: (from: Date, to: Date) {
        dateRange(for: preset, customFrom: customFrom, customTo: customTo)
    }

    var rangeLabel: String {
        let r = range
        let lastDay = Calendar.current.date(byAdding: .day, value: -1, to: r.to) ?? r.to
        return "\(Self.dayFormatter.string(from: r.from)) – \(Self.dayFormatter.string(from: lastDay))"
    }

    /// The previous window is `[from - span, from)` — not "last month". Spelled
    /// out in the UI so nobody mistakes it for a calendar period.
    var previousRangeLabel: String {
        let r = range
        let span = r.to.timeIntervalSince(r.from)
        let prevFrom = r.from.addingTimeInterval(-span)
        let prevLast = Calendar.current.date(byAdding: .day, value: -1, to: r.from) ?? r.from
        return "\(Self.dayFormatter.string(from: prevFrom)) – \(Self.dayFormatter.string(from: prevLast))"
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .none
        return f
    }()

    // MARK: - Loading

    func load() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let company = try await resolveCompanyId()
            summary = try await client
                .rpc("get_sales_analytics_summary", params: rpcParams(companyId: company))
                .execute()
                .value
            backendUnsupported = false
        } catch is CancellationError {
        } catch {
            handle(error)
        }
        await loadBreakdown()
    }

    func loadBreakdown() async {
        isLoadingRows = true
        defer { isLoadingRows = false }
        do {
            let company = try await resolveCompanyId()
            var params = rpcParams(companyId: company)
            params["p_dimension"] = .string(dimension.rawValue)
            params["p_product_id"] = .null
            rows = try await client
                .rpc("get_sales_analytics_breakdown", params: params)
                .execute()
                .value
            backendUnsupported = false
        } catch is CancellationError {
        } catch {
            handle(error)
        }
    }

    /// Per-machine distribution of one product — the detail sheet reuses the
    /// breakdown RPC with the machine dimension narrowed to a single product.
    func loadProductMachines(productId: UUID) async -> [AnalyticsBreakdownRow] {
        do {
            let company = try await resolveCompanyId()
            var params = rpcParams(companyId: company)
            params["p_dimension"] = .string(AnalyticsDimension.machine.rawValue)
            params["p_product_id"] = .string(productId.uuidString)
            return try await client
                .rpc("get_sales_analytics_breakdown", params: params)
                .execute()
                .value
        } catch {
            return []
        }
    }

    func loadFilterOptions() async {
        do {
            let company = try await resolveCompanyId()
            async let machineTask: [VendingMachine] = client
                .from("vendingMachine")
                .select("id, name")
                .eq("company", value: company.uuidString)
                .order("name")
                .execute()
                .value
            async let categoryTask: [ProductCategory] = client
                .from("product_category")
                .select("id, name, company")
                .eq("company", value: company.uuidString)
                .order("name")
                .execute()
                .value
            let (m, c) = try await (machineTask, categoryTask)
            machines = m
            categories = c
        } catch is CancellationError {
        } catch {
            // Non-fatal: the page still works with "all machines / all categories".
        }
    }

    // MARK: - Reactions

    private func rangeDidChange() {
        Task { await load() }
    }

    private func dimensionDidChange() {
        Task { await loadBreakdown() }
    }

    /// Called by the filter sheets after the user commits a selection, so a
    /// multi-select does not fire one round trip per tap.
    func filtersCommitted() {
        Task { await load() }
    }

    // MARK: - Helpers

    private func rpcParams(companyId: UUID) -> [String: AnyJSON] {
        let r = range
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return [
            "p_company_id": .string(companyId.uuidString),
            "p_from": .string(formatter.string(from: r.from)),
            "p_to": .string(formatter.string(from: r.to)),
            "p_machine_ids": selectedMachineIds.isEmpty
                ? .null : .array(selectedMachineIds.map { .string($0.uuidString) }),
            "p_category_ids": selectedCategoryIds.isEmpty
                ? .null : .array(selectedCategoryIds.map { .string($0.uuidString) }),
            "p_timezone": .string(TimeZone.current.identifier),
        ]
    }

    /// PostgREST answers an unknown function with 404 / PGRST202. That is a
    /// backend-version problem, not a bug, and gets its own screen.
    private func handle(_ error: Error) {
        let text = "\(error)"
        if text.contains("PGRST202") || text.contains("Could not find the function") {
            backendUnsupported = true
            self.error = nil
        } else {
            self.error = error.localizedDescription
        }
    }

    private func resolveCompanyId() async throws -> UUID {
        if let companyId { return companyId }
        let userId = try await client.auth.session.user.id
        struct OrgMember: Decodable {
            let companyId: UUID
            enum CodingKeys: String, CodingKey { case companyId = "company_id" }
        }
        let members: [OrgMember] = try await client
            .from("organization_members")
            .select("company_id")
            .eq("user_id", value: userId.uuidString)
            .limit(1)
            .execute()
            .value
        guard let id = members.first?.companyId else {
            throw NSError(domain: "AnalyticsVM", code: 0, userInfo: [
                NSLocalizedDescriptionKey: String(localized: "Could not determine company")
            ])
        }
        companyId = id
        return id
    }
}
