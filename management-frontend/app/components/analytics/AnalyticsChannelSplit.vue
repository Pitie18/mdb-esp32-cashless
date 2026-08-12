<script setup lang="ts">
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { formatCurrency } from '@/lib/utils'

const { t } = useI18n()
const { summary } = useAnalytics()

const channels = computed(() => summary.value?.channels ?? [])
const total = computed(() => channels.value.reduce((sum, c) => sum + c.revenue_gross, 0))

const label = (raw: string) => {
  const key = raw.toLowerCase()
  if (key === 'cash') return t('analytics.cash')
  if (key === 'cashless' || key === 'card') return t('analytics.cashless')
  return t('analytics.unknown')
}

const barClass = (raw: string) => {
  const key = raw.toLowerCase()
  if (key === 'cash') return 'bg-green-500'
  if (key === 'cashless' || key === 'card') return 'bg-blue-500'
  return 'bg-gray-400'
}
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.paymentMethods') }}</CardDescription>
    </CardHeader>
    <CardContent class="space-y-3">
      <p v-if="!channels.length" class="text-muted-foreground py-6 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-for="channel in channels" :key="channel.channel" class="space-y-1">
        <div class="flex items-center gap-2 text-sm">
          <span class="flex-1">{{ label(channel.channel) }}</span>
          <span class="font-semibold tabular-nums">{{ formatCurrency(channel.revenue_gross) }}</span>
          <span class="text-muted-foreground w-12 text-right text-xs">
            {{ total > 0 ? Math.round((channel.revenue_gross / total) * 100) : 0 }} %
          </span>
        </div>
        <div class="bg-muted h-1.5 w-full rounded-full">
          <div
            class="h-1.5 rounded-full" :class="barClass(channel.channel)"
            :style="{ width: total > 0 ? `${(channel.revenue_gross / total) * 100}%` : '0%' }"
          />
        </div>
        <p class="text-muted-foreground text-xs">
          {{ t('analytics.nPieces', channel.units) }} · ⌀ {{ formatCurrency(channel.avg_ticket) }}
        </p>
      </div>
    </CardContent>
  </Card>
</template>
