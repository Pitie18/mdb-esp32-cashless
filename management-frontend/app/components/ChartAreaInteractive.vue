<script setup lang="ts">
import BarChart, { type BarChartPoint } from '@/components/charts/BarChart.vue'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { formatCurrency } from '@/lib/utils'

const { t, locale } = useI18n()

interface SalesByDay {
  date: Date
  total: number
  /** Number of sales that day — shown as an extra tooltip line when given. */
  count?: number
}

const props = withDefaults(defineProps<{
  data: SalesByDay[]
  title?: string
  description?: string
  /** `currency`: `total` is EUR revenue. `units`: `total` is a piece count. */
  kind?: 'currency' | 'units'
  color?: 'blue' | 'purple'
  /** Tooltip label of the `count` line. */
  countLabel?: string
}>(), {
  title: undefined,
  description: undefined,
  kind: 'currency',
  color: 'blue',
  countLabel: undefined,
})

const formatValue = (v: number) =>
  props.kind === 'currency' ? formatCurrency(v, locale.value) : String(Math.round(v * 10) / 10)

const valueLabel = computed(() => (props.kind === 'currency' ? t('charts.revenue') : t('charts.units')))

const points = computed<BarChartPoint[]>(() => props.data.map(d => ({
  date: d.date,
  value: d.total,
  rows: [
    { label: valueLabel.value, value: formatValue(d.total) },
    ...(d.count == null ? [] : [{ label: props.countLabel ?? t('charts.sales'), value: String(d.count) }]),
  ],
})))

const sum = computed(() => props.data.reduce((s, d) => s + d.total, 0))
</script>

<template>
  <Card class="gap-4">
    <CardHeader class="flex flex-row items-start gap-2 space-y-0">
      <div class="grid flex-1 gap-1">
        <CardTitle>{{ title ?? t('dashboard.dailySales') }}</CardTitle>
        <CardDescription>
          {{ description ?? t('dashboard.revenuePerDay') }}
        </CardDescription>
      </div>
      <div class="flex flex-col items-end gap-2">
        <slot name="actions" />
        <span
          v-if="data.length"
          class="text-sm font-semibold tabular-nums"
          :style="{ color: color === 'purple' ? 'var(--chart-purple)' : 'var(--chart-blue)' }"
        >{{ formatValue(sum) }}</span>
      </div>
    </CardHeader>
    <CardContent class="px-4 pb-2 sm:px-6">
      <div v-if="data.length === 0" class="flex h-[220px] items-center justify-center rounded-xl bg-muted/50 text-sm text-muted-foreground">
        {{ t('dashboard.noSalesData') }}
      </div>
      <BarChart
        v-else
        :data="points"
        :format-value="formatValue"
        :color="color"
        :height="220"
      />
    </CardContent>
  </Card>
</template>
