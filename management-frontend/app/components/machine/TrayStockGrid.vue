<script setup lang="ts">
import { computeSlotWidths, slotRowCol } from '@/composables/useMachineAnalysis'
import type { TrayGroupInfo } from '@/lib/trayGroups'

/**
 * Stock map of the machine, laid out and styled like the Analysis grid
 * (`analysis/MachineLayoutGrid.vue`: 10 columns, width = gap to the next
 * slot, product image, slot number bottom-left, stock top-right). A cell's colour is its **product's**
 * status, so all slots of a product light up together; an empty slot of an
 * otherwise stocked product only gets a dashed outline. Tapping a cell
 * selects its product and highlights every slot that holds it.
 */
const props = defineProps<{
  trays: { id: string; item_number: number; product_id: string | null; product_name: string | null; image_url?: string | null; current_stock: number; capacity: number }[]
  index: Map<string, TrayGroupInfo<any>>
  selectedProductId: string | null
}>()

const emit = defineEmits<{ (e: 'select', productId: string | null): void }>()

const { t } = useI18n()

const cells = computed(() => {
  const widths = computeSlotWidths(props.trays.map(tr => tr.item_number))
  return props.trays.map((tray) => {
    const { row, column } = slotRowCol(tray.item_number)
    return { tray, row, column, width: widths.get(tray.item_number) ?? 1, info: props.index.get(tray.id) }
  })
})

function cellClass(info: TrayGroupInfo | undefined, productId: string | null) {
  if (!productId || !info) return 'border-dashed border-muted-foreground/30 bg-transparent text-muted-foreground'
  if (info.needsRefill) {
    if (info.group.state === 'critical') return 'border-red-500/80 bg-red-500/15'
    if (info.group.state === 'low') return 'border-amber-500/80 bg-amber-500/15'
    return 'border-blue-500/70 bg-blue-500/10'
  }
  if (info.flag === 'slotEmpty') return 'border-dashed border-muted-foreground/60 bg-transparent'
  return 'border-green-500/40 bg-green-500/10'
}

function toggle(productId: string | null) {
  emit('select', productId && productId !== props.selectedProductId ? productId : null)
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="grid gap-1.5" :style="{ gridTemplateColumns: 'repeat(10, minmax(0, 1fr))', gridAutoRows: '6.5rem' }">
      <button
        v-for="cell in cells"
        :key="cell.tray.id"
        type="button"
        :style="{ gridColumn: `${cell.column + 1} / span ${cell.width}`, gridRow: `${cell.row + 1}` }"
        class="relative flex min-w-0 flex-col items-center justify-center overflow-hidden rounded-md border-2 p-1 transition-opacity hover:brightness-105 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        :class="[
          cellClass(cell.info, cell.tray.product_id),
          selectedProductId && cell.tray.product_id === selectedProductId ? 'ring-2 ring-primary ring-offset-1 ring-offset-background' : '',
          selectedProductId && cell.tray.product_id !== selectedProductId ? 'opacity-35' : '',
        ]"
        :title="cell.tray.product_name ?? ''"
        @click="toggle(cell.tray.product_id)"
      >
        <!-- Product image / placeholder -->
        <img
          v-if="cell.tray.image_url"
          :src="cell.tray.image_url"
          :alt="cell.tray.product_name ?? ''"
          loading="lazy"
          class="aspect-square w-full max-w-16 rounded object-cover"
          :class="cell.tray.product_id && cell.tray.current_stock === 0 ? 'grayscale opacity-60' : ''"
        />
        <div
          v-else
          class="flex aspect-square w-full max-w-16 items-center justify-center rounded bg-muted/50 text-sm text-muted-foreground"
        >
          {{ cell.tray.product_id ? (cell.tray.product_name ?? '?').charAt(0) : '—' }}
        </div>

        <!-- Slot number (bottom-left) -->
        <span class="absolute bottom-0.5 left-0.5 rounded bg-black/60 px-1 text-[9px] font-semibold tabular-nums text-white">
          {{ cell.tray.item_number }}
        </span>

        <!-- Stock (top-right) -->
        <span
          v-if="cell.tray.product_id"
          class="absolute right-0.5 top-0.5 rounded px-1 text-[9px] font-semibold tabular-nums"
          :class="cell.info?.needsRefill && cell.info.group.state !== 'fill' ? 'bg-red-600 text-white' : 'bg-black/50 text-white'"
        >
          {{ cell.tray.current_stock }}<span class="hidden sm:inline">/{{ cell.tray.capacity }}</span>
        </span>
      </button>
    </div>
    <div class="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
      <span class="inline-flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border-2 border-red-500/80 bg-red-500/15" />{{ t('machineDetail.groupStateCritical') }}</span>
      <span class="inline-flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border-2 border-amber-500/80 bg-amber-500/15" />{{ t('machineDetail.groupStateLow') }}</span>
      <span class="inline-flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border-2 border-blue-500/70 bg-blue-500/10" />{{ t('machineDetail.groupStateFill') }}</span>
      <span class="inline-flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border-2 border-dashed border-muted-foreground/60" />{{ t('machineDetail.slotEmptyElsewhere') }}</span>
      <span class="inline-flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border-2 border-green-500/40 bg-green-500/10" />{{ t('machineDetail.groupStateOk') }}</span>
    </div>
  </div>
</template>
