<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

import { useProductDetail } from '~/composables/useProductDetail'
import { useWarehouse } from '~/composables/useWarehouse'
import { useProducts } from '~/composables/useProducts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { IconArrowLeft, IconPencil } from '@tabler/icons-vue'
import { timeAgo, formatCurrency, formatDate, formatDateTime } from '~/lib/utils'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const { transactionTypeLabel, transactionTypeBadgeClass } = useWarehouse()
const { products, fetchProducts } = useProducts()

const productId = computed(() => route.params.id as string)
const detail = useProductDetail(productId)

const editModalOpen = ref(false)

// On direct-link / deep-link landings the shared useProducts state may still be
// empty. ProductFormModal resolves `editingProduct` from that state; if it's
// empty when the user clicks Edit, the modal falls back to create-mode and a
// subsequent Save would insert a duplicate product. Populate it up-front.
onMounted(async () => {
  const jobs: Promise<unknown>[] = [detail.refresh()]
  if (products.value.length === 0) jobs.push(fetchProducts())
  await Promise.all(jobs)
})
watch(productId, () => detail.refresh())

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/products')
}

function onEditSaved() {
  editModalOpen.value = false
  detail.refresh()
}
</script>

<template>
  <div class="container mx-auto max-w-6xl px-4 py-6 space-y-6">
    <!-- Error banner -->
    <div
      v-if="detail.error.value"
      class="flex items-start justify-between gap-3 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm"
      role="alert"
    >
      <div>
        <p class="font-medium text-destructive">{{ t('products.detail.error.title') }}</p>
        <p class="mt-0.5 text-muted-foreground">{{ detail.error.value }}</p>
      </div>
      <Button variant="outline" size="sm" @click="detail.refresh()">
        {{ t('products.detail.error.retry') }}
      </Button>
    </div>

    <!-- Header -->
    <div class="flex items-start gap-3">
      <Button variant="ghost" size="icon" @click="goBack">
        <IconArrowLeft class="size-5" />
      </Button>

      <template v-if="detail.loading.value && !detail.product.value">
        <div class="h-16 flex-1 animate-pulse rounded-md bg-muted" />
      </template>

      <template v-else-if="detail.notFound.value">
        <div class="flex-1 rounded-md border border-destructive/20 bg-destructive/5 p-4">
          <p class="font-medium">{{ t('products.detail.notFound.title') }}</p>
          <NuxtLink to="/products" class="mt-1 inline-block text-sm underline">
            {{ t('products.detail.notFound.back') }}
          </NuxtLink>
        </div>
      </template>

      <template v-else-if="detail.product.value">
        <img
          v-if="detail.product.value.image_url"
          :src="detail.product.value.image_url"
          :alt="detail.product.value.name"
          class="size-16 rounded-md object-cover border"
        />
        <div v-else class="size-16 rounded-md border bg-muted" />

        <div class="flex-1">
          <div class="flex items-center gap-2">
            <h1 class="text-2xl font-semibold">{{ detail.product.value.name }}</h1>
            <Badge v-if="detail.product.value.discontinued" variant="secondary">
              {{ t('products.detail.header.discontinued') }}
            </Badge>
          </div>
          <p v-if="detail.product.value.category_name" class="text-sm text-muted-foreground">
            {{ detail.product.value.category_name }}
          </p>
          <div v-if="detail.barcodes.value.length" class="mt-1 flex flex-wrap gap-1">
            <span
              v-for="b in detail.barcodes.value"
              :key="b.id"
              class="rounded-full border bg-muted/50 px-2 py-0.5 text-xs font-mono"
            >
              {{ b.barcode }}
            </span>
          </div>
        </div>

        <Button variant="outline" @click="editModalOpen = true">
          <IconPencil class="mr-2 size-4" />
          {{ t('products.detail.header.edit') }}
        </Button>
      </template>
    </div>

    <div v-if="detail.kpis.value" class="grid grid-cols-2 gap-3 md:grid-cols-4">
      <Card>
        <CardHeader class="pb-2"><CardTitle class="text-sm font-medium text-muted-foreground">{{ t('products.detail.kpi.warehouseStock') }}</CardTitle></CardHeader>
        <CardContent>
          <div class="text-2xl font-semibold">{{ detail.kpis.value.warehouse_total_qty }}</div>
          <p class="text-xs text-muted-foreground">{{ t('products.detail.kpi.warehouseCount', { n: detail.kpis.value.warehouse_count }) }}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader class="pb-2"><CardTitle class="text-sm font-medium text-muted-foreground">{{ t('products.detail.kpi.machineStock') }}</CardTitle></CardHeader>
        <CardContent>
          <div class="text-2xl font-semibold">{{ detail.kpis.value.tray_total_stock }} / {{ detail.kpis.value.tray_total_capacity }}</div>
          <p class="text-xs text-muted-foreground">{{ t('products.detail.kpi.machineCount', { n: detail.kpis.value.machine_count }) }}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader class="pb-2"><CardTitle class="text-sm font-medium text-muted-foreground">{{ t('products.detail.kpi.salesToday') }}</CardTitle></CardHeader>
        <CardContent>
          <div class="text-2xl font-semibold">{{ detail.kpis.value.sales_today_units }}</div>
          <p class="text-xs text-muted-foreground">{{ formatCurrency(detail.kpis.value.sales_today_revenue) }}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader class="pb-2"><CardTitle class="text-sm font-medium text-muted-foreground">{{ t('products.detail.kpi.velocity') }}</CardTitle></CardHeader>
        <CardContent>
          <div class="text-2xl font-semibold">{{ detail.kpis.value.velocity_units_per_day.toFixed(1) }}</div>
          <p class="text-xs text-muted-foreground">{{ t('products.detail.kpi.velocitySubtitle', { n: detail.kpis.value.velocity_window_days }) }}</p>
        </CardContent>
      </Card>
    </div>

    <div v-if="detail.product.value" class="grid gap-3 md:grid-cols-2">
      <ChartAreaInteractive
        :data="detail.chartRevenue.value"
        :title="t('products.detail.chart.revenueTitle')"
        :description="t('products.detail.chart.revenueDescription')"
        :count-label="t('charts.units')"
      />
      <ChartAreaInteractive
        :data="detail.chartUnits.value"
        :title="t('products.detail.chart.unitsTitle')"
        :description="t('products.detail.chart.unitsDescription')"
        kind="units"
        color="purple"
      />
    </div>

    <section class="space-y-2" aria-labelledby="sec-warehouse">
      <h2 id="sec-warehouse" class="text-lg font-semibold">{{ t('products.detail.sections.warehouseStock') }}</h2>
      <p v-if="!detail.warehouseStock.value.length" class="text-sm text-muted-foreground">
        {{ t('products.detail.empty.noStock') }}
      </p>
      <div v-else class="space-y-2">
        <details
          v-for="w in detail.warehouseStock.value"
          :key="w.warehouse_id"
          class="rounded-md border"
        >
          <summary class="flex cursor-pointer items-center justify-between px-3 py-2 text-sm">
            <span class="font-medium">{{ w.warehouse_name }}</span>
            <span class="flex items-center gap-2">
              <span
                v-if="w.min_quantity !== null && w.total_qty < w.min_quantity"
                class="rounded-full bg-destructive/10 px-2 py-0.5 text-xs text-destructive"
              >
                {{ t('products.detail.warehouseStock.belowMin', { min: w.min_quantity }) }}
              </span>
              <span class="font-mono">{{ w.total_qty }}</span>
            </span>
          </summary>
          <table class="w-full border-t text-sm">
            <thead class="bg-muted/40 text-xs uppercase">
              <tr>
                <th class="px-3 py-1.5 text-left">{{ t('products.detail.warehouseStock.batchNumber') }}</th>
                <th class="px-3 py-1.5 text-left">{{ t('products.detail.warehouseStock.expiry') }}</th>
                <th class="px-3 py-1.5 text-left">{{ t('products.detail.warehouseStock.intake') }}</th>
                <th class="px-3 py-1.5 text-right">{{ t('products.detail.warehouseStock.qty') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in w.batches" :key="b.id" class="border-t">
                <td class="px-3 py-1.5 font-mono">{{ b.batch_number ?? '—' }}</td>
                <td class="px-3 py-1.5">{{ formatDate(b.expiration_date) }}</td>
                <td class="px-3 py-1.5">{{ formatDate(b.created_at) }}</td>
                <td class="px-3 py-1.5 text-right font-mono">{{ b.quantity }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </div>
    </section>

    <section class="space-y-2" aria-labelledby="sec-trays">
      <h2 id="sec-trays" class="text-lg font-semibold">{{ t('products.detail.sections.machineTrays') }}</h2>
      <p v-if="!detail.machineTrays.value.length" class="text-sm text-muted-foreground">
        {{ t('products.detail.empty.noTrays') }}
      </p>
      <div v-else class="overflow-x-auto rounded-md border">
        <table class="w-full text-sm">
          <thead class="bg-muted/40 text-xs uppercase">
            <tr>
              <th class="px-3 py-2 text-left">{{ t('products.detail.trays.machine') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.trays.slot') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.trays.stock') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.trays.fillWhenBelow') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.trays.lastSale') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t2 in detail.machineTrays.value" :key="t2.id" class="border-t">
              <td class="px-3 py-2">
                <NuxtLink
                  :to="`/machines/${t2.machine_id}?tab=stock`"
                  class="text-primary hover:underline"
                >
                  {{ t2.machine_name }}
                </NuxtLink>
              </td>
              <td class="px-3 py-2 font-mono">{{ t2.item_number }}</td>
              <td class="px-3 py-2 text-right font-mono">{{ t2.current_stock }} / {{ t2.capacity }}</td>
              <td class="px-3 py-2 text-right font-mono">{{ t2.fill_when_below ?? '—' }}</td>
              <td class="px-3 py-2 text-right text-muted-foreground">{{ t2.last_sale_at ? timeAgo(t2.last_sale_at) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="space-y-2" aria-labelledby="sec-top">
      <h2 id="sec-top" class="text-lg font-semibold">{{ t('products.detail.sections.topMachines') }}</h2>
      <p v-if="!detail.kpis.value || !detail.kpis.value.top_machines.length" class="text-sm text-muted-foreground">
        {{ t('products.detail.empty.noSales') }}
      </p>
      <div v-else class="overflow-x-auto rounded-md border">
        <table class="w-full text-sm">
          <thead class="bg-muted/40 text-xs uppercase">
            <tr>
              <th class="px-3 py-2 text-left">{{ t('products.detail.topMachines.machine') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.topMachines.units') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.topMachines.revenue') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in detail.kpis.value.top_machines" :key="m.machine_id" class="border-t">
              <td class="px-3 py-2">
                <NuxtLink :to="`/machines/${m.machine_id}?tab=sales`" class="text-primary hover:underline">
                  {{ m.machine_name }}
                </NuxtLink>
              </td>
              <td class="px-3 py-2 text-right font-mono">{{ m.units }}</td>
              <td class="px-3 py-2 text-right font-mono">{{ formatCurrency(m.revenue) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="space-y-2" aria-labelledby="sec-sales">
      <h2 id="sec-sales" class="text-lg font-semibold">{{ t('products.detail.sections.recentSales') }}</h2>
      <p v-if="!detail.recentSales.value.length" class="text-sm text-muted-foreground">
        {{ t('products.detail.empty.noSales') }}
      </p>
      <div v-else class="overflow-x-auto rounded-md border">
        <table class="w-full text-sm">
          <thead class="bg-muted/40 text-xs uppercase">
            <tr>
              <th class="px-3 py-2 text-left">{{ t('products.detail.sales.time') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.sales.machine') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.sales.channel') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.sales.price') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in detail.recentSales.value" :key="s.id" class="border-t">
              <td class="px-3 py-2 text-muted-foreground">{{ formatDateTime(s.created_at) }}</td>
              <td class="px-3 py-2">
                <NuxtLink
                  v-if="s.machine_id"
                  :to="`/machines/${s.machine_id}?tab=sales`"
                  class="text-primary hover:underline"
                >
                  {{ s.machine_name ?? '—' }}
                </NuxtLink>
                <span v-else>—</span>
              </td>
              <td class="px-3 py-2">{{ s.channel ?? '—' }}</td>
              <td class="px-3 py-2 text-right font-mono">{{ s.item_price !== null ? formatCurrency(s.item_price) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="space-y-2" aria-labelledby="sec-tx">
      <h2 id="sec-tx" class="text-lg font-semibold">{{ t('products.detail.sections.history') }}</h2>
      <p v-if="!detail.transactions.value.length" class="text-sm text-muted-foreground">
        {{ t('products.detail.empty.noTransactions') }}
      </p>
      <div v-else class="overflow-x-auto rounded-md border">
        <table class="w-full text-sm">
          <thead class="bg-muted/40 text-xs uppercase">
            <tr>
              <th class="px-3 py-2 text-left">{{ t('products.detail.history.time') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.history.warehouse') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.history.type') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.history.change') }}</th>
              <th class="px-3 py-2 text-right">{{ t('products.detail.history.after') }}</th>
              <th class="px-3 py-2 text-left">{{ t('products.detail.history.user') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tx in detail.transactions.value" :key="tx.id" class="border-t">
              <td class="px-3 py-2 text-muted-foreground">{{ formatDateTime(tx.created_at) }}</td>
              <td class="px-3 py-2">{{ tx.warehouse_name }}</td>
              <td class="px-3 py-2">
                <span
                  class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
                  :class="transactionTypeBadgeClass(tx.transaction_type)"
                >
                  {{ transactionTypeLabel(tx.transaction_type) }}
                </span>
              </td>
              <td
                class="px-3 py-2 text-right font-mono"
                :class="tx.quantity_change >= 0 ? 'text-emerald-600' : 'text-destructive'"
              >
                {{ tx.quantity_change > 0 ? '+' : '' }}{{ tx.quantity_change }}
              </td>
              <td class="px-3 py-2 text-right font-mono">{{ tx.quantity_after ?? '—' }}</td>
              <td class="px-3 py-2">{{ tx.user_display }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <ProductFormModal
      v-model:open="editModalOpen"
      :product-id="productId"
      @saved="onEditSaved"
    />
  </div>
</template>
