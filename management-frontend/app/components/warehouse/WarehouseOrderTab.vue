<script setup lang="ts">
import { ref } from 'vue'
import { IconCopy, IconMinus, IconPlus, IconTruckDelivery, IconX } from '@tabler/icons-vue'
import { formatCurrency, formatDate } from '@/lib/utils'
import { getProductImageUrl } from '@/composables/useProducts'
import { leadDays, intervalDays, orderText, type OrderGroup, type OrderSettings, type OrderSupplier } from '@/lib/orderSuggestion'
import type { PurchaseOrder } from '@/composables/usePurchaseOrders'

/**
 * Warehouse "Order" tab: one card per supplier with the suggested lines,
 * then the orders that are on the way. All writes go through the page.
 */
const props = defineProps<{
  groups: OrderGroup[]
  openOrders: PurchaseOrder[]
  suppliers: OrderSupplier[]
  settings: OrderSettings
  isAdmin: boolean
  busySupplierId: string | null
}>()

const emit = defineEmits<{
  setCartons: [productId: string, cartons: number]
  resetCartons: [productId: string]
  removeManual: [productId: string]
  addProduct: [productId: string]
  assignSupplier: [productId: string, supplierId: string]
  place: [group: OrderGroup]
  cancelOrder: [order: PurchaseOrder]
  checkDelivery: [order: PurchaseOrder]
  openSetup: [section: 'suppliers' | 'calculation']
}>()

const { t, locale } = useI18n()

const copiedFor = ref<string | null>(null)

async function copy(group: OrderGroup) {
  const text = orderText(group, {
    customerNumber: t('warehouse.order.customerNumber'),
    carton: (n, size) => t('warehouse.order.cartonsOf', { count: n, size }),
  })
  try {
    await navigator.clipboard.writeText(text)
    copiedFor.value = group.supplier?.id ?? null
    setTimeout(() => { copiedFor.value = null }, 2000)
  } catch {
    window.prompt(t('warehouse.order.copyFallback'), text)
  }
}

function orderedUnits(o: PurchaseOrder) {
  return o.lines.reduce((s, l) => s + l.quantity, 0)
}

function viaLabel(via: string) {
  return t(`warehouse.order.via.${via}`)
}

function onAdd(e: Event) {
  const el = e.target as HTMLSelectElement
  if (el.value) emit('addProduct', el.value)
  el.value = ''
}

function onAssign(productId: string, e: Event) {
  const el = e.target as HTMLSelectElement
  if (el.value) emit('assignSupplier', productId, el.value)
  el.value = ''
}

const pill = 'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium'
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="flex flex-col gap-1 text-sm text-muted-foreground">
      <p>
        {{ settings.countMachineStock ? t('warehouse.order.introWithMachines', { buffer: settings.bufferDays }) : t('warehouse.order.intro', { buffer: settings.bufferDays }) }}
        <button v-if="isAdmin" class="font-medium text-foreground underline-offset-2 hover:underline" @click="emit('openSetup', 'calculation')">
          {{ t('warehouse.order.changeCalculation') }}
        </button>
      </p>
      <p class="flex flex-wrap items-center gap-1.5">
        <span :class="[pill, 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400']">{{ t('warehouse.order.auto') }}</span>
        {{ t('warehouse.order.autoHint') }}
        <span :class="[pill, 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-400']">{{ t('warehouse.order.manual') }}</span>
        {{ t('warehouse.order.manualHint') }}
      </p>
    </div>

    <div v-if="groups.every(g => g.lines.length === 0)" class="rounded-lg border p-8 text-center text-sm text-muted-foreground">
      {{ t('warehouse.order.nothingToOrder') }}
    </div>

    <section v-for="g in groups" :key="g.supplier?.id ?? 'none'" class="overflow-hidden rounded-lg border">
      <header class="flex flex-wrap items-start justify-between gap-2 px-4 py-3">
        <div class="min-w-0">
          <h3 class="font-semibold">{{ g.supplier ? g.supplier.name : t('warehouse.order.noSupplier') }}</h3>
          <p class="text-xs text-muted-foreground">
            <template v-if="g.supplier">
              <template v-if="g.supplier.customer_number">{{ t('warehouse.order.customerNumber') }} {{ g.supplier.customer_number }} · </template>
              {{ t('warehouse.order.rhythm', { lead: leadDays(g.supplier, settings), interval: intervalDays(g.supplier, settings) }) }}
            </template>
            <template v-else>{{ isAdmin ? t('warehouse.order.noSupplierHint') : t('warehouse.order.noSupplierHintViewer') }}</template>
          </p>
        </div>
        <span v-if="g.belowMinOrder" :class="[pill, 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-400']">
          {{ t('warehouse.order.belowMinOrder', { amount: formatCurrency(g.supplier?.min_order_value, locale) }) }}
        </span>
        <span v-else-if="g.hasEmpty" :class="[pill, 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400']">
          {{ t('warehouse.order.containsEmpty') }}
        </span>
      </header>

      <div v-if="g.lines.length" class="overflow-x-auto border-t">
        <table class="w-full min-w-[640px] text-sm">
          <thead class="bg-muted/50 text-xs text-muted-foreground">
            <tr>
              <th class="px-4 py-2 text-left font-medium">{{ t('warehouse.product') }}</th>
              <th class="px-4 py-2 text-left font-medium">{{ t('warehouse.order.why') }}</th>
              <th class="px-4 py-2 text-right font-medium">{{ t('warehouse.order.available') }}</th>
              <th class="px-4 py-2 text-right font-medium">{{ t('warehouse.order.quantity') }}</th>
              <th class="px-4 py-2 text-right font-medium">{{ t('warehouse.order.netValue') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in g.lines" :key="l.product.product_id" class="border-t align-top">
              <td class="px-4 py-2.5">
                <div class="flex items-center gap-2">
                  <img v-if="l.product.image_path" :src="getProductImageUrl(l.product.image_path)" class="size-8 rounded object-cover" alt="" />
                  <div v-else class="flex size-8 shrink-0 items-center justify-center rounded bg-muted text-xs font-medium text-muted-foreground">{{ l.product.product_name.charAt(0) }}</div>
                  <span class="font-medium">{{ l.product.product_name }}</span>
                </div>
              </td>
              <td class="px-4 py-2.5">
                <div class="flex flex-wrap gap-1.5">
                  <span v-if="l.auto" :class="[pill, 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400']">
                    {{ t('warehouse.order.autoReason', { reach: l.reachDays ?? 0, target: l.targetDays }) }}
                  </span>
                  <span
                    v-if="l.manual"
                    :class="[pill, 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-400']"
                    :title="formatDate(l.manual.created_at, locale)"
                  >
                    {{ t('warehouse.order.manualBy', { name: l.manual.added_by_name || '?', via: viaLabel(l.manual.added_via) }) }}
                    <button :aria-label="t('warehouse.order.removeManual')" class="-mr-0.5 rounded-full hover:bg-blue-200 dark:hover:bg-blue-900" @click="emit('removeManual', l.product.product_id)">
                      <IconX class="size-3" />
                    </button>
                  </span>
                  <button
                    v-if="l.overridden"
                    :class="[pill, 'bg-muted text-muted-foreground hover:text-foreground']"
                    @click="emit('resetCartons', l.product.product_id)"
                  >
                    {{ t('warehouse.order.resetQuantity') }}
                  </button>
                </div>
                <select
                  v-if="!g.supplier && isAdmin"
                  class="mt-1.5 h-8 max-w-full rounded-md border border-input bg-background px-2 text-xs"
                  :aria-label="t('warehouse.order.assignSupplierFor', { name: l.product.product_name })"
                  @change="onAssign(l.product.product_id, $event)"
                >
                  <option value="">{{ t('warehouse.order.assignSupplier') }}</option>
                  <option v-for="s in suppliers" :key="s.id" :value="s.id">{{ s.name }}</option>
                </select>
              </td>
              <td class="px-4 py-2.5 text-right tabular-nums">
                {{ l.available }}
                <div class="text-xs text-muted-foreground">
                  {{ l.reachDays === null ? t('warehouse.order.noSales') : t('warehouse.order.lasts', { days: l.reachDays }) }}
                </div>
                <div v-if="l.product.on_order > 0" class="text-xs text-violet-600 dark:text-violet-400">
                  {{ t('warehouse.order.onTheWayUnits', { count: l.product.on_order }) }}
                </div>
              </td>
              <td class="px-4 py-2.5 text-right">
                <div class="inline-flex items-center overflow-hidden rounded-md border">
                  <button
                    class="flex size-7 items-center justify-center bg-muted/50 hover:bg-muted disabled:opacity-40"
                    :aria-label="t('warehouse.order.less')"
                    :disabled="l.cartons === 0"
                    @click="emit('setCartons', l.product.product_id, l.cartons - 1)"
                  >
                    <IconMinus class="size-3.5" />
                  </button>
                  <span class="min-w-8 px-1 text-center tabular-nums">{{ l.cartons }}</span>
                  <button
                    class="flex size-7 items-center justify-center bg-muted/50 hover:bg-muted"
                    :aria-label="t('warehouse.order.more')"
                    @click="emit('setCartons', l.product.product_id, l.cartons + 1)"
                  >
                    <IconPlus class="size-3.5" />
                  </button>
                </div>
                <div class="mt-0.5 text-xs text-muted-foreground">
                  {{ l.product.unit_size > 1 ? t('warehouse.order.cartonUnits', { size: l.product.unit_size, count: l.units }) : t('warehouse.unitsShort', { count: l.units }) }}
                </div>
              </td>
              <td class="px-4 py-2.5 text-right tabular-nums">{{ l.value === null ? '—' : formatCurrency(l.value, locale) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="flex flex-wrap items-center justify-between gap-2 border-t px-4 py-2.5">
        <div class="flex flex-wrap items-center gap-2 text-sm">
          <span v-if="g.lines.length" class="font-medium">
            {{ t('warehouse.order.lineCount', { count: g.lines.length }, g.lines.length) }} · {{ formatCurrency(g.value, locale) }}
          </span>
          <span v-else class="text-muted-foreground">{{ t('warehouse.order.nothingDue') }}</span>
          <select
            v-if="g.supplier && g.others.length"
            class="h-8 rounded-md border border-input bg-background px-2 text-xs"
            :aria-label="t('warehouse.order.addProduct')"
            @change="onAdd"
          >
            <option value="">{{ t('warehouse.order.addProduct') }}</option>
            <option v-for="p in g.others" :key="p.product_id" :value="p.product_id">{{ p.product_name }}</option>
          </select>
        </div>
        <div v-if="g.supplier && g.lines.length" class="flex flex-wrap gap-2">
          <button class="inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-xs font-medium hover:bg-muted" @click="copy(g)">
            <IconCopy class="size-3.5" />
            {{ copiedFor === g.supplier.id ? t('warehouse.order.copied') : t('warehouse.order.copyList') }}
          </button>
          <button
            v-if="isAdmin"
            class="inline-flex h-8 items-center gap-1.5 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            :disabled="busySupplierId === g.supplier.id || g.lines.every(l => l.cartons === 0)"
            @click="emit('place', g)"
          >
            {{ t('warehouse.order.markOrdered') }}
          </button>
        </div>
      </footer>
    </section>

    <template v-if="openOrders.length">
      <h3 class="mt-2 text-sm font-semibold">{{ t('warehouse.order.onTheWay') }}</h3>
      <div v-for="o in openOrders" :key="o.id" class="flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div class="flex min-w-0 items-center gap-3">
          <IconTruckDelivery class="size-5 shrink-0 text-violet-600 dark:text-violet-400" />
          <div class="min-w-0">
            <div class="font-medium">{{ o.supplier_name || t('warehouse.order.noSupplier') }}</div>
            <div class="text-xs text-muted-foreground">
              {{ t('warehouse.order.orderedMeta', { date: formatDate(o.created_at, locale), count: o.lines.length, units: orderedUnits(o) }) }}
              <template v-if="o.ordered_by_name"> · {{ o.ordered_by_name }}</template>
            </div>
          </div>
        </div>
        <div class="flex gap-2">
          <button v-if="isAdmin" class="h-8 rounded-md px-3 text-xs font-medium text-muted-foreground hover:bg-muted hover:text-foreground" @click="emit('cancelOrder', o)">
            {{ t('warehouse.order.cancelOrder') }}
          </button>
          <button class="h-8 rounded-md border px-3 text-xs font-medium hover:bg-muted" @click="emit('checkDelivery', o)">
            {{ t('warehouse.order.checkDelivery') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
