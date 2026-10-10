<script setup lang="ts">
import { IconArrowRight, IconArrowsExchange, IconCheck, IconX } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import type { RebuildSlot } from '@/composables/useRefillWizard'
import { formatCurrency } from '@/lib/utils'
import { ageChanged } from '@/lib/slotChange'

// At the machine: rebuild the slots of the change note. The refiller counts
// what comes out (sales since packing are already in the live stock), fills
// the new product, sets the price and marks each slot rebuilt or not. Nothing
// is booked until the machine is confirmed. What is left over rides along in
// the van and is booked back at the end of the tour (RefillTourLeftovers).

defineProps<{
  slots: RebuildSlot[]
}>()
const emit = defineEmits<{
  (e: 'removed', itemId: string, value: number): void
  (e: 'filled', itemId: string, value: number): void
  (e: 'action', itemId: string, value: 'done' | 'skip' | null): void
  (e: 'price', itemId: string, value: boolean): void
  (e: 'age', itemId: string, value: boolean): void
}>()
const { t, locale } = useI18n()

function num(e: Event) { return Number((e.target as HTMLInputElement).value) }
function age(n: number | null) { return n == null ? t('refillRebuild.noAgeLimit') : t('refillRebuild.ageFrom', { n }) }
</script>

<template>
  <div class="space-y-2">
    <p class="flex items-center gap-1.5 px-1 text-xs font-medium uppercase tracking-wide text-amber-700 dark:text-amber-400">
      <IconArrowsExchange class="size-4" />
      {{ t('refillRebuild.atMachineTitle') }}
    </p>

    <div
      v-for="s in slots"
      :key="s.item.id"
      class="rounded-xl border-2 bg-card p-3 sm:p-4"
      :class="s.action === 'done' ? 'border-green-600/60' : s.action === 'skip' ? 'border-muted opacity-70' : 'border-amber-500/50'"
    >
      <div class="flex items-center gap-2">
        <span class="inline-flex h-7 min-w-9 items-center justify-center rounded-md bg-muted px-1.5 font-mono text-xs font-semibold">#{{ s.item.item_number }}</span>
        <div class="flex min-w-0 flex-1 items-center gap-1.5 text-sm">
          <img v-if="s.item.from_image_path" :src="getProductImageUrl(s.item.from_image_path)" :alt="s.item.from_name ?? ''" class="size-8 rounded object-cover" />
          <span class="truncate text-muted-foreground">{{ s.item.from_name ?? t('slotPlan.empty') }}</span>
          <IconArrowRight class="size-4 shrink-0 text-muted-foreground" />
          <img v-if="s.item.to_image_path" :src="getProductImageUrl(s.item.to_image_path)" :alt="s.item.to_name ?? ''" class="size-8 rounded object-cover" />
          <b class="truncate">{{ s.item.to_name ?? t('slotPlan.empty') }}</b>
        </div>
      </div>

      <p class="mt-1.5 text-xs text-muted-foreground tabular-nums">
        {{ t('refillRebuild.liveStock', { n: s.live_stock, cap: s.item.from_capacity }) }}
        <template v-if="s.item.to_capacity !== s.item.from_capacity"> · <span class="text-amber-600">{{ t('slotPlan.changeSpiral', { from: s.item.from_capacity, to: s.item.to_capacity }) }}</span></template>
      </p>

      <div v-if="s.action !== 'skip'" class="mt-3 grid grid-cols-2 gap-3">
        <label class="text-xs text-muted-foreground">
          {{ t('refillRebuild.removed') }}
          <input
            type="number" min="0" inputmode="numeric"
            class="mt-1 block h-11 w-full rounded-lg border bg-background px-3 text-base font-semibold tabular-nums text-foreground"
            :value="s.removed"
            @change="emit('removed', s.item.id, num($event))"
          />
        </label>
        <label v-if="s.item.to_product_id" class="text-xs text-muted-foreground">
          {{ t('refillRebuild.filled', { cap: s.item.to_capacity }) }}
          <input
            type="number" min="0" :max="s.item.to_capacity" inputmode="numeric"
            class="mt-1 block h-11 w-full rounded-lg border bg-background px-3 text-base font-semibold tabular-nums text-foreground"
            :value="s.filled"
            @change="emit('filled', s.item.id, num($event))"
          />
          <span class="mt-0.5 block text-[11px]">{{ t('refillRebuild.fillSource', { moved: s.moved, van: s.from_van }) }}</span>
        </label>
      </div>

      <label
        v-if="s.action !== 'skip' && s.item.to_product_id && s.item.to_price != null && s.item.to_price !== s.item.from_price"
        class="mt-3 flex items-center gap-2 rounded-md bg-amber-500/10 px-2.5 py-2 text-sm"
      >
        <input type="checkbox" class="size-4" :checked="s.price_set" @change="emit('price', s.item.id, ($event.target as HTMLInputElement).checked)" />
        <span>{{ t('refillRebuild.priceSet', { n: s.item.item_number }) }} <b class="tabular-nums">{{ formatCurrency(s.item.to_price, locale) }}</b></span>
      </label>

      <label
        v-if="s.action !== 'skip' && ageChanged(s.item)"
        class="mt-2 flex items-center gap-2 rounded-md bg-red-500/10 px-2.5 py-2 text-sm"
      >
        <input type="checkbox" class="size-4" :checked="s.age_set" @change="emit('age', s.item.id, ($event.target as HTMLInputElement).checked)" />
        <span>{{ t('refillRebuild.ageSet', { n: s.item.item_number }) }} <b>{{ age(s.item.from_min_age) }} → {{ age(s.item.to_min_age) }}</b></span>
      </label>

      <div class="mt-3 grid grid-cols-2 gap-2">
        <button
          class="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          :class="s.action === 'done' ? 'border-green-600 bg-green-600 text-white' : 'hover:bg-muted'"
          :disabled="s.action !== 'done' && ageChanged(s.item) && !s.age_set"
          :title="ageChanged(s.item) && !s.age_set ? t('refillRebuild.confirmAgeFirst') : undefined"
          @click="emit('action', s.item.id, s.action === 'done' ? null : 'done')"
        ><IconCheck class="size-4" />{{ t('refillRebuild.rebuilt') }}</button>
        <button
          class="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border text-sm font-medium transition-colors"
          :class="s.action === 'skip' ? 'border-foreground/40 bg-muted' : 'hover:bg-muted'"
          @click="emit('action', s.item.id, s.action === 'skip' ? null : 'skip')"
        ><IconX class="size-4" />{{ t('refillRebuild.notRebuilt') }}</button>
      </div>
      <p v-if="s.action === 'skip'" class="mt-2 text-xs text-muted-foreground">{{ t('refillRebuild.staysOpen') }}</p>
      <p v-else-if="s.action === null && ageChanged(s.item) && !s.age_set" class="mt-2 text-xs text-red-600 dark:text-red-400">{{ t('refillRebuild.confirmAgeFirst') }}</p>
    </div>

    <p class="px-1 text-xs text-muted-foreground">{{ t('refillRebuild.takeAlongHint') }}</p>
  </div>
</template>
