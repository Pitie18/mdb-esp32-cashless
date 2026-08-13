# Refill Tour "Füll" Threshold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the "Befüllungstour starten" button, the dashboard refill banner, and the refill wizard itself all trigger on a tray dropping below `fill_when_below` ("Füll"), not only below `min_stock` ("Min") or hitting zero — while showing the Füll-only case as a distinct, less urgent (blue) state from the existing critical (red) / low (amber) states.

**Architecture:** Extract the tray-level classification (`current_stock` vs `min_stock`/`fill_when_below`) that was independently duplicated in four places into one pure function, `classifyTrayStock`, in `app/lib/stock-health.ts`. Every consumer (`useMachines.ts`, the dashboard's `computeStockHealthPerMachine`, and both classification sites in `useRefillWizard.ts`) switches to it and drops its own machine-level "only counts if already low/empty" gate, so a machine whose sole issue is a Füll-level shortfall is no longer invisible end-to-end.

**Tech Stack:** Nuxt 4 (`app/` dir), TypeScript, Vitest for unit tests, Supabase JS client (no schema changes — `machine_trays.min_stock`/`fill_when_below` already exist and are already queried everywhere touched).

## Global Constraints

- Pure additive frontend change. No DB migration, no MQTT/edge-function change — the live-device backward-compat rules in `CLAUDE.md` don't apply here.
- The four consumers being touched are: `app/composables/useMachines.ts`, `app/lib/stock-health.ts`, `app/composables/useRefillWizard.ts` (two sites: `initTour` and `loadTraysForCurrentMachine`). `app/composables/useMachineTrays.ts` and the local helpers in `app/pages/machines/[id]/index.vue` are explicitly **out of scope** — they already distinguish Min vs Füll per tray correctly without a machine-level gate.
- New visual tier color: blue (`bg-blue-500` / `text-blue-600 dark:text-blue-400`), matching the existing "fill" severity color already used in the tray product list (`app/pages/machines/index.vue:311`).
- Health tier ordering everywhere it's sorted: `critical` (0) < `low` (1) < `fill` (2) < `ok` (3).
- i18n: add matching `de.json`/`en.json` keys for every new user-facing string — never a key in only one locale.

---

### Task 1: `classifyTrayStock` shared classifier

**Files:**
- Modify: `management-frontend/app/lib/stock-health.ts`
- Test: `management-frontend/app/lib/__tests__/stock-health.test.ts` (new)

**Interfaces:**
- Produces: `export type TrayStockState = 'critical' | 'low' | 'fill' | 'ok'` and `export function classifyTrayStock(tray: { current_stock: number; min_stock: number; fill_when_below: number }): TrayStockState`. Every later task imports this from `@/lib/stock-health`.

- [ ] **Step 1: Write the failing tests**

Create `management-frontend/app/lib/__tests__/stock-health.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { classifyTrayStock } from '../stock-health'

describe('classifyTrayStock', () => {
  it('is critical when current_stock is 0, even with no min_stock set', () => {
    expect(classifyTrayStock({ current_stock: 0, min_stock: 0, fill_when_below: 0 })).toBe('critical')
  })

  it('is low when current_stock is at or below min_stock', () => {
    expect(classifyTrayStock({ current_stock: 5, min_stock: 5, fill_when_below: 10 })).toBe('low')
    expect(classifyTrayStock({ current_stock: 3, min_stock: 5, fill_when_below: 10 })).toBe('low')
  })

  it('is fill when current_stock is at or below fill_when_below but above min_stock', () => {
    expect(classifyTrayStock({ current_stock: 10, min_stock: 5, fill_when_below: 10 })).toBe('fill')
    expect(classifyTrayStock({ current_stock: 8, min_stock: 5, fill_when_below: 10 })).toBe('fill')
  })

  it('is ok when current_stock is above fill_when_below', () => {
    expect(classifyTrayStock({ current_stock: 11, min_stock: 5, fill_when_below: 10 })).toBe('ok')
  })

  it('ignores a disabled (0) min_stock threshold', () => {
    expect(classifyTrayStock({ current_stock: 3, min_stock: 0, fill_when_below: 10 })).toBe('fill')
  })

  it('ignores a disabled (0) fill_when_below threshold', () => {
    expect(classifyTrayStock({ current_stock: 3, min_stock: 0, fill_when_below: 0 })).toBe('ok')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/stock-health.test.ts`
Expected: FAIL — `classifyTrayStock` is not exported from `../stock-health`.

- [ ] **Step 3: Implement `classifyTrayStock`**

In `management-frontend/app/lib/stock-health.ts`, insert immediately after the closing brace of `isProductRefillable` (after line 45, before the `// ── Simple per-machine stock health (used by dashboard) ──` comment on line 47):

```ts

export type TrayStockState = 'critical' | 'low' | 'fill' | 'ok'

/**
 * Classify a single tray's stock state against its two independent thresholds.
 * A threshold of 0 means "disabled" and is skipped.
 */
export function classifyTrayStock(tray: {
  current_stock: number
  min_stock: number
  fill_when_below: number
}): TrayStockState {
  if (tray.current_stock === 0) return 'critical'
  if (tray.min_stock > 0 && tray.current_stock <= tray.min_stock) return 'low'
  if (tray.fill_when_below > 0 && tray.current_stock <= tray.fill_when_below) return 'fill'
  return 'ok'
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/stock-health.test.ts`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
cd management-frontend
git add app/lib/stock-health.ts app/lib/__tests__/stock-health.test.ts
git commit -m "feat: add classifyTrayStock shared tray classifier"
```

---

### Task 2: Widen `computeStockHealthPerMachine` to 4-tier health

**Files:**
- Modify: `management-frontend/app/lib/stock-health.ts`
- Test: `management-frontend/app/lib/__tests__/stock-health.test.ts`

**Interfaces:**
- Consumes: `classifyTrayStock` (Task 1), `isProductRefillable` (existing, same file).
- Produces: `MachineStockSummary` gains `refillableFill: number` and `health` widens to `'ok' | 'low' | 'fill' | 'critical'`. `TrayRow` gains `fill_when_below: number`. Signature of `computeStockHealthPerMachine` is unchanged. Task 3 consumes `stock.health === 'fill'` and `stock.refillableFill`/equivalent count.

- [ ] **Step 1: Write the failing tests**

In `management-frontend/app/lib/__tests__/stock-health.test.ts`, replace the existing import line (added in Task 1):

```ts
import { classifyTrayStock } from '../stock-health'
```

with:

```ts
import { classifyTrayStock, computeStockHealthPerMachine } from '../stock-health'
```

Then append this new `describe` block at the end of the file (after the `classifyTrayStock` describe block):

```ts
describe('computeStockHealthPerMachine', () => {
  const warehouseMap = new Map<string, number>([['p1', 100]])

  it('is fill when the only tray issue is a fill_when_below breach', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('fill')
    expect(result.get('m1')?.refillableFill).toBe(1)
    expect(result.get('m1')?.refillableLow).toBe(0)
  })

  it('prioritizes critical over low and fill on the same machine', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 0, min_stock: 5, fill_when_below: 10 },
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 8, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('critical')
  })

  it('is ok when no tray breaches any threshold', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 15, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.get('m1')?.health).toBe('ok')
  })

  it('does not require a low/empty tray on the machine for a fill-below tray to count (regression: old machine-level gate hid fill-only machines)', () => {
    const rows = [
      { machine_id: 'm1', product_id: 'p1', capacity: 20, current_stock: 9, min_stock: 5, fill_when_below: 10 },
    ]
    const result = computeStockHealthPerMachine(rows, warehouseMap, true)
    expect(result.has('m1')).toBe(true)
    expect(result.get('m1')?.health).toBe('fill')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/stock-health.test.ts`
Expected: FAIL — `TrayRow`/rows in the test are missing `fill_when_below` per the current type, and `refillableFill` is `undefined` (assertion fails), and `health` never becomes `'fill'` under the current implementation.

- [ ] **Step 3: Rewrite `computeStockHealthPerMachine`**

In `management-frontend/app/lib/stock-health.ts`, replace the whole block from the `MachineStockSummary` interface (line 49) through the end of `computeStockHealthPerMachine` (line 122) with:

```ts
export interface MachineStockSummary {
  refillableEmpty: number
  refillableLow: number
  refillableFill: number
  noStockCount: number
  noStockEmptyCount: number
  totalStock: number
  totalCapacity: number
  health: 'ok' | 'low' | 'fill' | 'critical'
  percent: number
}

interface TrayRow {
  machine_id: string
  product_id: string | null
  capacity: number
  current_stock: number
  min_stock: number
  fill_when_below: number
}

/**
 * Compute warehouse-aware stock health per machine from raw tray rows.
 *
 * - Trays without a product (`product_id == null`) are ignored.
 * - Low/empty/fill-below trays are split into "refillable" (product available in
 *   warehouse) and "no-stock" (not available).
 * - `health` is determined only by refillable trays, priority critical > low > fill.
 */
export function computeStockHealthPerMachine(
  trayRows: TrayRow[],
  warehouseStockMap: Map<string, number>,
  hasWarehouses: boolean,
): Map<string, MachineStockSummary> {
  const map = new Map<string, MachineStockSummary>()

  for (const tray of trayRows) {
    if (!tray.machine_id) continue

    let entry = map.get(tray.machine_id)
    if (!entry) {
      entry = { refillableEmpty: 0, refillableLow: 0, refillableFill: 0, noStockCount: 0, noStockEmptyCount: 0, totalStock: 0, totalCapacity: 0, health: 'ok', percent: 100 }
      map.set(tray.machine_id, entry)
    }

    entry.totalStock += tray.current_stock
    entry.totalCapacity += tray.capacity

    // Skip unassigned trays — nothing to refill
    if (tray.product_id == null) continue

    const state = classifyTrayStock(tray)
    if (state === 'ok') continue

    const refillable = isProductRefillable(tray.product_id, warehouseStockMap, hasWarehouses)
    if (refillable) {
      if (state === 'critical') entry.refillableEmpty++
      else if (state === 'low') entry.refillableLow++
      else entry.refillableFill++
    } else {
      entry.noStockCount++
      if (state === 'critical') entry.noStockEmptyCount++
    }
  }

  // Derive health + percent
  for (const entry of map.values()) {
    entry.health = entry.refillableEmpty > 0
      ? 'critical'
      : entry.refillableLow > 0
        ? 'low'
        : entry.refillableFill > 0
          ? 'fill'
          : 'ok'
    entry.percent = entry.totalCapacity > 0
      ? Math.round((entry.totalStock / entry.totalCapacity) * 100)
      : 100
  }

  return map
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/stock-health.test.ts`
Expected: PASS (10 tests total: 6 from Task 1 + 4 new)

- [ ] **Step 5: Commit**

```bash
cd management-frontend
git add app/lib/stock-health.ts app/lib/__tests__/stock-health.test.ts
git commit -m "feat: widen computeStockHealthPerMachine to a 4-tier fill state"
```

---

### Task 3: Dashboard consumes the fill tier

**Files:**
- Modify: `management-frontend/app/pages/index.vue:41-48,383,406-425,428`
- Modify: `management-frontend/app/components/DashboardMachineList.vue:13-42`

**Interfaces:**
- Consumes: `MachineStockSummary.health` now includes `'fill'` (Task 2); `stock.refillableFill` (Task 2).
- Produces: no new exports — this is leaf UI wiring. Note for reviewers: `DashboardMachine.stock_health` (from `DashboardMachineList.vue`) widens to include `'fill'`; nothing outside this task consumes that type.

This task has no automated test (page-level, Supabase-backed data fetch) — verify manually per Step 5.

- [ ] **Step 1: Add `fill_when_below` to the dashboard's tray query and row type**

In `management-frontend/app/pages/index.vue`, change line 383 from:

```ts
      supabase.from('machine_trays').select('machine_id, product_id, capacity, current_stock, min_stock').in('machine_id', machineIds),
```

to:

```ts
      supabase.from('machine_trays').select('machine_id, product_id, capacity, current_stock, min_stock, fill_when_below').in('machine_id', machineIds),
```

And change lines 406-408 from:

```ts
    const trayRows = (traysRes.data ?? []) as {
      machine_id: string; product_id: string | null; capacity: number; current_stock: number; min_stock: number
    }[]
```

to:

```ts
    const trayRows = (traysRes.data ?? []) as {
      machine_id: string; product_id: string | null; capacity: number; current_stock: number; min_stock: number; fill_when_below: number
    }[]
```

- [ ] **Step 2: Add a `stockFill` count and fold it into the refill banner**

Change lines 41-43 from:

```ts
const stockCritical = ref(0)
const stockLow = ref(0)
const stockSwap = ref(0)
```

to:

```ts
const stockCritical = ref(0)
const stockLow = ref(0)
const stockFill = ref(0)
const stockSwap = ref(0)
```

Change line 48 from:

```ts
const machinesNeedingRefill = computed(() => stockCritical.value + stockLow.value + stockSwap.value)
```

to:

```ts
const machinesNeedingRefill = computed(() => stockCritical.value + stockLow.value + stockFill.value + stockSwap.value)
```

- [ ] **Step 3: Count fill-tier machines and update the swap-detection condition**

Change lines 414-425 from:

```ts
    // Count stock alerts (refillable + swap)
    let critCount = 0
    let lowCount = 0
    let swapCount = 0
    for (const [, stock] of stockMap) {
      if (stock.health === 'critical') critCount++
      else if (stock.health === 'low') lowCount++
      if (stock.health === 'ok' && stock.noStockEmptyCount > 0) swapCount++
    }
    stockCritical.value = critCount
    stockLow.value = lowCount
    stockSwap.value = swapCount
```

to:

```ts
    // Count stock alerts (refillable + swap)
    let critCount = 0
    let lowCount = 0
    let fillCount = 0
    let swapCount = 0
    for (const [, stock] of stockMap) {
      if (stock.health === 'critical') critCount++
      else if (stock.health === 'low') lowCount++
      else if (stock.health === 'fill') fillCount++
      // "ok"/"fill" machines can still separately have non-refillable empty trays (swap candidates);
      // critical/low machines are already counted above, so this avoids double-counting them.
      if ((stock.health === 'ok' || stock.health === 'fill') && stock.noStockEmptyCount > 0) swapCount++
    }
    stockCritical.value = critCount
    stockLow.value = lowCount
    stockFill.value = fillCount
    stockSwap.value = swapCount
```

- [ ] **Step 4: Update the dashboard machine list sort order**

Change line 428 from:

```ts
    const healthOrder: Record<string, number> = { critical: 0, low: 1, ok: 2 }
```

to:

```ts
    const healthOrder: Record<string, number> = { critical: 0, low: 1, fill: 2, ok: 3 }
```

And the two lookups that reference this map (currently `?? 2` as the "unknown/ok" fallback), a few lines below at what is currently lines 445-446:

```ts
      const ha = healthOrder[a.stock_health] ?? 2
      const hb = healthOrder[b.stock_health] ?? 2
```

to:

```ts
      const ha = healthOrder[a.stock_health] ?? 3
      const hb = healthOrder[b.stock_health] ?? 3
```

- [ ] **Step 5: Widen `DashboardMachine.stock_health` and add the blue "fill" color case**

In `management-frontend/app/components/DashboardMachineList.vue`, change line 18 from:

```ts
  stock_health: 'ok' | 'low' | 'critical'
```

to:

```ts
  stock_health: 'ok' | 'low' | 'fill' | 'critical'
```

Change the `stockBarColor` function (lines 32-36) from:

```ts
function stockBarColor(health: 'ok' | 'low' | 'critical'): string {
  if (health === 'critical') return 'bg-red-500'
  if (health === 'low') return 'bg-amber-500'
  return 'bg-green-500'
}
```

to:

```ts
function stockBarColor(health: 'ok' | 'low' | 'fill' | 'critical'): string {
  if (health === 'critical') return 'bg-red-500'
  if (health === 'low') return 'bg-amber-500'
  if (health === 'fill') return 'bg-blue-500'
  return 'bg-green-500'
}
```

Change the `stockBadgeClass` function (lines 38-42) from:

```ts
function stockBadgeClass(health: 'ok' | 'low' | 'critical'): string {
  if (health === 'critical') return 'text-red-600 dark:text-red-400 border-red-200 dark:border-red-800'
  if (health === 'low') return 'text-amber-600 dark:text-amber-400 border-amber-200 dark:border-amber-800'
  return 'text-green-600 dark:text-green-400 border-green-200 dark:border-green-800'
}
```

to:

```ts
function stockBadgeClass(health: 'ok' | 'low' | 'fill' | 'critical'): string {
  if (health === 'critical') return 'text-red-600 dark:text-red-400 border-red-200 dark:border-red-800'
  if (health === 'low') return 'text-amber-600 dark:text-amber-400 border-amber-200 dark:border-amber-800'
  if (health === 'fill') return 'text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800'
  return 'text-green-600 dark:text-green-400 border-green-200 dark:border-green-800'
}
```

- [ ] **Step 6: Type-check**

Run: `cd management-frontend && npx vue-tsc --noEmit` (or `npm run typecheck` if defined — check `package.json` `scripts` first with `cat package.json | grep -A1 '"typecheck"'`; use whichever exists)
Expected: no new errors from `index.vue` or `DashboardMachineList.vue`.

- [ ] **Step 7: Commit**

```bash
cd management-frontend
git add app/pages/index.vue app/components/DashboardMachineList.vue
git commit -m "feat: dashboard refill banner and machine list react to fill-below stock"
```

---

### Task 4: `useMachines.ts` — 4-tier `stock_health` + `fill_trays`

**Files:**
- Modify: `management-frontend/app/composables/useMachines.ts:2,46-56,301-303,341-343,347-368,370-395,398-405`

**Interfaces:**
- Consumes: `classifyTrayStock` (Task 1).
- Produces: `Machine.stock_health` widens to `'ok' | 'low' | 'fill' | 'critical'`; new field `Machine.fill_trays?: number`. Task 5 (`machines/index.vue`) consumes both.

No automated test — this composable is Supabase-backed with no existing unit coverage of its stock logic (consistent with the existing `useMachines.test.ts`, which only covers `updateMachineSettings`). Verify manually per Step 8.

- [ ] **Step 1: Import the shared classifier**

Change line 2 from:

```ts
import { buildWarehouseStockInfo, isProductRefillable } from '@/lib/stock-health'
```

to:

```ts
import { buildWarehouseStockInfo, classifyTrayStock, isProductRefillable } from '@/lib/stock-health'
```

- [ ] **Step 2: Widen the `Machine.stock_health` field and add `fill_trays`**

Change lines 47-50 (inside the `VendingMachine` interface) from:

```ts
  total_trays?: number
  low_trays?: number
  empty_trays?: number
  stock_health?: 'ok' | 'low' | 'critical'
```

to:

```ts
  total_trays?: number
  low_trays?: number
  empty_trays?: number
  fill_trays?: number
  stock_health?: 'ok' | 'low' | 'fill' | 'critical'
```

- [ ] **Step 3: Replace the inline classification in the Pass-1 loop with `classifyTrayStock`, add a `refillableFill` counter**

Change lines 301-303 from:

```ts
        const isLow = tray.min_stock > 0 && tray.current_stock <= tray.min_stock
        const isEmpty = tray.current_stock === 0
        const isFillBelow = !isLow && !isEmpty && tray.fill_when_below > 0 && tray.current_stock <= tray.fill_when_below
```

to:

```ts
        const state = classifyTrayStock(tray)
        const isLow = state === 'low'
        const isEmpty = state === 'critical'
        const isFillBelow = state === 'fill'
```

Change the `stockMap` entry accumulator type (in the block that starts a few lines above, at the `stockMap = new Map<...` declaration around lines 276-287) — find:

```ts
      const stockMap = new Map<string, {
        total: number
        refillableEmpty: number
        refillableLow: number
        noStockCount: number
```

and change to:

```ts
      const stockMap = new Map<string, {
        total: number
        refillableEmpty: number
        refillableLow: number
        refillableFill: number
        noStockCount: number
```

and its matching initializer a few lines below — find:

```ts
          entry = { total: 0, refillableEmpty: 0, refillableLow: 0, noStockCount: 0, totalStock: 0, totalCapacity: 0, deficits: new Map(), noStockDeficits: new Map(), criticalProductIds: new Set(), fillBelowPending: [] }
```

and change to:

```ts
          entry = { total: 0, refillableEmpty: 0, refillableLow: 0, refillableFill: 0, noStockCount: 0, totalStock: 0, totalCapacity: 0, deficits: new Map(), noStockDeficits: new Map(), criticalProductIds: new Set(), fillBelowPending: [] }
```

- [ ] **Step 4: Remove the Pass-2 machine-level gate; count refillable fill-below trays**

Change lines 347-349 from:

```ts
      // Pass 2: for machines with refillable critical/low trays, add fill_when_below deficits
      for (const [, entry] of stockMap) {
        if (entry.refillableLow + entry.refillableEmpty === 0) continue
        for (const tray of entry.fillBelowPending) {
```

to:

```ts
      // Pass 2: fold fill_when_below deficits in for every machine (not gated on
      // already having a low/empty tray — a fill-only machine must still surface)
      for (const [, entry] of stockMap) {
        for (const tray of entry.fillBelowPending) {
```

Then, inside that same loop, change the branch that assigns severity `'fill'` — find:

```ts
          const refillable = isProductRefillable(tray.product_id, warehouseStockMap, hasWarehouses)
          const productName = tray.products?.name ?? `Slot ${tray.item_number}`
          const imagePath = tray.products?.image_path ?? null
          const sellprice = tray.products?.sellprice ?? null
          const discontinued = tray.products?.discontinued ?? false
          const key = tray.product_id
          const targetMap = refillable ? entry.deficits : entry.noStockDeficits
          const existing = targetMap.get(key)
          if (existing) {
            existing.deficit += deficit
            // Don't downgrade severity — fill is lowest priority
          } else {
            targetMap.set(key, { product_name: productName, product_id: tray.product_id, deficit, image_path: imagePath, sellprice, in_stock: refillable, severity: 'fill', discontinued })
          }
```

and change to (adding the `refillableFill` increment, matching how Pass 1 increments `refillableEmpty`/`refillableLow`):

```ts
          const refillable = isProductRefillable(tray.product_id, warehouseStockMap, hasWarehouses)
          if (refillable) entry.refillableFill++
          const productName = tray.products?.name ?? `Slot ${tray.item_number}`
          const imagePath = tray.products?.image_path ?? null
          const sellprice = tray.products?.sellprice ?? null
          const discontinued = tray.products?.discontinued ?? false
          const key = tray.product_id
          const targetMap = refillable ? entry.deficits : entry.noStockDeficits
          const existing = targetMap.get(key)
          if (existing) {
            existing.deficit += deficit
            // Don't downgrade severity — fill is lowest priority
          } else {
            targetMap.set(key, { product_name: productName, product_id: tray.product_id, deficit, image_path: imagePath, sellprice, in_stock: refillable, severity: 'fill', discontinued })
          }
```

- [ ] **Step 5: Compute 4-tier `stock_health` and set `fill_trays`**

Change lines 373-380 from:

```ts
        if (stock) {
          machine.total_trays = stock.total
          machine.low_trays = stock.refillableLow + stock.refillableEmpty
          machine.empty_trays = stock.refillableEmpty
          machine.stock_health = stock.refillableEmpty > 0 ? 'critical' : (stock.refillableLow > 0 ? 'low' : 'ok')
          machine.stock_percent = stock.totalCapacity > 0
            ? Math.round((stock.totalStock / stock.totalCapacity) * 100)
            : 0
```

to:

```ts
        if (stock) {
          machine.total_trays = stock.total
          machine.low_trays = stock.refillableLow + stock.refillableEmpty
          machine.empty_trays = stock.refillableEmpty
          machine.fill_trays = stock.refillableFill
          machine.stock_health = stock.refillableEmpty > 0
            ? 'critical'
            : stock.refillableLow > 0
              ? 'low'
              : stock.refillableFill > 0
                ? 'fill'
                : 'ok'
          machine.stock_percent = stock.totalCapacity > 0
            ? Math.round((stock.totalStock / stock.totalCapacity) * 100)
            : 0
```

- [ ] **Step 6: Set `fill_trays` in the no-tray-data fallback branch**

Change lines 385-394 from:

```ts
        } else {
          machine.total_trays = 0
          machine.low_trays = 0
          machine.empty_trays = 0
          machine.stock_health = 'ok'
          machine.stock_percent = 0
          machine.tray_summary = []
          machine.critical_product_ids = new Set()
          machine.no_stock_trays = 0
          machine.no_stock_summary = []
        }
```

to:

```ts
        } else {
          machine.total_trays = 0
          machine.low_trays = 0
          machine.empty_trays = 0
          machine.fill_trays = 0
          machine.stock_health = 'ok'
          machine.stock_percent = 0
          machine.tray_summary = []
          machine.critical_product_ids = new Set()
          machine.no_stock_trays = 0
          machine.no_stock_summary = []
        }
```

- [ ] **Step 7: Update the machine-list sort order**

Change line 399 from:

```ts
      const healthOrder: Record<string, number> = { critical: 0, low: 1, ok: 2 }
```

to:

```ts
      const healthOrder: Record<string, number> = { critical: 0, low: 1, fill: 2, ok: 3 }
```

Change the two lookups a couple of lines below (currently lines 401-402):

```ts
        const ha = healthOrder[a.stock_health ?? 'ok']
        const hb = healthOrder[b.stock_health ?? 'ok']
```

These stay as-is — `?? 'ok'` still resolves through the same map, now to `3`. No change needed here; verify while reading that the fallback key `'ok'` still exists in the updated map (it does).

- [ ] **Step 8: Manual verification**

Run: `cd management-frontend && npm run dev`, open `/machines`. Using Supabase Studio (or the Trays tab on a machine detail page), set one tray's `current_stock` to a value between `fill_when_below` and `min_stock` on an otherwise fully-stocked machine. Reload `/machines` and confirm the machine object (via Vue devtools or a temporary `console.log`) has `stock_health: 'fill'` and `fill_trays: 1`. Remove any temporary debug code before committing.

- [ ] **Step 9: Commit**

```bash
cd management-frontend
git add app/composables/useMachines.ts
git commit -m "feat: useMachines computes a 4-tier stock_health including fill-below"
```

---

### Task 5: `/machines` page UI — blue dot, topoff badge, sort order, i18n

**Files:**
- Modify: `management-frontend/app/pages/machines/index.vue:61-64,209-217,273-299`
- Modify: `management-frontend/i18n/locales/de.json:437`
- Modify: `management-frontend/i18n/locales/en.json:437`

**Interfaces:**
- Consumes: `Machine.stock_health === 'fill'` and `Machine.fill_trays` (Task 4).

No automated test (template-only change on a Supabase-backed page) — verify manually per Step 5.

- [ ] **Step 1: Add i18n keys**

In `management-frontend/i18n/locales/de.json`, after line 437 (`"noWarehouseStock": "{count} kein Lager",`), insert:

```json
    "topoffRecommended": "{count} Auffüllen empfohlen",
```

In `management-frontend/i18n/locales/en.json`, after line 437 (`"noWarehouseStock": "{count} no stock",`), insert:

```json
    "topoffRecommended": "{count} recommended to top off",
```

- [ ] **Step 2: Add the blue "fill" status dot**

In `management-frontend/app/pages/machines/index.vue`, change lines 210-217 from:

```vue
                  <!-- Stock health dot -->
                  <span
                    class="inline-block h-3 w-3 rounded-full"
                    :class="{
                      'bg-red-500': (machine.stock_health ?? 'ok') === 'critical',
                      'bg-amber-500': (machine.stock_health ?? 'ok') === 'low',
                      'bg-green-500': (machine.stock_health ?? 'ok') === 'ok',
                    }"
                  />
```

to:

```vue
                  <!-- Stock health dot -->
                  <span
                    class="inline-block h-3 w-3 rounded-full"
                    :class="{
                      'bg-red-500': (machine.stock_health ?? 'ok') === 'critical',
                      'bg-amber-500': (machine.stock_health ?? 'ok') === 'low',
                      'bg-blue-500': (machine.stock_health ?? 'ok') === 'fill',
                      'bg-green-500': (machine.stock_health ?? 'ok') === 'ok',
                    }"
                  />
```

- [ ] **Step 3: Add the "topoff recommended" badge**

Change lines 293-298 (the last badge in the row, `noWarehouseStock`) from:

```vue
                    <span
                      v-if="machine.no_stock_summary?.some(i => i.severity !== 'critical')"
                      class="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground"
                    >
                      {{ t('machines.noWarehouseStock', { count: machine.no_stock_summary!.filter(i => i.severity !== 'critical').length }) }}
                    </span>
```

to (adding the new badge as a sibling immediately after):

```vue
                    <span
                      v-if="machine.no_stock_summary?.some(i => i.severity !== 'critical')"
                      class="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground"
                    >
                      {{ t('machines.noWarehouseStock', { count: machine.no_stock_summary!.filter(i => i.severity !== 'critical').length }) }}
                    </span>
                    <span
                      v-if="(machine.fill_trays ?? 0) > 0"
                      class="inline-flex items-center gap-1 rounded-md bg-blue-500/10 px-2 py-0.5 text-xs font-semibold text-blue-600 dark:text-blue-400"
                    >
                      {{ t('machines.topoffRecommended', { count: machine.fill_trays }) }}
                    </span>
```

- [ ] **Step 4: Update the "sort by stock health" order**

Change lines 61-64 from:

```ts
    // stockHealth: critical > low > ok
    const healthOrder: Record<string, number> = { critical: 0, low: 1, ok: 2 }
    return (healthOrder[a.stock_health ?? 'ok'] ?? 2) - (healthOrder[b.stock_health ?? 'ok'] ?? 2)
```

to:

```ts
    // stockHealth: critical > low > fill > ok
    const healthOrder: Record<string, number> = { critical: 0, low: 1, fill: 2, ok: 3 }
    return (healthOrder[a.stock_health ?? 'ok'] ?? 3) - (healthOrder[b.stock_health ?? 'ok'] ?? 3)
```

- [ ] **Step 5: Manual verification**

Run: `cd management-frontend && npm run dev`, open `/machines`. Using the setup from Task 4 Step 8 (one machine with a single tray between `fill_when_below` and `min_stock`, nothing else low/empty on that machine):
- Confirm the machine's status dot is blue.
- Confirm the "X Auffüllen empfohlen" badge appears (and toggle the locale switcher to confirm the English string too).
- Confirm the "Befüllungstour starten" button is now visible (it wasn't before this change).
- Confirm a machine with an actual critical/low tray still shows red/amber as before (regression check).
- Confirm sorting by "Füllstand" (stock health) places the fill-only machine after critical/low machines and before fully-ok ones.

Take a screenshot (via the Browser pane) of the machine card showing the blue dot + badge for the record.

- [ ] **Step 6: Commit**

```bash
cd management-frontend
git add app/pages/machines/index.vue i18n/locales/de.json i18n/locales/en.json
git commit -m "feat: show a topoff-recommended badge and enable the tour button on fill-below stock"
```

---

### Task 6: `useRefillWizard.ts` — remove both fill-below gates

**Files:**
- Modify: `management-frontend/app/composables/useRefillWizard.ts:1-3,9-18,476-544,734-740`

**Interfaces:**
- Consumes: `classifyTrayStock` (Task 1).
- Produces: `RefillMachine.stock_health` widens to `'ok' | 'low' | 'fill' | 'critical'`. Nothing outside this file consumes `RefillMachine` directly by that field name in a way requiring further changes (verified: `machine.stock_health` is only read inside this same file, for the same badge-coloring purpose the wizard UI already handles generically — no other widen needed).

No automated test — `initTour`/`loadTraysForCurrentMachine` are Supabase-backed with no existing unit coverage (the two existing test files for this composable, `useRefillWizard.refillSnapshot.test.ts` and `useRefillWizard.tourStarted.test.ts`, cover unrelated pure functions only). Verify manually per Step 5.

- [ ] **Step 1: Import the shared classifier**

Change line 1-3 from:

```ts
import { useSupabaseClient } from '#imports'
import { useOrganization } from './useOrganization'
import { useWarehouse } from './useWarehouse'
```

to:

```ts
import { useSupabaseClient } from '#imports'
import { classifyTrayStock } from '@/lib/stock-health'
import { useOrganization } from './useOrganization'
import { useWarehouse } from './useWarehouse'
```

- [ ] **Step 2: Widen the `RefillMachine.stock_health` type**

Change line 12 from:

```ts
  stock_health: 'ok' | 'low' | 'critical'
```

to:

```ts
  stock_health: 'ok' | 'low' | 'fill' | 'critical'
```

- [ ] **Step 3: `initTour` — use the shared classifier, drop the Pass-2 gate, track a `fill` count**

Change the `stockMap` type declaration (lines 455-463) from:

```ts
      const stockMap = new Map<string, {
        total: number
        low: number
        empty: number
        totalStock: number
        totalCapacity: number
        deficits: Map<string, RefillItem>
        fillBelowPending: any[]
      }>()
```

to:

```ts
      const stockMap = new Map<string, {
        total: number
        low: number
        empty: number
        fill: number
        totalStock: number
        totalCapacity: number
        deficits: Map<string, RefillItem>
        fillBelowPending: any[]
      }>()
```

Change the entry initializer (line 469) from:

```ts
          entry = { total: 0, low: 0, empty: 0, totalStock: 0, totalCapacity: 0, deficits: new Map(), fillBelowPending: [] }
```

to:

```ts
          entry = { total: 0, low: 0, empty: 0, fill: 0, totalStock: 0, totalCapacity: 0, deficits: new Map(), fillBelowPending: [] }
```

Change the per-tray classification block (lines 476-481) from:

```ts
        const isLow = tray.min_stock > 0 && tray.current_stock <= tray.min_stock
        const isEmpty = tray.current_stock === 0
        const isFillBelow = !isLow && !isEmpty && tray.fill_when_below > 0 && tray.current_stock <= tray.fill_when_below

        if (isEmpty) entry.empty++
        else if (isLow) entry.low++
```

to:

```ts
        const state = classifyTrayStock(tray)
        const isLow = state === 'low'
        const isEmpty = state === 'critical'
        const isFillBelow = state === 'fill'

        if (isEmpty) entry.empty++
        else if (isLow) entry.low++
        else if (isFillBelow) entry.fill++
```

Change the Pass-2 gate (lines 502-504) from:

```ts
      // Add fill_when_below deficits for machines with critical trays
      for (const [, entry] of stockMap) {
        if (entry.low + entry.empty === 0) continue
```

to:

```ts
      // Add fill_when_below deficits for every machine (not gated on already
      // having a low/empty tray — a fill-only machine must still surface)
      for (const [, entry] of stockMap) {
```

Change the machine-inclusion gate and `RefillMachine` construction (lines 521-536) from:

```ts
      // Build RefillMachine list (only machines needing refill)
      const result: RefillMachine[] = []
      for (const m of (machineData ?? []) as any[]) {
        const stock = stockMap.get(m.id)
        if (!stock || (stock.empty === 0 && stock.low === 0)) continue
        result.push({
          id: m.id,
          name: m.name ?? 'Unnamed',
          stock_health: stock.empty > 0 ? 'critical' : 'low',
          stock_percent: stock.totalCapacity > 0 ? Math.round((stock.totalStock / stock.totalCapacity) * 100) : 0,
          empty_trays: stock.empty,
          low_trays: stock.low + stock.empty,
          total_trays: stock.total,
          tray_summary: Array.from(stock.deficits.values()).sort((a, b) => b.deficit - a.deficit),
        })
      }
```

to:

```ts
      // Build RefillMachine list (only machines needing refill)
      const result: RefillMachine[] = []
      for (const m of (machineData ?? []) as any[]) {
        const stock = stockMap.get(m.id)
        if (!stock || (stock.empty === 0 && stock.low === 0 && stock.fill === 0)) continue
        result.push({
          id: m.id,
          name: m.name ?? 'Unnamed',
          stock_health: stock.empty > 0 ? 'critical' : stock.low > 0 ? 'low' : 'fill',
          stock_percent: stock.totalCapacity > 0 ? Math.round((stock.totalStock / stock.totalCapacity) * 100) : 0,
          empty_trays: stock.empty,
          low_trays: stock.low + stock.empty,
          total_trays: stock.total,
          tray_summary: Array.from(stock.deficits.values()).sort((a, b) => b.deficit - a.deficit),
        })
      }
```

Change the tour sort order (line 539) from:

```ts
      const healthOrder: Record<string, number> = { critical: 0, low: 1, ok: 2 }
```

to:

```ts
      const healthOrder: Record<string, number> = { critical: 0, low: 1, fill: 2, ok: 3 }
```

(line 541-542's `?? 2` fallbacks stay unchanged — `'ok'` never actually appears as a `stock_health` value in this list since only machines needing refill are pushed, so the fallback is dead code either way, same as before this change.)

- [ ] **Step 4: `loadTraysForCurrentMachine` — drop the local `hasCritical` gate**

Change lines 734-740 from:

```ts
      for (const t of (data ?? []) as any[]) {
        const isLow = t.min_stock > 0 && t.current_stock <= t.min_stock
        const isEmpty = t.current_stock === 0
        const hasCritical = (data as any[]).some((tr: any) => tr.current_stock === 0 || (tr.min_stock > 0 && tr.current_stock <= tr.min_stock))
        const isFillBelow = hasCritical && !isLow && !isEmpty && t.fill_when_below > 0 && t.current_stock <= t.fill_when_below

        if (!isLow && !isEmpty && !isFillBelow) continue
```

to:

```ts
      for (const t of (data ?? []) as any[]) {
        const state = classifyTrayStock(t)
        const isLow = state === 'low'
        const isEmpty = state === 'critical'
        const isFillBelow = state === 'fill'

        if (!isLow && !isEmpty && !isFillBelow) continue
```

- [ ] **Step 5: Manual verification**

Run: `cd management-frontend && npm run dev`. Using the same single-fill-below-tray machine from Task 4/5:
- On `/machines`, click "Befüllungstour starten".
- Confirm the machine appears in the tour's machine list (packing step).
- Confirm the fill-below product appears in the combined pick list with a non-zero deficit.
- Proceed to that machine's per-machine refill step and confirm the tray shows up with the correct `fill_amount`.
- Complete the tour and confirm no errors.
- Regression check: repeat with a machine that has a genuine critical/low tray (existing behavior) and confirm it still works exactly as before.

- [ ] **Step 6: Commit**

```bash
cd management-frontend
git add app/composables/useRefillWizard.ts
git commit -m "feat: refill wizard includes fill-below-only machines and trays"
```

---

### Task 7: Full regression pass

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit test suite**

Run: `cd management-frontend && npx vitest run`
Expected: PASS, including the 10 new tests from Tasks 1-2 and all pre-existing tests (in particular `useMachines.test.ts` and both `useRefillWizard.*.test.ts` files, to confirm nothing in this plan broke their unrelated coverage).

- [ ] **Step 2: End-to-end manual pass in the browser**

Using `npm run dev`, on a machine with three trays in three different states (one empty, one at/below min_stock, one at/below fill_when_below only):
- `/machines`: card shows red "ausverkauft" + amber "Refill nötig" + blue "Auffüllen empfohlen" badges together; dot is red (critical wins); "Befüllungstour starten" visible.
- `/` (dashboard): refill banner count includes this machine; the small machine list shows it (still red, since critical wins there too).
- Start a tour: all three trays appear correctly in packing + per-machine refill steps with correct deficits.
- Then repeat with a *second*, separate machine that has **only** a fill-below tray (no critical/low) to specifically confirm the previously-broken case end-to-end: blue dot only, blue badge only, button still visible because of this second machine, dashboard banner count includes it, and the tour includes and correctly packs this machine too.

- [ ] **Step 3: Report results**

No commit for this task — it's a verification gate. If any check fails, return to the relevant task above, fix, and re-run from Step 1.
