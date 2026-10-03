<script setup lang="ts">
import { VisAxis, VisStackedBar, VisXYContainer } from '@unovis/vue'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { chartBucket, metricValue } from '~/lib/analytics'

const { t, locale } = useI18n()
const { summary, metric, bucketedDaily } = useAnalytics()

interface Point { date: Date; value: number }

const points = computed<Point[]>(() =>
  bucketedDaily.value.map(p => ({
    date: new Date(`${p.day}T00:00:00`),
    value: metricValue(p, metric.value),
  })))

const isWeekly = computed(() => chartBucket(summary.value?.range.days ?? 30) === 'week')

// unovis' default x scale is linear (core/xy-component: `Scale.scaleLinear()`),
// so `.ticks()` hands the formatter NUMBERS — epoch milliseconds — even though
// the accessor returns Dates. Calling a Date method on that throws inside the
// axis render and unovis then draws nothing at all, card and axes included.
const tickFormat = (d: number) =>
  new Date(d).toLocaleDateString(locale.value, { day: '2-digit', month: '2-digit' })
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
        <VisAxis
          type="x"
          :x="(d: Point) => d.date"
          :tick-format="tickFormat"
          :tick-line="false"
          :domain-line="false"
          :grid-line="false"
          :num-ticks="6"
        />
        <VisAxis type="y" :num-ticks="3" :tick-line="false" :domain-line="false" />
      </VisXYContainer>
    </CardContent>
  </Card>
</template>
