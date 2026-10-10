<script setup lang="ts">
import { IconArrowsExchange, IconCheck } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import type { TourLeftoverRow } from '@/composables/useRefillWizard'

// End of the tour, back at the warehouse: the goods the slot rebuilds left
// over (taken out of machines, or packed and not used) are counted and booked
// back into the warehouse or written off, in one go.

defineProps<{
  rows: TourLeftoverRow[]
  hasWarehouse: boolean
  returned: boolean
  busy: boolean
  error: string | null
}>()
const emit = defineEmits<{
  (e: 'count', productId: string, kind: 'van' | 'machine', value: number): void
  (e: 'destination', productId: string, value: 'warehouse' | 'waste'): void
  (e: 'expiry', productId: string, value: string): void
  (e: 'book'): void
}>()
const { t } = useI18n()
</script>

<template>
  <div class="rounded-xl border-2 p-3 sm:p-4" :class="returned ? 'border-green-600/50' : 'border-violet-500/60'">
    <p class="flex items-center gap-1.5 text-sm font-medium">
      <IconArrowsExchange class="size-4 text-violet-600" />
      {{ t('refillRebuild.tourLeftoverTitle') }}
    </p>

    <p v-if="returned" class="mt-2 flex items-center gap-1.5 text-sm text-green-700 dark:text-green-400">
      <IconCheck class="size-4" />{{ t('refillRebuild.booked') }}
    </p>

    <template v-else>
      <p class="mb-2 text-xs text-muted-foreground">{{ t('refillRebuild.tourLeftoverHint') }}</p>
      <div v-for="l in rows" :key="l.product_id" class="border-t py-2.5 first:border-t-0">
        <div class="flex items-center gap-2">
          <img v-if="l.image_path" :src="getProductImageUrl(l.image_path)" :alt="l.name ?? ''" class="size-9 rounded object-cover" />
          <p class="min-w-0 flex-1 truncate text-sm font-medium">{{ l.name }}</p>
          <div class="flex shrink-0 rounded-md border p-0.5 text-xs">
            <button
              class="rounded px-2 py-1 font-medium disabled:opacity-40"
              :class="l.destination === 'warehouse' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'"
              :disabled="!hasWarehouse"
              @click="emit('destination', l.product_id, 'warehouse')"
            >{{ t('refillRebuild.toWarehouse') }}</button>
            <button
              class="rounded px-2 py-1 font-medium"
              :class="l.destination === 'waste' ? 'bg-destructive text-white' : 'text-muted-foreground'"
              @click="emit('destination', l.product_id, 'waste')"
            >{{ t('refillRebuild.writeOff') }}</button>
          </div>
        </div>

        <div class="mt-2 grid grid-cols-2 gap-2">
          <div v-for="kind in (['van', 'machine'] as const)" :key="kind">
            <p class="mb-1 text-[11px] text-muted-foreground">{{ kind === 'van' ? t('refillRebuild.vanUnused') : t('refillRebuild.machineTaken') }}</p>
            <div class="flex items-center gap-1">
              <button
                class="inline-flex h-8 w-8 items-center justify-center rounded-lg border text-sm font-semibold active:bg-muted disabled:opacity-30"
                :disabled="l[kind] <= 0"
                @click="emit('count', l.product_id, kind, l[kind] - 1)"
              >&minus;</button>
              <span class="inline-flex h-8 min-w-10 items-center justify-center rounded-lg bg-muted px-1.5 text-sm font-bold tabular-nums">{{ l[kind] }}</span>
              <button
                class="inline-flex h-8 w-8 items-center justify-center rounded-lg border text-sm font-semibold active:bg-muted"
                @click="emit('count', l.product_id, kind, l[kind] + 1)"
              >+</button>
            </div>
          </div>
        </div>

        <label
          v-if="l.machine > 0 && l.destination === 'warehouse'"
          class="mt-2 flex items-center gap-2 text-xs text-muted-foreground"
        >
          {{ t('refillRebuild.bestBefore') }}
          <input
            type="date"
            class="h-8 rounded-md border bg-background px-2 text-sm text-foreground"
            :value="l.expiration_date ?? ''"
            @change="emit('expiry', l.product_id, ($event.target as HTMLInputElement).value)"
          />
        </label>
        <p v-if="l.destination === 'warehouse'" class="mt-1 text-[11px] text-muted-foreground">
          {{ l.van > 0 ? t('refillRebuild.vanBackHint') : '' }}
          {{ l.machine > 0 ? t('refillRebuild.machineBackHint') : '' }}
        </p>
      </div>

      <p v-if="error" class="mt-2 rounded-md bg-destructive/10 px-2 py-1.5 text-xs text-destructive">{{ error }}</p>
      <button
        class="mt-3 inline-flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-violet-600 px-4 text-sm font-medium text-white transition-colors hover:bg-violet-700 disabled:opacity-50"
        :disabled="busy"
        @click="emit('book')"
      >{{ t('refillRebuild.bookBack') }}</button>
    </template>
  </div>
</template>
