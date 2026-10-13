<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { TOUR_NOTE_MAX_LENGTH } from '@/composables/useTourNotes'

// Refill step: free text the refiller wants to see when packing the next tour
// ("bring new spirals"). Opens with this tour's note for the machine, if any;
// saving an empty text deletes it.

const props = defineProps<{
  open: boolean
  machineName: string
  load: () => Promise<string>
  save: (note: string) => Promise<void>
}>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void; (e: 'saved'): void }>()
const { t } = useI18n()

const text = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)

watch(() => props.open, async (open) => {
  if (!open) return
  text.value = ''
  error.value = null
  loading.value = true
  try {
    text.value = await props.load()
  } catch {
    // No prefill: the refiller can still write a note.
  } finally {
    loading.value = false
  }
}, { immediate: true })

async function onSave() {
  saving.value = true
  error.value = null
  try {
    await props.save(text.value)
    emit('saved')
    emit('update:open', false)
  } catch (err: any) {
    error.value = err?.message ?? String(err)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="(v) => emit('update:open', v)">
    <DialogContent class="sm:max-w-lg">
      <DialogHeader>
        <DialogTitle>{{ t('refillNotes.title') }}</DialogTitle>
        <DialogDescription>{{ machineName }} · {{ t('refillNotes.hint') }}</DialogDescription>
      </DialogHeader>

      <textarea
        v-model="text"
        rows="4"
        :maxlength="TOUR_NOTE_MAX_LENGTH"
        :disabled="loading || saving"
        :placeholder="t('refillNotes.placeholder')"
        class="w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-base sm:text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
      />
      <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>

      <DialogFooter>
        <Button variant="outline" @click="emit('update:open', false)">{{ t('common.cancel') }}</Button>
        <Button :disabled="loading || saving" @click="onSave">
          {{ saving ? t('common.saving') : t('common.save') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
