import { useSupabaseClient } from '#imports'
import type { Ref } from 'vue'
import { getProductImageUrl } from './useProducts'

function localDayKey(d: Date): string {
  // Returns YYYY-MM-DD in the user's local timezone (not UTC)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export interface ProductDetail {
  id: string
  name: string
  sellprice: number | null
  description: string | null
  category: string | null
  category_name: string | null
  image_path: string | null
  image_url: string | null
  discontinued: boolean
}

export interface ProductDetailBarcode {
  id: string
  barcode: string
  format: string
}

export interface ProductKpis {
  warehouse_total_qty: number
  warehouse_count: number
  tray_total_stock: number
  tray_total_capacity: number
  machine_count: number
  sales_today_units: number
  sales_today_revenue: number
  sales_7d_units: number
  sales_7d_revenue: number
  velocity_units_per_day: number
  velocity_window_days: number
  top_machines: Array<{ machine_id: string; machine_name: string; units: number; revenue: number }>
}

export interface WarehouseStockEntry {
  warehouse_id: string
  warehouse_name: string
  total_qty: number
  min_quantity: number | null
  batches: Array<{
    id: string
    batch_number: string | null
    expiration_date: string | null
    quantity: number
    created_at: string
  }>
}

export interface MachineTrayEntry {
  id: string
  machine_id: string
  machine_name: string
  item_number: number
  current_stock: number
  capacity: number
  fill_when_below: number | null
  last_sale_at: string | null
}

export interface RecentSaleEntry {
  id: number
  created_at: string
  item_price: number | null
  channel: string | null
  machine_id: string | null
  machine_name: string | null
}

export interface TransactionEntry {
  id: string
  created_at: string
  transaction_type: string
  quantity_change: number
  quantity_after: number | null
  warehouse_id: string
  warehouse_name: string
  user_id: string | null
  user_display: string
  notes: string | null
}

export interface SalesByDay { date: Date; total: number; count?: number }

export function useProductDetail(productId: Ref<string>) {
  const supabase = useSupabaseClient()

  const product = ref<ProductDetail | null>(null)
  const barcodes = ref<ProductDetailBarcode[]>([])
  const kpis = ref<ProductKpis | null>(null)
  const warehouseStock = ref<WarehouseStockEntry[]>([])
  const machineTrays = ref<MachineTrayEntry[]>([])
  const recentSales = ref<RecentSaleEntry[]>([])
  const transactions = ref<TransactionEntry[]>([])
  const chartRevenue = ref<SalesByDay[]>([])
  const chartUnits = ref<SalesByDay[]>([])

  const loading = ref(false)
  const notFound = ref(false)
  const error = ref<string | null>(null)

  async function refresh() {
    if (!productId.value) return
    loading.value = true
    notFound.value = false
    error.value = null

    try {
      const [
        productRes,
        barcodesRes,
        kpisRes,
        batchesRes,
        minStockRes,
        traysRes,
        salesRes,
        transactionsRes,
      ] = await Promise.all([
        supabase
          .from('products')
          .select('id, name, sellprice, description, category, image_path, discontinued, product_category(name)')
          .eq('id', productId.value)
          .maybeSingle(),
        supabase
          .from('product_barcodes')
          .select('id, barcode, format')
          .eq('product_id', productId.value)
          .order('created_at'),
        (supabase as any).rpc('get_product_detail_kpis', {
          p_product_id: productId.value,
          p_days: 30,
        }),
        supabase
          .from('warehouse_stock_batches')
          .select('id, warehouse_id, batch_number, expiration_date, quantity, created_at, warehouses(name)')
          .eq('product_id', productId.value)
          .gt('quantity', 0)
          .order('expiration_date', { ascending: true, nullsFirst: false }),
        supabase
          .from('product_min_stock')
          .select('warehouse_id, min_quantity')
          .eq('product_id', productId.value),
        supabase
          .from('machine_trays')
          .select('id, machine_id, item_number, current_stock, capacity, fill_when_below, vendingMachine(name)')
          .eq('product_id', productId.value),
        supabase
          .from('sales')
          .select('id, created_at, item_price, channel, machine_id, vendingMachine(name)')
          .eq('product_id', productId.value)
          .order('created_at', { ascending: false })
          .limit(50),
        supabase
          .from('warehouse_transactions')
          .select('id, created_at, transaction_type, quantity_change, quantity_after, warehouse_id, user_id, notes, warehouses(name)')
          .eq('product_id', productId.value)
          .order('created_at', { ascending: false })
          .limit(50),
      ])

      // Product itself (triggers not-found early)
      if (productRes.error) throw productRes.error
      if (!productRes.data) {
        notFound.value = true
        return
      }
      const p: any = productRes.data
      product.value = {
        id: p.id,
        name: p.name,
        sellprice: p.sellprice,
        description: p.description,
        category: p.category,
        category_name: p.product_category?.name ?? null,
        image_path: p.image_path,
        image_url: p.image_path ? getProductImageUrl(p.image_path) : null,
        discontinued: p.discontinued ?? false,
      }

      if (barcodesRes.error) throw barcodesRes.error
      barcodes.value = (barcodesRes.data ?? []) as ProductDetailBarcode[]

      if (kpisRes.error) throw kpisRes.error
      kpis.value = kpisRes.data as ProductKpis

      // Warehouse stock: group batches by warehouse, join min_quantity
      if (batchesRes.error) throw batchesRes.error
      if (minStockRes.error) throw minStockRes.error
      const minByWarehouse = new Map<string, number>()
      for (const row of (minStockRes.data ?? []) as Array<{ warehouse_id: string; min_quantity: number }>) {
        minByWarehouse.set(row.warehouse_id, row.min_quantity)
      }
      const byWarehouse = new Map<string, WarehouseStockEntry>()
      for (const row of (batchesRes.data ?? []) as any[]) {
        const wid = row.warehouse_id
        if (!byWarehouse.has(wid)) {
          byWarehouse.set(wid, {
            warehouse_id: wid,
            warehouse_name: row.warehouses?.name ?? '—',
            total_qty: 0,
            min_quantity: minByWarehouse.get(wid) ?? null,
            batches: [],
          })
        }
        const w = byWarehouse.get(wid)!
        w.total_qty += row.quantity
        w.batches.push({
          id: row.id,
          batch_number: row.batch_number,
          expiration_date: row.expiration_date,
          quantity: row.quantity,
          created_at: row.created_at,
        })
      }
      warehouseStock.value = [...byWarehouse.values()]

      if (traysRes.error) throw traysRes.error
      machineTrays.value = ((traysRes.data ?? []) as any[]).map((t) => ({
        id: t.id,
        machine_id: t.machine_id,
        machine_name: t.vendingMachine?.name ?? '—',
        item_number: t.item_number,
        current_stock: t.current_stock,
        capacity: t.capacity,
        fill_when_below: t.fill_when_below ?? null,
        last_sale_at: null,
      }))

      if (salesRes.error) throw salesRes.error
      const sales = ((salesRes.data ?? []) as any[])
      recentSales.value = sales.map((s) => ({
        id: s.id,
        created_at: s.created_at,
        item_price: s.item_price,
        channel: s.channel,
        machine_id: s.machine_id,
        machine_name: s.vendingMachine?.name ?? null,
      }))

      // Fill last_sale_at per tray by scanning the recent-sales window
      const lastSaleByMachine = new Map<string, string>()
      for (const s of sales) {
        if (s.machine_id && !lastSaleByMachine.has(s.machine_id)) {
          lastSaleByMachine.set(s.machine_id, s.created_at)
        }
      }
      for (const t of machineTrays.value) {
        t.last_sale_at = lastSaleByMachine.get(t.machine_id) ?? null
      }

      // Transactions + separate user lookup (FK points at auth.users, can't embed public.users)
      if (transactionsRes.error) throw transactionsRes.error
      const txRows = (transactionsRes.data ?? []) as any[]
      const userIds = [...new Set(txRows.map((r) => r.user_id).filter(Boolean))]
      const users = new Map<string, string>()
      if (userIds.length > 0) {
        const { data: userRows, error: usersErr } = await supabase
          .from('users')
          .select('id, first_name, last_name, email')
          .in('id', userIds)
        if (usersErr) throw usersErr
        for (const u of (userRows ?? []) as any[]) {
          const name = [u.first_name, u.last_name].filter(Boolean).join(' ').trim()
          users.set(u.id, name || u.email || '—')
        }
      }
      transactions.value = txRows.map((r) => ({
        id: r.id,
        created_at: r.created_at,
        transaction_type: r.transaction_type,
        quantity_change: r.quantity_change,
        quantity_after: r.quantity_after,
        warehouse_id: r.warehouse_id,
        warehouse_name: r.warehouses?.name ?? '—',
        user_id: r.user_id,
        user_display: r.user_id ? (users.get(r.user_id) ?? '—') : '—',
        notes: r.notes,
      }))

      // Chart: bucket the last 30 days of sales into per-day revenue + units
      const now = new Date()
      const buckets = new Map<string, { revenue: number; units: number }>()
      for (let i = 29; i >= 0; i--) {
        const d = new Date(now)
        d.setHours(0, 0, 0, 0)
        d.setDate(d.getDate() - i)
        buckets.set(localDayKey(d), { revenue: 0, units: 0 })
      }
      // Use a wider fetch than recentSales (which is LIMIT 50) — separate query
      const since = new Date()
      since.setDate(since.getDate() - 30)
      since.setHours(0, 0, 0, 0)
      const { data: chartRows, error: chartErr } = await supabase
        .from('sales')
        .select('created_at, item_price')
        .eq('product_id', productId.value)
        .gte('created_at', since.toISOString())
      if (chartErr) throw chartErr
      for (const row of (chartRows ?? []) as Array<{ created_at: string; item_price: number | null }>) {
        const key = localDayKey(new Date(row.created_at))
        const b = buckets.get(key)
        if (!b) continue
        b.units += 1
        b.revenue += row.item_price ?? 0
      }
      const parseLocalDayKey = (k: string): Date => {
        const parts = k.split('-')
        const y = Number(parts[0])
        const m = Number(parts[1])
        const day = Number(parts[2])
        return new Date(y, m - 1, day)
      }
      chartRevenue.value = [...buckets.entries()].map(([k, v]) => ({ date: parseLocalDayKey(k), total: v.revenue, count: v.units }))
      chartUnits.value = [...buckets.entries()].map(([k, v]) => ({ date: parseLocalDayKey(k), total: v.units }))
    } catch (e: any) {
      error.value = e?.message ?? 'failed to load product detail'
      // intentional: we set `error` for reactive consumers; do not re-throw
    } finally {
      loading.value = false
    }
  }

  return {
    product,
    barcodes,
    kpis,
    warehouseStock,
    machineTrays,
    recentSales,
    transactions,
    chartRevenue,
    chartUnits,
    loading,
    notFound,
    error,
    refresh,
  }
}
