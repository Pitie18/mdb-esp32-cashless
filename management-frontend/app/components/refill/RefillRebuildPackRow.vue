<script setup lang="ts">
import { IconArrowsExchange } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import type { RebuildPackRow } from '@/composables/useRefillWizard'

// Packing step: a product that only a slot rebuild needs, shown in the normal
// packing list (violet). The tick is a packing aid; the units are committed
// for the rebuild either way.

defineProps<{
  row: RebuildPackRow
  ticked: boolean
  subtitle?: string
}>()
const { t } = useI18n()
</script>

<template>
  <span
    class="flex h-6 w-6 shrink-0 items-center justify-center rounded border-2 transition-colors"
    :class="ticked ? 'border-violet-600 bg-violet-600 text-white' : 'border-violet-500/50'"
  >
    <svg v-if="ticked" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="h-3.5 w-3.5"><polyline points="20 6 9 17 4 12" /></svg>
  </span>
  <img
    v-if="row.image_path"
    :src="getProductImageUrl(row.image_path)"
    :alt="row.product_name"
    class="h-10 w-10 shrink-0 rounded object-cover transition-opacity"
    :class="ticked ? 'opacity-40' : ''"
  />
  <span
    v-else
    class="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-violet-500/10 text-xs text-violet-700 transition-opacity dark:text-violet-300"
    :class="ticked ? 'opacity-40' : ''"
  >
    {{ row.product_name.charAt(0) }}
  </span>
  <div class="min-w-0 flex-1 transition-all" :class="ticked ? 'text-muted-foreground/50' : ''">
    <span class="block text-sm">
      {{ row.packed }}&times; {{ row.product_name }}
      <span class="ml-1 inline-flex items-center gap-0.5 rounded bg-violet-500/15 px-1 text-xs font-medium text-violet-700 dark:text-violet-300">
        <IconArrowsExchange class="size-3" />{{ t('refillRebuild.rebuildRow') }}
      </span>
      <span v-if="row.packed < row.need" class="ml-1 text-xs text-amber-500 dark:text-amber-400">{{ t('machines.needed', { count: row.need }) }}</span>
    </span>
    <span v-if="subtitle" class="block text-xs text-muted-foreground">{{ subtitle }}</span>
  </div>
</template>
