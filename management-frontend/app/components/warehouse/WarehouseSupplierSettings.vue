<script setup lang="ts">
import { ref } from 'vue'
import { IconPlus } from '@tabler/icons-vue'
import type { OrderSettings, OrderSupplier } from '@/lib/orderSuggestion'

/**
 * Setup › Suppliers: lead time, order interval and minimum order value per
 * supplier (empty = company default), plus the contact data the order list
 * and the copied order text show. Each field saves on change.
 */
const props = defineProps<{
  suppliers: (OrderSupplier & { phone?: string | null })[]
  productCounts: Record<string, number>
  settings: OrderSettings
}>()

const emit = defineEmits<{
  update: [id: string, updates: Record<string, string | number | null>]
  create: [name: string]
}>()

const { t } = useI18n()

const newName = ref('')

function intOrNull(v: string): number | null {
  const n = parseInt(v, 10)
  return Number.isFinite(n) && n >= 0 ? n : null
}

function onNumber(id: string, field: 'lead_time_days' | 'order_interval_days', e: Event) {
  const value = intOrNull((e.target as HTMLInputElement).value)
  emit('update', id, { [field]: field === 'order_interval_days' && value === 0 ? null : value })
}

function onMoney(id: string, e: Event) {
  const n = parseFloat((e.target as HTMLInputElement).value.replace(',', '.'))
  emit('update', id, { min_order_value: Number.isFinite(n) && n > 0 ? Math.round(n * 100) / 100 : null })
}

function onText(id: string, field: 'customer_number' | 'email' | 'phone', e: Event) {
  emit('update', id, { [field]: (e.target as HTMLInputElement).value.trim() || null })
}

function create() {
  const name = newName.value.trim()
  if (!name) return
  emit('create', name)
  newName.value = ''
}

const input = 'h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring'
</script>

<template>
  <div class="flex flex-col gap-4">
    <p class="text-sm text-muted-foreground">{{ t('warehouse.order.suppliersHint', { lead: settings.defaultLeadDays, interval: settings.defaultIntervalDays }) }}</p>

    <p v-if="suppliers.length === 0" class="rounded-lg border p-4 text-sm text-muted-foreground">{{ t('warehouse.order.noSuppliers') }}</p>

    <section v-for="s in suppliers" :key="s.id" class="rounded-lg border">
      <header class="flex items-center justify-between gap-2 border-b px-4 py-3">
        <h3 class="font-semibold">{{ s.name }}</h3>
        <span class="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
          {{ t('warehouse.order.productCount', { count: productCounts[s.id] ?? 0 }, productCounts[s.id] ?? 0) }}
        </span>
      </header>
      <div class="grid gap-3 p-4 sm:grid-cols-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.leadTime') }}</span>
          <input :class="input" type="number" min="0" :value="s.lead_time_days ?? ''" :placeholder="String(settings.defaultLeadDays)" @change="onNumber(s.id, 'lead_time_days', $event)" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.interval') }}</span>
          <input :class="input" type="number" min="1" :value="s.order_interval_days ?? ''" :placeholder="String(settings.defaultIntervalDays)" @change="onNumber(s.id, 'order_interval_days', $event)" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.minOrderValue') }}</span>
          <input :class="input" inputmode="decimal" :value="s.min_order_value ?? ''" placeholder="—" @change="onMoney(s.id, $event)" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.customerNumber') }}</span>
          <input :class="input" :value="s.customer_number ?? ''" @change="onText(s.id, 'customer_number', $event)" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.email') }}</span>
          <input :class="input" type="email" :value="s.email ?? ''" @change="onText(s.id, 'email', $event)" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="font-medium">{{ t('warehouse.order.phone') }}</span>
          <input :class="input" type="tel" :value="s.phone ?? ''" @change="onText(s.id, 'phone', $event)" />
        </label>
      </div>
    </section>

    <form class="flex gap-2" @submit.prevent="create">
      <input v-model="newName" :class="input" class="max-w-xs" :placeholder="t('warehouse.order.newSupplier')" :aria-label="t('warehouse.order.newSupplier')" />
      <button type="submit" class="inline-flex h-9 items-center gap-1.5 rounded-md border px-3 text-sm font-medium hover:bg-muted disabled:opacity-50" :disabled="!newName.trim()">
        <IconPlus class="size-4" />
        {{ t('warehouse.order.addSupplier') }}
      </button>
    </form>
  </div>
</template>
