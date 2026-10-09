<script setup lang="ts">
import { computed } from 'vue'
import { IconArrowRight, IconArrowsExchange } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import type { SlotChangeItem } from '@/composables/useSlotChangeRequests'
import { formatCurrency } from '@/lib/utils'
import { ageChanged } from '@/lib/slotChange'

// Packing step: the machine's change note. The refiller accepts (default) or
// declines each slot; accepted slots add their rebuild units to the packing
// list. Declined slots stay open for a later tour.

const props = defineProps<{
  items: SlotChangeItem[]
  accepted: (itemId: string) => boolean
  /** Rebuild units per product: need = what the slots need, packed = what the warehouse covers. */
  pack: { product_id: string; name: string | null; image_path: string | null; need: number; packed: number }[]
}>()
const emit = defineEmits<{ (e: 'toggle', itemId: string): void }>()
const { t, locale } = useI18n()

function age(n: number | null) { return n == null ? t('refillRebuild.noAgeLimit') : t('refillRebuild.ageFrom', { n }) }
const acceptedCount = computed(() => props.items.filter(i => props.accepted(i.id)).length)
</script>

<template>
  <div class="space-y-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3">
    <div class="flex items-center justify-between gap-2">
      <p class="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-amber-700 dark:text-amber-400">
        <IconArrowsExchange class="size-4" />
        {{ t('refillRebuild.changeNote') }}
      </p>
      <span class="text-xs text-muted-foreground tabular-nums">{{ t('refillRebuild.acceptedOf', { n: acceptedCount, total: items.length }) }}</span>
    </div>
    <p class="text-xs text-muted-foreground">{{ t('refillRebuild.changeNoteHint') }}</p>

    <ul class="space-y-1">
      <li
        v-for="item in items"
        :key="item.id"
        class="flex cursor-pointer select-none items-center gap-2 rounded-md px-1.5 py-1.5 hover:bg-muted/50"
        :class="accepted(item.id) ? '' : 'opacity-50'"
        @click="emit('toggle', item.id)"
      >
        <span
          class="flex h-5 w-5 shrink-0 items-center justify-center rounded border-2"
          :class="accepted(item.id) ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/30'"
        >
          <svg v-if="accepted(item.id)" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="h-3 w-3"><polyline points="20 6 9 17 4 12" /></svg>
        </span>
        <span class="w-7 shrink-0 font-mono text-xs text-muted-foreground">#{{ item.item_number }}</span>
        <span class="min-w-0 flex-1 text-sm">
          <span class="truncate">{{ item.from_name ?? t('slotPlan.empty') }}</span>
          <IconArrowRight class="mx-1 inline size-3.5 text-muted-foreground" />
          <b class="truncate">{{ item.to_name ?? t('slotPlan.empty') }}</b>
          <span v-if="item.to_capacity !== item.from_capacity" class="ml-1 text-xs text-amber-600 tabular-nums">
            {{ t('slotPlan.changeSpiral', { from: item.from_capacity, to: item.to_capacity }) }}
          </span>
          <span v-if="item.to_product_id && item.to_price != null && item.to_price !== item.from_price" class="ml-1 rounded bg-amber-500/15 px-1 text-xs text-amber-700 tabular-nums dark:text-amber-400">
            {{ t('refillRebuild.newPrice', { price: formatCurrency(item.to_price, locale) }) }}
          </span>
          <span v-if="ageChanged(item)" class="ml-1 rounded bg-red-500/15 px-1 text-xs text-red-700 dark:text-red-400">
            {{ age(item.from_min_age) }} → {{ age(item.to_min_age) }}
          </span>
          <span v-if="!accepted(item.id)" class="ml-1 text-xs text-muted-foreground">{{ t('refillRebuild.notThisTour') }}</span>
        </span>
      </li>
    </ul>

    <div v-if="pack.length > 0" class="border-t pt-2">
      <p class="mb-1 text-xs font-medium text-muted-foreground">{{ t('refillRebuild.packForRebuild') }}</p>
      <div v-for="p in pack" :key="p.product_id" class="flex items-center gap-2 py-0.5 text-sm">
        <img v-if="p.image_path" :src="getProductImageUrl(p.image_path)" :alt="p.name ?? ''" class="size-7 rounded object-cover" />
        <span class="min-w-0 flex-1 truncate">{{ p.name }}</span>
        <b v-if="p.need > 0" class="tabular-nums">{{ p.packed }}&times;</b>
        <span v-else class="text-xs text-muted-foreground">{{ t('slotPlan.movesOver') }}</span>
        <span v-if="p.packed < p.need" class="text-xs text-amber-600">{{ t('machines.needed', { count: p.need }) }}</span>
      </div>
    </div>
  </div>
</template>
