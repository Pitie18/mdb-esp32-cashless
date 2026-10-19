<script setup lang="ts">
import { IconArrowsExchange, IconLoader2 } from '@tabler/icons-vue'

// How long a product taken out of a machine stays flagged in the refill
// review's replacement picker (iOS/Android), so a slow seller is not put
// straight back. Server side: get_recent_product_removals.
const { t } = useI18n()
const supabase = useSupabaseClient()
const { organization, role } = useOrganization()

const days = ref<number>(30)
const loading = ref(false)
const error = ref('')
const success = ref('')

async function load() {
  if (!organization.value?.id) return
  loading.value = true
  error.value = ''
  try {
    const { data, error: fetchErr } = await supabase
      .from('companies')
      .select('product_removal_cooldown_days')
      .eq('id', organization.value.id)
      .single()
    if (fetchErr) throw fetchErr
    const value = (data as any)?.product_removal_cooldown_days
    if (typeof value === 'number') days.value = value
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : t('settings.removalCooldownLoadError')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!organization.value?.id) return
  const value = Math.round(Number(days.value))
  if (!Number.isFinite(value) || value < 0 || value > 365) {
    error.value = t('settings.removalCooldownRange')
    return
  }
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const { error: updateErr } = await supabase
      .from('companies')
      .update({ product_removal_cooldown_days: value })
      .eq('id', organization.value.id)
    if (updateErr) throw updateErr
    days.value = value
    success.value = t('settings.removalCooldownSaved')
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : t('settings.removalCooldownSaveError')
  } finally {
    loading.value = false
  }
}

watch(() => organization.value?.id, (id) => {
  if (import.meta.client && id && role.value === 'admin') load()
}, { immediate: true })
</script>

<template>
  <div class="rounded-xl border bg-card p-6 shadow-sm">
    <div class="mb-5 flex items-center gap-2">
      <IconArrowsExchange class="size-5 text-primary" />
      <div>
        <h2 class="text-lg font-semibold">{{ t('settings.removalCooldownTitle') }}</h2>
        <p class="text-sm text-muted-foreground">{{ t('settings.removalCooldownDescription') }}</p>
      </div>
    </div>

    <form class="space-y-4" @submit.prevent="save">
      <div class="space-y-1">
        <label for="removal-cooldown" class="text-sm font-medium">{{ t('settings.removalCooldownDays') }}</label>
        <input
          id="removal-cooldown"
          v-model.number="days"
          type="number"
          min="0"
          max="365"
          step="1"
          class="flex h-9 w-32 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
        <p class="text-xs text-muted-foreground">{{ t('settings.removalCooldownHint') }}</p>
      </div>

      <p v-if="error" class="text-sm text-destructive">{{ error }}</p>
      <p v-if="success" class="text-sm text-green-600">{{ success }}</p>

      <button
        type="submit"
        :disabled="loading"
        class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90 disabled:opacity-50"
      >
        <IconLoader2 v-if="loading" class="mr-2 size-4 animate-spin" />
        {{ t('settings.removalCooldownSave') }}
      </button>
    </form>
  </div>
</template>
