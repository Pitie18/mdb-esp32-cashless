<script setup lang="ts">
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
// getProductImageUrl is a module-level export of useProducts, not part of the
// object useProducts() returns — destructuring it from the call yields
// undefined and only fails at render time, once a product with an image is
// opened.
import { getProductImageUrl } from '@/composables/useProducts'
import { formatCurrency } from '@/lib/utils'
import { deltaPct, type BreakdownRow } from '~/lib/analytics'

const props = defineProps<{ row: BreakdownRow | null }>()
const open = defineModel<boolean>('open', { required: true })

const { t } = useI18n()
const { loadProductMachines } = useAnalytics()

const machineRows = ref<BreakdownRow[]>([])
const loading = ref(false)

watch(() => props.row?.key, async key => {
  if (!key) { machineRows.value = []; return }
  loading.value = true
  machineRows.value = await loadProductMachines(key)
  loading.value = false
}, { immediate: true })

const maxUnits = computed(() => Math.max(0, ...machineRows.value.map(r => r.units)))

const revenueDelta = computed(() =>
  props.row ? deltaPct(props.row.revenue_gross, props.row.prev_revenue_gross) : null)

function machineSubtitle(machine: BreakdownRow) {
  const parts = [t('analytics.perDay', { value: machine.avg_daily_units.toFixed(1) })]
  if (machine.total_capacity > 0) {
    parts.push(t('analytics.stockOf', {
      current: machine.total_stock, capacity: machine.total_capacity,
    }))
    if (machine.total_stock === 0) parts.push(t('analytics.empty'))
  }
  return parts.join(' · ')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent v-if="row" class="max-h-[85vh] space-y-4 overflow-y-auto sm:max-w-lg">
      <DialogHeader>
        <DialogTitle class="flex items-center gap-3">
          <img
            v-if="row.image_path" :src="getProductImageUrl(row.image_path)"
            class="size-10 rounded object-cover" alt=""
          >
          <span>{{ row.label }}</span>
        </DialogTitle>
        <DialogDescription>
          {{ t('analytics.abcClass', { class: row.abc_class, share: row.share_pct.toFixed(1) }) }}
        </DialogDescription>
      </DialogHeader>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.units') }}</p>
          <p class="text-lg font-semibold tabular-nums">{{ row.units }}</p>
        </div>
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.revenue') }}</p>
          <p class="text-lg font-semibold tabular-nums">{{ formatCurrency(row.revenue_gross) }}</p>
          <p
            v-if="revenueDelta !== null" class="text-xs font-semibold"
            :class="revenueDelta >= 0 ? 'text-green-600' : 'text-red-600'"
          >{{ revenueDelta.toFixed(0) }} %</p>
        </div>
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.grossProfit') }}</p>
          <p class="text-lg font-semibold tabular-nums">
            {{ row.has_cost ? formatCurrency(row.gross_profit) : '—' }}
          </p>
          <p v-if="!row.has_cost" class="text-muted-foreground text-xs">
            {{ t('analytics.noPurchasePrice') }}
          </p>
        </div>
      </div>

      <div class="space-y-2">
        <p class="text-muted-foreground text-xs uppercase">{{ t('analytics.perMachine') }}</p>
        <p v-if="loading" class="text-muted-foreground text-sm">…</p>
        <p v-else-if="!machineRows.length" class="text-muted-foreground text-sm">
          {{ t('analytics.noSales') }}
        </p>
        <div v-for="machine in machineRows" v-else :key="machine.key ?? machine.label" class="space-y-1">
          <div class="flex items-center gap-2 text-sm">
            <span class="flex-1 truncate">{{ machine.label }}</span>
            <span class="font-semibold tabular-nums">{{ machine.units }}</span>
          </div>
          <div class="bg-muted h-1.5 w-full rounded-full">
            <div
              class="bg-primary h-1.5 rounded-full"
              :style="{ width: maxUnits > 0 ? `${(machine.units / maxUnits) * 100}%` : '0%' }"
            />
          </div>
          <p class="text-xs" :class="machine.total_stock === 0 ? 'text-red-600' : 'text-muted-foreground'">
            {{ machineSubtitle(machine) }}
          </p>
        </div>
      </div>

      <div v-if="row.sell_through_pct !== null || row.days_of_supply !== null" class="space-y-1">
        <p class="text-muted-foreground text-xs uppercase">{{ t('analytics.stock') }}</p>
        <p v-if="row.sell_through_pct !== null" class="text-muted-foreground text-sm">
          {{ t('analytics.sellThrough', { value: row.sell_through_pct.toFixed(1) }) }}
        </p>
        <p v-if="row.days_of_supply !== null" class="text-muted-foreground text-sm">
          {{ t('analytics.daysOfSupply', { days: row.days_of_supply.toFixed(0) }) }}
        </p>
      </div>
    </DialogContent>
  </Dialog>
</template>
