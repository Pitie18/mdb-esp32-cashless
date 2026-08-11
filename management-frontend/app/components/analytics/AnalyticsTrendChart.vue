<script setup lang="ts">
import { VisAxis, VisStackedBar, VisXYContainer } from '@unovis/vue'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { chartBucket, metricValue } from '~/lib/analytics'

const { t } = useI18n()
const { summary, metric, bucketedDaily } = useAnalytics()

interface Point { date: Date; value: number }

const points = computed<Point[]>(() =>
  bucketedDaily.value.map(p => ({
    date: new Date(`${p.day}T00:00:00`),
    value: metricValue(p, metric.value),
  })))

const isWeekly = computed(() => chartBucket(summary.value?.range.days ?? 30) === 'week')

const tickFormat = (d: Date) =>
  d.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' })
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.trend') }}</CardDescription>
    </CardHeader>
    <CardContent>
      <p v-if="!points.length" class="text-muted-foreground py-8 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <VisXYContainer v-else :data="points" class="h-56 w-full">
        <VisStackedBar
          :x="(d: Point) => d.date"
          :y="[(d: Point) => d.value]"
          color="var(--primary)"
          :bar-padding="isWeekly ? 0.3 : 0.2"
          :rounded-corners="4"
        />
        <VisAxis type="x" :tick-format="tickFormat" :tick-line="false" :domain-line="false" />
        <VisAxis type="y" :num-ticks="3" :tick-line="false" :domain-line="false" />
      </VisXYContainer>
    </CardContent>
  </Card>
</template>
