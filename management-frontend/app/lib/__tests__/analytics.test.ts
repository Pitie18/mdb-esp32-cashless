import { describe, it, expect } from 'vitest'
import {
  avgDailyValue,
  bucketDaily,
  chartBucket,
  deltaPct,
  heatIntensity,
  metricValue,
  prevMetricValue,
  resolveRange,
  sortRows,
} from '../analytics'
import type { AnalyticsDailyPoint, BreakdownRow } from '../analytics'

const row = (over: Partial<BreakdownRow>): BreakdownRow => ({
  key: 'k', label: 'x', image_path: null,
  units: 0, revenue_gross: 0, revenue_net: 0, gross_profit: 0,
  prev_units: 0, prev_revenue_gross: 0, prev_gross_profit: 0,
  share_pct: 0, cumulative_share_pct: 0, abc_class: 'C',
  avg_daily_units: 0, avg_daily_revenue: 0, avg_daily_gross_profit: 0,
  total_capacity: 0, total_stock: 0, sell_through_pct: null, days_of_supply: null,
  machine_count: 0, product_count: 0, has_cost: true,
  ...over,
})

describe('deltaPct', () => {
  it('reports a positive change', () => expect(deltaPct(110, 100)).toBe(10))
  it('reports a negative change', () => expect(deltaPct(90, 100)).toBe(-10))
  it('returns null against a zero baseline', () => expect(deltaPct(50, 0)).toBeNull())
  it('returns null when both are zero', () => expect(deltaPct(0, 0)).toBeNull())
})

describe('chartBucket', () => {
  it('keeps daily bars up to 60 days', () => {
    expect(chartBucket(7)).toBe('day')
    expect(chartBucket(60)).toBe('day')
  })
  it('switches to weekly bars beyond 60 days', () => {
    expect(chartBucket(61)).toBe('week')
    expect(chartBucket(365)).toBe('week')
  })
})

describe('heatIntensity', () => {
  it('maps units onto 0..1', () => {
    expect(heatIntensity(0, 10)).toBe(0)
    expect(heatIntensity(5, 10)).toBe(0.5)
    expect(heatIntensity(10, 10)).toBe(1)
  })
  it('never divides by zero', () => expect(heatIntensity(3, 0)).toBe(0))
})

describe('sortRows', () => {
  const rows = [
    row({ label: 'low', units: 1, revenue_gross: 90, gross_profit: 50 }),
    row({ label: 'high', units: 20, revenue_gross: 10, gross_profit: 5 }),
  ]
  it('sorts by units', () => expect(sortRows(rows, 'units')[0]!.label).toBe('high'))
  it('sorts by revenue', () => expect(sortRows(rows, 'revenue')[0]!.label).toBe('low'))
  it('sorts by gross profit', () => expect(sortRows(rows, 'grossProfit')[0]!.label).toBe('low'))
  it('breaks ties on the label', () => {
    const tied = [row({ label: 'b', units: 5 }), row({ label: 'a', units: 5 })]
    expect(sortRows(tied, 'units').map(r => r.label)).toEqual(['a', 'b'])
  })
  it('does not mutate the input', () => {
    sortRows(rows, 'units')
    expect(rows[0]!.label).toBe('low')
  })
})

describe('metricValue / prevMetricValue / avgDailyValue', () => {
  const r = row({
    units: 3, revenue_gross: 7, gross_profit: 2,
    prev_units: 30, prev_revenue_gross: 70, prev_gross_profit: 20,
    avg_daily_units: 0.3, avg_daily_revenue: 0.7, avg_daily_gross_profit: 0.2,
  })
  it('reads the selected metric off a row', () => {
    expect(metricValue(r, 'units')).toBe(3)
    expect(metricValue(r, 'revenue')).toBe(7)
    expect(metricValue(r, 'grossProfit')).toBe(2)
  })
  it('reads the previous-period value of the selected metric', () => {
    expect(prevMetricValue(r, 'units')).toBe(30)
    expect(prevMetricValue(r, 'revenue')).toBe(70)
    expect(prevMetricValue(r, 'grossProfit')).toBe(20)
  })
  it('reads the per-day average of the selected metric', () => {
    expect(avgDailyValue(r, 'units')).toBe(0.3)
    expect(avgDailyValue(r, 'revenue')).toBe(0.7)
    expect(avgDailyValue(r, 'grossProfit')).toBe(0.2)
  })
})

describe('resolveRange', () => {
  // vitest runs with TZ=UTC (vitest.config.ts), so local midnight is UTC midnight.
  const now = new Date('2026-07-15T13:45:00Z')

  it('covers 7 days ending with the exclusive next midnight', () => {
    const r = resolveRange('days7', '', '', now)
    expect(r.from).toBe('2026-07-09T00:00:00.000Z')
    expect(r.to).toBe('2026-07-16T00:00:00.000Z')
  })

  it('covers 30 days', () => {
    expect(resolveRange('days30', '', '', now).from).toBe('2026-06-16T00:00:00.000Z')
  })

  it('covers 90 days', () => {
    expect(resolveRange('days90', '', '', now).from).toBe('2026-04-17T00:00:00.000Z')
  })

  it('starts this month on the 1st', () => {
    expect(resolveRange('thisMonth', '', '', now).from).toBe('2026-07-01T00:00:00.000Z')
  })

  it('bounds last month by the two 1sts', () => {
    const r = resolveRange('lastMonth', '', '', now)
    expect(r.from).toBe('2026-06-01T00:00:00.000Z')
    expect(r.to).toBe('2026-07-01T00:00:00.000Z')
  })

  it('makes a custom range inclusive of its last day', () => {
    const r = resolveRange('custom', '2026-03-05', '2026-03-09', now)
    expect(r.from).toBe('2026-03-05T00:00:00.000Z')
    expect(r.to).toBe('2026-03-10T00:00:00.000Z')
  })

  it('swaps a reversed custom range instead of returning an empty window', () => {
    const r = resolveRange('custom', '2026-03-09', '2026-03-05', now)
    expect(r.from).toBe('2026-03-05T00:00:00.000Z')
    expect(r.to).toBe('2026-03-10T00:00:00.000Z')
  })

  it('falls back to the default window on an unparseable custom range', () => {
    // Without the guard these throw a RangeError inside toISOString().
    const fallback = resolveRange('days30', '', '', now)
    expect(resolveRange('custom', '', '', now)).toEqual(fallback)
    expect(resolveRange('custom', 'not-a-date', '2026-03-09', now)).toEqual(fallback)
    expect(resolveRange('custom', '2026-03-05', '', now)).toEqual(fallback)
  })

  it('always emits a full ISO timestamp, never a bare local datetime', () => {
    // A bare `yyyy-MM-ddT00:00:00` is read as UTC by PostgREST while the UI
    // renders local, shifting the window by the local offset.
    for (const preset of ['days7', 'days30', 'thisMonth', 'lastMonth'] as const) {
      const r = resolveRange(preset, '', '', now)
      expect(r.from).toMatch(/Z$/)
      expect(r.to).toMatch(/Z$/)
    }
  })
})

describe('bucketDaily', () => {
  const points: AnalyticsDailyPoint[] = [
    { day: '2026-07-06', units: 1, revenue_gross: 1, gross_profit: 0.5 }, // Mon
    { day: '2026-07-07', units: 2, revenue_gross: 2, gross_profit: 1 },
    { day: '2026-07-13', units: 4, revenue_gross: 4, gross_profit: 2 },   // next Mon
  ]

  it('returns the input untouched for daily buckets', () => {
    expect(bucketDaily(points, 'day')).toHaveLength(3)
  })

  it('folds days into ISO weeks', () => {
    const weeks = bucketDaily(points, 'week')
    expect(weeks).toHaveLength(2)
    expect(weeks[0]!.day).toBe('2026-07-06')
    expect(weeks[0]!.units).toBe(3)
    expect(weeks[1]!.units).toBe(4)
  })

  it('anchors a Sunday to the Monday that starts its week', () => {
    const sunday: AnalyticsDailyPoint[] = [
      { day: '2026-07-12', units: 9, revenue_gross: 9, gross_profit: 3 }, // Sunday
    ]
    expect(bucketDaily(sunday, 'week')[0]!.day).toBe('2026-07-06')
  })

  it('preserves the total across bucketing', () => {
    const weeks = bucketDaily(points, 'week')
    const sum = (xs: AnalyticsDailyPoint[]) => xs.reduce((n, p) => n + p.units, 0)
    expect(sum(weeks)).toBe(sum(points))
  })
})
