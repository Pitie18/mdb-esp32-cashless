<script lang="ts">
// Module-scoped: not serialized by Nuxt SSR
let _Sortable: typeof import('sortablejs').default | null = null
</script>

<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  IconPlus, IconBarcode, IconAdjustments, IconTrash,
  IconPackageImport, IconChevronDown, IconChevronRight,
  IconAlertTriangle, IconSearch, IconX,
  IconGripVertical,
  IconFolder, IconFolderPlus, IconChevronDown as IconChevronDownSmall, IconChevronRight as IconChevronRightSmall,
  IconEdit, IconSquareCheck, IconSquare,
  IconArrowUp, IconArrowDown, IconArrowsSort,
} from '@tabler/icons-vue'
import { timeAgo, formatCurrency, formatDate } from '@/lib/utils'
import { getProductImageUrl } from '@/composables/useProducts'
import {
  expirationStatus, expirationBadgeClass, expirationLabel,
  type Warehouse, type StockBatch, type WarehouseProductSummary,
  type WarehouseProductPosition, type WarehousePositionGroup,
} from '@/composables/useWarehouse'

const { t, locale } = useI18n()
const { organization, role } = useOrganization()
const { products, categories, fetchProducts, createProduct, uploadProductImage, toggleDiscontinued } = useProducts()
const {
  warehouses, batches, transactions, productSummaries, barcodes, minStocks,
  loading, transactionLoading, transactionHasMore,
  fetchWarehouses, createWarehouse, updateWarehouse, deleteWarehouse,
  fetchBarcodes, lookupBarcode, addBarcode, removeBarcode,
  fetchBatches, fetchProductSummaries, bookIncoming, adjustStock,
  fetchMinStocks, setMinStock, fetchVelocityDays, setVelocityDays, velocityDays,
  positions, groups, fetchPositions, savePositions, removePosition,
  fetchGroups, createGroup, updateGroup, deleteGroup, saveGroupOrder,
  fetchTransactions, fetchMoreTransactions,
  subscribeToStockUpdates,
  transactionTypeLabel, transactionTypeBadgeClass,
} = useWarehouse()

const isAdmin = computed(() => role.value === 'admin')

const route = useRoute()
const activeTab = ref((route.query.tab as string) || 'overview')
const selectedWarehouseId = ref<string | null>(null)

const selectedWarehouse = computed(() =>
  warehouses.value.find(w => w.id === selectedWarehouseId.value) ?? null
)

const pullRefreshHandler = async () => {
  await Promise.all([fetchWarehouses(), fetchProducts(), fetchMachineStockValue()])
  if (selectedWarehouseId.value) await loadWarehouseData()
}
usePullToRefresh(pullRefreshHandler)

// Disable pull-to-refresh on positions tab (conflicts with touch drag-and-drop)
if (import.meta.client) {
  const pullRefreshState = useState<(() => Promise<void> | void) | null>('pull-refresh-handler')
  watch(activeTab, (tab) => {
    pullRefreshState.value = tab === 'positions' ? null : pullRefreshHandler
  }, { immediate: true })
}

let unsubscribe: (() => void) | null = null

onMounted(async () => {
  // Load sortablejs client-side only
  const mod = await import('sortablejs')
  _Sortable = mod.default

  await Promise.all([fetchWarehouses(), fetchProducts(), fetchMachineStockValue(), fetchVelocityDays()])
  const first = warehouses.value[0]
  if (first) {
    selectedWarehouseId.value = first.id
    await loadWarehouseData()
  }
})

onUnmounted(() => {
  unsubscribe?.()
})

watch(selectedWarehouseId, async (id) => {
  unsubscribe?.()
  if (id) {
    await loadWarehouseData()
    unsubscribe = subscribeToStockUpdates(id)
  }
})

async function loadWarehouseData() {
  const id = selectedWarehouseId.value
  if (!id) return
  await Promise.all([
    fetchProductSummaries(id),
    fetchBatches(id),
    fetchMinStocks(id),
  ])
}

// ── KPIs ────────────────────────────────────────────────────────────────────

const showDiscontinued = ref(true)
const showUnassigned = ref(true)
/**
 * When false (the default), zero-stock products are hidden **unless the product
 * still occupies a machine slot** — there a zero warehouse stock is the
 * actionable signal (the slot needs refilling and there is nothing to refill it
 * with). Zero-stock products in no machine are catalogue noise. Mirrors the iOS
 * warehouse tab.
 */
const showAllOutOfStock = ref(false)

const activeProductSummaries = computed(() => {
  let items = productSummaries.value
  if (!showDiscontinued.value) items = items.filter(p => !p.discontinued)
  if (!showUnassigned.value) items = items.filter(p => assignedProductIds.value.has(p.product_id))
  if (!showAllOutOfStock.value) {
    items = items.filter(p => p.total_quantity > 0 || assignedProductIds.value.has(p.product_id))
  }
  return items
})

const totalProducts = computed(() => productSummaries.value.filter(p => !p.discontinued).length)
const totalUnits = computed(() => productSummaries.value.filter(p => !p.discontinued).reduce((s, p) => s + p.total_quantity, 0))
const expiringSoonCount = computed(() => productSummaries.value.filter(p => !p.discontinued && p.expiration_status !== 'ok').length)
const belowMinCount = computed(() => productSummaries.value.filter(p => p.is_below_min).length)
const outOfStockCount = computed(() => productSummaries.value.filter(p => !p.discontinued && p.total_quantity === 0).length)
const totalValue = computed(() => {
  const productMap = new Map(products.value.map(p => [p.id, p.sellprice]))
  return productSummaries.value.reduce((sum, ps) => {
    const price = productMap.get(ps.product_id) ?? 0
    return sum + ps.total_quantity * (price ?? 0)
  }, 0)
})

// ── Machine stock value ─────────────────────────────────────────────────────
const machineTrays = ref<{ product_id: string; current_stock: number; sellprice: number }[]>([])

async function fetchMachineStockValue() {
  const supabase = useSupabaseClient()
  const { data } = await supabase
    .from('machine_trays')
    .select('product_id, current_stock, products(sellprice)')
    .not('product_id', 'is', null)
  if (data) {
    machineTrays.value = (data as any[]).map(t => ({
      product_id: t.product_id,
      current_stock: t.current_stock ?? 0,
      sellprice: t.products?.sellprice ?? 0,
    }))
  }
}

const machineStockValue = computed(() =>
  machineTrays.value.reduce((sum, t) => sum + t.current_stock * t.sellprice, 0)
)

const assignedProductIds = computed(() => new Set(machineTrays.value.map(t => t.product_id)))

function isAssignedToMachine(productId: string): boolean {
  return assignedProductIds.value.has(productId)
}

// ── Stock tab state ─────────────────────────────────────────────────────────

const stockSearch = ref('')
const stockFilter = ref<'all' | 'warning' | 'critical'>('all')
const stockSortKey = ref<'name' | 'quantity' | 'min_stock' | 'range' | 'expiration' | 'status'>('name')
const stockSortDir = ref<'asc' | 'desc'>('asc')
const expandedProducts = ref(new Set<string>())

function toggleSort(key: 'name' | 'quantity' | 'min_stock' | 'range' | 'expiration' | 'status') {
  if (stockSortKey.value === key) {
    stockSortDir.value = stockSortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    stockSortKey.value = key
    stockSortDir.value = 'asc'
  }
}

function toggleExpand(productId: string) {
  if (expandedProducts.value.has(productId)) {
    expandedProducts.value.delete(productId)
  } else {
    expandedProducts.value.add(productId)
  }
}

import { fuzzyFilter } from '@/lib/fuzzySearch'

const filteredSummaries = computed(() => {
  let items = activeProductSummaries.value
  if (stockSearch.value) {
    items = fuzzyFilter(items, stockSearch.value, [p => p.product_name])
  }
  if (stockFilter.value === 'warning') {
    items = items.filter(p => p.expiration_status === 'warning' || p.expiration_status === 'critical')
  } else if (stockFilter.value === 'critical') {
    items = items.filter(p => p.expiration_status === 'critical')
  }
  return sortSummaries(items)
})

/**
 * Compute a numeric severity score for a product summary.
 * Lower = more urgent. Used for status column sorting.
 *
 *  0   — out of stock (active product, quantity 0)
 *  1   — below min stock
 *  2   — MHD critical (< 7 days)
 *  3   — MHD warning (7–30 days)
 *  4   — OK (everything fine)
 *  4.5 — not assigned to any machine (active but irrelevant)
 *  5   — discontinued (always last)
 */
function statusSeverity(p: WarehouseProductSummary): number {
  if (p.discontinued) return 5
  if (!p.discontinued && p.total_quantity === 0) return 0
  if (p.is_below_min) return 1
  if (p.expiration_status === 'critical') return 2
  if (p.expiration_status === 'warning') return 3
  if (!isAssignedToMachine(p.product_id)) return 4.5
  return 4
}

function sortSummaries(items: WarehouseProductSummary[]) {
  const dir = stockSortDir.value === 'asc' ? 1 : -1
  return [...items].sort((a, b) => {
    if (stockSortKey.value === 'name') {
      return dir * a.product_name.localeCompare(b.product_name)
    }
    if (stockSortKey.value === 'quantity') {
      return dir * (a.total_quantity - b.total_quantity)
    }
    if (stockSortKey.value === 'min_stock') {
      return dir * (a.min_stock - b.min_stock)
    }
    if (stockSortKey.value === 'range') {
      const aRange = a.estimated_days_remaining ?? Infinity
      const bRange = b.estimated_days_remaining ?? Infinity
      if (aRange === bRange) return 0
      return dir * (aRange - bRange)
    }
    if (stockSortKey.value === 'expiration') {
      const aDate = a.earliest_expiration ?? ''
      const bDate = b.earliest_expiration ?? ''
      if (!aDate && !bDate) return 0
      if (!aDate) return dir
      if (!bDate) return -dir
      return dir * aDate.localeCompare(bDate)
    }
    // status: composite severity (out-of-stock > low-stock > MHD critical > MHD warning > ok > discontinued)
    const aSev = statusSeverity(a)
    const bSev = statusSeverity(b)
    if (aSev !== bSev) return dir * (aSev - bSev)
    // secondary: sort by name within same severity
    return a.product_name.localeCompare(b.product_name)
  })
}

const sortedProductSummaries = computed(() => sortSummaries(activeProductSummaries.value))

function batchesForProduct(productId: string): StockBatch[] {
  return batches.value.filter(b => b.product_id === productId)
}

// ── Positions tab state ─────────────────────────────────────────────────────

const positionsLoading = ref(false)
const positionsSaving = ref(false)
let positionSaveTimer: ReturnType<typeof setTimeout> | null = null

// Group management
const expandedGroups = ref(new Set<string>())
const editingGroupId = ref<string | null>(null)
const editingGroupName = ref('')
const showAddGroupModal = ref(false)
const addGroupForm = ref({ name: '', parent_id: null as string | null })
const deletingGroup = ref<WarehousePositionGroup | null>(null)
const showDeleteGroupConfirm = ref(false)

// Multi-select
const selectedProducts = ref(new Set<string>())
const showMoveToGroupMenu = ref(false)

// Cleanup sortables on unmount
onUnmounted(() => destroySortables())

const positionedItems = computed(() => positions.value.filter(p => p.sort_order >= 0))
const unpositionedItems = computed(() => positions.value.filter(p => p.sort_order < 0))
const rootProducts = computed(() => positionedItems.value.filter(p => !p.group_id))
const ungroupedPositioned = computed(() => positionedItems.value.filter(p => !p.group_id))

// Flat list of all groups for move-to-group dropdown
const allGroupsFlat = computed(() => {
  const result: { id: string; name: string; depth: number }[] = []
  const traverse = (nodes: WarehousePositionGroup[], depth: number) => {
    for (const n of nodes) {
      result.push({ id: n.id, name: n.name, depth })
      traverse(n.children, depth + 1)
    }
  }
  traverse(groups.value, 0)
  return result
})

function toggleGroup(groupId: string) {
  if (expandedGroups.value.has(groupId)) {
    expandedGroups.value.delete(groupId)
  } else {
    expandedGroups.value.add(groupId)
  }
}

async function loadPositions() {
  if (!selectedWarehouseId.value) return
  positionsLoading.value = true
  try {
    await fetchGroups(selectedWarehouseId.value)
    await fetchPositions(selectedWarehouseId.value)
    // Auto-expand all groups
    const expandAll = (nodes: WarehousePositionGroup[]) => {
      for (const n of nodes) {
        expandedGroups.value.add(n.id)
        expandAll(n.children)
      }
    }
    expandAll(groups.value)
  } finally {
    positionsLoading.value = false
  }
  nextTick(() => { initSortables(); initGroupSortable() })
}

function debouncedSavePositions() {
  if (positionSaveTimer) clearTimeout(positionSaveTimer)
  positionSaveTimer = setTimeout(() => doSavePositions(), 500)
}

async function doSavePositions() {
  if (!selectedWarehouseId.value) return
  positionsSaving.value = true
  try {
    const items = positionedItems.value.map((p) => ({
      product_id: p.product_id,
      sort_order: p.sort_order,
      location_label: p.location_label,
      group_id: p.group_id,
    }))
    await savePositions(selectedWarehouseId.value, items)
  } catch (e) {
    console.error('Failed to save positions', e)
  } finally {
    positionsSaving.value = false
  }
}

// ── Group CRUD ──────────────────────────────────────────────────────────

function openAddGroup(parentId: string | null = null) {
  addGroupForm.value = { name: '', parent_id: parentId }
  showAddGroupModal.value = true
}

async function submitAddGroup() {
  if (!selectedWarehouseId.value || !addGroupForm.value.name.trim()) return
  try {
    await createGroup(selectedWarehouseId.value, {
      name: addGroupForm.value.name.trim(),
      parent_id: addGroupForm.value.parent_id,
    })
    showAddGroupModal.value = false
    await loadPositions()
  } catch (e) {
    console.error('Failed to create group', e)
  }
}

function startEditGroup(group: WarehousePositionGroup) {
  editingGroupId.value = group.id
  editingGroupName.value = group.name
}

async function saveEditGroup() {
  if (!editingGroupId.value || !editingGroupName.value.trim()) return
  try {
    await updateGroup(editingGroupId.value, { name: editingGroupName.value.trim() })
    editingGroupId.value = null
    await loadPositions()
  } catch (e) {
    console.error('Failed to update group', e)
  }
}

function cancelEditGroup() {
  editingGroupId.value = null
}

async function confirmDeleteGroup() {
  if (!deletingGroup.value) return
  try {
    await deleteGroup(deletingGroup.value.id)
    showDeleteGroupConfirm.value = false
    deletingGroup.value = null
    if (selectedWarehouseId.value) await loadPositions()
  } catch (e) {
    console.error('Failed to delete group', e)
  }
}

// ── Product operations ──────────────────────────────────────────────────

function addToPositions(item: WarehouseProductPosition, groupId: string | null = null) {
  // Snapshot positioned list BEFORE mutating item (item is reactive — mutating
  // sort_order would immediately make it appear in positionedItems computed)
  const currentPositioned = positions.value.filter(p => p.sort_order >= 0 && p.product_id !== item.product_id)
  item.sort_order = currentPositioned.length + 1
  item.group_id = groupId
  positions.value = [
    ...currentPositioned,
    item,
    ...positions.value.filter(p => p.sort_order < 0 && p.product_id !== item.product_id),
  ]
  debouncedSavePositions()
}

async function removeFromPositions(item: WarehouseProductPosition) {
  if (!selectedWarehouseId.value) return
  item.sort_order = -1
  item.group_id = null
  const list = positionedItems.value.filter(p => p.product_id !== item.product_id)
  list.forEach((p, i) => { p.sort_order = i + 1 })
  positions.value = [...list, ...unpositionedItems.value, item].filter((p, i, arr) =>
    arr.findIndex(x => x.product_id === p.product_id) === i
  )
  try {
    await removePosition(selectedWarehouseId.value, item.product_id)
    await doSavePositions()
  } catch (e) {
    console.error('Failed to remove position', e)
  }
}

function updateLocationLabel(item: WarehouseProductPosition, value: string) {
  item.location_label = value || null
  debouncedSavePositions()
}

// ── Multi-select ────────────────────────────────────────────────────────

function toggleSelectProduct(productId: string) {
  if (selectedProducts.value.has(productId)) {
    selectedProducts.value.delete(productId)
  } else {
    selectedProducts.value.add(productId)
  }
  selectedProducts.value = new Set(selectedProducts.value)
}

function deselectAll() {
  selectedProducts.value = new Set()
  showMoveToGroupMenu.value = false
}

/** Rebuild group.products arrays from current positions */
function rebuildGroupProducts() {
  const groupMap = new Map<string, WarehousePositionGroup>()
  const collect = (nodes: WarehousePositionGroup[]) => {
    for (const n of nodes) {
      n.products = []
      groupMap.set(n.id, n)
      collect(n.children)
    }
  }
  collect(groups.value)
  for (const pos of positionedItems.value) {
    if (pos.group_id && groupMap.has(pos.group_id)) {
      groupMap.get(pos.group_id)!.products.push(pos)
    }
  }
}

async function moveSelectedToGroup(groupId: string | null) {
  for (const pid of selectedProducts.value) {
    const item = positionedItems.value.find(p => p.product_id === pid)
    if (item) item.group_id = groupId
  }
  rebuildGroupProducts()
  deselectAll()
  showMoveToGroupMenu.value = false
  debouncedSavePositions()
}

// ── Sortable.js integration ─────────────────────────────────────────────

const sortableInstances = ref<any[]>([])

function destroySortables() {
  for (const s of sortableInstances.value) s.destroy()
  sortableInstances.value = []
}

function initSortables() {
  if (import.meta.server) return
  destroySortables()

  nextTick(() => {
    // Init sortable on each product list container
    const containers = document.querySelectorAll('[data-sortable-group]')
    for (const el of containers) {
      const groupId = el.getAttribute('data-sortable-group') || null
      const resolvedGroupId = groupId === '__root__' ? null : groupId

      if (!_Sortable) return
      const instance = _Sortable.create(el as HTMLElement, {
        group: 'products', // allows cross-group dragging
        handle: '.drag-handle',
        animation: 150,
        ghostClass: 'opacity-30',
        dragClass: 'shadow-lg',
        onEnd(evt) {
          const productId = evt.item.getAttribute('data-product-id')
          if (!productId) return

          const toGroupId = evt.to.getAttribute('data-sortable-group')
          const targetGroupId = !toGroupId || toGroupId === '__root__' ? null : toGroupId

          // Update group_id if moved between groups
          const item = positionedItems.value.find(p => p.product_id === productId)
          if (item) {
            item.group_id = targetGroupId

            // Reorder within target group based on new DOM order
            const targetContainer = evt.to
            const children = targetContainer.querySelectorAll('[data-product-id]')
            children.forEach((child, idx) => {
              const pid = child.getAttribute('data-product-id')
              const pos = positionedItems.value.find(p => p.product_id === pid)
              if (pos) pos.sort_order = idx + 1
            })

            rebuildGroupProducts()
            debouncedSavePositions()
          }
        },
      })
      sortableInstances.value.push(instance)
    }
  })
}

// ── Sortable for groups themselves ───────────────────────────────────────

function initGroupSortable() {
  if (import.meta.server) return
  nextTick(() => {
    const container = document.querySelector('[data-sortable-groups]')
    if (!container) return

    if (!_Sortable) return
    const instance = _Sortable.create(container as HTMLElement, {
      handle: '.group-drag-handle',
      animation: 150,
      ghostClass: 'opacity-30',
      onEnd(evt) {
        if (evt.oldIndex === undefined || evt.newIndex === undefined) return
        if (evt.oldIndex === evt.newIndex) return
        const list = [...groups.value]
        const [moved] = list.splice(evt.oldIndex, 1)
        list.splice(evt.newIndex, 0, moved!)
        list.forEach((g, i) => { g.sort_order = i + 1 })
        groups.value = list
        rebuildGroupProducts()
        // Save group order
        if (selectedWarehouseId.value) {
          saveGroupOrder(selectedWarehouseId.value, list.map(g => ({
            id: g.id,
            sort_order: g.sort_order,
            parent_id: g.parent_id,
          }))).catch(e => console.error('Failed to save group order', e))
        }
      },
    })
    sortableInstances.value.push(instance)
  })
}

// Re-init sortables when groups expand/collapse
watch(expandedGroups, () => { nextTick(() => initSortables()) }, { deep: true })

// Load positions when tab switches to positions
watch(activeTab, (tab) => {
  if (tab === 'positions' && selectedWarehouseId.value) {
    loadPositions()
  }
})

// ── Incoming tab state ──────────────────────────────────────────────────────

const incomingProductId = ref('')
const incomingQuantity = ref<number | null>(null)
const incomingQuantityRaw = ref('')

function evaluateQuantityFormula(raw: string): number | null {
  const expr = raw.replace(/\s/g, '')
  if (!expr) return null
  if (!/^[\d+\-*/.()]+$/.test(expr)) return null
  try {
    const result = Function(`"use strict"; return (${expr})`)()
    if (typeof result !== 'number' || !isFinite(result) || result <= 0) return null
    return Math.round(result)
  } catch { return null }
}

function onQuantityBlur() {
  const result = evaluateQuantityFormula(incomingQuantityRaw.value)
  incomingQuantity.value = result
  if (result !== null) {
    incomingQuantityRaw.value = String(result)
  }
}
const incomingExpiration = ref('')
const incomingBatch = ref('')
const incomingLoading = ref(false)
const incomingError = ref('')
const recentBookings = ref<{ product_id: string; product_name: string; product_image: string | null; quantity: number; expiration: string | null }[]>([])

function productImagePath(productId: string): string | null {
  return products.value.find(p => p.id === productId)?.image_path ?? null
}
const showScanner = ref(false)
const scannedBarcode = ref('')

// ── Quick-create product modal ───────────────────────────────────────────────
const { images: qcSuggestedImages, searching: qcSearchingImages, searchDebounced: qcSearchDebounced, downloadImage: qcDownloadSuggestedImage, clear: qcClearImageSearch } = useProductImageSearch()
const showQuickCreateModal = ref(false)
const quickCreateForm = ref({ name: '', sellprice: null as number | null, description: '', category: '' })
const quickCreateImageFile = ref<File | null>(null)
const quickCreateImagePreview = ref<string | null>(null)
const quickCreateSelectedImageUrl = ref<string | null>(null)
const quickCreateLoading = ref(false)
const quickCreateError = ref('')
// Tracks where the new product should go after creation
const quickCreateTarget = ref<'incoming' | 'assign'>('incoming')

// Watch quick-create product name for image suggestions
watch(() => quickCreateForm.value.name, (name) => {
  if (!quickCreateImageFile.value && !quickCreateSelectedImageUrl.value) {
    qcSearchDebounced(name)
  }
})

function selectQcSuggestedImage(thumbnail: string, imageUrl: string) {
  quickCreateImagePreview.value = thumbnail
  quickCreateSelectedImageUrl.value = imageUrl
  quickCreateImageFile.value = null
  qcClearImageSearch()
}

function openQuickCreateProduct(name: string, target: 'incoming' | 'assign') {
  quickCreateForm.value = { name: name.trim(), sellprice: null, description: '', category: '' }
  quickCreateImageFile.value = null
  quickCreateImagePreview.value = null
  quickCreateSelectedImageUrl.value = null
  qcClearImageSearch()
  quickCreateError.value = ''
  quickCreateTarget.value = target
  showQuickCreateModal.value = true
}

function onQuickCreateImageSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  quickCreateImageFile.value = file
  const reader = new FileReader()
  reader.onload = (e) => { quickCreateImagePreview.value = e.target?.result as string }
  reader.readAsDataURL(file)
}

function clearQuickCreateImage() {
  quickCreateImageFile.value = null
  quickCreateImagePreview.value = null
  quickCreateSelectedImageUrl.value = null
}

async function submitQuickCreate() {
  if (!quickCreateForm.value.name.trim() || !organization.value) {
    quickCreateError.value = t('products.nameRequired')
    return
  }
  quickCreateLoading.value = true
  quickCreateError.value = ''
  try {
    const newId = await createProduct({
      name: quickCreateForm.value.name.trim(),
      sellprice: quickCreateForm.value.sellprice,
      description: quickCreateForm.value.description.trim() || null,
      category: quickCreateForm.value.category || null,
      company: organization.value.id,
    })
    // Download suggested image if selected
    if (quickCreateSelectedImageUrl.value && !quickCreateImageFile.value) {
      const file = await qcDownloadSuggestedImage(quickCreateSelectedImageUrl.value)
      if (file) quickCreateImageFile.value = file
    }
    if (quickCreateImageFile.value) {
      await uploadProductImage(newId, quickCreateImageFile.value)
    }
    // Select the new product in the appropriate context
    if (quickCreateTarget.value === 'incoming') {
      incomingProductId.value = newId
    } else {
      assignBarcodeProductId.value = newId
    }
    showQuickCreateModal.value = false
  } catch (err: any) {
    quickCreateError.value = err.message ?? t('products.failedToSave')
  } finally {
    quickCreateLoading.value = false
  }
}

// Barcode assign flow (when barcode not found)
const showAssignBarcodeFlow = ref(false)
const assignBarcodeValue = ref('')
const assignBarcodeProductId = ref('')
const assignBarcodeLoading = ref(false)
const assignBarcodeError = ref('')

// Products that don't have any barcode yet
const productsWithoutBarcode = computed(() => {
  const barcodeProductIds = new Set(barcodes.value.map(b => b.product_id))
  return products.value.filter(p => !barcodeProductIds.has(p.id))
})

async function onBarcodeDetected(barcode: string) {
  showScanner.value = false
  scannedBarcode.value = barcode
  incomingError.value = ''
  showAssignBarcodeFlow.value = false
  const result = await lookupBarcode(barcode)
  if (result) {
    incomingProductId.value = result.product_id
  } else {
    // Show assign flow
    assignBarcodeValue.value = barcode
    assignBarcodeProductId.value = ''
    assignBarcodeError.value = ''
    showAssignBarcodeFlow.value = true
  }
}

async function assignBarcodeAndSelect() {
  if (!assignBarcodeProductId.value || !assignBarcodeValue.value) {
    assignBarcodeError.value = t('warehouse.selectProductForBarcode')
    return
  }
  assignBarcodeLoading.value = true
  assignBarcodeError.value = ''
  try {
    await addBarcode({ product_id: assignBarcodeProductId.value, barcode: assignBarcodeValue.value })
    // Select the product for incoming
    incomingProductId.value = assignBarcodeProductId.value
    showAssignBarcodeFlow.value = false
  } catch (err: any) {
    assignBarcodeError.value = err.message ?? t('warehouse.failedToAssignBarcode')
  } finally {
    assignBarcodeLoading.value = false
  }
}

async function submitIncoming() {
  if (!selectedWarehouseId.value || !incomingProductId.value || !incomingQuantity.value) {
    incomingError.value = t('warehouse.selectProductAndQuantity')
    return
  }
  incomingLoading.value = true
  incomingError.value = ''
  try {
    await bookIncoming({
      warehouse_id: selectedWarehouseId.value,
      product_id: incomingProductId.value,
      quantity: incomingQuantity.value,
      expiration_date: incomingExpiration.value || null,
      batch_number: incomingBatch.value.trim() || undefined,
    })
    const product = products.value.find(p => p.id === incomingProductId.value)
    recentBookings.value.unshift({
      product_id: incomingProductId.value,
      product_name: product?.name ?? 'Unknown',
      product_image: product?.image_path ?? null,
      quantity: incomingQuantity.value,
      expiration: incomingExpiration.value || null,
    })
    // Reset form for next scan
    incomingProductId.value = ''
    incomingQuantity.value = null
    incomingQuantityRaw.value = ''
    incomingExpiration.value = ''
    incomingBatch.value = ''
    scannedBarcode.value = ''
    showAssignBarcodeFlow.value = false
    // Refresh data
    await loadWarehouseData()
  } catch (err: any) {
    incomingError.value = err.message ?? t('warehouse.failedToBookIncoming')
  } finally {
    incomingLoading.value = false
  }
}

// ── Adjustment modal state ──────────────────────────────────────────────────

const showAdjustModal = ref(false)
const adjustBatch = ref<StockBatch | null>(null)
const adjustDirection = ref<'remove' | 'add'>('remove')
const adjustQuantity = ref<number | null>(null)
const adjustReason = ref<
  'adjustment_damage' | 'adjustment_expired' | 'adjustment_correction' | 'adjustment_refill_return'
>('adjustment_damage')
const adjustNotes = ref('')
const adjustLoading = ref(false)
const adjustError = ref('')

/** Default reason per direction — must be a valid option for the active direction. */
function defaultReasonFor(direction: 'remove' | 'add') {
  return direction === 'remove' ? 'adjustment_damage' : 'adjustment_refill_return'
}

function openAdjust(batch: StockBatch) {
  adjustBatch.value = batch
  adjustDirection.value = 'remove'
  adjustQuantity.value = null
  adjustReason.value = defaultReasonFor('remove')
  adjustNotes.value = ''
  adjustError.value = ''
  showAdjustModal.value = true
}

/**
 * Called when the direction toggle flips. Always resets to the default reason
 * for the new direction, even though `adjustment_correction` is valid in both
 * — this prevents stale negative-only reasons (Damaged/Expired) leaking into
 * the Add flow. A future polish could be "only reset when current reason is
 * not valid for the new direction."
 */
function onAdjustDirectionChange() {
  adjustReason.value = defaultReasonFor(adjustDirection.value)
}

async function submitAdjust() {
  if (!adjustBatch.value || !adjustQuantity.value) {
    adjustError.value = t('warehouse.enterQuantity')
    return
  }
  adjustLoading.value = true
  adjustError.value = ''
  try {
    const magnitude = Math.abs(adjustQuantity.value)
    const signed = adjustDirection.value === 'remove' ? -magnitude : magnitude
    await adjustStock({
      batch_id: adjustBatch.value.id,
      warehouse_id: adjustBatch.value.warehouse_id,
      product_id: adjustBatch.value.product_id,
      quantity_change: signed,
      reason: adjustReason.value,
      notes: adjustNotes.value.trim() || undefined,
    })
    showAdjustModal.value = false
    await loadWarehouseData()
  } catch (err: any) {
    adjustError.value = err.message ?? t('warehouse.failedToAdjustStock')
  } finally {
    adjustLoading.value = false
  }
}

// ── History tab state ───────────────────────────────────────────────────────

const historyFilterType = ref('')
const historyFilterProduct = ref('')
const historyLoaded = ref(false)

async function loadHistory() {
  if (!selectedWarehouseId.value) return
  historyLoaded.value = true
  await fetchTransactions(selectedWarehouseId.value, {
    type: historyFilterType.value || undefined,
    product_id: historyFilterProduct.value || undefined,
  })
}

async function loadMoreHistory() {
  if (!selectedWarehouseId.value) return
  await fetchMoreTransactions(selectedWarehouseId.value, {
    type: historyFilterType.value || undefined,
    product_id: historyFilterProduct.value || undefined,
  })
}

watch([historyFilterType, historyFilterProduct], () => {
  if (historyLoaded.value) loadHistory()
})

watch(activeTab, (tab) => {
  if (tab === 'history' && !historyLoaded.value) loadHistory()
})

// ── Warehouse settings modal state ──────────────────────────────────────────

const showWarehouseModal = ref(false)
const editingWarehouse = ref<Warehouse | null>(null)
const warehouseForm = ref({ name: '', address: '', notes: '' })
const warehouseLoading = ref(false)
const warehouseError = ref('')

function openAddWarehouse() {
  editingWarehouse.value = null
  warehouseForm.value = { name: '', address: '', notes: '' }
  warehouseError.value = ''
  showWarehouseModal.value = true
}

function openEditWarehouse(wh: Warehouse) {
  editingWarehouse.value = wh
  warehouseForm.value = { name: wh.name, address: wh.address ?? '', notes: wh.notes ?? '' }
  warehouseError.value = ''
  showWarehouseModal.value = true
}

async function submitWarehouse() {
  if (!warehouseForm.value.name.trim()) {
    warehouseError.value = t('warehouse.nameRequired')
    return
  }
  warehouseLoading.value = true
  warehouseError.value = ''
  try {
    if (editingWarehouse.value) {
      await updateWarehouse(editingWarehouse.value.id, {
        name: warehouseForm.value.name.trim(),
        address: warehouseForm.value.address.trim() || undefined,
        notes: warehouseForm.value.notes.trim() || undefined,
      })
    } else {
      const id = await createWarehouse({
        name: warehouseForm.value.name.trim(),
        address: warehouseForm.value.address.trim() || undefined,
        notes: warehouseForm.value.notes.trim() || undefined,
      })
      selectedWarehouseId.value = id
    }
    showWarehouseModal.value = false
  } catch (err: any) {
    warehouseError.value = err.message ?? t('warehouse.failedToSaveWarehouse')
  } finally {
    warehouseLoading.value = false
  }
}

const showDeleteWarehouseConfirm = ref(false)
const deletingWarehouse = ref<Warehouse | null>(null)

async function confirmDeleteWarehouse() {
  if (!deletingWarehouse.value) return
  warehouseLoading.value = true
  try {
    await deleteWarehouse(deletingWarehouse.value.id)
    if (selectedWarehouseId.value === deletingWarehouse.value.id) {
      selectedWarehouseId.value = warehouses.value[0]?.id ?? null
    }
    showDeleteWarehouseConfirm.value = false
  } catch (err: any) {
    warehouseError.value = err.message ?? t('warehouse.failedToDeleteWarehouse')
  } finally {
    warehouseLoading.value = false
  }
}

// ── Barcode modal state ─────────────────────────────────────────────────────

const showBarcodeModal = ref(false)
const barcodeForm = ref({ product_id: '', barcode: '' })
const barcodeLoading = ref(false)
const barcodeError = ref('')
const barcodeSettingsLoaded = ref(false)

async function loadBarcodeSettings() {
  if (!barcodeSettingsLoaded.value) {
    await fetchBarcodes()
    barcodeSettingsLoaded.value = true
  }
}

watch(activeTab, (tab) => {
  if (tab === 'settings') loadBarcodeSettings()
})

async function submitBarcode() {
  if (!barcodeForm.value.product_id || !barcodeForm.value.barcode.trim()) {
    barcodeError.value = t('warehouse.productAndBarcodeRequired')
    return
  }
  barcodeLoading.value = true
  barcodeError.value = ''
  try {
    await addBarcode({
      product_id: barcodeForm.value.product_id,
      barcode: barcodeForm.value.barcode.trim(),
    })
    showBarcodeModal.value = false
    barcodeForm.value = { product_id: '', barcode: '' }
  } catch (err: any) {
    barcodeError.value = err.message ?? t('warehouse.failedToAddBarcode')
  } finally {
    barcodeLoading.value = false
  }
}

// ── Min stock editing ───────────────────────────────────────────────────────

const minStockEdits = ref(new Map<string, number>())

function getMinStockValue(productId: string): number {
  if (minStockEdits.value.has(productId)) return minStockEdits.value.get(productId)!
  const entry = minStocks.value.find(m => m.product_id === productId)
  return entry?.min_quantity ?? 0
}

async function saveMinStock(productId: string) {
  if (!selectedWarehouseId.value) return
  const val = minStockEdits.value.get(productId) ?? 0
  await setMinStock({ product_id: productId, warehouse_id: selectedWarehouseId.value, min_quantity: val })
  minStockEdits.value.delete(productId)
  await fetchProductSummaries(selectedWarehouseId.value)
}

async function onVelocityDaysChange(e: Event) {
  const val = Number((e.target as HTMLSelectElement).value)
  setVelocityDays(val)
  if (selectedWarehouseId.value) {
    await fetchProductSummaries(selectedWarehouseId.value)
  }
}

</script>

<template>
  <div class="flex flex-col gap-4 p-4 md:p-6 overflow-x-hidden">
    <!-- Header -->
    <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <h1 class="text-2xl font-bold">{{ t('warehouse.title') }}</h1>
      <div class="flex items-center gap-2">
        <!-- Warehouse selector -->
        <select
          v-if="warehouses.length > 0"
          v-model="selectedWarehouseId"
          class="h-9 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          <option v-for="wh in warehouses" :key="wh.id" :value="wh.id">{{ wh.name }}</option>
        </select>
        <button
          v-if="isAdmin"
          class="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          @click="openAddWarehouse"
        >
          <IconPlus class="size-4" />
          <span class="hidden sm:inline">{{ t('warehouse.addWarehouse') }}</span>
        </button>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="warehouses.length === 0 && !loading" class="flex flex-col items-center gap-4 py-16 text-center">
      <div class="rounded-full bg-muted p-4">
        <IconPackageImport class="size-8 text-muted-foreground" />
      </div>
      <div>
        <h2 class="text-lg font-semibold">{{ t('warehouse.noWarehousesYet') }}</h2>
        <p class="text-sm text-muted-foreground">{{ t('warehouse.createFirstWarehouse') }}</p>
      </div>
      <button
        v-if="isAdmin"
        class="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        @click="openAddWarehouse"
      >
        <IconPlus class="size-4" />
        {{ t('warehouse.createWarehouse') }}
      </button>
    </div>

    <!-- Tabs -->
    <Tabs v-if="warehouses.length > 0" v-model="activeTab">
      <div class="overflow-x-auto -mx-4 px-4 scrollbar-none">
        <TabsList>
          <TabsTrigger value="overview">{{ t('warehouse.overview') }}</TabsTrigger>
          <TabsTrigger value="stock">{{ t('warehouse.stockTab') }}</TabsTrigger>
          <TabsTrigger value="incoming">{{ t('warehouse.incoming') }}</TabsTrigger>
          <TabsTrigger value="history">{{ t('warehouse.historyTab') }}</TabsTrigger>
          <TabsTrigger v-if="isAdmin" value="positions">{{ t('warehouse.positionsTab') }}</TabsTrigger>
          <TabsTrigger v-if="isAdmin" value="settings">{{ t('warehouse.settings') }}</TabsTrigger>
        </TabsList>
      </div>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- OVERVIEW TAB                                                       -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent value="overview">
        <div class="flex flex-col gap-4">
          <!-- KPI cards -->
          <div class="grid grid-cols-2 gap-3 lg:grid-cols-4 xl:grid-cols-7">
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.activeProducts') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold">{{ totalProducts }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.totalUnits') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold">{{ totalUnits }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.outOfStockCount') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold" :class="outOfStockCount > 0 ? 'text-red-600 dark:text-red-400' : ''">{{ outOfStockCount }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.belowMinStock') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold" :class="belowMinCount > 0 ? 'text-red-600 dark:text-red-400' : ''">{{ belowMinCount }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.expiringSoon') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold" :class="expiringSoonCount > 0 ? 'text-amber-600 dark:text-amber-400' : ''">{{ expiringSoonCount }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.stockValue') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold">{{ formatCurrency(totalValue, locale) }}</div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader class="pb-2">
                <CardTitle class="text-sm font-medium text-muted-foreground">{{ t('warehouse.machineStockValue') }}</CardTitle>
              </CardHeader>
              <CardContent>
                <div class="text-2xl font-bold">{{ formatCurrency(machineStockValue, locale) }}</div>
              </CardContent>
            </Card>
          </div>

          <!-- MHD warnings -->
          <div v-if="productSummaries.some(p => !p.discontinued && p.expiration_status !== 'ok')" class="rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950/20">
            <div class="flex items-center gap-2 font-medium text-amber-800 dark:text-amber-300">
              <IconAlertTriangle class="size-5" />
              {{ t('warehouse.expirationWarnings') }}
            </div>
            <div class="mt-3 space-y-2">
              <div
                v-for="p in productSummaries.filter(p => !p.discontinued && p.expiration_status !== 'ok')"
                :key="p.product_id"
                class="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between rounded-md bg-white/60 px-3 py-2 dark:bg-white/5"
              >
                <div class="flex items-center gap-2 min-w-0">
                  <span :class="[expirationBadgeClass(p.expiration_status), 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium']">
                    {{ expirationLabel(p.expiration_status) }}
                  </span>
                  <NuxtLink
                    :to="`/products/${p.product_id}`"
                    class="flex items-center gap-2 hover:underline"
                  >
                    <img v-if="p.product_image_path" :src="getProductImageUrl(p.product_image_path)" class="size-5 rounded object-cover" alt="" />
                    <span class="text-sm font-medium">{{ p.product_name }}</span>
                  </NuxtLink>
                  <span class="text-xs text-muted-foreground">{{ t('warehouse.unitsCount', { count: p.total_quantity }) }}</span>
                </div>
                <span class="text-sm text-muted-foreground">{{ t('warehouse.mhd', { date: formatDate(p.earliest_expiration, locale.value) }) }}</span>
              </div>
            </div>
          </div>

          <!-- Discontinued filter toggle -->
          <div class="flex items-center justify-end gap-4">
            <label class="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer select-none">
              <input type="checkbox" v-model="showUnassigned" class="rounded border-input" />
              {{ t('warehouse.showUnassigned') }}
            </label>
            <label class="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer select-none">
              <input type="checkbox" v-model="showDiscontinued" class="rounded border-input" />
              {{ t('warehouse.showDiscontinued') }}
            </label>
            <label class="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer select-none">
              <input type="checkbox" v-model="showAllOutOfStock" class="rounded border-input" />
              {{ t('warehouse.showAllOutOfStock') }}
            </label>
          </div>

          <!-- Product summary table -->
          <div class="overflow-x-auto rounded-md border">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b bg-muted/50 text-left">
                  <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleSort('name')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.productCol') }}
                      <component :is="stockSortKey === 'name' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="px-4 py-3 font-medium text-right cursor-pointer select-none hover:text-foreground" @click="toggleSort('quantity')">
                    <div class="flex items-center justify-end gap-1">
                      {{ t('warehouse.quantityCol') }}
                      <component :is="stockSortKey === 'quantity' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="hidden px-4 py-3 font-medium text-right md:table-cell cursor-pointer select-none hover:text-foreground" @click="toggleSort('min_stock')">
                    <div class="flex items-center justify-end gap-1">
                      {{ t('warehouse.minStockCol') }}
                      <component :is="stockSortKey === 'min_stock' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="hidden px-4 py-3 font-medium text-right md:table-cell cursor-pointer select-none hover:text-foreground" @click="toggleSort('range')">
                    <div class="flex items-center justify-end gap-1">
                      {{ t('warehouse.estimatedRange') }}
                      <component :is="stockSortKey === 'range' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="hidden px-4 py-3 font-medium md:table-cell cursor-pointer select-none hover:text-foreground" @click="toggleSort('expiration')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.earliestMhd') }}
                      <component :is="stockSortKey === 'expiration' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleSort('status')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.statusCol') }}
                      <component :is="stockSortKey === 'status' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="loading && sortedProductSummaries.length === 0">
                  <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('common.loading') }}</td>
                </tr>
                <tr v-else-if="sortedProductSummaries.length === 0">
                  <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('warehouse.noStockInWarehouse') }}</td>
                </tr>
                <tr
                  v-for="p in sortedProductSummaries"
                  :key="p.product_id"
                  class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                  :class="{ 'opacity-50': p.discontinued, 'opacity-70': !p.discontinued && !isAssignedToMachine(p.product_id) }"
                >
                  <td class="px-4 py-3">
                    <NuxtLink
                      :to="`/products/${p.product_id}`"
                      class="flex items-center gap-2 hover:underline"
                    >
                      <img
                        v-if="p.product_image_path"
                        :src="getProductImageUrl(p.product_image_path)"
                        class="size-8 rounded object-cover"
                        alt=""
                      />
                      <div v-else class="flex size-8 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">
                        {{ p.product_name.charAt(0) }}
                      </div>
                      <span class="font-medium" :class="{ 'line-through': p.discontinued }">{{ p.product_name }}</span>
                      <span
                        v-if="p.discontinued"
                        class="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-800 dark:text-gray-400"
                      >
                        {{ t('warehouse.discontinuedBadge') }}
                      </span>
                      <span
                        v-if="!p.discontinued && !isAssignedToMachine(p.product_id)"
                        class="inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600 dark:bg-blue-950/30 dark:text-blue-400"
                      >
                        {{ t('warehouse.notAssignedBadge') }}
                      </span>
                    </NuxtLink>
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums">{{ p.total_quantity }}</td>
                  <td class="hidden px-4 py-3 text-right tabular-nums md:table-cell">{{ p.min_stock || '—' }}</td>
                  <td class="hidden px-4 py-3 text-right tabular-nums md:table-cell">
                    <span v-if="p.estimated_days_remaining !== null" :class="p.estimated_days_remaining <= 7 ? 'text-red-600 dark:text-red-400 font-medium' : p.estimated_days_remaining <= 14 ? 'text-amber-600 dark:text-amber-400' : ''">
                      {{ t('warehouse.daysRemaining', { days: p.estimated_days_remaining }) }}
                    </span>
                    <span v-else class="text-muted-foreground">{{ p.total_quantity > 0 ? t('warehouse.noSalesData') : '—' }}</span>
                  </td>
                  <td class="hidden px-4 py-3 md:table-cell">{{ formatDate(p.earliest_expiration, locale.value) }}</td>
                  <td class="px-4 py-3">
                    <div class="flex flex-wrap gap-1.5">
                      <span
                        v-if="p.total_quantity === 0 && !p.discontinued"
                        class="inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-400"
                      >
                        {{ t('warehouse.outOfStock') }}
                      </span>
                      <span
                        v-if="p.expiration_status !== 'ok'"
                        :class="[expirationBadgeClass(p.expiration_status), 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium']"
                      >
                        {{ expirationLabel(p.expiration_status) }}
                      </span>
                      <span
                        v-if="p.is_below_min"
                        class="inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-400"
                      >
                        {{ t('warehouse.lowStock') }}
                      </span>
                      <span
                        v-if="p.total_quantity > 0 && p.expiration_status === 'ok' && !p.is_below_min && !p.discontinued"
                        class="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-950/40 dark:text-green-400"
                      >
                        {{ t('warehouse.ok') }}
                      </span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </TabsContent>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- STOCK TAB                                                          -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent value="stock">
        <div class="flex flex-col gap-4">
          <!-- Filters -->
          <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex items-center gap-2 flex-wrap">
              <div class="relative">
                <IconSearch class="absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  v-model="stockSearch"
                  type="text"
                  :placeholder="t('warehouse.searchProducts')"
                  class="h-9 rounded-md border border-input bg-background pl-8 pr-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
              <select
                v-model="stockFilter"
                class="h-9 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="all">{{ t('warehouse.all') }}</option>
                <option value="warning">{{ t('warehouse.expiringSoonFilter') }}</option>
                <option value="critical">{{ t('warehouse.criticalFilter') }}</option>
              </select>
              <label class="flex items-center gap-1.5 text-sm text-muted-foreground cursor-pointer select-none">
                <input type="checkbox" v-model="showUnassigned" class="rounded border-input" />
                {{ t('warehouse.showUnassigned') }}
              </label>
              <label class="flex items-center gap-1.5 text-sm text-muted-foreground cursor-pointer select-none">
                <input type="checkbox" v-model="showDiscontinued" class="rounded border-input" />
                {{ t('warehouse.showDiscontinued') }}
              </label>
              <!-- Off by default; zero-stock products still sitting in a
                   machine slot are shown regardless — hence "all". -->
              <label class="flex items-center gap-1.5 text-sm text-muted-foreground cursor-pointer select-none">
                <input type="checkbox" v-model="showAllOutOfStock" class="rounded border-input" />
                {{ t('warehouse.showAllOutOfStock') }}
              </label>
            </div>
            <button
              v-if="isAdmin"
              class="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              @click="activeTab = 'incoming'"
            >
              <IconPlus class="size-4" />
              {{ t('warehouse.addStock') }}
            </button>
          </div>

          <!-- Expandable stock table -->
          <div class="overflow-x-auto rounded-md border">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b bg-muted/50 text-left">
                  <th class="w-8 px-2 py-3"></th>
                  <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleSort('name')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.productCol') }}
                      <component :is="stockSortKey === 'name' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="px-4 py-3 font-medium text-right cursor-pointer select-none hover:text-foreground" @click="toggleSort('quantity')">
                    <div class="flex items-center justify-end gap-1">
                      {{ t('warehouse.totalQty') }}
                      <component :is="stockSortKey === 'quantity' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="hidden px-4 py-3 font-medium text-right md:table-cell cursor-pointer select-none hover:text-foreground" @click="toggleSort('range')">
                    <div class="flex items-center justify-end gap-1">
                      {{ t('warehouse.estimatedRange') }}
                      <component :is="stockSortKey === 'range' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="hidden px-4 py-3 font-medium md:table-cell cursor-pointer select-none hover:text-foreground" @click="toggleSort('expiration')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.earliestMhd') }}
                      <component :is="stockSortKey === 'expiration' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                  <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleSort('status')">
                    <div class="flex items-center gap-1">
                      {{ t('warehouse.statusCol') }}
                      <component :is="stockSortKey === 'status' ? (stockSortDir === 'asc' ? IconArrowUp : IconArrowDown) : IconArrowsSort" class="size-3.5 text-muted-foreground" />
                    </div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="filteredSummaries.length === 0">
                  <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">
                    {{ stockSearch || stockFilter !== 'all' ? t('warehouse.noMatchingProducts') : t('warehouse.noStock') }}
                  </td>
                </tr>
                <template v-for="p in filteredSummaries" :key="p.product_id">
                  <!-- Product row -->
                  <tr
                    class="border-b cursor-pointer hover:bg-muted/30 transition-colors"
                    :class="{ 'opacity-50': p.discontinued, 'opacity-70': !p.discontinued && !isAssignedToMachine(p.product_id) }"
                    @click="p.batch_count > 0 ? toggleExpand(p.product_id) : undefined"
                  >
                    <td class="px-2 py-3 text-center">
                      <component
                        v-if="p.batch_count > 0"
                        :is="expandedProducts.has(p.product_id) ? IconChevronDown : IconChevronRight"
                        class="size-4 text-muted-foreground"
                      />
                    </td>
                    <td class="px-4 py-3">
                      <div class="flex items-center gap-2">
                        <NuxtLink
                          :to="`/products/${p.product_id}`"
                          class="flex items-center gap-2 hover:underline"
                          @click.stop
                        >
                          <img
                            v-if="p.product_image_path"
                            :src="getProductImageUrl(p.product_image_path)"
                            class="size-8 rounded object-cover"
                            alt=""
                          />
                          <div v-else class="flex size-8 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">
                            {{ p.product_name.charAt(0) }}
                          </div>
                          <span
                            class="font-medium"
                            :class="{ 'line-through': p.discontinued }"
                          >
                            {{ p.product_name }}
                          </span>
                        </NuxtLink>
                        <span v-if="p.discontinued" class="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-800 dark:text-gray-400">
                          {{ t('warehouse.discontinuedBadge') }}
                        </span>
                        <span
                          v-if="!p.discontinued && !isAssignedToMachine(p.product_id)"
                          class="inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600 dark:bg-blue-950/30 dark:text-blue-400"
                        >
                          {{ t('warehouse.notAssignedBadge') }}
                        </span>
                        <span v-if="p.batch_count > 0" class="text-xs text-muted-foreground">{{ t('warehouse.batchCount', { count: p.batch_count }, p.batch_count) }}</span>
                      </div>
                    </td>
                    <td class="px-4 py-3 text-right tabular-nums font-medium">{{ p.total_quantity }}</td>
                    <td class="hidden px-4 py-3 text-right tabular-nums md:table-cell">
                      <span v-if="p.estimated_days_remaining !== null" :class="p.estimated_days_remaining <= 7 ? 'text-red-600 dark:text-red-400 font-medium' : p.estimated_days_remaining <= 14 ? 'text-amber-600 dark:text-amber-400' : ''">
                        {{ t('warehouse.daysRemaining', { days: p.estimated_days_remaining }) }}
                      </span>
                      <span v-else class="text-muted-foreground">{{ p.total_quantity > 0 ? t('warehouse.noSalesData') : '—' }}</span>
                    </td>
                    <td class="hidden px-4 py-3 md:table-cell">{{ formatDate(p.earliest_expiration, locale.value) }}</td>
                    <td class="px-4 py-3">
                      <div class="flex flex-wrap gap-1.5">
                        <span
                          v-if="p.total_quantity === 0 && !p.discontinued"
                          class="inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-400"
                        >
                          {{ t('warehouse.outOfStock') }}
                        </span>
                        <span
                          v-if="p.expiration_status !== 'ok'"
                          :class="[expirationBadgeClass(p.expiration_status), 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium']"
                        >
                          {{ expirationLabel(p.expiration_status) }}
                        </span>
                        <span
                          v-if="p.is_below_min"
                          class="inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-400"
                        >
                          {{ t('warehouse.lowStock') }}
                        </span>
                        <span
                          v-if="p.total_quantity > 0 && p.expiration_status === 'ok' && !p.is_below_min && !p.discontinued"
                          class="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-950/40 dark:text-green-400"
                        >
                          {{ t('warehouse.ok') }}
                        </span>
                      </div>
                    </td>
                  </tr>
                  <!-- Expanded batch rows -->
                  <tr
                    v-if="expandedProducts.has(p.product_id)"
                    v-for="batch in batchesForProduct(p.product_id)"
                    :key="batch.id"
                    class="border-b bg-muted/20"
                  >
                    <td></td>
                    <td class="px-4 py-2 pl-12">
                      <span class="text-muted-foreground">{{ batch.batch_number || t('warehouse.noBatchNumber') }}</span>
                    </td>
                    <td class="px-4 py-2 text-right tabular-nums">{{ batch.quantity }}</td>
                    <td class="hidden md:table-cell"></td>
                    <td class="hidden px-4 py-2 md:table-cell">
                      <span
                        :class="[expirationBadgeClass(expirationStatus(batch.expiration_date)), 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium']"
                      >
                        {{ formatDate(batch.expiration_date, locale.value) }}
                      </span>
                    </td>
                    <td class="px-4 py-2">
                      <button
                        v-if="isAdmin"
                        class="inline-flex h-7 items-center gap-1 rounded-md border px-2 text-xs hover:bg-muted"
                        @click.stop="openAdjust(batch)"
                      >
                        <IconAdjustments class="size-3.5" />
                        {{ t('warehouse.adjust') }}
                      </button>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </div>
      </TabsContent>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- INCOMING TAB                                                       -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent value="incoming">
        <div class="mx-auto flex max-w-lg flex-col gap-4">
          <h2 class="text-lg font-semibold">{{ t('warehouse.incomingGoods') }}</h2>

          <!-- Barcode scanner area -->
          <div class="flex flex-col gap-2">
            <BarcodeScanner
              v-if="showScanner"
              @detected="onBarcodeDetected"
              @close="showScanner = false"
            />
            <div class="flex gap-2">
              <button
                class="inline-flex h-9 flex-1 items-center justify-center gap-1.5 rounded-md border border-input bg-background text-sm font-medium hover:bg-muted"
                @click="showScanner = !showScanner"
              >
                <IconBarcode class="size-4" />
                {{ showScanner ? t('warehouse.closeScanner') : t('warehouse.scanBarcode') }}
              </button>
            </div>
            <p v-if="scannedBarcode" class="text-xs text-muted-foreground">
              {{ t('warehouse.scanned', { barcode: scannedBarcode }) }}
            </p>
          </div>

          <!-- Barcode assign flow (when barcode not found) -->
          <div v-if="showAssignBarcodeFlow" class="rounded-md border border-amber-300 bg-amber-50 p-3 dark:border-amber-700 dark:bg-amber-950/20">
            <div class="mb-2 flex items-start justify-between gap-2">
              <p class="text-sm font-medium">{{ t('warehouse.unknownBarcode', { barcode: assignBarcodeValue }) }}</p>
              <button class="shrink-0 text-muted-foreground hover:text-foreground" @click="showAssignBarcodeFlow = false">
                <IconX class="size-4" />
              </button>
            </div>
            <div class="mb-2">
              <ProductCombobox
                v-model="assignBarcodeProductId"
                :products="productsWithoutBarcode"
                :placeholder="t('warehouse.selectProduct')"
                :allow-create="isAdmin"
                @create="(query: string) => openQuickCreateProduct(query, 'assign')"
              />
            </div>
            <button
              class="h-9 w-full rounded-md border border-input bg-background text-sm font-medium hover:bg-muted disabled:opacity-50"
              :disabled="assignBarcodeLoading || !assignBarcodeProductId"
              @click="assignBarcodeAndSelect"
            >
              {{ assignBarcodeLoading ? t('warehouse.assigningBarcode') : t('warehouse.assignBarcodeAndSelect') }}
            </button>
            <FormError :message="assignBarcodeError" />
          </div>

          <!-- Product selection -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('warehouse.product') }} *</label>
            <ProductCombobox
              v-model="incomingProductId"
              :products="products"
              :placeholder="t('warehouse.selectProduct')"
              :allow-create="isAdmin"
              @create="(query: string) => openQuickCreateProduct(query, 'incoming')"
            />
          </div>

          <!-- Quantity -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('warehouse.quantity') }} *</label>
            <input
              v-model="incomingQuantityRaw"
              type="text"
              inputmode="numeric"
              :placeholder="t('warehouse.quantityPlaceholder')"
              class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              @blur="onQuantityBlur"
            />
          </div>

          <!-- Expiration date (optional) -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('warehouse.expirationDate') }}</label>
            <input
              v-model="incomingExpiration"
              type="date"
              class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
            <p class="mt-1 text-xs text-muted-foreground">{{ t('warehouse.expirationHint') }}</p>
          </div>

          <!-- Batch number -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('warehouse.batchLotNumber') }}</label>
            <input
              v-model="incomingBatch"
              type="text"
              :placeholder="t('warehouse.optional')"
              class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
          </div>

          <!-- Error -->
          <FormError :message="incomingError" />

          <!-- Submit -->
          <button
            class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            :disabled="incomingLoading || !incomingProductId || !incomingQuantity"
            @click="submitIncoming"
          >
            {{ incomingLoading ? t('warehouse.booking') : t('warehouse.bookIncoming') }}
          </button>

          <!-- Recent bookings -->
          <div v-if="recentBookings.length > 0" class="space-y-2">
            <h3 class="text-sm font-medium text-muted-foreground">{{ t('warehouse.justBooked') }}</h3>
            <div
              v-for="(item, i) in recentBookings"
              :key="i"
              class="flex items-center justify-between rounded-md border bg-green-50 px-3 py-2 text-sm dark:bg-green-950/20"
            >
              <NuxtLink
                :to="`/products/${item.product_id}`"
                class="flex items-center gap-2 hover:underline"
              >
                <img v-if="item.product_image" :src="getProductImageUrl(item.product_image)" class="size-5 rounded object-cover" alt="" />
                <div v-else class="flex size-5 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ item.product_name.charAt(0) }}</div>
                <span>{{ item.quantity }}x {{ item.product_name }}</span>
              </NuxtLink>
              <span v-if="item.expiration" class="text-muted-foreground">{{ t('warehouse.mhd', { date: formatDate(item.expiration, locale.value) }) }}</span>
            </div>
          </div>
        </div>
      </TabsContent>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- HISTORY TAB                                                        -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent value="history">
        <div class="flex flex-col gap-4">
          <!-- Filters -->
          <div class="flex flex-col gap-2 sm:flex-row sm:items-center">
            <select
              v-model="historyFilterType"
              class="h-9 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option value="">{{ t('warehouse.allTypes') }}</option>
              <option value="incoming">{{ t('warehouse.incomingFilter') }}</option>
              <option value="outgoing_refill">{{ t('warehouse.refillFilter') }}</option>
              <option value="adjustment_damage">{{ t('warehouse.damagedFilter') }}</option>
              <option value="adjustment_expired">{{ t('warehouse.expiredFilter') }}</option>
              <option value="adjustment_correction">{{ t('warehouse.correctionFilter') }}</option>
              <option value="adjustment_refill_return">{{ t('warehouse.refillReturnFilter') }}</option>
            </select>
            <select
              v-model="historyFilterProduct"
              class="h-9 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option value="">{{ t('warehouse.allProducts') }}</option>
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </div>

          <!-- Transaction table -->
          <div class="overflow-x-auto rounded-md border">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b bg-muted/50 text-left">
                  <th class="px-4 py-3 font-medium">{{ t('warehouse.timeCol') }}</th>
                  <th class="px-4 py-3 font-medium">{{ t('warehouse.typeCol') }}</th>
                  <th class="px-4 py-3 font-medium">{{ t('warehouse.productCol') }}</th>
                  <th class="hidden px-4 py-3 font-medium md:table-cell">{{ t('warehouse.batchCol') }}</th>
                  <th class="px-4 py-3 font-medium text-right">{{ t('warehouse.qtyCol') }}</th>
                  <th class="hidden px-4 py-3 font-medium md:table-cell">{{ t('warehouse.userCol') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="transactionLoading && transactions.length === 0">
                  <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('common.loading') }}</td>
                </tr>
                <tr v-else-if="transactions.length === 0">
                  <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('warehouse.noTransactions') }}</td>
                </tr>
                <tr
                  v-for="t in transactions"
                  :key="t.id"
                  class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                >
                  <td class="px-4 py-3 text-muted-foreground">{{ timeAgo(t.created_at) }}</td>
                  <td class="px-4 py-3">
                    <span :class="[transactionTypeBadgeClass(t.transaction_type), 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium']">
                      {{ transactionTypeLabel(t.transaction_type) }}
                    </span>
                  </td>
                  <td class="px-4 py-3">
                    <NuxtLink
                      v-if="t.product_id"
                      :to="`/products/${t.product_id}`"
                      class="flex items-center gap-2 hover:underline"
                    >
                      <img v-if="productImagePath(t.product_id)" :src="getProductImageUrl(productImagePath(t.product_id)!)" class="size-6 rounded object-cover" alt="" />
                      <div v-else class="flex size-6 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ (t.product_name ?? '?').charAt(0) }}</div>
                      <span class="font-medium">{{ t.product_name ?? '—' }}</span>
                    </NuxtLink>
                    <div v-else class="flex items-center gap-2">
                      <div class="flex size-6 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ (t.product_name ?? '?').charAt(0) }}</div>
                      <span class="font-medium">{{ t.product_name ?? '—' }}</span>
                    </div>
                  </td>
                  <td class="hidden px-4 py-3 md:table-cell text-muted-foreground">{{ t.batch_number ?? '—' }}</td>
                  <td class="px-4 py-3 text-right tabular-nums" :class="t.quantity_change > 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'">
                    {{ t.quantity_change > 0 ? '+' : '' }}{{ t.quantity_change }}
                  </td>
                  <td class="hidden px-4 py-3 md:table-cell text-muted-foreground">{{ (t.metadata as any)?._user_email ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <button
            v-if="transactionHasMore"
            class="inline-flex h-9 items-center justify-center rounded-md border px-4 text-sm font-medium hover:bg-muted disabled:opacity-50"
            :disabled="transactionLoading"
            @click="loadMoreHistory"
          >
            {{ transactionLoading ? t('common.loading') : t('warehouse.loadMore') }}
          </button>
        </div>
      </TabsContent>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- POSITIONS TAB                                                       -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent v-if="isAdmin" value="positions">
        <div class="relative flex flex-col gap-4">
          <!-- Saving indicator -->
          <div v-if="positionsSaving" class="absolute -top-5 right-0 text-xs text-muted-foreground">
            {{ t('common.saving') }}...
          </div>

          <div class="flex items-center justify-between">
            <p class="text-sm text-muted-foreground">{{ t('warehouse.positionsDescription') }}</p>
            <button
              class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
              @click="openAddGroup(null)"
            >
              <IconFolderPlus class="size-3.5" />
              {{ t('warehouse.addGroup') }}
            </button>
          </div>

          <!-- Loading -->
          <div v-if="positionsLoading" class="flex items-center justify-center py-12">
            <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          </div>

          <!-- Empty state -->
          <div v-else-if="positions.length === 0 && groups.length === 0" class="rounded-lg border border-dashed p-8 text-center">
            <p class="text-muted-foreground">{{ t('warehouse.positionsEmpty') }}</p>
          </div>

          <template v-else>
            <!-- ── Group tree (sortable container) ─────────────────────── -->
            <div data-sortable-groups class="flex flex-col gap-3">
              <div v-for="group in groups" :key="group.id" class="rounded-md border transition-colors">
                <!-- Group header -->
                <div class="flex items-center gap-2 px-3 py-2 bg-muted/30 cursor-pointer select-none" @click="toggleGroup(group.id)">
                  <IconGripVertical class="group-drag-handle size-4 text-muted-foreground/40 cursor-grab active:cursor-grabbing shrink-0 touch-none" @click.stop />
                  <IconFolder class="size-4 text-muted-foreground" />
                  <component :is="expandedGroups.has(group.id) ? IconChevronDownSmall : IconChevronRightSmall" class="size-4 text-muted-foreground" />

                  <!-- Group name (inline edit) -->
                  <template v-if="editingGroupId === group.id">
                    <input
                      v-model="editingGroupName"
                      type="text"
                      class="h-7 flex-1 rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                      @click.stop
                      @keyup.enter="saveEditGroup"
                      @keyup.escape="cancelEditGroup"
                    />
                    <button class="h-6 rounded px-1.5 text-xs bg-primary text-primary-foreground" @click.stop="saveEditGroup">OK</button>
                    <button class="h-6 rounded px-1.5 text-xs border hover:bg-muted" @click.stop="cancelEditGroup">{{ t('common.cancel') }}</button>
                  </template>
                  <template v-else>
                    <span class="flex-1 text-sm font-semibold">{{ group.name }}</span>
                    <span class="text-xs text-muted-foreground">{{ group.products.length }} {{ group.products.length === 1 ? 'Produkt' : 'Produkte' }}</span>
                  </template>

                  <!-- Group actions -->
                  <div v-if="editingGroupId !== group.id" class="flex items-center gap-0.5" @click.stop>
                    <button class="h-6 w-6 inline-flex items-center justify-center rounded hover:bg-muted" @click="openAddGroup(group.id)" :title="t('warehouse.addSubGroup')">
                      <IconFolderPlus class="size-3.5" />
                    </button>
                    <button class="h-6 w-6 inline-flex items-center justify-center rounded hover:bg-muted" @click="startEditGroup(group)" :title="t('warehouse.editGroup')">
                      <IconEdit class="size-3.5" />
                    </button>
                    <button
                      class="h-6 w-6 inline-flex items-center justify-center rounded hover:bg-red-50 text-red-600 dark:hover:bg-red-950/20 dark:text-red-400"
                      @click="deletingGroup = group; showDeleteGroupConfirm = true"
                      :title="t('warehouse.deleteGroup')"
                    >
                      <IconTrash class="size-3.5" />
                    </button>
                  </div>
                </div>

                <!-- Group content (products + subgroups) -->
                <div v-if="expandedGroups.has(group.id)" class="border-t">
                  <!-- Subgroups (recursive - one level shown inline) -->
                  <template v-for="child in group.children" :key="child.id">
                    <div class="ml-4 border-l transition-colors">
                      <div class="flex items-center gap-2 px-3 py-1.5 bg-muted/20 cursor-pointer select-none" @click="toggleGroup(child.id)">
                        <IconFolder class="size-3.5 text-muted-foreground" />
                        <component :is="expandedGroups.has(child.id) ? IconChevronDownSmall : IconChevronRightSmall" class="size-3.5 text-muted-foreground" />
                        <template v-if="editingGroupId === child.id">
                          <input v-model="editingGroupName" type="text" class="h-6 flex-1 rounded border border-input bg-background px-2 text-xs" @click.stop @keyup.enter="saveEditGroup" @keyup.escape="cancelEditGroup" />
                          <button class="h-5 rounded px-1 text-[10px] bg-primary text-primary-foreground" @click.stop="saveEditGroup">OK</button>
                        </template>
                        <template v-else>
                          <span class="flex-1 text-xs font-semibold">{{ child.name }}</span>
                          <span class="text-[10px] text-muted-foreground">{{ child.products.length }}</span>
                        </template>
                        <div v-if="editingGroupId !== child.id" class="flex items-center gap-0.5" @click.stop>
                          <button class="h-5 w-5 inline-flex items-center justify-center rounded hover:bg-muted" @click="openAddGroup(child.id)"><IconFolderPlus class="size-3" /></button>
                          <button class="h-5 w-5 inline-flex items-center justify-center rounded hover:bg-muted" @click="startEditGroup(child)"><IconEdit class="size-3" /></button>
                          <button class="h-5 w-5 inline-flex items-center justify-center rounded hover:bg-red-50 text-red-600 dark:hover:bg-red-950/20 dark:text-red-400" @click="deletingGroup = child; showDeleteGroupConfirm = true"><IconTrash class="size-3" /></button>
                        </div>
                      </div>
                      <!-- Child products (sortable) -->
                      <div v-if="expandedGroups.has(child.id)" :data-sortable-group="child.id" class="min-h-[2rem]">
                        <div
                          v-for="item in child.products"
                          :key="item.product_id"
                          :data-product-id="item.product_id"
                          class="flex items-center gap-2 border-t px-4 py-2 ml-2 hover:bg-muted/30 transition-colors"
                        >
                          <button class="shrink-0" @click.stop="toggleSelectProduct(item.product_id)">
                            <component :is="selectedProducts.has(item.product_id) ? IconSquareCheck : IconSquare" class="size-4 text-muted-foreground" />
                          </button>
                          <IconGripVertical class="drag-handle size-3.5 text-muted-foreground/40 cursor-grab active:cursor-grabbing shrink-0 touch-none" />
                          <NuxtLink
                            :to="`/products/${item.product_id}`"
                            class="flex min-w-0 flex-1 items-center gap-2 hover:underline"
                            @click.stop
                          >
                            <img v-if="item.image_path" :src="getProductImageUrl(item.image_path)" class="size-7 shrink-0 rounded object-cover" alt="" />
                            <div v-else class="flex size-7 shrink-0 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ item.product_name.charAt(0) }}</div>
                            <span class="min-w-0 flex-1 truncate text-xs font-medium">{{ item.product_name }}</span>
                          </NuxtLink>
                          <input type="text" :value="item.location_label ?? ''" :placeholder="t('warehouse.locationPlaceholder')" class="h-6 w-24 shrink-0 rounded border border-input bg-background px-1.5 text-[10px] sm:w-28" @input="updateLocationLabel(item, ($event.target as HTMLInputElement).value)" />
                          <button class="shrink-0 h-6 w-6 inline-flex items-center justify-center rounded border border-red-200 text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/20" @click="removeFromPositions(item)"><IconX class="size-3" /></button>
                        </div>
                      </div>
                    </div>
                  </template>

                  <!-- Products directly in this group (sortable) -->
                  <div :data-sortable-group="group.id" class="min-h-[2rem]">
                    <div
                      v-for="item in group.products"
                      :key="item.product_id"
                      :data-product-id="item.product_id"
                      class="flex items-center gap-2 border-t px-3 py-2 hover:bg-muted/30 transition-colors"
                    >
                      <button class="shrink-0" @click.stop="toggleSelectProduct(item.product_id)">
                        <component :is="selectedProducts.has(item.product_id) ? IconSquareCheck : IconSquare" class="size-4 text-muted-foreground" />
                      </button>
                      <IconGripVertical class="drag-handle size-4 text-muted-foreground/40 cursor-grab active:cursor-grabbing shrink-0 touch-none" />
                      <NuxtLink
                        :to="`/products/${item.product_id}`"
                        class="flex min-w-0 flex-1 items-center gap-2 hover:underline"
                        @click.stop
                      >
                        <img v-if="item.image_path" :src="getProductImageUrl(item.image_path)" class="size-8 shrink-0 rounded object-cover" alt="" />
                        <div v-else class="flex size-8 shrink-0 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">{{ item.product_name.charAt(0) }}</div>
                        <span class="min-w-0 flex-1 truncate text-sm font-medium">{{ item.product_name }}</span>
                      </NuxtLink>
                      <input type="text" :value="item.location_label ?? ''" :placeholder="t('warehouse.locationPlaceholder')" class="h-7 w-28 shrink-0 rounded-md border border-input bg-background px-2 text-xs sm:w-36" @input="updateLocationLabel(item, ($event.target as HTMLInputElement).value)" />
                      <button class="shrink-0 inline-flex h-7 items-center rounded-md border border-red-200 px-2 text-xs text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/20" @click.stop="removeFromPositions(item)"><IconX class="size-3" /></button>
                    </div>
                  </div>

                  <!-- Empty group hint -->
                  <div v-if="group.products.length === 0 && group.children.length === 0" class="px-4 py-3 text-xs text-muted-foreground text-center border-t">
                    {{ t('warehouse.positionsEmpty') }}
                  </div>
                </div>
              </div>
            </div>

            <!-- ── Root-level products (no group, sortable) ─────────────── -->
            <div
              v-if="ungroupedPositioned.length > 0 || groups.length > 0"
              class="rounded-md border transition-colors"
            >
              <div class="px-3 py-2 bg-muted/20 text-xs font-medium text-muted-foreground">{{ t('warehouse.ungrouped') }}</div>
              <div data-sortable-group="__root__" class="min-h-[2rem]">
                <div
                  v-for="item in ungroupedPositioned"
                  :key="item.product_id"
                  :data-product-id="item.product_id"
                  class="flex items-center gap-2 border-t px-3 py-2 hover:bg-muted/30 transition-colors"
                >
                  <button class="shrink-0" @click.stop="toggleSelectProduct(item.product_id)">
                    <component :is="selectedProducts.has(item.product_id) ? IconSquareCheck : IconSquare" class="size-4 text-muted-foreground" />
                  </button>
                  <IconGripVertical class="drag-handle size-4 text-muted-foreground/40 cursor-grab active:cursor-grabbing shrink-0 touch-none" />
                  <NuxtLink
                    :to="`/products/${item.product_id}`"
                    class="flex min-w-0 flex-1 items-center gap-2 hover:underline"
                    @click.stop
                  >
                    <img v-if="item.image_path" :src="getProductImageUrl(item.image_path)" class="size-8 shrink-0 rounded object-cover" alt="" />
                    <div v-else class="flex size-8 shrink-0 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">{{ item.product_name.charAt(0) }}</div>
                    <span class="min-w-0 flex-1 truncate text-sm font-medium">{{ item.product_name }}</span>
                  </NuxtLink>
                  <input type="text" :value="item.location_label ?? ''" :placeholder="t('warehouse.locationPlaceholder')" class="h-7 w-28 shrink-0 rounded-md border border-input bg-background px-2 text-xs sm:w-36" @input="updateLocationLabel(item, ($event.target as HTMLInputElement).value)" />
                  <button class="shrink-0 inline-flex h-7 items-center rounded-md border border-red-200 px-2 text-xs text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/20" @click.stop="removeFromPositions(item)"><IconX class="size-3" /></button>
                </div>
              </div>
            </div>

            <!-- ── Unpositioned products ──────────────────────────────── -->
            <div v-if="unpositionedItems.length > 0">
              <h3 class="mb-2 text-sm font-medium text-muted-foreground">{{ t('warehouse.unpositioned') }}</h3>
              <div class="rounded-md border border-dashed">
                <div
                  v-for="item in unpositionedItems"
                  :key="item.product_id"
                  class="flex items-center gap-3 border-b border-dashed px-3 py-2.5 last:border-0 opacity-60 hover:opacity-100 transition-opacity"
                >
                  <NuxtLink
                    :to="`/products/${item.product_id}`"
                    class="flex min-w-0 flex-1 items-center gap-3 hover:underline"
                    @click.stop
                  >
                    <img v-if="item.image_path" :src="getProductImageUrl(item.image_path)" class="size-8 shrink-0 rounded object-cover" alt="" />
                    <div v-else class="flex size-8 shrink-0 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">{{ item.product_name.charAt(0) }}</div>
                    <span class="min-w-0 flex-1 truncate text-sm">{{ item.product_name }}</span>
                  </NuxtLink>
                  <button class="inline-flex h-7 shrink-0 items-center gap-1 rounded-md border px-2 text-xs hover:bg-muted" @click.stop="addToPositions(item)">
                    <IconPlus class="size-3" />
                    {{ t('warehouse.addToPositions') }}
                  </button>
                </div>
              </div>
            </div>
          </template>

          <!-- ── Multi-select floating bar ────────────────────────────── -->
          <div
            v-if="selectedProducts.size > 0"
            class="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex items-center gap-3 rounded-lg border bg-background px-4 py-2.5 shadow-lg"
          >
            <span class="text-sm font-medium">{{ t('warehouse.nSelected', { count: selectedProducts.size }) }}</span>
            <div class="relative">
              <button
                class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
                @click="showMoveToGroupMenu = !showMoveToGroupMenu"
              >
                <IconFolder class="size-3.5" />
                {{ t('warehouse.moveToGroup') }}
              </button>
              <!-- Dropdown -->
              <div v-if="showMoveToGroupMenu" class="absolute bottom-full left-0 mb-1 w-56 rounded-md border bg-popover p-1 shadow-md">
                <button class="w-full rounded px-2 py-1.5 text-left text-sm hover:bg-muted" @click="moveSelectedToGroup(null)">
                  {{ t('warehouse.rootLevel') }}
                </button>
                <button
                  v-for="g in allGroupsFlat"
                  :key="g.id"
                  class="w-full rounded px-2 py-1.5 text-left text-sm hover:bg-muted"
                  :style="{ paddingLeft: `${(g.depth + 1) * 12 + 8}px` }"
                  @click="moveSelectedToGroup(g.id)"
                >
                  {{ g.name }}
                </button>
              </div>
            </div>
            <button class="h-8 rounded-md border px-3 text-xs hover:bg-muted" @click="deselectAll">
              {{ t('warehouse.deselectAll') }}
            </button>
          </div>
        </div>
      </TabsContent>

      <!-- ── Add group modal ──────────────────────────────────────────── -->
      <AppModal v-model:open="showAddGroupModal" :title="t('warehouse.addGroup')" size="sm">
        <form class="flex flex-col gap-3" @submit.prevent="submitAddGroup">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('warehouse.groupName') }} *</label>
            <input v-model="addGroupForm.name" type="text" :placeholder="t('warehouse.groupNamePlaceholder')" class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
          </div>
          <div class="flex justify-end gap-2">
            <button type="button" class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showAddGroupModal = false">{{ t('common.cancel') }}</button>
            <button type="submit" class="h-9 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90" :disabled="!addGroupForm.name.trim()">{{ t('common.save') }}</button>
          </div>
        </form>
      </AppModal>

      <!-- ── Delete group confirm modal ──────────────────────────────── -->
      <AppModal v-model:open="showDeleteGroupConfirm" :title="t('warehouse.deleteGroup')" size="sm">
        <p class="text-sm text-muted-foreground">{{ t('warehouse.deleteGroupConfirm', { name: deletingGroup?.name ?? '' }) }}</p>
        <div class="flex justify-end gap-2 pt-2">
          <button class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showDeleteGroupConfirm = false">{{ t('common.cancel') }}</button>
          <button class="h-9 rounded-md bg-red-600 px-4 text-sm font-medium text-white hover:bg-red-700" @click="confirmDeleteGroup">{{ t('common.delete') }}</button>
        </div>
      </AppModal>

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- SETTINGS TAB                                                       -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <TabsContent v-if="isAdmin" value="settings">
        <div class="flex flex-col gap-6">
          <!-- Warehouses management -->
          <div>
            <div class="mb-3 flex items-center justify-between">
              <h2 class="text-lg font-semibold">{{ t('warehouse.warehouses') }}</h2>
              <button
                class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
                @click="openAddWarehouse"
              >
                <IconPlus class="size-3.5" />
                {{ t('common.add') }}
              </button>
            </div>
            <div class="overflow-x-auto rounded-md border">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="px-4 py-3 font-medium">{{ t('common.name') }}</th>
                    <th class="hidden px-4 py-3 font-medium md:table-cell">{{ t('warehouse.warehouseAddress') }}</th>
                    <th class="px-4 py-3 font-medium text-right">{{ t('common.actions') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="wh in warehouses"
                    :key="wh.id"
                    class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                  >
                    <td class="px-4 py-3 font-medium">{{ wh.name }}</td>
                    <td class="hidden px-4 py-3 md:table-cell text-muted-foreground">{{ wh.address ?? '—' }}</td>
                    <td class="px-4 py-3 text-right">
                      <div class="flex items-center justify-end gap-1">
                        <button class="h-7 rounded-md border px-2 text-xs hover:bg-muted" @click="openEditWarehouse(wh)">{{ t('common.edit') }}</button>
                        <button
                          class="h-7 rounded-md border border-red-200 px-2 text-xs text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/20"
                          @click="deletingWarehouse = wh; showDeleteWarehouseConfirm = true"
                        >
                          <IconTrash class="size-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- Barcode management -->
          <div>
            <div class="mb-3 flex items-center justify-between">
              <h2 class="text-lg font-semibold">{{ t('warehouse.barcodesSection') }}</h2>
              <button
                class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
                @click="barcodeForm = { product_id: '', barcode: '' }; barcodeError = ''; showBarcodeModal = true"
              >
                <IconPlus class="size-3.5" />
                {{ t('common.add') }}
              </button>
            </div>
            <div class="overflow-x-auto rounded-md border">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="px-4 py-3 font-medium">{{ t('warehouse.barcodeCol') }}</th>
                    <th class="px-4 py-3 font-medium">{{ t('warehouse.productCol') }}</th>
                    <th class="hidden px-4 py-3 font-medium md:table-cell">{{ t('warehouse.formatCol') }}</th>
                    <th class="px-4 py-3 font-medium text-right">{{ t('common.actions') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="barcodes.length === 0">
                    <td colspan="4" class="px-4 py-6 text-center text-muted-foreground">{{ t('warehouse.noBarcodesYet') }}</td>
                  </tr>
                  <tr
                    v-for="b in barcodes"
                    :key="b.id"
                    class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                  >
                    <td class="px-4 py-3 font-mono text-xs">{{ b.barcode }}</td>
                    <td class="px-4 py-3">
                      <NuxtLink
                        :to="`/products/${b.product_id}`"
                        class="flex items-center gap-2 hover:underline"
                      >
                        <img v-if="productImagePath(b.product_id)" :src="getProductImageUrl(productImagePath(b.product_id)!)" class="size-6 rounded object-cover" alt="" />
                        <div v-else class="flex size-6 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ (b.product_name ?? '?').charAt(0) }}</div>
                        <span>{{ b.product_name ?? '—' }}</span>
                      </NuxtLink>
                    </td>
                    <td class="hidden px-4 py-3 md:table-cell text-muted-foreground">{{ b.format }}</td>
                    <td class="px-4 py-3 text-right">
                      <button
                        class="h-7 rounded-md border border-red-200 px-2 text-xs text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/20"
                        @click="removeBarcode(b.id)"
                      >
                        <IconTrash class="size-3.5" />
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- Velocity calculation period -->
          <div>
            <h2 class="mb-3 text-lg font-semibold">{{ t('warehouse.velocitySettingTitle') }}</h2>
            <p class="mb-3 text-sm text-muted-foreground">{{ t('warehouse.velocitySettingDescription') }}</p>
            <div class="flex items-center gap-3">
              <select
                :value="velocityDays"
                class="h-9 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                @change="onVelocityDaysChange"
              >
                <option :value="7">{{ t('warehouse.velocityDays7') }}</option>
                <option :value="14">{{ t('warehouse.velocityDays14') }}</option>
                <option :value="30">{{ t('warehouse.velocityDays30') }}</option>
                <option :value="60">{{ t('warehouse.velocityDays60') }}</option>
                <option :value="90">{{ t('warehouse.velocityDays90') }}</option>
              </select>
            </div>
          </div>

          <!-- Min stock thresholds -->
          <div>
            <h2 class="mb-3 text-lg font-semibold">{{ t('warehouse.minStockThresholds') }}</h2>
            <p class="mb-3 text-sm text-muted-foreground">{{ t('warehouse.minStockDescription', { name: selectedWarehouse?.name ?? '' }) }}</p>
            <div class="overflow-x-auto rounded-md border">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="px-4 py-3 font-medium">{{ t('warehouse.productCol') }}</th>
                    <th class="px-4 py-3 font-medium">{{ t('warehouse.currentStockCol') }}</th>
                    <th class="px-4 py-3 font-medium">{{ t('warehouse.minStockCol') }}</th>
                    <th class="px-4 py-3 font-medium text-center">{{ t('warehouse.discontinuedCol') }}</th>
                    <th class="px-4 py-3 font-medium text-right">{{ t('common.actions') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="products.length === 0">
                    <td colspan="5" class="px-4 py-6 text-center text-muted-foreground">{{ t('warehouse.noProducts') }}</td>
                  </tr>
                  <tr
                    v-for="p in products"
                    :key="p.id"
                    class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                    :class="{ 'opacity-50': p.discontinued }"
                  >
                    <td class="px-4 py-3">
                      <NuxtLink
                        :to="`/products/${p.id}`"
                        class="flex items-center gap-2 hover:underline"
                      >
                        <img v-if="p.image_path" :src="getProductImageUrl(p.image_path)" class="size-6 rounded object-cover" alt="" />
                        <div v-else class="flex size-6 items-center justify-center rounded bg-muted text-[10px] font-medium text-muted-foreground">{{ p.name.charAt(0) }}</div>
                        <span class="font-medium" :class="{ 'line-through': p.discontinued }">{{ p.name }}</span>
                      </NuxtLink>
                    </td>
                    <td class="px-4 py-3 tabular-nums">
                      {{ productSummaries.find(s => s.product_id === p.id)?.total_quantity ?? 0 }}
                    </td>
                    <td class="px-4 py-3">
                      <input
                        type="number"
                        min="0"
                        :value="getMinStockValue(p.id)"
                        :disabled="p.discontinued"
                        class="h-8 w-20 rounded-md border border-input bg-background px-2 text-sm tabular-nums focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50 disabled:cursor-not-allowed"
                        @input="minStockEdits.set(p.id, Number(($event.target as HTMLInputElement).value))"
                      />
                    </td>
                    <td class="px-4 py-3 text-center">
                      <button
                        class="relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                        :class="p.discontinued ? 'bg-gray-400 dark:bg-gray-600' : 'bg-muted'"
                        role="switch"
                        :aria-checked="p.discontinued"
                        @click="toggleDiscontinued(p.id, !p.discontinued).then(() => { if (selectedWarehouseId) fetchProductSummaries(selectedWarehouseId) })"
                      >
                        <span
                          class="pointer-events-none block size-4 rounded-full bg-background shadow-lg ring-0 transition-transform"
                          :class="p.discontinued ? 'translate-x-4' : 'translate-x-0'"
                        />
                      </button>
                    </td>
                    <td class="px-4 py-3 text-right">
                      <button
                        v-if="minStockEdits.has(p.id)"
                        class="h-7 rounded-md bg-primary px-2 text-xs font-medium text-primary-foreground hover:bg-primary/90"
                        @click="saveMinStock(p.id)"
                      >
                        {{ t('common.save') }}
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </TabsContent>
    </Tabs>

    <!-- ═══════════════════════════════════════════════════════════════════── -->
    <!-- MODALS                                                               -->
    <!-- ═══════════════════════════════════════════════════════════════════── -->

    <!-- Warehouse add/edit modal -->
    <AppModal v-model:open="showWarehouseModal" :title="editingWarehouse ? t('warehouse.editWarehouseModal') : t('warehouse.addWarehouseModal')">
      <form class="flex flex-col gap-3" @submit.prevent="submitWarehouse">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.name') }} *</label>
          <input v-model="warehouseForm.name" type="text" class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('warehouse.warehouseAddress') }}</label>
          <input v-model="warehouseForm.address" type="text" class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.notes') }}</label>
          <textarea v-model="warehouseForm.notes" rows="2" class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"></textarea>
        </div>
        <FormError :message="warehouseError" />
        <div class="flex justify-end gap-2">
          <button type="button" class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showWarehouseModal = false">{{ t('common.cancel') }}</button>
          <button type="submit" class="h-9 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50" :disabled="warehouseLoading">
            {{ warehouseLoading ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </form>
    </AppModal>

    <!-- Delete warehouse confirm modal -->
    <AppModal v-model:open="showDeleteWarehouseConfirm" :title="t('warehouse.deleteWarehouseTitle')" size="sm">
      <p class="text-sm text-muted-foreground">
        {{ t('warehouse.deleteWarehouseConfirm', { name: deletingWarehouse?.name ?? '' }) }}
      </p>
      <div class="flex justify-end gap-2 pt-2">
        <button class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showDeleteWarehouseConfirm = false">{{ t('common.cancel') }}</button>
        <button
          class="h-9 rounded-md bg-red-600 px-4 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
          :disabled="warehouseLoading"
          @click="confirmDeleteWarehouse"
        >
          {{ t('common.delete') }}
        </button>
      </div>
    </AppModal>

    <!-- Adjustment modal -->
    <AppModal v-model:open="showAdjustModal" :title="t('warehouse.adjustStockTitle')">
      <p class="mb-3 text-sm text-muted-foreground">
        {{ t('warehouse.adjustBatchInfo', { product: adjustBatch?.product_name ?? '', batch: adjustBatch?.batch_number || t('warehouse.noBatchId'), quantity: adjustBatch?.quantity ?? 0 }) }}
      </p>
      <form class="flex flex-col gap-3" @submit.prevent="submitAdjust">
        <!-- Direction toggle -->
        <div class="inline-flex rounded-md border bg-muted/40 p-0.5">
          <button
            type="button"
            class="flex-1 rounded px-3 py-1.5 text-sm font-medium transition-colors"
            :class="adjustDirection === 'remove' ? 'bg-red-600 text-white shadow-sm' : 'text-muted-foreground hover:text-foreground'"
            @click="adjustDirection = 'remove'; onAdjustDirectionChange()"
          >
            − {{ t('warehouse.adjustDirectionRemove') }}
          </button>
          <button
            type="button"
            class="flex-1 rounded px-3 py-1.5 text-sm font-medium transition-colors"
            :class="adjustDirection === 'add' ? 'bg-green-600 text-white shadow-sm' : 'text-muted-foreground hover:text-foreground'"
            @click="adjustDirection = 'add'; onAdjustDirectionChange()"
          >
            + {{ t('warehouse.adjustDirectionAdd') }}
          </button>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('warehouse.reason') }} *</label>
          <select v-model="adjustReason" class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
            <template v-if="adjustDirection === 'remove'">
              <option value="adjustment_damage">{{ t('warehouse.damaged') }}</option>
              <option value="adjustment_expired">{{ t('warehouse.expiredDisposed') }}</option>
              <option value="adjustment_correction">{{ t('warehouse.inventoryCorrection') }}</option>
            </template>
            <template v-else>
              <option value="adjustment_refill_return">{{ t('warehouse.refillReturn') }}</option>
              <option value="adjustment_correction">{{ t('warehouse.inventoryCorrection') }}</option>
            </template>
          </select>
          <p v-if="adjustDirection === 'add' && adjustReason === 'adjustment_refill_return'" class="mt-1 text-xs text-muted-foreground">
            {{ t('warehouse.refillReturnDescription') }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ adjustDirection === 'remove' ? t('warehouse.quantityToRemove') : t('warehouse.quantityToAdd') }} *
          </label>
          <input
            v-model.number="adjustQuantity"
            type="number"
            min="1"
            :max="adjustDirection === 'remove' ? (adjustBatch?.quantity ?? undefined) : undefined"
            class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.notes') }}</label>
          <textarea v-model="adjustNotes" rows="2" class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="t('warehouse.optionalDetails')"></textarea>
        </div>

        <FormError :message="adjustError" />

        <div class="flex justify-end gap-2">
          <button type="button" class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showAdjustModal = false">{{ t('common.cancel') }}</button>
          <button
            type="submit"
            class="h-9 rounded-md px-4 text-sm font-medium text-white disabled:opacity-50"
            :class="adjustDirection === 'remove' ? 'bg-red-600 hover:bg-red-700' : 'bg-green-600 hover:bg-green-700'"
            :disabled="adjustLoading"
          >
            {{ adjustLoading
                ? t('warehouse.adjusting')
                : (adjustDirection === 'remove' ? t('warehouse.removeStock') : t('warehouse.addStockSubmit')) }}
          </button>
        </div>
      </form>
    </AppModal>

    <!-- Barcode add modal -->
    <AppModal v-model:open="showBarcodeModal" :title="t('warehouse.addBarcodeTitle')">
      <form class="flex flex-col gap-3" @submit.prevent="submitBarcode">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('warehouse.product') }} *</label>
          <ProductCombobox
            :model-value="barcodeForm.product_id || null"
            :products="products"
            :placeholder="t('warehouse.selectProduct')"
            @update:model-value="barcodeForm.product_id = $event ?? ''"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('warehouse.barcodeEan') }} *</label>
          <input v-model="barcodeForm.barcode" type="text" :placeholder="t('warehouse.barcodeEanPlaceholder')" class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm font-mono focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
        </div>
        <FormError :message="barcodeError" />
        <div class="flex justify-end gap-2">
          <button type="button" class="h-9 rounded-md border px-4 text-sm hover:bg-muted" @click="showBarcodeModal = false">{{ t('common.cancel') }}</button>
          <button type="submit" class="h-9 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50" :disabled="barcodeLoading">
            {{ barcodeLoading ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </form>
    </AppModal>

    <!-- Quick-create product modal -->
    <AppModal v-model:open="showQuickCreateModal" :title="t('products.addProduct')" size="sm">
      <form class="space-y-4" @submit.prevent="submitQuickCreate">
        <div class="space-y-1">
          <label class="text-sm font-medium" for="qc-product-name">{{ t('common.name') }} *</label>
          <input
            id="qc-product-name"
            v-model="quickCreateForm.name"
            type="text"
            required
            :placeholder="t('products.productName')"
            class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>

        <!-- Image upload -->
        <div class="space-y-1">
          <label class="text-sm font-medium">{{ t('products.image') }}</label>
          <div v-if="quickCreateImagePreview" class="relative inline-block">
            <img :src="quickCreateImagePreview" alt="Preview" class="h-24 w-24 rounded-lg object-cover border" />
            <button
              type="button"
              class="absolute -right-2 -top-2 flex h-5 w-5 items-center justify-center rounded-full bg-destructive text-destructive-foreground text-xs shadow"
              @click="clearQuickCreateImage"
            >
              &times;
            </button>
          </div>
          <div v-else>
            <label
              for="qc-product-image"
              class="flex h-24 w-full cursor-pointer items-center justify-center rounded-lg border-2 border-dashed border-muted-foreground/25 text-sm text-muted-foreground transition-colors hover:border-muted-foreground/50 hover:bg-muted/30"
            >
              <div class="text-center">
                <svg xmlns="http://www.w3.org/2000/svg" class="mx-auto mb-1 h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/></svg>
                <span>{{ t('products.clickToUpload') }}</span>
              </div>
            </label>
            <input
              id="qc-product-image"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              class="hidden"
              @change="onQuickCreateImageSelected"
            />
            <!-- Image suggestions -->
            <div v-if="!quickCreateImagePreview && (qcSearchingImages || qcSuggestedImages.length > 0)" class="mt-2">
              <p class="mb-1.5 text-xs text-muted-foreground">{{ qcSearchingImages ? t('products.searchingImages') : t('products.imageSuggestions') }}</p>
              <div v-if="qcSearchingImages" class="flex items-center gap-2 text-xs text-muted-foreground">
                <svg class="size-4 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/></svg>
              </div>
              <div v-else class="grid grid-cols-4 gap-1.5">
                <button
                  v-for="img in qcSuggestedImages"
                  :key="img.image"
                  type="button"
                  class="group relative aspect-square overflow-hidden rounded-md border hover:ring-2 hover:ring-primary"
                  :title="img.title"
                  @click="selectQcSuggestedImage(img.thumbnail, img.image)"
                >
                  <img :src="img.thumbnail" :alt="img.title" class="h-full w-full object-cover" loading="lazy" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <div class="space-y-1">
          <label class="text-sm font-medium" for="qc-product-price">{{ t('products.price') }}</label>
          <input
            id="qc-product-price"
            v-model.number="quickCreateForm.sellprice"
            type="number"
            step="0.01"
            min="0"
            :placeholder="t('products.pricePlaceholder')"
            class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>
        <div class="space-y-1">
          <label class="text-sm font-medium" for="qc-product-category">{{ t('products.category') }}</label>
          <select
            id="qc-product-category"
            v-model="quickCreateForm.category"
            class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            <option value="">—</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
          </select>
        </div>
        <div class="space-y-1">
          <label class="text-sm font-medium" for="qc-product-description">{{ t('common.description') }}</label>
          <textarea
            id="qc-product-description"
            v-model="quickCreateForm.description"
            rows="2"
            :placeholder="t('products.optionalDescription')"
            class="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>

        <FormError :message="quickCreateError" />
        <div class="flex gap-2">
          <button
            type="button"
            class="inline-flex h-9 flex-1 items-center justify-center rounded-md border px-4 text-sm font-medium shadow-sm hover:bg-muted"
            @click="showQuickCreateModal = false"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            :disabled="quickCreateLoading"
            class="inline-flex h-9 flex-1 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90 disabled:opacity-50"
          >
            <span v-if="quickCreateLoading">{{ t('common.saving') }}</span>
            <span v-else>{{ t('common.create') }}</span>
          </button>
        </div>
      </form>
    </AppModal>
  </div>
</template>
