import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref as vueRef, computed as vueComputed } from 'vue'

// Change note in the refill tour: accepted slots get their own packing line,
// leave the normal refill, and are quit via apply_slot_change BEFORE the
// normal refill RPC runs.

;(globalThis as any).ref = vueRef
;(globalThis as any).computed = vueComputed

const { rpcCalls, applySlotChange, fetchOpenRequests } = vi.hoisted(() => ({
  rpcCalls: [] as { fn: string; args: any }[],
  applySlotChange: vi.fn(async (_input: unknown) => ({ request_status: 'done' })),
  fetchOpenRequests: vi.fn(),
}))

const TRAYS = [
  { id: 't11', machine_id: 'm1', item_number: 11, product_id: 'cola', capacity: 10, current_stock: 2, min_stock: 0, fill_when_below: 5, products: { name: 'Cola', image_path: null, sellprice: 2 } },
  { id: 't12', machine_id: 'm1', item_number: 12, product_id: 'schorle', capacity: 10, current_stock: 8, min_stock: 0, fill_when_below: 0, products: { name: 'Schorle', image_path: null, sellprice: 1.8 } },
]

function query(data: unknown) {
  const q: any = {
    select: () => q, eq: () => q, in: () => q, gt: () => q, order: () => q, maybeSingle: () => q,
    insert: () => Promise.resolve({ error: null }),
    then: (res: (v: unknown) => unknown) => Promise.resolve({ data, error: null }).then(res),
  }
  return q
}
const supabase = {
  from: (table: string) => query(
    table === 'vendingMachine' ? [{ id: 'm1', name: 'M1' }]
      : table === 'machine_trays' ? TRAYS
        : table === 'warehouse_stock_batches' ? [{ product_id: 'cola', quantity: 30 }]
          : table === 'warehouses' ? { name: 'Lager' } : [],
  ),
  rpc: (fn: string, args: any) => {
    rpcCalls.push({ fn, args })
    if (fn === 'refill_machine_trays') {
      return Promise.resolve({ data: args.p_trays.map((t: any) => ({ tray_id: t.tray_id, old_stock: 2, new_stock: 2 + t.fill_amount, fill_amount: t.fill_amount, was_already_applied: false })), error: null })
    }
    return Promise.resolve({ data: [], error: null })
  },
  auth: { getSession: async () => ({ data: { session: { user: { id: 'u1', email: 'a@b.c', user_metadata: {} } } } }) },
}

vi.mock('#imports', async () => {
  const vue = await import('vue')
  return { ...vue, useSupabaseClient: () => supabase }
})
vi.mock('../useOrganization', () => ({ useOrganization: () => ({ organization: vueRef({ id: 'c1' }) }) }))
vi.mock('../useWarehouse', () => ({ useWarehouse: () => ({ fetchOrderedProductIds: async () => [] }) }))
vi.mock('../useSlotChangeRequests', () => ({
  useSlotChangeRequests: () => ({ fetchOpenRequests, applySlotChange, suggestExpiry: async () => '2026-12-01' }),
}))

import { useRefillWizard } from '../useRefillWizard'

const ITEM = {
  id: 'i12', request_id: 'r1', tray_id: 't12', item_number: 12,
  from_product_id: 'schorle', to_product_id: 'cola', from_capacity: 10, to_capacity: 10,
  from_price: 1.8, to_price: 2, skip_count: 0, from_name: 'Schorle', to_name: 'Cola',
  from_image_path: null, to_image_path: null, from_image_url: null, to_image_url: null,
}

describe('refill tour with a change note', () => {
  beforeEach(() => {
    rpcCalls.length = 0
    applySlotChange.mockClear()
    fetchOpenRequests.mockResolvedValue(new Map([['m1', { id: 'r1', machine_id: 'm1', created_at: '', updated_at: '', note: null, items: [ITEM] }]]))
    try { localStorage.clear() } catch { /* ignore */ }
  })

  async function startedTour() {
    const w = useRefillWizard()
    await w.initTour()
    w.selectedWarehouseId.value = 'wh1'
    await w.loadWarehouseStock()
    return w
  }

  it('packs the rebuild on top of the normal refill and deducts once per product', async () => {
    const w = await startedTour()
    const machine = w.machines.value[0]!
    expect(machine.change_request?.items).toHaveLength(1)
    // Slot 12 is being rebuilt, so only cola's normal deficit (8) is listed
    expect(machine.tray_summary.map(i => [i.product_id, i.deficit])).toEqual([['cola', 8]])
    expect(w.rebuildNeeds('m1')).toEqual(new Map([['cola', 10]]))
    expect(w.rebuildCommitted('m1', 'cola')).toBe(10)

    w.togglePacked('m1', machine.tray_summary[0]!)
    await w.startTour()

    const deductions = rpcCalls.filter(c => c.fn === 'deduct_warehouse_stock_fifo')
    expect(deductions.map(d => [d.args.p_product_id, d.args.p_quantity])).toEqual([['cola', 18]])
    // At the machine: the rebuilt slot is not part of the normal refill
    expect(w.currentTrays.value.map(t => t.id)).toEqual(['t11'])
    expect(w.currentRebuild.value.map(s => [s.item.id, s.removed, s.filled])).toEqual([['i12', 8, 10]])
  })

  it('quits the rebuild before the normal refill and returns the old stock', async () => {
    const w = await startedTour()
    w.togglePacked('m1', w.machines.value[0]!.tray_summary[0]!)
    await w.startTour()

    await new Promise(r => setTimeout(r, 0))  // best-before suggestion loads async
    expect(w.leftoverExpiry.value.get('schorle')).toBe('2026-12-01')
    expect(w.rebuildReady.value).toBe(false)
    w.setRebuildAction('i12', 'done')
    w.setRebuildPriceSet('i12', true)
    expect(w.rebuildLeftovers.value.map(l => [l.product_id, l.van, l.machine])).toEqual([['schorle', 0, 8]])

    await w.confirmMachineRefill()

    expect(applySlotChange).toHaveBeenCalledTimes(1)
    const input = applySlotChange.mock.calls[0]![0] as any
    expect(input.items).toEqual([{ item_id: 'i12', action: 'done', removed: 8, filled: 10, price_set: true }])
    expect(input.leftovers).toEqual([{ product_id: 'schorle', van_qty: 0, machine_qty: 8, destination: 'warehouse', expiration_date: '2026-12-01', batch_number: 'Machine return' }])
    const refill = rpcCalls.find(c => c.fn === 'refill_machine_trays')!
    expect(refill.args.p_trays).toEqual([{ tray_id: 't11', fill_amount: 8 }])
    expect(w.tourLog.value[0]!.slots_rebuilt).toBe(1)
  })

  it('a declined slot is refilled normally and packs nothing for the rebuild', async () => {
    const w = await startedTour()
    w.toggleRebuildItem('m1', 'i12')
    expect(w.rebuildNeeds('m1')).toEqual(new Map())
    expect(w.rebuildCommitted('m1', 'cola')).toBe(0)
    expect(w.hasAnyPackedItems()).toBe(false)
  })

  it('skipping the machine leaves the slots open and returns the packed units', async () => {
    const w = await startedTour()
    await w.startTour()
    await w.skipMachine()
    const input = applySlotChange.mock.calls[0]![0] as any
    expect(input.items[0]).toMatchObject({ item_id: 'i12', action: 'skip' })
    expect(input.leftovers).toEqual([expect.objectContaining({ product_id: 'cola', van_qty: 10, machine_qty: 0 })])
  })
})
