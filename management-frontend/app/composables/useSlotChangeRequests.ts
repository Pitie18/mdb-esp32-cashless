import { useSupabaseClient } from '#imports'
import { getProductImageUrl } from './useProducts'
import type { ChangeItem } from '@/lib/slotChange'

// ─────────────────────────────────────────────────────────────────────────────
// Slot change requests ("Fächer umbelegen")
//
// A planned layout is a change request, never a tray update. The office saves
// it here; the refill tour shows it as a change note and the refiller quits
// the rebuild at the machine (apply_slot_change). All writes go through the
// SECURITY DEFINER RPCs in 20261008120000_slot_change_requests.sql, which iOS
// and Android call the same way.
// ─────────────────────────────────────────────────────────────────────────────

export interface SlotChangeItem extends ChangeItem {
  request_id: string
  from_price: number | null
  to_price: number | null
  skip_count: number
  from_name: string | null
  to_name: string | null
  from_image_url: string | null
  to_image_url: string | null
  from_image_path: string | null
  to_image_path: string | null
}

export interface SlotChangeRequest {
  id: string
  machine_id: string
  created_at: string
  updated_at: string
  note: string | null
  items: SlotChangeItem[]
}

export interface PlannedChange {
  tray_id: string
  to_product_id: string | null
  to_capacity: number
}

export interface SlotChangeApplyItem {
  item_id: string
  action: 'done' | 'skip'
  removed?: number
  filled?: number
  price_set?: boolean
}

export interface SlotChangeLeftover {
  product_id: string
  van_qty: number
  machine_qty: number
  destination: 'warehouse' | 'waste'
  expiration_date?: string | null
  batch_number?: string | null
}

const ITEM_SELECT = `id, request_id, tray_id, item_number, from_product_id, to_product_id,
  from_capacity, to_capacity, from_price, to_price, skip_count,
  from_product:products!slot_change_request_items_from_product_id_fkey(name, image_path),
  to_product:products!slot_change_request_items_to_product_id_fkey(name, image_path)`

function mapItem(row: any): SlotChangeItem {
  const fromPath = row.from_product?.image_path ?? null
  const toPath = row.to_product?.image_path ?? null
  return {
    id: row.id,
    request_id: row.request_id,
    tray_id: row.tray_id,
    item_number: Number(row.item_number),
    from_product_id: row.from_product_id ?? null,
    to_product_id: row.to_product_id ?? null,
    from_capacity: row.from_capacity,
    to_capacity: row.to_capacity,
    from_price: row.from_price ?? null,
    to_price: row.to_price ?? null,
    skip_count: row.skip_count ?? 0,
    from_name: row.from_product?.name ?? null,
    to_name: row.to_product?.name ?? null,
    from_image_path: fromPath,
    to_image_path: toPath,
    from_image_url: fromPath ? getProductImageUrl(fromPath) : null,
    to_image_url: toPath ? getProductImageUrl(toPath) : null,
  }
}

export function useSlotChangeRequests() {
  const supabase = useSupabaseClient()

  /** Open requests with their pending items, keyed by machine id. */
  async function fetchOpenRequests(machineIds: string[]): Promise<Map<string, SlotChangeRequest>> {
    const result = new Map<string, SlotChangeRequest>()
    if (machineIds.length === 0) return result
    const { data: reqs, error } = await (supabase as any)
      .from('slot_change_requests')
      .select('id, machine_id, created_at, updated_at, note')
      .eq('status', 'open')
      .in('machine_id', machineIds)
    if (error) throw error
    if (!reqs?.length) return result

    const { data: items, error: itemErr } = await (supabase as any)
      .from('slot_change_request_items')
      .select(ITEM_SELECT)
      .eq('status', 'pending')
      .in('request_id', reqs.map((r: any) => r.id))
      .order('item_number')
    if (itemErr) throw itemErr

    for (const r of reqs as any[]) {
      const own = ((items ?? []) as any[]).filter(i => i.request_id === r.id).map(mapItem)
      if (own.length === 0) continue
      result.set(r.machine_id, { ...r, items: own })
    }
    return result
  }

  async function fetchOpenRequest(machineId: string): Promise<SlotChangeRequest | null> {
    return (await fetchOpenRequests([machineId])).get(machineId) ?? null
  }

  /** Replace the machine's pending plan. Returns the request id, or null when nothing changes. */
  async function saveRequest(machineId: string, changes: PlannedChange[], note?: string | null): Promise<string | null> {
    const { data, error } = await (supabase as any).rpc('save_slot_change_request', {
      p_machine_id: machineId,
      p_items: changes,
      p_note: note ?? null,
    })
    if (error) throw error
    return (data as string | null) ?? null
  }

  async function withdrawRequest(requestId: string) {
    const { error } = await (supabase as any).rpc('withdraw_slot_change_request', { p_request_id: requestId })
    if (error) throw error
  }

  /**
   * Quit the rebuild at the machine. Idempotent per (request, tour), so a
   * retry after a network error cannot book anything twice.
   */
  async function applySlotChange(input: {
    requestId: string
    tourId: string
    warehouseId: string | null
    items: SlotChangeApplyItem[]
    leftovers: SlotChangeLeftover[]
  }) {
    const { data, error } = await (supabase as any).rpc('apply_slot_change', {
      p_request_id: input.requestId,
      p_tour_id: input.tourId,
      p_warehouse_id: input.warehouseId,
      p_items: input.items,
      p_leftovers: input.leftovers,
    })
    if (error) throw error
    return data as Record<string, unknown>
  }

  /**
   * Best-before date to suggest for goods taken out of a machine: the date of
   * the batch the last refill of that product into this machine came from.
   * The machine does not track batches, so the refiller confirms it.
   */
  async function suggestExpiry(machineId: string, productId: string): Promise<string | null> {
    const { data } = await (supabase as any)
      .from('warehouse_transactions')
      .select('expiration_date')
      .eq('reference_id', machineId)
      .eq('product_id', productId)
      .eq('transaction_type', 'outgoing_refill')
      .not('expiration_date', 'is', null)
      .order('created_at', { ascending: false })
      .limit(1)
    return (data?.[0]?.expiration_date as string | undefined) ?? null
  }

  return { fetchOpenRequests, fetchOpenRequest, saveRequest, withdrawRequest, applySlotChange, suggestExpiry }
}
