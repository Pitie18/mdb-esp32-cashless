// Pure helpers and types for the Analytics page.
//
// Everything that computes lives here rather than in components, so it is
// testable under Vitest without a Nuxt runtime — same split as printSheet.ts.
//
// Types mirror the JSON contracts of get_sales_analytics_summary and
// get_sales_analytics_breakdown (migrations 20260811000000 / 20260811000100).

export type AnalyticsMetric = 'units' | 'revenue' | 'grossProfit'
export type AnalyticsDimension = 'product' | 'category' | 'machine'
export type RangePreset = 'days7' | 'days30' | 'days90' | 'thisMonth' | 'lastMonth' | 'custom'

export interface AnalyticsTotals {
  units: number
  revenue_gross: number
  revenue_net: number
  cost_net: number
  gross_profit: number
  avg_ticket: number
  avg_daily_units: number
  avg_daily_revenue: number
  avg_daily_gross_profit: number
}

export interface AnalyticsDailyPoint {
  /** `yyyy-MM-dd` in the requested timezone. */
  day: string
  units: number
  revenue_gross: number
  gross_profit: number
}

export interface AnalyticsHeatCell {
  /** ISO weekday: 1 = Monday … 7 = Sunday. */
  dow: number
  hour: number
  units: number
  revenue_gross: number
}

export interface AnalyticsChannel {
  channel: string
  units: number
  revenue_gross: number
  avg_ticket: number
}

export interface AnalyticsSummary {
  range: {
    from: string
    to: string
    previous_from: string
    previous_to: string
    days: number
    timezone: string
  }
  totals: AnalyticsTotals
  previous: AnalyticsTotals
  daily: AnalyticsDailyPoint[]
  heatmap: AnalyticsHeatCell[]
  channels: AnalyticsChannel[]
  missing_cost_products: number
  unknown_product_units: number
}

export interface BreakdownRow {
  /** null for the aggregate "Unknown" row (unresolvable sales). */
  key: string | null
  label: string
  image_path: string | null
  units: number
  revenue_gross: number
  revenue_net: number
  gross_profit: number
  prev_units: number
  prev_revenue_gross: number
  prev_gross_profit: number
  share_pct: number
  cumulative_share_pct: number
  abc_class: string
  avg_daily_units: number
  avg_daily_revenue: number
  avg_daily_gross_profit: number
  total_capacity: number
  total_stock: number
  sell_through_pct: number | null
  days_of_supply: number | null
  machine_count: number
  product_count: number
  has_cost: boolean
}

/**
 * Percentage change against the previous period. Returns null on a zero
 * baseline — "+∞ %" is not something a user can act on, so the UI shows nothing.
 */
export function deltaPct(current: number, previous: number): number | null {
  if (!previous) return null
  return ((current - previous) / Math.abs(previous)) * 100
}

/** Daily bars turn into hairlines past roughly two months. */
export function chartBucket(days: number): 'day' | 'week' {
  return days > 60 ? 'week' : 'day'
}

export function heatIntensity(units: number, max: number): number {
  if (max <= 0) return 0
  return Math.min(units / max, 1)
}

type MetricSource = Pick<BreakdownRow, 'units' | 'revenue_gross' | 'gross_profit'>

export function metricValue(source: MetricSource, metric: AnalyticsMetric): number {
  if (metric === 'units') return source.units
  if (metric === 'revenue') return source.revenue_gross
  return source.gross_profit
}

/** The same metric one period earlier — the basis for every delta shown. */
export function prevMetricValue(row: BreakdownRow, metric: AnalyticsMetric): number {
  if (metric === 'units') return row.prev_units
  if (metric === 'revenue') return row.prev_revenue_gross
  return row.prev_gross_profit
}

/** The row's average per day for the selected metric — the row subtitle. */
export function avgDailyValue(row: BreakdownRow, metric: AnalyticsMetric): number {
  if (metric === 'units') return row.avg_daily_units
  if (metric === 'revenue') return row.avg_daily_revenue
  return row.avg_daily_gross_profit
}

/**
 * Sorts a copy by the selected metric, descending. The RPC returns rows
 * revenue-sorted; switching the metric reorders client-side rather than
 * triggering another round trip.
 */
export function sortRows(rows: BreakdownRow[], metric: AnalyticsMetric): BreakdownRow[] {
  return [...rows].sort((a, b) => {
    const diff = metricValue(b, metric) - metricValue(a, metric)
    if (diff !== 0) return diff
    return a.label.localeCompare(b.label)
  })
}

function startOfLocalDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function addDays(d: Date, n: number): Date {
  const copy = new Date(d)
  copy.setDate(copy.getDate() + n)
  return copy
}

/**
 * Resolves a preset into a half-open `[from, to)` window as full ISO strings
 * with offset. Never hand PostgREST a bare `yyyy-MM-ddT00:00:00` — it reads
 * that as UTC while the UI renders local, which shifts the window by the
 * local offset.
 *
 * `to` is the exclusive midnight after the last included day, because the RPC
 * filters `created_at < p_to`.
 */
export function resolveRange(
  preset: RangePreset,
  customFrom: string,
  customTo: string,
  now: Date = new Date(),
): { from: string; to: string } {
  const today = startOfLocalDay(now)
  const tomorrow = addDays(today, 1)

  const iso = (d: Date) => d.toISOString()

  switch (preset) {
    case 'days7':
      return { from: iso(addDays(today, -6)), to: iso(tomorrow) }
    case 'days30':
      return { from: iso(addDays(today, -29)), to: iso(tomorrow) }
    case 'days90':
      return { from: iso(addDays(today, -89)), to: iso(tomorrow) }
    case 'thisMonth':
      return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(tomorrow) }
    case 'lastMonth': {
      const thisMonth = new Date(now.getFullYear(), now.getMonth(), 1)
      const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1)
      return { from: iso(lastMonth), to: iso(thisMonth) }
    }
    case 'custom': {
      const a = startOfLocalDay(new Date(`${customFrom}T00:00:00`))
      const b = startOfLocalDay(new Date(`${customTo}T00:00:00`))
      const from = a <= b ? a : b
      const lastDay = a <= b ? b : a
      return { from: iso(from), to: iso(addDays(lastDay, 1)) }
    }
  }
}

/**
 * Folds a gapless daily series into ISO weeks (Monday-anchored) when the
 * window is long enough for weekly bars.
 */
export function bucketDaily(
  points: AnalyticsDailyPoint[],
  bucket: 'day' | 'week',
): AnalyticsDailyPoint[] {
  if (bucket === 'day') return points

  const byWeek = new Map<string, AnalyticsDailyPoint>()
  for (const point of points) {
    const date = new Date(`${point.day}T00:00:00`)
    // getDay(): 0 = Sunday. Shift so Monday anchors the week.
    const offset = (date.getDay() + 6) % 7
    const monday = addDays(date, -offset)
    const key = [
      monday.getFullYear(),
      String(monday.getMonth() + 1).padStart(2, '0'),
      String(monday.getDate()).padStart(2, '0'),
    ].join('-')

    const existing = byWeek.get(key)
    if (existing) {
      existing.units += point.units
      existing.revenue_gross += point.revenue_gross
      existing.gross_profit += point.gross_profit
    } else {
      byWeek.set(key, {
        day: key,
        units: point.units,
        revenue_gross: point.revenue_gross,
        gross_profit: point.gross_profit,
      })
    }
  }
  return [...byWeek.values()].sort((a, b) => a.day.localeCompare(b.day))
}
