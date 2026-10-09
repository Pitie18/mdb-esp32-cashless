<script setup lang="ts">
import { IconArrowRight, IconArrowsExchange, IconCheck, IconX } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import type { RebuildLeftover, RebuildSlot } from '@/composables/useRefillWizard'
import { formatCurrency } from '@/lib/utils'
import { ageChanged } from '@/lib/slotChange'

// At the machine: rebuild the slots of the change note. The refiller counts
// what comes out (sales since packing are already in the live stock), fills
// the new product, sets the price and marks each slot rebuilt or not. What is
// left over goes back to the warehouse or is written off. Nothing is booked
// until the machine is confirmed.

defineProps<{
  slots: RebuildSlot[]
  leftovers: RebuildLeftover[]
  destinations: Map<string, 'warehouse' | 'waste'>
  expiry: Map<string, string>
}>()
const emit = defineEmits<{
  (e: 'removed', itemId: string, value: number): void
  (e: 'filled', itemId: string, value: number): void
  (e: 'action', itemId: string, value: 'done' | 'skip' | null): void
  (e: 'price', itemId: string, value: boolean): void
  (e: 'age', itemId: string, value: boolean): void
  (e: 'destination', productId: string, value: 'warehouse' | 'waste'): void
  (e: 'expiry', productId: string, value: string): void
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

    <!-- Leftovers -->
    <div v-if="leftovers.length > 0" class="rounded-xl border bg-card p-3 sm:p-4">
      <p class="text-sm font-medium">{{ t('refillRebuild.leftoverTitle') }}</p>
      <p class="mb-2 text-xs text-muted-foreground">{{ t('refillRebuild.leftoverHint') }}</p>
      <div v-for="l in leftovers" :key="l.product_id" class="border-t py-2 first:border-t-0">
        <div class="flex items-center gap-2">
          <img v-if="l.image_path" :src="getProductImageUrl(l.image_path)" :alt="l.name ?? ''" class="size-8 rounded object-cover" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm">{{ l.name }} <b class="tabular-nums">{{ t('slotPlan.units', { n: l.van + l.machine }) }}</b></p>
            <p class="text-xs text-muted-foreground">
              <template v-if="l.machine > 0">{{ t('refillRebuild.fromMachine', { n: l.machine }) }}</template>
              <template v-if="l.machine > 0 && l.van > 0"> · </template>
              <template v-if="l.van > 0">{{ t('refillRebuild.fromVan', { n: l.van }) }}</template>
            </p>
          </div>
          <div class="flex shrink-0 rounded-md border p-0.5 text-xs">
            <button
              class="rounded px-2 py-1 font-medium"
              :class="(destinations.get(l.product_id) ?? 'warehouse') === 'warehouse' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'"
              @click="emit('destination', l.product_id, 'warehouse')"
            >{{ t('refillRebuild.toWarehouse') }}</button>
            <button
              class="rounded px-2 py-1 font-medium"
              :class="destinations.get(l.product_id) === 'waste' ? 'bg-destructive text-white' : 'text-muted-foreground'"
              @click="emit('destination', l.product_id, 'waste')"
            >{{ t('refillRebuild.writeOff') }}</button>
          </div>
        </div>
        <label
          v-if="l.machine > 0 && (destinations.get(l.product_id) ?? 'warehouse') === 'warehouse'"
          class="mt-2 flex items-center gap-2 text-xs text-muted-foreground"
        >
          {{ t('refillRebuild.bestBefore') }}
          <input
            type="date"
            class="h-8 rounded-md border bg-background px-2 text-sm text-foreground"
            :value="expiry.get(l.product_id) ?? ''"
            @change="emit('expiry', l.product_id, ($event.target as HTMLInputElement).value)"
          />
        </label>
        <p v-if="(destinations.get(l.product_id) ?? 'warehouse') === 'warehouse'" class="mt-1 text-[11px] text-muted-foreground">
          {{ l.van > 0 ? t('refillRebuild.vanBackHint') : '' }}
          {{ l.machine > 0 ? t('refillRebuild.machineBackHint') : '' }}
        </p>
      </div>
    </div>
  </div>
</template>
