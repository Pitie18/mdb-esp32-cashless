import { describe, it, expect } from 'vitest'
import {
  buildOrderGroups, orderText, suggestedCartons, targetDays, reachDays,
  DEFAULT_ORDER_SETTINGS, type OrderProductInput, type OrderSupplier,
} from '../orderSuggestion'

const becker: OrderSupplier = { id: 's1', name: 'Becker', customer_number: '40817', lead_time_days: 2, order_interval_days: 14, min_order_value: 50 }
const hahn: OrderSupplier = { id: 's2', name: 'Hahn', lead_time_days: null, order_interval_days: null, min_order_value: null }

function product(over: Partial<OrderProductInput> = {}): OrderProductInput {
  return {
    product_id: 'p1', product_name: 'Cola', discontinued: false,
    warehouse_qty: 12, machine_qty: 18, avg_daily_sales: 3.1, on_order: 0,
    unit_size: 24, supplier_id: 's1', unit_price_net: 0.52, ...over,
  }
}

const settings = DEFAULT_ORDER_SETTINGS

describe('targetDays', () => {
  it('adds interval, lead time and buffer', () => {
    expect(targetDays(becker, settings)).toBe(14 + 2 + 3)
  })
  it('falls back to the company defaults', () => {
    expect(targetDays(hahn, settings)).toBe(14 + 3 + 3)
    expect(targetDays(null, settings)).toBe(20)
  })
})

describe('suggestedCartons', () => {
  it('rounds the missing units up to cartons', () => {
    // 3.1 × 19 = 58.9 needed, 30 available → 28.9 missing → 2 cartons of 24
    expect(suggestedCartons(product(), 19, settings)).toBe(2)
  })
  it('subtracts what is already on order', () => {
    expect(suggestedCartons(product({ on_order: 24 }), 19, settings)).toBe(1)
    expect(suggestedCartons(product({ on_order: 48 }), 19, settings)).toBe(0)
  })
  it('ignores machine stock when the setting is off', () => {
    // 58.9 − 12 = 46.9 → 2 cartons
    expect(suggestedCartons(product({ machine_qty: 100 }), 19, { ...settings, countMachineStock: false })).toBe(2)
    expect(suggestedCartons(product({ machine_qty: 100 }), 19, settings)).toBe(0)
  })
  it('never suggests discontinued or unsold products', () => {
    expect(suggestedCartons(product({ discontinued: true, warehouse_qty: 0 }), 19, settings)).toBe(0)
    expect(suggestedCartons(product({ avg_daily_sales: 0, warehouse_qty: 0 }), 19, settings)).toBe(0)
  })
  it('does not add a carton for float noise', () => {
    // 3.1 × 10 = 31.000000000000004; 7 available → exactly 24 missing → 1 carton
    expect(suggestedCartons(product({ warehouse_qty: 7, machine_qty: 0 }), 10, settings)).toBe(1)
  })
})

describe('reachDays', () => {
  it('counts warehouse and machines', () => {
    expect(reachDays(product(), settings)).toBe(10)
    expect(reachDays(product({ avg_daily_sales: 0 }), settings)).toBeNull()
  })
})

describe('buildOrderGroups', () => {
  it('groups by supplier, with manual entries and others', () => {
    const groups = buildOrderGroups({
      products: [
        product(),
        product({ product_id: 'p2', product_name: 'Fanta', warehouse_qty: 100 }),
        product({ product_id: 'p3', product_name: 'Snickers', supplier_id: 's2', warehouse_qty: 80, avg_daily_sales: 1 }),
        product({ product_id: 'p4', product_name: 'Nuts', supplier_id: null, warehouse_qty: 0, machine_qty: 0, unit_size: 1 }),
      ],
      suppliers: [hahn, becker],
      manual: [{ product_id: 'p3', added_by_name: 'Lucien', added_via: 'ios', created_at: '2026-10-19T08:00:00Z' }],
      settings,
    })
    expect(groups.map(g => g.supplier?.name ?? null)).toEqual(['Becker', 'Hahn', null])

    const [b, h, none] = groups
    expect(b!.lines.map(l => l.product.product_id)).toEqual(['p1'])
    expect(b!.others.map(p => p.product_id)).toEqual(['p2'])
    expect(b!.lines[0]!.auto).toBe(true)
    expect(b!.lines[0]!.units).toBe(48)
    expect(b!.value).toBeCloseTo(48 * 0.52)
    expect(b!.belowMinOrder).toBe(true)

    // Manual entry with enough stock still gets one carton
    expect(h!.lines[0]!.auto).toBe(false)
    expect(h!.lines[0]!.manual?.added_by_name).toBe('Lucien')
    expect(h!.lines[0]!.cartons).toBe(1)

    expect(none!.supplier).toBeNull()
    expect(none!.hasEmpty).toBe(true)
    expect(none!.lines[0]!.cartons).toBe(62) // 3.1 × 20 days, cartons of 1
  })

  it('applies quantity overrides and flags them', () => {
    const groups = buildOrderGroups({ products: [product()], suppliers: [becker], manual: [], overrides: { p1: 5 }, settings })
    const line = groups[0]!.lines[0]!
    expect(line.cartons).toBe(5)
    expect(line.units).toBe(120)
    expect(line.overridden).toBe(true)
    expect(groups[0]!.belowMinOrder).toBe(false)
  })

  it('keeps an overridden zero on the list', () => {
    const groups = buildOrderGroups({ products: [product()], suppliers: [becker], manual: [], overrides: { p1: 0 }, settings })
    expect(groups[0]!.lines[0]!.cartons).toBe(0)
    expect(groups[0]!.belowMinOrder).toBe(false)
  })

  it('drops the no-supplier group when empty and suppliers with nothing at all', () => {
    const groups = buildOrderGroups({ products: [product({ warehouse_qty: 500 })], suppliers: [becker, hahn], manual: [], settings })
    expect(groups.map(g => g.supplier?.id)).toEqual(['s1'])
    expect(groups[0]!.lines).toEqual([])
  })
})

describe('orderText', () => {
  it('lists units, cartons and the customer number', () => {
    const [g] = buildOrderGroups({ products: [product()], suppliers: [becker], manual: [], settings })
    expect(orderText(g!, { customerNumber: 'Kd.-Nr.', carton: (n, s) => `${n} × ${s}` }))
      .toBe('Becker\nKd.-Nr.: 40817\n\n48 × Cola (2 × 24)')
  })
})
