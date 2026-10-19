import { describe, it, expect } from 'vitest'
import { chipCounts, matchesChip, needsAction, resolveWarehouseView, type StockChipItem } from '../warehouseView'

function item(over: Partial<StockChipItem> = {}): StockChipItem {
  return { product_id: 'p', total_quantity: 10, is_below_min: false, expiration_status: 'ok', discontinued: false, ...over }
}

describe('needsAction', () => {
  it('flags empty, below-min and expiring products in a machine', () => {
    expect(needsAction(item({ total_quantity: 0 }), true)).toBe(true)
    expect(needsAction(item({ is_below_min: true }), true)).toBe(true)
    expect(needsAction(item({ expiration_status: 'warning' }), true)).toBe(true)
    expect(needsAction(item(), true)).toBe(false)
  })

  it('ignores hidden products', () => {
    expect(needsAction(item({ total_quantity: 0 }), false)).toBe(false)
    expect(needsAction(item({ total_quantity: 0, discontinued: true }), true)).toBe(false)
  })
})

describe('matchesChip', () => {
  it('all shows products that are in a machine and not discontinued', () => {
    expect(matchesChip(item(), true, 'all')).toBe(true)
    expect(matchesChip(item(), false, 'all')).toBe(false)
    expect(matchesChip(item({ discontinued: true }), true, 'all')).toBe(false)
  })

  it('all keeps an empty product that still sits in a machine', () => {
    expect(matchesChip(item({ total_quantity: 0 }), true, 'all')).toBe(true)
  })

  it('hidden lists discontinued or unassigned products with stock only', () => {
    expect(matchesChip(item(), false, 'hidden')).toBe(true)
    expect(matchesChip(item({ discontinued: true }), true, 'hidden')).toBe(true)
    expect(matchesChip(item({ total_quantity: 0 }), false, 'hidden')).toBe(false)
    expect(matchesChip(item(), true, 'hidden')).toBe(false)
  })

  it('expiring ignores discontinued and empty products', () => {
    expect(matchesChip(item({ expiration_status: 'critical' }), false, 'expiring')).toBe(true)
    expect(matchesChip(item({ expiration_status: 'critical', discontinued: true }), true, 'expiring')).toBe(false)
    expect(matchesChip(item({ expiration_status: 'critical', total_quantity: 0 }), true, 'expiring')).toBe(false)
  })
})

describe('chipCounts', () => {
  it('counts every chip', () => {
    const items = [
      item({ product_id: 'a' }),
      item({ product_id: 'b', total_quantity: 0 }),
      item({ product_id: 'c', expiration_status: 'warning' }),
      item({ product_id: 'd' }),
    ]
    expect(chipCounts(items, new Set(['a', 'b', 'c']))).toEqual({ all: 3, action: 2, expiring: 1, hidden: 1 })
  })
})

describe('resolveWarehouseView', () => {
  it('maps the old tabs', () => {
    expect(resolveWarehouseView('overview', undefined, true).tab).toBe('stock')
    expect(resolveWarehouseView('stock', undefined, true).tab).toBe('stock')
    expect(resolveWarehouseView('positions', undefined, true)).toEqual({ tab: 'setup', section: 'positions' })
    expect(resolveWarehouseView('settings', undefined, true)).toEqual({ tab: 'setup', section: 'warehouses' })
  })

  it('keeps incoming and history', () => {
    expect(resolveWarehouseView('incoming', undefined, false).tab).toBe('incoming')
    expect(resolveWarehouseView('history', undefined, false).tab).toBe('history')
  })

  it('opens a setup section', () => {
    expect(resolveWarehouseView('setup', 'barcodes', true)).toEqual({ tab: 'setup', section: 'barcodes' })
    expect(resolveWarehouseView('setup', 'nope', true)).toEqual({ tab: 'setup', section: 'warehouses' })
  })

  it('keeps non-admins out of setup', () => {
    expect(resolveWarehouseView('positions', undefined, false).tab).toBe('stock')
    expect(resolveWarehouseView('setup', 'barcodes', false).tab).toBe('stock')
  })

  it('falls back to stock', () => {
    expect(resolveWarehouseView(undefined, undefined, true).tab).toBe('stock')
    expect(resolveWarehouseView('garbage', undefined, true).tab).toBe('stock')
  })
})
