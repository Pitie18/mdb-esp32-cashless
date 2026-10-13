<script setup lang="ts">
// Packing step: −/+ for the units packed for a slot rebuild, e.g. only half a
// spiral of a new product. Violet like the rest of the rebuild goods.

const props = defineProps<{
  value: number
  max: number
}>()
const emit = defineEmits<{ set: [value: number] }>()
const { t } = useI18n()
</script>

<template>
  <div class="flex shrink-0 items-center gap-1" @click.stop>
    <button
      class="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-violet-500/40 text-sm font-semibold text-violet-700 transition-colors active:bg-violet-500/10 disabled:opacity-30 dark:text-violet-300"
      :aria-label="t('refillRebuild.packLess')"
      :disabled="props.value <= 0"
      @click="emit('set', props.value - 1)"
    >&minus;</button>
    <span class="inline-flex h-8 min-w-10 items-center justify-center rounded-lg bg-violet-500/15 px-1.5 text-sm font-bold tabular-nums text-violet-700 dark:text-violet-300">
      {{ props.value }}
    </span>
    <button
      class="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-violet-500/40 text-sm font-semibold text-violet-700 transition-colors active:bg-violet-500/10 disabled:opacity-30 dark:text-violet-300"
      :aria-label="t('refillRebuild.packMore')"
      :disabled="props.value >= props.max"
      @click="emit('set', props.value + 1)"
    >+</button>
  </div>
</template>
