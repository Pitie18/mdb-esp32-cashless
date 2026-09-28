import { describe, it, expect } from 'vitest'
import { classifyTrayStock, computeStockHealthPerMachine, countMachineStockBuckets, distributeAcrossSlots, groupNeedsRefill, groupTraysByProduct } from '../stock-health'
import type { MachineStockSummary, ProductStockGroup } from '../stock-health'

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
      { machine_id: 'm1', product_id: 'p2', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, new Map([['p1', 1], ['p2', 1]]), true)
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
      noStockCount: 0, noStockEmptyCount: 0, emptySlotsWithStock: 0,
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

describe('groupTraysByProduct', () => {
  const tray = (over: Partial<{ machine_id: string; product_id: string | null; item_number: number; capacity: number; current_stock: number; min_stock: number; fill_when_below: number }>) => ({
    machine_id: 'm1', product_id: 'cola', item_number: 10, capacity: 10, current_stock: 10, min_stock: 2, fill_when_below: 5, ...over,
  })

  it('sums stock, capacity and both thresholds across the slots of one product', () => {
    const [g] = groupTraysByProduct([
      tray({ item_number: 12, current_stock: 0 }),
      tray({ item_number: 13, current_stock: 2 }),
      tray({ item_number: 14, current_stock: 9 }),
    ])
    expect(g).toMatchObject({ machine_id: 'm1', product_id: 'cola', current_stock: 11, capacity: 30, min_stock: 6, fill_when_below: 15, deficit: 19 })
    expect(g!.trays.map(t => t.item_number)).toEqual([12, 13, 14])
  })

  it('classifies the product, not the slot: an empty slot next to full ones is not critical', () => {
    const [g] = groupTraysByProduct([
      tray({ item_number: 12, current_stock: 0 }),
      tray({ item_number: 13, current_stock: 2 }),
      tray({ item_number: 14, current_stock: 9 }),
    ])
    expect(g!.state).toBe('fill')
    expect(g!.emptySlots).toBe(1)
  })

  it('is critical only when the product is sold out in every slot', () => {
    const [g] = groupTraysByProduct([tray({ current_stock: 0 }), tray({ item_number: 11, current_stock: 0 })])
    expect(g!.state).toBe('critical')
    expect(g!.emptySlots).toBe(0)
  })

  it('keeps products and machines apart and skips unassigned slots', () => {
    const groups = groupTraysByProduct([
      tray({ product_id: 'cola' }),
      tray({ product_id: 'fanta', item_number: 11 }),
      tray({ machine_id: 'm2', product_id: 'cola' }),
      tray({ product_id: null, item_number: 12 }),
    ])
    expect(groups.map(g => `${g.machine_id}/${g.product_id}`)).toEqual(['m1/cola', 'm1/fanta', 'm2/cola'])
  })

  it('behaves exactly like classifyTrayStock for a product in a single slot', () => {
    for (const current_stock of [0, 1, 2, 3, 5, 6, 10]) {
      const t = tray({ current_stock })
      expect(groupTraysByProduct([t])[0]!.state).toBe(classifyTrayStock(t))
    }
  })
})

describe('groupNeedsRefill', () => {
  const g = (over: Partial<ProductStockGroup>) => ({ state: 'ok', deficit: 5, ...over }) as ProductStockGroup
  it('is true for critical, low and fill with a deficit', () => {
    expect(groupNeedsRefill(g({ state: 'critical' }))).toBe(true)
    expect(groupNeedsRefill(g({ state: 'low' }))).toBe(true)
    expect(groupNeedsRefill(g({ state: 'fill' }))).toBe(true)
  })
  it('is false for ok and for a fill tier that is already full (fill_when_below >= capacity)', () => {
    expect(groupNeedsRefill(g({ state: 'ok' }))).toBe(false)
    expect(groupNeedsRefill(g({ state: 'fill', deficit: 0 }))).toBe(false)
  })
})

describe('distributeAcrossSlots', () => {
  it('fills the emptiest slot first so no selection stays sold out', () => {
    const slots = [
      { item_number: 12, capacity: 10, current_stock: 4 },
      { item_number: 13, capacity: 10, current_stock: 0 },
      { item_number: 14, capacity: 10, current_stock: 9 },
    ]
    expect(distributeAcrossSlots(slots, 8)).toEqual([0, 8, 0])
    expect(distributeAcrossSlots(slots, 13)).toEqual([3, 10, 0])
    expect(distributeAcrossSlots(slots, 100)).toEqual([6, 10, 1])
  })

  it('breaks ties by slot number and never returns negatives', () => {
    const slots = [
      { item_number: 21, capacity: 5, current_stock: 1 },
      { item_number: 20, capacity: 5, current_stock: 1 },
      { item_number: 22, capacity: 5, current_stock: 7 },
    ]
    expect(distributeAcrossSlots(slots, 2)).toEqual([0, 2, 0])
    expect(distributeAcrossSlots(slots, 0)).toEqual([0, 0, 0])
  })
})

describe('computeStockHealthPerMachine — one product in several slots', () => {
  const warehouseMap = new Map<string, number>([['cola', 100]])
  const rows = (stocks: number[]) => stocks.map((current_stock, i) => ({
    machine_id: 'm1', product_id: 'cola', item_number: 12 + i, capacity: 10, current_stock, min_stock: 2, fill_when_below: 5,
  }))

  it('does not mark the machine critical while the product is still in another slot', () => {
    const s = computeStockHealthPerMachine(rows([0, 2, 9]), warehouseMap, true).get('m1')!
    expect(s.health).toBe('fill')
    expect(s.refillableEmpty).toBe(0)
    expect(s.refillableFill).toBe(1)
  })

  it('reports an empty slot of a well-stocked product only as a hint', () => {
    const s = computeStockHealthPerMachine(rows([0, 10, 10]), warehouseMap, true).get('m1')!
    expect(s.health).toBe('ok')
    expect(s.emptySlotsWithStock).toBe(1)
  })

  it('counts a product once, however many of its slots are low', () => {
    const s = computeStockHealthPerMachine(rows([1, 1, 1]), warehouseMap, true).get('m1')!
    expect(s.health).toBe('low')
    expect(s.refillableLow).toBe(1)
  })

  it('is a swap candidate only when the non-refillable product is empty everywhere', () => {
    const empty = new Map<string, number>()
    expect(computeStockHealthPerMachine(rows([0, 4]), empty, true).get('m1')!.noStockEmptyCount).toBe(0)
    expect(computeStockHealthPerMachine(rows([0, 0]), empty, true).get('m1')!.noStockEmptyCount).toBe(1)
  })
})
