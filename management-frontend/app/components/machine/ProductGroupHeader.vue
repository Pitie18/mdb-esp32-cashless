<script setup lang="ts">
import type { ProductStockGroup } from '@/lib/stock-health'

/**
 * Summary line for a product that sits in two or more slots: the product's
 * summed stock and status, one bar segment per slot. The slots follow as
 * normal rows below it.
 */
const props = defineProps<{
  group: ProductStockGroup<{ id: string; item_number: number; machine_id: string; product_id: string | null; capacity: number; current_stock: number; min_stock: number; fill_when_below: number }>
  name: string
  imageUrl?: string | null
  needsRefill: boolean
  /** False for machines with linked selections, where an empty slot doesn't matter. */
  showEmptySlots?: boolean
}>()

const { t } = useI18n()

const status = computed(() => {
  if (!props.needsRefill) return { label: t('machineDetail.groupStateOk'), cls: 'bg-green-500/10 text-green-600 dark:text-green-400' }
  if (props.group.state === 'critical') return { label: t('machineDetail.groupStateCritical'), cls: 'bg-red-500/10 text-red-600 dark:text-red-400' }
  if (props.group.state === 'low') return { label: t('machineDetail.groupStateLow'), cls: 'bg-amber-500/10 text-amber-600 dark:text-amber-400' }
  return { label: t('machineDetail.groupStateFill'), cls: 'bg-blue-500/10 text-blue-600 dark:text-blue-400' }
})

const segments = computed(() =>
  [...props.group.trays]
    .sort((a, b) => a.item_number - b.item_number)
    .map(tray => ({
      id: tray.id,
      item: tray.item_number,
      pct: tray.capacity > 0 ? Math.min(100, Math.round((tray.current_stock / tray.capacity) * 100)) : 0,
    })),
)

const barColor = computed(() => {
  if (!props.needsRefill) return 'bg-green-500'
  if (props.group.state === 'critical' || props.group.state === 'low') return 'bg-amber-500'
  return 'bg-blue-500'
})
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="flex items-center gap-3">
      <img v-if="imageUrl" :src="imageUrl" :alt="name" class="h-8 w-8 shrink-0 rounded-md object-cover" />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-baseline gap-x-2">
          <span class="truncate text-sm font-semibold">{{ name }}</span>
          <span class="text-xs text-muted-foreground">{{ t('machineDetail.groupSlots', { count: group.trays.length }, group.trays.length) }}</span>
        </div>
        <div class="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
          <span class="tabular-nums"><span class="font-semibold text-foreground">{{ group.current_stock }}</span> / {{ group.capacity }}</span>
          <span v-if="group.deficit > 0" class="tabular-nums">{{ t('machineDetail.groupMissing', { count: group.deficit }) }}</span>
          <span v-if="group.emptySlots > 0 && !needsRefill && showEmptySlots !== false">{{ t('machineDetail.groupEmptySlots', { count: group.emptySlots }, group.emptySlots) }}</span>
        </div>
      </div>
      <span class="shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold" :class="status.cls">{{ status.label }}</span>
    </div>
    <div class="flex h-2 gap-0.5" :aria-label="`${group.current_stock} / ${group.capacity}`">
      <div
        v-for="seg in segments"
        :key="seg.id"
        class="relative flex-1 overflow-hidden rounded-sm bg-muted"
        :title="`${t('machineDetail.slot')} ${seg.item}`"
      >
        <div class="h-full" :class="barColor" :style="{ width: `${seg.pct}%` }" />
      </div>
    </div>
  </div>
</template>
