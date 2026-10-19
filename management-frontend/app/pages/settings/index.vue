<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { role } = useOrganization()
const isAdmin = computed(() => role.value === 'admin')
</script>

<template>
  <div class="flex flex-1 flex-col gap-6 p-4 md:p-6">
    <h1 class="text-2xl font-semibold">{{ t('settings.title') }}</h1>

    <div v-if="!isAdmin" class="max-w-3xl rounded-lg border bg-card p-6 text-sm text-muted-foreground">
      {{ t('settings.noAccessHint') }}
    </div>

    <div v-else class="grid w-full max-w-3xl gap-6">
      <SettingsImprintCard />
      <SettingsAiKeyCard />
      <SettingsLowStockCard />
      <SettingsRemovalCooldownCard />
      <SettingsStripeCard />
      <SettingsDealSearchCard />
      <SettingsTaxCard />
      <NuxtLink
        to="/settings/extensions"
        class="inline-flex h-9 w-fit items-center gap-2 rounded-md border bg-card px-4 text-sm font-medium shadow-sm transition-colors hover:bg-accent"
      >
        {{ t('settings.extensionsLink') }}
      </NuxtLink>
    </div>
  </div>
</template>
