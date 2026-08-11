<script setup lang="ts">
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatCurrency } from '@/lib/utils'
import { deltaPct, metricValue, type AnalyticsMetric, type AnalyticsTotals } from '~/lib/analytics'

const { t } = useI18n()
const { summary, metric, previousRangeLabel } = useAnalytics()

const EMPTY: AnalyticsTotals = {
  units: 0, revenue_gross: 0, revenue_net: 0, cost_net: 0, gross_profit: 0,
  avg_ticket: 0, avg_daily_units: 0, avg_daily_revenue: 0, avg_daily_gross_profit: 0,
}

const cards = computed(() => {
  const totals = summary.value?.totals ?? EMPTY
  const previous = summary.value?.previous ?? EMPTY
  const defs: { key: AnalyticsMetric; label: string; money: boolean }[] = [
    { key: 'revenue', label: t('analytics.revenue'), money: true },
    { key: 'units', label: t('analytics.units'), money: false },
    { key: 'grossProfit', label: t('analytics.grossProfit'), money: true },
  ]
  return defs.map(def => {
    const value = metricValue(totals, def.key)
    return {
      ...def,
      display: def.money ? formatCurrency(value) : String(value),
      delta: deltaPct(value, metricValue(previous, def.key)),
    }
  })
})
</script>

<template>
  <div class="space-y-1">
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <Card
        v-for="card in cards" :key="card.key"
        :class="metric === card.key ? 'border-primary' : ''"
      >
        <CardHeader class="pb-2">
          <CardDescription>{{ card.label }}</CardDescription>
          <CardTitle class="text-2xl tabular-nums">{{ card.display }}</CardTitle>
        </CardHeader>
        <CardContent class="pt-0">
          <span
            v-if="card.delta !== null"
            class="text-xs font-semibold"
            :class="card.delta >= 0 ? 'text-green-600' : 'text-red-600'"
          >{{ card.delta >= 0 ? '▲' : '▼' }} {{ Math.abs(card.delta).toFixed(0) }} %</span>
        </CardContent>
      </Card>
    </div>
    <p class="text-muted-foreground text-xs">
      {{ t('analytics.vsPrevious', { range: previousRangeLabel }) }}
    </p>
    <p v-if="summary?.missing_cost_products" class="text-muted-foreground text-xs">
      {{ t('analytics.costHint', { count: summary.missing_cost_products }) }}
    </p>
  </div>
</template>
