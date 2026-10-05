# Nayax reconciliation: order-based (sequence) matching

**Date:** 2026-06-01
**Area:** `management-frontend` → `/reports/nayax-reconciliation`
**Type:** Behavior change to an existing feature (matching algorithm + results presentation)

## Problem

The Nayax reconciliation matcher pairs each Nayax row to a DB sale by
`machine + slot + price` **within a ±N-second time window** (`toleranceSeconds`,
default 10 s). When the machine clock and the MQTT/DB timestamps drift apart —
which happens "here and there" in the field — the window misfires:

- a real, present sale falls outside the window → wrongly reported **missing in DB**,
- and its DB twin is left unconsumed → wrongly reported as a **ghost (DB-only)**.

So a single drifted sale produces *two* false findings at once. The operator
can't trust either bucket, which defeats the tool's purpose: filling genuine
gaps in the local DB.

## Goal

Stop trusting the seconds. Model the reality the operator describes:

- **Nayax is the complete, source-of-truth list.** Everything that happened is in it.
- **The local DB is the same list with gaps** (missed MQTT messages) and,
  occasionally, **phantoms** (sales the DB counted that never happened —
  double-counts, aborted vends).

Therefore: per machine, compare the two as **ordered sequences of products** and
report, in both directions, what doesn't line up:

| Direction | Meaning | Operator action |
|-----------|---------|-----------------|
| In Nayax, not in DB | a real sale the DB missed | **add** (bulk import — unchanged) |
| In DB, not in Nayax | a phantom / double-count | **review & remove** (per-row delete — unchanged) |

Time is used **only to sort** each sequence — never to decide a match. A constant
clock offset or a few seconds of drift no longer changes any result.

## Approach: per-machine sequence diff (LCS), keyed on slot

Chosen over (B) a greedy two-pointer heuristic — rejected because a heuristic that
can invent false gaps is unacceptable in a tool that *creates sales records* — and
over (C) widening the time tolerance — rejected because it papers over the very
problem we're removing.

### Matching key (decided)

Two sales align when they share the **same slot / `item_number`**, in sequence
order. **Price is not part of the key.** When an aligned pair has differing prices,
it still counts as matched but is **flagged** (`priceDiffers`) so a re-priced or
mis-recorded product is visible without creating a false gap.

### Algorithm (`useNayaxReconciliation.ts → runMatch`)

The pre-filter is **unchanged**:
- Nayax row on an unmapped machine → `unmapped`.
- Nayax row with `itemNumber == null` or `priceGross <= 0` → `unparseable`.

Everything else is **grouped by mapped VM id** and aligned independently:

1. **A** = that machine's eligible Nayax rows, sorted by `utcDt` ascending.
2. **B** = that machine's DB sales, sorted by `created_at` ascending.
3. Compute **LCS(A, B)** with equality predicate `a.itemNumber === b.item_number`.
   Recover the alignment:
   - aligned pairs → `matched`. Each carries `deltaSeconds`
     (`db.created_at − nayax.utcDt`, now **informational only**) and
     `priceDiffers = roundTo2(nayax.priceGross) !== roundTo2(db.item_price)`.
   - A-only items → `missingInDb` (the importable gaps).
   - B-only items, **strictly within `[fromUtc, toUtc]`** → `ghostInDb` (phantoms).
     (B items only in the boundary buffer — see below — are dropped, never ghosts.)
   - **The strict-range filter applies only to the B-only remainder.** An aligned
     pair stays `matched` even when its DB row's `created_at` falls in the buffer
     (outside the strict range) — i.e. don't re-filter aligned pairs by range.
4. Concatenate all machines' results into the existing `ReconResult` shape.

Repeated products (same slot vended several times) are handled naturally by the
sequence diff. Two machines never cross-align (grouping is per VM).

**Complexity & guard.** LCS is `O(n·m)` time / `O(n·m)` space per machine. For a
realistic monthly–quarterly export (a busy machine ≈ 1,500 sales/mo → ~2.25M cells,
`Int32Array`, < 100 ms) this is trivial. A per-machine guard caps the DP at a cell
budget (≈ 25M cells); if a single machine's `n·m` exceeds it, that machine falls
back to **day-bucketed LCS** (align within each file-timezone calendar day, then
concatenate) and a non-blocking notice is surfaced. This keeps the tab from ever
hanging on a pathological single-machine, year-long upload, at the cost of possibly
splitting a sale that drifted across midnight on that machine only. No silent
truncation — if the fallback engages, the operator is told.

### Boundary robustness

`loadDbSales` widens its query window by a small fixed **±2-minute buffer** around
`[fromUtc, toUtc]`. A sale that drifted just across the file's start/end can then
still find its twin and align (no false gap at the edges). The ghost filter stays
**strict** to the real `[fromUtc, toUtc]`, so a buffer-only DB row can partner a
match but can never itself be flagged as a phantom.

**In-range rows are matched authoritatively (two-pass).** Aligning the Nayax
sequence against *all* loaded DB rows at once is unsafe at the start edge: because
LCS greedily takes the earliest equal slot, a buffer row just *before* `fromUtc`
with the same slot as a real in-range sale would steal the match and orphan the
in-range sale into a false phantom (which the operator would then delete). So
`runMatch` aligns per machine in two passes: **Pass 1** aligns the Nayax rows
against the *in-range* DB rows only — these matches are authoritative and the
Pass-1 leftovers are the true in-range phantoms; **Pass 2** aligns only the
*residual* unmatched Nayax rows against the buffer-only DB rows (genuine
cross-boundary drift), with residual Nayax rows becoming `missingInDb` and
unmatched buffer rows dropped. This keeps the buffer's benefit (no false missing
at the edges → no duplicate imports) without letting a buffer row displace a real
in-range sale.

**Pass 3 — order-swap leftovers (added 2026-10-05).** The LCS is order-only, so
two neighbouring sales whose order differs between Nayax and the DB can only align
one of them; the other used to surface as a `missingInDb` + `ghostInDb` pair on the
same slot. In practice this happens whenever the device clock drifts against Nayax,
and especially after an import: an imported sale carries the Nayax time and lands
among device-timestamped neighbours in a different order. Deleting the ghost and
importing the gap then recreated the same swap, so the operator could never clear
the difference. Pass 3 (`pairLeftoversByTime`) therefore pairs the residual Nayax
rows (after Pass 2) with the Pass-1 phantom candidates when their effective slot is
equal and `|Δt| ≤ LEFTOVER_PAIR_WINDOW_SECONDS` (15 min), greedily, closest pair
first. Only rows still unpaired after Pass 3 are reported as `missingInDb` /
`ghostInDb`. Known tradeoff: a genuine gap and a genuine phantom on the same slot
within 15 minutes of each other are now paired instead of reported — far rarer
than the swap it fixes.

The buffer is a **local computation inside `loadDbSales`** applied only to the
Supabase `.gte` / `.lte` query bounds. It must **not** mutate `settings.fromUtc` /
`settings.toUtc` — those remain the strict range used by `runMatch`'s ghost filter
and by `fileDateRange` in the results header.

## Data model changes (`useNayaxReconciliation.ts`)

- `MatchPair` gains `priceDiffers: boolean`. `deltaSeconds` is retained (now
  informational; still used by CSV export and the matched table).
- `settings` loses `toleranceSeconds`. Shape becomes
  `{ timezone, fromUtc, toUtc }`.
- `ReconResult.settings` loses `toleranceSeconds`. (The buckets `matched`,
  `missingInDb`, `ghostInDb`, `unmapped`, `unparseable` and `fileDateRange` are
  unchanged.)
- `bulkImportMissing`, `deleteGhost`, `logNayaxActivity`, `exportDiffCsv`, `reset`,
  mapping helpers, and the parser are **unchanged in behavior**. (`exportDiffCsv`
  keeps its `delta_seconds` column.)

## UI changes

### Settings step (`NayaxSettingsStep.vue`, `pages/reports/nayax-reconciliation.vue`)

- **Remove the "Time tolerance (seconds)" number field** and its hint.
- Remove the `nayax-reconcile-tolerance` localStorage read/write and the
  `Math.max(5, Math.min(600, …))` tolerance clamp in both the component's `submit()`
  and the page's `onMounted`. From / To / Timezone (and the `nayax-reconcile-tz`
  localStorage) stay.

### Results header (`NayaxResultsView.vue`)

- Replace the `· ±{toleranceSeconds}s` suffix with a label describing the method
  (`results.matchMethod`, e.g. EN "matched by product order" / DE "Abgleich nach
  Produkt-Reihenfolge").
- If any matched pair has `priceDiffers`, append a small count
  (`results.priceDiffersN`, e.g. "3 price differences").

### Differences table (`NayaxDifferencesTable.vue`) — phantom highlighting

The merged chronological table and the bulk-**import** flow for missing rows stay.
Phantom (DB-only) rows are **promoted from the current soft "info" look to a warning
treatment**:

- swap `IconInfoCircle` + yellow for `IconAlertTriangle` + amber/red emphasis on the
  badge and (subtly) the row,
- relabel the badge from "DB only" to something that states the consequence, e.g.
  EN "DB only — likely false sale" / DE "nur in DB — vermutlich Fehlverkauf"
  (`results.bucketGhostWarn`),
- add a one-line explanation above/within the section
  (`results.ghostExplain`, e.g. DE "Diese Verkäufe stehen in der DB, aber nicht in
  Nayax — vermutlich fälschlich gezählte Doppelverkäufe.").
- **Removal stays per-row** (existing `IconTrash` + `deleteConfirmBody` confirm). No
  bulk-delete surface is added (decided: safer/more deliberate for record deletion).

**Day grouping.** The merged rows (already sorted chronologically) are visually
grouped by calendar day, with light spacing between days so the operator can tell
days apart at a glance:

- A `dayGroups` computed folds the flat `mergedRows` into
  `[{ dayKey, dayLabel, rows }]` in a single linear pass (rows are already sorted,
  so same-day rows are contiguous).
- **Day key uses the browser-local day**, derived from the same `Date` the cells
  render — i.e. local `getFullYear()/getMonth()/getDate()` of `utcDt` (missing) or
  `created_at` (ghost). This is deliberate: `formatDateTime` renders with no
  `timeZone` option (browser-local), so grouping by anything else (e.g. the file
  timezone) could place a row under a day that differs from its shown time. Grouping
  and display must share one basis.
- Each group is preceded by a **subtle divider row** spanning all columns
  (`<td :colspan>`): a muted, small date label (`formatDate(ts, locale)`), a hairline
  `border-t`, and a bit of extra top padding for the "light spacing." No heavy header,
  no background fill beyond a faint tint. The first group needs no leading gap.
- Row markup, selection (`select all missing` still spans all days by `txId`),
  import, and per-row delete are **unchanged** — only the wrapping/iteration changes.
- No new i18n string (the divider shows a formatted date). Purely presentational;
  verified via the preview workflow, no new unit test required.

Implementation notes (from spec review): the divider `<td>` uses `colspan="10"`
(the table has 10 columns); keep **all** rows under one `<tbody>` so the data rows'
`last:border-0` still resolves to the final row; add `formatDate` to the existing
`@/lib/utils` import; and remove the now-unused `IconInfoCircle` import left dangling
after the ghost-badge icon swap.

### Matched table (`NayaxMatchedTable.vue`)

- Add a small "price differs" badge (`results.priceDiffers`) on rows where
  `priceDiffers === true`.

## i18n (`i18n/locales/{en,de}.json`)

- **Remove** `nayax.reconcile.settings.tolerance` and `…settings.toleranceHint`.
- **Add** under `nayax.reconcile.results`: `matchMethod`, `priceDiffers`,
  `priceDiffersN`, `bucketGhostWarn`, `ghostExplain`, and `bucketedNotice` (the
  size-guard fallback message, shown only when a machine was day-bucketed).
- German strings use the informal *du* form, consistent with the existing file.

## Out of scope (YAGNI)

- No toggle to keep the old timestamp matcher — it's being replaced outright.
- No bulk-delete for phantoms (per-row only, decided).
- No Myers/Hirschberg implementation unless the per-machine guard ever trips in
  practice; day-bucketed LCS is the bounded fallback.
- No change to import / delete RPCs, activity logging, CSV mechanics, mapping step,
  or the parser.

## Testing (`__tests__/useNayaxReconciliation.test.ts`)

`localDtToUtc`, `parseSelectionInfo`, `parseTitleDateRange`, `parseFile`,
`derivedChannelFromPaymentSource`, and `exportDiffCsv` tests are **untouched**
(except `setupRecon`/`exportDiffCsv` no longer pass `toleranceSeconds`).

The `runMatch` describe block is **rewritten** for sequence semantics. New/updated
cases:

1. **DB is an exact in-order subset** → all matched, correct `missingInDb`, zero ghosts.
2. **Gap in the middle** → exactly that Nayax row is `missingInDb`; the rest match.
3. **Clock drift, same order** (the key regression): Nayax and DB timestamps differ
   far beyond any old tolerance, but the product order is identical → **all matched,
   zero missing, zero ghosts**. (Under the old matcher these would all be
   missing+ghost.)
4. **Phantom** → a DB sale with no Nayax counterpart, in range → `ghostInDb`.
5. **Repeated slot** (same product vended twice) → both align correctly; one missing
   when the DB only has one of them.
6. **Price differs, slot matches** → `matched` with `priceDiffers === true`.
7. **Adjacent order swap** → resolved by Pass 3: both pairs matched, zero missing,
   zero ghosts. Fixture uses two genuinely distinct `item_number`s so the LCS can't
   absorb the swap via another equal-key path. (Originally documented as the
   accepted 1 missing + 1 ghost; changed 2026-10-05, see Pass 3.) Companion cases:
   an imported row next to a drifted device row (the 2026-09-08 field case) pairs,
   and a same-slot gap + phantom beyond the 15-min window stays reported.
8. **Per-machine independence** → identical slot sequences on two different VMs do
   not cross-align.
9. **Boundary buffer** → a DB sale just outside `[fromUtc,toUtc]` but within the
   buffer aligns with its in-range Nayax twin (no false missing) and is **not** a ghost.
10. `unmapped` / `unparseable` pre-filter behavior unchanged.

## Files touched

| File | Change |
|------|--------|
| `app/composables/useNayaxReconciliation.ts` | rewrite `runMatch` (LCS); `MatchPair.priceDiffers`; drop `toleranceSeconds` from `settings` + `ReconResult`; ±2 min buffer in `loadDbSales` |
| `app/components/nayax/NayaxSettingsStep.vue` | remove tolerance field/clamp |
| `app/pages/reports/nayax-reconciliation.vue` | remove tolerance localStorage + clamp |
| `app/components/nayax/NayaxResultsView.vue` | header method label + price-diff count |
| `app/components/nayax/NayaxDifferencesTable.vue` | phantom warning styling + explanation; day-grouped rows with light spacing |
| `app/components/nayax/NayaxMatchedTable.vue` | price-differs badge |
| `i18n/locales/en.json`, `i18n/locales/de.json` | remove tolerance keys; add new result keys |
| `app/composables/__tests__/useNayaxReconciliation.test.ts` | rewrite `runMatch` tests |
