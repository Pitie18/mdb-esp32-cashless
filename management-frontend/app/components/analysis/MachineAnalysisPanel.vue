<script setup lang="ts">
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { Badge } from '@/components/ui/badge'
import { IconSparkles, IconLoader2, IconArrowRight, IconFlask } from '@tabler/icons-vue'
import { formatCurrency } from '@/lib/utils'
import MachineLayoutGrid from './MachineLayoutGrid.vue'
import SearchInput from '@/components/SearchInput.vue'
import { useMachineAnalysis, filterSearchableProducts, type GridSlot, type ProductAnalysis, type SlotTier, type Suggestion } from '@/composables/useMachineAnalysis'
import { useInsights, priorityVariant, recommendationTypeLabel } from '@/composables/useInsights'

const props = defineProps<{ machineId: string; isAdmin: boolean }>()

const { t, locale } = useI18n()

const {
  products, slots, fillSuggestions, searchableProducts, rowCount, loading, error, days,
  tierCounts, slotTierCounts, weakProducts, lostRevenuePotential, analyze, applySwap, pendingTrayIds,
} = useMachineAnalysis()

// Replacements only go onto the machine's change request; the slot changes
// when the refiller rebuilds it on the next tour.
const queuedMessage = ref('')
const applyError = ref('')

const PERIODS = [7, 30, 90]

const TIER_ORDER: SlotTier[] = ['dead', 'weak', 'testing', 'ok', 'strong', 'empty']
const tierDot: Record<SlotTier, string> = {
  dead: 'bg-red-500',
  weak: 'bg-orange-400',
  testing: 'bg-blue-400',
  ok: 'bg-yellow-400',
  strong: 'bg-green-500',
  empty: 'bg-muted-foreground/30',
}

onMounted(() => analyze(props.machineId))

function changePeriod(d: number) {
  analyze(props.machineId, d)
}

// ── Detail sheet ─────────────────────────────────────────────────────────────
// The sheet shows a product's machine-wide performance. It can be opened from a
// grid slot (apply targets that slot) or from the weak-products list (apply
// targets the product's first slot). Empty slots open a "fill it" variant.
const sheetOpen = ref(false)
const selectedProductId = ref<string | null>(null)
const targetTrayId = ref<string | null>(null)
const applyingProductId = ref<string | null>(null)

const selectedProduct = computed<ProductAnalysis | null>(() =>
  products.value.find(p => p.product_id === selectedProductId.value) ?? null,
)
const sheetSuggestions = computed<Suggestion[]>(() =>
  selectedProduct.value ? selectedProduct.value.suggestions : fillSuggestions.value,
)
const targetSlotNumber = computed(() =>
  slots.value.find(s => s.trayId === targetTrayId.value)?.item_number ?? null,
)

// Full-catalogue search within the replace sheet. Reset the query each time the
// sheet toggles; exclude the product currently in the slot (can't replace itself).
const productQuery = ref('')
watch(sheetOpen, () => { productQuery.value = '' })
const searchResults = computed(() =>
  filterSearchableProducts(searchableProducts.value, productQuery.value, {
    excludeProductId: selectedProductId.value,
    limit: 30,
  }),
)

function openSlot(slot: GridSlot) {
  targetTrayId.value = slot.trayId
  selectedProductId.value = slot.product_id
  sheetOpen.value = true
}

function openProduct(p: ProductAnalysis) {
  selectedProductId.value = p.product_id
  targetTrayId.value = p.trayIds[0] ?? null
  sheetOpen.value = true
}

async function applyProduct(productId: string) {
  if (!targetTrayId.value) return
  applyingProductId.value = productId
  applyError.value = ''
  try {
    await applySwap(targetTrayId.value, productId)
    queuedMessage.value = t('analysis.queued', { slot: targetSlotNumber.value ?? '' })
    sheetOpen.value = false
  } catch (e: unknown) {
    applyError.value = e instanceof Error ? e.message : String(e)
  } finally {
    applyingProductId.value = null
  }
}
const handleApply = (suggestion: Suggestion) => applyProduct(suggestion.product_id)

// ── AI recommendations ─────────────────────────────────────────────────────
const { data: insights, loading: aiLoading, error: aiError, fetchInsights } = useInsights()
const aiRequested = ref(false)

function loadAi() {
  aiRequested.value = true
  fetchInsights(props.machineId, days.value)
}

const aiSwaps = computed(() =>
  (insights.value?.recommendations ?? []).filter(r => r.type === 'product_swap' || r.type === 'remove_slot'),
)
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2 class="text-base font-medium">{{ t('analysis.title') }}</h2>
        <p class="text-sm text-muted-foreground">{{ t('analysis.subtitle') }}</p>
      </div>
      <div class="flex items-center gap-1 rounded-md border p-0.5">
        <button
          v-for="d in PERIODS"
          :key="d"
          class="rounded px-2.5 py-1 text-xs font-medium transition-colors"
          :class="days === d ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted'"
          @click="changePeriod(d)"
        >
          {{ t('analysis.days', { count: d }) }}
        </button>
      </div>
    </div>

    <!-- Loading / error / empty -->
    <div v-if="loading && slots.length === 0" class="flex items-center justify-center py-12 text-muted-foreground">
      <IconLoader2 class="size-5 animate-spin" />
    </div>
    <div v-else-if="error" class="rounded-md border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
      {{ error }}
    </div>
    <div v-else-if="slots.length === 0" class="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      {{ t('analysis.noTrays') }}
    </div>

    <template v-else>
      <div v-if="queuedMessage" class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-green-600/40 bg-green-500/10 p-3 text-sm">
        <span>{{ queuedMessage }}</span>
        <a href="?tab=slots" class="text-sm font-medium underline">{{ t('analysis.openRequestLink') }}</a>
      </div>
      <!-- KPI strip (counts of distinct products) -->
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div class="rounded-xl border bg-card p-3">
          <p class="text-xs text-muted-foreground">{{ t('analysis.tier.dead') }}</p>
          <p class="text-xl font-semibold tabular-nums">{{ tierCounts.dead }}</p>
        </div>
        <div class="rounded-xl border bg-card p-3">
          <p class="text-xs text-muted-foreground">{{ t('analysis.tier.weak') }}</p>
          <p class="text-xl font-semibold tabular-nums">{{ tierCounts.weak }}</p>
        </div>
        <div class="rounded-xl border bg-card p-3">
          <p class="text-xs text-muted-foreground">{{ t('analysis.tier.testing') }}</p>
          <p class="text-xl font-semibold tabular-nums">{{ tierCounts.testing }}</p>
        </div>
        <div class="rounded-xl border bg-card p-3">
          <p class="text-xs text-muted-foreground">{{ t('analysis.opportunity') }}</p>
          <p class="text-xl font-semibold tabular-nums">{{ formatCurrency(lostRevenuePotential, locale) }}</p>
        </div>
      </div>

      <!-- Layout grid -->
      <div class="rounded-xl border bg-card p-3 sm:p-4">
        <MachineLayoutGrid
          :slots="slots"
          :row-count="rowCount"
          :selected-tray-id="targetTrayId"
          @select="openSlot"
        />
        <!-- Legend (slot counts) -->
        <div class="mt-4 flex flex-wrap gap-x-4 gap-y-1.5">
          <div v-for="tier in TIER_ORDER" :key="tier" class="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span class="size-2.5 rounded-full" :class="tierDot[tier]" />
            {{ t(`analysis.tier.${tier}`) }} ({{ slotTierCounts[tier] }})
          </div>
        </div>
      </div>

      <!-- Weak products list -->
      <div>
        <h3 class="mb-2 text-sm font-medium">{{ t('analysis.weakProducts') }}</h3>
        <p v-if="weakProducts.length === 0" class="text-sm text-muted-foreground">{{ t('analysis.noWeakProducts') }}</p>
        <div v-else class="rounded-xl border bg-card divide-y">
          <button
            v-for="product in weakProducts"
            :key="product.product_id"
            class="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/50"
            @click="openProduct(product)"
          >
            <img
              v-if="product.image_url"
              :src="product.image_url"
              :alt="product.name"
              class="h-9 w-9 shrink-0 rounded object-cover"
            />
            <div v-else class="flex h-9 w-9 shrink-0 items-center justify-center rounded bg-muted text-xs text-muted-foreground">
              ?
            </div>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium">{{ product.name }}</p>
              <p class="text-xs text-muted-foreground">
                {{ t('analysis.unitsShort', { n: product.units_sold }) }} ·
                {{ Math.round(product.sell_through_pct) }}%
                <template v-if="product.tenure_days != null"> · {{ t('analysis.inMachineFor', { days: product.tenure_days }) }}</template>
              </p>
              <div class="mt-1 flex flex-wrap gap-1">
                <span
                  v-for="s in product.slots"
                  :key="s"
                  class="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium tabular-nums text-muted-foreground"
                >{{ t('analysis.slot') }} {{ s }}</span>
              </div>
            </div>
            <Badge :variant="product.tier === 'dead' ? 'destructive' : 'secondary'">
              {{ t(`analysis.tier.${product.tier}`) }}
            </Badge>
            <IconArrowRight class="size-4 shrink-0 text-muted-foreground" />
          </button>
        </div>
      </div>

      <!-- AI recommendations -->
      <div class="rounded-xl border bg-card p-4">
        <div class="flex items-center justify-between gap-2">
          <h3 class="flex items-center gap-1.5 text-sm font-medium">
            <IconSparkles class="size-4 text-primary" />
            {{ t('analysis.aiRecommendations') }}
          </h3>
          <button
            v-if="!aiRequested"
            class="inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-xs font-medium transition-colors hover:bg-muted"
            @click="loadAi"
          >
            {{ t('analysis.loadAi') }}
          </button>
        </div>
        <div v-if="aiLoading" class="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
          <IconLoader2 class="size-4 animate-spin" /> {{ t('analysis.aiLoading') }}
        </div>
        <p v-else-if="aiError" class="mt-3 text-sm text-destructive">{{ aiError }}</p>
        <template v-else-if="aiRequested && insights">
          <p v-if="insights.summary" class="mt-3 text-sm text-muted-foreground">{{ insights.summary }}</p>
          <p v-if="aiSwaps.length === 0" class="mt-3 text-sm text-muted-foreground">{{ t('analysis.noAiSwaps') }}</p>
          <div v-else class="mt-3 space-y-2">
            <div v-for="(rec, i) in aiSwaps" :key="i" class="rounded-lg border p-3">
              <div class="flex items-center gap-2">
                <Badge :variant="priorityVariant(rec.priority)">{{ t(recommendationTypeLabel(rec.type)) }}</Badge>
                <span v-if="rec.item_number != null" class="text-xs text-muted-foreground">{{ t('analysis.slot') }} {{ rec.item_number }}</span>
              </div>
              <p class="mt-1.5 text-sm font-medium">{{ rec.title }}</p>
              <p class="text-sm text-muted-foreground">{{ rec.detail }}</p>
            </div>
          </div>
        </template>
      </div>
    </template>

    <!-- Detail sheet -->
    <Sheet v-model:open="sheetOpen">
      <SheetContent class="w-full overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>
            {{ selectedProduct?.name ?? t('analysis.emptySlot') }}
          </SheetTitle>
          <SheetDescription>
            <template v-if="targetSlotNumber != null">{{ t('analysis.slot') }} {{ targetSlotNumber }}</template>
          </SheetDescription>
        </SheetHeader>

        <div class="mt-4 space-y-5 px-1">
          <!-- Product performance (machine-wide) -->
          <template v-if="selectedProduct">
            <div class="grid grid-cols-2 gap-3">
              <div class="rounded-lg border p-3">
                <p class="text-xs text-muted-foreground">{{ t('analysis.unitsSold') }}</p>
                <p class="text-lg font-semibold tabular-nums">{{ selectedProduct.units_sold }}</p>
              </div>
              <div class="rounded-lg border p-3">
                <p class="text-xs text-muted-foreground">{{ t('analysis.revenue') }}</p>
                <p class="text-lg font-semibold tabular-nums">{{ formatCurrency(selectedProduct.revenue_eur, locale) }}</p>
              </div>
              <div class="rounded-lg border p-3">
                <p class="text-xs text-muted-foreground">{{ t('analysis.sellThrough') }}</p>
                <p class="text-lg font-semibold tabular-nums">{{ Math.round(selectedProduct.sell_through_pct) }}%</p>
              </div>
              <div class="rounded-lg border p-3">
                <p class="text-xs text-muted-foreground">{{ t('analysis.avgDaily') }}</p>
                <p class="text-lg font-semibold tabular-nums">{{ selectedProduct.avg_daily_units }}</p>
              </div>
            </div>

            <p v-if="selectedProduct.tenure_days != null" class="text-xs text-muted-foreground">
              {{ t('analysis.inMachineFor', { days: selectedProduct.tenure_days }) }}
            </p>

            <!-- Slots this product occupies -->
            <div v-if="selectedProduct.slots.length > 1" class="flex flex-wrap items-center gap-1.5">
              <span class="text-xs text-muted-foreground">{{ t('analysis.occupiesSlots') }}:</span>
              <span
                v-for="s in selectedProduct.slots"
                :key="s"
                class="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium tabular-nums"
              >{{ s }}</span>
            </div>

            <div v-if="selectedProduct.tier === 'testing'" class="rounded-lg border border-blue-400/40 bg-blue-400/10 p-3 text-sm">
              {{ t('analysis.testingHint') }}
            </div>
          </template>

          <p v-if="targetTrayId && pendingTrayIds.has(targetTrayId)" class="rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
            {{ t('analysis.slotHasRequest') }}
          </p>
          <p v-if="applyError" class="text-sm text-destructive">{{ applyError }}</p>

          <!-- Empty slot: offer products to fill it -->
          <p v-else class="text-sm text-muted-foreground">{{ t('analysis.emptySlotHint') }}</p>

          <!-- Replacement / fill suggestions -->
          <div v-if="sheetSuggestions.length > 0">
            <h4 class="mb-2 text-sm font-medium">{{ selectedProduct ? t('analysis.suggestions') : t('analysis.fillSuggestions') }}</h4>
            <div class="space-y-2">
              <div
                v-for="sug in sheetSuggestions"
                :key="sug.product_id"
                class="flex items-center gap-3 rounded-lg border p-2.5"
              >
                <img
                  v-if="sug.image_url"
                  :src="sug.image_url"
                  :alt="sug.name"
                  class="h-9 w-9 shrink-0 rounded object-cover"
                />
                <div v-else class="flex h-9 w-9 shrink-0 items-center justify-center rounded bg-muted text-muted-foreground">
                  <IconFlask v-if="sug.kind === 'newcomer'" class="size-4" />
                  <IconSparkles v-else class="size-4" />
                </div>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">{{ sug.name }}</p>
                  <p class="text-xs text-muted-foreground">
                    <span v-if="sug.kind === 'bestseller'">{{ t('analysis.perDay', { n: sug.velocity.toFixed(1) }) }}</span>
                    <span v-else class="text-blue-600 dark:text-blue-400">{{ t('analysis.neverSold') }}</span>
                  </p>
                </div>
                <button
                  v-if="props.isAdmin && targetTrayId"
                  class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                  :disabled="applyingProductId !== null"
                  @click="handleApply(sug)"
                >
                  <IconLoader2 v-if="applyingProductId === sug.product_id" class="size-3.5 animate-spin" />
                  {{ applyingProductId === sug.product_id ? t('analysis.applying') : t('analysis.apply') }}
                </button>
              </div>
            </div>
            <p v-if="props.isAdmin && targetSlotNumber != null" class="mt-2 text-xs text-muted-foreground">
              {{ t('analysis.swapHint', { slot: targetSlotNumber }) }}
            </p>
          </div>

          <!-- Full-catalogue search (admin). Decoupled from the suggestions block
               so it also appears on an empty slot that has no curated picks. -->
          <div v-if="props.isAdmin && targetTrayId">
            <h4 class="mb-2 text-sm font-medium">{{ t('analysis.searchHeading') }}</h4>
            <SearchInput v-model="productQuery" :placeholder="t('analysis.searchPlaceholder')" />

            <div v-if="searchResults.results.length > 0" class="mt-2 space-y-2">
              <div
                v-for="p in searchResults.results"
                :key="p.product_id"
                class="flex items-center gap-3 rounded-lg border p-2.5"
              >
                <img
                  v-if="p.image_url"
                  :src="p.image_url"
                  :alt="p.name"
                  class="h-9 w-9 shrink-0 rounded object-cover"
                />
                <div v-else class="h-9 w-9 shrink-0 rounded bg-muted" />
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">{{ p.name }}</p>
                  <p class="text-xs text-muted-foreground">
                    <span v-if="p.inMachineSlots.length > 0" class="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium">
                      {{ t('analysis.alreadyInMachine', { slots: p.inMachineSlots.join(', ') }) }}
                    </span>
                    <span v-else-if="p.velocity > 0">{{ t('analysis.perDay', { n: p.velocity.toFixed(1) }) }}</span>
                    <span v-else class="text-blue-600 dark:text-blue-400">{{ t('analysis.neverSold') }}</span>
                  </p>
                </div>
                <button
                  class="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                  :disabled="applyingProductId !== null"
                  @click="applyProduct(p.product_id)"
                >
                  <IconLoader2 v-if="applyingProductId === p.product_id" class="size-3.5 animate-spin" />
                  {{ applyingProductId === p.product_id ? t('analysis.applying') : t('analysis.apply') }}
                </button>
              </div>
              <p v-if="searchResults.truncated" class="text-xs text-muted-foreground">
                {{ t('analysis.moreResults') }}
              </p>
            </div>
            <p v-else-if="productQuery.trim()" class="mt-2 text-sm text-muted-foreground">
              {{ t('analysis.noProductsFound') }}
            </p>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  </div>
</template>
