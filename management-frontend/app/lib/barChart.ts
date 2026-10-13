/**
 * Pure helpers behind `components/charts/BarChart.vue` — the web port of the
 * iOS app's Swift Charts bar charts (dashboard, analytics trend, product
 * detail): gradient bars, lighter weekends, a dashed orange average line and
 * a tap/hover selection with a tooltip.
 */

export type ChartBucket = 'day' | 'week' | 'month'

/** `yyyy-MM-dd` of the LOCAL calendar day. `toISOString()` would give the UTC
 *  day, which shifts every sale between midnight and 01:00/02:00 (CET/CEST)
 *  onto the previous bar. */
export function localDayKey(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export interface DailyBucket {
  date: Date
  total: number
  count: number
}

/**
 * One bucket per local calendar day for the last `days` days ending today,
 * zero days included — Swift Charts draws gaps as empty days too, and a
 * series that skips days makes the x axis lie about spacing.
 */
export function dailySeries(
  rows: { created_at: string; item_price: number | null }[],
  days: number,
  now: Date = new Date(),
): DailyBucket[] {
  const buckets = new Map<string, DailyBucket>()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)
    buckets.set(localDayKey(d), { date: d, total: 0, count: 0 })
  }
  for (const row of rows) {
    const b = buckets.get(localDayKey(new Date(row.created_at)))
    if (!b) continue
    b.total += row.item_price ?? 0
    b.count += 1
  }
  return [...buckets.values()]
}

export function isWeekend(date: Date): boolean {
  const day = date.getDay()
  return day === 0 || day === 6
}

/** Mean over the drawn buckets, so the line sits where the bars are. */
export function average(values: number[]): number {
  if (!values.length) return 0
  return values.reduce((sum, v) => sum + v, 0) / values.length
}

/**
 * Round axis ticks from 0 to just above `max` (1/2/2.5/5 × 10ⁿ steps), like
 * Swift Charts' automatic y axis. Always at least [0, step].
 */
export function niceTicks(max: number, target = 4): number[] {
  if (!(max > 0)) return [0, 1]
  const raw = max / target
  const magnitude = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map(f => f * magnitude).find(s => s >= raw) ?? 10 * magnitude
  const ticks: number[] = []
  for (let v = 0; v < max + step * 1e-9; v += step) ticks.push(round(v))
  if ((ticks.at(-1) ?? 0) < max) ticks.push(round(ticks.length * step))
  return ticks
}

function round(v: number): number {
  return Math.round(v * 1e6) / 1e6
}

/** The iOS axis label: whole numbers, thousands as `1k`. */
export function compactNumber(value: number): string {
  if (Math.abs(value) >= 1000) {
    const k = value / 1000
    return `${Number.isInteger(k) ? k : k.toFixed(1)}k`
  }
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

/**
 * Show every n-th x label. Daily 30-day charts label weekly like the iOS
 * `AxisMarks(values: .stride(by: .day, count: 7))`; short series label every
 * day; anything else aims for about six labels.
 */
export function labelStride(count: number, bucket: ChartBucket): number {
  if (bucket === 'day') {
    if (count <= 8) return 1
    if (count <= 16) return 2
    if (count <= 45) return 7
  }
  return Math.max(1, Math.ceil(count / 6))
}

/** Index of the bar under `x` (plot-area coordinates), clamped. */
export function barIndexAt(x: number, plotWidth: number, count: number): number {
  if (count <= 0 || plotWidth <= 0) return -1
  const i = Math.floor((x / plotWidth) * count)
  return Math.min(count - 1, Math.max(0, i))
}
