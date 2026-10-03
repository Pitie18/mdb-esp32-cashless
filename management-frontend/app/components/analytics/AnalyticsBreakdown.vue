<script setup lang="ts">
import {
  IconBuildingStore, IconCategory, IconPackage,
  IconSortAscending, IconSortDescending, IconX,
} from '@tabler/icons-vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
// getProductImageUrl is a module-level export of useProducts, not part of the
// object useProducts() returns.
import { getProductImageUrl } from '@/composables/useProducts'
import { formatCurrency } from '@/lib/utils'
import {
  avgDailyValue, deltaPct, metricShares, metricValue, prevMetricValue, type BreakdownRow,
} from '~/lib/analytics'

const emit = defineEmits<{ select: [row: BreakdownRow] }>()

const { t } = useI18n()
const {
  dimension, metric, sortDirection, sortedRows, loadingRows, loadBreakdown, drillDown,
  activeMachineLabel, activeCategoryLabel, clearMachineFilter, clearCategoryFilter,
} = useAnalytics()

// drillDown swaps the dimension itself and reloads once; this watcher would
// otherwise fire a second breakdown request for the same state.
let suppressDimensionWatch = false
watch(dimension, () => {
  if (suppressDimensionWatch) return
  loadBreakdown()
})

const maxValue = computed(() =>
  Math.max(0, ...sortedRows.value.map(r => metricValue(r, metric.value))))

/** Per-row delta against the previous period, precomputed so the template does
 *  not repeat the metric branch for every row. */
const rowDeltas = computed(() => new Map(sortedRows.value.map(row => [
  row.key ?? row.label,
  deltaPct(metricValue(row, metric.value), prevMetricValue(row, metric.value)),
])))

/** Share of the window total for the selected metric — so switching to units
 *  reports unit shares, not the revenue-based share_pct from the RPC. */
const shares = computed(() => metricShares(sortedRows.value, metric.value))

function toggleSort() {
  sortDirection.value = sortDirection.value === 'desc' ? 'asc' : 'desc'
}

function display(value: number) {
  return metric.value === 'units' ? String(Math.round(value)) : formatCurrency(value)
}

function subtitle(row: BreakdownRow) {
  const avg = avgDailyValue(row, metric.value)
  const share = shares.value.get(row.key ?? row.label)
  const parts: string[] = []
  if (share !== undefined) parts.push(`${share.toFixed(1)} %`)
  parts.push(t('analytics.perDay', {
    value: metric.value === 'units' ? avg.toFixed(1) : formatCurrency(avg),
  }))
  if (metric.value !== 'units') parts.push(t('analytics.nPieces', row.units))
  if (!row.has_cost && metric.value === 'grossProfit') parts.push(t('analytics.noPurchasePrice'))
  if (dimension.value === 'product' && row.machine_count) {
    parts.push(t('analytics.nMachines', row.machine_count))
  } else if (dimension.value !== 'product' && row.product_count) {
    parts.push(t('analytics.nProducts', row.product_count))
  }
  return parts.join(' · ')
}

const abcColor = (cls: string) =>
  cls === 'A'
    ? 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-400'
    : cls === 'B'
      ? 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-400'
      : 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-400'

async function onRowClick(row: BreakdownRow) {
  // The aggregate "Unknown" row has no key to filter or look up.
  if (!row.key) return
  if (dimension.value === 'product') {
    emit('select', row)
    return
  }
  suppressDimensionWatch = true
  try {
    await drillDown(row)
  } finally {
    suppressDimensionWatch = false
  }
}
</script>

<template>
  <Card>
    <CardHeader class="gap-3 pb-2">
      <div class="flex items-center justify-between gap-2">
        <CardDescription>{{ t('analytics.breakdown') }}</CardDescription>
        <Button
          variant="ghost" size="sm" class="gap-1.5 text-xs"
          @click="toggleSort"
        >
          <component
            :is="sortDirection === 'desc' ? IconSortDescending : IconSortAscending"
            class="size-4"
          />
          {{ t(sortDirection === 'desc' ? 'analytics.sortDescending' : 'analytics.sortAscending') }}
        </Button>
      </div>
      <Tabs v-model="dimension">
        <TabsList>
          <TabsTrigger value="product">{{ t('analytics.products') }}</TabsTrigger>
          <TabsTrigger value="category">{{ t('analytics.categories') }}</TabsTrigger>
          <TabsTrigger value="machine">{{ t('analytics.machines') }}</TabsTrigger>
        </TabsList>
      </Tabs>
    </CardHeader>

    <CardContent>
      <!-- The active filter repeated right above the rows. The filter bar sits
           far enough up the page that a drill-down changes it out of sight,
           which is exactly when knowing about it matters most. -->
      <div
        v-if="activeCategoryLabel || activeMachineLabel"
        class="mb-3 flex flex-wrap items-center gap-2"
      >
        <span class="text-muted-foreground text-xs">{{ t('analytics.filtered') }}</span>
        <button
          v-if="activeCategoryLabel" type="button"
          class="bg-primary text-primary-foreground flex items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold"
          @click="clearCategoryFilter"
        >
          <IconCategory class="size-3" />
          {{ activeCategoryLabel }}
          <IconX class="size-3" />
        </button>
        <button
          v-if="activeMachineLabel" type="button"
          class="bg-primary text-primary-foreground flex items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold"
          @click="clearMachineFilter"
        >
          <IconBuildingStore class="size-3" />
          {{ activeMachineLabel }}
          <IconX class="size-3" />
        </button>
      </div>

      <p v-if="loadingRows && !sortedRows.length" class="text-muted-foreground py-8 text-center text-sm">
        …
      </p>
      <p v-else-if="!sortedRows.length" class="text-muted-foreground py-8 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-else class="space-y-0.5">
        <button
          v-for="row in sortedRows" :key="row.key ?? row.label"
          type="button"
          class="relative flex w-full items-center gap-3 overflow-hidden rounded-md px-2 py-2 text-left"
          :class="row.key ? 'hover:bg-accent cursor-pointer' : 'cursor-default'"
          @click="onRowClick(row)"
        >
          <span
            class="bg-primary/10 absolute inset-y-0 left-0"
            :style="{ width: maxValue > 0 ? `${(metricValue(row, metric) / maxValue) * 100}%` : '0%' }"
          />
          <span class="relative flex min-w-0 flex-1 items-center gap-2">
            <!-- Products carry a thumbnail, the way the iOS breakdown does.
                 Categories and machines have no image to show. -->
            <template v-if="dimension === 'product'">
              <img
                v-if="row.image_path"
                :src="getProductImageUrl(row.image_path)"
                :alt="row.label"
                class="size-8 shrink-0 rounded object-cover"
              >
              <span
                v-else
                class="bg-muted text-muted-foreground flex size-8 shrink-0 items-center justify-center rounded"
              >
                <IconPackage class="size-4" />
              </span>
            </template>
            <span
              v-if="dimension === 'product'"
              class="rounded px-1 py-0.5 text-[10px] font-bold"
              :class="abcColor(row.abc_class)"
            >{{ row.abc_class }}</span>
            <span class="min-w-0">
              <span class="block truncate text-sm font-medium">{{ row.label }}</span>
              <span class="text-muted-foreground block truncate text-xs">{{ subtitle(row) }}</span>
            </span>
          </span>
          <span class="relative text-right">
            <span class="block text-sm font-semibold tabular-nums">
              {{ display(metricValue(row, metric)) }}
            </span>
            <span
              v-if="rowDeltas.get(row.key ?? row.label) !== null"
              class="block text-xs font-semibold"
              :class="(rowDeltas.get(row.key ?? row.label) ?? 0) >= 0 ? 'text-green-600' : 'text-red-600'"
            >
              {{ (rowDeltas.get(row.key ?? row.label) ?? 0).toFixed(0) }} %
            </span>
          </span>
        </button>
      </div>
    </CardContent>
  </Card>
</template>
