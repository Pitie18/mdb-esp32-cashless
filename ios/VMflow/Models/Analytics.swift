import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// DTOs and pure helpers for the Analytics page.
//
// Mirrors the JSON contracts of the RPCs get_sales_analytics_summary and
// get_sales_analytics_breakdown (migrations 20260811000000 / 20260811000100).
//
// Numeric fields are decoded defensively: PostgREST may serialize a Postgres
// `numeric` as a JSON number or as a string depending on version, and a whole
// page going blank because of that is not an acceptable failure mode.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Enums

/// The single metric that drives BOTH the trend chart and the breakdown list.
/// There is deliberately no "average per day" metric: in a daily chart the
/// per-day average is the daily value, so it lives as a row subtitle instead.
enum AnalyticsMetric: String, CaseIterable, Identifiable {
    case units, revenue, grossProfit
    var id: String { rawValue }
}

enum AnalyticsDimension: String, CaseIterable, Identifiable {
    case product, category, machine
    var id: String { rawValue }
}

enum AnalyticsRangePreset: String, CaseIterable, Identifiable {
    case days7, days30, days90, thisMonth, lastMonth, custom
    var id: String { rawValue }
}

enum ChartBucket { case day, week }

// MARK: - Decoding helpers

// The container and its key must share one generic parameter — two separate
// `some CodingKey` positions would be independent opaque types and not compile.
private func flexDouble<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                      default fallback: Double = 0) -> Double {
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return d }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let d = Double(s) { return d }
    return fallback
}

private func flexDoubleOptional<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K) -> Double? {
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return d }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let d = Double(s) { return d }
    return nil
}

private func flexInt<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                   default fallback: Int = 0) -> Int {
    if let i = (try? container.decodeIfPresent(Int.self, forKey: key)) ?? nil { return i }
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return Int(d) }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let i = Int(s) { return i }
    return fallback
}

private func flexString<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                      default fallback: String) -> String {
    ((try? container.decodeIfPresent(String.self, forKey: key)) ?? nil) ?? fallback
}

/// `yyyy-MM-dd` as returned by a Postgres `date` inside json_build_object.
/// Parsed by hand rather than via a decoder strategy so the summary's
/// timestamptz fields and these plain dates can coexist.
private let isoDayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone.current
    f.dateFormat = "yyyy-MM-dd"
    return f
}()

// MARK: - Summary DTOs

struct AnalyticsRangeInfo: Decodable {
    let days: Double
    let timezone: String

    enum CodingKeys: String, CodingKey { case days, timezone }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        days = flexDouble(c, .days, default: 1)
        timezone = flexString(c, .timezone, default: "UTC")
    }
}

struct AnalyticsTotals: Decodable {
    let units: Int
    let revenueGross: Double
    let revenueNet: Double
    let costNet: Double
    let grossProfit: Double
    let avgTicket: Double
    let avgDailyUnits: Double
    let avgDailyRevenue: Double
    let avgDailyGrossProfit: Double

    enum CodingKeys: String, CodingKey {
        case units
        case revenueGross = "revenue_gross"
        case revenueNet = "revenue_net"
        case costNet = "cost_net"
        case grossProfit = "gross_profit"
        case avgTicket = "avg_ticket"
        case avgDailyUnits = "avg_daily_units"
        case avgDailyRevenue = "avg_daily_revenue"
        case avgDailyGrossProfit = "avg_daily_gross_profit"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        revenueNet = flexDouble(c, .revenueNet)
        costNet = flexDouble(c, .costNet)
        grossProfit = flexDouble(c, .grossProfit)
        avgTicket = flexDouble(c, .avgTicket)
        avgDailyUnits = flexDouble(c, .avgDailyUnits)
        avgDailyRevenue = flexDouble(c, .avgDailyRevenue)
        avgDailyGrossProfit = flexDouble(c, .avgDailyGrossProfit)
    }

    private init(units: Int, revenueGross: Double, revenueNet: Double, costNet: Double,
                 grossProfit: Double, avgTicket: Double, avgDailyUnits: Double,
                 avgDailyRevenue: Double, avgDailyGrossProfit: Double) {
        self.units = units; self.revenueGross = revenueGross; self.revenueNet = revenueNet
        self.costNet = costNet; self.grossProfit = grossProfit; self.avgTicket = avgTicket
        self.avgDailyUnits = avgDailyUnits; self.avgDailyRevenue = avgDailyRevenue
        self.avgDailyGrossProfit = avgDailyGrossProfit
    }

    /// The value of the metric currently selected in the UI.
    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenueGross
        case .grossProfit: return grossProfit
        }
    }

    static let empty = AnalyticsTotals(
        units: 0, revenueGross: 0, revenueNet: 0, costNet: 0, grossProfit: 0,
        avgTicket: 0, avgDailyUnits: 0, avgDailyRevenue: 0, avgDailyGrossProfit: 0)
}

struct AnalyticsDailyPoint: Decodable, Identifiable, Equatable {
    let day: Date
    let units: Int
    let revenueGross: Double
    let grossProfit: Double

    var id: Date { day }

    enum CodingKeys: String, CodingKey {
        case day, units
        case revenueGross = "revenue_gross"
        case grossProfit = "gross_profit"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let raw = try c.decode(String.self, forKey: .day)
        day = isoDayFormatter.date(from: raw) ?? Date(timeIntervalSince1970: 0)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        grossProfit = flexDouble(c, .grossProfit)
    }

    /// Memberwise initialiser — used when folding daily points into weekly buckets.
    init(day: Date, units: Int, revenueGross: Double, grossProfit: Double) {
        self.day = day; self.units = units
        self.revenueGross = revenueGross; self.grossProfit = grossProfit
    }

    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenueGross
        case .grossProfit: return grossProfit
        }
    }

    var isWeekend: Bool { Calendar.current.isDateInWeekend(day) }
}

struct AnalyticsHeatCell: Decodable, Identifiable, Equatable {
    /// ISO weekday: 1 = Monday … 7 = Sunday.
    let dow: Int
    let hour: Int
    let units: Int
    let revenueGross: Double

    var id: Int { dow * 100 + hour }

    enum CodingKeys: String, CodingKey {
        case dow, hour, units
        case revenueGross = "revenue_gross"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dow = flexInt(c, .dow)
        hour = flexInt(c, .hour)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
    }
}

struct AnalyticsChannel: Decodable, Identifiable, Equatable {
    let channel: String
    let units: Int
    let revenueGross: Double
    let avgTicket: Double

    var id: String { channel }

    enum CodingKeys: String, CodingKey {
        case channel, units
        case revenueGross = "revenue_gross"
        case avgTicket = "avg_ticket"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        channel = flexString(c, .channel, default: "unknown")
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        avgTicket = flexDouble(c, .avgTicket)
    }
}

struct AnalyticsSummary: Decodable {
    let range: AnalyticsRangeInfo
    let totals: AnalyticsTotals
    let previous: AnalyticsTotals
    let daily: [AnalyticsDailyPoint]
    let heatmap: [AnalyticsHeatCell]
    let channels: [AnalyticsChannel]
    let missingCostProducts: Int
    let unknownProductUnits: Int

    enum CodingKeys: String, CodingKey {
        case range, totals, previous, daily, heatmap, channels
        case missingCostProducts = "missing_cost_products"
        case unknownProductUnits = "unknown_product_units"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        range = try c.decode(AnalyticsRangeInfo.self, forKey: .range)
        totals = try c.decode(AnalyticsTotals.self, forKey: .totals)
        previous = try c.decode(AnalyticsTotals.self, forKey: .previous)
        daily = (try? c.decode([AnalyticsDailyPoint].self, forKey: .daily)) ?? []
        heatmap = (try? c.decode([AnalyticsHeatCell].self, forKey: .heatmap)) ?? []
        channels = (try? c.decode([AnalyticsChannel].self, forKey: .channels)) ?? []
        missingCostProducts = flexInt(c, .missingCostProducts)
        unknownProductUnits = flexInt(c, .unknownProductUnits)
    }
}

// MARK: - Breakdown DTO

struct AnalyticsBreakdownRow: Decodable, Identifiable, Equatable {
    /// NULL for the aggregate "Unknown" row (sales whose product could not be resolved).
    let key: UUID?
    let label: String
    let imagePath: String?
    let units: Int
    let revenue: Double
    let revenueNet: Double
    let grossProfit: Double
    let prevUnits: Int
    let prevRevenue: Double
    let prevGrossProfit: Double
    let sharePct: Double
    let cumulativeSharePct: Double
    let abcClass: String
    let avgDailyUnits: Double
    let avgDailyRevenue: Double
    let avgDailyGrossProfit: Double
    let totalCapacity: Int
    let totalStock: Int
    let sellThroughPct: Double?
    let daysOfSupply: Double?
    let machineCount: Int
    let productCount: Int
    let hasCost: Bool

    var id: String { key?.uuidString ?? "unknown-\(label)" }

    enum CodingKeys: String, CodingKey {
        case key, label, units
        case imagePath = "image_path"
        case revenue = "revenue_gross"
        case revenueNet = "revenue_net"
        case grossProfit = "gross_profit"
        case prevUnits = "prev_units"
        case prevRevenue = "prev_revenue_gross"
        case prevGrossProfit = "prev_gross_profit"
        case sharePct = "share_pct"
        case cumulativeSharePct = "cumulative_share_pct"
        case abcClass = "abc_class"
        case avgDailyUnits = "avg_daily_units"
        case avgDailyRevenue = "avg_daily_revenue"
        case avgDailyGrossProfit = "avg_daily_gross_profit"
        case totalCapacity = "total_capacity"
        case totalStock = "total_stock"
        case sellThroughPct = "sell_through_pct"
        case daysOfSupply = "days_of_supply"
        case machineCount = "machine_count"
        case productCount = "product_count"
        case hasCost = "has_cost"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = (try? c.decodeIfPresent(UUID.self, forKey: .key)) ?? nil
        label = flexString(c, .label, default: "Unknown")
        imagePath = (try? c.decodeIfPresent(String.self, forKey: .imagePath)) ?? nil
        units = flexInt(c, .units)
        revenue = flexDouble(c, .revenue)
        revenueNet = flexDouble(c, .revenueNet)
        grossProfit = flexDouble(c, .grossProfit)
        prevUnits = flexInt(c, .prevUnits)
        prevRevenue = flexDouble(c, .prevRevenue)
        prevGrossProfit = flexDouble(c, .prevGrossProfit)
        sharePct = flexDouble(c, .sharePct)
        cumulativeSharePct = flexDouble(c, .cumulativeSharePct)
        abcClass = flexString(c, .abcClass, default: "C")
        avgDailyUnits = flexDouble(c, .avgDailyUnits)
        avgDailyRevenue = flexDouble(c, .avgDailyRevenue)
        avgDailyGrossProfit = flexDouble(c, .avgDailyGrossProfit)
        totalCapacity = flexInt(c, .totalCapacity)
        totalStock = flexInt(c, .totalStock)
        sellThroughPct = flexDoubleOptional(c, .sellThroughPct)
        daysOfSupply = flexDoubleOptional(c, .daysOfSupply)
        machineCount = flexInt(c, .machineCount)
        productCount = flexInt(c, .productCount)
        hasCost = ((try? c.decodeIfPresent(Bool.self, forKey: .hasCost)) ?? nil) ?? false
    }

    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenue
        case .grossProfit: return grossProfit
        }
    }

    func previousValue(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(prevUnits)
        case .revenue: return prevRevenue
        case .grossProfit: return prevGrossProfit
        }
    }

    func avgDaily(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return avgDailyUnits
        case .revenue: return avgDailyRevenue
        case .grossProfit: return avgDailyGrossProfit
        }
    }

    /// Memberwise initialiser for tests and previews.
    init(key: UUID?, label: String, imagePath: String? = nil, units: Int, revenue: Double,
         revenueNet: Double = 0, grossProfit: Double, prevUnits: Int = 0, prevRevenue: Double = 0,
         prevGrossProfit: Double = 0, sharePct: Double = 0, cumulativeSharePct: Double = 0,
         abcClass: String = "C", avgDailyUnits: Double = 0, avgDailyRevenue: Double = 0,
         avgDailyGrossProfit: Double = 0, totalCapacity: Int = 0, totalStock: Int = 0,
         sellThroughPct: Double? = nil, daysOfSupply: Double? = nil, machineCount: Int = 0,
         productCount: Int = 0, hasCost: Bool = true) {
        self.key = key; self.label = label; self.imagePath = imagePath; self.units = units
        self.revenue = revenue; self.revenueNet = revenueNet; self.grossProfit = grossProfit
        self.prevUnits = prevUnits; self.prevRevenue = prevRevenue
        self.prevGrossProfit = prevGrossProfit; self.sharePct = sharePct
        self.cumulativeSharePct = cumulativeSharePct; self.abcClass = abcClass
        self.avgDailyUnits = avgDailyUnits; self.avgDailyRevenue = avgDailyRevenue
        self.avgDailyGrossProfit = avgDailyGrossProfit; self.totalCapacity = totalCapacity
        self.totalStock = totalStock; self.sellThroughPct = sellThroughPct
        self.daysOfSupply = daysOfSupply; self.machineCount = machineCount
        self.productCount = productCount; self.hasCost = hasCost
    }

    static func stub(label: String, units: Int, revenue: Double, profit: Double) -> AnalyticsBreakdownRow {
        AnalyticsBreakdownRow(key: UUID(), label: label, units: units,
                              revenue: revenue, grossProfit: profit)
    }
}

// MARK: - Pure helpers

/// Percentage change against the previous period. `nil` when the baseline is
/// zero — "+∞ %" is not a number a user can act on, so the UI shows nothing.
func deltaPct(current: Double, previous: Double) -> Double? {
    guard previous != 0 else { return nil }
    return (current - previous) / abs(previous) * 100
}

/// Daily bars stay readable up to about two months; beyond that they turn into
/// unreadable hairlines, so the chart switches to weekly buckets.
func chartBucket(forDays days: Double) -> ChartBucket {
    days > 60 ? .week : .day
}

/// 0…1 colour intensity for a heatmap cell.
func heatIntensity(units: Int, max: Int) -> Double {
    guard max > 0 else { return 0 }
    return min(Double(units) / Double(max), 1)
}

/// Sorts breakdown rows by the currently selected metric, descending. The RPC
/// returns them revenue-sorted; switching the metric must reorder client-side
/// rather than trigger another round trip.
func sortRows(_ rows: [AnalyticsBreakdownRow], by metric: AnalyticsMetric) -> [AnalyticsBreakdownRow] {
    rows.sorted {
        let l = $0.value(for: metric), r = $1.value(for: metric)
        if l != r { return l > r }
        return $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending
    }
}

/// Resolves a preset into a half-open `[from, to)` window on local day
/// boundaries. `to` is the exclusive midnight *after* the last included day —
/// the RPC filters `created_at < p_to`, so an inclusive end would silently
/// drop the last day's sales.
func dateRange(for preset: AnalyticsRangePreset, customFrom: Date, customTo: Date,
               calendar: Calendar = .current, now: Date = Date()) -> (from: Date, to: Date) {
    let startOfToday = calendar.startOfDay(for: now)
    let tomorrow = calendar.date(byAdding: .day, value: 1, to: startOfToday)!

    switch preset {
    case .days7:
        return (calendar.date(byAdding: .day, value: -6, to: startOfToday)!, tomorrow)
    case .days30:
        return (calendar.date(byAdding: .day, value: -29, to: startOfToday)!, tomorrow)
    case .days90:
        return (calendar.date(byAdding: .day, value: -89, to: startOfToday)!, tomorrow)
    case .thisMonth:
        let start = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        return (start, tomorrow)
    case .lastMonth:
        let thisMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        let start = calendar.date(byAdding: .month, value: -1, to: thisMonth)!
        return (start, thisMonth)
    case .custom:
        let from = calendar.startOfDay(for: min(customFrom, customTo))
        let toDay = calendar.startOfDay(for: max(customFrom, customTo))
        return (from, calendar.date(byAdding: .day, value: 1, to: toDay)!)
    }
}
