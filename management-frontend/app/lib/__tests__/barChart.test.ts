import { describe, it, expect } from 'vitest'
import {
  average,
  barIndexAt,
  compactNumber,
  dailySeries,
  isWeekend,
  labelStride,
  localDayKey,
  niceTicks,
} from '../barChart'

describe('localDayKey', () => {
  it('uses the local calendar day, not the UTC one', () => {
    expect(localDayKey(new Date(2026, 9, 13, 0, 30))).toBe('2026-10-13')
    expect(localDayKey(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05')
  })
})

describe('dailySeries', () => {
  const now = new Date(2026, 9, 13, 15, 0)

  it('fills every day including empty ones, oldest first', () => {
    const series = dailySeries([], 7, now)
    expect(series).toHaveLength(7)
    expect(localDayKey(series[0]!.date)).toBe('2026-10-07')
    expect(localDayKey(series[6]!.date)).toBe('2026-10-13')
    expect(series.every(b => b.total === 0 && b.count === 0)).toBe(true)
  })

  it('sums revenue and counts sales per local day', () => {
    const rows = [
      { created_at: new Date(2026, 9, 13, 0, 15).toISOString(), item_price: 2.5 },
      { created_at: new Date(2026, 9, 13, 12, 0).toISOString(), item_price: 1.2 },
      { created_at: new Date(2026, 9, 12, 23, 50).toISOString(), item_price: null },
      { created_at: new Date(2026, 8, 1).toISOString(), item_price: 9 }, // outside the window
    ]
    const series = dailySeries(rows, 3, now)
    expect(series.map(b => b.count)).toEqual([0, 1, 2])
    expect(series[2]!.total).toBeCloseTo(3.7)
    expect(series[1]!.total).toBe(0)
  })
})

describe('isWeekend', () => {
  it('flags Saturday and Sunday only', () => {
    expect(isWeekend(new Date(2026, 9, 10))).toBe(true) // Sat
    expect(isWeekend(new Date(2026, 9, 11))).toBe(true) // Sun
    expect(isWeekend(new Date(2026, 9, 12))).toBe(false) // Mon
  })
})

describe('average', () => {
  it('is the mean of the drawn values, 0 when empty', () => {
    expect(average([])).toBe(0)
    expect(average([1, 2, 3, 6])).toBe(3)
  })
})

describe('niceTicks', () => {
  it('starts at 0 and covers the max with round steps', () => {
    expect(niceTicks(87)).toEqual([0, 25, 50, 75, 100])
    expect(niceTicks(4)).toEqual([0, 1, 2, 3, 4])
    expect(niceTicks(1300)).toEqual([0, 500, 1000, 1500])
  })

  it('falls back to [0, 1] without data', () => {
    expect(niceTicks(0)).toEqual([0, 1])
  })

  it('does not accumulate float noise', () => {
    expect(niceTicks(0.7)).toEqual([0, 0.2, 0.4, 0.6, 0.8])
  })
})

describe('compactNumber', () => {
  it('abbreviates thousands like the iOS axis', () => {
    expect(compactNumber(0)).toBe('0')
    expect(compactNumber(250)).toBe('250')
    expect(compactNumber(2000)).toBe('2k')
    expect(compactNumber(2500)).toBe('2.5k')
    expect(compactNumber(0.5)).toBe('0.5')
  })
})

describe('labelStride', () => {
  it('labels short daily series every day and 30 days weekly', () => {
    expect(labelStride(7, 'day')).toBe(1)
    expect(labelStride(14, 'day')).toBe(2)
    expect(labelStride(30, 'day')).toBe(7)
  })

  it('aims for about six labels otherwise', () => {
    expect(labelStride(52, 'week')).toBe(9)
    expect(labelStride(4, 'month')).toBe(1)
  })
})

describe('barIndexAt', () => {
  it('maps a position to the bar under it, clamped to the series', () => {
    expect(barIndexAt(0, 300, 30)).toBe(0)
    expect(barIndexAt(155, 300, 30)).toBe(15)
    expect(barIndexAt(400, 300, 30)).toBe(29)
    expect(barIndexAt(-5, 300, 30)).toBe(0)
    expect(barIndexAt(10, 300, 0)).toBe(-1)
  })
})
