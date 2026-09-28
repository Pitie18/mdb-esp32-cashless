import { describe, it, expect } from 'vitest'
import { buildTrayGroupIndex, productListRows } from '../trayGroups'

const tray = (id: string, item_number: number, product_id: string | null, current_stock: number, over: Record<string, number> = {}) => ({
  id, item_number, product_id, current_stock, machine_id: 'm1', capacity: 10, min_stock: 2, fill_when_below: 5, ...over,
})

describe('buildTrayGroupIndex', () => {
  it('flags only the slots with room when the product needs refill', () => {
    const idx = buildTrayGroupIndex([tray('a', 12, 'cola', 0), tray('b', 13, 'cola', 1), tray('c', 14, 'cola', 10)])
    // 11/30, min 6 → fill (11 > 6, ≤ 15)
    expect(idx.get('a')!.flag).toBe('fill')
    expect(idx.get('b')!.flag).toBe('fill')
    expect(idx.get('c')!.flag).toBe('ok')
    expect(idx.get('a')!.group.current_stock).toBe(11)
  })

  it('marks an empty slot of a well-stocked product as slotEmpty, not low', () => {
    const idx = buildTrayGroupIndex([tray('a', 12, 'cola', 0), tray('b', 13, 'cola', 10), tray('c', 14, 'cola', 10)])
    expect(idx.get('a')!.flag).toBe('slotEmpty')
    expect(idx.get('a')!.needsRefill).toBe(false)
  })

  it('flags low when the product total is at or below the summed min_stock', () => {
    const idx = buildTrayGroupIndex([tray('a', 12, 'cola', 1), tray('b', 13, 'cola', 3)])
    expect(idx.get('a')!.flag).toBe('low')
    expect(idx.get('b')!.flag).toBe('low')
  })

  it('leaves unassigned slots out', () => {
    expect(buildTrayGroupIndex([tray('a', 12, null, 0)]).has('a')).toBe(false)
  })
})

describe('productListRows', () => {
  const trays = [
    tray('fanta', 11, 'fanta', 5),
    tray('c14', 14, 'cola', 9),
    tray('c12', 12, 'cola', 0),
    tray('empty', 13, null, 0),
    tray('c20', 20, 'cola', 2),
  ]
  const idx = buildTrayGroupIndex(trays)

  it('orders products by their first slot and keeps a product together', () => {
    const rows = productListRows(trays, idx)
    expect(rows.map(r => r.tray.id)).toEqual(['fanta', 'c12', 'c14', 'c20', 'empty'])
  })

  it('puts a header on the first slot of multi-slot products only', () => {
    const rows = productListRows(trays, idx)
    expect(rows.map(r => r.header?.product_id ?? null)).toEqual([null, 'cola', null, null, null])
    expect(rows.map(r => r.inGroup)).toEqual([false, true, true, true, false])
  })

  it('filters rows but keeps whole-product totals in the header', () => {
    const rows = productListRows(trays, idx, t => t.id === 'c14')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.header?.current_stock).toBe(11)
  })
})
