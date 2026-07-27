import { describe, it, expect } from 'vitest'
import { baseFromSemver, formatVersion } from '../appVersion'

describe('baseFromSemver', () => {
  it('takes the first two components', () => {
    expect(baseFromSemver('1.0.0')).toBe('1.0')
    expect(baseFromSemver('2.5.13')).toBe('2.5')
  })
  it('passes through a bare major.minor', () => {
    expect(baseFromSemver('1.0')).toBe('1.0')
  })
})

describe('formatVersion', () => {
  it('builds real (YYMMDD) and display (M.D) for a two-digit month/day', () => {
    const r = formatVersion('1.0', new Date(2026, 6, 27)) // month is 0-based: 6 = July
    expect(r.real).toBe('1.0.260727')
    expect(r.display).toBe('1.0.7.27')
  })
  it('pads the real version but strips leading zeros in display', () => {
    const r = formatVersion('1.0', new Date(2026, 0, 5)) // 5 Jan 2026
    expect(r.real).toBe('1.0.260105')
    expect(r.display).toBe('1.0.1.5')
  })
  it('handles December for monotonic ordering', () => {
    const r = formatVersion('1.0', new Date(2026, 11, 31)) // 31 Dec 2026
    expect(r.real).toBe('1.0.261231')
    expect(r.display).toBe('1.0.12.31')
  })
})
