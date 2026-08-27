# MDB Item-Number-Offset pro Automat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pro Automat einen ganzzahligen Offset hinterlegen, der ab dem Zeitpunkt seiner Eintragung auf die vom Automaten gemeldete MDB-/DEX-Selection-Nummer addiert wird, damit die im Backend geführte Fachnummer der Beschriftung am Automaten entspricht.

**Architecture:** Der Offset lebt als Spalte auf `vendingMachine` und wird an genau einem Punkt angewandt: beim Ingest in der Edge Function `mqtt-webhook` — sowohl auf dem Sale- als auch auf dem DEX-Pfad. Die Firmware bleibt unangetastet und meldet weiterhin roh. Es gibt **keinen Backfill**: ein zweiter Spaltenwert `item_number_offset_since` hält den Cut-over-Zeitpunkt fest, damit jeder Leser, der über den Cut-over hinweg vergleicht (Nayax-Abgleich, DEX-Lückenrechnung), pro Zeile entscheiden kann, ob geschoben werden muss.

**Tech Stack:** PostgreSQL 15 (Supabase-Migrationen), Deno (Edge Functions), Nuxt 4 / TypeScript / Vue 3, Vitest, `@nuxtjs/i18n`.

## Global Constraints

- **Rückwärtskompatibilität ist Pflicht.** Produktivsystem mit Geräten im Feld. `item_number_offset` bekommt `not null default 0`; bei 0 ist jeder Codepfad bit-identisch zum heutigen Verhalten.
- **Migrationen sind unveränderlich.** Neue Datei mit höherem Zeitstempel, niemals eine bestehende editieren. `.githooks/pre-commit` erzwingt das.
- **Kein `supabase db reset`.** Ausschließlich `supabase migration up`.
- **Kein Backfill.** Bestandszeilen (`sales`, `machine_trays`, `stock_decrement_log`, `dex_snapshots`) bleiben unverändert. Der Offset gilt ausschließlich für ab dem Cut-over eingehende Daten.
- **Vorzeichen-Konvention:** `effektiv = roh + item_number_offset`. Automat meldet 1, Fach ist mit 10 beschriftet → Offset `9`.
- **Der Cut-over-Zeitstempel wird serverseitig gesetzt**, per Trigger, nie vom Client geschrieben.
- **i18n:** Jeder neue UI-String muss in allen vier Locales stehen: `de.json`, `en.json`, `fr.json`, `nl.json`. Deutsch im Du-Ton (siehe Nachbarschlüssel `machineSettings.*`).
- **SECURITY DEFINER Funktionen** brauchen `set search_path` (bestehende Konvention in diesem Repo: `set search_path = ''` mit voll qualifizierten Namen).

## Kontext, den der Umsetzende kennen muss

Die Item-Nummer entsteht im Automaten (VMC) und wird von der Firmware unverändert durchgereicht: `mdb-slave-esp32s3.c:769` (VEND_REQUEST) bzw. `:903` (CASH_SALE) → `sale_queue_enqueue` → `xorEncodeWithPasskey` Bytes 6/7 → `mqtt-webhook/index.ts:399` → `sales.item_number`. Der Trigger `stamp_machine_and_decrement_stock` joint danach `sales.item_number = machine_trays.item_number`; passt nichts, landet ein Eintrag in `stock_decrement_log` und der Bestand wird nicht abgezogen.

Der DEX-Pfad ist getrennt: `parseDexAudit` (`index.ts:20-55`) liest PA1-Records und legt `slot_counters` als `{ "<selection-id>": { vends, value_cents } }` ab; die rohen Bytes bleiben zusätzlich in `dex_snapshots.raw` erhalten. Die Keys sind Strings direkt aus dem DEX-Record und können nullgepolstert sein (`"01"`).

**Wichtig zur Einordnung von `dex_reconcile_gaps`:** die Funktion hat heute **keinen Aufrufer** (Grep über `*.ts`, `*.vue`, `*.swift`, `*.kt`, `*.sql` findet nur Definition und Kommentar). Der Cut-over erzeugt dort ein latentes Artefakt, keinen laufenden Alarm — der Guard in Task 1 ist billige Vorsorge, keine Brandbekämpfung.

## File Structure

| Datei | Verantwortung |
|---|---|
| `Docker/supabase/migrations/20260827000000_machine_item_number_offset.sql` | **Neu.** Zwei Spalten auf `vendingMachine`, Stamp-Trigger für den Cut-over-Zeitpunkt, Guard in `dex_reconcile_gaps`. |
| `Docker/supabase/tests/machine_item_number_offset.test.sql` | **Neu.** SQL-Tests für Trigger und Guard. |
| `Docker/supabase/functions/mqtt-webhook/slot-offset.ts` | **Neu.** Reine Logik: Offset anwenden, `slot_counters`-Keys verschieben. Folgt dem Muster von `suppress.ts` / `stock-urgency.ts`. |
| `Docker/supabase/functions/mqtt-webhook/slot-offset.test.ts` | **Neu.** Deno-Tests dazu. |
| `Docker/supabase/functions/mqtt-webhook/index.ts` | **Ändern.** Machine-Lookup vor dem Sale-Insert; Offset auf Sale- und DEX-Ingest. |
| `management-frontend/app/composables/useNayaxReconciliation.ts` | **Ändern.** Offset-Konfiguration laden, `effectiveItemNumber` an drei Stellen anwenden. |
| `management-frontend/app/composables/__tests__/useNayaxReconciliation.test.ts` | **Ändern.** Tests für `effectiveItemNumber`. |
| `management-frontend/app/composables/useMachines.ts` | **Ändern.** `item_number_offset` in `MachineSettingsPatch`, Select und Cache-Update. |
| `management-frontend/app/components/MachineSettingsModal.vue` | **Ändern.** Eingabefeld inkl. Warnhinweis. |
| `management-frontend/i18n/locales/{de,en,fr,nl}.json` | **Ändern.** Neue `machineSettings.*`-Schlüssel. |

**Nicht im Scope (bewusst):** Firmware, iOS, Android. Der Offset ist eine reine Backend-/Admin-Einstellung; die Clients arbeiten bereits im Label-Raum. Präzedenzfall ist `nayax_machine_id`, das ebenfalls nur in der PWA gepflegt wird.

---

### Task 1: Datenbank — Spalten, Stamp-Trigger, DEX-Guard

**Files:**
- Create: `Docker/supabase/migrations/20260827000000_machine_item_number_offset.sql`
- Test: `Docker/supabase/tests/machine_item_number_offset.test.sql`

**Interfaces:**
- Consumes: nichts.
- Produces: `public."vendingMachine".item_number_offset integer not null default 0`, `public."vendingMachine".item_number_offset_since timestamptz null`. Trigger `stamp_item_number_offset_since`. Geänderte Funktion `public.dex_reconcile_gaps(uuid, timestamptz, timestamptz)` mit unveränderter Signatur und unverändertem Rückgabetyp.

- [ ] **Step 1: Migration schreiben**

Datei `Docker/supabase/migrations/20260827000000_machine_item_number_offset.sql`:

```sql
-- Per-machine MDB/DEX item-number offset.
--
-- Some VMCs (observed on a Sanden Vendo outdoor unit) number their selections
-- densely from 1 while the front labels are two-digit tray.column ("10", "11",
-- ...). The firmware forwards the raw MDB number verbatim, so the backend
-- stores 1 where the customer pressed 10 and the tray join misses.
--
-- The offset is ADDED to the raw number at ingest time:  effective = raw + offset.
-- There is deliberately NO backfill: rows written before the offset was set keep
-- their raw numbers. `item_number_offset_since` records the cut-over so readers
-- that span it (Nayax reconciliation, DEX gap maths) can decide per row.

ALTER TABLE public."vendingMachine"
  ADD COLUMN IF NOT EXISTS item_number_offset integer NOT NULL DEFAULT 0;

ALTER TABLE public."vendingMachine"
  ADD COLUMN IF NOT EXISTS item_number_offset_since timestamptz;

COMMENT ON COLUMN public."vendingMachine".item_number_offset IS
  'Added to the raw MDB/DEX selection number at ingest: effective = raw + offset. 0 = machine numbers its selections the same way its labels read.';
COMMENT ON COLUMN public."vendingMachine".item_number_offset_since IS
  'Server-stamped moment the current offset took effect. NULL when the offset is 0. Data written before this timestamp is raw and must not be shifted retroactively.';

-- Sanity bound: a selection number is a uint16 on the wire, so an offset outside
-- this range can only be a typo.
ALTER TABLE public."vendingMachine"
  DROP CONSTRAINT IF EXISTS vendingmachine_item_number_offset_range;
ALTER TABLE public."vendingMachine"
  ADD CONSTRAINT vendingmachine_item_number_offset_range
  CHECK (item_number_offset BETWEEN -9999 AND 9999);

-- The cut-over timestamp is server-owned: no client may write it, and it is
-- restamped on every actual change of the offset value.
CREATE OR REPLACE FUNCTION public.stamp_item_number_offset_since()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.item_number_offset_since :=
      CASE WHEN NEW.item_number_offset <> 0 THEN now() ELSE NULL END;
    RETURN NEW;
  END IF;

  IF NEW.item_number_offset IS DISTINCT FROM OLD.item_number_offset THEN
    NEW.item_number_offset_since :=
      CASE WHEN NEW.item_number_offset <> 0 THEN now() ELSE NULL END;
  ELSE
    -- Unchanged offset: keep the original stamp regardless of what was sent.
    NEW.item_number_offset_since := OLD.item_number_offset_since;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS stamp_item_number_offset_since ON public."vendingMachine";
CREATE TRIGGER stamp_item_number_offset_since
  BEFORE INSERT OR UPDATE ON public."vendingMachine"
  FOR EACH ROW EXECUTE FUNCTION public.stamp_item_number_offset_since();

-- DEX gap maths: snapshots taken before the cut-over carry raw keys, snapshots
-- after it carry shifted keys. Comparing across the boundary would subtract a
-- missing key from a lifetime counter and report the whole counter as a gap.
-- Clamp the window to the cut-over when an offset is active.
CREATE OR REPLACE FUNCTION public.dex_reconcile_gaps(
  p_embedded_id uuid,
  p_window_start timestamptz,
  p_window_end timestamptz
)
RETURNS TABLE (
  item_number integer,
  dex_delta   bigint,
  sales_count bigint,
  gap         bigint
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  with cutover as (
    select max(vm.item_number_offset_since) as since
    from public."vendingMachine" vm
    where vm.embedded = p_embedded_id
      and vm.item_number_offset <> 0
  ),
  bounds as (
    select
      greatest(p_window_start, coalesce((select since from cutover), p_window_start)) as w_start,
      p_window_end as w_end
  ),
  start_snap as (
    select d.slot_counters
    from public.dex_snapshots d, bounds b
    where d.embedded_id = p_embedded_id
      and d.captured_at <= b.w_start
      and (
        (select since from cutover) is null
        or d.captured_at >= (select since from cutover)
      )
    order by d.captured_at desc
    limit 1
  ),
  end_snap as (
    select d.slot_counters
    from public.dex_snapshots d, bounds b
    where d.embedded_id = p_embedded_id
      and d.captured_at <= b.w_end
      and (
        (select since from cutover) is null
        or d.captured_at >= (select since from cutover)
      )
    order by d.captured_at desc
    limit 1
  ),
  slot_deltas as (
    select
      (key)::integer as item_number,
      coalesce(((end_snap.slot_counters -> key) ->> 'vends')::bigint, 0)
        - coalesce(((start_snap.slot_counters -> key) ->> 'vends')::bigint, 0) as dex_delta
    from end_snap
    cross join start_snap
    cross join jsonb_object_keys(end_snap.slot_counters) as key
  ),
  sales_counts as (
    select s.item_number, count(*)::bigint as sales_count
    from public.sales s, bounds b
    where s.embedded_id = p_embedded_id
      and s.created_at >= b.w_start
      and s.created_at <  b.w_end
    group by s.item_number
  )
  select
    d.item_number,
    d.dex_delta,
    coalesce(c.sales_count, 0) as sales_count,
    d.dex_delta - coalesce(c.sales_count, 0) as gap
  from slot_deltas d
  left join sales_counts c on c.item_number = d.item_number
  where d.dex_delta - coalesce(c.sales_count, 0) > 0
  order by gap desc;
$$;

COMMENT ON FUNCTION public.dex_reconcile_gaps(uuid, timestamptz, timestamptz) IS
  'Slots whose DEX vend counters grew by more than the recorded sales in the window. When the machine has an item_number_offset, the window is clamped to the cut-over so raw and shifted snapshot keys are never compared.';
```

**Achtung beim Übernehmen:** Der `slot_deltas`/`sales_counts`-Block ist gegenüber `20260419000100_offline_sales_protection.sql:126-152` inhaltlich unverändert bis auf die `bounds`-Klammerung — nicht "verbessern", nur übernehmen.

- [ ] **Step 2: Migration anwenden**

```bash
cd Docker/supabase && supabase migration up
```

Erwartet: `Applying migration 20260827000000_machine_item_number_offset.sql...` ohne Fehler. **Niemals `supabase db reset`.**

- [ ] **Step 3: SQL-Test schreiben**

Datei `Docker/supabase/tests/machine_item_number_offset.test.sql`:

```sql
-- Per-machine item_number_offset: server-stamped cut-over + DEX guard.
-- Rolled back. Plain ASSERTs. See plan 2026-08-27-mdb-item-number-offset.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_admin   uuid := gen_random_uuid();
  v_dev     uuid := gen_random_uuid();
  v_machine uuid;
  v_since   timestamptz;
  v_since2  timestamptz;
  n         int;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Offset Co');
  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_admin, '00000000-0000-0000-0000-000000000000', 'offset@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'offset@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('M-Offset', v_company, v_dev) RETURNING id INTO v_machine;

  -- 1. Default is a no-op offset with no cut-over stamp.
  SELECT item_number_offset, item_number_offset_since
    INTO n, v_since FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT n = 0, 'default offset must be 0, got ' || n;
  ASSERT v_since IS NULL, 'default cut-over must be NULL';

  -- 2. Setting a non-zero offset stamps the cut-over server-side, ignoring
  --    whatever the client sent for the timestamp.
  UPDATE public."vendingMachine"
     SET item_number_offset = 9,
         item_number_offset_since = '1999-01-01T00:00:00Z'
   WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since IS NOT NULL, 'cut-over must be stamped when offset becomes non-zero';
  ASSERT v_since > now() - interval '1 minute',
    'cut-over must be server now(), got ' || v_since;

  -- 3. An unrelated update must NOT move the cut-over.
  UPDATE public."vendingMachine" SET name = 'M-Offset renamed' WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since2
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since2 = v_since, 'unrelated update must not restamp the cut-over';

  -- 4. Clearing the offset clears the cut-over.
  UPDATE public."vendingMachine" SET item_number_offset = 0 WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since IS NULL, 'clearing the offset must clear the cut-over';

  -- 5. Out-of-range offsets are rejected.
  BEGIN
    UPDATE public."vendingMachine" SET item_number_offset = 100000 WHERE id = v_machine;
    ASSERT false, 'offset 100000 should have violated the range check';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;

  RAISE NOTICE 'machine_item_number_offset: trigger assertions passed';
END $$;

-- 6. DEX guard: snapshots from before the cut-over must not be compared with
--    snapshots after it.
DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_admin   uuid := gen_random_uuid();
  v_dev     uuid := gen_random_uuid();
  v_machine uuid;
  v_since   timestamptz;
  v_rows    int;
  v_gap     bigint;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Dex Co');
  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_admin, '00000000-0000-0000-0000-000000000000', 'dex@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'dex@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded, item_number_offset)
    VALUES ('M-Dex', v_company, v_dev, 9) RETURNING id INTO v_machine;

  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;

  -- Pre-cut-over snapshot with raw keys and a large lifetime counter.
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"1": {"vends": 5000, "value_cents": 500000}}'::jsonb,
            5000, 500000, v_since - interval '2 hours');
  -- Two post-cut-over snapshots with shifted keys, three vends apart.
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"10": {"vends": 5000, "value_cents": 500000}}'::jsonb,
            5000, 500000, v_since + interval '1 minute');
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"10": {"vends": 5003, "value_cents": 500300}}'::jsonb,
            5003, 500300, v_since + interval '2 hours');

  -- Window spans the cut-over. Without the guard the start snapshot would be
  -- the raw one, key "10" would be missing from it, and the gap would be 5003.
  SELECT count(*), coalesce(max(gap), 0) INTO v_rows, v_gap
  FROM public.dex_reconcile_gaps(v_dev, v_since - interval '3 hours', v_since + interval '3 hours');

  ASSERT v_gap = 3,
    'gap across the cut-over must be the real delta 3, got ' || v_gap;

  RAISE NOTICE 'machine_item_number_offset: dex guard assertions passed';
END $$;

ROLLBACK;
```

- [ ] **Step 4: SQL-Tests laufen lassen**

```bash
cd Docker/supabase/tests && ./run-sql-tests.sh machine_item_number_offset
```

Erwartet: beide `NOTICE`-Zeilen, kein `ASSERT`-Fehler. Falls `run-sql-tests.sh` kein Argument-Filtering kennt, ohne Argument laufen lassen und die neue Datei in der Ausgabe suchen.

- [ ] **Step 5: Commit**

```bash
git add Docker/supabase/migrations/20260827000000_machine_item_number_offset.sql Docker/supabase/tests/machine_item_number_offset.test.sql
git commit -m "feat(db): per-machine MDB item-number offset with server-stamped cut-over"
```

---

### Task 2: Reine Offset-Logik für den Webhook

**Files:**
- Create: `Docker/supabase/functions/mqtt-webhook/slot-offset.ts`
- Test: `Docker/supabase/functions/mqtt-webhook/slot-offset.test.ts`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `export function applyItemOffset(raw: number, offset: number): number`
  - `export function shiftSlotCounters(counters: Record<string, SlotCounter>, offset: number): Record<string, SlotCounter>`
  - `export interface SlotCounter { vends: number; value_cents: number }`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Datei `Docker/supabase/functions/mqtt-webhook/slot-offset.test.ts`:

```ts
import { assertEquals } from 'jsr:@std/assert';
import { applyItemOffset, shiftSlotCounters } from './slot-offset.ts';

Deno.test('applyItemOffset: offset 0 is a no-op', () => {
  assertEquals(applyItemOffset(1, 0), 1);
  assertEquals(applyItemOffset(0, 0), 0);
});

Deno.test('applyItemOffset: shifts by the configured amount', () => {
  assertEquals(applyItemOffset(1, 9), 10);
  assertEquals(applyItemOffset(11, 9), 20);
  assertEquals(applyItemOffset(10, -9), 1);
});

Deno.test('applyItemOffset: never produces a negative item number', () => {
  // A misconfigured offset must not turn a real selection into a negative key
  // that no tray can ever match; clamp at 0 and let the tray join miss loudly.
  assertEquals(applyItemOffset(1, -9), 0);
});

Deno.test('applyItemOffset: leaves the MDB "unknown item" sentinel alone', () => {
  assertEquals(applyItemOffset(0xffff, 9), 0xffff);
});

Deno.test('shiftSlotCounters: offset 0 returns an equal map', () => {
  const input = { '1': { vends: 5, value_cents: 250 } };
  assertEquals(shiftSlotCounters(input, 0), input);
});

Deno.test('shiftSlotCounters: shifts numeric keys and normalises padding', () => {
  const input = {
    '01': { vends: 5, value_cents: 250 },
    '2': { vends: 7, value_cents: 350 },
  };
  assertEquals(shiftSlotCounters(input, 9), {
    '10': { vends: 5, value_cents: 250 },
    '11': { vends: 7, value_cents: 350 },
  });
});

Deno.test('shiftSlotCounters: keeps non-numeric keys verbatim', () => {
  const input = { 'ZZ': { vends: 1, value_cents: 100 } };
  assertEquals(shiftSlotCounters(input, 9), { 'ZZ': { vends: 1, value_cents: 100 } });
});

Deno.test('shiftSlotCounters: colliding keys keep the higher counter', () => {
  // Defensive: two raw keys can only collide if the DEX dump was malformed.
  // Losing the smaller counter is safer than silently halving the larger one.
  const input = {
    '1': { vends: 5, value_cents: 250 },
    '01': { vends: 9, value_cents: 450 },
  };
  assertEquals(shiftSlotCounters(input, 9), { '10': { vends: 9, value_cents: 450 } });
});
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno test --allow-all slot-offset.test.ts
```

Erwartet: FAIL, `Module not found ... slot-offset.ts`.

- [ ] **Step 3: Minimale Implementierung schreiben**

Datei `Docker/supabase/functions/mqtt-webhook/slot-offset.ts`:

```ts
/**
 * Per-machine MDB/DEX selection-number offset.
 *
 * Some VMCs number their selections densely from 1 while the front labels read
 * two-digit tray.column. The offset is added at ingest so the stored number
 * matches what the customer pressed and what the tray table holds.
 *
 * Pure functions only — no DB, no I/O — so they stay unit-testable.
 */

export interface SlotCounter {
  vends: number;
  value_cents: number;
}

/** MDB uses 0xFFFF for "item number unknown / not applicable". */
const ITEM_NUMBER_UNKNOWN = 0xffff;

/**
 * effective = raw + offset.
 *
 * `0xFFFF` passes through untouched — it is a sentinel, not a selection.
 * The result is clamped at 0: a misconfigured offset should make the tray join
 * miss visibly, not write negative keys that corrupt later comparisons.
 */
export function applyItemOffset(raw: number, offset: number): number {
  if (offset === 0) return raw;
  if (raw === ITEM_NUMBER_UNKNOWN) return raw;
  return Math.max(0, raw + offset);
}

/**
 * Shift the keys of a parsed DEX `slot_counters` map by the same offset.
 *
 * DEX PA1 selection ids arrive as strings and may be zero-padded ("01"), so the
 * keys are normalised to their decimal form on the way through. Non-numeric
 * keys are passed through untouched. On a key collision the higher `vends`
 * counter wins — dropping the smaller one is safer than merging two lifetime
 * counters into a number that never existed.
 */
export function shiftSlotCounters(
  counters: Record<string, SlotCounter>,
  offset: number,
): Record<string, SlotCounter> {
  if (offset === 0) return counters;

  const out: Record<string, SlotCounter> = {};
  for (const [key, value] of Object.entries(counters)) {
    if (!/^\d+$/.test(key)) {
      out[key] = value;
      continue;
    }
    const shifted = String(applyItemOffset(parseInt(key, 10), offset));
    const existing = out[shifted];
    if (existing && existing.vends >= value.vends) continue;
    out[shifted] = value;
  }
  return out;
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno test --allow-all slot-offset.test.ts
```

Erwartet: `ok | 8 passed | 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add Docker/supabase/functions/mqtt-webhook/slot-offset.ts Docker/supabase/functions/mqtt-webhook/slot-offset.test.ts
git commit -m "feat(webhook): pure item-number offset helpers"
```

---

### Task 3: Offset auf dem Sale-Ingest anwenden

**Files:**
- Modify: `Docker/supabase/functions/mqtt-webhook/index.ts` (Import-Block; Sale-Zweig ab `:392`; Machine-Lookup bei `:523`)

**Interfaces:**
- Consumes: `applyItemOffset` aus Task 2; Spalte `item_number_offset` aus Task 1.
- Produces: `sales.item_number` enthält ab Cut-over die verschobene Nummer. Die lokale Variable `machine` ist ab jetzt **vor** dem Insert aufgelöst und trägt zusätzlich `item_number_offset`.

**Kontext:** Heute wird der Automat erst **nach** dem Sale-Insert nachgeschlagen, innerhalb des Push-Notification-`try`-Blocks (`index.ts:523`). Für den Offset muss der Lookup vor den Insert. Der Lookup wandert komplett nach oben; der Push-Block benutzt danach dieselbe Variable weiter. Das ist eine zusätzliche Query auf dem heißen Pfad — akzeptiert, weil `vendingMachine.embedded` bereits für den Push-Pfad abgefragt wurde und die Query dadurch netto nicht häufiger stattfindet.

- [ ] **Step 1: Import ergänzen**

Am Kopf von `index.ts`, direkt neben den bestehenden lokalen Imports (dort, wo `suppress.ts` / `stock-urgency.ts` importiert werden):

```ts
import { applyItemOffset, shiftSlotCounters } from './slot-offset.ts';
```

- [ ] **Step 2: Machine-Lookup vor den Sale-Insert ziehen**

In `index.ts` im `if (eventType === 'sale')`-Zweig, unmittelbar **nach** der Zeile

```ts
      const itemNumber = ((payload[6] << 8) | payload[7]) & 0xFFFF;
```

einfügen:

```ts
      // The machine is resolved here rather than in the push block below because
      // the per-machine item-number offset has to be applied before the insert.
      // Same query the push path used to make on its own — not an extra round
      // trip, just an earlier one.
      const { data: machine } = await adminClient
        .from('vendingMachine')
        .select('id, name, item_number_offset')
        .eq('embedded', embedded.id)
        .maybeSingle();

      // effective = raw + offset. No backfill: rows written before the operator
      // set the offset keep their raw numbers on purpose.
      const effectiveItemNumber = applyItemOffset(
        itemNumber,
        machine?.item_number_offset ?? 0,
      );
```

- [ ] **Step 3: Alle Verwendungen von `itemNumber` im Sale-Zweig auf `effectiveItemNumber` umstellen**

Vier Stellen, alle im `sale`-Zweig:

1. Duplikat-Kandidatensuche (`.eq('item_number', itemNumber)`, heute `index.ts:437`) → `.eq('item_number', effectiveItemNumber)`
2. Sale-Insert (`item_number: itemNumber`, heute `index.ts:500`) → `item_number: effectiveItemNumber`
3. Tray-Lookup (`.eq('item_number', itemNumber)`, heute `index.ts:538`) → `.eq('item_number', effectiveItemNumber)`
4. Aktivitäts-Log-Insert (`item_number: itemNumber`, heute `index.ts:629`) → `item_number: effectiveItemNumber`

Die beiden Push-Labels (`Item #${itemNumber}`, heute `:571` und `:602`) ebenfalls auf `effectiveItemNumber` umstellen — der Nachfüller soll die Zahl sehen, die am Fach klebt.

- [ ] **Step 4: Den doppelten Machine-Lookup im Push-Block entfernen**

Im Push-`try`-Block (heute `index.ts:523-527`) den Block

```ts
        const { data: machine } = await adminClient
          .from('vendingMachine')
          .select('id, name')
          .eq('embedded', embedded.id)
          .maybeSingle();
```

**löschen**. Die äußere `machine`-Variable aus Step 2 ist im selben Scope sichtbar; der Rest des Blocks (`if (machine) { ... }`, `machine?.name`) bleibt unverändert.

- [ ] **Step 5: Typecheck**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno check index.ts
```

Erwartet: keine Ausgabe (Erfolg). Insbesondere darf es **keinen** `Block-scoped variable 'machine' ... already declared`-Fehler geben — falls doch, ist Step 4 nicht ausgeführt worden.

- [ ] **Step 6: Bestehende Webhook-Tests laufen lassen**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno test --allow-all
```

Erwartet: alle bestehenden Suiten (`mdb-log`, `stock-urgency`, `suppress`, `slot-offset`) grün.

- [ ] **Step 7: Commit**

```bash
git add Docker/supabase/functions/mqtt-webhook/index.ts
git commit -m "feat(webhook): apply per-machine item-number offset on sale ingest"
```

---

### Task 4: Offset auf dem DEX-Ingest anwenden

**Files:**
- Modify: `Docker/supabase/functions/mqtt-webhook/index.ts` (DEX-Zweig, heute `:341-355`)

**Interfaces:**
- Consumes: `shiftSlotCounters` aus Task 2; Spalte `item_number_offset` aus Task 1.
- Produces: `dex_snapshots.slot_counters` trägt ab Cut-over verschobene Keys. `dex_snapshots.raw` bleibt unangetastet.

- [ ] **Step 1: DEX-Zweig umbauen**

Den Block im `if (eventType === 'dex')`-Zweig ersetzen durch:

```ts
    if (eventType === 'dex') {
      const dexBytes = decodeBase64(payloadB64);
      const parsed = parseDexAudit(dexBytes);

      // The parsed slot keys are shifted by the same per-machine offset the sale
      // path uses, so DEX counters and `sales.item_number` stay in one number
      // space. `raw` below is deliberately NOT touched: it is the machine's own
      // audit record and must stay verbatim.
      const { data: dexMachine } = await adminClient
        .from('vendingMachine')
        .select('item_number_offset')
        .eq('embedded', embedded.id)
        .maybeSingle();

      const slotCounters = shiftSlotCounters(
        parsed.slot_counters,
        dexMachine?.item_number_offset ?? 0,
      );

      const { error: insertErr } = await adminClient
        .from('dex_snapshots')
        .insert({
          embedded_id: embedded.id,
          raw: `\\x${Array.from(dexBytes).map((b) => b.toString(16).padStart(2, '0')).join('')}`,
          slot_counters: slotCounters,
          total_vends: parsed.total_vends,
          total_value: parsed.total_value,
        });

      if (insertErr) throw insertErr;
      return new Response(JSON.stringify({ ok: true, slots: Object.keys(slotCounters).length }), { status: 200 });
    }
```

- [ ] **Step 2: Typecheck**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno check index.ts
```

Erwartet: keine Ausgabe.

- [ ] **Step 3: Tests laufen lassen**

```bash
cd Docker/supabase/functions/mqtt-webhook && deno test --allow-all
```

Erwartet: alles grün.

- [ ] **Step 4: Commit**

```bash
git add Docker/supabase/functions/mqtt-webhook/index.ts
git commit -m "feat(webhook): shift DEX slot counters by the machine offset, leave raw audit intact"
```

---

### Task 5: Nayax-Abgleich über den Cut-over hinweg korrigieren

**Files:**
- Modify: `management-frontend/app/composables/useNayaxReconciliation.ts`
- Test: `management-frontend/app/composables/__tests__/useNayaxReconciliation.test.ts`

**Interfaces:**
- Consumes: Spalten `item_number_offset`, `item_number_offset_since` aus Task 1.
- Produces:
  - `export interface MachineOffset { offset: number; since: string | null }`
  - `export function effectiveNayaxItemNumber(rawItemNumber: number, rowUtcDt: string, cfg: MachineOffset | undefined): number`
  - `offsets: Ref<Record<string, MachineOffset>>` (Key: `vendingMachine.id`), befüllt von `loadMapping()`.

**Kontext:** Der Nayax-Leser hängt am selben MDB-Bus wie unser Gerät und meldet daher weiterhin **rohe** Selection-Nummern. Der Matcher aligniert Nayax-Zeilen gegen DB-Zeilen über die Item-Nummer als Sequenzschlüssel (`useNayaxReconciliation.ts:572`, `:586`). Ohne Korrektur wären ab Cut-over für den betroffenen Automaten sämtliche Zeilen gleichzeitig "fehlt in DB" und "Phantom in DB". Weil es keinen Backfill gibt, darf die Korrektur **nur auf Zeilen ab dem Cut-over** wirken.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Ans Ende von `management-frontend/app/composables/__tests__/useNayaxReconciliation.test.ts` anhängen. Vorher die bestehende Import-Zeile

```ts
import { localDtToUtc, parseSelectionInfo, parseTitleDateRange } from '../useNayaxReconciliation'
```

erweitern zu:

```ts
import { localDtToUtc, parseSelectionInfo, parseTitleDateRange, effectiveNayaxItemNumber } from '../useNayaxReconciliation'
```

(`MachineOffset` wird nicht importiert — die Testfälle übergeben Objektliterale.) Dann anhängen:

```ts
describe('effectiveNayaxItemNumber', () => {
  const cfg = { offset: 9, since: '2026-08-27T10:00:00.000Z' }

  it('returns the raw number when no offset is configured', () => {
    expect(effectiveNayaxItemNumber(1, '2026-08-27T12:00:00.000Z', undefined)).toBe(1)
    expect(effectiveNayaxItemNumber(1, '2026-08-27T12:00:00.000Z', { offset: 0, since: null })).toBe(1)
  })

  it('shifts rows at or after the cut-over', () => {
    expect(effectiveNayaxItemNumber(1, '2026-08-27T10:00:00.000Z', cfg)).toBe(10)
    expect(effectiveNayaxItemNumber(1, '2026-08-27T12:00:00.000Z', cfg)).toBe(10)
    expect(effectiveNayaxItemNumber(11, '2026-08-27T12:00:00.000Z', cfg)).toBe(20)
  })

  it('leaves rows from before the cut-over raw — there was no backfill', () => {
    expect(effectiveNayaxItemNumber(1, '2026-08-26T23:59:59.000Z', cfg)).toBe(1)
  })

  it('treats a missing cut-over stamp as "never shift"', () => {
    // Defensive: offset set but stamp somehow absent. Shifting everything would
    // silently rewrite history; refusing to shift only degrades to today.
    expect(effectiveNayaxItemNumber(1, '2026-08-27T12:00:00.000Z', { offset: 9, since: null })).toBe(1)
  })

  it('never returns a negative item number', () => {
    expect(effectiveNayaxItemNumber(1, '2026-08-27T12:00:00.000Z', { offset: -9, since: cfg.since })).toBe(0)
  })
})
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
cd management-frontend && npx vitest run app/composables/__tests__/useNayaxReconciliation.test.ts
```

Erwartet: FAIL, `effectiveNayaxItemNumber is not a function` bzw. Import-Fehler.

- [ ] **Step 3: Die reine Funktion implementieren**

In `useNayaxReconciliation.ts` neben die anderen exportierten Helfer (direkt unter `parseSelectionInfo`, ca. `:47`):

```ts
export interface MachineOffset {
  offset: number
  /** ISO timestamp the offset took effect; null means it never did. */
  since: string | null
}

/**
 * Nayax reads the same MDB bus as our device, so its export carries the RAW
 * selection number. Since the offset was introduced as a cut-over with no
 * backfill, DB rows are raw before `since` and shifted from `since` onwards —
 * so the Nayax row has to be shifted by the same rule to align against them.
 */
export function effectiveNayaxItemNumber(
  rawItemNumber: number,
  rowUtcDt: string,
  cfg: MachineOffset | undefined,
): number {
  if (!cfg || cfg.offset === 0 || cfg.since === null) return rawItemNumber
  if (Date.parse(rowUtcDt) < Date.parse(cfg.since)) return rawItemNumber
  return Math.max(0, rawItemNumber + cfg.offset)
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

```bash
cd management-frontend && npx vitest run app/composables/__tests__/useNayaxReconciliation.test.ts
```

Erwartet: alle Tests grün, inklusive der bestehenden.

- [ ] **Step 5: Offset-Konfiguration beim Mapping mitladen**

In `useNayaxReconciliation.ts` neben `mapping` einen zweiten geteilten State anlegen:

```ts
  const offsets = useState<Record<string, MachineOffset>>('nayax-offsets', () => ({}))
```

In `loadMapping()` (ca. `:419`) das Select erweitern und die Map füllen:

```ts
    const { data } = await supabase
      .from('vendingMachine')
      .select('id, nayax_machine_id, item_number_offset, item_number_offset_since')
      .not('nayax_machine_id', 'is', null)

    const m: Record<string, string> = {}
    const o: Record<string, MachineOffset> = {}
    for (const row of (data ?? []) as {
      id: string
      nayax_machine_id: string
      item_number_offset: number | null
      item_number_offset_since: string | null
    }[]) {
      m[row.nayax_machine_id] = row.id
      o[row.id] = {
        offset: row.item_number_offset ?? 0,
        since: row.item_number_offset_since ?? null,
      }
    }
    mapping.value = m
    offsets.value = o
```

**Achtung:** die bestehenden Zeilen `m[row.nayax_machine_id] = row.id` und die Zuweisung an `mapping.value` nicht doppelt stehen lassen — der obige Block ersetzt sie.

- [ ] **Step 6: Die Funktion an den drei Matcher-Stellen anwenden**

In `runMatch()`, innerhalb der `for (const vmId of vmIds)`-Schleife, direkt nach der Zeile `const bAll = ...`:

```ts
        const vmOffset = offsets.value[vmId]
```

Dann die drei Verwendungen der rohen Nayax-Nummer ersetzen:

1. `:572` — `const aKeys = aRows.map(r => r.itemNumber as number)`
   → `const aKeys = aRows.map(r => effectiveNayaxItemNumber(r.itemNumber as number, r.utcDt, vmOffset))`
2. `:586` — `const raKeys = residualA.map(r => r.itemNumber as number)`
   → `const raKeys = residualA.map(r => effectiveNayaxItemNumber(r.itemNumber as number, r.utcDt, vmOffset))`
3. Der Import fehlender Verkäufe (`:664-671`): `p_item_number: n.itemNumber` →

```ts
          p_item_number: effectiveNayaxItemNumber(
            n.itemNumber,
            n.utcDt,
            offsets.value[vmId],
          ),
```

Beim Import muss `vmId` im Scope sein — dort steht bereits `const vmId = mapping.value[n.nayaxMachineId!]` bzw. eine gleichwertige Auflösung (`:664`); diese wiederverwenden, nicht neu berechnen.

Die CSV-Diff-Ausgabe (`:776`, `:789`) bleibt bewusst auf `n.itemNumber` — der Export soll zeigen, was Nayax gemeldet hat.

- [ ] **Step 7: Vollen Frontend-Testlauf**

```bash
cd management-frontend && npx vitest run
```

Erwartet: alle Suiten grün.

- [ ] **Step 8: Commit**

```bash
git add management-frontend/app/composables/useNayaxReconciliation.ts management-frontend/app/composables/__tests__/useNayaxReconciliation.test.ts
git commit -m "feat(nayax): shift raw Nayax selection numbers past the offset cut-over"
```

---

### Task 6: UI zum Setzen des Offsets

**Files:**
- Modify: `management-frontend/app/composables/useMachines.ts` (`MachineSettingsPatch` `:68-78`, Select `:100-106`, `updateMachineSettings` Cache-Update)
- Modify: `management-frontend/app/components/MachineSettingsModal.vue`
- Modify: `management-frontend/app/pages/machines/[id]/index.vue:200` (Select erweitern)
- Modify: `management-frontend/i18n/locales/{de,en,fr,nl}.json`
- Test: `management-frontend/app/composables/__tests__/useMachines.test.ts`

**Interfaces:**
- Consumes: Spalte `item_number_offset` aus Task 1.
- Produces: `MachineSettingsPatch` enthält zusätzlich `item_number_offset: number`. `VendingMachine` trägt `item_number_offset: number`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `management-frontend/app/composables/__tests__/useMachines.test.ts` beim bestehenden `updateMachineSettings`-Test ergänzen (bzw. neuen Fall anhängen):

```ts
  it('writes item_number_offset through and updates the local cache', async () => {
    const { machines, updateMachineSettings } = useMachines()
    machines.value = [{ id: 'm1', item_number_offset: 0 } as any]

    await updateMachineSettings('m1', {
      location_lat: null,
      location_lon: null,
      address_street: null,
      address_house_number: null,
      address_postal_code: null,
      address_city: null,
      formatted_address: null,
      country_code: null,
      nayax_machine_id: null,
      item_number_offset: 9,
    })

    expect(machines.value[0]!.item_number_offset).toBe(9)
  })
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
cd management-frontend && npx vitest run app/composables/__tests__/useMachines.test.ts
```

Erwartet: FAIL — Typfehler auf `item_number_offset` bzw. `expected undefined to be 9`.

- [ ] **Step 3: `useMachines.ts` erweitern**

Drei Änderungen:

1. Im `VendingMachine`-Interface (bei `nayax_machine_id: string | null`, `:32`) ergänzen:

```ts
  item_number_offset: number
```

2. In `MachineSettingsPatch` (`:68-78`) ergänzen:

```ts
  item_number_offset: number
```

3. Im Select von `fetchMachines()` (`:103`) `nayax_machine_id,` erweitern zu:

```ts
          nayax_machine_id, item_number_offset,
```

4. In `updateMachineSettings()` beim Cache-Update neben `machine.nayax_machine_id = patch.nayax_machine_id` ergänzen:

```ts
      machine.item_number_offset = patch.item_number_offset
```

`CreateMachineLocation` bleibt unverändert — der Offset wird nicht bei der Anlage gesetzt, genau wie `nayax_machine_id`. Da `Omit` dort `item_number_offset` **nicht** ausschließt, muss der Schlüssel dem `Omit` hinzugefügt werden:

```ts
export type CreateMachineLocation = Omit<MachineSettingsPatch, 'location_lat' | 'location_lon' | 'nayax_machine_id' | 'item_number_offset'> & {
  location_lat: number
  location_lon: number
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

```bash
cd management-frontend && npx vitest run app/composables/__tests__/useMachines.test.ts
```

Erwartet: grün.

- [ ] **Step 5: i18n-Schlüssel in allen vier Locales ergänzen**

Jeweils im `machineSettings`-Block, direkt nach `nayaxMachineIdHint` (Zeile 703 in allen vier Dateien):

`de.json`:
```json
    "itemNumberOffset": "Fachnummer-Offset",
    "itemNumberOffsetPlaceholder": "0",
    "itemNumberOffsetHint": "Wird auf die vom Automaten gemeldete Fachnummer addiert. Meldet der Automat 1, wo das Fach mit 10 beschriftet ist, trag 9 ein. Gilt erst ab dem Speichern — bereits erfasste Verkäufe bleiben unverändert.",
    "itemNumberOffsetWarning": "Prüfe vorher, dass deine Fächer hier mit den Nummern vom Aufkleber angelegt sind (10, 11, …) und nicht mit den rohen Nummern des Automaten."
```

`en.json`:
```json
    "itemNumberOffset": "Slot number offset",
    "itemNumberOffsetPlaceholder": "0",
    "itemNumberOffsetHint": "Added to the slot number the machine reports. If the machine reports 1 where the slot is labelled 10, enter 9. Takes effect on save — sales already recorded are left untouched.",
    "itemNumberOffsetWarning": "Check first that your trays here use the numbers from the label (10, 11, …) and not the machine's raw numbers."
```

`fr.json`:
```json
    "itemNumberOffset": "Décalage du numéro de case",
    "itemNumberOffsetPlaceholder": "0",
    "itemNumberOffsetHint": "Ajouté au numéro de case signalé par la machine. Si la machine signale 1 là où la case porte l'étiquette 10, saisissez 9. Prend effet à l'enregistrement — les ventes déjà enregistrées ne sont pas modifiées.",
    "itemNumberOffsetWarning": "Vérifiez d'abord que vos cases sont créées ici avec les numéros de l'étiquette (10, 11, …) et non avec les numéros bruts de la machine."
```

`nl.json`:
```json
    "itemNumberOffset": "Vaknummer-offset",
    "itemNumberOffsetPlaceholder": "0",
    "itemNumberOffsetHint": "Wordt opgeteld bij het vaknummer dat de automaat meldt. Meldt de automaat 1 waar het vak met 10 is gelabeld, vul dan 9 in. Geldt vanaf het opslaan — al geregistreerde verkopen blijven ongewijzigd.",
    "itemNumberOffsetWarning": "Controleer eerst of je vakken hier zijn aangemaakt met de nummers van de sticker (10, 11, …) en niet met de ruwe nummers van de automaat."
```

- [ ] **Step 6: Feld in `MachineSettingsModal.vue` ergänzen**

1. Prop-Typ (`:21`) erweitern:

```ts
  initial: Partial<LocationModel & { nayax_machine_id: string | null; item_number_offset: number }>
```

2. Formulartyp (`:34`):

```ts
type MachineSettingsForm = LocationModel & { nayax_machine_id: string | null; item_number_offset: number }
```

3. In `cloneInitial()` (bei `nayax_machine_id: props.initial.nayax_machine_id ?? null`, `:143`) ergänzen:

```ts
    item_number_offset: props.initial.item_number_offset ?? 0,
```

4. In `save()` vor dem `updateMachineSettings`-Aufruf normalisieren — ein leeres Feld ist 0, nicht `NaN`:

```ts
    const parsedOffset = Number(form.value.item_number_offset)
    form.value.item_number_offset = Number.isFinite(parsedOffset) ? Math.trunc(parsedOffset) : 0
```

5. Im Template direkt nach dem Nayax-Block (`:296-307`) einfügen:

```vue
        <!-- MDB slot number offset -->
        <div class="space-y-1">
          <label class="text-xs font-medium text-muted-foreground">{{ t('machineSettings.itemNumberOffset') }}</label>
          <input
            v-model.number="form.item_number_offset"
            type="number"
            step="1"
            inputmode="numeric"
            :placeholder="t('machineSettings.itemNumberOffsetPlaceholder')"
            class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
          <p class="mt-1 text-[10px] text-muted-foreground">{{ t('machineSettings.itemNumberOffsetHint') }}</p>
          <p v-if="form.item_number_offset !== 0" class="mt-1 rounded bg-amber-500/10 px-2 py-1 text-[10px] text-amber-700 dark:text-amber-400">
            {{ t('machineSettings.itemNumberOffsetWarning') }}
          </p>
        </div>
```

- [ ] **Step 7: Detailseite den Wert mitladen und durchreichen**

Zwei Stellen in `management-frontend/app/pages/machines/[id]/index.vue`:

1. Zeile `200`, im Select von `fetchMachine()`: `nayax_machine_id,` erweitern zu `nayax_machine_id, item_number_offset,`

2. Zeile `3097`, in der `:initial`-Bindung an `MachineSettingsModal`, direkt nach der `nayax_machine_id`-Zeile einfügen:

```vue
          item_number_offset: (machine as any).item_number_offset ?? 0,
```

Der Cast auf `any` folgt hier dem bestehenden Muster der Nachbarzeilen — das Repo hat keine generierten DB-Typen (`~/types/database.types.ts` fehlt bewusst).

- [ ] **Step 8: Typecheck und volle Testsuite**

```bash
cd management-frontend && npx nuxi typecheck && npx vitest run
```

Erwartet: beides ohne Fehler. Sollte `nuxi typecheck` im Projekt nicht eingerichtet sein, entfällt der erste Teil.

- [ ] **Step 9: Commit**

```bash
git add management-frontend/app/composables/useMachines.ts management-frontend/app/composables/__tests__/useMachines.test.ts management-frontend/app/components/MachineSettingsModal.vue "management-frontend/app/pages/machines/[id]/index.vue" management-frontend/i18n/locales/de.json management-frontend/i18n/locales/en.json management-frontend/i18n/locales/fr.json management-frontend/i18n/locales/nl.json
git commit -m "feat(machines): slot number offset in machine settings"
```

---

## Manuelle Abnahme nach Task 6

Diese Schritte sind nicht automatisierbar und müssen mit echten Daten laufen:

1. **Vorbedingung prüfen.** Für den betroffenen Automaten kontrollieren, ob `machine_trays.item_number` im Label-Raum liegt (10, 11, …). Falls jemand die Trays als Workaround auf 1 ff. umgenummert hat, müssen sie **vor** dem Setzen des Offsets zurück auf Label-Raum — sonst schiebt der Offset den Match ins Leere.
2. **Linearität verifizieren.** `select slot_counters from dex_snapshots where embedded_id = '<dev>' order by captured_at desc limit 1;` — sind die Keys dicht ab 1 (`"1"`, `"2"`, …), passt ein konstanter Offset. Sind Reihen unterschiedlich breit belegt, passt er nur für die erste Reihe; dann ist der konstante Offset die falsche Lösung und es braucht ein Label pro Tray.
3. **Offset setzen**, einen Testkauf am Automaten auslösen und prüfen: `sales.item_number` = Label-Nummer, `machine_trays.current_stock` wurde dekrementiert, kein neuer Eintrag in `stock_decrement_log`.
4. **Nayax-Abgleich** über einen Zeitraum **vor** dem Cut-over laufen lassen — Ergebnis muss identisch zu vorher sein (keine neuen Phantome).

## Bekannte, bewusst akzeptierte Grenzen

- **Historie bricht am Cut-over.** Auswertungen, die über den Zeitpunkt hinweg nach Fachnummer gruppieren, sehen für diesen Automaten zwei Nummernräume. Bewusste Entscheidung gegen einen Backfill.
- **Eine spätere Offset-Änderung ist nicht rekonstruierbar.** `item_number_offset_since` hält nur den *aktuellen* Cut-over. Wird der Offset ein zweites Mal geändert, ist das mittlere Zeitfenster weder mit dem alten noch mit dem neuen Offset korrekt interpretierbar. Wer den Offset ändern will, sollte ihn richtig setzen — einmal.
- **Ein konstanter Offset setzt gleich breite Reihen voraus.** Bei Reihen mit weniger als zehn belegten Positionen stimmt die Zuordnung ab der zweiten Reihe nicht mehr. Dann ist ein Label-Feld pro Tray die richtige Lösung, nicht dieser Offset.
- **Firmware, iOS und Android bleiben unverändert.** Der Offset ist eine Ingest-Einstellung; die Clients arbeiten bereits im Label-Raum.
