<script setup lang="ts">
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { heatIntensity } from '~/lib/analytics'

const { t } = useI18n()
const { summary } = useAnalytics()

/** 2-hour columns keep the grid readable on a phone. */
const HOUR_STEP = 2
const hours = Array.from({ length: 24 / HOUR_STEP }, (_, i) => i * HOUR_STEP)
const days = [1, 2, 3, 4, 5, 6, 7]

/** Locale weekday names in ISO order (Monday first), matching the RPC's
 *  `isodow`. 2026-06-01 is a Monday, so the offsets line up directly. */
const weekdayNames = computed(() => {
  const fmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', timeZone: 'UTC' })
  return days.map(d => fmt.format(new Date(Date.UTC(2026, 5, d))))
})

const buckets = computed(() => {
  const map = new Map<number, number>()
  for (const cell of summary.value?.heatmap ?? []) {
    const key = cell.dow * 100 + Math.floor(cell.hour / HOUR_STEP) * HOUR_STEP
    map.set(key, (map.get(key) ?? 0) + cell.units)
  }
  return map
})

const maxUnits = computed(() => Math.max(0, ...buckets.value.values()))

const cellUnits = (dow: number, hour: number) => buckets.value.get(dow * 100 + hour) ?? 0

const cellStyle = (dow: number, hour: number) => ({
  opacity: String(0.08 + heatIntensity(cellUnits(dow, hour), maxUnits.value) * 0.92),
})
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.peakHours') }}</CardDescription>
    </CardHeader>
    <CardContent>
      <p v-if="!summary?.heatmap.length" class="text-muted-foreground py-6 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-else class="overflow-x-auto">
        <div class="min-w-[320px] space-y-1">
          <div v-for="(dow, i) in days" :key="dow" class="flex items-center gap-1">
            <span class="text-muted-foreground w-8 shrink-0 text-right text-[10px]">
              {{ weekdayNames[i] }}
            </span>
            <span
              v-for="hour in hours" :key="hour"
              class="bg-primary h-4 flex-1 rounded-sm"
              :style="cellStyle(dow, hour)"
              :title="`${weekdayNames[i]} ${hour}:00 — ${cellUnits(dow, hour)}`"
            />
          </div>
          <div class="flex items-center gap-1">
            <span class="w-8 shrink-0" />
            <span
              v-for="hour in hours" :key="hour"
              class="text-muted-foreground flex-1 text-center text-[9px]"
            >{{ hour % 6 === 0 ? hour : '' }}</span>
          </div>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
