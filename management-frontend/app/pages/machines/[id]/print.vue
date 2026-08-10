<script setup lang="ts">
import { IconArrowLeft, IconPrinter, IconAlertTriangle, IconLoader2 } from '@tabler/icons-vue'
import { watchDebounced } from '@vueuse/core'
import StickerSheet from '@/components/print/StickerSheet.vue'
import { useMachinePrint } from '@/composables/useMachinePrint'
import { PRINT_MOTIFS, isStickerFormat, motifById } from '@/lib/printMotifs'
import type { MotifId, PosterT } from '@/lib/printMotifs'
import { FORMAT_MM, distributeStickers } from '@/lib/printSheet'
import type { PrintBlock, PrintFormat, PrintSheet } from '@/lib/printSheet'

definePageMeta({ middleware: 'auth', layout: false })

const { t, locale, locales } = useI18n()
const route = useRoute()
const machineId = route.params.id as string

const print = useMachinePrint()

const motifId = ref<MotifId>('klar')
const format = ref<PrintFormat>('a4')
const blocks = ref<PrintBlock[]>(['phone', 'imprint'])
const customText = ref('')
const sheetLocale = ref<string>(locale.value)
const selectedIds = ref<string[]>([machineId])

const motif = computed(() => motifById(motifId.value) ?? PRINT_MOTIFS[0]!)
const availableBlocks = computed(() => motif.value.blocks)
const availableFormats = computed(() => motif.value.formats)
const isSticker = computed(() => isStickerFormat(format.value))

/**
 * Poster strings resolve against the *sheet* language, not the UI language:
 * an operator running the app in English still prints a German sign.
 */
const posterT: PosterT = (key, named) =>
  (t as unknown as (k: string, n: Record<string, unknown>, o: { locale: string }) => string)(
    key,
    named ?? {},
    { locale: sheetLocale.value },
  )

const localeOptions = computed(() =>
  (locales.value as { code: string; name?: string }[]).map(l => ({
    code: l.code,
    name: l.name ?? l.code,
  })),
)

// Switching motif must never leave an unsupported format or a block the new
// motif cannot render — the preview would silently drop them otherwise.
// Blocks the previous motif never offered start switched on: the operator has
// not made a choice about them, and off would quietly drop, say, the fault QR
// from the very sticker whose purpose it is.
watch(motif, (m, previous) => {
  if (!m.formats.includes(format.value)) format.value = m.formats[0]!
  blocks.value = m.blocks.filter(
    b => blocks.value.includes(b) || !previous.blocks.includes(b),
  )
})

function toggleBlock(block: PrintBlock) {
  blocks.value = blocks.value.includes(block)
    ? blocks.value.filter(b => b !== block)
    : [...blocks.value, block]
}

function toggleMachine(id: string) {
  if (id === machineId) return
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(m => m !== id)
    : [...selectedIds.value, id]
}

const sheets = ref<PrintSheet[]>([])
const building = ref(false)

async function rebuild() {
  if (!print.company.value) return
  building.value = true
  try {
    sheets.value = await print.buildSheets({
      machineIds: selectedIds.value,
      blocks: blocks.value,
      format: format.value,
      whatsappTemplate: posterT('print.whatsappTemplate'),
      customText: customText.value,
      fallbackMachineName: posterT('print.fallbackMachineName'),
    })
  } finally {
    building.value = false
  }
}

watch(
  [selectedIds, blocks, format, sheetLocale, () => print.company.value],
  rebuild,
  { deep: true },
)
watchDebounced(customText, rebuild, { debounce: 300 })

onMounted(async () => {
  await print.load()
  // Keep the machine we navigated from at the top of the batch.
  if (!selectedIds.value.includes(machineId)) selectedIds.value.unshift(machineId)
  await rebuild()
})

/** One rendered page: either a single poster or a full sticker sheet. */
const pages = computed<PrintSheet[][]>(() =>
  isSticker.value
    ? distributeStickers(sheets.value)
    : sheets.value.map(s => [s]),
)

const sheetMm = computed(() => FORMAT_MM[format.value])

const sheetStyle = computed(() => ({
  width: `${sheetMm.value.w}mm`,
  height: `${sheetMm.value.h}mm`,
  // One layout scales across A4/A5/A6: motifs size everything in em.
  fontSize: `${(4 * sheetMm.value.w) / 210}mm`,
  padding: isSticker.value ? '0' : 'max(5mm, 2.5em)',
}))

const PX_PER_MM = 96 / 25.4
const PREVIEW_WIDTH_PX = 460

const previewScale = computed(() => PREVIEW_WIDTH_PX / (sheetMm.value.w * PX_PER_MM))

const previewStyle = computed(() => ({
  width: `${PREVIEW_WIDTH_PX}px`,
  height: `${sheetMm.value.h * PX_PER_MM * previewScale.value}px`,
  '--preview-scale': String(previewScale.value),
}))

const pageSizeCss = computed(() => {
  if (format.value === 'a5') return 'A5 portrait'
  if (format.value === 'a6') return 'A6 portrait'
  return 'A4 portrait'
})

useHead(() => ({
  title: t('print.pageTitle'),
  style: [{ innerHTML: `@page { size: ${pageSizeCss.value}; margin: 0; }` }],
}))

/** Distinct missing-field warnings across all selected machines. */
const missingFields = computed(() => {
  const set = new Set<string>()
  for (const sheet of sheets.value) for (const field of sheet.missing) set.add(field)
  return [...set]
})

async function doPrint() {
  await print.logPrinted(selectedIds.value, {
    motif: motifId.value,
    format: format.value,
    blocks: blocks.value,
    sheetLanguage: sheetLocale.value,
  })
  await nextTick()
  window.print()
}
</script>

<template>
  <div class="print-page flex min-h-screen items-start gap-8 bg-background p-6 text-foreground">
    <aside class="no-print sticky top-6 flex w-[300px] flex-none flex-col gap-5">
      <NuxtLink
        :to="`/machines/${machineId}`"
        class="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
      >
        <IconArrowLeft :size="16" />
        {{ t('print.backToMachine') }}
      </NuxtLink>

      <div>
        <h1 class="text-lg font-semibold">{{ t('print.pageTitle') }}</h1>
        <p class="mt-1 text-sm text-muted-foreground">{{ t('print.pageLead') }}</p>
      </div>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.motif') }}
        </h2>
        <div class="flex flex-col gap-1.5">
          <button
            v-for="m in PRINT_MOTIFS"
            :key="m.id"
            type="button"
            class="flex flex-col gap-0.5 rounded-lg border bg-card px-3 py-2 text-left transition-colors hover:bg-muted"
            :class="m.id === motifId ? 'border-primary ring-1 ring-primary' : 'border-input'"
            @click="motifId = m.id"
          >
            <span class="text-sm font-medium">{{ t(m.labelKey) }}</span>
            <span class="text-xs leading-snug text-muted-foreground">{{ t(m.descriptionKey) }}</span>
          </button>
        </div>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.format') }}
        </h2>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="f in availableFormats"
            :key="f"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="f === format ? 'border-primary bg-primary/10 text-primary' : 'border-input bg-card hover:bg-muted'"
            @click="format = f"
          >
            {{ t(`print.formats.${f}`) }}
          </button>
        </div>
      </section>

      <section v-if="availableBlocks.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.blocks') }}
        </h2>
        <label v-for="b in availableBlocks" :key="b" class="flex items-center gap-2 py-0.5 text-sm">
          <input type="checkbox" :checked="blocks.includes(b)" @change="toggleBlock(b)">
          <span>{{ t(`print.blockNames.${b}`) }}</span>
        </label>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.sheetLanguage') }}
        </h2>
        <select
          v-model="sheetLocale"
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          <option v-for="l in localeOptions" :key="l.code" :value="l.code">{{ l.name }}</option>
        </select>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.customText') }}
        </h2>
        <input
          v-model="customText"
          :placeholder="t('print.customTextPlaceholder')"
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
      </section>

      <section v-if="print.machines.value.length > 1">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.moreMachines') }}
        </h2>
        <p class="mb-1 text-xs text-muted-foreground">{{ t('print.moreMachinesHint') }}</p>
        <label
          v-for="m in print.machines.value"
          :key="m.id"
          class="flex items-center gap-2 py-0.5 text-sm"
          :class="{ 'text-muted-foreground': m.id === machineId }"
        >
          <input
            type="checkbox"
            :checked="selectedIds.includes(m.id)"
            :disabled="m.id === machineId"
            @change="toggleMachine(m.id)"
          >
          <span>{{ m.name || t('print.fallbackMachineName') }}</span>
        </label>
      </section>

      <button
        type="button"
        class="inline-flex h-10 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
        :disabled="!sheets.length"
        @click="doPrint"
      >
        <IconPrinter :size="18" />
        {{ t('print.print', pages.length) }}
      </button>
      <p class="text-xs leading-snug text-muted-foreground">{{ t('print.browserHint') }}</p>
    </aside>

    <main class="flex flex-1 flex-col items-center gap-5">
      <div
        v-if="!print.originIsPublic.value"
        class="no-print flex w-full max-w-[460px] gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2.5 text-sm leading-normal text-destructive"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.originWarnTitle') }}</strong>
          <p>{{ t('print.originWarnBody', { origin: print.publicOrigin.value || '—' }) }}</p>
        </div>
      </div>

      <div
        v-if="missingFields.length"
        class="no-print flex w-full max-w-[460px] gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm leading-normal text-amber-700 dark:text-amber-400"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.missingTitle') }}</strong>
          <p>{{ missingFields.map(f => t(`print.missingFields.${f}`)).join(' · ') }}</p>
          <NuxtLink to="/settings" class="mt-1 inline-block underline">{{ t('print.missingLink') }}</NuxtLink>
        </div>
      </div>

      <div v-if="building && !sheets.length" class="no-print p-12 text-muted-foreground">
        <IconLoader2 :size="20" class="spin" />
      </div>

      <div v-for="(page, i) in pages" :key="i" class="preview" :style="previewStyle">
        <div class="sheet" :style="sheetStyle">
          <StickerSheet
            v-if="isSticker"
            :sheets="page"
            :motif="motif.component"
            :t="posterT"
          />
          <component
            :is="motif.component"
            v-else-if="page[0]"
            :sheet="page[0]"
            :t="posterT"
          />
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.preview { position: relative; overflow: hidden; }

/* The sheet is paper: it stays white/dark by its motif, never by the app theme. */
.sheet {
  box-sizing: border-box;
  background: #fff;
  color: #14110f;
  overflow: hidden;
  print-color-adjust: exact;
  -webkit-print-color-adjust: exact;
}

@media screen {
  .sheet {
    transform: scale(var(--preview-scale));
    transform-origin: top left;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.18);
  }
}

@media print {
  .no-print { display: none !important; }
  .print-page { display: block; padding: 0; background: #fff; min-height: 0; }
  .preview {
    width: auto !important;
    height: auto !important;
    overflow: visible;
  }
  .sheet {
    transform: none;
    box-shadow: none;
    page-break-after: always;
    break-after: page;
  }
  /* Without this the last sheet pushes out a trailing blank page. */
  .preview:last-child .sheet {
    page-break-after: auto;
    break-after: auto;
  }
}
</style>
