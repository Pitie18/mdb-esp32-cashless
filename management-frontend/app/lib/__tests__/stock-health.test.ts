import { describe, it, expect } from 'vitest'
import { classifyTrayStock, computeStockHealthPerMachine } from '../stock-health'

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

describe('computeStockHealthPerMachine', () => {
  const warehouseMap = new Map<string, number>([['p1', 100]])

  it('is fill when the only tray issue is a fill_when_below breach', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('fill')
    expect(result.get('m1')?.refillableFill).toBe(1)
    expect(result.get('m1')?.refillableLow).toBe(0)
  })

  it('prioritizes critical over low and fill on the same machine', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 0, min_stock: 5, fill_when_below: 10 },
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('critical')
  })

  it('is ok when no tray breaches any threshold', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 15, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('ok')
  })

  it('does not require a low/empty tray on the machine for a fill-below tray to count (regression: old machine-level gate hid fill-only machines)', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 9, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.has('m1')).toBe(true)
    expect(result.get('m1')?.health).toBe('fill')
  })

  it('does not count a fill-tier tray whose fill_when_below is misconfigured >= capacity (zero deficit)', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 20, min_stock: 5, fill_when_below: 20 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('ok')
    expect(result.get('m1')?.refillableFill).toBe(0)
  })

  it('is ok when a non-refillable product only breaches fill_when_below (fill health only applies to refillable trays)', () => {
    const emptyWarehouse = new Map<string, number>()
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, emptyWarehouse, true)
    expect(result.get('m1')?.health).toBe('ok')
    expect(result.get('m1')?.noStockCount).toBe(1)
  })
})
