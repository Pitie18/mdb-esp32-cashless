<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { IconArrowLeft, IconCheck, IconPlayerSkipForward, IconTruck } from '@tabler/icons-vue'
import { getProductImageUrl } from '@/composables/useProducts'
import { useRefillWizard, hasSavedTour } from '@/composables/useRefillWizard'
import { formatCurrency } from '@/lib/utils'

const { t, locale } = useI18n()
const router = useRouter()
const { warehouses, fetchWarehouses } = useWarehouse()

const {
  currentStep, machines, currentMachineIndex, selectedWarehouseId,
  loading, tourStarting, confirmingRefill,
  currentTrays, currentTraysLoading,
  tourLog, tourSummary, currentMachine, totalMachinesInTour, currentMachineNumber,
  completedMachineIds, allMachinesCompleted,
  pickingMode, sortedMachines, combinedPickList, allPackedCombined,
  isPacked, togglePacked, allPacked, effectiveDeficit,
  isOutOfWarehouseStock, hasPartialStock, hasAnyPackedItems, effectiveStockHealth,
  isPackedCombined, togglePackedCombined, effectiveDeficitCombined,
  setCustomQuantity, getCustomQuantity, getMaxCustomQuantity, getDisplayedPackingQty,
  initTour, loadWarehouseStock, startTour,
  adjustFillAmount, confirmMachineRefill, confirmError, skipMachine, goToMachine, isMachineCompleted, resetWizard,
  resumeTour,
} = useRefillWizard()

// Init — auto-resume saved tour if available, otherwise start fresh
onMounted(async () => {
  if (hasSavedTour()) {
    const resumed = await resumeTour()
    if (resumed) return
  }
  await fetchWarehouses()
  if (warehouses.value.length > 0) {
    selectedWarehouseId.value = warehouses.value[0]!.id
  }
  await initTour()
  await loadWarehouseStock()
})

watch(selectedWarehouseId, () => loadWarehouseStock())

function goBack() {
  if (currentStep.value === 'packing') {
    router.push('/machines')
  }
}

// Check if confirming this machine would complete all remaining machines
const wouldFinishTour = computed(() => {
  const remaining = machines.value.filter(m => !isMachineCompleted(m.id))
  return remaining.length <= 1
})

// Current machine already done?
const currentMachineDone = computed(() =>
  currentMachine.value ? isMachineCompleted(currentMachine.value.id) : false
)
</script>

<template>
  <div class="flex flex-1 flex-col gap-3 p-3 pb-0 sm:gap-4 sm:p-4 md:p-6">
    <!-- Header -->
    <div class="flex items-center gap-3">
      <button
        v-if="currentStep === 'packing'"
        class="inline-flex h-10 w-10 items-center justify-center rounded-md border hover:bg-muted"
        @click="goBack"
      >
        <IconArrowLeft class="h-4 w-4" />
      </button>
      <h1 class="text-xl font-semibold sm:text-2xl">{{ t('refill.title') }}</h1>
    </div>

    <!-- Step indicator — compact on mobile -->
    <div class="flex items-center gap-1.5 sm:gap-2 text-sm">
      <span
        class="inline-flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium"
        :class="currentStep === 'packing' ? 'bg-primary text-primary-foreground' : currentStep === 'refill' || currentStep === 'summary' ? 'bg-green-600 text-white' : 'bg-muted text-muted-foreground'"
      >
        <IconCheck v-if="currentStep !== 'packing'" class="h-3.5 w-3.5" />
        <template v-else>1</template>
      </span>
      <span class="hidden sm:inline" :class="currentStep === 'packing' ? 'font-medium' : 'text-muted-foreground'">{{ t('refill.packingStep') }}</span>
      <span class="text-muted-foreground mx-0.5">&rarr;</span>
      <span
        class="inline-flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium"
        :class="currentStep === 'refill' ? 'bg-primary text-primary-foreground' : currentStep === 'summary' ? 'bg-green-600 text-white' : 'bg-muted text-muted-foreground'"
      >
        <IconCheck v-if="currentStep === 'summary'" class="h-3.5 w-3.5" />
        <template v-else>2</template>
      </span>
      <span class="hidden sm:inline" :class="currentStep === 'refill' ? 'font-medium' : 'text-muted-foreground'">{{ t('refill.refillStep') }}</span>
      <span class="text-muted-foreground mx-0.5">&rarr;</span>
      <span
        class="inline-flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium"
        :class="currentStep === 'summary' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'"
      >3</span>
      <span class="hidden sm:inline" :class="currentStep === 'summary' ? 'font-medium' : 'text-muted-foreground'">{{ t('refill.summaryStep') }}</span>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="text-muted-foreground">{{ t('common.loading') }}</div>

    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <!-- STEP 1: PACKING -->
    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <template v-else-if="currentStep === 'packing'">
      <!-- No machines need refill -->
      <div v-if="machines.length === 0" class="text-muted-foreground py-8 text-center">
        {{ t('refill.noMachinesNeedRefill') }}
      </div>

      <template v-else>
        <!-- Warehouse selector -->
        <div v-if="warehouses.length > 0" class="flex items-center gap-2 rounded-lg border bg-muted/30 px-3 py-2.5">
          <label class="shrink-0 text-sm text-muted-foreground">{{ t('refill.selectWarehouse') }}</label>
          <select
            v-model="selectedWarehouseId"
            class="h-9 min-w-0 flex-1 rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            <option v-for="wh in warehouses" :key="wh.id" :value="wh.id">{{ wh.name }}</option>
          </select>
        </div>

        <!-- Picking mode toggle -->
        <div class="flex items-center gap-1 rounded-lg border p-1">
          <button
            class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
            :class="pickingMode === 'per-machine' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
            @click="pickingMode = 'per-machine'"
          >
            {{ t('refill.perMachine') }}
          </button>
          <button
            class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
            :class="pickingMode === 'combined' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
            @click="pickingMode = 'combined'"
          >
            {{ t('refill.combined') }}
          </button>
        </div>

        <!-- ── COMBINED PICKING MODE ──────────────────────────────────── -->
        <div v-if="pickingMode === 'combined'" class="flex flex-col gap-3 pb-20 sm:pb-24">
          <Card>
            <CardContent class="space-y-1 px-4 py-3 sm:px-6">
              <div class="flex items-center justify-between mb-2">
                <p class="text-xs font-medium text-muted-foreground uppercase tracking-wide">{{ t('refill.packForMachine') }}</p>
                <span
                  v-if="allPackedCombined"
                  class="text-xs font-medium text-green-600"
                >
                  {{ t('refill.allPacked') }}
                </span>
              </div>
              <ul class="space-y-0.5">
                <li
                  v-for="item in combinedPickList"
                  :key="item.product_id"
                  class="flex items-center gap-2.5 rounded-lg px-2 py-2.5 -mx-2 transition-colors cursor-pointer select-none hover:bg-muted/50 active:bg-muted"
                  @click="togglePackedCombined(item.product_id)"
                >
                  <!-- Checkbox -->
                  <span
                    class="flex h-6 w-6 shrink-0 items-center justify-center rounded border-2 transition-colors"
                    :class="isPackedCombined(item.product_id)
                      ? 'bg-primary border-primary text-primary-foreground'
                      : 'border-muted-foreground/30'"
                  >
                    <svg v-if="isPackedCombined(item.product_id)" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="h-3.5 w-3.5"><polyline points="20 6 9 17 4 12" /></svg>
                  </span>
                  <!-- Product image -->
                  <img
                    v-if="item.image_path"
                    :src="getProductImageUrl(item.image_path)"
                    :alt="item.product_name"
                    class="h-10 w-10 shrink-0 rounded object-cover transition-opacity"
                    :class="isPackedCombined(item.product_id) ? 'opacity-40' : ''"
                  />
                  <span
                    v-else
                    class="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-muted text-xs text-muted-foreground transition-opacity"
                    :class="isPackedCombined(item.product_id) ? 'opacity-40' : ''"
                  >
                    {{ item.product_name.charAt(0) }}
                  </span>
                  <!-- Product info -->
                  <div class="min-w-0 flex-1 transition-all" :class="isPackedCombined(item.product_id) ? 'text-muted-foreground/50' : ''">
                    <span class="text-sm block">
                      {{ effectiveDeficitCombined(item.product_id) }}&times; {{ item.product_name }}
                      <span v-if="item.sellprice != null" class="ml-1 text-xs font-medium text-muted-foreground tabular-nums">
                        {{ formatCurrency(item.sellprice, locale) }}
                      </span>
                    </span>
                    <span class="text-xs text-muted-foreground block">
                      {{ t('refill.forMachines', { machines: item.machines.map(m => m.name).join(', ') }) }}
                    </span>
                    <span v-if="isPackedCombined(item.product_id)" class="text-[11px] text-primary/60 block">
                      {{ t('refill.adjustPerMachine') }}
                    </span>
                  </div>
                </li>
              </ul>
            </CardContent>
          </Card>
        </div>

        <!-- ── PER-MACHINE PICKING MODE ───────────────────────────────── -->
        <div v-else class="flex flex-col gap-3 pb-20 sm:pb-24 md:grid md:grid-cols-2 xl:grid-cols-3 md:gap-4">
          <Card
            v-for="machine in sortedMachines"
            :key="machine.id"
            class="h-full"
            :class="effectiveStockHealth(machine) === 'ok' ? 'opacity-40' : ''"
          >
            <CardHeader class="flex flex-row items-center justify-between space-y-0 pb-2 px-4 sm:px-6">
              <CardTitle class="text-base font-semibold truncate">
                {{ machine.name }}
              </CardTitle>
              <span
                class="ml-2 inline-block h-3 w-3 shrink-0 rounded-full"
                :class="{
                  'bg-red-500': effectiveStockHealth(machine) === 'critical',
                  'bg-amber-500': effectiveStockHealth(machine) === 'low',
                  'bg-blue-500': effectiveStockHealth(machine) === 'fill',
                  'bg-green-500': effectiveStockHealth(machine) === 'ok',
                }"
              />
            </CardHeader>

            <CardContent v-if="effectiveStockHealth(machine) !== 'ok'" class="space-y-3 px-4 sm:px-6">
              <!-- Stock bar -->
              <div class="flex items-center gap-2">
                <div class="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                  <div
                    class="h-full rounded-full transition-all"
                    :class="{
                      'bg-red-500': machine.stock_percent < 20,
                      'bg-amber-500': machine.stock_percent >= 20 && machine.stock_percent < 50,
                      'bg-green-500': machine.stock_percent >= 50,
                    }"
                    :style="{ width: `${machine.stock_percent}%` }"
                  />
                </div>
                <span class="text-xs font-medium text-muted-foreground w-8 text-right">{{ machine.stock_percent }}%</span>
              </div>

              <!-- Packing checklist -->
              <div v-if="machine.tray_summary.length > 0" class="space-y-2">
                <div class="flex items-center justify-between">
                  <p class="text-xs font-medium text-muted-foreground uppercase tracking-wide">{{ t('refill.packForMachine') }}</p>
                  <span
                    v-if="allPacked(machine.id, machine.tray_summary)"
                    class="text-xs font-medium text-green-600"
                  >
                    {{ t('refill.allPacked') }}
                  </span>
                </div>
                <ul class="space-y-0.5">
                  <li
                    v-for="item in machine.tray_summary"
                    :key="item.product_id ?? item.product_name"
                    class="flex items-center gap-2.5 rounded-lg px-2 py-2.5 -mx-2 transition-colors"
                    :class="isOutOfWarehouseStock(item)
                      ? 'opacity-50 cursor-not-allowed'
                      : 'cursor-pointer select-none hover:bg-muted/50 active:bg-muted'"
                    @click="togglePacked(machine.id, item)"
                  >
                    <!-- Checkbox — larger touch target -->
                    <span
                      class="flex h-6 w-6 shrink-0 items-center justify-center rounded border-2 transition-colors"
                      :class="isOutOfWarehouseStock(item, machine.id)
                        ? 'border-muted-foreground/20 bg-muted'
                        : isPacked(machine.id, item)
                          ? 'bg-primary border-primary text-primary-foreground'
                          : 'border-muted-foreground/30'"
                    >
                      <svg v-if="isPacked(machine.id, item) && !isOutOfWarehouseStock(item, machine.id)" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="h-3.5 w-3.5"><polyline points="20 6 9 17 4 12" /></svg>
                    </span>
                    <!-- Product image -->
                    <img
                      v-if="item.image_path"
                      :src="getProductImageUrl(item.image_path)"
                      :alt="item.product_name"
                      class="h-10 w-10 shrink-0 rounded object-cover transition-opacity"
                      :class="isPacked(machine.id, item) || isOutOfWarehouseStock(item, machine.id) ? 'opacity-40' : ''"
                    />
                    <span
                      v-else
                      class="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-muted text-xs text-muted-foreground transition-opacity"
                      :class="isPacked(machine.id, item) || isOutOfWarehouseStock(item, machine.id) ? 'opacity-40' : ''"
                    >
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="h-4 w-4"><rect width="18" height="18" x="3" y="3" rx="2" /><circle cx="9" cy="9" r="2" /><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" /></svg>
                    </span>
                    <!-- Product name + quantity -->
                    <span class="min-w-0 flex-1 text-sm transition-all" :class="isPacked(machine.id, item) ? 'text-muted-foreground/50' : ''">
                      <template v-if="isOutOfWarehouseStock(item, machine.id)">
                        <span class="text-muted-foreground">{{ item.deficit }}&times; {{ item.product_name }}</span>
                        <span v-if="item.sellprice != null" class="ml-1 text-xs font-medium text-muted-foreground tabular-nums">
                          {{ formatCurrency(item.sellprice, locale) }}
                        </span>
                        <span class="ml-1 text-xs text-red-500 dark:text-red-400">{{ t('machines.notInStock') }}</span>
                      </template>
                      <template v-else-if="isPacked(machine.id, item) && item.product_id && selectedWarehouseId">
                        <span class="truncate block">
                          {{ item.product_name }}
                          <span v-if="item.sellprice != null" class="ml-1 text-xs font-medium text-muted-foreground tabular-nums">
                            {{ formatCurrency(item.sellprice, locale) }}
                          </span>
                        </span>
                        <span v-if="hasPartialStock(item, machine.id)" class="text-xs text-amber-500 dark:text-amber-400">{{ t('machines.needed', { count: item.deficit }) }}</span>
                      </template>
                      <template v-else-if="hasPartialStock(item, machine.id)">
                        {{ effectiveDeficit(item, machine.id) }}&times; {{ item.product_name }}
                        <span v-if="item.sellprice != null" class="ml-1 text-xs font-medium text-muted-foreground tabular-nums">
                          {{ formatCurrency(item.sellprice, locale) }}
                        </span>
                        <span class="ml-1 text-xs text-amber-500 dark:text-amber-400">{{ t('machines.needed', { count: item.deficit }) }}</span>
                      </template>
                      <template v-else>
                        {{ effectiveDeficit(item, machine.id) }}&times; {{ item.product_name }}
                        <span v-if="item.sellprice != null" class="ml-1 text-xs font-medium text-muted-foreground tabular-nums">
                          {{ formatCurrency(item.sellprice, locale) }}
                        </span>
                      </template>
                    </span>
                    <!-- Quantity adjuster (shown when item is checked and has warehouse context) -->
                    <div
                      v-if="isPacked(machine.id, item) && item.product_id && selectedWarehouseId"
                      class="flex items-center gap-1 shrink-0"
                      @click.stop
                    >
                      <button
                        class="inline-flex h-8 w-8 items-center justify-center rounded-lg border text-sm font-semibold active:bg-muted disabled:opacity-30 transition-colors"
                        :disabled="getDisplayedPackingQty(machine.id, item) <= 1"
                        @click="setCustomQuantity(machine.id, item.product_id!, getDisplayedPackingQty(machine.id, item) - 1)"
                      >&minus;</button>
                      <span class="inline-flex h-8 min-w-10 items-center justify-center rounded-lg bg-primary/10 px-1.5 text-sm font-bold tabular-nums text-primary">
                        {{ getDisplayedPackingQty(machine.id, item) }}
                      </span>
                      <button
                        class="inline-flex h-8 w-8 items-center justify-center rounded-lg border text-sm font-semibold active:bg-muted disabled:opacity-30 transition-colors"
                        :disabled="getDisplayedPackingQty(machine.id, item) >= getMaxCustomQuantity(machine.id, item)"
                        @click="setCustomQuantity(machine.id, item.product_id!, getDisplayedPackingQty(machine.id, item) + 1)"
                      >+</button>
                    </div>
                  </li>
                </ul>
              </div>
            </CardContent>
          </Card>
        </div>

        <!-- Start Tour button — fixed bottom bar on mobile -->
        <div class="fixed bottom-[calc(3.5rem+env(safe-area-inset-bottom))] md:bottom-0 inset-x-0 z-20 border-t bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80 p-3 sm:p-4 md:pb-[max(1rem,env(safe-area-inset-bottom))]">
          <button
            :disabled="!hasAnyPackedItems() || tourStarting"
            class="inline-flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary px-6 text-base font-medium text-primary-foreground shadow-lg transition-colors hover:bg-primary/90 disabled:opacity-50"
            @click="startTour"
          >
            <IconTruck v-if="!tourStarting" class="h-5 w-5" />
            <span v-if="tourStarting">{{ t('refill.startingTour') }}</span>
            <span v-else>{{ t('refill.startTour') }}</span>
          </button>
          <p v-if="!hasAnyPackedItems()" class="mt-1.5 text-center text-xs text-muted-foreground">
            {{ t('refill.noItemsPacked') }}
          </p>
          <p v-else class="mt-1.5 text-center text-xs text-muted-foreground">
            {{ t('refill.warehouseDeductionHint') }}
          </p>
        </div>
      </template>
    </template>

    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <!-- STEP 2: REFILL PER MACHINE -->
    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <template v-else-if="currentStep === 'refill' && currentMachine">
      <!-- Machine tab bar — horizontal scroll on mobile -->
      <div class="-mx-3 sm:-mx-4 md:-mx-6 px-3 sm:px-4 md:px-6 overflow-x-auto scrollbar-none">
        <div class="flex gap-1.5 min-w-max pb-1">
          <button
            v-for="(machine, idx) in machines"
            :key="machine.id"
            class="relative inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors shrink-0"
            :class="idx === currentMachineIndex
              ? 'bg-primary text-primary-foreground shadow-sm'
              : isMachineCompleted(machine.id)
                ? 'bg-green-100 text-green-700 dark:bg-green-950/40 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-950/60'
                : 'bg-muted/60 text-muted-foreground hover:bg-muted'"
            @click="goToMachine(idx)"
          >
            <IconCheck v-if="isMachineCompleted(machine.id)" class="h-3.5 w-3.5 shrink-0" />
            <span
              v-else
              class="inline-flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-bold shrink-0"
              :class="idx === currentMachineIndex ? 'bg-primary-foreground/20' : 'bg-background/50'"
            >{{ idx + 1 }}</span>
            <span class="truncate max-w-[8rem]">{{ machine.name }}</span>
          </button>
        </div>
      </div>

      <!-- Progress summary -->
      <div class="flex items-center justify-between text-sm text-muted-foreground px-1">
        <span>{{ completedMachineIds.size }} / {{ machines.length }} {{ t('refill.completed') }}</span>
        <div class="flex items-center gap-2">
          <div class="h-1.5 w-16 sm:w-24 overflow-hidden rounded-full bg-muted">
            <div
              class="h-full rounded-full bg-green-500 transition-all"
              :style="{ width: `${Math.round((completedMachineIds.size / machines.length) * 100)}%` }"
            />
          </div>
        </div>
      </div>

      <div v-if="currentTraysLoading" class="text-muted-foreground px-1">{{ t('common.loading') }}</div>

      <div v-else-if="currentTrays.length === 0" class="text-muted-foreground text-sm px-1">
        {{ t('refill.noMachinesNeedRefill') }}
      </div>

      <!-- Tray cards — mobile-friendly card layout instead of table -->
      <div v-else class="flex flex-col gap-2 pb-28 sm:pb-32">
        <div
          v-for="tray in currentTrays"
          :key="tray.id"
          class="rounded-xl border bg-card p-3 sm:p-4"
        >
          <div class="flex items-center justify-between gap-3">
            <!-- Left: image + product -->
            <div class="flex items-center gap-3 min-w-0 flex-1">
              <img
                v-if="tray.image_path"
                :src="getProductImageUrl(tray.image_path)"
                :alt="tray.product_name ?? ''"
                class="h-10 w-10 shrink-0 rounded-lg object-cover"
              />
              <span
                v-else
                class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted font-mono text-xs font-medium"
              >
                {{ tray.item_number }}
              </span>
              <div class="min-w-0 flex-1">
                <p class="text-sm font-medium truncate">
                  <span class="text-muted-foreground font-mono text-xs mr-1">#{{ tray.item_number }}</span>
                  {{ tray.product_name ?? `Slot ${tray.item_number}` }}
                </p>
                <p class="text-xs text-muted-foreground tabular-nums flex items-center gap-1.5 flex-wrap">
                  <span
                    class="inline-flex items-center rounded px-1 py-0.5 text-[11px] font-medium"
                    :class="tray.current_stock === 0
                      ? 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
                      : 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-400'"
                  >{{ tray.current_stock }}</span>
                  <span>/</span>
                  <span>{{ tray.capacity }}</span>
                  <span v-if="tray.sellprice != null" class="ml-1 font-medium text-foreground/80">
                    {{ formatCurrency(tray.sellprice, locale) }}
                  </span>
                </p>
              </div>
            </div>

            <!-- Right: fill amount controls — large touch targets -->
            <div class="flex items-center gap-1.5 shrink-0">
              <button
                class="inline-flex h-11 w-11 items-center justify-center rounded-xl border-2 text-lg font-semibold active:bg-muted disabled:opacity-30 transition-colors"
                :disabled="tray.fill_amount <= 0"
                @click="adjustFillAmount(tray.id, -1)"
              >&minus;</button>
              <span class="inline-flex h-11 min-w-14 items-center justify-center rounded-xl bg-primary/10 px-2 text-base font-bold tabular-nums text-primary">
                +{{ tray.fill_amount }}
              </span>
              <button
                class="inline-flex h-11 w-11 items-center justify-center rounded-xl border-2 text-lg font-semibold active:bg-muted disabled:opacity-30 transition-colors"
                :disabled="tray.fill_amount >= (tray.capacity - tray.current_stock)"
                @click="adjustFillAmount(tray.id, 1)"
              >+</button>
            </div>
          </div>
        </div>
      </div>

      <!-- Action buttons — fixed bottom bar -->
      <div class="fixed bottom-[calc(3.5rem+env(safe-area-inset-bottom))] md:bottom-0 inset-x-0 z-20 border-t bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80 p-3 sm:p-4 md:pb-[max(1rem,env(safe-area-inset-bottom))]">
        <div class="flex flex-col gap-2 max-w-3xl mx-auto">
          <!-- Already completed: show done state + finish tour option -->
          <template v-if="currentMachineDone">
            <div class="flex items-center justify-center gap-2 h-12 rounded-xl bg-green-100 dark:bg-green-950/40 text-green-700 dark:text-green-400 text-base font-medium">
              <IconCheck class="h-5 w-5" />
              {{ t('refill.alreadyCompleted') }}
            </div>
            <button
              v-if="allMachinesCompleted"
              class="inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-primary px-4 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90"
              @click="currentStep = 'summary'"
            >
              {{ t('refill.finishTour') }}
            </button>
          </template>

          <!-- Normal: confirm + skip -->
          <template v-else>
            <div
              v-if="confirmError"
              role="alert"
              class="rounded-xl border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            >
              <p class="font-medium">{{ t('refill.confirmFailedTitle') }}</p>
              <p class="mt-1 text-xs opacity-90">{{ confirmError }}</p>
            </div>
            <button
              :disabled="confirmingRefill"
              class="inline-flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary px-6 text-base font-medium text-primary-foreground shadow-lg transition-colors hover:bg-primary/90 disabled:opacity-50"
              @click="confirmMachineRefill"
            >
              <IconCheck v-if="!confirmingRefill" class="h-5 w-5" />
              <span v-if="confirmingRefill">{{ t('refill.confirming') }}</span>
              <span v-else-if="confirmError">{{ t('refill.retry') }}</span>
              <span v-else-if="wouldFinishTour">{{ t('refill.confirmAndFinish') }}</span>
              <span v-else>{{ t('refill.confirmAndNext') }}</span>
            </button>
            <button
              :disabled="confirmingRefill"
              class="inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl border px-4 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted disabled:opacity-50"
              @click="skipMachine"
            >
              <IconPlayerSkipForward class="h-4 w-4" />
              {{ t('refill.skipMachine') }}
            </button>
          </template>
        </div>
      </div>
    </template>

    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <!-- STEP 3: SUMMARY -->
    <!-- ══════════════════════════════════════════════════════════════════════ -->
    <template v-else-if="currentStep === 'summary'">
      <!-- Totals -->
      <div class="grid grid-cols-2 gap-2 sm:gap-4 sm:grid-cols-4">
        <div class="rounded-xl border p-3 text-center">
          <p class="text-2xl font-bold tabular-nums">{{ tourSummary.machinesRefilled }}</p>
          <p class="text-[11px] sm:text-xs text-muted-foreground">{{ t('refill.machinesRefilled', tourSummary.machinesRefilled) }}</p>
        </div>
        <div v-if="tourSummary.machinesSkipped > 0" class="rounded-xl border p-3 text-center">
          <p class="text-2xl font-bold tabular-nums text-amber-500">{{ tourSummary.machinesSkipped }}</p>
          <p class="text-[11px] sm:text-xs text-muted-foreground">{{ t('refill.machinesSkipped', { count: tourSummary.machinesSkipped }) }}</p>
        </div>
        <div class="rounded-xl border p-3 text-center">
          <p class="text-2xl font-bold tabular-nums">{{ tourSummary.totalTraysRefilled }}</p>
          <p class="text-[11px] sm:text-xs text-muted-foreground">{{ t('refill.traysRefilled', tourSummary.totalTraysRefilled) }}</p>
        </div>
        <div class="rounded-xl border p-3 text-center">
          <p class="text-2xl font-bold tabular-nums">{{ tourSummary.totalItemsAdded }}</p>
          <p class="text-[11px] sm:text-xs text-muted-foreground">{{ t('refill.itemsAdded', tourSummary.totalItemsAdded) }}</p>
        </div>
      </div>

      <Separator />

      <!-- Per-machine log -->
      <div class="flex flex-col gap-2 pb-20 sm:pb-24">
        <div
          v-for="entry in tourLog"
          :key="entry.machine_id"
          class="flex items-center justify-between rounded-xl border px-4 py-3"
        >
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium truncate">{{ entry.machine_name }}</p>
            <p v-if="!entry.skipped" class="text-xs text-muted-foreground">
              {{ entry.trays_refilled }} {{ entry.trays_refilled === 1 ? 'tray' : 'trays' }} &middot;
              +{{ entry.total_added }} {{ entry.total_added === 1 ? 'item' : 'items' }}
            </p>
          </div>
          <span
            v-if="entry.skipped"
            class="ml-2 shrink-0 inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-950/40 dark:text-amber-400"
          >
            {{ t('refill.skipped') }}
          </span>
          <IconCheck v-else class="ml-2 h-5 w-5 shrink-0 text-green-600" />
        </div>
      </div>

      <!-- Bottom bar — Back to Machines + Tour History link -->
      <div class="fixed bottom-[calc(3.5rem+env(safe-area-inset-bottom))] md:bottom-0 inset-x-0 z-20 border-t bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80 p-3 sm:p-4 md:pb-[max(1rem,env(safe-area-inset-bottom))]">
        <div class="flex flex-col gap-2 max-w-3xl mx-auto">
          <button
            class="inline-flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary px-6 text-base font-medium text-primary-foreground shadow-lg transition-colors hover:bg-primary/90"
            @click="resetWizard(); router.push('/machines')"
          >
            {{ t('refill.backToMachines') }}
          </button>
          <button
            class="inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl border px-4 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
            @click="resetWizard(); router.push('/tour-history')"
          >
            {{ t('tourHistory.viewTourHistory') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
