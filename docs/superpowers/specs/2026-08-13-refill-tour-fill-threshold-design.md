# Refill tour: trigger on "Füll" threshold, not just "Min"

**Date:** 2026-08-13
**Area:** `management-frontend` (PWA only)
**Type:** Small-to-medium additive change — widen the trigger for the "Befüllungstour starten" button (and the dashboard refill banner) to also fire when a tray drops below its `fill_when_below` ("Füll") threshold, not only its `min_stock` ("Min") threshold. Includes a consolidation of the tray-classification logic that was independently duplicated in three places.

## Goal

On `/machines`, the "Befüllungstour starten" button only appears once a tray drops to or below its `min_stock` ("Min") threshold or hits zero. Trays that have only crossed the higher, opportunistic `fill_when_below` ("Füll") threshold — "worth topping off, not yet urgent" — are silently ignored: the button doesn't show, the dashboard's red refill banner doesn't fire, and even if a tour is started for another reason, the refill wizard never surfaces or packs those items.

The fix: a machine whose only issue is a Füll-level shortfall should still be considered "needs a tour" everywhere that decision is made (the `/machines` button, the dashboard banner, and the refill wizard's own machine/tray selection) — but visually distinguished from a true Min/critical shortage, since it's a lower-urgency signal.

## Existing pattern / root cause

The tray-level classification `isLow = min_stock>0 && current_stock<=min_stock`, `isEmpty = current_stock===0`, `isFillBelow = !isLow && !isEmpty && fill_when_below>0 && current_stock<=fill_when_below` is reimplemented independently in four places:

1. `app/composables/useMachines.ts` (drives `machine.stock_health` → the `/machines` button + card badges)
2. `app/lib/stock-health.ts::computeStockHealthPerMachine` (drives the dashboard banner)
3. `app/composables/useRefillWizard.ts::initTour` (drives which machines enter a tour + the packing list)
4. `app/composables/useRefillWizard.ts::loadTraysForCurrentMachine` (drives the per-machine refill step's tray list)

All four **compute** `isFillBelow` but then gate it behind "this machine already has a low/empty tray" before it can affect anything:
- (1) and (3) skip folding fill-below deficits in unless `refillableLow/Empty > 0` (or `low/empty > 0`).
- (3)'s outer loop excludes the machine entirely from `machines.value` unless `stock.empty>0 || stock.low>0`.
- (4) additionally requires a **local** `hasCritical` flag (computed from the same machine's trays) before treating any tray as fill-below.

So a machine with *only* fill-below trays is invisible end-to-end: no badge, no button, not in the wizard's machine list, and (redundantly) not in the per-machine refill screen either.

Two other places already handle Min vs Füll correctly at the single-tray display level and are **out of scope**: `useMachineTrays.ts`'s `isLowStock`/`isFillBelow` helpers and the local `isLowStock`/`isFillBelow` functions in `machines/[id]/index.vue` (~line 1020/1024) — both already show Min and Füll as distinct per-tray states without a machine-level gate. Leaving them untouched avoids scope creep beyond what's needed to fix the actual bug.

## Design

### 1. Shared classification helper (`app/lib/stock-health.ts`)

```ts
export type TrayStockState = 'critical' | 'low' | 'fill' | 'ok'

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

Consumers derive booleans where needed (`isEmpty = state === 'critical'`, `isLow = state === 'low'`, `isFillBelow = state === 'fill'`) or switch on the state directly. This replaces the inline triplet in `useMachines.ts`, `stock-health.ts`, and both spots in `useRefillWizard.ts`.

### 2. `useMachines.ts` (drives `/machines` page)

- Use `classifyTrayStock` in the Pass-1 loop instead of the inline booleans.
- **Remove** the Pass-2 gate `if (entry.refillableLow + entry.refillableEmpty === 0) continue` — fill-below deficits fold in regardless of whether the machine also has low/empty trays. The existing refillable/not-refillable split (`targetMap = refillable ? entry.deficits : entry.noStockDeficits`) is unchanged and already routes non-refillable fill-below trays correctly.
- Add `entry.refillableFill` counter, incremented per refillable tray in state `'fill'` (mirrors `refillableEmpty`/`refillableLow`).
- `machine.stock_health` becomes 4-tier: `refillableEmpty>0 ? 'critical' : refillableLow>0 ? 'low' : refillableFill>0 ? 'fill' : 'ok'`.
- New field `machine.fill_trays = stock.refillableFill` (parallels `low_trays`/`empty_trays`).

### 3. `machines/index.vue` UI

- Button `v-if="machines.some(m => (m.stock_health ?? 'ok') !== 'ok')"` is unchanged — it now naturally fires for `'fill'` too.
- Status dot: add a 4th case, `'bg-blue-500': (machine.stock_health ?? 'ok') === 'fill'`, alongside the existing red/amber/green cases.
- New badge, sibling to the existing ones (lines ~274-298): `v-if="(machine.fill_trays ?? 0) > 0"`, blue/muted styling (matching the visual weight of `noWarehouseStock`, not the red/amber urgency badges), new i18n key `machines.topoffRecommended`.
- `machineSortKey === 'stockHealth'` order: `{ critical: 0, low: 1, fill: 2, ok: 3 }` (was `{ critical: 0, low: 1, ok: 2 }`).

### 4. Dashboard (`app/lib/stock-health.ts::computeStockHealthPerMachine` + `app/pages/index.vue`)

- `computeStockHealthPerMachine`: same treatment as (2) — use `classifyTrayStock`, drop the equivalent gate, return a 4-tier `health` and a new fill count field alongside the existing ones.
- `index.vue`: add `stockFill` (mirrors `stockCritical`/`stockLow`), and fold it into the existing sum: `machinesNeedingRefill = stockCritical + stockLow + stockSwap + stockFill`. The banner itself is **not** split into a separate/softer variant — it already aggregates dissimilar severities (`stockSwap` is a different problem class and already shares the banner), so this follows the existing pattern rather than introducing a new one. Banner text/style unchanged.

### 5. `useRefillWizard.ts` (the part that actually makes the tour work)

Without this, the button would appear but starting a tour would find nothing — this composable has its own independent gates.

- `initTour`'s per-machine accumulation: use `classifyTrayStock`; remove the Pass-2 gate (`if (entry.low + entry.empty === 0) continue`); add a `fill` counter to the accumulator.
- Final inclusion gate: `if (!stock || (stock.empty === 0 && stock.low === 0)) continue` → `... && stock.fill === 0`.
- `RefillMachine.stock_health` type widens to include `'fill'`; value becomes `stock.empty > 0 ? 'critical' : stock.low > 0 ? 'low' : 'fill'`.
- `healthOrder` sort map used for tour ordering: `{ critical: 0, low: 1, fill: 2, ok: 3 }`.
- `loadTraysForCurrentMachine`: drop the local `hasCritical` gate entirely. `isFillBelow` (via `classifyTrayStock`) no longer requires "this machine also has a critical/low tray" — a fill-below tray surfaces on its own machine's refill screen even when nothing else on that machine needs attention.

### 6. i18n (`i18n/locales/en.json` + `de.json`)

One new key pair for the new badge:

| key | de | en |
|-----|----|----|
| `machines.topoffRecommended` | `{count} Auffüllen empfohlen` | `{count} recommended to top off` |

No change to `machineDetail.min`/`machineDetail.fill` — those labels are already correct.

## Backward compatibility / risk

Purely additive frontend change — no DB schema, MQTT, or edge-function surface touched, so the live-device backward-compat rules don't apply. `machine_trays.min_stock` and `fill_when_below` already exist and are already fetched everywhere this design touches; no new columns or queries. `stock_health`/`RefillMachine.stock_health` gain a new union member (`'fill'`) — any other consumer of these types that doesn't already handle an unknown value should be checked for exhaustive `switch`/type-narrowing that would now warn or silently fall through (expected to just be the two files already covered by this design; verify during implementation).

## Testing

- **Unit (Vitest)** — `classifyTrayStock` gets direct coverage in a `stock-health` test file: empty (current_stock=0) → critical even if min_stock=0; below/at min_stock → low; below/at fill_when_below with min_stock not breached → fill; above fill_when_below → ok; min_stock=0 or fill_when_below=0 (disabled) skip that tier correctly; boundary values (`current_stock === min_stock`, `current_stock === fill_when_below`).
- **Manual** — on a test machine: set one tray's `current_stock` between `fill_when_below` and `min_stock` (all other trays healthy). Confirm: blue dot + "Auffüllen empfohlen" badge on `/machines`; "Befüllungstour starten" button appears; dashboard banner fires; starting the tour includes the machine and the per-machine refill screen shows that tray; packing/combined mode includes it. Then also verify the existing critical/low paths still behave unchanged (regression check).

## Files touched

| File | Change |
|------|--------|
| `app/lib/stock-health.ts` | Add `TrayStockState` type + `classifyTrayStock` helper; update `computeStockHealthPerMachine` to use it, drop the fill-below machine-level gate, return a fill count |
| `app/composables/useMachines.ts` | Use `classifyTrayStock`; remove Pass-2 gate; add `refillableFill` counter; `stock_health` becomes 4-tier; new `fill_trays` field |
| `app/pages/machines/index.vue` | Blue dot case for `'fill'`; new topoff badge; updated `stockHealth` sort order |
| `app/pages/index.vue` | New `stockFill` count folded into `machinesNeedingRefill` |
| `app/composables/useRefillWizard.ts` | Use `classifyTrayStock` in `initTour` and `loadTraysForCurrentMachine`; remove both gates (`low+empty===0` and `hasCritical`); widen `RefillMachine.stock_health` type; update `healthOrder` |
| `i18n/locales/en.json`, `i18n/locales/de.json` | New `machines.topoffRecommended` key |
| `app/composables/__tests__/` (new or existing stock-health test file) | Unit tests for `classifyTrayStock` |
