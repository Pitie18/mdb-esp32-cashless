import { describe, it, expect } from 'vitest'
import {
  planFromTrays, planChanges, productReach, slotNeeds, removalCandidates, firstEmpty,
  rebuildPackNeeds, fillPlan, computeLeftovers, priceChanges, widthWarning,
  ageChanged, type PlanTray, type ChangeItem,
} from '../slotChange'

const trays: PlanTray[] = [
  { id: 't11', item_number: 11, product_id: 'cola', capacity: 10, current_stock: 3 },
  { id: 't12', item_number: 12, product_id: 'schorle', capacity: 10, current_stock: 8 },
  { id: 't13', item_number: 13, product_id: 'snickers', capacity: 12, current_stock: 6 },
  { id: 't14', item_number: 14, product_id: 'tictac', capacity: 15, current_stock: 14 },
  { id: 't16', item_number: 16, product_id: 'pringles', capacity: 8, current_stock: 4 },
]
// units per day
const rates = new Map([['cola', 3], ['schorle', 0.5], ['snickers', 2], ['tictac', 0.05], ['pringles', 1]])

describe('plan basics', () => {
  it('starts from the current layout and reports no changes', () => {
    const plan = planFromTrays(trays)
    expect(plan.t11).toEqual({ product_id: 'cola', capacity: 10 })
    expect(planChanges(trays, plan)).toEqual([])
  })

  it('reports product and capacity changes per slot', () => {
    const plan = planFromTrays(trays)
    plan.t12 = { product_id: 'cola', capacity: 10 }
    plan.t16 = { product_id: 'pringles', capacity: 6 }
    expect(planChanges(trays, plan)).toEqual([
      { tray_id: 't12', item_number: 12, from_product_id: 'schorle', to_product_id: 'cola', from_capacity: 10, to_capacity: 10 },
      { tray_id: 't16', item_number: 16, from_product_id: 'pringles', to_product_id: 'pringles', from_capacity: 8, to_capacity: 6 },
    ])
  })
})

describe('reach and hints', () => {
  it('reach is total capacity over daily sales, Infinity when nothing sells', () => {
    const plan = planFromTrays(trays)
    const r = productReach(trays, plan, rates)
    expect(r.get('cola')!.reachDays).toBeCloseTo(10 / 3)
    expect(r.get('cola')!.slots).toEqual([11])
    rates.set('dead', 0)
    const plan2 = { ...plan, t14: { product_id: 'dead', capacity: 15 } }
    expect(productReach(trays, plan2, rates).get('dead')!.reachDays).toBe(Infinity)
    rates.delete('dead')
  })

  it('lists products that do not last until the target, with the missing spirals', () => {
    const plan = planFromTrays(trays)
    const needs = slotNeeds(trays, plan, rates, 7)
    // cola: 3/day × 7 = 21 units, avg spiral 10 → 3 spirals, has 1 → 2 more
    // snickers: 14 units / 12 → 2 spirals, has 1 → 1 more
    // pringles: 8 / 1 = 8 days → lasts, not listed
    expect(needs.map(n => [n.product_id, n.extra])).toEqual([['cola', 2], ['snickers', 1]])
    // A second cola spiral halves the shortfall
    plan.t12 = { product_id: 'cola', capacity: 10 }
    expect(slotNeeds(trays, plan, rates, 7).find(n => n.product_id === 'cola')!.extra).toBe(1)
  })

  it('removal candidates are slow sellers outside their test period', () => {
    const tenure = new Map([['tictac', 60], ['schorle', 5]])
    const units = new Map([['tictac', 2], ['schorle', 1], ['cola', 90], ['snickers', 60], ['pringles', 30]])
    expect(removalCandidates(trays, units, tenure)).toEqual(['tictac'])
  })

  it('first empty product decides the next visit', () => {
    const plan = planFromTrays(trays)
    expect(firstEmpty(trays, plan, rates)).toEqual({ product_id: 'cola', reachDays: 10 / 3 })
  })

  it('warns when a product needs a wider slot than the target', () => {
    const wide: PlanTray[] = [
      { id: 'a', item_number: 10, product_id: 'chips', capacity: 6, current_stock: 0 },
      { id: 'b', item_number: 12, product_id: 'bar', capacity: 10, current_stock: 0 },
      { id: 'c', item_number: 13, product_id: 'gum', capacity: 10, current_stock: 0 },
      { id: 'd', item_number: 14, product_id: 'gum', capacity: 10, current_stock: 0 },
    ]
    // chips sits in a 2-column slot (10–11); slot 13 is one column wide
    expect(widthWarning(wide, 'chips', 'c')).toBe(true)
    expect(widthWarning(wide, 'bar', 'a')).toBe(false)
    expect(widthWarning(wide, 'new-product', 'c')).toBe(false)
  })
})

const item = (id: string, tray: string, from: string | null, to: string | null, fromCap: number, toCap: number): ChangeItem =>
  ({ id, tray_id: tray, item_number: Number(tray.slice(1)), from_product_id: from, to_product_id: to, from_capacity: fromCap, to_capacity: toCap })

describe('tour: packing', () => {
  it('packs the new slots minus what moves over from changed slots', () => {
    // Doppelbelegung: schorle → cola (10)
    const items = [item('i12', 't12', 'schorle', 'cola', 10, 10)]
    expect(rebuildPackNeeds(items, new Map([['t12', 8]]))).toEqual(new Map([['cola', 10]]))
  })

  it('a neighbour swap needs nothing from the warehouse when stock covers it', () => {
    const items = [
      item('i16', 't16', 'balisto', 'duplo', 12, 12),
      item('i17', 't17', 'duplo', 'balisto', 14, 14),
    ]
    const stock = new Map([['t16', 10], ['t17', 12]])
    // duplo needs 12, 12 come out of slot 17 → 0; balisto needs 14, 10 come out of 16 → 4
    expect(rebuildPackNeeds(items, stock)).toEqual(new Map([['duplo', 0], ['balisto', 4]]))
  })

  it('a smaller spiral for the same product needs nothing extra', () => {
    const items = [item('i16', 't16', 'pringles', 'pringles', 8, 6)]
    expect(rebuildPackNeeds(items, new Map([['t16', 5]]))).toEqual(new Map([['pringles', 1]]))
    expect(rebuildPackNeeds(items, new Map([['t16', 8]]))).toEqual(new Map([['pringles', 0]]))
  })

  it('emptying a slot packs nothing', () => {
    const items = [item('i14', 't14', 'tictac', null, 15, 15)]
    expect(rebuildPackNeeds(items, new Map([['t14', 14]]))).toEqual(new Map())
  })
})

describe('tour: at the machine', () => {
  it('fills moved stock first, then van stock', () => {
    const items = [
      item('i16', 't16', 'balisto', 'duplo', 12, 12),
      item('i17', 't17', 'duplo', 'balisto', 14, 14),
    ]
    // a duplo sold since packing: 11 come out of 17
    const removed = new Map([['i16', 10], ['i17', 11]])
    const plan = fillPlan(items, removed, new Map([['balisto', 4], ['duplo', 0]]))
    expect(plan.get('i16')).toEqual({ moved: 11, van: 0, total: 11 })
    expect(plan.get('i17')).toEqual({ moved: 10, van: 4, total: 14 })
  })

  it('leftovers split into van goods and goods from the machine', () => {
    const items = [
      item('i12', 't12', 'schorle', 'cola', 10, 10),
      item('i13', 't13', 'redbull', 'redbull', 8, 6),
    ]
    const outcome = new Map([
      ['i12', { action: 'done' as const, removed: 7, filled: 7 }],
      ['i13', { action: 'done' as const, removed: 8, filled: 6 }],
    ])
    const left = computeLeftovers(items, outcome, new Map([['cola', 10]]))
    expect(left.get('cola')).toEqual({ van: 3, machine: 0 })
    expect(left.get('schorle')).toEqual({ van: 0, machine: 7 })
    expect(left.get('redbull')).toEqual({ van: 0, machine: 2 })
  })

  it('a skipped slot returns everything packed for it', () => {
    const items = [item('i12', 't12', 'schorle', 'cola', 10, 10)]
    const left = computeLeftovers(items, new Map([['i12', { action: 'skip' as const, removed: 0, filled: 0 }]]), new Map([['cola', 10]]))
    expect(left.get('cola')).toEqual({ van: 10, machine: 0 })
    expect(left.has('schorle')).toBe(false)
  })

  it('a swap where everything moves leaves nothing over', () => {
    const items = [
      item('i16', 't16', 'balisto', 'duplo', 12, 12),
      item('i17', 't17', 'duplo', 'balisto', 14, 14),
    ]
    const outcome = new Map([
      ['i16', { action: 'done' as const, removed: 10, filled: 11 }],
      ['i17', { action: 'done' as const, removed: 11, filled: 14 }],
    ])
    const left = computeLeftovers(items, outcome, new Map([['balisto', 4]]))
    expect([...left.entries()].filter(([, v]) => v.van + v.machine > 0)).toEqual([])
  })

  it('lists the prices to change at the machine', () => {
    const rows = [
      { item_number: 12, to_product_id: 'cola', from_price: 1.8, to_price: 2 },
      { item_number: 13, to_product_id: 'redbull', from_price: 2.5, to_price: 2.5 },
      { item_number: 14, to_product_id: null, from_price: 1, to_price: null },
    ]
    expect(priceChanges(rows)).toEqual([{ item_number: 12, price: 2 }])
  })
})

describe('ageChanged', () => {
  it('compares the age limits of the old and new product, null = none', () => {
    expect(ageChanged({ from_min_age: null, to_min_age: 18 })).toBe(true)
    expect(ageChanged({ from_min_age: 16, to_min_age: 18 })).toBe(true)
    expect(ageChanged({ from_min_age: 18, to_min_age: null })).toBe(true)
    expect(ageChanged({ from_min_age: 18, to_min_age: 18 })).toBe(false)
    expect(ageChanged({ from_min_age: null, to_min_age: null })).toBe(false)
    expect(ageChanged({})).toBe(false)
  })
})
