<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { IconAdjustments, IconExternalLink, IconShoppingCartPlus, IconX } from '@tabler/icons-vue'
import { formatDate } from '@/lib/utils'
import { getProductImageUrl } from '@/composables/useProducts'
import {
  expirationStatus, expirationBadgeClass,
  type StockBatch, type WarehouseProductSummary,
} from '@/composables/useWarehouse'
import type { ManualEntry, OrderSupplier } from '@/lib/orderSuggestion'

/**
 * Product detail on the warehouse stock tab: batches with their corrections,
 * the minimum stock and the discontinued flag. These used to live in the
 * expandable stock rows and in two tables under settings.
 */
const props = defineProps<{
  open: boolean
  summary: WarehouseProductSummary | null
  batches: StockBatch[]
  machineStock: number
  assigned: boolean
  isAdmin: boolean
  /** Units on open purchase orders. */
  onOrder?: number
  /** The suggestion lists this product on its own. */
  autoOrder?: boolean
  manualEntry?: ManualEntry | null
  suppliers?: OrderSupplier[]
  /** Preferred supplier, else the supplier of the latest purchase price. */
  supplierId?: string | null
  unitSize?: number
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  adjust: [batch: StockBatch]
  saveMinStock: [value: number]
  toggleDiscontinued: [value: boolean]
  addToOrderList: []
  removeFromOrderList: []
  saveOrdering: [updates: { preferred_supplier_id?: string | null; order_unit_size?: number }]
}>()

const { t, locale } = useI18n()

const minStock = ref(0)
const savingMin = ref(false)
watch(() => [props.summary?.product_id, props.summary?.min_stock], () => {
  minStock.value = props.summary?.min_stock ?? 0
}, { immediate: true })

const minDirty = computed(() => (props.summary?.min_stock ?? 0) !== minStock.value)

const unitSizeDraft = ref(1)
watch(() => [props.summary?.product_id, props.unitSize], () => {
  unitSizeDraft.value = props.unitSize ?? 1
}, { immediate: true })

function saveUnitSize() {
  const n = Math.max(1, Math.floor(Number(unitSizeDraft.value) || 1))
  unitSizeDraft.value = n
  if (n !== (props.unitSize ?? 1)) emit('saveOrdering', { order_unit_size: n })
}

function onSupplierChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  emit('saveOrdering', { preferred_supplier_id: v || null })
}

const supplierName = computed(() => props.suppliers?.find(s => s.id === props.supplierId)?.name ?? null)

async function saveMin() {
  savingMin.value = true
  try {
    emit('saveMinStock', Math.max(0, Math.floor(Number(minStock.value) || 0)))
  } finally {
    savingMin.value = false
  }
}
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent class="w-full overflow-y-auto sm:max-w-md">
      <template v-if="summary">
        <SheetHeader>
          <SheetTitle class="flex items-center gap-2 pr-6">
            <img v-if="summary.product_image_path" :src="getProductImageUrl(summary.product_image_path)" class="size-8 rounded object-cover" alt="" />
            <span>{{ summary.product_name }}</span>
          </SheetTitle>
          <SheetDescription>
            <NuxtLink :to="`/products/${summary.product_id}`" class="inline-flex items-center gap-1 hover:underline">
              {{ t('warehouse.openProduct') }}
              <IconExternalLink class="size-3.5" />
            </NuxtLink>
          </SheetDescription>
        </SheetHeader>

        <div class="flex flex-col gap-5 px-4 pb-6">
          <div class="grid grid-cols-3 gap-2">
            <div class="rounded-md border px-3 py-2">
              <div class="text-xs text-muted-foreground">{{ t('warehouse.inWarehouse') }}</div>
              <div class="text-lg font-semibold tabular-nums">{{ summary.total_quantity }}</div>
            </div>
            <div class="rounded-md border px-3 py-2">
              <div class="text-xs text-muted-foreground">{{ t('warehouse.inMachines') }}</div>
              <div class="text-lg font-semibold tabular-nums">{{ assigned ? machineStock : '—' }}</div>
            </div>
            <div class="rounded-md border px-3 py-2">
              <div class="text-xs text-muted-foreground">{{ t('warehouse.estimatedRange') }}</div>
              <div class="text-lg font-semibold tabular-nums">
                {{ summary.estimated_days_remaining !== null ? t('warehouse.daysRemaining', { days: summary.estimated_days_remaining }) : '—' }}
              </div>
            </div>
          </div>
          <p v-if="summary.avg_daily_sales > 0" class="-mt-3 text-xs text-muted-foreground">
            {{ t('warehouse.avgDailySales', { count: summary.avg_daily_sales.toLocaleString(locale, { maximumFractionDigits: 1 }) }) }}
          </p>

          <section class="flex flex-col gap-2">
            <p v-if="(onOrder ?? 0) > 0" class="text-sm text-violet-700 dark:text-violet-400">
              {{ t('warehouse.order.onTheWayHint', { count: onOrder }) }}
            </p>
            <div v-if="manualEntry" class="flex flex-wrap items-center gap-2">
              <span class="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-950/40 dark:text-blue-400">
                {{ t('warehouse.order.manualOnList', { name: manualEntry.added_by_name || '?', via: t(`warehouse.order.via.${manualEntry.added_via}`) }) }}
              </span>
              <button class="inline-flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground hover:bg-muted hover:text-foreground" @click="emit('removeFromOrderList')">
                <IconX class="size-3.5" />
                {{ t('warehouse.order.removeFromList') }}
              </button>
            </div>
            <div v-else-if="autoOrder" class="flex flex-wrap items-center gap-2">
              <span class="inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-400">
                {{ t('warehouse.order.autoOnList') }}
              </span>
            </div>
            <button
              v-else-if="!summary.discontinued"
              class="inline-flex h-8 w-fit items-center gap-1.5 rounded-md border px-3 text-sm font-medium hover:bg-muted"
              @click="emit('addToOrderList')"
            >
              <IconShoppingCartPlus class="size-4" />
              {{ t('warehouse.order.addToList') }}
            </button>
          </section>

          <section v-if="isAdmin" class="grid grid-cols-2 gap-3">
            <label class="flex flex-col gap-1 text-sm">
              <span class="font-semibold">{{ t('warehouse.order.preferredSupplier') }}</span>
              <select
                class="h-9 rounded-md border border-input bg-background px-2 text-sm"
                :value="supplierId ?? ''"
                @change="onSupplierChange"
              >
                <option value="">{{ t('warehouse.order.noSupplier') }}</option>
                <option v-for="s in suppliers ?? []" :key="s.id" :value="s.id">{{ s.name }}</option>
              </select>
            </label>
            <label class="flex flex-col gap-1 text-sm">
              <span class="font-semibold">{{ t('warehouse.order.unitSize') }}</span>
              <input
                v-model.number="unitSizeDraft"
                type="number"
                min="1"
                class="h-9 rounded-md border border-input bg-background px-3 text-sm"
                @change="saveUnitSize"
              />
            </label>
          </section>
          <p v-else-if="supplierName" class="text-sm">
            <span class="text-muted-foreground">{{ t('warehouse.order.preferredSupplier') }}:</span> {{ supplierName }}
          </p>

          <section class="flex flex-col gap-2">
            <h3 class="text-sm font-semibold">{{ t('warehouse.batchesInWarehouse') }}</h3>
            <p v-if="batches.length === 0" class="text-sm text-muted-foreground">{{ t('warehouse.noBatches') }}</p>
            <div
              v-for="batch in batches"
              :key="batch.id"
              class="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
            >
              <div class="min-w-0">
                <div class="font-medium tabular-nums">{{ t('warehouse.unitsShort', { count: batch.quantity }) }}</div>
                <div class="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  <span
                    v-if="batch.expiration_date"
                    :class="[expirationBadgeClass(expirationStatus(batch.expiration_date)), 'inline-flex items-center rounded-full px-1.5 py-0.5 font-medium']"
                  >
                    {{ t('warehouse.mhd', { date: formatDate(batch.expiration_date, locale) }) }}
                  </span>
                  <span>{{ batch.batch_number || t('warehouse.noBatchNumber') }}</span>
                </div>
              </div>
              <button
                v-if="isAdmin"
                class="inline-flex h-7 shrink-0 items-center gap-1 rounded-md border px-2 text-xs hover:bg-muted"
                @click="emit('adjust', batch)"
              >
                <IconAdjustments class="size-3.5" />
                {{ t('warehouse.adjust') }}
              </button>
            </div>
          </section>

          <section v-if="isAdmin" class="flex flex-col gap-2">
            <label for="sheet-min-stock" class="text-sm font-semibold">{{ t('warehouse.minStockCol') }}</label>
            <div class="flex gap-2">
              <input
                id="sheet-min-stock"
                v-model.number="minStock"
                type="number"
                min="0"
                class="h-9 w-28 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                @keydown.enter="minDirty && saveMin()"
              />
              <button
                class="h-9 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                :disabled="!minDirty || savingMin"
                @click="saveMin"
              >
                {{ t('common.save') }}
              </button>
            </div>
            <p class="text-xs text-muted-foreground">{{ t('warehouse.minStockHint') }}</p>
          </section>
          <div v-else class="text-sm">
            <span class="text-muted-foreground">{{ t('warehouse.minStockCol') }}:</span> {{ summary.min_stock || '—' }}
          </div>

          <section v-if="isAdmin" class="flex items-start justify-between gap-4 border-t pt-4">
            <div>
              <div class="text-sm font-semibold">{{ t('warehouse.discontinuedCol') }}</div>
              <p class="text-xs text-muted-foreground">{{ t('warehouse.discontinuedHint') }}</p>
            </div>
            <button
              role="switch"
              :aria-checked="summary.discontinued"
              :aria-label="t('warehouse.discontinuedCol')"
              class="relative mt-0.5 inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors"
              :class="summary.discontinued ? 'bg-primary' : 'bg-input'"
              @click="emit('toggleDiscontinued', !summary.discontinued)"
            >
              <span
                class="inline-block size-4 rounded-full bg-background shadow transition-transform"
                :class="summary.discontinued ? 'translate-x-4' : 'translate-x-0.5'"
              />
            </button>
          </section>
        </div>
      </template>
    </SheetContent>
  </Sheet>
</template>
