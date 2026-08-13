import { describe, it, expect } from 'vitest'
import { classifyTrayStock } from '../stock-health'

describe('classifyTrayStock', () => {
  it('is critical when current_stock is 0, even with no min_stock set', () => {
    expect(classifyTrayStock({ current_stock: 0, min_stock: 0, fill_when_below: 0 })).toBe('critical')
  })

  it('is low when current_stock is at or below min_stock', () => {
    expect(classifyTrayStock({ current_stock: 5, min_stock: 5, fill_when_below: 10 })).toBe('low')
    expect(classifyTrayStock({ current_stock: 3, min_stock: 5, fill_when_below: 10 })).toBe('low')
  })

  it('is fill when current_stock is at or below fill_when_below but above min_stock', () => {
    expect(classifyTrayStock({ current_stock: 10, min_stock: 5, fill_when_below: 10 })).toBe('fill')
    expect(classifyTrayStock({ current_stock: 8, min_stock: 5, fill_when_below: 10 })).toBe('fill')
  })

  it('is ok when current_stock is above fill_when_below', () => {
    expect(classifyTrayStock({ current_stock: 11, min_stock: 5, fill_when_below: 10 })).toBe('ok')
  })

  it('ignores a disabled (0) min_stock threshold', () => {
    expect(classifyTrayStock({ current_stock: 3, min_stock: 0, fill_when_below: 10 })).toBe('fill')
  })

  it('ignores a disabled (0) fill_when_below threshold', () => {
    expect(classifyTrayStock({ current_stock: 3, min_stock: 0, fill_when_below: 0 })).toBe('ok')
  })
})
