<script setup lang="ts">
import AnalyticsBreakdown from '@/components/analytics/AnalyticsBreakdown.vue'
import AnalyticsChannelSplit from '@/components/analytics/AnalyticsChannelSplit.vue'
import AnalyticsFilterBar from '@/components/analytics/AnalyticsFilterBar.vue'
import AnalyticsHeatmap from '@/components/analytics/AnalyticsHeatmap.vue'
import AnalyticsKpiRow from '@/components/analytics/AnalyticsKpiRow.vue'
import AnalyticsTrendChart from '@/components/analytics/AnalyticsTrendChart.vue'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import type { BreakdownRow } from '~/lib/analytics'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const {
  metric, summary, error, backendUnsupported, loadAll, loadFilterOptions,
} = useAnalytics()

const selectedRow = ref<BreakdownRow | null>(null)
const dialogOpen = ref(false)

onMounted(async () => {
  await loadFilterOptions()
  if (!summary.value) await loadAll()
})

usePullToRefresh(() => loadAll())
</script>

<template>
  <div class="space-y-4 p-4">
    <div>
      <h1 class="text-2xl font-bold">{{ t('analytics.title') }}</h1>
      <p class="text-muted-foreground text-sm">{{ t('analytics.subtitle') }}</p>
    </div>

    <div
      v-if="backendUnsupported"
      class="rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200"
    >
      {{ t('analytics.unsupported') }}
    </div>

    <template v-else>
      <AnalyticsFilterBar />
      <AnalyticsKpiRow />

      <Tabs v-model="metric">
        <TabsList>
          <TabsTrigger value="units">{{ t('analytics.units') }}</TabsTrigger>
          <TabsTrigger value="revenue">{{ t('analytics.revenue') }}</TabsTrigger>
          <TabsTrigger value="grossProfit">{{ t('analytics.profit') }}</TabsTrigger>
        </TabsList>
      </Tabs>

      <AnalyticsTrendChart />

      <AnalyticsBreakdown @select="row => { selectedRow = row; dialogOpen = true }" />

      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <AnalyticsHeatmap />
        <AnalyticsChannelSplit />
      </div>

      <div
        v-if="error"
        class="rounded-md border border-red-300 bg-red-50 p-4 text-sm text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-200"
      >
        {{ error }}
      </div>
    </template>
  </div>
</template>
