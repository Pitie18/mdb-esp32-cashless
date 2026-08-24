import { describe, it, expect } from 'vitest'
import { classifyTrayStock, computeStockHealthPerMachine, countMachineStockBuckets } from '../stock-health'
import type { MachineStockSummary } from '../stock-health'

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

describe('countMachineStockBuckets', () => {
  function summary(over: Partial<MachineStockSummary>): MachineStockSummary {
    return {
      refillableEmpty: 0, refillableLow: 0, refillableFill: 0,
      noStockCount: 0, noStockEmptyCount: 0,
      totalStock: 0, totalCapacity: 0,
      health: 'ok', percent: 100,
      ...over,
    }
  }

  it('puts each machine in exactly one bucket', () => {
    const buckets = countMachineStockBuckets([
      summary({ health: 'critical' }),
      summary({ health: 'low' }),
      summary({ health: 'fill' }),
      summary({ health: 'ok', noStockEmptyCount: 1 }),
      summary({ health: 'ok' }),
    ])
    expect(buckets).toEqual({ critical: 1, low: 1, fill: 1, swap: 1, needingAttention: 4 })
  })

  it('counts a fill machine with a non-refillable empty tray only once (regression: banner claimed more machines than exist)', () => {
    const buckets = countMachineStockBuckets([
      summary({ health: 'fill', noStockEmptyCount: 1 }),
      summary({ health: 'ok' }),
      summary({ health: 'ok' }),
    ])
    expect(buckets.fill).toBe(1)
    expect(buckets.swap).toBe(0)
    expect(buckets.needingAttention).toBe(1)
  })

  it('never reports more machines than it was given', () => {
    const machines = [
      summary({ health: 'critical', noStockEmptyCount: 2 }),
      summary({ health: 'low', noStockEmptyCount: 1 }),
      summary({ health: 'fill', noStockEmptyCount: 3 }),
    ]
    const buckets = countMachineStockBuckets(machines)
    expect(buckets.needingAttention).toBe(machines.length)
    expect(buckets.critical + buckets.low + buckets.fill + buckets.swap).toBe(buckets.needingAttention)
  })

  it('counts nothing for a healthy fleet', () => {
    const buckets = countMachineStockBuckets([summary({}), summary({})])
    expect(buckets).toEqual({ critical: 0, low: 0, fill: 0, swap: 0, needingAttention: 0 })
  })
})
