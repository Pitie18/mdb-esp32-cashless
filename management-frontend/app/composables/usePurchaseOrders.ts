import { useSupabaseClient } from '#imports'
import { useOrganization } from './useOrganization'
import {
  DEFAULT_ORDER_SETTINGS,
  type ManualEntry, type OrderSettings, type OrderSupplier,
} from '@/lib/orderSuggestion'

export interface PurchaseOrderLine {
  id: string
  product_id: string | null
  product_name: string | null
  quantity: number
  unit_size: number
  unit_price_net: number | null
  received_quantity: number | null
}

export interface PurchaseOrder {
  id: string
  created_at: string
  warehouse_id: string
  supplier_id: string | null
  supplier_name: string | null
  status: 'ordered' | 'received' | 'closed' | 'cancelled'
  note: string | null
  ordered_by_name: string | null
  lines: PurchaseOrderLine[]
}

export interface ProductOrderInfo {
  preferred_supplier_id: string | null
  order_unit_size: number
  /** Supplier and net price of the latest purchase price. */
  latest_supplier_id: string | null
  latest_price_net: number | null
}

export interface ReceiveLineInput {
  line_id: string
  quantity: number
  expiration_date?: string | null
  batch_number?: string | null
}

/**
 * Data and actions behind the warehouse "Order" tab: suppliers with their
 * ordering rhythm, the company's calculation settings, manual order-list
 * entries and open purchase orders. The suggestion itself is computed by
 * lib/orderSuggestion.ts. Refs are per call; the page owns them.
 */
export function usePurchaseOrders() {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()

  const suppliers = ref<OrderSupplier[]>([])
  const settings = ref<OrderSettings>({ ...DEFAULT_ORDER_SETTINGS })
  const manualEntries = ref<ManualEntry[]>([])
  const openOrders = ref<PurchaseOrder[]>([])
  const productInfo = ref<Map<string, ProductOrderInfo>>(new Map())

  async function fetchSuppliers() {
    const { data, error } = await (supabase as any)
      .from('suppliers')
      .select('id, name, email, phone, address, customer_number, lead_time_days, order_interval_days, min_order_value')
      .order('name')
    if (error) throw error
    suppliers.value = ((data ?? []) as any[]).map(s => ({
      ...s,
      min_order_value: s.min_order_value == null ? null : Number(s.min_order_value),
    }))
  }

  async function createSupplier(name: string): Promise<string> {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const { data, error } = await (supabase as any)
      .from('suppliers')
      .insert({ company_id: companyId, name: name.trim() })
      .select('id')
      .single()
    if (error) throw error
    await fetchSuppliers()
    return data.id as string
  }

  async function updateSupplier(id: string, updates: Partial<Omit<OrderSupplier, 'id'>> & { phone?: string | null; address?: string | null }) {
    const { error } = await (supabase as any).from('suppliers').update(updates).eq('id', id)
    if (error) throw error
    await fetchSuppliers()
  }

  async function fetchSettings() {
    const companyId = organization.value?.id
    if (!companyId) return
    const { data } = await (supabase as any)
      .from('companies')
      .select('order_buffer_days, order_count_machine_stock, order_default_lead_days, order_default_interval_days')
      .eq('id', companyId)
      .maybeSingle()
    if (data) {
      settings.value = {
        bufferDays: data.order_buffer_days ?? DEFAULT_ORDER_SETTINGS.bufferDays,
        countMachineStock: data.order_count_machine_stock ?? DEFAULT_ORDER_SETTINGS.countMachineStock,
        defaultLeadDays: data.order_default_lead_days ?? DEFAULT_ORDER_SETTINGS.defaultLeadDays,
        defaultIntervalDays: data.order_default_interval_days ?? DEFAULT_ORDER_SETTINGS.defaultIntervalDays,
      }
    }
  }

  async function saveSettings(next: OrderSettings) {
    const companyId = organization.value?.id
    if (!companyId) return
    const previous = settings.value
    settings.value = next
    const { error } = await (supabase as any)
      .from('companies')
      .update({
        order_buffer_days: next.bufferDays,
        order_count_machine_stock: next.countMachineStock,
        order_default_lead_days: next.defaultLeadDays,
        order_default_interval_days: next.defaultIntervalDays,
      })
      .eq('id', companyId)
    if (error) {
      settings.value = previous
      throw error
    }
  }

  /** Preferred supplier, carton size and latest purchase price per product. */
  async function fetchProductInfo() {
    const productsRes = await (supabase as any)
      .from('products')
      .select('id, preferred_supplier_id, order_unit_size')
    if (productsRes.error) throw productsRes.error
    const ids = ((productsRes.data ?? []) as any[]).map(p => p.id as string)
    const summaryRes = ids.length
      ? await (supabase as any).rpc('get_product_purchase_summary', { p_product_ids: ids })
      : { data: [], error: null }
    // The summary names the newest supplier; names are unique per company.
    const byName = new Map(suppliers.value.map(s => [s.name.trim().toLowerCase(), s.id]))
    const summaries = new Map<string, any>()
    for (const r of (summaryRes.error ? [] : summaryRes.data ?? []) as any[]) summaries.set(r.product_id, r)

    const map = new Map<string, ProductOrderInfo>()
    for (const p of (productsRes.data ?? []) as any[]) {
      const s = summaries.get(p.id)
      map.set(p.id, {
        preferred_supplier_id: p.preferred_supplier_id ?? null,
        order_unit_size: p.order_unit_size ?? 1,
        latest_supplier_id: s?.newest_supplier ? byName.get(String(s.newest_supplier).trim().toLowerCase()) ?? null : null,
        latest_price_net: s?.newest_net == null ? null : Number(s.newest_net),
      })
    }
    productInfo.value = map
  }

  function supplierFor(productId: string): string | null {
    const info = productInfo.value.get(productId)
    return info?.preferred_supplier_id ?? info?.latest_supplier_id ?? null
  }

  async function updateProductOrdering(productId: string, updates: { preferred_supplier_id?: string | null; order_unit_size?: number }) {
    const { error } = await (supabase as any).from('products').update(updates).eq('id', productId)
    if (error) throw error
    const info = productInfo.value.get(productId)
    if (info) {
      const map = new Map(productInfo.value)
      map.set(productId, { ...info, ...updates })
      productInfo.value = map
    }
  }

  async function fetchManualEntries(warehouseId: string) {
    const { data, error } = await (supabase as any)
      .from('order_list_items')
      .select('product_id, added_by_name, added_via, created_at')
      .eq('warehouse_id', warehouseId)
    if (error) throw error
    manualEntries.value = (data ?? []) as ManualEntry[]
  }

  async function addToOrderList(warehouseId: string, productId: string) {
    const { error } = await (supabase as any).rpc('add_to_order_list', {
      p_warehouse_id: warehouseId, p_product_id: productId, p_via: 'web',
    })
    if (error) throw error
    await fetchManualEntries(warehouseId)
  }

  async function removeFromOrderList(warehouseId: string, productId: string) {
    const { error } = await (supabase as any).rpc('remove_from_order_list', {
      p_warehouse_id: warehouseId, p_product_id: productId,
    })
    if (error) throw error
    await fetchManualEntries(warehouseId)
  }

  async function fetchOpenOrders(warehouseId: string) {
    const { data, error } = await (supabase as any)
      .from('purchase_orders')
      .select('id, created_at, warehouse_id, supplier_id, supplier_name, status, note, ordered_by_name, purchase_order_lines(id, product_id, product_name, quantity, unit_size, unit_price_net, received_quantity, sort_order)')
      .eq('warehouse_id', warehouseId)
      .eq('status', 'ordered')
      .order('created_at')
    if (error) throw error
    openOrders.value = ((data ?? []) as any[]).map(o => ({
      ...o,
      lines: ((o.purchase_order_lines ?? []) as any[])
        .sort((a, b) => a.sort_order - b.sort_order)
        .map(l => ({ ...l, unit_price_net: l.unit_price_net == null ? null : Number(l.unit_price_net) })),
    }))
  }

  function onOrderFor(productId: string): number {
    let sum = 0
    for (const o of openOrders.value) for (const l of o.lines) if (l.product_id === productId) sum += l.quantity
    return sum
  }

  async function placeOrder(warehouseId: string, supplierId: string, lines: { product_id: string; quantity: number; unit_size: number; unit_price_net: number | null }[]) {
    const { data, error } = await (supabase as any).rpc('place_purchase_order', {
      p_warehouse_id: warehouseId, p_supplier_id: supplierId, p_lines: lines, p_note: null,
    })
    if (error) throw error
    await Promise.all([fetchOpenOrders(warehouseId), fetchManualEntries(warehouseId)])
    return data as string
  }

  async function cancelOrder(order: PurchaseOrder) {
    const { error } = await (supabase as any).rpc('cancel_purchase_order', { p_order_id: order.id })
    if (error) throw error
    await fetchOpenOrders(order.warehouse_id)
  }

  async function closeOrder(order: PurchaseOrder) {
    const { error } = await (supabase as any).rpc('close_purchase_order', { p_order_id: order.id })
    if (error) throw error
    await fetchOpenOrders(order.warehouse_id)
  }

  async function receiveOrder(order: PurchaseOrder, lines: ReceiveLineInput[]) {
    const { data, error } = await (supabase as any).rpc('receive_purchase_order', {
      p_order_id: order.id,
      p_lines: lines.map(l => ({
        line_id: l.line_id,
        quantity: l.quantity,
        expiration_date: l.expiration_date || null,
        batch_number: l.batch_number?.trim() || null,
      })),
    })
    if (error) throw error
    await fetchOpenOrders(order.warehouse_id)
    return data as { status: string; booked: number; units: number }
  }

  function subscribe(warehouseId: string, onChange: () => void): () => void {
    const channel = (supabase as any)
      .channel(`ordering-${warehouseId}`)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'order_list_items', filter: `warehouse_id=eq.${warehouseId}` }, () => {
        fetchManualEntries(warehouseId).then(onChange).catch(() => {})
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'purchase_orders', filter: `warehouse_id=eq.${warehouseId}` }, () => {
        fetchOpenOrders(warehouseId).then(onChange).catch(() => {})
      })
      .subscribe()
    return () => { (supabase as any).removeChannel(channel) }
  }

  return {
    suppliers, settings, manualEntries, openOrders, productInfo,
    fetchSuppliers, createSupplier, updateSupplier,
    fetchSettings, saveSettings,
    fetchProductInfo, supplierFor, updateProductOrdering,
    fetchManualEntries, addToOrderList, removeFromOrderList,
    fetchOpenOrders, onOrderFor, placeOrder, cancelOrder, closeOrder, receiveOrder,
    subscribe,
  }
}
