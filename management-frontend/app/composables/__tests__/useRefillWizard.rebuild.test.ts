import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref as vueRef, computed as vueComputed } from 'vue'

// Change note in the refill tour: accepted slots add their units to the
// normal packing list, leave the normal refill, and are quit via
// apply_slot_change BEFORE the normal refill RPC runs. Their leftovers are
// booked back once, at the end of the tour.

;(globalThis as any).ref = vueRef
;(globalThis as any).computed = vueComputed

const { rpcCalls, inserts, applySlotChange, fetchOpenRequests } = vi.hoisted(() => ({
  rpcCalls: [] as { fn: string; args: any }[],
  inserts: [] as { table: string; row: any }[],
  applySlotChange: vi.fn(async (_input: unknown) => ({ request_status: 'done' })),
  fetchOpenRequests: vi.fn(),
}))

const TRAYS = [
  { id: 't11', machine_id: 'm1', item_number: 11, product_id: 'cola', capacity: 10, current_stock: 2, min_stock: 0, fill_when_below: 5, products: { name: 'Cola', image_path: null, sellprice: 2 } },
  { id: 't12', machine_id: 'm1', item_number: 12, product_id: 'schorle', capacity: 10, current_stock: 8, min_stock: 0, fill_when_below: 0, products: { name: 'Schorle', image_path: null, sellprice: 1.8 } },
]

function query(data: unknown, table = '') {
  const q: any = {
    select: () => q, eq: () => q, in: () => q, gt: () => q, order: () => q, maybeSingle: () => q,
    insert: (row: unknown) => { inserts.push({ table, row }); return Promise.resolve({ error: null }) },
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
    table,
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
  from_price: 1.8, to_price: 2, from_min_age: null as number | null, to_min_age: null as number | null, skip_count: 0, from_name: 'Schorle', to_name: 'Cola',
  from_image_path: null, to_image_path: null, from_image_url: null, to_image_url: null,
}

describe('refill tour with a change note', () => {
  beforeEach(() => {
    rpcCalls.length = 0
    inserts.length = 0
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

    // The rebuild units sit on cola's normal packing row, not in a list of their own
    expect(w.machinePackRows(machine)).toEqual([
      expect.objectContaining({ kind: 'refill', product_id: 'cola', rebuild: 10 }),
    ])
    expect(w.combinedPackRows.value).toEqual([
      expect.objectContaining({ kind: 'refill', product_id: 'cola', rebuild: 10 }),
    ])

    w.togglePacked('m1', machine.tray_summary[0]!)
    await w.startTour()

    const deductions = rpcCalls.filter(c => c.fn === 'deduct_warehouse_stock_fifo')
    expect(deductions.map(d => [d.args.p_product_id, d.args.p_quantity])).toEqual([['cola', 18]])
    // At the machine: the rebuilt slot is not part of the normal refill
    expect(w.currentTrays.value.map(t => t.id)).toEqual(['t11'])
    expect(w.currentRebuild.value.map(s => [s.item.id, s.removed, s.filled])).toEqual([['i12', 8, 10]])
  })

  it('quits the rebuild before the normal refill and books the old stock back at the end of the tour', async () => {
    const w = await startedTour()
    w.togglePacked('m1', w.machines.value[0]!.tray_summary[0]!)
    await w.startTour()

    expect(w.rebuildReady.value).toBe(false)
    w.setRebuildAction('i12', 'done')
    w.setRebuildPriceSet('i12', true)
    expect(w.rebuildLeftovers.value.map(l => [l.product_id, l.van, l.machine])).toEqual([['schorle', 0, 8]])

    await w.confirmMachineRefill()

    expect(applySlotChange).toHaveBeenCalledTimes(1)
    const input = applySlotChange.mock.calls[0]![0] as any
    expect(input.items).toEqual([{ item_id: 'i12', action: 'done', removed: 8, filled: 10, price_set: true, age_set: false }])
    // Nothing is booked at the machine: the old stock rides along in the van
    expect(input.leftovers).toEqual([])
    const refill = rpcCalls.find(c => c.fn === 'refill_machine_trays')!
    expect(refill.args.p_trays).toEqual([{ tray_id: 't11', fill_amount: 8 }])
    expect(w.tourLog.value[0]!.slots_rebuilt).toBe(1)
    // The history lists the rebuilt slot next to the refilled one
    expect(w.tourLog.value[0]!.total_added).toBe(18)
    const log = inserts.find(i => i.table === 'activity_log' && i.row.action === 'stock_refill_tour')!
    expect(log.row.metadata.products).toEqual([
      { product_id: 'cola', product_name: 'Cola', quantity: 8 },
      { product_id: 'cola', product_name: 'Cola', quantity: 10, item_number: 12, rebuild: true },
    ])
    expect(log.row.metadata.total_added).toBe(18)
    expect(log.row.metadata.trays_refilled).toBe(2)

    // End of the tour, back at the warehouse
    expect(w.currentStep.value).toBe('summary')
    expect(w.hasPendingLeftovers.value).toBe(true)
    await w.loadTourExpirySuggestions()
    expect(w.tourLeftoverRows.value).toEqual([expect.objectContaining({
      product_id: 'schorle', van: 0, machine: 8, destination: 'warehouse', expiration_date: '2026-12-01',
    })])
    w.setLeftoverCount('schorle', 'machine', 7)  // one was broken, counted at the warehouse
    expect(await w.returnTourLeftovers()).toBe(true)
    const ret = rpcCalls.find(c => c.fn === 'return_slot_change_leftovers')!
    expect(ret.args).toEqual({
      p_tour_id: expect.any(String),
      p_warehouse_id: 'wh1',
      p_leftovers: [{ product_id: 'schorle', van_qty: 0, machine_qty: 7, destination: 'warehouse', expiration_date: '2026-12-01', batch_number: 'Machine return' }],
    })
    expect(w.hasPendingLeftovers.value).toBe(false)
  })

  it('a product only the rebuild needs gets its own packing row in warehouse order', async () => {
    fetchOpenRequests.mockResolvedValue(new Map([['m1', { id: 'r1', machine_id: 'm1', created_at: '', updated_at: '', note: null, items: [
      { ...ITEM, to_product_id: 'mate', to_name: 'Mate' },
    ] }]]))
    const w = await startedTour()
    const machine = w.machines.value[0]!
    expect(w.machinePackRows(machine).map(r => [r.kind, r.product_id])).toEqual([['refill', 'cola'], ['rebuild', 'mate']])
    expect(w.allPackedWithRebuild(machine)).toBe(false)
    w.togglePacked('m1', machine.tray_summary[0]!)
    expect(w.allPackedWithRebuild(machine)).toBe(false)
    w.toggleRebuildTick('m1', 'mate')
    expect(w.allPackedWithRebuild(machine)).toBe(true)
    expect(w.combinedPackRows.value.map(r => [r.kind, r.product_id])).toEqual([['refill', 'cola'], ['rebuild', 'mate']])
  })

  it('a declined slot is refilled normally and packs nothing for the rebuild', async () => {
    const w = await startedTour()
    w.toggleRebuildItem('m1', 'i12')
    expect(w.rebuildNeeds('m1')).toEqual(new Map())
    expect(w.rebuildCommitted('m1', 'cola')).toBe(0)
    expect(w.hasAnyPackedItems()).toBe(false)
  })

  it('skipping the machine leaves the slots open and returns the packed units at the end of the tour', async () => {
    const w = await startedTour()
    await w.startTour()
    await w.skipMachine()
    const input = applySlotChange.mock.calls[0]![0] as any
    expect(input.items[0]).toMatchObject({ item_id: 'i12', action: 'skip' })
    expect(input.leftovers).toEqual([])
    expect(w.tourLeftoverRows.value).toEqual([expect.objectContaining({ product_id: 'cola', van: 10, machine: 0 })])
  })

  it('a slot whose age limit changes can only be quit after the age setting is confirmed', async () => {
    fetchOpenRequests.mockResolvedValue(new Map([['m1', { id: 'r1', machine_id: 'm1', created_at: '', updated_at: '', note: null, items: [{ ...ITEM, to_min_age: 18 }] }]]))
    const w = await startedTour()
    w.togglePacked('m1', w.machines.value[0]!.tray_summary[0]!)
    await w.startTour()

    w.setRebuildAction('i12', 'done')
    expect(w.currentRebuild.value[0]!.action).toBeNull()
    w.setRebuildAgeSet('i12', true)
    w.setRebuildAction('i12', 'done')
    expect(w.currentRebuild.value[0]!.action).toBe('done')
    // Unticking it takes the slot back to undecided
    w.setRebuildAgeSet('i12', false)
    expect(w.currentRebuild.value[0]!.action).toBeNull()
    w.setRebuildAgeSet('i12', true)
    w.setRebuildAction('i12', 'done')

    await w.confirmMachineRefill()
    const input = applySlotChange.mock.calls[0]![0] as any
    expect(input.items[0]).toMatchObject({ item_id: 'i12', action: 'done', age_set: true })
  })
})
