import { useSupabaseClient } from '#imports'
import { useOrganization } from './useOrganization'
import { getProductImageUrl } from './useProducts'

// ── Interfaces ──────────────────────────────────────────────────────────────

export interface Warehouse {
  id: string
  name: string
  address: string | null
  notes: string | null
}

export interface ProductBarcode {
  id: string
  product_id: string
  barcode: string
  format: string
  product_name?: string | null
}

export interface StockBatch {
  id: string
  warehouse_id: string
  product_id: string
  product_name: string | null
  product_image_path: string | null
  batch_number: string | null
  expiration_date: string
  quantity: number
}

export interface WarehouseTransaction {
  id: string
  created_at: string
  warehouse_id: string
  product_id: string
  product_name: string | null
  batch_id: string | null
  user_id: string | null
  transaction_type: string
  quantity_change: number
  quantity_before: number | null
  quantity_after: number | null
  batch_number: string | null
  expiration_date: string | null
  reference_id: string | null
  notes: string | null
  metadata: Record<string, unknown> | null
}

export interface WarehouseProductSummary {
  product_id: string
  product_name: string
  product_image_path: string | null
  total_quantity: number
  min_stock: number
  is_below_min: boolean
  earliest_expiration: string | null
  expiration_status: 'ok' | 'warning' | 'critical'
  batch_count: number
  discontinued: boolean
  avg_daily_sales: number
  estimated_days_remaining: number | null
}

export interface MinStockEntry {
  id: string
  product_id: string
  warehouse_id: string
  min_quantity: number
}

export interface WarehouseProductPosition {
  product_id: string
  product_name: string
  image_path: string | null
  sort_order: number
  location_label: string | null
  has_stock: boolean
  group_id: string | null
}

export interface WarehousePositionGroup {
  id: string
  parent_id: string | null
  name: string
  sort_order: number
  children: WarehousePositionGroup[]
  products: WarehouseProductPosition[]
}

// ── Helpers ─────────────────────────────────────────────────────────────────

export function expirationStatus(dateStr: string | null): 'ok' | 'warning' | 'critical' {
  if (!dateStr) return 'ok' // no expiration = always ok
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const exp = new Date(dateStr)
  const diffDays = Math.floor((exp.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
  if (diffDays < 7) return 'critical'
  if (diffDays <= 30) return 'warning'
  return 'ok'
}

export function expirationBadgeClass(status: 'ok' | 'warning' | 'critical'): string {
  switch (status) {
    case 'critical': return 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
    case 'warning': return 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-400'
    case 'ok': return 'bg-green-100 text-green-700 dark:bg-green-950/40 dark:text-green-400'
  }
}

export function expirationLabel(status: 'ok' | 'warning' | 'critical'): string {
  switch (status) {
    case 'critical': return 'Critical'
    case 'warning': return 'Expiring soon'
    case 'ok': return 'OK'
  }
}

const PAGE_SIZE = 50

// ── Composable ──────────────────────────────────────────────────────────────

export function useWarehouse() {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()

  const warehouses = ref<Warehouse[]>([])
  const batches = ref<StockBatch[]>([])
  const transactions = ref<WarehouseTransaction[]>([])
  const productSummaries = ref<WarehouseProductSummary[]>([])
  const barcodes = useState<ProductBarcode[]>('warehouse-barcodes', () => [])
  const minStocks = ref<MinStockEntry[]>([])
  const positions = ref<WarehouseProductPosition[]>([])
  const groups = ref<WarehousePositionGroup[]>([])
  const loading = ref(false)
  const transactionLoading = ref(false)

  // Velocity calculation lookback period (days) — stored per company in DB
  const velocityDays = useState<number>('warehouse-velocity-days', () => 30)
  const transactionHasMore = ref(false)
  const transactionOffset = ref(0)

  // ── Warehouse CRUD ──────────────────────────────────────────────────────

  async function fetchWarehouses() {
    const { data, error } = await (supabase as any)
      .from('warehouses')
      .select('id, name, address, notes')
      .order('name')
    if (error) throw error
    warehouses.value = (data ?? []) as Warehouse[]
  }

  async function createWarehouse(input: { name: string; address?: string; notes?: string }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const { data, error } = await (supabase as any)
      .from('warehouses')
      .insert({ ...input, company_id: companyId })
      .select('id')
      .single()
    if (error) throw error
    await fetchWarehouses()
    return (data as any).id as string
  }

  async function updateWarehouse(id: string, updates: { name?: string; address?: string; notes?: string }) {
    const { error } = await (supabase as any)
      .from('warehouses')
      .update(updates)
      .eq('id', id)
    if (error) throw error
    await fetchWarehouses()
  }

  async function deleteWarehouse(id: string) {
    const { error } = await (supabase as any)
      .from('warehouses')
      .delete()
      .eq('id', id)
    if (error) throw error
    await fetchWarehouses()
  }

  // ── Barcode management ──────────────────────────────────────────────────

  async function fetchBarcodes() {
    const { data, error } = await (supabase as any)
      .from('product_barcodes')
      .select('id, product_id, barcode, format, products(name)')
      .order('barcode')
    if (error) throw error
    barcodes.value = ((data ?? []) as any[]).map((b: any) => ({
      id: b.id,
      product_id: b.product_id,
      barcode: b.barcode,
      format: b.format,
      product_name: b.products?.name ?? null,
    }))
  }

  async function lookupBarcode(barcode: string): Promise<{ product_id: string; product_name: string } | null> {
    const { data, error } = await (supabase as any)
      .from('product_barcodes')
      .select('product_id, products(name)')
      .eq('barcode', barcode)
      .maybeSingle()
    if (error) throw error
    if (!data) return null
    return { product_id: (data as any).product_id, product_name: (data as any).products?.name ?? '' }
  }

  async function addBarcode(input: { product_id: string; barcode: string; format?: string }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const { error } = await (supabase as any)
      .from('product_barcodes')
      .insert({ ...input, format: input.format ?? 'EAN-13', company_id: companyId })
    if (error) throw error
    await fetchBarcodes()
  }

  async function removeBarcode(id: string) {
    const { error } = await (supabase as any)
      .from('product_barcodes')
      .delete()
      .eq('id', id)
    if (error) throw error
    await fetchBarcodes()
  }

  // ── Stock operations ────────────────────────────────────────────────────

  async function fetchBatches(warehouseId: string) {
    loading.value = true
    try {
      const { data, error } = await (supabase as any)
        .from('warehouse_stock_batches')
        .select('id, warehouse_id, product_id, batch_number, expiration_date, quantity, products(name, image_path)')
        .eq('warehouse_id', warehouseId)
        .gt('quantity', 0)
        .order('expiration_date')
      if (error) throw error
      batches.value = ((data ?? []) as any[]).map((b: any) => ({
        id: b.id,
        warehouse_id: b.warehouse_id,
        product_id: b.product_id,
        product_name: b.products?.name ?? null,
        product_image_path: b.products?.image_path ?? null,
        batch_number: b.batch_number,
        expiration_date: b.expiration_date,
        quantity: b.quantity,
      }))
    } finally {
      loading.value = false
    }
  }

  async function fetchProductSummaries(warehouseId: string) {
    loading.value = true
    try {
      const companyId = organization.value?.id
      if (!companyId) throw new Error('No organization')

      // Fetch all data in parallel: all products, stock batches, min stocks, sales velocity
      const [productsRes, batchRes, minStockRes, velocityRes] = await Promise.all([
        (supabase as any)
          .from('products')
          .select('id, name, image_path, discontinued, sellprice')
          .order('name'),
        (supabase as any)
          .from('warehouse_stock_batches')
          .select('product_id, quantity, expiration_date')
          .eq('warehouse_id', warehouseId)
          .gt('quantity', 0),
        (supabase as any)
          .from('product_min_stock')
          .select('product_id, min_quantity')
          .eq('warehouse_id', warehouseId),
        (supabase as any)
          .rpc('get_product_sales_velocity', { p_company_id: companyId, p_days: velocityDays.value }),
      ])

      if (productsRes.error) throw productsRes.error
      if (batchRes.error) throw batchRes.error
      if (minStockRes.error) throw minStockRes.error
      // Velocity RPC may fail if no sales exist — treat as empty
      const velocityData = velocityRes.error ? [] : (velocityRes.data ?? []) as any[]

      const minStockMap = new Map<string, number>()
      for (const ms of (minStockRes.data ?? []) as any[]) {
        minStockMap.set(ms.product_id, ms.min_quantity)
      }

      const velocityMap = new Map<string, number>()
      for (const v of velocityData) {
        velocityMap.set(v.product_id, parseFloat(v.avg_daily_units) || 0)
      }

      // Aggregate batches by product
      const stockMap = new Map<string, {
        total_quantity: number
        earliest_expiration: string | null
        batch_count: number
      }>()

      for (const b of (batchRes.data ?? []) as any[]) {
        const pid = b.product_id as string
        const existing = stockMap.get(pid)
        if (existing) {
          existing.total_quantity += b.quantity
          existing.batch_count += 1
          if (!existing.earliest_expiration || b.expiration_date < existing.earliest_expiration) {
            existing.earliest_expiration = b.expiration_date
          }
        } else {
          stockMap.set(pid, {
            total_quantity: b.quantity,
            earliest_expiration: b.expiration_date,
            batch_count: 1,
          })
        }
      }

      // Merge: every product gets a summary (even with 0 stock)
      productSummaries.value = ((productsRes.data ?? []) as any[]).map((p: any) => {
        const stock = stockMap.get(p.id)
        const totalQty = stock?.total_quantity ?? 0
        const minStock = minStockMap.get(p.id) ?? 0
        const avgDaily = velocityMap.get(p.id) ?? 0
        const expStatus = stock?.earliest_expiration ? expirationStatus(stock.earliest_expiration) : 'ok'
        const discontinued = p.discontinued ?? false

        return {
          product_id: p.id,
          product_name: p.name ?? 'Unknown',
          product_image_path: p.image_path ?? null,
          total_quantity: totalQty,
          min_stock: minStock,
          is_below_min: !discontinued && minStock > 0 && totalQty <= minStock,
          earliest_expiration: stock?.earliest_expiration ?? null,
          expiration_status: expStatus,
          batch_count: stock?.batch_count ?? 0,
          discontinued,
          avg_daily_sales: avgDaily,
          estimated_days_remaining: avgDaily > 0 ? Math.round(totalQty / avgDaily) : null,
        }
      }).sort((a, b) => a.product_name.localeCompare(b.product_name))
    } finally {
      loading.value = false
    }
  }

  async function bookIncoming(input: {
    warehouse_id: string
    product_id: string
    quantity: number
    expiration_date?: string | null
    batch_number?: string
  }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')

    // Check if batch already exists (same warehouse + product + batch_number + expiration_date)
    let query = (supabase as any)
      .from('warehouse_stock_batches')
      .select('id, quantity')
      .eq('warehouse_id', input.warehouse_id)
      .eq('product_id', input.product_id)
      .eq('batch_number', input.batch_number ?? '')

    if (input.expiration_date) {
      query = query.eq('expiration_date', input.expiration_date)
    } else {
      query = query.is('expiration_date', null)
    }

    const { data: existing } = await query.maybeSingle()

    let batchId: string
    let quantityBefore: number

    if (existing) {
      // Add to existing batch
      batchId = existing.id
      quantityBefore = existing.quantity
      const { error } = await (supabase as any)
        .from('warehouse_stock_batches')
        .update({ quantity: existing.quantity + input.quantity })
        .eq('id', existing.id)
      if (error) throw error
    } else {
      // Create new batch
      quantityBefore = 0
      const { data, error } = await (supabase as any)
        .from('warehouse_stock_batches')
        .insert({
          warehouse_id: input.warehouse_id,
          product_id: input.product_id,
          batch_number: input.batch_number || null,
          expiration_date: input.expiration_date || null,
          quantity: input.quantity,
          company_id: companyId,
        })
        .select('id')
        .single()
      if (error) throw error
      batchId = (data as any).id
    }

    // Log transaction
    const { data: { session } } = await supabase.auth.getSession()
    await (supabase as any).from('warehouse_transactions').insert({
      company_id: companyId,
      warehouse_id: input.warehouse_id,
      product_id: input.product_id,
      batch_id: batchId,
      user_id: session?.user?.id ?? null,
      transaction_type: 'incoming',
      quantity_change: input.quantity,
      quantity_before: quantityBefore,
      quantity_after: quantityBefore + input.quantity,
      batch_number: input.batch_number || null,
      expiration_date: input.expiration_date,
      metadata: {
        _user_email: session?.user?.email ?? null,
      },
    })
  }

  async function adjustStock(input: {
    batch_id: string
    warehouse_id: string
    product_id: string
    quantity_change: number
    reason: 'adjustment_damage' | 'adjustment_expired' | 'adjustment_correction' | 'adjustment_refill_return'
    notes?: string
  }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')

    // Get current batch quantity
    const { data: batch, error: fetchErr } = await (supabase as any)
      .from('warehouse_stock_batches')
      .select('quantity, batch_number, expiration_date')
      .eq('id', input.batch_id)
      .single()
    if (fetchErr) throw fetchErr

    const quantityBefore = (batch as any).quantity
    const quantityAfter = Math.max(0, quantityBefore + input.quantity_change)

    const { error } = await (supabase as any)
      .from('warehouse_stock_batches')
      .update({ quantity: quantityAfter })
      .eq('id', input.batch_id)
    if (error) throw error

    // Log transaction
    const { data: { session } } = await supabase.auth.getSession()
    await (supabase as any).from('warehouse_transactions').insert({
      company_id: companyId,
      warehouse_id: input.warehouse_id,
      product_id: input.product_id,
      batch_id: input.batch_id,
      user_id: session?.user?.id ?? null,
      transaction_type: input.reason,
      quantity_change: input.quantity_change,
      quantity_before: quantityBefore,
      quantity_after: quantityAfter,
      batch_number: (batch as any).batch_number,
      expiration_date: (batch as any).expiration_date,
      notes: input.notes ?? null,
      metadata: {
        _user_email: session?.user?.email ?? null,
      },
    })
  }

  async function deductForRefill(input: {
    warehouse_id: string
    product_id: string
    quantity: number
    machine_id: string
  }) {
    const { data: { session } } = await supabase.auth.getSession()
    const { data, error } = await (supabase as any).rpc('deduct_warehouse_stock_fifo', {
      p_warehouse_id: input.warehouse_id,
      p_product_id: input.product_id,
      p_quantity: input.quantity,
      p_user_id: session?.user?.id ?? null,
      p_reference_id: input.machine_id,
      p_notes: 'Machine refill',
      p_metadata: { _user_email: session?.user?.email ?? null },
    })
    if (error) throw error
    return data
  }

  /** Get total available stock for a product across a specific warehouse */
  async function getProductStock(warehouseId: string, productId: string): Promise<number> {
    const { data, error } = await (supabase as any)
      .from('warehouse_stock_batches')
      .select('quantity')
      .eq('warehouse_id', warehouseId)
      .eq('product_id', productId)
      .gt('quantity', 0)
    if (error) throw error
    return ((data ?? []) as any[]).reduce((sum: number, b: any) => sum + b.quantity, 0)
  }

  /** Get total available stock for all products in a warehouse as a Map<productId, quantity> */
  async function fetchWarehouseStockMap(warehouseId: string): Promise<Map<string, number>> {
    const { data, error } = await (supabase as any)
      .from('warehouse_stock_batches')
      .select('product_id, quantity')
      .eq('warehouse_id', warehouseId)
      .gt('quantity', 0)
    if (error) throw error
    const stockMap = new Map<string, number>()
    for (const b of (data ?? []) as any[]) {
      stockMap.set(b.product_id, (stockMap.get(b.product_id) ?? 0) + b.quantity)
    }
    return stockMap
  }

  // ── Min stock thresholds ────────────────────────────────────────────────

  async function fetchMinStocks(warehouseId: string) {
    const { data, error } = await (supabase as any)
      .from('product_min_stock')
      .select('id, product_id, warehouse_id, min_quantity')
      .eq('warehouse_id', warehouseId)
    if (error) throw error
    minStocks.value = (data ?? []) as MinStockEntry[]
  }

  async function setMinStock(input: { product_id: string; warehouse_id: string; min_quantity: number }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')

    const { error } = await (supabase as any)
      .from('product_min_stock')
      .upsert(
        { ...input, company_id: companyId },
        { onConflict: 'product_id,warehouse_id' }
      )
    if (error) throw error
    await fetchMinStocks(input.warehouse_id)
  }

  // ── Transaction history ─────────────────────────────────────────────────

  async function fetchTransactions(warehouseId: string, filters?: { product_id?: string; type?: string; dateFrom?: string; dateTo?: string }) {
    transactionLoading.value = true
    transactionOffset.value = 0
    try {
      let query = (supabase as any)
        .from('warehouse_transactions')
        .select('id, created_at, warehouse_id, product_id, batch_id, user_id, transaction_type, quantity_change, quantity_before, quantity_after, batch_number, expiration_date, reference_id, notes, metadata, products(name)')
        .eq('warehouse_id', warehouseId)
        .order('created_at', { ascending: false })
        .range(0, PAGE_SIZE - 1)

      if (filters?.product_id) query = query.eq('product_id', filters.product_id)
      if (filters?.type) query = query.eq('transaction_type', filters.type)
      // Interpret the picked dates in the viewer's local timezone (matching how
      // created_at is rendered), then send explicit UTC instants — a bare date
      // string is read as UTC by Postgres and shifts the window by the offset.
      if (filters?.dateFrom) query = query.gte('created_at', new Date(filters.dateFrom + 'T00:00:00').toISOString())
      if (filters?.dateTo) query = query.lte('created_at', new Date(filters.dateTo + 'T23:59:59.999').toISOString())

      const { data, error } = await query
      if (error) throw error

      transactions.value = ((data ?? []) as any[]).map(mapTransaction)
      transactionHasMore.value = (data ?? []).length >= PAGE_SIZE
      transactionOffset.value = (data ?? []).length
    } finally {
      transactionLoading.value = false
    }
  }

  async function fetchMoreTransactions(warehouseId: string, filters?: { product_id?: string; type?: string; dateFrom?: string; dateTo?: string }) {
    transactionLoading.value = true
    try {
      let query = (supabase as any)
        .from('warehouse_transactions')
        .select('id, created_at, warehouse_id, product_id, batch_id, user_id, transaction_type, quantity_change, quantity_before, quantity_after, batch_number, expiration_date, reference_id, notes, metadata, products(name)')
        .eq('warehouse_id', warehouseId)
        .order('created_at', { ascending: false })
        .range(transactionOffset.value, transactionOffset.value + PAGE_SIZE - 1)

      if (filters?.product_id) query = query.eq('product_id', filters.product_id)
      if (filters?.type) query = query.eq('transaction_type', filters.type)
      // Interpret the picked dates in the viewer's local timezone (matching how
      // created_at is rendered), then send explicit UTC instants — a bare date
      // string is read as UTC by Postgres and shifts the window by the offset.
      if (filters?.dateFrom) query = query.gte('created_at', new Date(filters.dateFrom + 'T00:00:00').toISOString())
      if (filters?.dateTo) query = query.lte('created_at', new Date(filters.dateTo + 'T23:59:59.999').toISOString())

      const { data, error } = await query
      if (error) throw error

      const mapped = ((data ?? []) as any[]).map(mapTransaction)
      transactions.value = [...transactions.value, ...mapped]
      transactionHasMore.value = (data ?? []).length >= PAGE_SIZE
      transactionOffset.value += (data ?? []).length
    } finally {
      transactionLoading.value = false
    }
  }

  function mapTransaction(t: any): WarehouseTransaction {
    return {
      id: t.id,
      created_at: t.created_at,
      warehouse_id: t.warehouse_id,
      product_id: t.product_id,
      product_name: t.products?.name ?? null,
      batch_id: t.batch_id,
      user_id: t.user_id,
      transaction_type: t.transaction_type,
      quantity_change: t.quantity_change,
      quantity_before: t.quantity_before,
      quantity_after: t.quantity_after,
      batch_number: t.batch_number,
      expiration_date: t.expiration_date,
      reference_id: t.reference_id,
      notes: t.notes,
      metadata: t.metadata,
    }
  }

  // ── Realtime ────────────────────────────────────────────────────────────

  function subscribeToStockUpdates(warehouseId: string) {
    const channel = supabase
      .channel(`warehouse-stock-${warehouseId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'warehouse_stock_batches', filter: `warehouse_id=eq.${warehouseId}` },
        () => {
          fetchBatches(warehouseId)
          fetchProductSummaries(warehouseId)
        }
      )
      .subscribe()

    return () => supabase.removeChannel(channel)
  }

  // ── Transaction type helpers ────────────────────────────────────────────

  function transactionTypeLabel(type: string): string {
    switch (type) {
      case 'incoming': return 'Incoming'
      case 'outgoing_refill': return 'Refill'
      case 'adjustment_damage': return 'Damaged'
      case 'adjustment_expired': return 'Expired'
      case 'adjustment_correction': return 'Correction'
      case 'adjustment_refill_return': return 'Refill return'
      case 'transfer_out': return 'Transfer out'
      case 'transfer_in': return 'Transfer in'
      default: return type
    }
  }

  function transactionTypeBadgeClass(type: string): string {
    switch (type) {
      case 'incoming': return 'bg-green-100 text-green-700 dark:bg-green-950/40 dark:text-green-400'
      case 'outgoing_refill': return 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-400'
      case 'adjustment_damage': return 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
      case 'adjustment_expired': return 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
      case 'adjustment_correction': return 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-400'
      case 'adjustment_refill_return': return 'bg-green-100 text-green-700 dark:bg-green-950/40 dark:text-green-400'
      case 'transfer_out': return 'bg-purple-100 text-purple-700 dark:bg-purple-950/40 dark:text-purple-400'
      case 'transfer_in': return 'bg-purple-100 text-purple-700 dark:bg-purple-950/40 dark:text-purple-400'
      default: return 'bg-muted text-muted-foreground'
    }
  }

  // ── Position groups ──────────────────────────────────────────────────

  async function fetchGroups(warehouseId: string) {
    const { data, error } = await (supabase as any)
      .from('warehouse_position_groups')
      .select('id, parent_id, name, sort_order')
      .eq('warehouse_id', warehouseId)
      .order('sort_order')
    if (error) throw error

    // Build tree from flat list
    const flat = (data ?? []) as { id: string; parent_id: string | null; name: string; sort_order: number }[]
    const nodeMap = new Map<string, WarehousePositionGroup>()
    for (const g of flat) {
      nodeMap.set(g.id, { id: g.id, parent_id: g.parent_id, name: g.name, sort_order: g.sort_order, children: [], products: [] })
    }
    const roots: WarehousePositionGroup[] = []
    for (const node of nodeMap.values()) {
      if (node.parent_id && nodeMap.has(node.parent_id)) {
        nodeMap.get(node.parent_id)!.children.push(node)
      } else {
        roots.push(node)
      }
    }
    // Sort children at each level
    const sortChildren = (nodes: WarehousePositionGroup[]) => {
      nodes.sort((a, b) => a.sort_order - b.sort_order)
      for (const n of nodes) sortChildren(n.children)
    }
    sortChildren(roots)
    groups.value = roots
  }

  async function createGroup(warehouseId: string, input: { name: string; parent_id?: string | null; sort_order?: number }) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const { data, error } = await (supabase as any)
      .from('warehouse_position_groups')
      .insert({
        warehouse_id: warehouseId,
        company_id: companyId,
        name: input.name,
        parent_id: input.parent_id ?? null,
        sort_order: input.sort_order ?? 0,
      })
      .select('id')
      .single()
    if (error) throw error
    return (data as any).id as string
  }

  async function updateGroup(groupId: string, updates: { name?: string; sort_order?: number; parent_id?: string | null }) {
    const { error } = await (supabase as any)
      .from('warehouse_position_groups')
      .update(updates)
      .eq('id', groupId)
    if (error) throw error
  }

  async function deleteGroup(groupId: string) {
    const { error } = await (supabase as any)
      .from('warehouse_position_groups')
      .delete()
      .eq('id', groupId)
    if (error) throw error
  }

  async function saveGroupOrder(warehouseId: string, items: { id: string; sort_order: number; parent_id: string | null }[]) {
    const updates = items.map((item) =>
      (supabase as any)
        .from('warehouse_position_groups')
        .update({ sort_order: item.sort_order, parent_id: item.parent_id })
        .eq('id', item.id)
    )
    const results = await Promise.all(updates)
    const firstError = results.find((r: any) => r.error)
    if (firstError) throw (firstError as any).error
  }

  // ── Product positions ─────────────────────────────────────────────────

  /**
   * Fetch product positions for a warehouse, merged with stocked products.
   * Positioned products come first (by sort_order), unpositioned last (alphabetically).
   */
  async function fetchPositions(warehouseId: string) {
    // Fetch positions, groups, and stocked products in parallel
    const [posRes, stockRes] = await Promise.all([
      (supabase as any)
        .from('warehouse_product_positions')
        .select('product_id, sort_order, location_label, group_id, products(name, image_path)')
        .eq('warehouse_id', warehouseId)
        .order('sort_order'),
      (supabase as any)
        .from('warehouse_stock_batches')
        .select('product_id, quantity, products(name, image_path)')
        .eq('warehouse_id', warehouseId)
        .gt('quantity', 0),
    ])

    if (posRes.error) throw posRes.error
    if (stockRes.error) throw stockRes.error

    // Build stock map
    const stockMap = new Map<string, { name: string; image_path: string | null }>()
    for (const b of (stockRes.data ?? []) as any[]) {
      if (!stockMap.has(b.product_id)) {
        stockMap.set(b.product_id, {
          name: b.products?.name ?? 'Unknown',
          image_path: b.products?.image_path ?? null,
        })
      }
    }

    // Positioned products
    const positionedIds = new Set<string>()
    const positioned: WarehouseProductPosition[] = ((posRes.data ?? []) as any[]).map((p: any) => {
      positionedIds.add(p.product_id)
      const stock = stockMap.get(p.product_id)
      return {
        product_id: p.product_id,
        product_name: stock?.name ?? p.products?.name ?? 'Unknown',
        image_path: stock?.image_path ?? p.products?.image_path ?? null,
        sort_order: p.sort_order,
        location_label: p.location_label,
        has_stock: stockMap.has(p.product_id),
        group_id: p.group_id ?? null,
      }
    })

    // Unpositioned products (have stock but no position entry)
    const unpositioned: WarehouseProductPosition[] = []
    for (const [productId, info] of stockMap) {
      if (!positionedIds.has(productId)) {
        unpositioned.push({
          product_id: productId,
          product_name: info.name,
          image_path: info.image_path,
          sort_order: -1,
          location_label: null,
          has_stock: true,
          group_id: null,
        })
      }
    }
    unpositioned.sort((a, b) => a.product_name.localeCompare(b.product_name))

    positions.value = [...positioned, ...unpositioned]

    // Populate group.products from positions
    const groupMap = new Map<string, WarehousePositionGroup>()
    const collectGroups = (nodes: WarehousePositionGroup[]) => {
      for (const n of nodes) {
        n.products = []
        groupMap.set(n.id, n)
        collectGroups(n.children)
      }
    }
    collectGroups(groups.value)
    for (const pos of positioned) {
      if (pos.group_id && groupMap.has(pos.group_id)) {
        groupMap.get(pos.group_id)!.products.push(pos)
      }
    }
  }

  /**
   * Save (upsert) product positions for a warehouse.
   */
  async function savePositions(
    warehouseId: string,
    items: { product_id: string; sort_order: number; location_label?: string | null; group_id?: string | null }[],
  ) {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')

    // Deduplicate by product_id (keep last occurrence — the most recent sort_order)
    const seen = new Map<string, typeof items[0]>()
    for (const item of items) {
      seen.set(item.product_id, item)
    }
    const rows = Array.from(seen.values()).map((item) => ({
      warehouse_id: warehouseId,
      product_id: item.product_id,
      sort_order: item.sort_order,
      location_label: item.location_label ?? null,
      group_id: item.group_id ?? null,
      company_id: companyId,
      updated_at: new Date().toISOString(),
    }))

    const { error } = await (supabase as any)
      .from('warehouse_product_positions')
      .upsert(rows, { onConflict: 'warehouse_id,product_id' })

    if (error) throw error
  }

  /** Remove a product's position entry from a warehouse */
  async function removePosition(warehouseId: string, productId: string) {
    const { error } = await (supabase as any)
      .from('warehouse_product_positions')
      .delete()
      .eq('warehouse_id', warehouseId)
      .eq('product_id', productId)
    if (error) throw error
  }

  /**
   * Returns product_id[] in depth-first warehouse position order.
   * Traverses groups tree, then appends ungrouped positioned products, then unpositioned.
   */
  async function fetchOrderedProductIds(warehouseId: string): Promise<string[]> {
    // Fetch groups and positions in parallel
    const [grpRes, posRes] = await Promise.all([
      (supabase as any)
        .from('warehouse_position_groups')
        .select('id, parent_id, sort_order')
        .eq('warehouse_id', warehouseId)
        .order('sort_order'),
      (supabase as any)
        .from('warehouse_product_positions')
        .select('product_id, sort_order, group_id')
        .eq('warehouse_id', warehouseId)
        .order('sort_order'),
    ])
    if (grpRes.error) throw grpRes.error
    if (posRes.error) throw posRes.error

    const grpFlat = (grpRes.data ?? []) as { id: string; parent_id: string | null; sort_order: number }[]
    const posFlat = (posRes.data ?? []) as { product_id: string; sort_order: number; group_id: string | null }[]

    // Build group tree
    const nodeMap = new Map<string, { id: string; parent_id: string | null; sort_order: number; children: any[]; productIds: string[] }>()
    for (const g of grpFlat) {
      nodeMap.set(g.id, { ...g, children: [], productIds: [] })
    }
    const roots: typeof nodeMap extends Map<string, infer V> ? V[] : never[] = []
    for (const node of nodeMap.values()) {
      if (node.parent_id && nodeMap.has(node.parent_id)) {
        nodeMap.get(node.parent_id)!.children.push(node)
      } else {
        (roots as any[]).push(node)
      }
    }
    const sortNodes = (nodes: any[]) => {
      nodes.sort((a: any, b: any) => a.sort_order - b.sort_order)
      for (const n of nodes) sortNodes(n.children)
    }
    sortNodes(roots as any[])

    // Assign products to groups
    const ungrouped: string[] = []
    for (const p of posFlat) {
      if (p.group_id && nodeMap.has(p.group_id)) {
        nodeMap.get(p.group_id)!.productIds.push(p.product_id)
      } else {
        ungrouped.push(p.product_id)
      }
    }

    // Depth-first traversal
    const result: string[] = []
    const traverse = (nodes: any[]) => {
      for (const node of nodes) {
        result.push(...node.productIds)
        traverse(node.children)
      }
    }
    traverse(roots as any[])
    result.push(...ungrouped)

    return result
  }

  /**
   * Trigger processing of queued low-stock notifications.
   * Calls the check-low-stock edge function which reads unsent entries
   * from low_stock_notifications and sends push notifications.
   */
  async function checkLowStockNotifications() {
    try {
      const { data: { session } } = await supabase.auth.getSession()
      if (!session) return
      const config = useRuntimeConfig()
      const supabaseUrl = config.public.supabase?.url || 'http://127.0.0.1:54321'
      await fetch(`${supabaseUrl}/functions/v1/check-low-stock`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${session.access_token}`,
          'Content-Type': 'application/json',
        },
      })
    } catch {
      // Silently ignore — notifications are best-effort
    }
  }

  async function fetchVelocityDays() {
    const companyId = organization.value?.id
    if (!companyId) return
    const { data } = await (supabase as any)
      .from('companies')
      .select('velocity_days')
      .eq('id', companyId)
      .maybeSingle()
    if (data?.velocity_days) {
      velocityDays.value = data.velocity_days
    }
  }

  async function setVelocityDays(days: number) {
    const companyId = organization.value?.id
    if (!companyId) return
    velocityDays.value = days
    await (supabase as any)
      .from('companies')
      .update({ velocity_days: days })
      .eq('id', companyId)
  }

  return {
    warehouses, batches, transactions, productSummaries, barcodes, minStocks, positions, groups,
    loading, transactionLoading, transactionHasMore, velocityDays,
    fetchWarehouses, createWarehouse, updateWarehouse, deleteWarehouse,
    fetchBarcodes, lookupBarcode, addBarcode, removeBarcode,
    fetchBatches, fetchProductSummaries, bookIncoming, adjustStock, deductForRefill, getProductStock, fetchWarehouseStockMap,
    fetchMinStocks, setMinStock, fetchVelocityDays, setVelocityDays,
    fetchGroups, createGroup, updateGroup, deleteGroup, saveGroupOrder,
    fetchPositions, savePositions, removePosition, fetchOrderedProductIds,
    fetchTransactions, fetchMoreTransactions,
    subscribeToStockUpdates, checkLowStockNotifications,
    transactionTypeLabel, transactionTypeBadgeClass,
  }
}
