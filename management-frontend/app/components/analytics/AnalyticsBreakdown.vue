<script setup lang="ts">
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatCurrency } from '@/lib/utils'
import {
  avgDailyValue, deltaPct, metricValue, prevMetricValue, type BreakdownRow,
} from '~/lib/analytics'

const emit = defineEmits<{ select: [row: BreakdownRow] }>()

const { t } = useI18n()
const { dimension, metric, sortedRows, loadingRows, loadBreakdown } = useAnalytics()

watch(dimension, () => { loadBreakdown() })

const maxValue = computed(() =>
  Math.max(0, ...sortedRows.value.map(r => metricValue(r, metric.value))))

/** Per-row delta against the previous period, precomputed so the template does
 *  not repeat the metric branch for every row. */
const rowDeltas = computed(() => new Map(sortedRows.value.map(row => [
  row.key ?? row.label,
  deltaPct(metricValue(row, metric.value), prevMetricValue(row, metric.value)),
])))

function display(value: number) {
  return metric.value === 'units' ? String(Math.round(value)) : formatCurrency(value)
}

function subtitle(row: BreakdownRow) {
  const avg = avgDailyValue(row, metric.value)
  const parts = [t('analytics.perDay', {
    value: metric.value === 'units' ? avg.toFixed(1) : formatCurrency(avg),
  })]
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

function onRowClick(row: BreakdownRow) {
  if (dimension.value !== 'product' || !row.key) return
  emit('select', row)
}
</script>

<template>
  <Card>
    <CardHeader class="gap-3 pb-2">
      <CardDescription>{{ t('analytics.breakdown') }}</CardDescription>
      <Tabs v-model="dimension">
        <TabsList>
          <TabsTrigger value="product">{{ t('analytics.products') }}</TabsTrigger>
          <TabsTrigger value="category">{{ t('analytics.categories') }}</TabsTrigger>
          <TabsTrigger value="machine">{{ t('analytics.machines') }}</TabsTrigger>
        </TabsList>
      </Tabs>
    </CardHeader>

    <CardContent>
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
          :class="dimension === 'product' && row.key ? 'hover:bg-accent cursor-pointer' : 'cursor-default'"
          @click="onRowClick(row)"
        >
          <span
            class="bg-primary/10 absolute inset-y-0 left-0"
            :style="{ width: maxValue > 0 ? `${(metricValue(row, metric) / maxValue) * 100}%` : '0%' }"
          />
          <span class="relative flex min-w-0 flex-1 items-center gap-2">
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
