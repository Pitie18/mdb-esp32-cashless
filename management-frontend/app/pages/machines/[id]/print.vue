<script setup lang="ts">
import { IconArrowLeft, IconPrinter, IconAlertTriangle, IconLoader2, IconRotate, IconDeviceFloppy, IconPlus, IconMinus, IconArrowAutofitWidth } from '@tabler/icons-vue'
import { watchDebounced } from '@vueuse/core'
import StickerSheet from '@/components/print/StickerSheet.vue'
import MotifThumb from '@/components/print/MotifThumb.vue'
import { useMachinePrint } from '@/composables/useMachinePrint'
import { PRINT_MOTIFS, defaultLayout, isStickerFormat, motifById } from '@/lib/printMotifs'
import type { MotifId } from '@/lib/printMotifs'
import { FORMAT_MM, SLOT_SOURCES, distributeStickers, stickersPerSheet } from '@/lib/printSheet'
import type { PosterLayout, PosterT, PrintBlock, PrintFormat, PrintSheet, SlotSource } from '@/lib/printSheet'

definePageMeta({ middleware: 'auth', layout: false })

const { t, locale, locales, loadLocaleMessages } = useI18n()
const route = useRoute()
const machineId = route.params.id as string

const print = useMachinePrint()

const motifId = ref<MotifId>('klar')
const format = ref<PrintFormat>('a4')
const customText = ref('')
const sheetLocale = ref<string>(locale.value)
const selectedIds = ref<string[]>([machineId])

const motif = computed(() => motifById(motifId.value) ?? PRINT_MOTIFS[0]!)
const isSticker = computed(() => isStickerFormat(format.value))

/** The working copy the preview renders; saved rows only seed it. */
const layout = ref<PosterLayout>(defaultLayout(PRINT_MOTIFS[0]!))

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

/**
 * Locale files are lazy-loaded, so only the *active* language is in memory.
 * Asking vue-i18n for a string in any other one returns the key path — which
 * is what a poster printed in a non-UI language showed. Load it first.
 */
const loadedLocales = new Set<string>([locale.value])
async function ensureSheetLocale() {
  const code = sheetLocale.value
  if (loadedLocales.has(code)) return
  try {
    await loadLocaleMessages(code)
    loadedLocales.add(code)
  } catch {
    // Fall through: vue-i18n's fallbackLocale still yields readable text.
  }
}

const localeOptions = computed(() =>
  (locales.value as { code: string; name?: string }[]).map(l => ({
    code: l.code,
    name: l.name ?? l.code,
  })),
)

/** Saved layout for this motif, or the motif's defaults. */
function seedLayout() {
  const stored = print.storedLayout(motif.value.id, machineId)
  const base = defaultLayout(motif.value)
  layout.value = stored
    ? {
        ...base,
        ...stored,
        // Slots the motif gained since the layout was saved must not vanish.
        slots: { ...base.slots, ...(stored.slots ?? {}) },
        custom: { ...base.custom, ...(stored.custom ?? {}) },
        texts: { ...(stored.texts ?? {}) },
        blocks: stored.blocks ?? base.blocks,
      }
    : base
}

watch(motif, (m) => {
  if (!m.formats.includes(format.value)) format.value = m.formats[0]!
  seedLayout()
})

function setSlotSource(slotId: string, source: SlotSource) {
  layout.value = {
    ...layout.value,
    slots: { ...layout.value.slots, [slotId]: { source } },
  }
}

function toggleBlock(block: PrintBlock) {
  const current = layout.value.blocks ?? []
  layout.value = {
    ...layout.value,
    blocks: current.includes(block) ? current.filter(b => b !== block) : [...current, block],
  }
}

function setText(key: string, value: string) {
  const texts = { ...(layout.value.texts ?? {}) }
  if (value.trim()) texts[key] = value
  else delete texts[key]
  layout.value = { ...layout.value, texts }
}

function resetText(key: string) {
  setText(key, '')
}

function toggleMachine(id: string) {
  if (id === machineId) return
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(m => m !== id)
    : [...selectedIds.value, id]
}

/** Sources whose data is not configured, so the picker can say so up front. */
const unavailableSources = computed(() => {
  const co = print.company.value
  const machine = print.machines.value.find(m => m.id === machineId)
  const out = new Set<SlotSource>()
  if (!(machine?.contact_phone?.trim() || co?.contact_phone?.trim())) out.add('tel')
  if (!(machine?.whatsapp_phone?.trim() || co?.whatsapp_phone?.trim())) out.add('whatsapp')
  return out
})

/**
 * One sheet per motif for the gallery thumbnails, built from each motif's
 * saved-or-default layout. The selected motif is served from the working copy
 * instead, so its tile and the big preview never disagree.
 */
const previews = ref<Record<string, PrintSheet>>({})

async function buildPreviews() {
  if (!print.company.value) return
  const out: Record<string, PrintSheet> = {}
  for (const m of PRINT_MOTIFS) {
    const [sheet] = await print.buildSheets({
      machineIds: [machineId],
      slotDeclarations: m.slots,
      layout: print.storedLayout(m.id, machineId) ?? defaultLayout(m),
      format: m.formats[0]!,
      t: posterT,
      whatsappTemplate: posterT('print.whatsappTemplate'),
      fallbackMachineName: posterT('print.fallbackMachineName'),
    })
    if (sheet) out[m.id] = sheet
  }
  previews.value = out
}

function thumbSheet(id: string): PrintSheet | null {
  if (id === motifId.value && sheets.value[0]) return sheets.value[0]!
  return previews.value[id] ?? null
}

const sheets = ref<PrintSheet[]>([])
const building = ref(false)

async function rebuild() {
  if (!print.company.value) return
  building.value = true
  try {
    await ensureSheetLocale()
    sheets.value = await print.buildSheets({
      machineIds: selectedIds.value,
      slotDeclarations: motif.value.slots,
      layout: layout.value,
      format: format.value,
      t: posterT,
      whatsappTemplate: posterT('print.whatsappTemplate'),
      customText: customText.value,
      fallbackMachineName: posterT('print.fallbackMachineName'),
    })
  } finally {
    building.value = false
  }
}

watch([selectedIds, layout, format, sheetLocale, () => print.company.value], rebuild, { deep: true })
watchDebounced(customText, rebuild, { debounce: 300 })

onMounted(async () => {
  await Promise.all([print.load(), ensureSheetLocale()])
  seedLayout()
  if (!selectedIds.value.includes(machineId)) selectedIds.value.unshift(machineId)
  await rebuild()
  await buildPreviews()
})

// Gallery labels are in the sheet language too, so it has to rebuild with it.
watch(sheetLocale, async () => {
  await ensureSheetLocale()
  await buildPreviews()
})

// ── Saving ──────────────────────────────────────────────────────────────────
const saveScope = ref<'company' | 'machine'>('company')
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedAt = ref(false)

const scopeInUse = computed(() => print.layoutScope(motif.value.id, machineId))

async function save() {
  saving.value = true
  saveError.value = null
  try {
    await print.saveLayout(motif.value.id, layout.value, saveScope.value, machineId)
    savedAt.value = true
    setTimeout(() => { savedAt.value = false }, 2500)
  } catch (e) {
    saveError.value = (e as Error)?.message ?? 'save-failed'
  } finally {
    saving.value = false
  }
}

async function resetToDefaults() {
  layout.value = defaultLayout(motif.value)
  await rebuild()
}

// ── Rendering ───────────────────────────────────────────────────────────────
const pages = computed<PrintSheet[][]>(() =>
  isSticker.value
    ? distributeStickers(sheets.value, stickersPerSheet(format.value))
    : sheets.value.map(s => [s]),
)

const sheetMm = computed(() => FORMAT_MM[format.value])

const sheetStyle = computed(() => ({
  width: `${sheetMm.value.w}mm`,
  height: `${sheetMm.value.h}mm`,
  // One layout scales across A4/A5/A6: motifs size everything in em.
  fontSize: `${(4 * sheetMm.value.w) / 210}mm`,
}))

const THUMB_WIDTH = 150
const PX_PER_MM = 96 / 25.4

// ── Zoom ────────────────────────────────────────────────────────────────────
// Percent of physical size, the way a PDF viewer counts: 100 % means an A4
// sheet is 210 mm wide on screen. The ladder is the familiar one so the steps
// land on values people recognise.
const ZOOM_STEPS = [0.25, 0.33, 0.5, 0.67, 0.75, 1, 1.25, 1.5, 2]
const MIN_ZOOM = ZOOM_STEPS[0]!
const MAX_ZOOM = ZOOM_STEPS[ZOOM_STEPS.length - 1]!

const stage = ref<HTMLElement | null>(null)
const stageWidth = ref(0)
const zoom = ref(0.5)

// Two sources on purpose. The observer catches layout changes that leave the
// window alone — the rail growing when a motif adds fields, for one. The
// window listener is the belt: ResizeObserver notifications are not delivered
// in every embedded browser, and a zoom control that silently stops tracking
// the viewport is worse than one extra listener.
let stageObserver: ResizeObserver | null = null

function measureStage() {
  stageWidth.value = stage.value?.clientWidth ?? 0
}

onMounted(() => {
  measureStage()
  if (stage.value && typeof ResizeObserver !== 'undefined') {
    stageObserver = new ResizeObserver(measureStage)
    stageObserver.observe(stage.value)
  }
  window.addEventListener('resize', measureStage)
})

onUnmounted(() => {
  stageObserver?.disconnect()
  window.removeEventListener('resize', measureStage)
})

/** Largest zoom at which the sheet still fits the stage, never past 100 %. */
const fitZoom = computed(() => {
  const available = stageWidth.value - 32
  if (available <= 0) return 0.5
  return Math.min(1, Math.max(MIN_ZOOM, available / (sheetMm.value.w * PX_PER_MM)))
})

function zoomToFit() {
  zoom.value = fitZoom.value
}

function zoomIn() {
  zoom.value = ZOOM_STEPS.find(z => z > zoom.value + 0.001) ?? MAX_ZOOM
}

function zoomOut() {
  zoom.value = [...ZOOM_STEPS].reverse().find(z => z < zoom.value - 0.001) ?? MIN_ZOOM
}

// A different paper size needs a different fit, and the stage only has a
// width once it is mounted — hence both triggers.
watch([sheetMm, stageWidth], zoomToFit)

const previewStyle = computed(() => ({
  width: `${Math.round(sheetMm.value.w * PX_PER_MM * zoom.value)}px`,
  height: `${Math.round(sheetMm.value.h * PX_PER_MM * zoom.value)}px`,
  '--preview-scale': String(zoom.value),
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

const missingFields = computed(() => {
  const set = new Set<string>()
  for (const sheet of sheets.value) for (const field of sheet.missing) set.add(field)
  return [...set]
})

const usesCustomLink = computed(() =>
  motif.value.slots.some(s => (layout.value.slots?.[s.id]?.source ?? s.defaultSource) === 'custom'),
)

async function doPrint() {
  await print.logPrinted(sheets.value, {
    motif: motifId.value,
    format: format.value,
    layout: layout.value,
    slotDeclarations: motif.value.slots,
    sheetLanguage: sheetLocale.value,
  })
  await nextTick()
  window.print()
}
</script>

<template>
  <div class="print-page flex min-h-screen items-start gap-8 bg-background p-6 text-foreground">
    <aside class="no-print sticky top-6 flex max-h-[calc(100vh-3rem)] w-[380px] flex-none flex-col gap-5 overflow-y-auto pr-1">
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
        <div class="grid grid-cols-2 gap-2">
          <button
            v-for="m in PRINT_MOTIFS"
            :key="m.id"
            type="button"
            class="flex flex-col overflow-hidden rounded-lg border bg-card text-left transition-colors hover:bg-muted"
            :class="m.id === motifId ? 'border-primary ring-1 ring-primary' : 'border-input'"
            :title="t(m.descriptionKey)"
            @click="motifId = m.id"
          >
            <div class="flex items-center justify-center border-b border-input bg-[#f5f5f4] p-1.5">
              <MotifThumb :motif="m" :sheet="thumbSheet(m.id)" :t="posterT" :width="THUMB_WIDTH" />
            </div>
            <div class="px-2 py-1.5">
              <div class="text-xs font-medium">{{ t(m.labelKey) }}</div>
              <div class="mt-0.5 line-clamp-2 text-[11px] leading-snug text-muted-foreground">
                {{ t(m.descriptionKey) }}
              </div>
            </div>
          </button>
        </div>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.format') }}
        </h2>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="f in motif.formats"
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

      <!-- One picker per QR slot the motif declares, with its label and hint. -->
      <section v-if="motif.slots.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.qrCodes') }}
        </h2>
        <div v-for="slot in motif.slots" :key="slot.id" class="mb-3">
          <label class="text-xs text-muted-foreground">{{ t(slot.labelKey) }}</label>
          <select
            class="mt-1 h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            :value="layout.slots?.[slot.id]?.source ?? slot.defaultSource"
            @change="setSlotSource(slot.id, ($event.target as HTMLSelectElement).value as SlotSource)"
          >
            <option
              v-for="source in SLOT_SOURCES"
              :key="source"
              :value="source"
              :disabled="source === 'none' && !slot.optional"
            >
              {{ t(`print.sources.${source}`) }}{{ unavailableSources.has(source) ? ` — ${t('print.sourceUnavailable')}` : '' }}
            </option>
          </select>

          <!-- Label and hint follow the chosen source until overridden here. -->
          <div class="mt-1.5 grid grid-cols-1 gap-1.5">
            <div class="flex items-center gap-1">
              <input
                class="h-8 w-full rounded-md border border-input bg-background px-2 text-xs placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                :placeholder="sheets[0]?.slots?.[slot.id]?.title ?? t('print.texts.slotTitle')"
                :value="layout.texts?.[`slot.${slot.id}.title`] ?? ''"
                @input="setText(`slot.${slot.id}.title`, ($event.target as HTMLInputElement).value)"
              >
              <button
                v-if="layout.texts?.[`slot.${slot.id}.title`]"
                type="button"
                class="text-muted-foreground hover:text-foreground"
                :title="t('print.resetText')"
                @click="resetText(`slot.${slot.id}.title`)"
              >
                <IconRotate :size="14" />
              </button>
            </div>
            <div class="flex items-center gap-1">
              <input
                class="h-8 w-full rounded-md border border-input bg-background px-2 text-xs placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                :placeholder="sheets[0]?.slots?.[slot.id]?.hint ?? t('print.texts.slotHint')"
                :value="layout.texts?.[`slot.${slot.id}.hint`] ?? ''"
                @input="setText(`slot.${slot.id}.hint`, ($event.target as HTMLInputElement).value)"
              >
              <button
                v-if="layout.texts?.[`slot.${slot.id}.hint`]"
                type="button"
                class="text-muted-foreground hover:text-foreground"
                :title="t('print.resetText')"
                @click="resetText(`slot.${slot.id}.hint`)"
              >
                <IconRotate :size="14" />
              </button>
            </div>
          </div>
        </div>
      </section>

      <section v-if="usesCustomLink">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.customLink') }}
        </h2>
        <input
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          :placeholder="t('print.customLinkPlaceholder')"
          :value="layout.custom?.url ?? ''"
          @input="layout = { ...layout, custom: { ...layout.custom, url: ($event.target as HTMLInputElement).value } }"
        >
        <p class="mt-1 text-xs text-muted-foreground">{{ t('print.customLinkHint') }}</p>
      </section>

      <section v-if="motif.texts.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.headlines') }}
        </h2>
        <div v-for="field in motif.texts" :key="field.id" class="mb-2">
          <label class="text-xs text-muted-foreground">{{ t(field.labelKey) }}</label>
          <div class="mt-1 flex items-center gap-1">
            <input
              class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="posterT(field.defaultKey)"
              :value="layout.texts?.[field.id] ?? ''"
              @input="setText(field.id, ($event.target as HTMLInputElement).value)"
            >
            <button
              v-if="layout.texts?.[field.id]"
              type="button"
              class="text-muted-foreground hover:text-foreground"
              :title="t('print.resetText')"
              @click="resetText(field.id)"
            >
              <IconRotate :size="14" />
            </button>
          </div>
        </div>
      </section>

      <section v-if="motif.blocks.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.blocks') }}
        </h2>
        <label v-for="b in motif.blocks" :key="b" class="flex items-center gap-2 py-0.5 text-sm">
          <input
            type="checkbox"
            :checked="(layout.blocks ?? []).includes(b)"
            @change="toggleBlock(b)"
          >
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
        <p class="mt-1 text-xs text-muted-foreground">{{ t('print.customTextHint') }}</p>
      </section>

      <!-- Saving turns this configuration into what colleagues get next time. -->
      <section v-if="print.canSave.value" class="rounded-lg border border-input bg-card p-3">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.saveSection') }}
        </h2>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="scope in (['company', 'machine'] as const)"
            :key="scope"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="scope === saveScope ? 'border-primary bg-primary/10 text-primary' : 'border-input hover:bg-muted'"
            @click="saveScope = scope"
          >
            {{ t(`print.saveScope.${scope}`) }}
          </button>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <button
            type="button"
            class="inline-flex h-9 flex-1 items-center justify-center gap-2 rounded-md border border-input text-sm transition-colors hover:bg-muted"
            :disabled="saving"
            @click="save"
          >
            <IconDeviceFloppy :size="16" />
            {{ savedAt ? t('print.saved') : t('print.save') }}
          </button>
          <button
            type="button"
            class="inline-flex h-9 items-center justify-center gap-1.5 rounded-md border border-input px-3 text-sm transition-colors hover:bg-muted"
            @click="resetToDefaults"
          >
            <IconRotate :size="16" />
            {{ t('print.resetAll') }}
          </button>
        </div>
        <p v-if="saveError" class="mt-1 text-xs text-destructive">{{ saveError }}</p>
        <p class="mt-1 text-xs text-muted-foreground">{{ t(`print.saveState.${scopeInUse}`) }}</p>
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

    <main ref="stage" class="flex min-w-0 flex-1 flex-col gap-4">
      <div
        v-if="!print.originIsPublic.value"
        class="no-print mx-auto flex w-full max-w-[560px] gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2.5 text-sm leading-normal text-destructive"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.originWarnTitle') }}</strong>
          <p>{{ t('print.originWarnBody', { origin: print.publicOrigin.value || '—' }) }}</p>
        </div>
      </div>

      <div
        v-if="missingFields.length"
        class="no-print mx-auto flex w-full max-w-[560px] gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm leading-normal text-amber-700 dark:text-amber-400"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.missingTitle') }}</strong>
          <p>{{ missingFields.map(f => t(`print.missingFields.${f}`)).join(' · ') }}</p>
          <NuxtLink to="/settings" class="mt-1 inline-block underline">{{ t('print.missingLink') }}</NuxtLink>
        </div>
      </div>

      <!-- Sticky so the zoom stays reachable while scrolling a long sheet. -->
      <div class="no-print sticky top-6 z-10 mx-auto flex items-center gap-1 rounded-full border border-input bg-card/95 px-2 py-1.5 shadow-sm backdrop-blur">
        <button
          type="button"
          class="inline-flex size-7 items-center justify-center rounded-full transition-colors hover:bg-muted"
          :title="t('print.zoomOut')"
          :aria-label="t('print.zoomOut')"
          @click="zoomOut"
        >
          <IconMinus :size="15" />
        </button>
        <span class="w-12 text-center text-xs tabular-nums text-muted-foreground">{{ Math.round(zoom * 100) }} %</span>
        <button
          type="button"
          class="inline-flex size-7 items-center justify-center rounded-full transition-colors hover:bg-muted"
          :title="t('print.zoomIn')"
          :aria-label="t('print.zoomIn')"
          @click="zoomIn"
        >
          <IconPlus :size="15" />
        </button>
        <span class="mx-1 h-4 w-px bg-border" />
        <button
          type="button"
          class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs transition-colors hover:bg-muted"
          @click="zoomToFit"
        >
          <IconArrowAutofitWidth :size="15" />
          {{ t('print.zoomFit') }}
        </button>
      </div>

      <div v-if="building && !sheets.length" class="no-print mx-auto p-12 text-muted-foreground">
        <IconLoader2 :size="20" class="spin" />
      </div>

      <!-- Horizontal scroll for zoom levels wider than the stage. `safe center`
           keeps an oversized sheet from having its left edge cut off. -->
      <div class="sheets">
        <div v-for="(page, i) in pages" :key="i" class="preview" :style="previewStyle">
          <div class="sheet" :style="sheetStyle">
            <StickerSheet
              v-if="isSticker"
              :sheets="page"
              :motif="motif.component"
              :t="posterT"
              :format="format"
            />
            <component
              :is="motif.component"
              v-else-if="page[0]"
              :sheet="page[0]"
              :t="posterT"
            />
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.sheets {
  display: flex;
  flex-direction: column;
  align-items: safe center;
  gap: 1.25rem;
  overflow-x: auto;
  padding-bottom: 1rem;
}
.preview { position: relative; overflow: hidden; flex: none; }

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
  .sheets { display: block; overflow: visible; padding: 0; gap: 0; }
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
