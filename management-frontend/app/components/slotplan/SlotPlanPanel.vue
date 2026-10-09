<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { IconArrowBackUp, IconArrowsExchange, IconLoader2, IconAlertTriangle, IconX, IconPlus } from '@tabler/icons-vue'
import SearchInput from '@/components/SearchInput.vue'
import { formatCurrency } from '@/lib/utils'
import { computeSlotWidths, slotRowCol } from '@/composables/useMachineAnalysis'
import { getProductImageUrl } from '@/composables/useProducts'
import { useOrganization } from '@/composables/useOrganization'
import { useSlotChangeRequests, type SlotChangeRequest } from '@/composables/useSlotChangeRequests'
import {
  firstEmpty, planChanges, planFromTrays, priceChanges, productReach, rebuildPackNeeds,
  removalCandidates, slotNeeds, widthWarning, type PlanTray, type SlotPlan,
} from '@/lib/slotChange'

// "Fächer umbelegen": plan a new slot layout from hints, then save it as a
// change request. Nothing here touches machine_trays — the refiller applies
// the request at the machine during the next tour.

const props = defineProps<{ machineId: string; isAdmin: boolean }>()
const { t, locale } = useI18n()
const supabase = useSupabaseClient()
const { organization } = useOrganization()
const { fetchOpenRequest, saveRequest, withdrawRequest } = useSlotChangeRequests()

const WINDOW_DAYS = 30
const TARGETS = [3, 5, 7, 10]

interface ProductInfo { id: string; name: string; image_url: string | null; sellprice: number | null; discontinued: boolean }

const loading = ref(true)
const error = ref('')
const saving = ref(false)
const saveMessage = ref('')
const trays = ref<PlanTray[]>([])
const products = ref(new Map<string, ProductInfo>())
const rates = ref(new Map<string, number>())
const fleetRates = ref(new Map<string, number>())
const unitsInWindow = ref(new Map<string, number>())
const revenueInWindow = ref(new Map<string, number>())
const tenure = ref(new Map<string, number | null>())
const openRequest = ref<SlotChangeRequest | null>(null)

const plan = ref<SlotPlan>({})
const targetDays = ref(7)
const step = ref<1 | 2>(1)
const armed = ref<string | null>(null)       // product waiting to be placed (tap mode)
const swapFrom = ref<string | null>(null)    // tray waiting for a swap partner (tap mode)
const selected = ref<string | null>(null)    // tray shown in the detail panel
const hoverProduct = ref<string | null>(null)
const query = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const [trayRes, kpiRes, velRes, prodRes, req] = await Promise.all([
      (supabase as any).from('machine_trays')
        .select('id, item_number, product_id, capacity, current_stock')
        .eq('machine_id', props.machineId).order('item_number'),
      (supabase as any).rpc('get_machine_product_kpis', { p_machine_id: props.machineId, p_company_id: companyId, p_days: WINDOW_DAYS }),
      (supabase as any).rpc('get_product_sales_velocity', { p_company_id: companyId, p_days: WINDOW_DAYS }),
      (supabase as any).from('products').select('id, name, image_path, sellprice, discontinued').order('name'),
      fetchOpenRequest(props.machineId),
    ])
    if (trayRes.error) throw trayRes.error
    if (kpiRes.error) throw kpiRes.error

    trays.value = ((trayRes.data ?? []) as any[]).map(r => ({
      id: r.id, item_number: Number(r.item_number), product_id: r.product_id ?? null,
      capacity: r.capacity, current_stock: r.current_stock,
    }))
    const pm = new Map<string, ProductInfo>()
    for (const p of (prodRes.data ?? []) as any[]) {
      pm.set(p.id, { id: p.id, name: p.name ?? '?', image_url: p.image_path ? getProductImageUrl(p.image_path) : null, sellprice: p.sellprice ?? null, discontinued: !!p.discontinued })
    }
    products.value = pm

    const fleet = new Map<string, number>()
    for (const v of (velRes.data ?? []) as any[]) if (v.product_id) fleet.set(v.product_id, parseFloat(v.avg_daily_units) || 0)
    fleetRates.value = fleet

    const units = new Map<string, number>(), revenue = new Map<string, number>(), ten = new Map<string, number | null>()
    const now = Date.now()
    for (const row of (kpiRes.data?.products ?? []) as any[]) {
      units.set(row.product_id, Number(row.units_sold) || 0)
      revenue.set(row.product_id, Number(row.revenue_eur) || 0)
      ten.set(row.product_id, row.offered_since ? Math.floor((now - new Date(row.offered_since).getTime()) / 86_400_000) : null)
    }
    unitsInWindow.value = units
    revenueInWindow.value = revenue
    tenure.value = ten

    // Machine's own sales for products in it, fleet average for the rest.
    const r = new Map(fleet)
    for (const [pid, u] of units) r.set(pid, u / WINDOW_DAYS)
    rates.value = r

    openRequest.value = req
    resetPlan()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** Today's layout with the open request's pending changes on top. */
function resetPlan() {
  const p = planFromTrays(trays.value)
  for (const it of openRequest.value?.items ?? []) {
    if (p[it.tray_id]) p[it.tray_id] = { product_id: it.to_product_id, capacity: it.to_capacity }
  }
  plan.value = p
  armed.value = null
  swapFrom.value = null
  selected.value = null
}

onMounted(load)

// ── Derived ────────────────────────────────────────────────────────────────
const basePlan = computed(() => planFromTrays(trays.value))
const changes = computed(() => planChanges(trays.value, plan.value))
const changedTrayIds = computed(() => new Set(changes.value.map(c => c.tray_id)))
const reachMap = computed(() => productReach(trays.value, plan.value, rates.value))
const needs = computed(() => slotNeeds(trays.value, plan.value, rates.value, targetDays.value))
const missingSpirals = computed(() => needs.value.reduce((s, n) => s + n.extra, 0))
const outCandidates = computed(() => removalCandidates(trays.value, unitsInWindow.value, tenure.value))
const outSet = computed(() => new Set(outCandidates.value))
const nextVisitNow = computed(() => firstEmpty(trays.value, basePlan.value, rates.value))
const nextVisitPlan = computed(() => firstEmpty(trays.value, plan.value, rates.value))
const productsInPlan = computed(() => new Set(Object.values(plan.value).map(s => s.product_id).filter(Boolean) as string[]))
const removedProducts = computed(() => {
  const before = new Set(trays.value.map(t => t.product_id).filter(Boolean) as string[])
  return [...before].filter(pid => !productsInPlan.value.has(pid))
})
const lostRevenue = computed(() => removedProducts.value.reduce((s, pid) => s + (revenueInWindow.value.get(pid) ?? 0), 0))
const widths = computed(() => computeSlotWidths(trays.value.map(t => t.item_number)))
const trayById = computed(() => new Map(trays.value.map(t => [t.id, t])))
const isDirty = computed(() => {
  const saved = new Map((openRequest.value?.items ?? []).map(i => [i.tray_id, i]))
  if (saved.size !== changes.value.length) return true
  return changes.value.some((c) => {
    const s = saved.get(c.tray_id)
    return !s || s.to_product_id !== c.to_product_id || s.to_capacity !== c.to_capacity
  })
})

const catalogue = computed(() => {
  const q = query.value.trim().toLowerCase()
  return [...products.value.values()]
    .filter(p => !p.discontinued && (!q || p.name.toLowerCase().includes(q)))
    .sort((a, b) => (rates.value.get(b.id) ?? 0) - (rates.value.get(a.id) ?? 0))
    .slice(0, q ? 30 : 12)
})

function name(pid: string | null) { return pid ? (products.value.get(pid)?.name ?? '?') : t('slotPlan.empty') }
function image(pid: string | null) { return pid ? (products.value.get(pid)?.image_url ?? null) : null }
function price(pid: string | null) { return pid ? (products.value.get(pid)?.sellprice ?? null) : null }
function days(n: number) {
  if (!Number.isFinite(n)) return '∞'
  return n.toLocaleString(locale.value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })
}

type SlotTag = 'new' | 'out' | 'short' | 'testing' | null
function slotTag(trayId: string): SlotTag {
  const pid = plan.value[trayId]?.product_id ?? null
  // A slow seller stays marked when it is only moved to make room: moving
  // it does not make it sell better.
  if (pid && outSet.value.has(pid)) return 'out'
  if (changedTrayIds.value.has(trayId)) return 'new'
  if (!pid) return null
  const ten = tenure.value.get(pid)
  if (ten != null && ten < 14) return 'testing'
  if ((reachMap.value.get(pid)?.reachDays ?? Infinity) < targetDays.value) return 'short'
  return null
}
const tagClass: Record<Exclude<SlotTag, null>, string> = {
  new: 'border-green-600 bg-green-500/10',
  out: 'border-red-500 bg-red-500/10',
  short: 'border-amber-500 bg-amber-500/10',
  testing: 'border-blue-400 border-dashed',
}
function tagLabel(trayId: string) {
  const tag = slotTag(trayId)
  const pid = plan.value[trayId]?.product_id ?? null
  if (tag === 'new') return t('slotPlan.tagNew')
  if (tag === 'out') return t('slotPlan.tagOut')
  if (tag === 'testing') return t('slotPlan.tagTesting')
  if (tag === 'short' && pid) return t('slotPlan.daysShort', { n: days(reachMap.value.get(pid)!.reachDays) })
  return ''
}

const cells = computed(() => trays.value.map((tr) => {
  const { row, column } = slotRowCol(tr.item_number)
  const slot = plan.value[tr.id]!
  return {
    tray: tr,
    row,
    column,
    width: widths.value.get(tr.item_number) ?? 1,
    product_id: slot.product_id,
    capacity: slot.capacity,
    changed: changedTrayIds.value.has(tr.id),
    narrow: slot.product_id ? widthWarning(trays.value, slot.product_id, tr.id) && slot.product_id !== tr.product_id : false,
  }
}))

// ── Editing ────────────────────────────────────────────────────────────────
const editable = computed(() => props.isAdmin)

function assign(trayId: string, productId: string | null) {
  if (!editable.value || !plan.value[trayId]) return
  plan.value = { ...plan.value, [trayId]: { ...plan.value[trayId]!, product_id: productId } }
  saveMessage.value = ''
}
function swap(a: string, b: string) {
  if (!editable.value || a === b) return
  const pa = plan.value[a]!.product_id, pb = plan.value[b]!.product_id
  plan.value = { ...plan.value, [a]: { ...plan.value[a]!, product_id: pb }, [b]: { ...plan.value[b]!, product_id: pa } }
  saveMessage.value = ''
}
function undo(trayId: string) {
  const tr = trayById.value.get(trayId)
  if (!tr) return
  plan.value = { ...plan.value, [trayId]: { product_id: tr.product_id, capacity: tr.capacity } }
  saveMessage.value = ''
}
function setCapacity(trayId: string, value: number) {
  const cap = Math.max(1, Math.min(99, Math.round(value || 1)))
  plan.value = { ...plan.value, [trayId]: { ...plan.value[trayId]!, capacity: cap } }
  saveMessage.value = ''
}

// Highlight a product's slots while the mouse is over its hint (not on touch,
// where pointerenter fires on tap and would leave the grid dimmed).
function hoverOn(e: PointerEvent, pid: string) {
  if (e.pointerType === 'mouse') hoverProduct.value = pid
}

function onSlotClick(trayId: string) {
  hoverProduct.value = null
  if (armed.value) {
    assign(trayId, armed.value)
    armed.value = null
    return
  }
  if (swapFrom.value) {
    swap(swapFrom.value, trayId)
    swapFrom.value = null
    return
  }
  selected.value = selected.value === trayId ? null : trayId
}
function arm(pid: string) {
  if (!editable.value) return
  swapFrom.value = null
  hoverProduct.value = null
  armed.value = armed.value === pid ? null : pid
}

// Desktop drag & drop (touch uses the tap flow above)
function onDragProduct(e: DragEvent, pid: string) {
  e.dataTransfer?.setData('text/plain', `product:${pid}`)
}
function onDragSlot(e: DragEvent, trayId: string) {
  e.dataTransfer?.setData('text/plain', `slot:${trayId}`)
}
function onDrop(e: DragEvent, trayId: string) {
  const data = e.dataTransfer?.getData('text/plain') ?? ''
  if (data.startsWith('product:')) assign(trayId, data.slice(8))
  else if (data.startsWith('slot:')) swap(data.slice(5), trayId)
}

const selectedCell = computed(() => cells.value.find(c => c.tray.id === selected.value) ?? null)

// ── Request (step 2) ───────────────────────────────────────────────────────
const requestRows = computed(() => changes.value.map(c => ({
  ...c,
  current_stock: trayById.value.get(c.tray_id)?.current_stock ?? 0,
  from_price: price(c.from_product_id),
  to_price: price(c.to_product_id),
  narrow: c.to_product_id ? widthWarning(trays.value, c.to_product_id, c.tray_id) : false,
})))
const prices = computed(() => priceChanges(requestRows.value))
const extraPacking = computed(() => {
  const items = changes.value.map(c => ({ ...c, id: c.tray_id }))
  const stock = new Map(trays.value.map(t => [t.id, t.current_stock]))
  return [...rebuildPackNeeds(items, stock).entries()].map(([pid, qty]) => ({ pid, qty }))
})
const oldStockFate = computed(() => {
  const seen = new Set<string>()
  const out: { pid: string; slots: number[] }[] = []
  for (const c of changes.value) {
    if (!c.from_product_id || seen.has(c.from_product_id) || c.from_product_id === c.to_product_id) continue
    seen.add(c.from_product_id)
    out.push({ pid: c.from_product_id, slots: reachMap.value.get(c.from_product_id)?.slots ?? [] })
  }
  return out
})

async function save() {
  saving.value = true
  saveMessage.value = ''
  error.value = ''
  try {
    await saveRequest(props.machineId, changes.value.map(c => ({
      tray_id: c.tray_id, to_product_id: c.to_product_id, to_capacity: c.to_capacity,
    })))
    openRequest.value = await fetchOpenRequest(props.machineId)
    saveMessage.value = openRequest.value ? t('slotPlan.saved') : t('slotPlan.nothingToSave')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

async function withdraw() {
  if (!openRequest.value) return
  saving.value = true
  error.value = ''
  try {
    await withdrawRequest(openRequest.value.id)
    openRequest.value = null
    resetPlan()
    step.value = 1
    saveMessage.value = t('slotPlan.withdrawn')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div v-if="loading" class="flex items-center justify-center py-12 text-muted-foreground">
      <IconLoader2 class="size-5 animate-spin" />
    </div>
    <div v-else-if="trays.length === 0" class="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      {{ t('analysis.noTrays') }}
    </div>

    <template v-else>
      <!-- Steps -->
      <div class="flex items-center gap-2 text-sm">
        <button
          class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-medium"
          :class="step === 1 ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted'"
          @click="step = 1"
        >1 · {{ t('slotPlan.stepPlan') }}</button>
        <span class="h-px w-6 bg-border" />
        <button
          class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-medium disabled:opacity-50"
          :class="step === 2 ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted'"
          :disabled="changes.length === 0"
          @click="step = 2"
        >2 · {{ t('slotPlan.stepRequest') }}</button>
      </div>

      <div v-if="error" class="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">{{ error }}</div>

      <div
        v-if="openRequest"
        class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 p-3 text-sm"
      >
        <span>
          <b>{{ t('slotPlan.openRequest', { n: openRequest.items.length }) }}</b>
          {{ t('slotPlan.openRequestHint') }}
        </span>
        <button
          v-if="isAdmin"
          class="inline-flex h-8 items-center rounded-md border bg-background px-3 text-xs font-medium hover:bg-muted disabled:opacity-50"
          :disabled="saving"
          @click="withdraw"
        >{{ t('slotPlan.withdraw') }}</button>
      </div>

      <!-- ═════════════ STEP 1: PLAN ═════════════ -->
      <template v-if="step === 1">
        <!-- KPIs -->
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <div class="rounded-xl border bg-card p-3">
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.nextVisit') }}</p>
            <p class="text-xl font-semibold tabular-nums">
              <s v-if="changes.length && nextVisitNow" class="mr-1 text-sm font-normal text-muted-foreground">{{ days(nextVisitNow.reachDays) }}</s>
              {{ nextVisitPlan ? t('slotPlan.daysValue', { n: days(nextVisitPlan.reachDays) }) : '–' }}
            </p>
            <p v-if="nextVisitPlan" class="truncate text-xs text-muted-foreground">{{ t('slotPlan.firstEmpty', { name: name(nextVisitPlan.product_id) }) }}</p>
          </div>
          <div class="rounded-xl border bg-card p-3">
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.slotsChanged') }}</p>
            <p class="text-xl font-semibold tabular-nums">{{ changes.length }}</p>
          </div>
          <div class="rounded-xl border bg-card p-3">
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.removedRevenue') }}</p>
            <p class="text-xl font-semibold tabular-nums">{{ formatCurrency(lostRevenue, locale) }}</p>
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.lastDays', { n: WINDOW_DAYS }) }}</p>
          </div>
          <div class="rounded-xl border bg-card p-3">
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.missingSpirals') }}</p>
            <p class="text-xl font-semibold tabular-nums">{{ missingSpirals }}</p>
            <p class="text-xs text-muted-foreground">{{ t('slotPlan.forTarget', { n: targetDays }) }}</p>
          </div>
        </div>

        <!-- Toolbar -->
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="flex flex-wrap items-center gap-2 text-sm">
            <span class="text-muted-foreground">{{ t('slotPlan.target') }}</span>
            <div class="flex items-center gap-1 rounded-md border p-0.5">
              <button
                v-for="d in TARGETS"
                :key="d"
                class="rounded px-2.5 py-1 text-xs font-medium transition-colors"
                :class="targetDays === d ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted'"
                @click="targetDays = d"
              >{{ t('slotPlan.daysValue', { n: d }) }}</button>
            </div>
          </div>
          <div class="flex gap-2">
            <button
              class="inline-flex h-9 items-center rounded-md border px-3 text-sm font-medium hover:bg-muted disabled:opacity-50"
              :disabled="!isDirty"
              @click="resetPlan"
            >{{ t('slotPlan.reset') }}</button>
            <button
              class="inline-flex min-h-9 items-center rounded-md bg-primary px-3 py-1 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              :disabled="changes.length === 0"
              @click="step = 2"
            >{{ t('slotPlan.toRequest', { n: changes.length }) }}</button>
          </div>
        </div>

        <div
          v-if="armed || swapFrom"
          class="sticky top-2 z-10 flex items-center justify-between gap-2 rounded-lg bg-primary px-3 py-2 text-sm text-primary-foreground shadow"
        >
          <span>{{ armed ? t('slotPlan.armedHint', { name: name(armed) }) : t('slotPlan.swapHint', { n: trayById.get(swapFrom!)?.item_number }) }}</span>
          <button class="rounded p-1 hover:bg-primary-foreground/20" :aria-label="t('common.cancel')" @click="armed = null; swapFrom = null">
            <IconX class="size-4" />
          </button>
        </div>

        <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
          <!-- Machine grid -->
          <div class="min-w-0 rounded-xl border bg-card p-3 sm:p-4">
            <h3 class="text-sm font-medium">{{ t('slotPlan.layoutTitle') }}</h3>
            <p class="mb-3 text-xs text-muted-foreground">{{ t('slotPlan.layoutHint') }}</p>
            <div class="overflow-x-auto">
              <div
                class="grid min-w-[640px] gap-1.5"
                :style="{ gridTemplateColumns: 'repeat(10, minmax(0, 1fr))', gridAutoRows: '8.5rem' }"
              >
                <div
                  v-for="c in cells"
                  :key="c.tray.id"
                  role="button"
                  tabindex="0"
                  :draggable="editable"
                  class="group relative flex cursor-pointer flex-col items-center overflow-hidden rounded-md border-2 p-1 pt-4 text-center transition-opacity"
                  :class="[
                    slotTag(c.tray.id) ? tagClass[slotTag(c.tray.id)!] : 'border-border',
                    selected === c.tray.id ? 'ring-2 ring-primary' : '',
                    (armed || swapFrom) ? 'outline-dashed outline-1 outline-primary/50' : '',
                    hoverProduct && c.product_id !== hoverProduct ? 'opacity-40' : '',
                  ]"
                  :style="{ gridColumn: `${c.column + 1} / span ${c.width}`, gridRow: `${c.row + 1}` }"
                  @click="onSlotClick(c.tray.id)"
                  @keydown.enter="onSlotClick(c.tray.id)"
                  @dragstart="onDragSlot($event, c.tray.id)"
                  @dragover.prevent
                  @drop.prevent="onDrop($event, c.tray.id)"
                >
                  <span v-if="tagLabel(c.tray.id)" class="absolute left-1 top-0.5 rounded bg-black/60 px-1 text-[9px] font-semibold text-white">{{ tagLabel(c.tray.id) }}</span>
                  <span class="absolute right-1 top-0.5 text-[10px] tabular-nums text-muted-foreground">{{ c.capacity }}</span>
                  <img v-if="image(c.product_id)" :src="image(c.product_id)!" :alt="name(c.product_id)" class="h-12 w-12 rounded object-cover" draggable="false" />
                  <div v-else class="flex h-12 w-12 items-center justify-center rounded bg-muted/50 text-xs text-muted-foreground">{{ c.product_id ? '?' : '—' }}</div>
                  <p class="mt-1 line-clamp-2 text-[11px] font-medium leading-tight">{{ name(c.product_id) }}</p>
                  <p v-if="c.changed && c.tray.product_id !== c.product_id" class="line-clamp-1 text-[10px] text-muted-foreground">{{ t('slotPlan.instead', { name: name(c.tray.product_id) }) }}</p>
                  <p v-if="c.narrow" class="text-[10px] font-medium text-amber-600">⚠ {{ t('slotPlan.checkWidth') }}</p>
                  <span class="absolute bottom-0.5 left-0.5 rounded bg-black/60 px-1 text-[9px] font-semibold tabular-nums text-white">{{ c.tray.item_number }}</span>
                  <button
                    v-if="c.changed && editable"
                    class="absolute bottom-0.5 right-0.5 rounded bg-background/90 p-0.5 text-muted-foreground hover:text-foreground"
                    :title="t('slotPlan.undo')"
                    @click.stop="undo(c.tray.id)"
                  ><IconArrowBackUp class="size-3.5" /></button>
                </div>
              </div>
            </div>
            <div class="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
              <span class="flex items-center gap-1.5"><i class="size-3 rounded border-2 border-red-500 bg-red-500/10" />{{ t('slotPlan.legendOut') }}</span>
              <span class="flex items-center gap-1.5"><i class="size-3 rounded border-2 border-amber-500 bg-amber-500/10" />{{ t('slotPlan.legendShort', { n: targetDays }) }}</span>
              <span class="flex items-center gap-1.5"><i class="size-3 rounded border-2 border-green-600 bg-green-500/10" />{{ t('slotPlan.legendNew') }}</span>
              <span class="flex items-center gap-1.5"><i class="size-3 rounded border-2 border-dashed border-blue-400" />{{ t('slotPlan.legendTesting') }}</span>
            </div>
          </div>

          <!-- Side panels -->
          <div class="min-w-0 space-y-4">
            <!-- Selected slot -->
            <div v-if="selectedCell" class="rounded-xl border bg-card p-4">
              <div class="flex items-start justify-between gap-2">
                <div>
                  <h3 class="text-sm font-medium">{{ t('slotPlan.slot', { n: selectedCell.tray.item_number }) }}</h3>
                  <p class="text-xs text-muted-foreground">
                    {{ name(selectedCell.product_id) }}
                    <template v-if="selectedCell.changed && selectedCell.tray.product_id !== selectedCell.product_id"> · {{ t('slotPlan.before', { name: name(selectedCell.tray.product_id) }) }}</template>
                  </p>
                </div>
                <button class="rounded p-1 text-muted-foreground hover:bg-muted" :aria-label="t('common.close')" @click="selected = null"><IconX class="size-4" /></button>
              </div>
              <dl v-if="selectedCell.product_id" class="mt-3 space-y-1.5 text-sm">
                <div class="flex justify-between gap-2">
                  <dt class="text-muted-foreground">{{ t('slotPlan.perDay') }}</dt>
                  <dd class="tabular-nums">{{ days(rates.get(selectedCell.product_id) ?? 0) }}<span v-if="!unitsInWindow.has(selectedCell.product_id)" class="text-muted-foreground"> {{ t('slotPlan.fleetEstimate') }}</span></dd>
                </div>
                <div class="flex justify-between gap-2">
                  <dt class="text-muted-foreground">{{ t('slotPlan.lastDays', { n: WINDOW_DAYS }) }}</dt>
                  <dd class="tabular-nums">{{ unitsInWindow.get(selectedCell.product_id) ?? 0 }} · {{ formatCurrency(revenueInWindow.get(selectedCell.product_id) ?? 0, locale) }}</dd>
                </div>
                <div class="flex justify-between gap-2">
                  <dt class="text-muted-foreground">{{ t('slotPlan.slotsWithProduct') }}</dt>
                  <dd class="tabular-nums">{{ reachMap.get(selectedCell.product_id)?.slots.join(', ') }}</dd>
                </div>
                <div class="flex justify-between gap-2">
                  <dt class="text-muted-foreground">{{ t('slotPlan.lasts') }}</dt>
                  <dd class="tabular-nums">{{ t('slotPlan.daysValue', { n: days(reachMap.get(selectedCell.product_id)?.reachDays ?? Infinity) }) }}</dd>
                </div>
              </dl>
              <p v-if="selectedCell.product_id && (tenure.get(selectedCell.product_id) ?? 99) < 14" class="mt-2 rounded-md bg-blue-400/10 p-2 text-xs">
                {{ t('slotPlan.testingHint', { n: tenure.get(selectedCell.product_id) }) }}
              </p>
              <div v-if="editable" class="mt-3 space-y-3">
                <label class="flex items-center justify-between gap-2 text-sm">
                  <span class="text-muted-foreground">{{ t('slotPlan.capacity') }}</span>
                  <input
                    type="number" min="1" max="99"
                    class="h-8 w-20 rounded-md border bg-background px-2 text-right tabular-nums"
                    :value="selectedCell.capacity"
                    @change="setCapacity(selectedCell.tray.id, Number(($event.target as HTMLInputElement).value))"
                  />
                </label>
                <div class="flex flex-wrap gap-2">
                  <button class="inline-flex h-8 items-center gap-1 rounded-md border px-2.5 text-xs font-medium hover:bg-muted" @click="swapFrom = selectedCell.tray.id; armed = null; selected = null">
                    <IconArrowsExchange class="size-3.5" />{{ t('slotPlan.swapWith') }}
                  </button>
                  <button v-if="selectedCell.product_id" class="inline-flex h-8 items-center gap-1 rounded-md border px-2.5 text-xs font-medium hover:bg-muted" @click="assign(selectedCell.tray.id, null)">
                    {{ t('slotPlan.leaveEmpty') }}
                  </button>
                  <button v-if="selectedCell.changed" class="inline-flex h-8 items-center gap-1 rounded-md border px-2.5 text-xs font-medium hover:bg-muted" @click="undo(selectedCell.tray.id)">
                    <IconArrowBackUp class="size-3.5" />{{ t('slotPlan.undo') }}
                  </button>
                </div>
              </div>
            </div>

            <!-- Needs more spirals -->
            <div class="rounded-xl border bg-card p-4">
              <h3 class="flex items-center gap-2 text-sm font-medium">
                {{ t('slotPlan.needsMore') }}
                <span class="rounded-full bg-green-500/15 px-2 text-xs text-green-700 dark:text-green-400">{{ missingSpirals }}</span>
              </h3>
              <p class="mb-3 text-xs text-muted-foreground">{{ t('slotPlan.needsMoreHint', { n: targetDays }) }}</p>
              <p v-if="needs.length === 0" class="text-sm text-green-700 dark:text-green-400">{{ t('slotPlan.allLast', { n: targetDays }) }}</p>
              <div v-else class="space-y-2">
                <div
                  v-for="n in needs"
                  :key="n.product_id"
                  class="rounded-lg border p-2"
                  @pointerenter="hoverOn($event, n.product_id)"
                  @pointerleave="hoverProduct = null"
                >
                  <div class="flex items-center gap-2">
                    <img v-if="image(n.product_id)" :src="image(n.product_id)!" :alt="name(n.product_id)" class="size-8 rounded object-cover" />
                    <div class="min-w-0">
                      <p class="truncate text-sm font-medium">{{ name(n.product_id) }}</p>
                      <p class="text-xs text-muted-foreground tabular-nums">{{ t('slotPlan.needLine', { rate: days(n.rate), days: days(n.reachDays), slots: n.slots.length }) }}</p>
                    </div>
                  </div>
                  <div class="mt-2 flex flex-wrap gap-1.5">
                    <span
                      v-for="i in n.extra"
                      :key="i"
                      :draggable="editable"
                      class="inline-flex cursor-grab items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium select-none"
                      :class="armed === n.product_id ? 'border-primary bg-primary text-primary-foreground' : 'border-green-600/50 bg-green-500/10 hover:bg-green-500/20'"
                      @dragstart="onDragProduct($event, n.product_id)"
                      @click="arm(n.product_id)"
                    ><IconPlus class="size-3" />{{ t('slotPlan.oneSpiral') }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- Can go -->
            <div class="rounded-xl border bg-card p-4">
              <h3 class="flex items-center gap-2 text-sm font-medium">
                {{ t('slotPlan.canGo') }}
                <span class="rounded-full bg-red-500/15 px-2 text-xs text-red-700 dark:text-red-400">{{ outCandidates.filter(p => productsInPlan.has(p)).length }}</span>
              </h3>
              <p class="mb-3 text-xs text-muted-foreground">{{ t('slotPlan.canGoHint', { n: WINDOW_DAYS }) }}</p>
              <p v-if="outCandidates.length === 0" class="text-sm text-muted-foreground">{{ t('slotPlan.noneToRemove') }}</p>
              <div
                v-for="pid in outCandidates"
                :key="pid"
                class="flex items-center gap-2 py-1.5"
                :class="productsInPlan.has(pid) ? '' : 'opacity-50 line-through'"
                @pointerenter="hoverOn($event, pid)"
                @pointerleave="hoverProduct = null"
              >
                <img v-if="image(pid)" :src="image(pid)!" :alt="name(pid)" class="size-8 rounded object-cover" />
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm">{{ name(pid) }}</p>
                  <p class="text-xs text-muted-foreground tabular-nums">{{ t('slotPlan.soldLine', { n: unitsInWindow.get(pid) ?? 0, revenue: formatCurrency(revenueInWindow.get(pid) ?? 0, locale) }) }}</p>
                </div>
                <span class="shrink-0 text-xs text-muted-foreground tabular-nums">
                  {{ productsInPlan.has(pid) ? t('slotPlan.slot', { n: reachMap.get(pid)?.slots.join(', ') }) : t('slotPlan.replaced') }}
                </span>
              </div>
            </div>

            <!-- Any product -->
            <div class="rounded-xl border bg-card p-4">
              <h3 class="text-sm font-medium">{{ t('slotPlan.otherProduct') }}</h3>
              <p class="mb-2 text-xs text-muted-foreground">{{ t('slotPlan.otherProductHint') }}</p>
              <SearchInput v-model="query" :placeholder="t('analysis.searchPlaceholder')" />
              <div class="mt-2 flex flex-wrap gap-1.5">
                <span
                  v-for="p in catalogue"
                  :key="p.id"
                  :draggable="editable"
                  class="inline-flex max-w-full cursor-grab items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs select-none"
                  :class="armed === p.id ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-muted'"
                  @dragstart="onDragProduct($event, p.id)"
                  @click="arm(p.id)"
                >
                  <img v-if="p.image_url" :src="p.image_url" :alt="p.name" class="size-4 rounded object-cover" draggable="false" />
                  <span class="truncate">{{ p.name }}</span>
                </span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- ═════════════ STEP 2: REQUEST ═════════════ -->
      <template v-else>
        <div class="rounded-xl border bg-muted/40 p-3 text-sm">{{ t('slotPlan.requestHint') }}</div>

        <div class="rounded-xl border bg-card p-4">
          <h3 class="text-sm font-medium">{{ t('slotPlan.requestTitle') }}</h3>
          <p class="mb-3 text-xs text-muted-foreground tabular-nums">
            {{ t('slotPlan.requestSummary', { n: changes.length, after: days(nextVisitPlan?.reachDays ?? Infinity), before: days(nextVisitNow?.reachDays ?? Infinity) }) }}
          </p>
          <div class="overflow-x-auto">
            <table class="w-full min-w-[640px] text-sm">
              <thead>
                <tr class="border-b text-left text-xs text-muted-foreground">
                  <th class="py-2 pr-2 font-medium">{{ t('slotPlan.colSlot') }}</th>
                  <th class="py-2 pr-2 font-medium">{{ t('slotPlan.colOut') }}</th>
                  <th class="py-2 pr-2 font-medium">{{ t('slotPlan.colIn') }}</th>
                  <th class="py-2 pr-2 font-medium">{{ t('slotPlan.colCapacity') }}</th>
                  <th class="py-2 font-medium">{{ t('slotPlan.colPrice') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in requestRows" :key="r.tray_id" class="border-b last:border-0">
                  <td class="py-2 pr-2 font-mono tabular-nums">{{ r.item_number }}</td>
                  <td class="py-2 pr-2">
                    <div class="flex items-center gap-2">
                      <img v-if="image(r.from_product_id)" :src="image(r.from_product_id)!" :alt="name(r.from_product_id)" class="size-8 rounded object-cover" />
                      <div>
                        <p>{{ name(r.from_product_id) }}</p>
                        <p class="text-xs text-muted-foreground tabular-nums">{{ t('slotPlan.inSlotToday', { n: r.current_stock, cap: r.from_capacity }) }}</p>
                      </div>
                    </div>
                  </td>
                  <td class="py-2 pr-2">
                    <div class="flex items-center gap-2">
                      <img v-if="image(r.to_product_id)" :src="image(r.to_product_id)!" :alt="name(r.to_product_id)" class="size-8 rounded object-cover" />
                      <div>
                        <p>{{ name(r.to_product_id) }}</p>
                        <p v-if="r.narrow" class="text-xs text-amber-600"><IconAlertTriangle class="inline size-3" /> {{ t('slotPlan.narrowSlot') }}</p>
                      </div>
                    </div>
                  </td>
                  <td class="py-2 pr-2">
                    <input
                      type="number" min="1" max="99"
                      class="h-8 w-16 rounded-md border bg-background px-2 text-right tabular-nums disabled:opacity-60"
                      :value="r.to_capacity"
                      :disabled="!editable"
                      @change="setCapacity(r.tray_id, Number(($event.target as HTMLInputElement).value))"
                    />
                    <p v-if="r.to_capacity !== r.from_capacity" class="text-[11px] text-amber-600 tabular-nums">{{ t('slotPlan.changeSpiral', { from: r.from_capacity, to: r.to_capacity }) }}</p>
                  </td>
                  <td class="py-2 tabular-nums">
                    <template v-if="!r.to_product_id">–</template>
                    <span v-else-if="r.from_price === r.to_price" class="text-muted-foreground">{{ t('slotPlan.priceStays', { price: formatCurrency(r.to_price ?? 0, locale) }) }}</span>
                    <span v-else>
                      <s v-if="r.from_price != null" class="text-muted-foreground">{{ formatCurrency(r.from_price, locale) }}</s>
                      → <b class="rounded bg-amber-500/15 px-1.5 text-amber-700 dark:text-amber-400">{{ r.to_price != null ? formatCurrency(r.to_price, locale) : '?' }}</b>
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="mt-2 text-xs text-muted-foreground">{{ t('slotPlan.capacityHint') }}</p>
        </div>

        <div class="grid gap-4 md:grid-cols-3">
          <div class="rounded-xl border border-amber-500/50 bg-card p-4">
            <h3 class="text-sm font-medium">{{ t('slotPlan.pricesTitle') }} <span class="text-amber-600">({{ prices.length }})</span></h3>
            <p class="mb-2 text-xs text-muted-foreground">{{ t('slotPlan.pricesHint') }}</p>
            <p v-if="prices.length === 0" class="text-sm text-muted-foreground">{{ t('slotPlan.noPriceChange') }}</p>
            <div v-for="p in prices" :key="p.item_number" class="mb-1 flex justify-between rounded-md bg-amber-500/10 px-2 py-1 text-sm tabular-nums">
              <span>{{ t('slotPlan.selection', { n: p.item_number }) }}</span>
              <b>{{ formatCurrency(p.price, locale) }}</b>
            </div>
          </div>
          <div class="rounded-xl border bg-card p-4">
            <h3 class="text-sm font-medium">{{ t('slotPlan.extraPacking') }}</h3>
            <p class="mb-2 text-xs text-muted-foreground">{{ t('slotPlan.extraPackingHint') }}</p>
            <div v-for="x in extraPacking" :key="x.pid" class="flex justify-between py-0.5 text-sm tabular-nums">
              <span>{{ name(x.pid) }}</span>
              <b v-if="x.qty > 0">{{ t('slotPlan.units', { n: x.qty }) }}</b>
              <span v-else class="text-muted-foreground">{{ t('slotPlan.movesOver') }}</span>
            </div>
          </div>
          <div class="rounded-xl border bg-card p-4">
            <h3 class="text-sm font-medium">{{ t('slotPlan.oldStock') }}</h3>
            <p class="mb-2 text-xs text-muted-foreground">{{ t('slotPlan.oldStockHint') }}</p>
            <div v-for="x in oldStockFate" :key="x.pid" class="flex justify-between gap-2 py-0.5 text-sm">
              <span>{{ name(x.pid) }}</span>
              <span class="text-muted-foreground">{{ x.slots.length ? t('slotPlan.intoSlots', { slots: x.slots.join(', ') }) : t('slotPlan.backToWarehouse') }}</span>
            </div>
          </div>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-2">
          <button class="inline-flex h-9 items-center rounded-md border px-3 text-sm font-medium hover:bg-muted" @click="step = 1">← {{ t('slotPlan.backToPlan') }}</button>
          <div class="flex flex-wrap items-center gap-2">
            <span v-if="saveMessage" class="text-sm text-green-700 dark:text-green-400">{{ saveMessage }}</span>
            <button
              v-if="isAdmin"
              class="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              :disabled="saving || !isDirty"
              @click="save"
            >
              <IconLoader2 v-if="saving" class="size-4 animate-spin" />
              {{ openRequest ? t('slotPlan.updateRequest') : t('slotPlan.saveRequest') }}
            </button>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
