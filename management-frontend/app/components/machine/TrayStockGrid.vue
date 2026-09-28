<script setup lang="ts">
import { computeSlotWidths, slotRowCol } from '@/composables/useMachineAnalysis'
import type { TrayGroupInfo } from '@/lib/trayGroups'

/**
 * Compact stock map of the machine, laid out like the Analysis grid (10
 * columns, width = gap to the next slot). A cell's colour is its **product's**
 * status, so all slots of a product light up together; an empty slot of an
 * otherwise stocked product only gets a dashed outline. Tapping a cell
 * selects its product and highlights every slot that holds it.
 */
const props = defineProps<{
  trays: { id: string; item_number: number; product_id: string | null; product_name: string | null; current_stock: number; capacity: number }[]
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
    <div class="grid gap-1" :style="{ gridTemplateColumns: 'repeat(10, minmax(0, 1fr))', gridAutoRows: '2.75rem' }">
      <button
        v-for="cell in cells"
        :key="cell.tray.id"
        type="button"
        :style="{ gridColumn: `${cell.column + 1} / span ${cell.width}`, gridRow: `${cell.row + 1}` }"
        class="flex min-w-0 flex-col items-center justify-center rounded-md border-2 px-0.5 leading-tight transition-opacity focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        :class="[
          cellClass(cell.info, cell.tray.product_id),
          selectedProductId && cell.tray.product_id === selectedProductId ? 'ring-2 ring-primary ring-offset-1 ring-offset-background' : '',
          selectedProductId && cell.tray.product_id !== selectedProductId ? 'opacity-35' : '',
        ]"
        :title="cell.tray.product_name ?? ''"
        @click="toggle(cell.tray.product_id)"
      >
        <span class="text-[10px] font-semibold tabular-nums">{{ cell.tray.item_number }}</span>
        <span v-if="cell.tray.product_id" class="text-[9px] tabular-nums text-muted-foreground">{{ cell.tray.current_stock }}/{{ cell.tray.capacity }}</span>
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
