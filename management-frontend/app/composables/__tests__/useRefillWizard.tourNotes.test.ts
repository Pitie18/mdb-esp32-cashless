import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref as vueRef, computed as vueComputed } from 'vue'

// Notes for the next tour: written at the machine, shown at the top of the
// packing step of every tour until the refiller ticks it off. A failing note
// lookup never blocks the tour.

;(globalThis as any).ref = vueRef
;(globalThis as any).computed = vueComputed

const { rpcCalls, notes, notesFail, rpcFail } = vi.hoisted(() => ({
  rpcCalls: [] as { fn: string; args: any }[],
  notes: [] as any[],
  notesFail: { value: false },
  rpcFail: { value: false },
}))

const TRAYS = [
  { id: 't11', machine_id: 'm1', item_number: 11, product_id: 'cola', capacity: 10, current_stock: 2, min_stock: 0, fill_when_below: 5, products: { name: 'Cola', image_path: null, sellprice: 2 } },
]

function query(table: string) {
  const data = table === 'vendingMachine' ? [{ id: 'm1', name: 'M1' }]
    : table === 'machine_trays' ? TRAYS
      : table === 'warehouse_stock_batches' ? [{ product_id: 'cola', quantity: 30 }]
        : table === 'machine_tour_notes' ? notes : []
  const q: any = {
    select: () => q, eq: () => q, in: () => q, gt: () => q, is: () => q, order: () => q,
    maybeSingle: () => q,
    insert: () => Promise.resolve({ error: null }),
    then: (res: (v: unknown) => unknown) => Promise.resolve(
      table === 'machine_tour_notes' && notesFail.value
        ? { data: null, error: new Error('relation does not exist') }
        : { data, error: null },
    ).then(res),
  }
  return q
}
const supabase = {
  from: (table: string) => query(table),
  rpc: (fn: string, args: any) => {
    rpcCalls.push({ fn, args })
    if (rpcFail.value) return Promise.resolve({ data: null, error: new Error('denied') })
    return Promise.resolve({ data: fn === 'refill_machine_trays' ? [] : null, error: null })
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
  useSlotChangeRequests: () => ({ fetchOpenRequests: async () => new Map(), applySlotChange: vi.fn(), suggestExpiry: async () => null }),
}))

import { useRefillWizard } from '../useRefillWizard'
import { notesForPacking, type TourNote } from '../useTourNotes'

const NOTE_ROW = { id: 'n1', machine_id: 'm1', note: '2 neue Spiralen', written_in_tour: 'T0', created_at: '2026-10-12T10:00:00Z', vendingMachine: { name: 'M1' } }

describe('notes for the next tour', () => {
  beforeEach(() => {
    rpcCalls.length = 0
    notes.length = 0
    notesFail.value = false
    rpcFail.value = false
    try { localStorage.clear() } catch { /* ignore */ }
  })

  it('keeps a note across tours until it is ticked off', async () => {
    notes.push(NOTE_ROW)
    const w = useRefillWizard()
    await w.initTour()
    expect(w.tourNotes.value).toEqual([
      { id: 'n1', machine_id: 'm1', machine_name: 'M1', note: '2 neue Spiralen', written_in_tour: 'T0', created_at: '2026-10-12T10:00:00Z' },
    ])

    // Starting the tour does not check it off
    w.selectedWarehouseId.value = 'wh1'
    await w.loadWarehouseStock()
    w.togglePacked('m1', w.machines.value[0]!.tray_summary[0]!)
    await w.startTour()
    expect(rpcCalls.some(c => c.fn === 'complete_machine_tour_notes')).toBe(false)
  })

  it('ticks a note off right away and puts it back when the server refuses', async () => {
    notes.push(NOTE_ROW)
    const w = useRefillWizard()
    await w.initTour()

    rpcFail.value = true
    await expect(w.completeTourNote('n1')).rejects.toThrow()
    expect(w.tourNotes.value.map(n => n.id)).toEqual(['n1'])

    rpcFail.value = false
    await w.completeTourNote('n1')
    expect(w.tourNotes.value).toEqual([])
    const done = rpcCalls.filter(c => c.fn === 'complete_machine_tour_notes')
    expect(done.at(-1)!.args).toEqual({ p_note_ids: ['n1'], p_tour_id: '' })
  })

  it('starts the tour without notes when the lookup fails', async () => {
    notesFail.value = true
    const w = useRefillWizard()
    await w.initTour()
    expect(w.tourNotes.value).toEqual([])
    expect(w.machines.value).toHaveLength(1)
    w.selectedWarehouseId.value = 'wh1'
    await w.startTour()
    expect(rpcCalls.some(c => c.fn === 'complete_machine_tour_notes')).toBe(false)
  })

  it('writes the note at the machine for the running tour', async () => {
    const w = useRefillWizard()
    await w.initTour()
    await expect(w.saveTourNote('m1', 'x')).rejects.toThrow()
    w.selectedWarehouseId.value = 'wh1'
    await w.loadWarehouseStock()
    w.togglePacked('m1', w.machines.value[0]!.tray_summary[0]!)
    await w.startTour()
    await w.saveTourNote('m1', 'Dosenerhöhung')
    const tourId = rpcCalls.find(c => c.fn === 'deduct_warehouse_stock_fifo')!.args.p_metadata.tour_id
    expect(rpcCalls.find(c => c.fn === 'save_machine_tour_note')!.args).toEqual({
      p_machine_id: 'm1', p_tour_id: tourId, p_note: 'Dosenerhöhung',
    })
  })
})

describe('notesForPacking', () => {
  const note = (id: string, tour: string | null, at: string): TourNote =>
    ({ id, machine_id: 'm1', machine_name: 'M1', note: id, written_in_tour: tour, created_at: at })

  it('sorts oldest first and leaves out the running tour', () => {
    const rows = [note('b', 'T1', '2026-10-12T10:00:00Z'), note('a', null, '2026-10-01T10:00:00Z'), note('c', 'T2', '2026-10-13T10:00:00Z')]
    expect(notesForPacking(rows, 'T2').map(n => n.id)).toEqual(['a', 'b'])
    expect(notesForPacking(rows, '').map(n => n.id)).toEqual(['a', 'b', 'c'])
  })
})
