<script setup lang="ts">
import BarChart, { type BarChartPoint } from '@/components/charts/BarChart.vue'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { average } from '~/lib/barChart'
import { chartBucket, metricValue } from '~/lib/analytics'
import { formatCurrency } from '~/lib/utils'

const { t, locale } = useI18n()
const { summary, metric, bucketedDaily } = useAnalytics()

const bucket = computed(() => chartBucket(summary.value?.range.days ?? 30))

const money = (v: number) => formatCurrency(v, locale.value)
const formatValue = (v: number) =>
  metric.value === 'units' ? String(Math.round(v * 10) / 10) : money(v)

// Same tooltip as the iOS trend chart: all three figures for the bucket,
// whichever metric the bars show.
const points = computed<BarChartPoint[]>(() =>
  bucketedDaily.value.map(p => ({
    date: new Date(`${p.day}T00:00:00`),
    value: metricValue(p, metric.value),
    rows: [
      { label: t('charts.revenue'), value: money(p.revenue_gross) },
      { label: t('charts.units'), value: String(p.units) },
      { label: t('charts.grossProfit'), value: money(p.gross_profit) },
    ],
  })))

// Average per drawn bucket, so with weekly bars it is the weekly average.
const avg = computed(() => average(points.value.map(p => p.value)))
</script>

<template>
  <Card class="gap-3">
    <CardHeader class="flex flex-row items-center justify-between space-y-0 pb-0">
      <CardDescription class="text-xs uppercase tracking-wide">{{ t('analytics.trend') }}</CardDescription>
      <span v-if="avg > 0" class="text-xs font-semibold tabular-nums" style="color: var(--chart-average)">
        Ø {{ formatValue(avg) }}
      </span>
    </CardHeader>
    <CardContent>
      <p v-if="!points.length" class="text-muted-foreground py-8 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <BarChart
        v-else
        :data="points"
        :bucket="bucket"
        :format-value="formatValue"
        :height="200"
      />
    </CardContent>
  </Card>
</template>
