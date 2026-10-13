<script setup lang="ts">
import { IconNote } from '@tabler/icons-vue'
import type { TourNote } from '@/composables/useTourNotes'
import { timeAgo } from '@/lib/utils'

// Packing step: the notes written at the machines on the last tours
// ("bring new spirals"). They are checked off when this tour starts.

defineProps<{ notes: TourNote[] }>()
const { t } = useI18n()
</script>

<template>
  <div class="space-y-2 rounded-lg border border-sky-500/40 bg-sky-500/5 p-3">
    <p class="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-sky-700 dark:text-sky-400">
      <IconNote class="size-4" />
      {{ t('refillNotes.packingTitle') }}
    </p>
    <p class="text-xs text-muted-foreground">{{ t('refillNotes.packingHint') }}</p>
    <ul class="divide-y divide-sky-500/20">
      <li v-for="note in notes" :key="note.id" class="py-2 first:pt-1 last:pb-0">
        <div class="flex items-baseline justify-between gap-2">
          <span class="truncate text-sm font-semibold">{{ note.machine_name ?? '—' }}</span>
          <span class="shrink-0 text-xs text-muted-foreground">{{ timeAgo(note.created_at, t) }}</span>
        </div>
        <p class="whitespace-pre-line break-words text-sm">{{ note.note }}</p>
      </li>
    </ul>
  </div>
</template>
