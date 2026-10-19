<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { IconTruckDelivery } from '@tabler/icons-vue'
import { formatDate } from '@/lib/utils'
import type { PurchaseOrder, ReceiveLineInput } from '@/composables/usePurchaseOrders'

/**
 * Incoming tab: the orders that are on the way. Opening one lists its lines
 * prefilled with the ordered quantity; the refiller corrects what actually
 * came, adds best-before dates and books everything in one go. An order whose
 * goods were already booked by hand (older app versions know no orders) is
 * closed without booking.
 */
const props = defineProps<{
  orders: PurchaseOrder[]
  focusOrderId: string | null
  busyOrderId: string | null
}>()

const emit = defineEmits<{
  receive: [order: PurchaseOrder, lines: ReceiveLineInput[]]
  close: [order: PurchaseOrder]
}>()

const { t, locale } = useI18n()

const openId = ref<string | null>(null)
const drafts = reactive<Record<string, { quantity: string; expiration_date: string; batch_number: string }>>({})

function open(o: PurchaseOrder) {
  openId.value = o.id
  for (const l of o.lines) {
    if (!drafts[l.id]) drafts[l.id] = { quantity: String(l.quantity), expiration_date: '', batch_number: '' }
  }
}

watch(() => props.focusOrderId, (id) => {
  const o = id ? props.orders.find(x => x.id === id) : null
  if (o) open(o)
}, { immediate: true })

function parsed(lineId: string): number {
  const n = parseInt(drafts[lineId]?.quantity ?? '', 10)
  return Number.isFinite(n) && n > 0 ? n : 0
}

function receive(o: PurchaseOrder) {
  emit('receive', o, o.lines.map(l => ({
    line_id: l.id,
    quantity: parsed(l.id),
    expiration_date: drafts[l.id]?.expiration_date || null,
    batch_number: drafts[l.id]?.batch_number || null,
  })))
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <h2 class="text-lg font-semibold">{{ t('warehouse.order.expectedDeliveries') }}</h2>
    <p v-if="orders.length === 0" class="rounded-lg border p-4 text-sm text-muted-foreground">
      {{ t('warehouse.order.noExpectedDeliveries') }}
    </p>

    <section v-for="o in orders" :key="o.id" class="overflow-hidden rounded-lg border">
      <header class="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
        <div class="flex min-w-0 items-center gap-3">
          <IconTruckDelivery class="size-5 shrink-0 text-violet-600 dark:text-violet-400" />
          <div class="min-w-0">
            <div class="font-medium">{{ o.supplier_name || t('warehouse.order.noSupplier') }}</div>
            <div class="text-xs text-muted-foreground">
              {{ t('warehouse.order.orderedMeta', { date: formatDate(o.created_at, locale), count: o.lines.length, units: o.lines.reduce((s, l) => s + l.quantity, 0) }) }}
            </div>
          </div>
        </div>
        <button
          v-if="openId !== o.id"
          class="h-8 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
          @click="open(o)"
        >
          {{ t('warehouse.order.checkDelivery') }}
        </button>
      </header>

      <div v-if="openId === o.id" class="border-t">
        <div class="hidden grid-cols-[minmax(0,1fr)_5rem_9rem_7rem] gap-2 px-4 pt-2 text-xs text-muted-foreground sm:grid">
          <span>{{ t('warehouse.product') }}</span>
          <span>{{ t('warehouse.order.delivered') }}</span>
          <span>{{ t('warehouse.expirationDate') }}</span>
          <span>{{ t('warehouse.batchLotNumber') }}</span>
        </div>
        <div
          v-for="l in o.lines"
          :key="l.id"
          class="grid grid-cols-2 gap-2 border-t px-4 py-2 first-of-type:border-t-0 sm:grid-cols-[minmax(0,1fr)_5rem_9rem_7rem] sm:items-center"
        >
          <div class="col-span-2 min-w-0 sm:col-span-1">
            <div class="truncate text-sm font-medium">{{ l.product_name }}</div>
            <div class="text-xs text-muted-foreground">{{ t('warehouse.order.orderedQty', { count: l.quantity }) }}</div>
          </div>
          <input
            v-model="drafts[l.id]!.quantity"
            inputmode="numeric"
            :aria-label="t('warehouse.order.deliveredFor', { name: l.product_name })"
            class="h-8 w-full rounded-md border border-input bg-background px-2 text-sm tabular-nums"
            :class="{ 'border-amber-500': parsed(l.id) !== l.quantity }"
          />
          <input
            v-model="drafts[l.id]!.expiration_date"
            type="date"
            :aria-label="t('warehouse.expirationDate')"
            class="h-8 w-full rounded-md border border-input bg-background px-2 text-sm"
          />
          <input
            v-model="drafts[l.id]!.batch_number"
            :placeholder="t('warehouse.optional')"
            :aria-label="t('warehouse.batchLotNumber')"
            class="col-span-2 h-8 w-full rounded-md border border-input bg-background px-2 text-sm sm:col-span-1"
          />
        </div>
        <footer class="flex flex-wrap items-center justify-between gap-2 border-t px-4 py-2.5">
          <button
            class="text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline disabled:opacity-50"
            :disabled="busyOrderId === o.id"
            :title="t('warehouse.order.closeWithoutBookingHint')"
            @click="emit('close', o)"
          >
            {{ t('warehouse.order.closeWithoutBooking') }}
          </button>
          <div class="flex gap-2">
            <button class="h-8 rounded-md px-3 text-xs font-medium text-muted-foreground hover:bg-muted" @click="openId = null">
              {{ t('common.cancel') }}
            </button>
            <button
              class="h-8 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              :disabled="busyOrderId === o.id"
              @click="receive(o)"
            >
              {{ t('warehouse.order.bookAll') }}
            </button>
          </div>
        </footer>
      </div>
    </section>
  </div>
</template>
