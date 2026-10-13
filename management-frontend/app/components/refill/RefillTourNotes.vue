<script setup lang="ts">
import { IconNote } from '@tabler/icons-vue'
import type { TourNote } from '@/composables/useTourNotes'
import { timeAgo } from '@/lib/utils'

// Packing step: the notes written at the machines on the last tours
// ("bring new spirals"). Each stays on every tour until it is ticked off.

defineProps<{ notes: TourNote[]; error?: string | null }>()
const emit = defineEmits<{ (e: 'done', noteId: string): void }>()
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
      <li v-for="note in notes" :key="note.id" class="flex gap-2.5 py-2 first:pt-1 last:pb-0">
        <button
          type="button"
          class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded border-2 border-sky-600/50 transition-colors hover:bg-sky-500/15"
          :aria-label="t('refillNotes.markDone')"
          :title="t('refillNotes.markDone')"
          @click="emit('done', note.id)"
        />
        <div class="min-w-0 flex-1">
          <div class="flex items-baseline justify-between gap-2">
            <span class="truncate text-sm font-semibold">{{ note.machine_name ?? '—' }}</span>
            <span class="shrink-0 text-xs text-muted-foreground">{{ timeAgo(note.created_at, t) }}</span>
          </div>
          <p class="whitespace-pre-line break-words text-sm">{{ note.note }}</p>
        </div>
      </li>
    </ul>
    <p v-if="error" role="alert" class="text-xs text-destructive">{{ error }}</p>
  </div>
</template>
