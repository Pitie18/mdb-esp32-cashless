# Analytics Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a cross-fleet Analytics page — units, revenue and gross profit per article/category/machine over a freely chosen time range — as two SQL RPCs plus a new page in the native iOS app and in the PWA.

**Architecture:** Two additive `SECURITY DEFINER` RPCs do all aggregation server-side (`get_sales_analytics_summary` for the page header, `get_sales_analytics_breakdown` for the switchable breakdown). Both clients are thin: one view model / composable holding filter state, pure testable helper functions for classification and formatting, and presentational components. No schema changes, no changes to existing functions.

**Tech Stack:** PostgreSQL 15 / plpgsql · Supabase (PostgREST RPC) · SwiftUI + Swift Charts (iOS 17+) · Nuxt 4 + Vue 3 + `@unovis/vue` + shadcn-nuxt + TailwindCSS 4 · Vitest · plain-ASSERT SQL tests

**Spec:** [`docs/superpowers/specs/2026-08-11-analytics-page-design.md`](../specs/2026-08-11-analytics-page-design.md)

## Global Constraints

- **Migrations are immutable.** Never edit a migration file that exists on `origin/main`. This plan creates two *new* migration files; each is written once and never edited again after its task commits.
- **Never run `supabase db reset`.** Apply migrations with `supabase migration up` only. The local dev DB holds prod-synced data.
- **Backward compatibility.** Everything is additive: new functions only. Old firmware, old clients and old frontends are unaffected. New clients must survive a backend *without* the migration (PostgREST `404 PGRST202`).
- **`sales.item_price` is EUR, never cents.** Never divide by 100.
- **SECURITY DEFINER functions** use `SET search_path = public` and check the caller's company via `public.my_company_id()`.
- **Money rounding:** all monetary values rounded to 2 decimals in SQL (`::numeric`, never `float8` — `round(double precision, integer)` does not exist in Postgres and has broken production before).
- **iOS: every new `.swift` file must be registered by hand in `ios/VMflow.xcodeproj/project.pbxproj`** (four entries per file; a new group also needs a `PBXGroup` and an entry in the `Views` group's `children`). The project has no synchronized groups. Never regenerate with `xcodegen`.
- **iOS: pull-to-refresh uses `.dataRefreshable`**, never bare `.refreshable`.
- **iOS localisation:** German strings go into `ios/VMflow/Resources/Localizable.xcstrings` as `de`-only entries, key = the resolved `String(localized:)` literal, du-tone. Insert surgically — never re-serialise the file with a script.
- **PWA i18n:** add keys to `i18n/locales/en.json` and `i18n/locales/de.json`. `fr` and `nl` fall back to `en` (`fallbackLocale: 'en'` in `nuxt.config.ts`) — do not add stub translations.
- **No generated DB types.** `~/types/database.types.ts` does not exist; Supabase queries return `never`. PWA RPC calls use `(supabase as any).rpc(...)` and results are cast manually — this is the established pattern.

## Deviations from the spec (deliberate)

1. **Two migration files instead of one** (`…_sales_analytics_summary.sql`, `…_sales_analytics_breakdown.sql`). The spec says one file. Two keeps Task 1 and Task 2 independently committable without a second task editing an already-committed migration.
2. **`get_sales_analytics_breakdown` gains a `p_product_id uuid DEFAULT NULL` filter.** The spec (§4.3) requires the product detail sheet to show that product's distribution across machines but does not say how. Reusing the breakdown RPC with `p_dimension := 'machine'` and `p_product_id := <id>` avoids a third function.
3. **The PWA breakdown does not grow extra desktop columns.** The spec (§5) mentions sell-through moving into its own column on wide screens. The row already carries the previous-period delta and an average-per-day subtitle; adding a responsive column set for one more number is not worth the layout branching. Sell-through stays in the product detail dialog. Raise this again if it turns out to be missed in daily use.
4. **KPI row and trend chart stack rather than sitting side by side on `lg`.** The spec (§5) suggests the side-by-side arrangement. A full-width chart reads better at 90-day ranges, and the metric segment sits between the two — splitting them would separate the control from what it controls.

---

## Task 1: `get_sales_analytics_summary` RPC

**Files:**
- Create: `Docker/supabase/migrations/20260811000000_sales_analytics_summary.sql`
- Test: `Docker/supabase/tests/sales_analytics_summary.test.sql`

**Interfaces:**
- Consumes: existing `public.my_company_id()`, `public.resolve_product_tax_rate(uuid, date)`, tables `sales`, `vendingMachine`, `machine_trays`, `products`, `product_purchase_prices`.
- Produces: `public.get_sales_analytics_summary(p_company_id uuid, p_from timestamptz, p_to timestamptz, p_machine_ids uuid[], p_category_ids uuid[], p_timezone text) RETURNS json`. Return shape is fixed by Task 3 (iOS DTOs) and Task 10 (PWA types) — the JSON keys below are the contract.

**Prerequisite:** `supabase start` must be running (`cd Docker/supabase && supabase status`).

- [ ] **Step 1: Write the failing test**

Create `Docker/supabase/tests/sales_analytics_summary.test.sql`:

```sql
-- Integration test for get_sales_analytics_summary
-- (migration 20260811000000_sales_analytics_summary.sql).
--
-- Runs inside one transaction that is rolled back at the end → no dev data touched.
-- Plain ASSERT statements in a DO block (no pgTAP). Fake JWT via set_config so the
-- SECURITY DEFINER function's my_company_id() check can be exercised.
--
-- Requires `supabase start` + `supabase migration up`.
-- Run via Docker/supabase/tests/run-sql-tests.sh.

BEGIN;

SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_other    uuid := gen_random_uuid();
  v_user     uuid := gen_random_uuid();
  v_cat_a    uuid := gen_random_uuid();
  v_cat_b    uuid := gen_random_uuid();
  v_prod_a   uuid := gen_random_uuid();
  v_prod_b   uuid := gen_random_uuid();
  v_prod_x   uuid := gen_random_uuid();
  v_mach_1   uuid := gen_random_uuid();
  v_mach_2   uuid := gen_random_uuid();
  v_from     timestamptz := '2026-07-01 00:00+02';
  v_to       timestamptz := '2026-07-11 00:00+02';
  r json;
BEGIN
  -- ─── Tenant fixtures ─────────────────────────────────────────────────────
  INSERT INTO public.companies (id, name) VALUES
    (v_company, 'AnalyticsTestCo'), (v_other, 'StrangerCo');

  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_user, '00000000-0000-0000-0000-000000000000', 'analytics@test.local', now());
  INSERT INTO public.users (id, company, email)
    VALUES (v_user, v_company, 'analytics@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_user, 'admin');

  PERFORM set_config('request.jwt.claims',
                     json_build_object('sub', v_user::text, 'role', 'authenticated')::text,
                     true);

  INSERT INTO public.product_category (id, name, company) VALUES
    (v_cat_a, 'Drinks', v_company), (v_cat_b, 'Sweets', v_company);

  INSERT INTO public.products (id, name, company, category) VALUES
    (v_prod_a, 'Cola',   v_company, v_cat_a),
    (v_prod_b, 'Bar',    v_company, v_cat_b),
    (v_prod_x, 'NoCost', v_company, v_cat_a);

  INSERT INTO public."vendingMachine" (id, name, company) VALUES
    (v_mach_1, 'M1', v_company), (v_mach_2, 'M2', v_company);

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock) VALUES
    (v_mach_1, 11, v_prod_a, 10, 5),
    (v_mach_1, 12, v_prod_b, 10, 5),
    (v_mach_2, 11, v_prod_x, 10, 5);

  -- EK price for Cola only: 0.40 net, observed well before the window.
  INSERT INTO public.suppliers (id, company_id, name)
    VALUES (gen_random_uuid(), v_company, 'TestSupplier');
  INSERT INTO public.product_purchase_prices (product_id, supplier_id, price_net, price_gross, observed_on)
    SELECT v_prod_a, s.id, 0.40, 0.48, DATE '2026-01-01'
    FROM public.suppliers s WHERE s.company_id = v_company;

  -- ─── Sales ───────────────────────────────────────────────────────────────
  -- Current window: 2026-07-01T00:00+02 .. 2026-07-11T00:00+02 (10 days).
  -- Boundary probes: exactly at from (counts), exactly at to (does NOT count),
  -- one microsecond before to (counts).
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at) VALUES
    (v_mach_1, 11, v_prod_a, 1.00, 0.84, 'cash',     '2026-07-01 00:00+02'),
    (v_mach_1, 11, v_prod_a, 1.00, 0.84, 'cashless', '2026-07-03 09:30+02'),
    (v_mach_1, 12, v_prod_b, 2.00, 1.68, 'cash',     '2026-07-05 14:00+02'),
    (v_mach_2, 11, v_prod_x, 3.00, 2.52, 'cashless', '2026-07-06 11:00+02'),
    (v_mach_1, 11, v_prod_a, 1.00, 0.84, 'cash',     '2026-07-10 23:59:59.999999+02'),
    (v_mach_1, 11, v_prod_a, 1.00, 0.84, 'cash',     '2026-07-11 00:00+02');
  -- Previous window: 2026-06-21T00:00+02 .. 2026-07-01T00:00+02
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at) VALUES
    (v_mach_1, 11, v_prod_a, 1.00, 0.84, 'cash', '2026-06-25 10:00+02');
  -- Legacy sale without product_id — resolvable via machine_trays(machine, item).
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at) VALUES
    (v_mach_1, 12, NULL, 2.00, 1.68, 'cash', '2026-07-07 10:00+02');
  -- Unresolvable sale: no product_id and no tray at item_number 99.
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at) VALUES
    (v_mach_1, 99, NULL, 5.00, 4.20, 'cash', '2026-07-08 10:00+02');

  -- ─── 1. Window boundaries ────────────────────────────────────────────────
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin');

  ASSERT (r->'totals'->>'units')::int = 7,
    format('expected 7 units in window, got %s', r->'totals'->>'units');
  ASSERT (r->'totals'->>'revenue_gross')::numeric = 15.00,
    format('expected 15.00 gross revenue, got %s', r->'totals'->>'revenue_gross');

  -- ─── 2. Previous period = same-length window immediately before ──────────
  ASSERT (r->'previous'->>'units')::int = 1,
    format('expected 1 unit in previous window, got %s', r->'previous'->>'units');

  -- ─── 3. Gross profit: only products with an EK price contribute ──────────
  -- Cola sold 4x in window: (0.84 - 0.40) * 4 = 1.76. Bar/NoCost have no EK.
  ASSERT (r->'totals'->>'gross_profit')::numeric = 1.76,
    format('expected 1.76 gross profit, got %s', r->'totals'->>'gross_profit');
  -- Products sold without any EK notation: Bar, NoCost → 2
  ASSERT (r->>'missing_cost_products')::int = 2,
    format('expected 2 products without cost, got %s', r->>'missing_cost_products');

  -- ─── 4. Unresolvable sales are counted but flagged ───────────────────────
  ASSERT (r->>'unknown_product_units')::int = 1,
    format('expected 1 unknown-product unit, got %s', r->>'unknown_product_units');

  -- ─── 5. Daily series is gapless ──────────────────────────────────────────
  ASSERT json_array_length(r->'daily') = 10,
    format('expected 10 daily buckets, got %s', json_array_length(r->'daily'));

  -- ─── 6. Timezone affects bucketing ───────────────────────────────────────
  -- The 2026-07-10 23:59:59.999999+02 sale is 21:59 UTC on 2026-07-10 in UTC,
  -- but 23:59 local on 2026-07-10 in Berlin. Compare heatmap hours.
  ASSERT EXISTS (
    SELECT 1 FROM json_array_elements(r->'heatmap') h
    WHERE (h->>'hour')::int = 23 AND (h->>'dow')::int = 5
  ), 'expected a Berlin-local Friday 23:00 heatmap cell';

  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, NULL, 'UTC');
  ASSERT NOT EXISTS (
    SELECT 1 FROM json_array_elements(r->'heatmap') h
    WHERE (h->>'hour')::int = 23 AND (h->>'dow')::int = 5
  ), 'UTC bucketing must not produce the Berlin-local 23:00 cell';

  -- ─── 7. Machine filter ───────────────────────────────────────────────────
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, ARRAY[v_mach_2], NULL, 'Europe/Berlin');
  ASSERT (r->'totals'->>'units')::int = 1,
    format('expected 1 unit for machine 2, got %s', r->'totals'->>'units');

  -- ─── 8. Category filter drops unresolvable sales ─────────────────────────
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, ARRAY[v_cat_a], 'Europe/Berlin');
  ASSERT (r->'totals'->>'units')::int = 5,
    format('expected 5 Drinks units, got %s', r->'totals'->>'units');
  ASSERT (r->>'unknown_product_units')::int = 0,
    'category filter must exclude unresolvable sales';

  -- ─── 9. Channel split ────────────────────────────────────────────────────
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin');
  ASSERT (SELECT (c->>'units')::int FROM json_array_elements(r->'channels') c
          WHERE c->>'channel' = 'cashless') = 2,
    'expected 2 cashless units';

  -- ─── 10. Tenant isolation ────────────────────────────────────────────────
  BEGIN
    r := public.get_sales_analytics_summary(v_other, v_from, v_to, NULL, NULL, 'UTC');
    RAISE EXCEPTION 'expected access denied for foreign company';
  EXCEPTION WHEN sqlstate 'P0001' THEN
    IF SQLERRM = 'expected access denied for foreign company' THEN RAISE; END IF;
  END;

  -- ─── 11. Invalid range rejected ──────────────────────────────────────────
  BEGIN
    r := public.get_sales_analytics_summary(v_company, v_to, v_from, NULL, NULL, 'UTC');
    RAISE EXCEPTION 'expected invalid range rejection';
  EXCEPTION WHEN sqlstate 'P0001' THEN
    IF SQLERRM = 'expected invalid range rejection' THEN RAISE; END IF;
  END;

  -- ─── 12. Garbage timezone falls back to UTC instead of erroring ──────────
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, NULL, 'Not/AZone');
  ASSERT (r->'range'->>'timezone') = 'UTC',
    format('expected UTC fallback, got %s', r->'range'->>'timezone');

  RAISE NOTICE 'sales_analytics_summary: all assertions passed';
END $$;

ROLLBACK;
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd Docker/supabase && ./tests/run-sql-tests.sh
```

Expected: `FAIL` on `sales_analytics_summary.test.sql` with `ERROR: function public.get_sales_analytics_summary(...) does not exist`.

- [ ] **Step 3: Write the migration**

Create `Docker/supabase/migrations/20260811000000_sales_analytics_summary.sql`:

```sql
-- =========================================================
-- get_sales_analytics_summary — page header of the Analytics page.
--
-- Returns totals for a freely chosen window, the same totals for the
-- immediately preceding window of equal length, a gapless daily series,
-- a weekday x hour heatmap, the payment-channel split, and two data-quality
-- counters.
--
-- Purely additive: creates one function, touches no table and no existing
-- function. Old clients and old firmware are unaffected.
--
-- All day/hour bucketing happens in p_timezone, NOT in the session timezone.
-- Without that the heatmap would be shifted by the UTC offset and the daily
-- buckets would be cut at the wrong midnight.
-- =========================================================

CREATE OR REPLACE FUNCTION public.get_sales_analytics_summary(
  p_company_id   uuid,
  p_from         timestamptz,
  p_to           timestamptz,
  p_machine_ids  uuid[] DEFAULT NULL,
  p_category_ids uuid[] DEFAULT NULL,
  p_timezone     text   DEFAULT 'UTC'
)
RETURNS json
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_caller    uuid := public.my_company_id();
  v_span      interval;
  v_prev_from timestamptz;
  v_days      numeric;
  v_tz        text;
  v_result    json;
BEGIN
  IF v_caller IS NULL OR v_caller IS DISTINCT FROM p_company_id THEN
    RAISE EXCEPTION 'not authenticated or access denied';
  END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from THEN
    RAISE EXCEPTION 'invalid range';
  END IF;

  -- Validate the timezone; a client sending garbage must not break the page.
  BEGIN
    PERFORM now() AT TIME ZONE p_timezone;
    v_tz := p_timezone;
  EXCEPTION WHEN OTHERS THEN
    v_tz := 'UTC';
  END;

  v_span      := p_to - p_from;
  v_prev_from := p_from - v_span;
  v_days      := GREATEST(EXTRACT(epoch FROM v_span) / 86400.0, 1.0 / 86400.0);

  WITH machines AS (
    SELECT vm.id
    FROM public."vendingMachine" vm
    WHERE vm.company = p_company_id
      AND (p_machine_ids IS NULL
           OR cardinality(p_machine_ids) = 0
           OR vm.id = ANY(p_machine_ids))
  ),
  raw_sales AS (
    -- One pass over both windows; `is_current` separates them later.
    -- Legacy sales without a stamped product_id fall back to the slot's
    -- current product, exactly like get_machine_product_kpis does.
    SELECT s.id, s.created_at, s.item_price, s.price_net, s.channel,
           COALESCE(s.product_id, mt.product_id) AS product_id
    FROM public.sales s
    JOIN machines m ON m.id = s.machine_id
    LEFT JOIN public.machine_trays mt
      ON s.product_id IS NULL
     AND mt.machine_id = s.machine_id
     AND mt.item_number = s.item_number
    WHERE s.created_at >= v_prev_from
      AND s.created_at <  p_to
  ),
  priced AS (
    SELECT r.id, r.created_at, r.item_price, r.channel, r.product_id,
           (r.created_at >= p_from) AS is_current,
           p.category AS category_id,
           -- Net selling price: stamped where available, otherwise derived
           -- from the product's tax rate, otherwise the gross price.
           COALESCE(
             r.price_net,
             CASE WHEN tr.rate IS NOT NULL
                  THEN round(r.item_price::numeric / (1 + tr.rate), 4)
                  ELSE r.item_price::numeric
             END
           ) AS eff_net,
           ek.price_net AS cost_net
    FROM raw_sales r
    LEFT JOIN public.products p ON p.id = r.product_id
    LEFT JOIN LATERAL (
      SELECT public.resolve_product_tax_rate(
               r.product_id, (r.created_at AT TIME ZONE v_tz)::date) AS rate
    ) tr ON r.price_net IS NULL AND r.product_id IS NOT NULL
    LEFT JOIN LATERAL (
      -- The purchase price that was valid on the sale date: the newest
      -- notation at or before it. If the sale predates every notation, the
      -- oldest one is used — a slightly wrong basis beats a hole in the row.
      SELECT pp.price_net
      FROM public.product_purchase_prices pp
      WHERE pp.product_id = r.product_id
      ORDER BY (pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date) DESC,
               CASE WHEN pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date
                    THEN pp.observed_on END DESC NULLS LAST,
               pp.observed_on ASC
      LIMIT 1
    ) ek ON true
  ),
  fsales AS (
    SELECT * FROM priced
    WHERE (p_category_ids IS NULL
           OR cardinality(p_category_ids) = 0
           OR category_id = ANY(p_category_ids))
  ),
  cur AS (SELECT * FROM fsales WHERE is_current),
  prv AS (SELECT * FROM fsales WHERE NOT is_current),
  agg_cur AS (
    SELECT count(*)::bigint AS units,
           round(COALESCE(sum(item_price)::numeric, 0), 2)                     AS revenue_gross,
           round(COALESCE(sum(eff_net), 0), 2)                                 AS revenue_net,
           round(COALESCE(sum(cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS cost_net,
           round(COALESCE(sum(eff_net - cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS gross_profit,
           round(COALESCE(avg(item_price)::numeric, 0), 4)                     AS avg_ticket
    FROM cur
  ),
  agg_prv AS (
    SELECT count(*)::bigint AS units,
           round(COALESCE(sum(item_price)::numeric, 0), 2)                     AS revenue_gross,
           round(COALESCE(sum(eff_net), 0), 2)                                 AS revenue_net,
           round(COALESCE(sum(cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS cost_net,
           round(COALESCE(sum(eff_net - cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS gross_profit,
           round(COALESCE(avg(item_price)::numeric, 0), 4)                     AS avg_ticket
    FROM prv
  ),
  day_grid AS (
    SELECT generate_series(
             (p_from AT TIME ZONE v_tz)::date,
             ((p_to AT TIME ZONE v_tz) - interval '1 microsecond')::date,
             interval '1 day')::date AS day
  ),
  daily AS (
    -- Gapless on purpose: days without a sale must render as an empty bar,
    -- not silently collapse the chart.
    SELECT g.day,
           count(c.id)::bigint AS units,
           round(COALESCE(sum(c.item_price)::numeric, 0), 2) AS revenue_gross,
           round(COALESCE(sum(c.eff_net - c.cost_net) FILTER (WHERE c.cost_net IS NOT NULL), 0), 2) AS gross_profit
    FROM day_grid g
    LEFT JOIN cur c ON (c.created_at AT TIME ZONE v_tz)::date = g.day
    GROUP BY g.day
  ),
  heat AS (
    SELECT extract(isodow FROM (created_at AT TIME ZONE v_tz))::int AS dow,
           extract(hour   FROM (created_at AT TIME ZONE v_tz))::int AS hour,
           count(*)::bigint AS units,
           round(sum(item_price)::numeric, 2) AS revenue_gross
    FROM cur
    GROUP BY 1, 2
  ),
  chan AS (
    SELECT COALESCE(NULLIF(btrim(channel), ''), 'unknown') AS channel,
           count(*)::bigint AS units,
           round(sum(item_price)::numeric, 2) AS revenue_gross,
           round(avg(item_price)::numeric, 4) AS avg_ticket
    FROM cur
    GROUP BY 1
  )
  SELECT json_build_object(
    'range', json_build_object(
      'from', p_from, 'to', p_to,
      'previous_from', v_prev_from, 'previous_to', p_from,
      'days', round(v_days, 4), 'timezone', v_tz
    ),
    'totals', (SELECT json_build_object(
        'units', units, 'revenue_gross', revenue_gross, 'revenue_net', revenue_net,
        'cost_net', cost_net, 'gross_profit', gross_profit, 'avg_ticket', avg_ticket,
        'avg_daily_units',   round(units / v_days, 4),
        'avg_daily_revenue', round(revenue_gross / v_days, 4),
        'avg_daily_gross_profit', round(gross_profit / v_days, 4)
      ) FROM agg_cur),
    'previous', (SELECT json_build_object(
        'units', units, 'revenue_gross', revenue_gross, 'revenue_net', revenue_net,
        'cost_net', cost_net, 'gross_profit', gross_profit, 'avg_ticket', avg_ticket,
        'avg_daily_units',   round(units / v_days, 4),
        'avg_daily_revenue', round(revenue_gross / v_days, 4),
        'avg_daily_gross_profit', round(gross_profit / v_days, 4)
      ) FROM agg_prv),
    'daily', (SELECT COALESCE(json_agg(json_build_object(
        'day', day, 'units', units,
        'revenue_gross', revenue_gross, 'gross_profit', gross_profit
      ) ORDER BY day), '[]'::json) FROM daily),
    'heatmap', (SELECT COALESCE(json_agg(json_build_object(
        'dow', dow, 'hour', hour, 'units', units, 'revenue_gross', revenue_gross
      ) ORDER BY dow, hour), '[]'::json) FROM heat),
    'channels', (SELECT COALESCE(json_agg(json_build_object(
        'channel', channel, 'units', units,
        'revenue_gross', revenue_gross, 'avg_ticket', avg_ticket
      ) ORDER BY revenue_gross DESC), '[]'::json) FROM chan),
    'missing_cost_products', (
      SELECT count(DISTINCT product_id)::int FROM cur
      WHERE product_id IS NOT NULL AND cost_net IS NULL),
    'unknown_product_units', (
      SELECT count(*)::bigint FROM cur WHERE product_id IS NULL)
  ) INTO v_result;

  RETURN v_result;
END $$;

GRANT EXECUTE ON FUNCTION public.get_sales_analytics_summary(uuid, timestamptz, timestamptz, uuid[], uuid[], text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_sales_analytics_summary(uuid, timestamptz, timestamptz, uuid[], uuid[], text) TO service_role;
```

- [ ] **Step 4: Apply the migration and run the test**

```bash
cd Docker/supabase && supabase migration up && ./tests/run-sql-tests.sh
```

Expected: `sales_analytics_summary: all assertions passed` followed by `PASS`. All other pre-existing test files must still pass.

- [ ] **Step 5: Commit**

```bash
git add Docker/supabase/migrations/20260811000000_sales_analytics_summary.sql Docker/supabase/tests/sales_analytics_summary.test.sql
git commit -m "feat(db): add get_sales_analytics_summary RPC"
```

---

## Task 2: `get_sales_analytics_breakdown` RPC

**Files:**
- Create: `Docker/supabase/migrations/20260811000100_sales_analytics_breakdown.sql`
- Test: `Docker/supabase/tests/sales_analytics_breakdown.test.sql`

**Interfaces:**
- Consumes: same tables and helpers as Task 1.
- Produces: `public.get_sales_analytics_breakdown(p_company_id uuid, p_from timestamptz, p_to timestamptz, p_machine_ids uuid[], p_category_ids uuid[], p_timezone text, p_dimension text, p_product_id uuid) RETURNS json` — a JSON array whose element keys are the contract for Task 3 / Task 10.

- [ ] **Step 1: Write the failing test**

Create `Docker/supabase/tests/sales_analytics_breakdown.test.sql`:

```sql
-- Integration test for get_sales_analytics_breakdown
-- (migration 20260811000100_sales_analytics_breakdown.sql).
--
-- Rolled-back transaction, plain ASSERTs, fake JWT — same pattern as
-- sales_analytics_summary.test.sql. Run via tests/run-sql-tests.sh.

BEGIN;

SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_other   uuid := gen_random_uuid();
  v_user    uuid := gen_random_uuid();
  v_cat_a   uuid := gen_random_uuid();
  v_cat_b   uuid := gen_random_uuid();
  v_big     uuid := gen_random_uuid();   -- ~85 % of revenue → class A
  v_mid     uuid := gen_random_uuid();   -- pushes cumulative past 95 % → B
  v_small   uuid := gen_random_uuid();   -- tail → C
  v_mach_1  uuid := gen_random_uuid();
  v_mach_2  uuid := gen_random_uuid();
  v_from    timestamptz := '2026-07-01 00:00+02';
  v_to      timestamptz := '2026-07-11 00:00+02';
  r json; row_big json;
BEGIN
  INSERT INTO public.companies (id, name) VALUES
    (v_company, 'BreakdownTestCo'), (v_other, 'StrangerCo');

  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_user, '00000000-0000-0000-0000-000000000000', 'bd@test.local', now());
  INSERT INTO public.users (id, company, email)
    VALUES (v_user, v_company, 'bd@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_user, 'admin');

  PERFORM set_config('request.jwt.claims',
                     json_build_object('sub', v_user::text, 'role', 'authenticated')::text,
                     true);

  INSERT INTO public.product_category (id, name, company) VALUES
    (v_cat_a, 'Drinks', v_company), (v_cat_b, 'Sweets', v_company);

  INSERT INTO public.products (id, name, company, category) VALUES
    (v_big,   'BigSeller', v_company, v_cat_a),
    (v_mid,   'MidSeller', v_company, v_cat_a),
    (v_small, 'Tail',      v_company, v_cat_b);

  INSERT INTO public."vendingMachine" (id, name, company) VALUES
    (v_mach_1, 'M1', v_company), (v_mach_2, 'M2', v_company);

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock) VALUES
    (v_mach_1, 11, v_big,   10, 4),
    (v_mach_2, 11, v_big,   10, 6),
    (v_mach_1, 12, v_mid,   10, 5),
    (v_mach_1, 13, v_small, 10, 5);

  INSERT INTO public.suppliers (id, company_id, name)
    VALUES (gen_random_uuid(), v_company, 'S');
  INSERT INTO public.product_purchase_prices (product_id, supplier_id, price_net, price_gross, observed_on)
    SELECT v_big, s.id, 0.50, 0.60, DATE '2026-01-01'
    FROM public.suppliers s WHERE s.company_id = v_company;

  -- Revenue: BigSeller 17 x 1.00 = 17.00 (85 %), MidSeller 2 x 1.00 = 2.00 (10 %),
  -- Tail 1 x 1.00 = 1.00 (5 %). Cumulative: 85 → A, 95 → B, 100 → C.
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
  SELECT v_mach_1, 11, v_big, 1.00, 0.84, 'cash', '2026-07-02 10:00+02'
  FROM generate_series(1, 12);
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
  SELECT v_mach_2, 11, v_big, 1.00, 0.84, 'cash', '2026-07-02 11:00+02'
  FROM generate_series(1, 5);
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
  SELECT v_mach_1, 12, v_mid, 1.00, 0.84, 'cash', '2026-07-03 10:00+02'
  FROM generate_series(1, 2);
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
    VALUES (v_mach_1, 13, v_small, 1.00, 0.84, 'cash', '2026-07-04 10:00+02');
  -- Previous window, BigSeller only.
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
  SELECT v_mach_1, 11, v_big, 1.00, 0.84, 'cash', '2026-06-25 10:00+02'
  FROM generate_series(1, 3);

  -- ─── 1. Product dimension: aggregation across all slots ──────────────────
  r := public.get_sales_analytics_breakdown(
         v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin', 'product', NULL);

  SELECT e INTO row_big FROM json_array_elements(r) e WHERE e->>'key' = v_big::text;
  ASSERT row_big IS NOT NULL, 'BigSeller row missing';
  ASSERT (row_big->>'units')::int = 17,
    format('expected 17 units for BigSeller, got %s', row_big->>'units');
  ASSERT (row_big->>'machine_count')::int = 2,
    format('expected BigSeller in 2 machines, got %s', row_big->>'machine_count');
  ASSERT (row_big->>'total_capacity')::int = 20,
    format('expected capacity 20, got %s', row_big->>'total_capacity');
  ASSERT (row_big->>'total_stock')::int = 10,
    format('expected stock 10, got %s', row_big->>'total_stock');
  ASSERT (row_big->>'has_cost')::boolean IS TRUE, 'BigSeller must have cost';
  -- (0.84 - 0.50) * 17 = 5.78
  ASSERT (row_big->>'gross_profit')::numeric = 5.78,
    format('expected 5.78 gross profit, got %s', row_big->>'gross_profit');

  -- ─── 2. Previous-period values per row ───────────────────────────────────
  ASSERT (row_big->>'prev_units')::int = 3,
    format('expected 3 previous units, got %s', row_big->>'prev_units');

  -- ─── 3. ABC classification on the cumulative revenue curve ───────────────
  ASSERT (row_big->>'abc_class') = 'A', format('expected A, got %s', row_big->>'abc_class');
  ASSERT (SELECT e->>'abc_class' FROM json_array_elements(r) e WHERE e->>'key' = v_mid::text) = 'B',
    'MidSeller must be class B';
  ASSERT (SELECT e->>'abc_class' FROM json_array_elements(r) e WHERE e->>'key' = v_small::text) = 'C',
    'Tail must be class C';

  -- ─── 4. Rows without cost are marked, not silently zeroed ────────────────
  ASSERT (SELECT (e->>'has_cost')::boolean FROM json_array_elements(r) e
          WHERE e->>'key' = v_mid::text) IS FALSE,
    'MidSeller has no EK price and must be flagged';

  -- ─── 5. Category dimension ───────────────────────────────────────────────
  r := public.get_sales_analytics_breakdown(
         v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin', 'category', NULL);
  ASSERT (SELECT (e->>'units')::int FROM json_array_elements(r) e
          WHERE e->>'key' = v_cat_a::text) = 19,
    'Drinks category must aggregate BigSeller + MidSeller';

  -- ─── 6. Machine dimension ────────────────────────────────────────────────
  r := public.get_sales_analytics_breakdown(
         v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin', 'machine', NULL);
  ASSERT (SELECT (e->>'units')::int FROM json_array_elements(r) e
          WHERE e->>'key' = v_mach_2::text) = 5,
    'M2 must show 5 units';

  -- ─── 7. p_product_id narrows the machine dimension to one product ────────
  r := public.get_sales_analytics_breakdown(
         v_company, v_from, v_to, NULL, NULL, 'Europe/Berlin', 'machine', v_big);
  ASSERT json_array_length(r) = 2,
    format('expected 2 machines for BigSeller, got %s', json_array_length(r));
  ASSERT (SELECT (e->>'units')::int FROM json_array_elements(r) e
          WHERE e->>'key' = v_mach_1::text) = 12,
    'M1 must show 12 BigSeller units';

  -- ─── 8. Invalid dimension rejected ───────────────────────────────────────
  BEGIN
    r := public.get_sales_analytics_breakdown(
           v_company, v_from, v_to, NULL, NULL, 'UTC', 'supplier', NULL);
    RAISE EXCEPTION 'expected invalid dimension rejection';
  EXCEPTION WHEN sqlstate 'P0001' THEN
    IF SQLERRM = 'expected invalid dimension rejection' THEN RAISE; END IF;
  END;

  -- ─── 9. Tenant isolation ─────────────────────────────────────────────────
  BEGIN
    r := public.get_sales_analytics_breakdown(
           v_other, v_from, v_to, NULL, NULL, 'UTC', 'product', NULL);
    RAISE EXCEPTION 'expected access denied for foreign company';
  EXCEPTION WHEN sqlstate 'P0001' THEN
    IF SQLERRM = 'expected access denied for foreign company' THEN RAISE; END IF;
  END;

  RAISE NOTICE 'sales_analytics_breakdown: all assertions passed';
END $$;

ROLLBACK;
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd Docker/supabase && ./tests/run-sql-tests.sh
```

Expected: `FAIL` on `sales_analytics_breakdown.test.sql` with `function public.get_sales_analytics_breakdown(...) does not exist`.

- [ ] **Step 3: Write the migration**

Create `Docker/supabase/migrations/20260811000100_sales_analytics_breakdown.sql`:

```sql
-- =========================================================
-- get_sales_analytics_breakdown — the switchable breakdown of the
-- Analytics page: sales aggregated per product, per category or per machine
-- for the same filters as get_sales_analytics_summary.
--
-- share_pct / cumulative_share_pct / abc_class are ALWAYS revenue-based, so
-- a row's Pareto class does not change when the user switches the displayed
-- metric. The share bar in the UI is computed client-side from the selected
-- metric and is deliberately a different thing.
--
-- The array is never truncated: the breakdown must stay sum-consistent with
-- the KPI row above it.
--
-- Purely additive. Creates one function, touches nothing else.
-- =========================================================

CREATE OR REPLACE FUNCTION public.get_sales_analytics_breakdown(
  p_company_id   uuid,
  p_from         timestamptz,
  p_to           timestamptz,
  p_machine_ids  uuid[] DEFAULT NULL,
  p_category_ids uuid[] DEFAULT NULL,
  p_timezone     text   DEFAULT 'UTC',
  p_dimension    text   DEFAULT 'product',
  p_product_id   uuid   DEFAULT NULL
)
RETURNS json
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_caller    uuid := public.my_company_id();
  v_span      interval;
  v_prev_from timestamptz;
  v_days      numeric;
  v_weeks     numeric;
  v_tz        text;
  v_result    json;
BEGIN
  IF v_caller IS NULL OR v_caller IS DISTINCT FROM p_company_id THEN
    RAISE EXCEPTION 'not authenticated or access denied';
  END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from THEN
    RAISE EXCEPTION 'invalid range';
  END IF;
  IF p_dimension IS NULL OR p_dimension NOT IN ('product', 'category', 'machine') THEN
    RAISE EXCEPTION 'invalid dimension: %', p_dimension;
  END IF;

  BEGIN
    PERFORM now() AT TIME ZONE p_timezone;
    v_tz := p_timezone;
  EXCEPTION WHEN OTHERS THEN
    v_tz := 'UTC';
  END;

  v_span      := p_to - p_from;
  v_prev_from := p_from - v_span;
  v_days      := GREATEST(EXTRACT(epoch FROM v_span) / 86400.0, 1.0 / 86400.0);
  v_weeks     := GREATEST(v_days / 7.0, 1.0 / 604800.0);

  WITH machines AS (
    SELECT vm.id
    FROM public."vendingMachine" vm
    WHERE vm.company = p_company_id
      AND (p_machine_ids IS NULL
           OR cardinality(p_machine_ids) = 0
           OR vm.id = ANY(p_machine_ids))
  ),
  raw_sales AS (
    SELECT s.id, s.created_at, s.item_price, s.price_net, s.machine_id,
           COALESCE(s.product_id, mt.product_id) AS product_id
    FROM public.sales s
    JOIN machines m ON m.id = s.machine_id
    LEFT JOIN public.machine_trays mt
      ON s.product_id IS NULL
     AND mt.machine_id = s.machine_id
     AND mt.item_number = s.item_number
    WHERE s.created_at >= v_prev_from
      AND s.created_at <  p_to
  ),
  priced AS (
    SELECT r.id, r.created_at, r.item_price, r.machine_id, r.product_id,
           (r.created_at >= p_from) AS is_current,
           p.category AS category_id,
           COALESCE(
             r.price_net,
             CASE WHEN tr.rate IS NOT NULL
                  THEN round(r.item_price::numeric / (1 + tr.rate), 4)
                  ELSE r.item_price::numeric
             END
           ) AS eff_net,
           ek.price_net AS cost_net
    FROM raw_sales r
    LEFT JOIN public.products p ON p.id = r.product_id
    LEFT JOIN LATERAL (
      SELECT public.resolve_product_tax_rate(
               r.product_id, (r.created_at AT TIME ZONE v_tz)::date) AS rate
    ) tr ON r.price_net IS NULL AND r.product_id IS NOT NULL
    LEFT JOIN LATERAL (
      SELECT pp.price_net
      FROM public.product_purchase_prices pp
      WHERE pp.product_id = r.product_id
      ORDER BY (pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date) DESC,
               CASE WHEN pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date
                    THEN pp.observed_on END DESC NULLS LAST,
               pp.observed_on ASC
      LIMIT 1
    ) ek ON true
  ),
  fsales AS (
    SELECT * FROM priced
    WHERE (p_category_ids IS NULL
           OR cardinality(p_category_ids) = 0
           OR category_id = ANY(p_category_ids))
      AND (p_product_id IS NULL OR product_id = p_product_id)
  ),
  keyed AS (
    SELECT CASE p_dimension
             WHEN 'product'  THEN product_id
             WHEN 'category' THEN category_id
             ELSE machine_id
           END AS key,
           is_current, item_price, eff_net, cost_net
    FROM fsales
  ),
  sales_agg AS (
    SELECT key,
           count(*) FILTER (WHERE is_current)::bigint AS units,
           round(COALESCE(sum(item_price) FILTER (WHERE is_current)::numeric, 0), 2) AS revenue_gross,
           round(COALESCE(sum(eff_net)    FILTER (WHERE is_current), 0), 2)          AS revenue_net,
           round(COALESCE(sum(eff_net - cost_net)
                          FILTER (WHERE is_current AND cost_net IS NOT NULL), 0), 2) AS gross_profit,
           count(*) FILTER (WHERE is_current AND cost_net IS NULL)::bigint           AS units_without_cost,
           count(*) FILTER (WHERE NOT is_current)::bigint AS prev_units,
           round(COALESCE(sum(item_price) FILTER (WHERE NOT is_current)::numeric, 0), 2) AS prev_revenue_gross,
           round(COALESCE(sum(eff_net - cost_net)
                          FILTER (WHERE NOT is_current AND cost_net IS NOT NULL), 0), 2) AS prev_gross_profit
    FROM keyed
    GROUP BY key
  ),
  trays AS (
    SELECT mt.machine_id, mt.product_id, p.category AS category_id,
           mt.capacity, mt.current_stock
    FROM public.machine_trays mt
    JOIN machines m ON m.id = mt.machine_id
    LEFT JOIN public.products p ON p.id = mt.product_id
    WHERE mt.product_id IS NOT NULL
      AND (p_category_ids IS NULL
           OR cardinality(p_category_ids) = 0
           OR p.category = ANY(p_category_ids))
      AND (p_product_id IS NULL OR mt.product_id = p_product_id)
  ),
  tray_agg AS (
    SELECT CASE p_dimension
             WHEN 'product'  THEN product_id
             WHEN 'category' THEN category_id
             ELSE machine_id
           END AS key,
           COALESCE(sum(capacity), 0)::bigint      AS total_capacity,
           COALESCE(sum(current_stock), 0)::bigint AS total_stock,
           count(DISTINCT machine_id)::int         AS machine_count,
           count(DISTINCT product_id)::int         AS product_count
    FROM trays
    GROUP BY 1
  ),
  all_keys AS (
    SELECT key FROM sales_agg
    UNION
    SELECT key FROM tray_agg
  ),
  labelled AS (
    SELECT k.key,
           CASE p_dimension
             WHEN 'product'  THEN COALESCE(pr.name, 'Unknown')
             WHEN 'category' THEN COALESCE(pc.name, 'Unknown')
             ELSE COALESCE(vm.name, 'Unknown')
           END AS label,
           CASE WHEN p_dimension = 'product' THEN pr.image_path END AS image_path
    FROM all_keys k
    LEFT JOIN public.products         pr ON p_dimension = 'product'  AND pr.id = k.key
    LEFT JOIN public.product_category pc ON p_dimension = 'category' AND pc.id = k.key
    LEFT JOIN public."vendingMachine" vm ON p_dimension = 'machine'  AND vm.id = k.key
  ),
  joined AS (
    SELECT l.key, l.label, l.image_path,
           COALESCE(sa.units, 0)              AS units,
           COALESCE(sa.revenue_gross, 0)      AS revenue_gross,
           COALESCE(sa.revenue_net, 0)        AS revenue_net,
           COALESCE(sa.gross_profit, 0)       AS gross_profit,
           COALESCE(sa.units_without_cost, 0) AS units_without_cost,
           COALESCE(sa.prev_units, 0)         AS prev_units,
           COALESCE(sa.prev_revenue_gross, 0) AS prev_revenue_gross,
           COALESCE(sa.prev_gross_profit, 0)  AS prev_gross_profit,
           COALESCE(ta.total_capacity, 0)     AS total_capacity,
           COALESCE(ta.total_stock, 0)        AS total_stock,
           COALESCE(ta.machine_count, 0)      AS machine_count,
           COALESCE(ta.product_count, 0)      AS product_count
    FROM labelled l
    LEFT JOIN sales_agg sa ON sa.key IS NOT DISTINCT FROM l.key
    LEFT JOIN tray_agg  ta ON ta.key IS NOT DISTINCT FROM l.key
  ),
  ranked AS (
    SELECT j.*,
           NULLIF(sum(revenue_gross) OVER (), 0) AS total_revenue,
           sum(revenue_gross) OVER (ORDER BY revenue_gross DESC, label ASC
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_revenue
    FROM joined j
  )
  SELECT COALESCE(json_agg(json_build_object(
    'key',   key,
    'label', label,
    'image_path', image_path,
    'units', units,
    'revenue_gross', revenue_gross,
    'revenue_net',   revenue_net,
    'gross_profit',  gross_profit,
    'prev_units', prev_units,
    'prev_revenue_gross', prev_revenue_gross,
    'prev_gross_profit',  prev_gross_profit,
    'share_pct', CASE WHEN total_revenue IS NULL THEN 0
                      ELSE round(revenue_gross / total_revenue * 100, 2) END,
    'cumulative_share_pct', CASE WHEN total_revenue IS NULL THEN 0
                                 ELSE round(cum_revenue / total_revenue * 100, 2) END,
    'abc_class', CASE
                   WHEN total_revenue IS NULL THEN 'C'
                   WHEN cum_revenue / total_revenue * 100 <= 80  THEN 'A'
                   WHEN cum_revenue / total_revenue * 100 <= 95  THEN 'B'
                   ELSE 'C' END,
    'avg_daily_units',        round(units / v_days, 4),
    'avg_daily_revenue',      round(revenue_gross / v_days, 4),
    'avg_daily_gross_profit', round(gross_profit / v_days, 4),
    'total_capacity', total_capacity,
    'total_stock',    total_stock,
    'sell_through_pct', CASE WHEN total_capacity > 0
      THEN LEAST(round(units / (total_capacity * v_weeks) * 100, 2), 100) END,
    'days_of_supply',   CASE WHEN units > 0
      THEN round(total_stock / (units / v_days), 1) END,
    'machine_count', machine_count,
    'product_count', product_count,
    'has_cost', (units > 0 AND units_without_cost = 0)
  ) ORDER BY revenue_gross DESC, label ASC), '[]'::json)
  INTO v_result
  FROM ranked;

  RETURN v_result;
END $$;

GRANT EXECUTE ON FUNCTION public.get_sales_analytics_breakdown(uuid, timestamptz, timestamptz, uuid[], uuid[], text, text, uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_sales_analytics_breakdown(uuid, timestamptz, timestamptz, uuid[], uuid[], text, text, uuid) TO service_role;
```

- [ ] **Step 4: Apply the migration and run the tests**

```bash
cd Docker/supabase && supabase migration up && ./tests/run-sql-tests.sh
```

Expected: `sales_analytics_breakdown: all assertions passed` + `PASS`, and every other test file still passing.

- [ ] **Step 5: Commit**

```bash
git add Docker/supabase/migrations/20260811000100_sales_analytics_breakdown.sql Docker/supabase/tests/sales_analytics_breakdown.test.sql
git commit -m "feat(db): add get_sales_analytics_breakdown RPC"
```

## Task 3: iOS — DTOs and pure helpers

**Files:**
- Create: `ios/VMflow/Models/Analytics.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`
- Test: `$SCRATCH/analytics_helpers_test.swift` (throwaway — the project has no test target)

**Set `SCRATCH` once at the start of this task** to the session scratchpad directory (any writable temp dir works; nothing here is committed):

```bash
export SCRATCH="${TMPDIR:-/tmp}/vmflow-analytics" && mkdir -p "$SCRATCH" && echo "$SCRATCH"
```

**Interfaces:**
- Consumes: the JSON contracts of Task 1 and Task 2.
- Produces:
  - `enum AnalyticsMetric: String, CaseIterable { case units, revenue, grossProfit }`
  - `enum AnalyticsDimension: String, CaseIterable { case product, category, machine }`
  - `enum AnalyticsRangePreset: String, CaseIterable { case days7, days30, days90, thisMonth, lastMonth, custom }`
  - `struct AnalyticsSummary: Decodable` with `range`, `totals`, `previous`, `daily`, `heatmap`, `channels`, `missingCostProducts`, `unknownProductUnits`
  - `struct AnalyticsTotals: Decodable`, `struct AnalyticsDailyPoint: Decodable, Identifiable`, `struct AnalyticsHeatCell: Decodable, Identifiable`, `struct AnalyticsChannel: Decodable, Identifiable`
  - `struct AnalyticsBreakdownRow: Decodable, Identifiable`
  - `func deltaPct(current: Double, previous: Double) -> Double?`
  - `func chartBucket(forDays days: Double) -> ChartBucket` where `enum ChartBucket { case day, week }`
  - `func heatIntensity(units: Int, max: Int) -> Double`
  - `func sortRows(_ rows: [AnalyticsBreakdownRow], by metric: AnalyticsMetric) -> [AnalyticsBreakdownRow]`
  - `func dateRange(for preset: AnalyticsRangePreset, customFrom: Date, customTo: Date, calendar: Calendar, now: Date) -> (from: Date, to: Date)`

- [ ] **Step 1: Write the failing test**

Create `analytics_helpers_test.swift` in the scratchpad directory:

```swift
// Throwaway assertions for the pure helpers in ios/VMflow/Models/Analytics.swift.
// The Xcode project has no test target; this file is concatenated after
// Analytics.swift and run with `swift`.

func check(_ condition: Bool, _ message: String) {
    if !condition {
        print("FAIL: \(message)")
        exit(1)
    }
}

// ─── deltaPct ────────────────────────────────────────────────────────────────
check(deltaPct(current: 110, previous: 100) == 10, "deltaPct 110 vs 100 == 10")
check(deltaPct(current: 90, previous: 100) == -10, "deltaPct 90 vs 100 == -10")
check(deltaPct(current: 50, previous: 0) == nil, "deltaPct against zero baseline is nil")
check(deltaPct(current: 0, previous: 0) == nil, "deltaPct 0 vs 0 is nil")

// ─── chartBucket ─────────────────────────────────────────────────────────────
check(chartBucket(forDays: 7) == .day, "7 days uses daily bars")
check(chartBucket(forDays: 60) == .day, "60 days still uses daily bars")
check(chartBucket(forDays: 61) == .week, "61 days switches to weekly bars")
check(chartBucket(forDays: 365) == .week, "a year uses weekly bars")

// ─── heatIntensity ───────────────────────────────────────────────────────────
check(heatIntensity(units: 0, max: 10) == 0, "zero units means zero intensity")
check(heatIntensity(units: 10, max: 10) == 1, "max units means full intensity")
check(heatIntensity(units: 5, max: 10) == 0.5, "half units means half intensity")
check(heatIntensity(units: 3, max: 0) == 0, "zero max must not divide by zero")

// ─── sortRows ────────────────────────────────────────────────────────────────
let rows = [
    AnalyticsBreakdownRow.stub(label: "low",  units: 1,  revenue: 90, profit: 50),
    AnalyticsBreakdownRow.stub(label: "high", units: 20, revenue: 10, profit: 5),
]
check(sortRows(rows, by: .units).first?.label == "high", "sorting by units puts 20 first")
check(sortRows(rows, by: .revenue).first?.label == "low", "sorting by revenue puts 90 first")
check(sortRows(rows, by: .grossProfit).first?.label == "low", "sorting by profit puts 50 first")

// ─── dateRange ───────────────────────────────────────────────────────────────
var cal = Calendar(identifier: .gregorian)
cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
let fmt = DateFormatter()
fmt.calendar = cal
fmt.timeZone = cal.timeZone
fmt.dateFormat = "yyyy-MM-dd HH:mm"
let now = fmt.date(from: "2026-07-15 13:45")!

let r7 = dateRange(for: .days7, customFrom: now, customTo: now, calendar: cal, now: now)
check(fmt.string(from: r7.from) == "2026-07-09 00:00", "7 days starts 6 days before today's midnight")
check(fmt.string(from: r7.to) == "2026-07-16 00:00", "range end is the exclusive next midnight")

let rThis = dateRange(for: .thisMonth, customFrom: now, customTo: now, calendar: cal, now: now)
check(fmt.string(from: rThis.from) == "2026-07-01 00:00", "this month starts on the 1st")

let rLast = dateRange(for: .lastMonth, customFrom: now, customTo: now, calendar: cal, now: now)
check(fmt.string(from: rLast.from) == "2026-06-01 00:00", "last month starts on the 1st of June")
check(fmt.string(from: rLast.to) == "2026-07-01 00:00", "last month ends at the 1st of July")

let customFrom = fmt.date(from: "2026-03-05 09:00")!
let customTo = fmt.date(from: "2026-03-09 17:00")!
let rc = dateRange(for: .custom, customFrom: customFrom, customTo: customTo, calendar: cal, now: now)
check(fmt.string(from: rc.from) == "2026-03-05 00:00", "custom start snaps to midnight")
check(fmt.string(from: rc.to) == "2026-03-10 00:00", "custom end snaps to the next midnight (inclusive day)")

// ─── Decoding tolerates numeric-as-string ────────────────────────────────────
let json = """
{"key":"11111111-1111-1111-1111-111111111111","label":"Cola","image_path":null,
 "units":12,"revenue_gross":"18.00","revenue_net":15.13,"gross_profit":"5.78",
 "prev_units":3,"prev_revenue_gross":4.5,"prev_gross_profit":1.2,
 "share_pct":85.0,"cumulative_share_pct":85.0,"abc_class":"A",
 "avg_daily_units":1.2,"avg_daily_revenue":1.8,"avg_daily_gross_profit":0.58,
 "total_capacity":20,"total_stock":10,"sell_through_pct":42.0,"days_of_supply":8.3,
 "machine_count":2,"product_count":0,"has_cost":true}
""".data(using: .utf8)!
let decoded = try! JSONDecoder().decode(AnalyticsBreakdownRow.self, from: json)
check(decoded.revenue == 18.0, "revenue_gross decoded from a JSON string")
check(decoded.grossProfit == 5.78, "gross_profit decoded from a JSON string")
check(decoded.abcClass == "A", "abc_class decoded")
check(decoded.hasCost, "has_cost decoded")

print("PASS: all Analytics helper assertions passed")
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cat ios/VMflow/Models/Analytics.swift "$SCRATCH/analytics_helpers_test.swift" > "$SCRATCH/combined.swift" && swift "$SCRATCH/combined.swift"
```

Expected: FAIL — `cat: ios/VMflow/Models/Analytics.swift: No such file or directory`.

- [ ] **Step 3: Write `ios/VMflow/Models/Analytics.swift`**

```swift
import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// DTOs and pure helpers for the Analytics page.
//
// Mirrors the JSON contracts of the RPCs get_sales_analytics_summary and
// get_sales_analytics_breakdown (migrations 20260811000000 / 20260811000100).
//
// Numeric fields are decoded defensively: PostgREST may serialize a Postgres
// `numeric` as a JSON number or as a string depending on version, and a whole
// page going blank because of that is not an acceptable failure mode.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Enums

/// The single metric that drives BOTH the trend chart and the breakdown list.
/// There is deliberately no "average per day" metric: in a daily chart the
/// per-day average is the daily value, so it lives as a row subtitle instead.
enum AnalyticsMetric: String, CaseIterable, Identifiable {
    case units, revenue, grossProfit
    var id: String { rawValue }
}

enum AnalyticsDimension: String, CaseIterable, Identifiable {
    case product, category, machine
    var id: String { rawValue }
}

enum AnalyticsRangePreset: String, CaseIterable, Identifiable {
    case days7, days30, days90, thisMonth, lastMonth, custom
    var id: String { rawValue }
}

enum ChartBucket { case day, week }

// MARK: - Decoding helpers

// The container and its key must share one generic parameter — two separate
// `some CodingKey` positions would be independent opaque types and not compile.
private func flexDouble<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                      default fallback: Double = 0) -> Double {
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return d }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let d = Double(s) { return d }
    return fallback
}

private func flexDoubleOptional<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K) -> Double? {
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return d }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let d = Double(s) { return d }
    return nil
}

private func flexInt<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                   default fallback: Int = 0) -> Int {
    if let i = (try? container.decodeIfPresent(Int.self, forKey: key)) ?? nil { return i }
    if let d = (try? container.decodeIfPresent(Double.self, forKey: key)) ?? nil { return Int(d) }
    if let s = (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil,
       let i = Int(s) { return i }
    return fallback
}

private func flexString<K: CodingKey>(_ container: KeyedDecodingContainer<K>, _ key: K,
                                      default fallback: String) -> String {
    ((try? container.decodeIfPresent(String.self, forKey: key)) ?? nil) ?? fallback
}

/// `yyyy-MM-dd` as returned by a Postgres `date` inside json_build_object.
/// Parsed by hand rather than via a decoder strategy so the summary's
/// timestamptz fields and these plain dates can coexist.
private let isoDayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone.current
    f.dateFormat = "yyyy-MM-dd"
    return f
}()

// MARK: - Summary DTOs

struct AnalyticsRangeInfo: Decodable {
    let days: Double
    let timezone: String

    enum CodingKeys: String, CodingKey { case days, timezone }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        days = flexDouble(c, .days, default: 1)
        timezone = flexString(c, .timezone, default: "UTC")
    }
}

struct AnalyticsTotals: Decodable {
    let units: Int
    let revenueGross: Double
    let revenueNet: Double
    let costNet: Double
    let grossProfit: Double
    let avgTicket: Double
    let avgDailyUnits: Double
    let avgDailyRevenue: Double
    let avgDailyGrossProfit: Double

    enum CodingKeys: String, CodingKey {
        case units
        case revenueGross = "revenue_gross"
        case revenueNet = "revenue_net"
        case costNet = "cost_net"
        case grossProfit = "gross_profit"
        case avgTicket = "avg_ticket"
        case avgDailyUnits = "avg_daily_units"
        case avgDailyRevenue = "avg_daily_revenue"
        case avgDailyGrossProfit = "avg_daily_gross_profit"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        revenueNet = flexDouble(c, .revenueNet)
        costNet = flexDouble(c, .costNet)
        grossProfit = flexDouble(c, .grossProfit)
        avgTicket = flexDouble(c, .avgTicket)
        avgDailyUnits = flexDouble(c, .avgDailyUnits)
        avgDailyRevenue = flexDouble(c, .avgDailyRevenue)
        avgDailyGrossProfit = flexDouble(c, .avgDailyGrossProfit)
    }

    /// The value of the metric currently selected in the UI.
    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenueGross
        case .grossProfit: return grossProfit
        }
    }

    static let empty = AnalyticsTotals(
        units: 0, revenueGross: 0, revenueNet: 0, costNet: 0, grossProfit: 0,
        avgTicket: 0, avgDailyUnits: 0, avgDailyRevenue: 0, avgDailyGrossProfit: 0)

    private init(units: Int, revenueGross: Double, revenueNet: Double, costNet: Double,
                 grossProfit: Double, avgTicket: Double, avgDailyUnits: Double,
                 avgDailyRevenue: Double, avgDailyGrossProfit: Double) {
        self.units = units; self.revenueGross = revenueGross; self.revenueNet = revenueNet
        self.costNet = costNet; self.grossProfit = grossProfit; self.avgTicket = avgTicket
        self.avgDailyUnits = avgDailyUnits; self.avgDailyRevenue = avgDailyRevenue
        self.avgDailyGrossProfit = avgDailyGrossProfit
    }
}

struct AnalyticsDailyPoint: Decodable, Identifiable, Equatable {
    let day: Date
    let units: Int
    let revenueGross: Double
    let grossProfit: Double

    var id: Date { day }

    enum CodingKeys: String, CodingKey {
        case day, units
        case revenueGross = "revenue_gross"
        case grossProfit = "gross_profit"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let raw = try c.decode(String.self, forKey: .day)
        day = isoDayFormatter.date(from: raw) ?? Date(timeIntervalSince1970: 0)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        grossProfit = flexDouble(c, .grossProfit)
    }

    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenueGross
        case .grossProfit: return grossProfit
        }
    }

    var isWeekend: Bool { Calendar.current.isDateInWeekend(day) }
}

struct AnalyticsHeatCell: Decodable, Identifiable, Equatable {
    /// ISO weekday: 1 = Monday … 7 = Sunday.
    let dow: Int
    let hour: Int
    let units: Int
    let revenueGross: Double

    var id: Int { dow * 100 + hour }

    enum CodingKeys: String, CodingKey {
        case dow, hour, units
        case revenueGross = "revenue_gross"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dow = flexInt(c, .dow)
        hour = flexInt(c, .hour)
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
    }
}

struct AnalyticsChannel: Decodable, Identifiable, Equatable {
    let channel: String
    let units: Int
    let revenueGross: Double
    let avgTicket: Double

    var id: String { channel }

    enum CodingKeys: String, CodingKey {
        case channel, units
        case revenueGross = "revenue_gross"
        case avgTicket = "avg_ticket"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        channel = flexString(c, .channel, default: "unknown")
        units = flexInt(c, .units)
        revenueGross = flexDouble(c, .revenueGross)
        avgTicket = flexDouble(c, .avgTicket)
    }
}

struct AnalyticsSummary: Decodable {
    let range: AnalyticsRangeInfo
    let totals: AnalyticsTotals
    let previous: AnalyticsTotals
    let daily: [AnalyticsDailyPoint]
    let heatmap: [AnalyticsHeatCell]
    let channels: [AnalyticsChannel]
    let missingCostProducts: Int
    let unknownProductUnits: Int

    enum CodingKeys: String, CodingKey {
        case range, totals, previous, daily, heatmap, channels
        case missingCostProducts = "missing_cost_products"
        case unknownProductUnits = "unknown_product_units"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        range = try c.decode(AnalyticsRangeInfo.self, forKey: .range)
        totals = try c.decode(AnalyticsTotals.self, forKey: .totals)
        previous = try c.decode(AnalyticsTotals.self, forKey: .previous)
        daily = (try? c.decode([AnalyticsDailyPoint].self, forKey: .daily)) ?? []
        heatmap = (try? c.decode([AnalyticsHeatCell].self, forKey: .heatmap)) ?? []
        channels = (try? c.decode([AnalyticsChannel].self, forKey: .channels)) ?? []
        missingCostProducts = flexInt(c, .missingCostProducts)
        unknownProductUnits = flexInt(c, .unknownProductUnits)
    }
}

// MARK: - Breakdown DTO

struct AnalyticsBreakdownRow: Decodable, Identifiable, Equatable {
    /// NULL for the aggregate "Unknown" row (sales whose product could not be resolved).
    let key: UUID?
    let label: String
    let imagePath: String?
    let units: Int
    let revenue: Double
    let revenueNet: Double
    let grossProfit: Double
    let prevUnits: Int
    let prevRevenue: Double
    let prevGrossProfit: Double
    let sharePct: Double
    let cumulativeSharePct: Double
    let abcClass: String
    let avgDailyUnits: Double
    let avgDailyRevenue: Double
    let avgDailyGrossProfit: Double
    let totalCapacity: Int
    let totalStock: Int
    let sellThroughPct: Double?
    let daysOfSupply: Double?
    let machineCount: Int
    let productCount: Int
    let hasCost: Bool

    var id: String { key?.uuidString ?? "unknown-\(label)" }

    enum CodingKeys: String, CodingKey {
        case key, label, units
        case imagePath = "image_path"
        case revenue = "revenue_gross"
        case revenueNet = "revenue_net"
        case grossProfit = "gross_profit"
        case prevUnits = "prev_units"
        case prevRevenue = "prev_revenue_gross"
        case prevGrossProfit = "prev_gross_profit"
        case sharePct = "share_pct"
        case cumulativeSharePct = "cumulative_share_pct"
        case abcClass = "abc_class"
        case avgDailyUnits = "avg_daily_units"
        case avgDailyRevenue = "avg_daily_revenue"
        case avgDailyGrossProfit = "avg_daily_gross_profit"
        case totalCapacity = "total_capacity"
        case totalStock = "total_stock"
        case sellThroughPct = "sell_through_pct"
        case daysOfSupply = "days_of_supply"
        case machineCount = "machine_count"
        case productCount = "product_count"
        case hasCost = "has_cost"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = (try? c.decodeIfPresent(UUID.self, forKey: .key)) ?? nil
        label = (try? c.decode(String.self, forKey: .label)) ?? "Unknown"
        imagePath = (try? c.decodeIfPresent(String.self, forKey: .imagePath)) ?? nil
        units = flexInt(c, .units)
        revenue = flexDouble(c, .revenue)
        revenueNet = flexDouble(c, .revenueNet)
        grossProfit = flexDouble(c, .grossProfit)
        prevUnits = flexInt(c, .prevUnits)
        prevRevenue = flexDouble(c, .prevRevenue)
        prevGrossProfit = flexDouble(c, .prevGrossProfit)
        sharePct = flexDouble(c, .sharePct)
        cumulativeSharePct = flexDouble(c, .cumulativeSharePct)
        abcClass = flexString(c, .abcClass, default: "C")
        avgDailyUnits = flexDouble(c, .avgDailyUnits)
        avgDailyRevenue = flexDouble(c, .avgDailyRevenue)
        avgDailyGrossProfit = flexDouble(c, .avgDailyGrossProfit)
        totalCapacity = flexInt(c, .totalCapacity)
        totalStock = flexInt(c, .totalStock)
        sellThroughPct = flexDoubleOptional(c, .sellThroughPct)
        daysOfSupply = flexDoubleOptional(c, .daysOfSupply)
        machineCount = flexInt(c, .machineCount)
        productCount = flexInt(c, .productCount)
        hasCost = ((try? c.decodeIfPresent(Bool.self, forKey: .hasCost)) ?? nil) ?? false
    }

    func value(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(units)
        case .revenue: return revenue
        case .grossProfit: return grossProfit
        }
    }

    func previousValue(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return Double(prevUnits)
        case .revenue: return prevRevenue
        case .grossProfit: return prevGrossProfit
        }
    }

    func avgDaily(for metric: AnalyticsMetric) -> Double {
        switch metric {
        case .units: return avgDailyUnits
        case .revenue: return avgDailyRevenue
        case .grossProfit: return avgDailyGrossProfit
        }
    }

    // Memberwise initialiser for tests and previews.
    init(key: UUID?, label: String, imagePath: String? = nil, units: Int, revenue: Double,
         revenueNet: Double = 0, grossProfit: Double, prevUnits: Int = 0, prevRevenue: Double = 0,
         prevGrossProfit: Double = 0, sharePct: Double = 0, cumulativeSharePct: Double = 0,
         abcClass: String = "C", avgDailyUnits: Double = 0, avgDailyRevenue: Double = 0,
         avgDailyGrossProfit: Double = 0, totalCapacity: Int = 0, totalStock: Int = 0,
         sellThroughPct: Double? = nil, daysOfSupply: Double? = nil, machineCount: Int = 0,
         productCount: Int = 0, hasCost: Bool = true) {
        self.key = key; self.label = label; self.imagePath = imagePath; self.units = units
        self.revenue = revenue; self.revenueNet = revenueNet; self.grossProfit = grossProfit
        self.prevUnits = prevUnits; self.prevRevenue = prevRevenue
        self.prevGrossProfit = prevGrossProfit; self.sharePct = sharePct
        self.cumulativeSharePct = cumulativeSharePct; self.abcClass = abcClass
        self.avgDailyUnits = avgDailyUnits; self.avgDailyRevenue = avgDailyRevenue
        self.avgDailyGrossProfit = avgDailyGrossProfit; self.totalCapacity = totalCapacity
        self.totalStock = totalStock; self.sellThroughPct = sellThroughPct
        self.daysOfSupply = daysOfSupply; self.machineCount = machineCount
        self.productCount = productCount; self.hasCost = hasCost
    }

    static func stub(label: String, units: Int, revenue: Double, profit: Double) -> AnalyticsBreakdownRow {
        AnalyticsBreakdownRow(key: UUID(), label: label, units: units,
                              revenue: revenue, grossProfit: profit)
    }
}

// MARK: - Pure helpers

/// Percentage change against the previous period. `nil` when the baseline is
/// zero — "+∞ %" is not a number a user can act on, so the UI shows nothing.
func deltaPct(current: Double, previous: Double) -> Double? {
    guard previous != 0 else { return nil }
    return (current - previous) / abs(previous) * 100
}

/// Daily bars stay readable up to about two months; beyond that they turn into
/// unreadable hairlines, so the chart switches to weekly buckets.
func chartBucket(forDays days: Double) -> ChartBucket {
    days > 60 ? .week : .day
}

/// 0…1 colour intensity for a heatmap cell.
func heatIntensity(units: Int, max: Int) -> Double {
    guard max > 0 else { return 0 }
    return min(Double(units) / Double(max), 1)
}

/// Sorts breakdown rows by the currently selected metric, descending. The RPC
/// returns them revenue-sorted; switching the metric must reorder client-side
/// rather than trigger another round trip.
func sortRows(_ rows: [AnalyticsBreakdownRow], by metric: AnalyticsMetric) -> [AnalyticsBreakdownRow] {
    rows.sorted {
        let l = $0.value(for: metric), r = $1.value(for: metric)
        if l != r { return l > r }
        return $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending
    }
}

/// Resolves a preset into a half-open `[from, to)` window on local day
/// boundaries. `to` is the exclusive midnight *after* the last included day —
/// the RPC filters `created_at < p_to`, so an inclusive end would silently
/// drop the last day's sales.
func dateRange(for preset: AnalyticsRangePreset, customFrom: Date, customTo: Date,
               calendar: Calendar = .current, now: Date = Date()) -> (from: Date, to: Date) {
    let startOfToday = calendar.startOfDay(for: now)
    let tomorrow = calendar.date(byAdding: .day, value: 1, to: startOfToday)!

    switch preset {
    case .days7:
        return (calendar.date(byAdding: .day, value: -6, to: startOfToday)!, tomorrow)
    case .days30:
        return (calendar.date(byAdding: .day, value: -29, to: startOfToday)!, tomorrow)
    case .days90:
        return (calendar.date(byAdding: .day, value: -89, to: startOfToday)!, tomorrow)
    case .thisMonth:
        let start = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        return (start, tomorrow)
    case .lastMonth:
        let thisMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        let start = calendar.date(byAdding: .month, value: -1, to: thisMonth)!
        return (start, thisMonth)
    case .custom:
        let from = calendar.startOfDay(for: min(customFrom, customTo))
        let toDay = calendar.startOfDay(for: max(customFrom, customTo))
        return (from, calendar.date(byAdding: .day, value: 1, to: toDay)!)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cat ios/VMflow/Models/Analytics.swift "$SCRATCH/analytics_helpers_test.swift" > "$SCRATCH/combined.swift" && swift "$SCRATCH/combined.swift"
```

Expected: `PASS: all Analytics helper assertions passed`

- [ ] **Step 5: Register the file in `project.pbxproj`**

Generate two fresh 24-character uppercase hex IDs (they must not already appear in the file):

```bash
python3 -c "import secrets;print(secrets.token_hex(12).upper());print(secrets.token_hex(12).upper())"
```

Call them `<BUILD_ID>` and `<FILE_ID>`. Make four edits to `ios/VMflow.xcodeproj/project.pbxproj`:

1. In the `PBXBuildFile` section (near the other `… in Sources */ = {isa = PBXBuildFile;` lines), add:
   ```
   		<BUILD_ID> /* Analytics.swift in Sources */ = {isa = PBXBuildFile; fileRef = <FILE_ID> /* Analytics.swift */; };
   ```
2. In the `PBXFileReference` section, add:
   ```
   		<FILE_ID> /* Analytics.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = Analytics.swift; sourceTree = "<group>"; };
   ```
3. In the `Models` `PBXGroup`'s `children = (` list (find it with `grep -n '/\* Models \*/ = {' ios/VMflow.xcodeproj/project.pbxproj`), add:
   ```
   				<FILE_ID> /* Analytics.swift */,
   ```
4. In the `Sources` build phase's `files = (` list (the one containing `ExpenseSheet.swift in Sources`), add:
   ```
   				<BUILD_ID> /* Analytics.swift in Sources */,
   ```

- [ ] **Step 6: Verify the project still builds**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 7: Commit**

```bash
git add ios/VMflow/Models/Analytics.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics DTOs and pure helpers"
```

---

## Task 4: iOS — view model, navigation entry, KPI header

**Files:**
- Create: `ios/VMflow/ViewModels/AnalyticsViewModel.swift`
- Create: `ios/VMflow/Views/Analytics/AnalyticsView.swift`
- Modify: `ios/VMflow/Navigation/AppNavigation.swift`
- Modify: `ios/VMflow/VMflowApp.swift` (MoreView list + `navigationDestination`)
- Modify: `ios/VMflow/Navigation/SidebarNavigationView.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AnalyticsSummary`, `AnalyticsBreakdownRow`, `AnalyticsMetric`, `AnalyticsDimension`, `AnalyticsRangePreset`, `dateRange(for:customFrom:customTo:calendar:now:)`, `deltaPct(current:previous:)` (Task 3); `SupabaseService.shared.client`.
- Produces:
  - `@MainActor final class AnalyticsViewModel: ObservableObject` with published `summary`, `rows`, `preset`, `customFrom`, `customTo`, `selectedMachineIds: Set<UUID>`, `selectedCategoryIds: Set<UUID>`, `metric`, `dimension`, `machines: [VendingMachine]`, `categories: [ProductCategory]`, `isLoading`, `isLoadingRows`, `error`, `backendUnsupported`, `didRunInitialLoad`
  - `func load() async`, `func loadBreakdown() async`, `func loadFilterOptions() async`
  - `var sortedRows: [AnalyticsBreakdownRow]`, `var rangeLabel: String`, `var previousRangeLabel: String`
  - `SidebarItem.analytics`

- [ ] **Step 1: Write `ios/VMflow/ViewModels/AnalyticsViewModel.swift`**

```swift
import Foundation
import Supabase

/// Drives the Analytics page: filter state, the two RPC round trips, and the
/// distinction between "this failed" and "this backend is too old".
@MainActor
final class AnalyticsViewModel: ObservableObject {
    // MARK: - Filter state

    @Published var preset: AnalyticsRangePreset = .days30 { didSet { rangeDidChange() } }
    @Published var customFrom: Date = Calendar.current.date(byAdding: .day, value: -29, to: Date())!
    @Published var customTo: Date = Date()
    @Published var selectedMachineIds: Set<UUID> = []
    @Published var selectedCategoryIds: Set<UUID> = []

    /// Shared by the trend chart and the breakdown list — switching either
    /// segment switches both, by design.
    @Published var metric: AnalyticsMetric = .revenue
    @Published var dimension: AnalyticsDimension = .product { didSet { dimensionDidChange() } }

    // MARK: - Data

    @Published var summary: AnalyticsSummary?
    @Published var rows: [AnalyticsBreakdownRow] = []
    @Published var machines: [VendingMachine] = []
    @Published var categories: [ProductCategory] = []

    @Published var isLoading = false
    @Published var isLoadingRows = false
    @Published var error: String?

    /// True when the connected server predates the analytics migrations. The
    /// whole page depends on the RPCs, so this must be visible rather than
    /// silently swallowed the way the dashboard treats get_new_deals_count.
    @Published var backendUnsupported = false

    /// Guards the tab-root `.task` against re-firing on every tab re-selection.
    @Published var didRunInitialLoad = false

    private let client = SupabaseService.shared.client
    private var companyId: UUID?

    // MARK: - Derived

    var sortedRows: [AnalyticsBreakdownRow] { sortRows(rows, by: metric) }

    var range: (from: Date, to: Date) {
        dateRange(for: preset, customFrom: customFrom, customTo: customTo)
    }

    var rangeLabel: String {
        let r = range
        let lastDay = Calendar.current.date(byAdding: .day, value: -1, to: r.to) ?? r.to
        return "\(Self.dayFormatter.string(from: r.from)) – \(Self.dayFormatter.string(from: lastDay))"
    }

    /// The previous window is `[from - span, from)` — not "last month". Spelled
    /// out in the UI so nobody mistakes it for a calendar period.
    var previousRangeLabel: String {
        let r = range
        let span = r.to.timeIntervalSince(r.from)
        let prevFrom = r.from.addingTimeInterval(-span)
        let prevLast = Calendar.current.date(byAdding: .day, value: -1, to: r.from) ?? r.from
        return "\(Self.dayFormatter.string(from: prevFrom)) – \(Self.dayFormatter.string(from: prevLast))"
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .none
        return f
    }()

    // MARK: - Loading

    func load() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let company = try await resolveCompanyId()
            summary = try await client
                .rpc("get_sales_analytics_summary", params: rpcParams(companyId: company))
                .execute()
                .value
            backendUnsupported = false
        } catch is CancellationError {
        } catch {
            handle(error)
        }
        await loadBreakdown()
    }

    func loadBreakdown() async {
        isLoadingRows = true
        defer { isLoadingRows = false }
        do {
            let company = try await resolveCompanyId()
            var params = rpcParams(companyId: company)
            params["p_dimension"] = .string(dimension.rawValue)
            params["p_product_id"] = .null
            rows = try await client
                .rpc("get_sales_analytics_breakdown", params: params)
                .execute()
                .value
            backendUnsupported = false
        } catch is CancellationError {
        } catch {
            handle(error)
        }
    }

    /// Per-machine distribution of one product — the detail sheet reuses the
    /// breakdown RPC with the machine dimension narrowed to a single product.
    func loadProductMachines(productId: UUID) async -> [AnalyticsBreakdownRow] {
        do {
            let company = try await resolveCompanyId()
            var params = rpcParams(companyId: company)
            params["p_dimension"] = .string(AnalyticsDimension.machine.rawValue)
            params["p_product_id"] = .string(productId.uuidString)
            return try await client
                .rpc("get_sales_analytics_breakdown", params: params)
                .execute()
                .value
        } catch {
            return []
        }
    }

    func loadFilterOptions() async {
        do {
            let company = try await resolveCompanyId()
            async let machineTask: [VendingMachine] = client
                .from("vendingMachine")
                .select("id, name")
                .eq("company", value: company.uuidString)
                .order("name")
                .execute()
                .value
            async let categoryTask: [ProductCategory] = client
                .from("product_category")
                .select("id, name, company")
                .eq("company", value: company.uuidString)
                .order("name")
                .execute()
                .value
            let (m, c) = try await (machineTask, categoryTask)
            machines = m
            categories = c
        } catch is CancellationError {
        } catch {
            // Non-fatal: the page still works with "all machines / all categories".
        }
    }

    // MARK: - Reactions

    private func rangeDidChange() {
        Task { await load() }
    }

    private func dimensionDidChange() {
        Task { await loadBreakdown() }
    }

    /// Called by the filter sheets after the user commits a selection, so a
    /// multi-select does not fire one round trip per tap.
    func filtersCommitted() {
        Task { await load() }
    }

    // MARK: - Helpers

    private func rpcParams(companyId: UUID) -> [String: AnyJSON] {
        let r = range
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return [
            "p_company_id": .string(companyId.uuidString),
            "p_from": .string(formatter.string(from: r.from)),
            "p_to": .string(formatter.string(from: r.to)),
            "p_machine_ids": selectedMachineIds.isEmpty
                ? .null : .array(selectedMachineIds.map { .string($0.uuidString) }),
            "p_category_ids": selectedCategoryIds.isEmpty
                ? .null : .array(selectedCategoryIds.map { .string($0.uuidString) }),
            "p_timezone": .string(TimeZone.current.identifier),
        ]
    }

    /// PostgREST answers an unknown function with 404 / PGRST202. That is a
    /// backend-version problem, not a bug, and gets its own screen.
    private func handle(_ error: Error) {
        let text = "\(error)"
        if text.contains("PGRST202") || text.contains("Could not find the function") {
            backendUnsupported = true
            self.error = nil
        } else {
            self.error = error.localizedDescription
        }
    }

    private func resolveCompanyId() async throws -> UUID {
        if let companyId { return companyId }
        let userId = try await client.auth.session.user.id
        struct OrgMember: Decodable {
            let companyId: UUID
            enum CodingKeys: String, CodingKey { case companyId = "company_id" }
        }
        let members: [OrgMember] = try await client
            .from("organization_members")
            .select("company_id")
            .eq("user_id", value: userId.uuidString)
            .limit(1)
            .execute()
            .value
        guard let id = members.first?.companyId else {
            throw NSError(domain: "AnalyticsVM", code: 0, userInfo: [
                NSLocalizedDescriptionKey: String(localized: "Could not determine company")
            ])
        }
        companyId = id
        return id
    }
}
```

- [ ] **Step 2: Write `ios/VMflow/Views/Analytics/AnalyticsView.swift`**

Filter bar, chart, breakdown, heatmap and channel split arrive in Tasks 5–7; this task delivers a reachable page with a working KPI header.

```swift
import SwiftUI

/// Cross-fleet sales analytics. The page is a report frame (filters, KPIs,
/// trend) over a switchable breakdown block.
struct AnalyticsView: View {
    @StateObject private var viewModel = AnalyticsViewModel()

    var body: some View {
        Group {
            if viewModel.backendUnsupported {
                ContentUnavailableView {
                    Label(String(localized: "Analytics not available"), systemImage: "server.rack")
                } description: {
                    Text("This server does not support analytics yet. Please update the backend.")
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        kpiRow
                        costHint
                    }
                    .padding()
                }
            }
        }
        .navigationTitle(String(localized: "Analytics"))
        .navigationBarTitleDisplayMode(.large)
        .overlay {
            if viewModel.isLoading && viewModel.summary == nil {
                ProgressView()
            }
        }
        .alert(String(localized: "Error"), isPresented: .constant(viewModel.error != nil)) {
            Button(String(localized: "OK")) { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
        .dataRefreshable { await viewModel.load() }
        .task {
            // Tab roots re-run `.task` on every re-selection; load only once.
            guard !viewModel.didRunInitialLoad else { return }
            viewModel.didRunInitialLoad = true
            await viewModel.loadFilterOptions()
            await viewModel.load()
        }
    }

    // MARK: - KPI row

    private var kpiRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                kpiCard(.revenue, icon: "eurosign.circle.fill", title: "Revenue", color: .blue)
                kpiCard(.units, icon: "cart.fill", title: "Units", color: .green)
                kpiCard(.grossProfit, icon: "chart.line.uptrend.xyaxis", title: "Gross profit", color: .purple)
            }
            Text("vs. \(viewModel.previousRangeLabel)")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func kpiCard(_ metric: AnalyticsMetric, icon: String,
                         title: LocalizedStringKey, color: Color) -> some View {
        let totals = viewModel.summary?.totals ?? .empty
        let previous = viewModel.summary?.previous ?? .empty
        let delta = deltaPct(current: totals.value(for: metric),
                             previous: previous.value(for: metric))
        return KPICard(
            icon: icon,
            title: title,
            value: format(totals.value(for: metric), metric: metric),
            subtitle: delta.map { LocalizedStringKey(String(format: "%+.0f %%", $0)) },
            color: viewModel.metric == metric ? color : .secondary
        )
    }

    @ViewBuilder
    private var costHint: some View {
        if let missing = viewModel.summary?.missingCostProducts, missing > 0 {
            Label {
                Text("Gross profit is net of tax. \(missing) products have no purchase price and are excluded.")
            } icon: {
                Image(systemName: "info.circle")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func format(_ value: Double, metric: AnalyticsMetric) -> String {
        switch metric {
        case .units: return String(Int(value))
        case .revenue, .grossProfit: return String(format: "%.2f €", value)
        }
    }
}

#Preview {
    NavigationStack { AnalyticsView() }
}
```

- [ ] **Step 3: Add the navigation entry**

In `ios/VMflow/Navigation/AppNavigation.swift`, add `case analytics` to `SidebarItem` right after `case dashboard`, and add the three matching switch arms:

```swift
enum SidebarItem: String, Hashable, CaseIterable, Identifiable {
    case dashboard
    case analytics
    case machines
    // … unchanged …
```

```swift
        case .dashboard: "Dashboard"
        case .analytics: NSLocalizedString("Analytics", comment: "")
```

```swift
        case .dashboard: "chart.bar.fill"
        case .analytics: "chart.xyaxis.line"
```

`compactTab` needs no new arm — `analytics` falls into the existing `default: nil`, which is what puts it under "More". Update that comment:

```swift
        default: nil  // analytics, inbox, cashBook, products, deals, settings → More tab
```

- [ ] **Step 4: Add the entry to MoreView and its destination**

In `ios/VMflow/VMflowApp.swift`, inside `MoreView`'s first `Section`, add as the **first** row:

```swift
                    NavigationLink {
                        AnalyticsView()
                    } label: {
                        Label(String(localized: "Analytics"), systemImage: "chart.xyaxis.line")
                    }
```

And add the matching arm to the `navigationDestination(item: $deepLink)` switch:

```swift
                case .analytics: AnalyticsView()
```

- [ ] **Step 5: Add the entry to the iPad sidebar**

In `ios/VMflow/Navigation/SidebarNavigationView.swift`, find the switch that maps a `SidebarItem` to its destination view and add:

```swift
            case .analytics: AnalyticsView()
```

- [ ] **Step 6: Register both new files in `project.pbxproj`**

Generate four fresh IDs plus one for the new group:

```bash
python3 -c "import secrets;[print(secrets.token_hex(12).upper()) for _ in range(5)]"
```

For `AnalyticsViewModel.swift`, repeat the four edits from Task 3 Step 5 (group: `ViewModels`).

For `AnalyticsView.swift` a new `Analytics` group is needed as well — five edits:

1. `PBXBuildFile` entry for `AnalyticsView.swift in Sources`
2. `PBXFileReference` entry for `AnalyticsView.swift`
3. A new `PBXGroup` next to the other view groups:
   ```
   		<GROUP_ID> /* Analytics */ = {
   			isa = PBXGroup;
   			children = (
   				<FILE_ID> /* AnalyticsView.swift */,
   			);
   			path = Analytics;
   			sourceTree = "<group>";
   		};
   ```
4. Add `<GROUP_ID> /* Analytics */,` to the `Views` group's `children` list (alphabetically, before `Auth`)
5. Add the build-file entry to the `Sources` phase `files = (` list

- [ ] **Step 7: Build and run in the simulator**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

Then launch it, open **More → Analytics**, and confirm the three KPI cards show non-zero values for the last 30 days. Take a screenshot as evidence.

- [ ] **Step 8: Commit**

```bash
git add ios/VMflow/ViewModels/AnalyticsViewModel.swift ios/VMflow/Views/Analytics/AnalyticsView.swift ios/VMflow/Navigation/AppNavigation.swift ios/VMflow/Navigation/SidebarNavigationView.swift ios/VMflow/VMflowApp.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics page with KPI header"
```

## Task 5: iOS — filter bar and selection sheets

**Files:**
- Create: `ios/VMflow/Views/Analytics/AnalyticsFilterBar.swift`
- Modify: `ios/VMflow/Views/Analytics/AnalyticsView.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AnalyticsViewModel` (Task 4), `AnalyticsRangePreset`, `dateRange(...)` (Task 3).
- Produces: `struct AnalyticsFilterBar: View` taking `@ObservedObject var viewModel: AnalyticsViewModel`.

- [ ] **Step 1: Write `ios/VMflow/Views/Analytics/AnalyticsFilterBar.swift`**

```swift
import SwiftUI

/// Three chips — time range, machines, categories — each opening its own sheet.
/// Selections are committed on dismiss rather than on every tap, so a
/// multi-select does not fire one RPC round trip per checkbox.
struct AnalyticsFilterBar: View {
    @ObservedObject var viewModel: AnalyticsViewModel

    @State private var showRange = false
    @State private var showMachines = false
    @State private var showCategories = false

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(viewModel.rangeLabel, systemImage: "calendar", isActive: true) { showRange = true }
                chip(machineLabel, systemImage: "storefront",
                     isActive: !viewModel.selectedMachineIds.isEmpty) { showMachines = true }
                chip(categoryLabel, systemImage: "square.grid.2x2",
                     isActive: !viewModel.selectedCategoryIds.isEmpty) { showCategories = true }
            }
            .padding(.horizontal, 2)
        }
        .sheet(isPresented: $showRange) {
            AnalyticsRangeSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $showMachines, onDismiss: { viewModel.filtersCommitted() }) {
            AnalyticsMultiSelectSheet(
                title: String(localized: "Machines"),
                options: viewModel.machines.map { ($0.id, $0.name ?? String(localized: "Unnamed")) },
                selection: $viewModel.selectedMachineIds,
                allLabel: String(localized: "All machines"))
        }
        .sheet(isPresented: $showCategories, onDismiss: { viewModel.filtersCommitted() }) {
            AnalyticsMultiSelectSheet(
                title: String(localized: "Categories"),
                options: viewModel.categories.map { ($0.id, $0.name) },
                selection: $viewModel.selectedCategoryIds,
                allLabel: String(localized: "All categories"))
        }
    }

    private var machineLabel: String {
        let n = viewModel.selectedMachineIds.count
        if n == 0 { return String(localized: "All machines") }
        if n == 1, let id = viewModel.selectedMachineIds.first,
           let m = viewModel.machines.first(where: { $0.id == id }) {
            return m.name ?? String(localized: "Unnamed")
        }
        return String(format: String(localized: "%d machines"), n)
    }

    private var categoryLabel: String {
        let n = viewModel.selectedCategoryIds.count
        if n == 0 { return String(localized: "All categories") }
        if n == 1, let id = viewModel.selectedCategoryIds.first,
           let c = viewModel.categories.first(where: { $0.id == id }) {
            return c.name
        }
        return String(format: String(localized: "%d categories"), n)
    }

    private func chip(_ text: String, systemImage: String, isActive: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage).font(.caption2)
                Text(text).font(.caption).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(isActive ? Color.accentColor.opacity(0.15) : Color(.secondarySystemBackground))
            .foregroundStyle(isActive ? Color.accentColor : Color.primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Preset list plus a custom two-date range.
private struct AnalyticsRangeSheet: View {
    @ObservedObject var viewModel: AnalyticsViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(AnalyticsRangePreset.allCases.filter { $0 != .custom }) { preset in
                        Button {
                            viewModel.preset = preset
                            dismiss()
                        } label: {
                            HStack {
                                Text(label(for: preset))
                                Spacer()
                                if viewModel.preset == preset {
                                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }

                Section(String(localized: "Custom range")) {
                    DatePicker(String(localized: "From"), selection: $viewModel.customFrom,
                               displayedComponents: .date)
                    DatePicker(String(localized: "To"), selection: $viewModel.customTo,
                               in: viewModel.customFrom..., displayedComponents: .date)
                    Button(String(localized: "Apply custom range")) {
                        viewModel.preset = .custom
                        dismiss()
                    }
                }
            }
            .navigationTitle(String(localized: "Time range"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func label(for preset: AnalyticsRangePreset) -> String {
        switch preset {
        case .days7: return String(localized: "Last 7 days")
        case .days30: return String(localized: "Last 30 days")
        case .days90: return String(localized: "Last 90 days")
        case .thisMonth: return String(localized: "This month")
        case .lastMonth: return String(localized: "Last month")
        case .custom: return String(localized: "Custom range")
        }
    }
}

/// Generic multi-select with an explicit "all" state (empty selection).
private struct AnalyticsMultiSelectSheet: View {
    let title: String
    let options: [(UUID, String)]
    @Binding var selection: Set<UUID>
    let allLabel: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button {
                    selection.removeAll()
                } label: {
                    HStack {
                        Text(allLabel)
                        Spacer()
                        if selection.isEmpty {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .foregroundStyle(.primary)

                ForEach(options, id: \.0) { id, name in
                    Button {
                        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
                    } label: {
                        HStack {
                            Text(name)
                            Spacer()
                            if selection.contains(id) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
```

- [ ] **Step 2: Mount the filter bar in `AnalyticsView`**

In `ios/VMflow/Views/Analytics/AnalyticsView.swift`, replace the `VStack` contents so the filter bar sits above the KPI row:

```swift
                    VStack(alignment: .leading, spacing: 16) {
                        AnalyticsFilterBar(viewModel: viewModel)
                        kpiRow
                        costHint
                    }
                    .padding()
```

- [ ] **Step 3: Register the file in `project.pbxproj`**

Four edits as in Task 3 Step 5 (group: the `Analytics` group created in Task 4).

- [ ] **Step 4: Build and verify**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

In the simulator: open Analytics, switch the range chip to "Last 7 days" and confirm the KPI values change; open the machine chip, select a single machine, dismiss, and confirm the KPIs drop accordingly.

- [ ] **Step 5: Commit**

```bash
git add ios/VMflow/Views/Analytics/AnalyticsFilterBar.swift ios/VMflow/Views/Analytics/AnalyticsView.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics filter bar with range and multi-select sheets"
```

---

## Task 6: iOS — trend chart and breakdown list

**Files:**
- Create: `ios/VMflow/Views/Analytics/AnalyticsBreakdownList.swift`
- Modify: `ios/VMflow/Views/Analytics/AnalyticsView.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AnalyticsViewModel`, `AnalyticsMetric`, `AnalyticsDimension`, `AnalyticsBreakdownRow`, `chartBucket(forDays:)`, `deltaPct(current:previous:)`.
- Produces: `struct AnalyticsBreakdownList: View`, `struct AnalyticsMetricPicker: View`, and on `AnalyticsView` a `trendChart` section. `AnalyticsBreakdownList` exposes `var onSelectProduct: (AnalyticsBreakdownRow) -> Void` used by Task 8.

- [ ] **Step 1: Write `ios/VMflow/Views/Analytics/AnalyticsBreakdownList.swift`**

```swift
import SwiftUI

/// The shared metric segment. Rendered twice — above the chart and above the
/// breakdown — bound to the same view-model property, so switching one
/// switches the other.
struct AnalyticsMetricPicker: View {
    @Binding var metric: AnalyticsMetric

    var body: some View {
        Picker("", selection: $metric) {
            Text("Units").tag(AnalyticsMetric.units)
            Text("Revenue").tag(AnalyticsMetric.revenue)
            Text("Profit").tag(AnalyticsMetric.grossProfit)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

/// Dimension segment plus the sorted rows. Each row carries a share bar drawn
/// from the SELECTED metric — deliberately different from `share_pct`, which
/// is always revenue-based because it backs the ABC class.
struct AnalyticsBreakdownList: View {
    @ObservedObject var viewModel: AnalyticsViewModel
    var onSelectProduct: (AnalyticsBreakdownRow) -> Void

    private var rows: [AnalyticsBreakdownRow] { viewModel.sortedRows }

    private var maxValue: Double {
        rows.map { $0.value(for: viewModel.metric) }.max() ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Breakdown")
                .font(.caption).textCase(.uppercase)
                .foregroundStyle(.secondary)

            Picker("", selection: $viewModel.dimension) {
                Text("Products").tag(AnalyticsDimension.product)
                Text("Categories").tag(AnalyticsDimension.category)
                Text("Machines").tag(AnalyticsDimension.machine)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            AnalyticsMetricPicker(metric: $viewModel.metric)

            if viewModel.isLoadingRows && rows.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
            } else if rows.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 24)
            } else {
                LazyVStack(spacing: 2) {
                    ForEach(rows) { row in
                        Button {
                            guard viewModel.dimension == .product, row.key != nil else { return }
                            onSelectProduct(row)
                        } label: {
                            rowView(row)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.dimension != .product || row.key == nil)
                    }
                }
            }
        }
        .padding(14)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.regularMaterial)
        }
    }

    private func rowView(_ row: AnalyticsBreakdownRow) -> some View {
        let value = row.value(for: viewModel.metric)
        let delta = deltaPct(current: value, previous: row.previousValue(for: viewModel.metric))
        return HStack(spacing: 9) {
            if viewModel.dimension == .product {
                ProductImage(imagePath: row.imagePath, size: 30)
            }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 5) {
                    if viewModel.dimension == .product {
                        Text(row.abcClass)
                            .font(.system(size: 9, weight: .bold))
                            .padding(.horizontal, 4).padding(.vertical, 1)
                            .background(abcColor(row.abcClass).opacity(0.18))
                            .foregroundStyle(abcColor(row.abcClass))
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                    Text(row.label).font(.subheadline.weight(.medium)).lineLimit(1)
                }
                Text(subtitle(row))
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 1) {
                Text(format(value))
                    .font(.subheadline.weight(.semibold)).monospacedDigit()
                if let delta {
                    Text(String(format: "%+.0f %%", delta))
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(delta >= 0 ? .green : .red)
                }
            }
        }
        .padding(.horizontal, 7).padding(.vertical, 7)
        .background(alignment: .leading) {
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 7)
                    .fill(Color.accentColor.opacity(0.10))
                    .frame(width: maxValue > 0 ? geo.size.width * value / maxValue : 0)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 7))
        .contentShape(Rectangle())
    }

    private func subtitle(_ row: AnalyticsBreakdownRow) -> String {
        var parts: [String] = []
        parts.append(String(format: String(localized: "%@ /day"), format(row.avgDaily(for: viewModel.metric))))
        if viewModel.metric != .units {
            parts.append(String(format: String(localized: "%d pcs"), row.units))
        }
        if !row.hasCost && viewModel.metric == .grossProfit {
            parts.append(String(localized: "no purchase price"))
        }
        switch viewModel.dimension {
        case .product where row.machineCount > 0:
            parts.append(String(format: String(localized: "%d machines"), row.machineCount))
        case .category, .machine:
            if row.productCount > 0 {
                parts.append(String(format: String(localized: "%d products"), row.productCount))
            }
        default: break
        }
        return parts.joined(separator: " · ")
    }

    private func format(_ value: Double) -> String {
        switch viewModel.metric {
        case .units: return value < 10 ? String(format: "%.1f", value) : String(Int(value.rounded()))
        case .revenue, .grossProfit: return String(format: "%.2f €", value)
        }
    }

    private func abcColor(_ cls: String) -> Color {
        switch cls {
        case "A": return .green
        case "B": return .orange
        default: return .red
        }
    }
}
```

- [ ] **Step 2: Add the trend chart and mount the breakdown in `AnalyticsView`**

Add `import Charts` at the top of `ios/VMflow/Views/Analytics/AnalyticsView.swift`, add `@State private var selectedProduct: AnalyticsBreakdownRow?`, extend the `VStack`:

```swift
                    VStack(alignment: .leading, spacing: 16) {
                        AnalyticsFilterBar(viewModel: viewModel)
                        kpiRow
                        costHint
                        AnalyticsMetricPicker(metric: $viewModel.metric)
                        trendChart
                        AnalyticsBreakdownList(viewModel: viewModel) { row in
                            selectedProduct = row
                        }
                    }
                    .padding()
```

and add the chart section:

```swift
    // MARK: - Trend

    /// Weekend bars are drawn lighter. Beyond 60 days the daily bars become
    /// hairlines, so the points are folded into weekly buckets first.
    private var trendChart: some View {
        let points = bucketedPoints
        let prevAvg = previousDailyAverage
        return VStack(alignment: .leading, spacing: 8) {
            Text("Trend")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
            Chart {
                ForEach(points) { point in
                    BarMark(
                        x: .value("Date", point.day, unit: bucketUnit),
                        y: .value("Value", point.value(for: viewModel.metric))
                    )
                    .foregroundStyle(point.isWeekend && bucketUnit == .day
                                     ? Color.accentColor.opacity(0.4).gradient
                                     : Color.accentColor.gradient)
                    .cornerRadius(3)
                }
                if prevAvg > 0 {
                    RuleMark(y: .value("Previous average", prevAvg))
                        .foregroundStyle(.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                }
            }
            .frame(height: 150)
            .chartYAxis { AxisMarks(position: .leading) }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private var bucketUnit: Calendar.Component {
        chartBucket(forDays: viewModel.summary?.range.days ?? 30) == .week ? .weekOfYear : .day
    }

    private var bucketedPoints: [AnalyticsDailyPoint] {
        let daily = viewModel.summary?.daily ?? []
        guard bucketUnit == .weekOfYear else { return daily }
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: daily) { point in
            calendar.dateInterval(of: .weekOfYear, for: point.day)?.start ?? point.day
        }
        return grouped.map { start, group in
            AnalyticsDailyPoint(
                day: start,
                units: group.reduce(0) { $0 + $1.units },
                revenueGross: group.reduce(0) { $0 + $1.revenueGross },
                grossProfit: group.reduce(0) { $0 + $1.grossProfit })
        }
        .sorted { $0.day < $1.day }
    }

    /// The previous period's average per bucket — the dashed reference line.
    private var previousDailyAverage: Double {
        guard let summary = viewModel.summary, summary.range.days > 0 else { return 0 }
        let perDay = summary.previous.value(for: viewModel.metric) / summary.range.days
        return bucketUnit == .weekOfYear ? perDay * 7 : perDay
    }
```

`AnalyticsDailyPoint` needs a memberwise initialiser for the weekly aggregation. Add it to `ios/VMflow/Models/Analytics.swift` inside `AnalyticsDailyPoint`:

```swift
    init(day: Date, units: Int, revenueGross: Double, grossProfit: Double) {
        self.day = day; self.units = units
        self.revenueGross = revenueGross; self.grossProfit = grossProfit
    }
```

- [ ] **Step 3: Register the file in `project.pbxproj`**

Four edits as in Task 3 Step 5 (group: `Analytics`).

- [ ] **Step 4: Build and verify**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

In the simulator: confirm switching the metric segment above the chart also reorders the breakdown list (and vice versa — they are one binding), that switching the dimension to "Machines" reloads the list, and that a 90-day range renders weekly bars.

- [ ] **Step 5: Commit**

```bash
git add ios/VMflow/Views/Analytics/AnalyticsBreakdownList.swift ios/VMflow/Views/Analytics/AnalyticsView.swift ios/VMflow/Models/Analytics.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics trend chart and breakdown list"
```

---

## Task 7: iOS — peak-hours heatmap and payment-channel split

**Files:**
- Create: `ios/VMflow/Views/Analytics/HeatmapCard.swift`
- Create: `ios/VMflow/Views/Analytics/ChannelSplitCard.swift`
- Modify: `ios/VMflow/Views/Analytics/AnalyticsView.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AnalyticsHeatCell`, `AnalyticsChannel`, `heatIntensity(units:max:)`.
- Produces: `struct HeatmapCard: View` (init `cells: [AnalyticsHeatCell]`), `struct ChannelSplitCard: View` (init `channels: [AnalyticsChannel]`).

- [ ] **Step 1: Write `ios/VMflow/Views/Analytics/HeatmapCard.swift`**

```swift
import SwiftUI

/// Weekday x hour grid of sales volume. Answers "when is this actually bought",
/// which drives tour planning far more directly than a daily total does.
///
/// Hours are bucketed server-side in the caller's timezone; the cells arrive
/// sparse (only hours with sales) and are expanded into the full grid here.
struct HeatmapCard: View {
    let cells: [AnalyticsHeatCell]

    /// Hours are shown in 2-hour columns to stay readable on an iPhone.
    private let hourStep = 2

    private var byBucket: [Int: Int] {
        var result: [Int: Int] = [:]
        for cell in cells {
            let bucket = cell.dow * 100 + (cell.hour / hourStep) * hourStep
            result[bucket, default: 0] += cell.units
        }
        return result
    }

    private var maxUnits: Int { byBucket.values.max() ?? 0 }

    private var weekdaySymbols: [String] {
        // Calendar.shortWeekdaySymbols starts at the locale's first weekday
        // index 0 = Sunday; the RPC uses ISO (1 = Monday), so reorder.
        let symbols = Calendar.current.shortWeekdaySymbols
        return Array(symbols[1...6]) + [symbols[0]]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Peak hours")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if cells.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
            } else {
                VStack(spacing: 3) {
                    ForEach(1...7, id: \.self) { dow in
                        HStack(spacing: 3) {
                            Text(weekdaySymbols[dow - 1])
                                .font(.system(size: 9))
                                .foregroundStyle(.secondary)
                                .frame(width: 26, alignment: .trailing)
                            ForEach(Array(stride(from: 0, to: 24, by: hourStep)), id: \.self) { hour in
                                let units = byBucket[dow * 100 + hour] ?? 0
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color.accentColor.opacity(
                                        0.08 + heatIntensity(units: units, max: maxUnits) * 0.92))
                                    .frame(height: 13)
                            }
                        }
                    }
                    HStack(spacing: 3) {
                        Spacer().frame(width: 26)
                        ForEach(Array(stride(from: 0, to: 24, by: hourStep)), id: \.self) { hour in
                            Text(hour % 6 == 0 ? "\(hour)" : "")
                                .font(.system(size: 8))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }
}
```

- [ ] **Step 2: Write `ios/VMflow/Views/Analytics/ChannelSplitCard.swift`**

```swift
import SwiftUI

/// Cash vs. cashless for the selected window — relevant for reconciling the
/// cash book and for judging where card payment actually pays off.
struct ChannelSplitCard: View {
    let channels: [AnalyticsChannel]

    private var total: Double { channels.reduce(0) { $0 + $1.revenueGross } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Payment methods")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if channels.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
            } else {
                ForEach(channels) { channel in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(displayName(channel.channel)).font(.subheadline)
                            Spacer()
                            Text(String(format: "%.2f €", channel.revenueGross))
                                .font(.subheadline.weight(.semibold)).monospacedDigit()
                            Text(total > 0
                                 ? String(format: "%.0f %%", channel.revenueGross / total * 100)
                                 : "0 %")
                                .font(.caption).foregroundStyle(.secondary)
                                .frame(width: 42, alignment: .trailing)
                        }
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(color(for: channel.channel))
                                .frame(width: total > 0
                                       ? geo.size.width * channel.revenueGross / total : 0)
                        }
                        .frame(height: 6)
                        Text(String(format: String(localized: "%d pcs · ⌀ %.2f €"),
                                    channel.units, channel.avgTicket))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private func displayName(_ raw: String) -> String {
        switch raw.lowercased() {
        case "cash": return String(localized: "Cash")
        case "cashless", "card": return String(localized: "Cashless")
        default: return String(localized: "Unknown")
        }
    }

    private func color(for raw: String) -> Color {
        switch raw.lowercased() {
        case "cash": return .green
        case "cashless", "card": return .blue
        default: return .gray
        }
    }
}
```

- [ ] **Step 3: Mount both cards in `AnalyticsView`**

Append inside the `VStack`, after `AnalyticsBreakdownList`:

```swift
                        HeatmapCard(cells: viewModel.summary?.heatmap ?? [])
                        ChannelSplitCard(channels: viewModel.summary?.channels ?? [])
```

- [ ] **Step 4: Register both files in `project.pbxproj`**

Four edits each, as in Task 3 Step 5 (group: `Analytics`).

- [ ] **Step 5: Build and verify**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

In the simulator: confirm the heatmap shows darker cells during business hours and near-empty rows on weekends, and that the channel split percentages add up to 100 %.

- [ ] **Step 6: Commit**

```bash
git add ios/VMflow/Views/Analytics/HeatmapCard.swift ios/VMflow/Views/Analytics/ChannelSplitCard.swift ios/VMflow/Views/Analytics/AnalyticsView.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics peak-hours heatmap and channel split"
```

---

## Task 8: iOS — product detail sheet

**Files:**
- Create: `ios/VMflow/Views/Analytics/ProductAnalyticsSheet.swift`
- Modify: `ios/VMflow/Views/Analytics/AnalyticsView.swift`
- Modify: `ios/VMflow.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AnalyticsBreakdownRow`, `AnalyticsViewModel.loadProductMachines(productId:)` (Task 4), existing `get_product_purchase_summary` RPC and `ProductPurchaseSummary` model, `ProductImage`.
- Produces: `struct ProductAnalyticsSheet: View` with `init(row: AnalyticsBreakdownRow, viewModel: AnalyticsViewModel)`.

- [ ] **Step 1: Write `ios/VMflow/Views/Analytics/ProductAnalyticsSheet.swift`**

The purchase card reuses the existing `ProductPurchaseSummary` model
(`ios/VMflow/Models/ProductPurchaseSummary.swift`) and the existing
`get_product_purchase_summary(p_product_ids uuid[])` RPC — no new backend work.

```swift
import SwiftUI
import Supabase

/// Drill-down for one product under the currently active filters: its KPIs,
/// how it splits across machines, and what it costs to buy.
struct ProductAnalyticsSheet: View {
    let row: AnalyticsBreakdownRow
    @ObservedObject var viewModel: AnalyticsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var machineRows: [AnalyticsBreakdownRow] = []
    @State private var purchase: ProductPurchaseSummary?
    @State private var isLoading = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    kpiRow
                    machineDistribution
                    stockCard
                    purchaseCard
                }
                .padding()
            }
            .navigationTitle(row.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .task {
            guard let productId = row.key else { isLoading = false; return }
            machineRows = await viewModel.loadProductMachines(productId: productId)
            purchase = await loadPurchaseSummary(productId: productId)
            isLoading = false
        }
    }

    /// Latest purchase price and observed spread — the "why is the margin what
    /// it is" half of the sheet. Non-fatal: the rest of the sheet stands alone.
    private func loadPurchaseSummary(productId: UUID) async -> ProductPurchaseSummary? {
        do {
            let rows: [ProductPurchaseSummary] = try await SupabaseService.shared.client
                .rpc("get_product_purchase_summary",
                     params: ["p_product_ids": AnyJSON.array([.string(productId.uuidString)])])
                .execute()
                .value
            return rows.first
        } catch {
            return nil
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            ProductImage(imagePath: row.imagePath, size: 52)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.label).font(.headline)
                Text(String(format: String(localized: "Class %@ · %.1f %% of revenue"),
                            row.abcClass, row.sharePct))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var kpiRow: some View {
        HStack(spacing: 8) {
            KPICard(icon: "cart.fill", title: "Units", value: String(row.units),
                    subtitle: LocalizedStringKey(String(format: String(localized: "⌀ %.1f/day"),
                                                        row.avgDailyUnits)),
                    color: .green)
            KPICard(icon: "eurosign.circle.fill", title: "Revenue",
                    value: String(format: "%.2f €", row.revenue),
                    subtitle: deltaSubtitle, color: .blue)
            KPICard(icon: "chart.line.uptrend.xyaxis", title: "Gross profit",
                    value: row.hasCost ? String(format: "%.2f €", row.grossProfit) : "—",
                    subtitle: row.hasCost ? nil : LocalizedStringKey("no purchase price"),
                    color: .purple)
        }
    }

    private var deltaSubtitle: LocalizedStringKey? {
        guard let delta = deltaPct(current: row.revenue, previous: row.prevRevenue) else { return nil }
        return LocalizedStringKey(String(format: "%+.0f %%", delta))
    }

    private var machineDistribution: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Per machine")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)

            if isLoading {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 16)
            } else if machineRows.isEmpty {
                Text("No sales in this period.")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                let maxUnits = machineRows.map(\.units).max() ?? 0
                ForEach(machineRows) { machine in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(machine.label).font(.subheadline)
                            Spacer()
                            Text(String(machine.units))
                                .font(.subheadline.weight(.semibold)).monospacedDigit()
                        }
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color.accentColor)
                                .frame(width: maxUnits > 0
                                       ? geo.size.width * Double(machine.units) / Double(maxUnits) : 0)
                        }
                        .frame(height: 6)
                        Text(machineSubtitle(machine))
                            .font(.caption2).foregroundStyle(machine.totalStock == 0 ? .red : .secondary)
                    }
                }
            }
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    private func machineSubtitle(_ machine: AnalyticsBreakdownRow) -> String {
        var parts = [String(format: String(localized: "⌀ %.1f/day"), machine.avgDailyUnits)]
        if machine.totalCapacity > 0 {
            parts.append(String(format: String(localized: "stock %d/%d"),
                                machine.totalStock, machine.totalCapacity))
        }
        if machine.totalStock == 0 && machine.totalCapacity > 0 {
            parts.append(String(localized: "empty"))
        }
        return parts.joined(separator: " · ")
    }

    private var stockCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Stock")
                .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
            Text(String(format: String(localized: "%d of %d slots filled"),
                        row.totalStock, row.totalCapacity))
                .font(.subheadline)
            if let sellThrough = row.sellThroughPct {
                Text(String(format: String(localized: "Sell-through %.1f %%"), sellThrough))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let days = row.daysOfSupply {
                Text(String(format: String(localized: "Lasts about %.0f more days"), days))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
    }

    @ViewBuilder
    private var purchaseCard: some View {
        if let purchase, purchase.ekCount > 0 {
            VStack(alignment: .leading, spacing: 6) {
                Text("Purchase")
                    .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
                if let net = purchase.newestNet {
                    Text(String(format: String(localized: "Latest %.2f € net"), net))
                        .font(.subheadline)
                }
                Text(purchaseDetail(purchase))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background { RoundedRectangle(cornerRadius: 14).fill(.regularMaterial) }
        }
    }

    private func purchaseDetail(_ purchase: ProductPurchaseSummary) -> String {
        var parts: [String] = []
        if let supplier = purchase.newestSupplier { parts.append(supplier) }
        if let date = purchase.newestOn { parts.append(date) }
        if let low = purchase.minGross, let high = purchase.maxGross, low != high {
            parts.append(String(format: String(localized: "range %.2f – %.2f € gross"), low, high))
        }
        parts.append(String(format: String(localized: "%d quotes"), purchase.ekCount))
        return parts.joined(separator: " · ")
    }
}
```

- [ ] **Step 2: Present the sheet from `AnalyticsView`**

Add to `AnalyticsView`'s `body`, next to the existing `.alert`:

```swift
        .sheet(item: $selectedProduct) { row in
            ProductAnalyticsSheet(row: row, viewModel: viewModel)
        }
```

- [ ] **Step 3: Register the file in `project.pbxproj`**

Four edits as in Task 3 Step 5 (group: `Analytics`).

- [ ] **Step 4: Build and verify**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`

In the simulator: tap a product row, confirm the sheet opens, its per-machine bars sum to the row's unit count, and that switching the dimension to "Machines" makes rows non-tappable.

- [ ] **Step 5: Commit**

```bash
git add ios/VMflow/Views/Analytics/ProductAnalyticsSheet.swift ios/VMflow/Views/Analytics/AnalyticsView.swift ios/VMflow.xcodeproj/project.pbxproj
git commit -m "feat(ios): add analytics product detail sheet"
```

---

## Task 9: iOS — German localisation

**Files:**
- Modify: `ios/VMflow/Resources/Localizable.xcstrings`

**Interfaces:**
- Consumes: every `String(localized:)` / `Text("…")` literal introduced in Tasks 4–8.

- [ ] **Step 1: Collect the literals that need a German entry**

```bash
grep -rhoE 'String\(localized: "[^"]+"\)|Text\("[^"]+"\)|LocalizedStringKey\("[^"]+"\)' ios/VMflow/Views/Analytics ios/VMflow/ViewModels/AnalyticsViewModel.swift | sed -E 's/.*"(.*)".*/\1/' | sort -u
```

- [ ] **Step 2: Add `de` entries to `Localizable.xcstrings`**

For each key, insert a `de` localisation **surgically** into the existing JSON — do not re-serialise the file with a script, Xcode's key ordering is not codepoint sort and a rewrite produces an unreviewable diff. Each entry follows the shape already used in the file:

```json
    "Analytics" : {
      "localizations" : {
        "de" : {
          "stringUnit" : {
            "state" : "translated",
            "value" : "Auswertung"
          }
        }
      }
    },
```

Translations (du-tone, matching the rest of the app):

| Key | `de` |
|---|---|
| `Analytics` | Auswertung |
| `Analytics not available` | Auswertung nicht verfügbar |
| `This server does not support analytics yet. Please update the backend.` | Dieser Server unterstützt die Auswertung noch nicht. Bitte aktualisiere das Backend. |
| `Revenue` | Umsatz |
| `Units` | Stück |
| `Gross profit` | Rohertrag |
| `Profit` | Rohertrag |
| `Gross profit is net of tax. %d products have no purchase price and are excluded.` | Rohertrag netto. %d Artikel ohne EK-Preis sind nicht enthalten. |
| `Time range` | Zeitraum |
| `Last 7 days` | Letzte 7 Tage |
| `Last 30 days` | Letzte 30 Tage |
| `Last 90 days` | Letzte 90 Tage |
| `This month` | Dieser Monat |
| `Last month` | Letzter Monat |
| `Custom range` | Eigener Zeitraum |
| `Apply custom range` | Zeitraum übernehmen |
| `From` | Von |
| `To` | Bis |
| `Machines` | Automaten |
| `All machines` | Alle Automaten |
| `%d machines` | %d Automaten |
| `Categories` | Kategorien |
| `All categories` | Alle Kategorien |
| `%d categories` | %d Kategorien |
| `Products` | Artikel |
| `%d products` | %d Artikel |
| `%d pcs` | %d Stk |
| `%@ /day` | %@ /Tag |
| `⌀ %.1f/day` | ⌀ %.1f/Tag |
| `Breakdown` | Aufschlüsselung |
| `Trend` | Verlauf |
| `Peak hours` | Stoßzeiten |
| `Payment methods` | Zahlungsarten |
| `Cash` | Bar |
| `Cashless` | Bargeldlos |
| `Unknown` | Unbekannt |
| `%d pcs · ⌀ %.2f €` | %d Stk · ⌀ %.2f € |
| `No sales in this period.` | Keine Verkäufe in diesem Zeitraum. |
| `no purchase price` | kein EK-Preis |
| `Per machine` | Je Automat |
| `Class %@ · %.1f %% of revenue` | Klasse %@ · %.1f %% vom Umsatz |
| `Stock` | Bestand |
| `stock %d/%d` | Bestand %d/%d |
| `empty` | leer |
| `%d of %d slots filled` | %d von %d Plätzen gefüllt |
| `Sell-through %.1f %%` | Abverkauf %.1f %% |
| `Lasts about %.0f more days` | Reicht noch etwa %.0f Tage |
| `Purchase` | Einkauf |
| `Latest %.2f € net` | Neuester EK %.2f € netto |
| `range %.2f – %.2f € gross` | Spanne %.2f – %.2f € brutto |
| `%d quotes` | %d Notierungen |
| `Unnamed` | Unbenannt |

Keys already present in the file (`Done`, `OK`, `Error`, `Could not determine company`) must **not** be duplicated — check with `grep -n '"Done" :' ios/VMflow/Resources/Localizable.xcstrings` before adding.

- [ ] **Step 3: Validate the catalogue by building**

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`. The build runs `xcstringstool`, so a malformed catalogue fails here.

- [ ] **Step 4: Verify in German**

Run the app in the simulator with the German locale and confirm the page reads "Auswertung", the chips say "Alle Automaten"/"Alle Kategorien", and no key renders as its raw English literal where a translation was added.

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'platform=iOS Simulator,name=iPhone 16' -testLanguage de -testRegion DE build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -3
```

- [ ] **Step 5: Commit**

```bash
git add ios/VMflow/Resources/Localizable.xcstrings
git commit -m "feat(ios): add German strings for the analytics page"
```

## Task 10: PWA — pure helpers in `app/lib/analytics.ts`

**Files:**
- Create: `management-frontend/app/lib/analytics.ts`
- Test: `management-frontend/app/lib/__tests__/analytics.test.ts`

**Interfaces:**
- Consumes: the JSON contracts of Task 1 and Task 2.
- Produces (all exported from `app/lib/analytics.ts`):
  - `type AnalyticsMetric = 'units' | 'revenue' | 'grossProfit'`
  - `type AnalyticsDimension = 'product' | 'category' | 'machine'`
  - `type RangePreset = 'days7' | 'days30' | 'days90' | 'thisMonth' | 'lastMonth' | 'custom'`
  - `interface AnalyticsTotals`, `interface AnalyticsDailyPoint`, `interface AnalyticsHeatCell`, `interface AnalyticsChannel`, `interface AnalyticsSummary`, `interface BreakdownRow`
  - `deltaPct(current: number, previous: number): number | null`
  - `chartBucket(days: number): 'day' | 'week'`
  - `heatIntensity(units: number, max: number): number`
  - `metricValue(row: BreakdownRow | AnalyticsTotals | AnalyticsDailyPoint, metric: AnalyticsMetric): number`
  - `sortRows(rows: BreakdownRow[], metric: AnalyticsMetric): BreakdownRow[]`
  - `resolveRange(preset: RangePreset, customFrom: string, customTo: string, now?: Date): { from: string; to: string }`
  - `bucketDaily(points: AnalyticsDailyPoint[], bucket: 'day' | 'week'): AnalyticsDailyPoint[]`

- [ ] **Step 1: Write the failing test**

Create `management-frontend/app/lib/__tests__/analytics.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import {
  bucketDaily,
  chartBucket,
  deltaPct,
  heatIntensity,
  metricValue,
  resolveRange,
  sortRows,
} from '../analytics'
import type { AnalyticsDailyPoint, BreakdownRow } from '../analytics'

const row = (over: Partial<BreakdownRow>): BreakdownRow => ({
  key: 'k', label: 'x', image_path: null,
  units: 0, revenue_gross: 0, revenue_net: 0, gross_profit: 0,
  prev_units: 0, prev_revenue_gross: 0, prev_gross_profit: 0,
  share_pct: 0, cumulative_share_pct: 0, abc_class: 'C',
  avg_daily_units: 0, avg_daily_revenue: 0, avg_daily_gross_profit: 0,
  total_capacity: 0, total_stock: 0, sell_through_pct: null, days_of_supply: null,
  machine_count: 0, product_count: 0, has_cost: true,
  ...over,
})

describe('deltaPct', () => {
  it('reports a positive change', () => expect(deltaPct(110, 100)).toBe(10))
  it('reports a negative change', () => expect(deltaPct(90, 100)).toBe(-10))
  it('returns null against a zero baseline', () => expect(deltaPct(50, 0)).toBeNull())
  it('returns null when both are zero', () => expect(deltaPct(0, 0)).toBeNull())
})

describe('chartBucket', () => {
  it('keeps daily bars up to 60 days', () => {
    expect(chartBucket(7)).toBe('day')
    expect(chartBucket(60)).toBe('day')
  })
  it('switches to weekly bars beyond 60 days', () => {
    expect(chartBucket(61)).toBe('week')
    expect(chartBucket(365)).toBe('week')
  })
})

describe('heatIntensity', () => {
  it('maps units onto 0..1', () => {
    expect(heatIntensity(0, 10)).toBe(0)
    expect(heatIntensity(5, 10)).toBe(0.5)
    expect(heatIntensity(10, 10)).toBe(1)
  })
  it('never divides by zero', () => expect(heatIntensity(3, 0)).toBe(0))
})

describe('sortRows', () => {
  const rows = [
    row({ label: 'low', units: 1, revenue_gross: 90, gross_profit: 50 }),
    row({ label: 'high', units: 20, revenue_gross: 10, gross_profit: 5 }),
  ]
  it('sorts by units', () => expect(sortRows(rows, 'units')[0]!.label).toBe('high'))
  it('sorts by revenue', () => expect(sortRows(rows, 'revenue')[0]!.label).toBe('low'))
  it('sorts by gross profit', () => expect(sortRows(rows, 'grossProfit')[0]!.label).toBe('low'))
  it('does not mutate the input', () => {
    sortRows(rows, 'units')
    expect(rows[0]!.label).toBe('low')
  })
})

describe('metricValue', () => {
  it('reads the selected metric off a row', () => {
    const r = row({ units: 3, revenue_gross: 7, gross_profit: 2 })
    expect(metricValue(r, 'units')).toBe(3)
    expect(metricValue(r, 'revenue')).toBe(7)
    expect(metricValue(r, 'grossProfit')).toBe(2)
  })
})

describe('resolveRange', () => {
  // vitest runs with TZ=UTC (vitest.config.ts), so local midnight is UTC midnight.
  const now = new Date('2026-07-15T13:45:00Z')

  it('covers 7 days ending with the exclusive next midnight', () => {
    const r = resolveRange('days7', '', '', now)
    expect(r.from).toBe('2026-07-09T00:00:00.000Z')
    expect(r.to).toBe('2026-07-16T00:00:00.000Z')
  })

  it('starts this month on the 1st', () => {
    expect(resolveRange('thisMonth', '', '', now).from).toBe('2026-07-01T00:00:00.000Z')
  })

  it('bounds last month by the two 1sts', () => {
    const r = resolveRange('lastMonth', '', '', now)
    expect(r.from).toBe('2026-06-01T00:00:00.000Z')
    expect(r.to).toBe('2026-07-01T00:00:00.000Z')
  })

  it('makes a custom range inclusive of its last day', () => {
    const r = resolveRange('custom', '2026-03-05', '2026-03-09', now)
    expect(r.from).toBe('2026-03-05T00:00:00.000Z')
    expect(r.to).toBe('2026-03-10T00:00:00.000Z')
  })

  it('swaps a reversed custom range instead of returning an empty window', () => {
    const r = resolveRange('custom', '2026-03-09', '2026-03-05', now)
    expect(r.from).toBe('2026-03-05T00:00:00.000Z')
    expect(r.to).toBe('2026-03-10T00:00:00.000Z')
  })
})

describe('bucketDaily', () => {
  const points: AnalyticsDailyPoint[] = [
    { day: '2026-07-06', units: 1, revenue_gross: 1, gross_profit: 0.5 }, // Mon
    { day: '2026-07-07', units: 2, revenue_gross: 2, gross_profit: 1 },
    { day: '2026-07-13', units: 4, revenue_gross: 4, gross_profit: 2 },   // next Mon
  ]

  it('returns the input untouched for daily buckets', () => {
    expect(bucketDaily(points, 'day')).toHaveLength(3)
  })

  it('folds days into ISO weeks', () => {
    const weeks = bucketDaily(points, 'week')
    expect(weeks).toHaveLength(2)
    expect(weeks[0]!.day).toBe('2026-07-06')
    expect(weeks[0]!.units).toBe(3)
    expect(weeks[1]!.units).toBe(4)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd management-frontend && npx vitest run app/lib/__tests__/analytics.test.ts
```

Expected: FAIL — `Failed to resolve import "../analytics"`.

- [ ] **Step 3: Write `management-frontend/app/lib/analytics.ts`**

```ts
// Pure helpers and types for the Analytics page.
//
// Everything that computes lives here rather than in components, so it is
// testable under Vitest without a Nuxt runtime — same split as printSheet.ts.
//
// Types mirror the JSON contracts of get_sales_analytics_summary and
// get_sales_analytics_breakdown (migrations 20260811000000 / 20260811000100).

export type AnalyticsMetric = 'units' | 'revenue' | 'grossProfit'
export type AnalyticsDimension = 'product' | 'category' | 'machine'
export type RangePreset = 'days7' | 'days30' | 'days90' | 'thisMonth' | 'lastMonth' | 'custom'

export interface AnalyticsTotals {
  units: number
  revenue_gross: number
  revenue_net: number
  cost_net: number
  gross_profit: number
  avg_ticket: number
  avg_daily_units: number
  avg_daily_revenue: number
  avg_daily_gross_profit: number
}

export interface AnalyticsDailyPoint {
  /** `yyyy-MM-dd` in the requested timezone. */
  day: string
  units: number
  revenue_gross: number
  gross_profit: number
}

export interface AnalyticsHeatCell {
  /** ISO weekday: 1 = Monday … 7 = Sunday. */
  dow: number
  hour: number
  units: number
  revenue_gross: number
}

export interface AnalyticsChannel {
  channel: string
  units: number
  revenue_gross: number
  avg_ticket: number
}

export interface AnalyticsSummary {
  range: { from: string; to: string; previous_from: string; previous_to: string; days: number; timezone: string }
  totals: AnalyticsTotals
  previous: AnalyticsTotals
  daily: AnalyticsDailyPoint[]
  heatmap: AnalyticsHeatCell[]
  channels: AnalyticsChannel[]
  missing_cost_products: number
  unknown_product_units: number
}

export interface BreakdownRow {
  /** null for the aggregate "Unknown" row (unresolvable sales). */
  key: string | null
  label: string
  image_path: string | null
  units: number
  revenue_gross: number
  revenue_net: number
  gross_profit: number
  prev_units: number
  prev_revenue_gross: number
  prev_gross_profit: number
  share_pct: number
  cumulative_share_pct: number
  abc_class: string
  avg_daily_units: number
  avg_daily_revenue: number
  avg_daily_gross_profit: number
  total_capacity: number
  total_stock: number
  sell_through_pct: number | null
  days_of_supply: number | null
  machine_count: number
  product_count: number
  has_cost: boolean
}

/**
 * Percentage change against the previous period. Returns null on a zero
 * baseline — "+∞ %" is not something a user can act on, so the UI shows nothing.
 */
export function deltaPct(current: number, previous: number): number | null {
  if (!previous) return null
  return ((current - previous) / Math.abs(previous)) * 100
}

/** Daily bars turn into hairlines past roughly two months. */
export function chartBucket(days: number): 'day' | 'week' {
  return days > 60 ? 'week' : 'day'
}

export function heatIntensity(units: number, max: number): number {
  if (max <= 0) return 0
  return Math.min(units / max, 1)
}

type MetricSource = Pick<BreakdownRow, 'units' | 'revenue_gross' | 'gross_profit'>

export function metricValue(source: MetricSource, metric: AnalyticsMetric): number {
  if (metric === 'units') return source.units
  if (metric === 'revenue') return source.revenue_gross
  return source.gross_profit
}

/**
 * Sorts a copy by the selected metric, descending. The RPC returns rows
 * revenue-sorted; switching the metric reorders client-side rather than
 * triggering another round trip.
 */
export function sortRows(rows: BreakdownRow[], metric: AnalyticsMetric): BreakdownRow[] {
  return [...rows].sort((a, b) => {
    const diff = metricValue(b, metric) - metricValue(a, metric)
    if (diff !== 0) return diff
    return a.label.localeCompare(b.label)
  })
}

function startOfLocalDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function addDays(d: Date, n: number): Date {
  const copy = new Date(d)
  copy.setDate(copy.getDate() + n)
  return copy
}

/**
 * Resolves a preset into a half-open `[from, to)` window as full ISO strings
 * with offset. Never hand PostgREST a bare `yyyy-MM-ddT00:00:00` — it reads
 * that as UTC while the UI renders local, which shifts the window by the
 * local offset.
 *
 * `to` is the exclusive midnight after the last included day, because the RPC
 * filters `created_at < p_to`.
 */
export function resolveRange(
  preset: RangePreset,
  customFrom: string,
  customTo: string,
  now: Date = new Date(),
): { from: string; to: string } {
  const today = startOfLocalDay(now)
  const tomorrow = addDays(today, 1)

  const iso = (d: Date) => d.toISOString()

  switch (preset) {
    case 'days7':
      return { from: iso(addDays(today, -6)), to: iso(tomorrow) }
    case 'days30':
      return { from: iso(addDays(today, -29)), to: iso(tomorrow) }
    case 'days90':
      return { from: iso(addDays(today, -89)), to: iso(tomorrow) }
    case 'thisMonth':
      return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(tomorrow) }
    case 'lastMonth': {
      const thisMonth = new Date(now.getFullYear(), now.getMonth(), 1)
      const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1)
      return { from: iso(lastMonth), to: iso(thisMonth) }
    }
    case 'custom': {
      const a = startOfLocalDay(new Date(`${customFrom}T00:00:00`))
      const b = startOfLocalDay(new Date(`${customTo}T00:00:00`))
      const from = a <= b ? a : b
      const lastDay = a <= b ? b : a
      return { from: iso(from), to: iso(addDays(lastDay, 1)) }
    }
  }
}

/**
 * Folds a gapless daily series into ISO weeks (Monday-anchored) when the
 * window is long enough for weekly bars.
 */
export function bucketDaily(
  points: AnalyticsDailyPoint[],
  bucket: 'day' | 'week',
): AnalyticsDailyPoint[] {
  if (bucket === 'day') return points

  const byWeek = new Map<string, AnalyticsDailyPoint>()
  for (const point of points) {
    const date = new Date(`${point.day}T00:00:00`)
    // getDay(): 0 = Sunday. Shift so Monday anchors the week.
    const offset = (date.getDay() + 6) % 7
    const monday = addDays(date, -offset)
    const key = `${monday.getFullYear()}-${String(monday.getMonth() + 1).padStart(2, '0')}-${String(monday.getDate()).padStart(2, '0')}`

    const existing = byWeek.get(key)
    if (existing) {
      existing.units += point.units
      existing.revenue_gross += point.revenue_gross
      existing.gross_profit += point.gross_profit
    } else {
      byWeek.set(key, {
        day: key,
        units: point.units,
        revenue_gross: point.revenue_gross,
        gross_profit: point.gross_profit,
      })
    }
  }
  return [...byWeek.values()].sort((a, b) => a.day.localeCompare(b.day))
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd management-frontend && npx vitest run app/lib/__tests__/analytics.test.ts
```

Expected: all tests pass.

- [ ] **Step 5: Run the whole suite to check for regressions**

```bash
cd management-frontend && npx vitest run
```

Expected: no new failures.

- [ ] **Step 6: Commit**

```bash
git add management-frontend/app/lib/analytics.ts management-frontend/app/lib/__tests__/analytics.test.ts
git commit -m "feat(frontend): add pure analytics helpers"
```

---

## Task 11: PWA — `useAnalytics` composable

**Files:**
- Create: `management-frontend/app/composables/useAnalytics.ts`

**Interfaces:**
- Consumes: `app/lib/analytics.ts` (Task 10), `useSupabaseClient()`, `useOrganization()`.
- Produces: `useAnalytics()` returning refs `preset`, `customFrom`, `customTo`, `machineIds`, `categoryIds`, `metric`, `dimension`, `summary`, `rows`, `machines`, `categories`, `loading`, `loadingRows`, `error`, `backendUnsupported`; computed `range`, `sortedRows`, `bucketedDaily`, `rangeLabel`, `previousRangeLabel`; and functions `loadSummary()`, `loadBreakdown()`, `loadFilterOptions()`, `loadAll()`, `loadProductMachines(productId)`.

- [ ] **Step 1: Write `management-frontend/app/composables/useAnalytics.ts`**

```ts
import {
  bucketDaily,
  chartBucket,
  resolveRange,
  sortRows,
  type AnalyticsDimension,
  type AnalyticsMetric,
  type AnalyticsSummary,
  type BreakdownRow,
  type RangePreset,
} from '~/lib/analytics'

interface MachineOption { id: string; name: string | null }
interface CategoryOption { id: string; name: string }

/**
 * Filter state and data loading for /analytics.
 *
 * State lives in useState so the filters survive navigating away and back —
 * losing a carefully-set custom range on a round trip to a machine page is the
 * kind of small betrayal that makes a report page feel disposable.
 */
export const useAnalytics = () => {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()

  const preset = useState<RangePreset>('analytics-preset', () => 'days30')
  const customFrom = useState<string>('analytics-custom-from', () => '')
  const customTo = useState<string>('analytics-custom-to', () => '')
  const machineIds = useState<string[]>('analytics-machine-ids', () => [])
  const categoryIds = useState<string[]>('analytics-category-ids', () => [])
  const metric = useState<AnalyticsMetric>('analytics-metric', () => 'revenue')
  const dimension = useState<AnalyticsDimension>('analytics-dimension', () => 'product')

  const summary = useState<AnalyticsSummary | null>('analytics-summary', () => null)
  const rows = useState<BreakdownRow[]>('analytics-rows', () => [])
  const machines = useState<MachineOption[]>('analytics-machines', () => [])
  const categories = useState<CategoryOption[]>('analytics-categories', () => [])

  const loading = ref(false)
  const loadingRows = ref(false)
  const error = ref('')
  /** The connected backend predates the analytics migrations. */
  const backendUnsupported = ref(false)

  const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

  const range = computed(() => resolveRange(preset.value, customFrom.value, customTo.value))

  const sortedRows = computed(() => sortRows(rows.value, metric.value))

  const bucketedDaily = computed(() =>
    bucketDaily(summary.value?.daily ?? [], chartBucket(summary.value?.range.days ?? 30)))

  const dayFmt = (iso: string) => new Date(iso).toLocaleDateString(undefined, {
    day: '2-digit', month: '2-digit', year: 'numeric',
  })

  const rangeLabel = computed(() => {
    const r = range.value
    const lastDay = new Date(new Date(r.to).getTime() - 86_400_000)
    return `${dayFmt(r.from)} – ${dayFmt(lastDay.toISOString())}`
  })

  /**
   * The previous window is `[from - span, from)` — not "last month". Spelled
   * out in the UI so nobody reads it as a calendar period.
   */
  const previousRangeLabel = computed(() => {
    const r = range.value
    const span = new Date(r.to).getTime() - new Date(r.from).getTime()
    const prevFrom = new Date(new Date(r.from).getTime() - span)
    const prevLast = new Date(new Date(r.from).getTime() - 86_400_000)
    return `${dayFmt(prevFrom.toISOString())} – ${dayFmt(prevLast.toISOString())}`
  })

  function baseParams() {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const r = range.value
    return {
      p_company_id: companyId,
      p_from: r.from,
      p_to: r.to,
      p_machine_ids: machineIds.value.length ? machineIds.value : null,
      p_category_ids: categoryIds.value.length ? categoryIds.value : null,
      p_timezone: timezone(),
    }
  }

  /** PostgREST answers an unknown function with PGRST202 — a backend-version
   *  problem, not a bug, and the whole page depends on it. */
  function handle(err: unknown) {
    const code = (err as { code?: string })?.code
    const message = (err as { message?: string })?.message ?? String(err)
    if (code === 'PGRST202' || message.includes('Could not find the function')) {
      backendUnsupported.value = true
      error.value = ''
    } else {
      error.value = message
    }
  }

  async function loadSummary() {
    loading.value = true
    error.value = ''
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_summary', baseParams())
      if (err) throw err
      summary.value = data as AnalyticsSummary
      backendUnsupported.value = false
    } catch (err) {
      handle(err)
    } finally {
      loading.value = false
    }
  }

  async function loadBreakdown() {
    loadingRows.value = true
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_breakdown', {
          ...baseParams(),
          p_dimension: dimension.value,
          p_product_id: null,
        })
      if (err) throw err
      rows.value = (data ?? []) as BreakdownRow[]
      backendUnsupported.value = false
    } catch (err) {
      handle(err)
    } finally {
      loadingRows.value = false
    }
  }

  /** Per-machine split of one product — the detail dialog reuses the breakdown
   *  RPC with the machine dimension narrowed to a single product. */
  async function loadProductMachines(productId: string): Promise<BreakdownRow[]> {
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_breakdown', {
          ...baseParams(),
          p_dimension: 'machine',
          p_product_id: productId,
        })
      if (err) throw err
      return (data ?? []) as BreakdownRow[]
    } catch {
      return []
    }
  }

  async function loadFilterOptions() {
    const companyId = organization.value?.id
    if (!companyId) return
    const [machineRes, categoryRes] = await Promise.all([
      (supabase as any).from('vendingMachine').select('id, name')
        .eq('company', companyId).order('name'),
      (supabase as any).from('product_category').select('id, name')
        .eq('company', companyId).order('name'),
    ])
    // Non-fatal: without these the page still works as "all machines / all categories".
    machines.value = (machineRes.data ?? []) as MachineOption[]
    categories.value = (categoryRes.data ?? []) as CategoryOption[]
  }

  async function loadAll() {
    await Promise.all([loadSummary(), loadBreakdown()])
  }

  return {
    preset, customFrom, customTo, machineIds, categoryIds, metric, dimension,
    summary, rows, machines, categories,
    loading, loadingRows, error, backendUnsupported,
    range, sortedRows, bucketedDaily, rangeLabel, previousRangeLabel,
    loadSummary, loadBreakdown, loadProductMachines, loadFilterOptions, loadAll,
  }
}
```

- [ ] **Step 2: Type-check**

```bash
cd management-frontend && npx nuxi typecheck 2>&1 | tail -20
```

Expected: no new errors mentioning `useAnalytics.ts`. (Pre-existing errors elsewhere are out of scope — compare against `git stash` output if unsure.)

- [ ] **Step 3: Commit**

```bash
git add management-frontend/app/composables/useAnalytics.ts
git commit -m "feat(frontend): add useAnalytics composable"
```

---

## Task 12: PWA — page shell, filters, KPIs, trend chart, navigation

**Files:**
- Create: `management-frontend/app/pages/analytics.vue`
- Create: `management-frontend/app/components/analytics/AnalyticsFilterBar.vue`
- Create: `management-frontend/app/components/analytics/AnalyticsKpiRow.vue`
- Create: `management-frontend/app/components/analytics/AnalyticsTrendChart.vue`
- Modify: `management-frontend/app/components/AppSidebar.vue`
- Modify: `management-frontend/i18n/locales/en.json`
- Modify: `management-frontend/i18n/locales/de.json`

**Interfaces:**
- Consumes: `useAnalytics()` (Task 11), `deltaPct`, `metricValue`, `chartBucket` (Task 10).
- Produces: route `/analytics`; components `AnalyticsFilterBar` (props: none — reads the composable), `AnalyticsKpiRow`, `AnalyticsTrendChart`.

- [ ] **Step 1: Add i18n keys**

In `management-frontend/i18n/locales/en.json` add `"analytics": "Analytics"` to the `nav` object and a new top-level `analytics` object:

```json
  "analytics": {
    "title": "Analytics",
    "subtitle": "Understand what sells, where and when",
    "unsupported": "This server does not support analytics yet. Please update the backend.",
    "revenue": "Revenue",
    "units": "Units",
    "grossProfit": "Gross profit",
    "profit": "Profit",
    "vsPrevious": "vs. {range}",
    "costHint": "Gross profit is net of tax. {count} products have no purchase price and are excluded.",
    "timeRange": "Time range",
    "days7": "Last 7 days",
    "days30": "Last 30 days",
    "days90": "Last 90 days",
    "thisMonth": "This month",
    "lastMonth": "Last month",
    "custom": "Custom range",
    "from": "From",
    "to": "To",
    "apply": "Apply",
    "machines": "Machines",
    "allMachines": "All machines",
    "nMachines": "{count} machines",
    "categories": "Categories",
    "allCategories": "All categories",
    "nCategories": "{count} categories",
    "products": "Products",
    "breakdown": "Breakdown",
    "trend": "Trend",
    "peakHours": "Peak hours",
    "paymentMethods": "Payment methods",
    "cash": "Cash",
    "cashless": "Cashless",
    "unknown": "Unknown",
    "noSales": "No sales in this period.",
    "noPurchasePrice": "no purchase price",
    "perDay": "{value} /day",
    "nPieces": "{count} pcs",
    "nProducts": "{count} products",
    "perMachine": "Per machine",
    "abcClass": "Class {class} · {share}% of revenue",
    "stock": "Stock",
    "stockOf": "stock {current}/{capacity}",
    "empty": "empty",
    "sellThrough": "Sell-through {value}%",
    "daysOfSupply": "Lasts about {days} more days",
    "previousAverage": "Previous average"
  }
```

Add the same structure to `management-frontend/i18n/locales/de.json` with:

```json
  "analytics": {
    "title": "Auswertung",
    "subtitle": "Verstehen, was sich wo und wann verkauft",
    "unsupported": "Dieser Server unterstützt die Auswertung noch nicht. Bitte aktualisiere das Backend.",
    "revenue": "Umsatz",
    "units": "Stück",
    "grossProfit": "Rohertrag",
    "profit": "Rohertrag",
    "vsPrevious": "vs. {range}",
    "costHint": "Rohertrag netto. {count} Artikel ohne EK-Preis sind nicht enthalten.",
    "timeRange": "Zeitraum",
    "days7": "Letzte 7 Tage",
    "days30": "Letzte 30 Tage",
    "days90": "Letzte 90 Tage",
    "thisMonth": "Dieser Monat",
    "lastMonth": "Letzter Monat",
    "custom": "Eigener Zeitraum",
    "from": "Von",
    "to": "Bis",
    "apply": "Übernehmen",
    "machines": "Automaten",
    "allMachines": "Alle Automaten",
    "nMachines": "{count} Automaten",
    "categories": "Kategorien",
    "allCategories": "Alle Kategorien",
    "nCategories": "{count} Kategorien",
    "products": "Artikel",
    "breakdown": "Aufschlüsselung",
    "trend": "Verlauf",
    "peakHours": "Stoßzeiten",
    "paymentMethods": "Zahlungsarten",
    "cash": "Bar",
    "cashless": "Bargeldlos",
    "unknown": "Unbekannt",
    "noSales": "Keine Verkäufe in diesem Zeitraum.",
    "noPurchasePrice": "kein EK-Preis",
    "perDay": "{value} /Tag",
    "nPieces": "{count} Stk",
    "nProducts": "{count} Artikel",
    "perMachine": "Je Automat",
    "abcClass": "Klasse {class} · {share} % vom Umsatz",
    "stock": "Bestand",
    "stockOf": "Bestand {current}/{capacity}",
    "empty": "leer",
    "sellThrough": "Abverkauf {value} %",
    "daysOfSupply": "Reicht noch etwa {days} Tage",
    "previousAverage": "Ø Vorperiode"
  }
```

Also add `"analytics": "Auswertung"` to `de.json`'s `nav` object.

- [ ] **Step 2: Add the sidebar entry**

In `management-frontend/app/components/AppSidebar.vue`, import the icon:

```ts
  IconChartHistogram,
```

and add as the **first** item of the `groupOperations` group:

```ts
        {
          title: t('nav.analytics'),
          url: "/analytics",
          icon: IconChartHistogram,
        },
```

- [ ] **Step 3: Write `management-frontend/app/components/analytics/AnalyticsFilterBar.vue`**

```vue
<script setup lang="ts">
import type { RangePreset } from '~/lib/analytics'

const { t } = useI18n()
const {
  preset, customFrom, customTo, machineIds, categoryIds,
  machines, categories, rangeLabel, loadAll,
} = useAnalytics()

const presets: RangePreset[] = ['days7', 'days30', 'days90', 'thisMonth', 'lastMonth']

const machineLabel = computed(() => {
  if (!machineIds.value.length) return t('analytics.allMachines')
  if (machineIds.value.length === 1) {
    const m = machines.value.find(x => x.id === machineIds.value[0])
    return m?.name || t('analytics.allMachines')
  }
  return t('analytics.nMachines', { count: machineIds.value.length })
})

const categoryLabel = computed(() => {
  if (!categoryIds.value.length) return t('analytics.allCategories')
  if (categoryIds.value.length === 1) {
    return categories.value.find(x => x.id === categoryIds.value[0])?.name
      || t('analytics.allCategories')
  }
  return t('analytics.nCategories', { count: categoryIds.value.length })
})

function toggle(list: string[], id: string) {
  const i = list.indexOf(id)
  if (i >= 0) list.splice(i, 1)
  else list.push(id)
}

function applyPreset(p: RangePreset) {
  preset.value = p
  loadAll()
}

function applyCustom() {
  if (!customFrom.value || !customTo.value) return
  preset.value = 'custom'
  loadAll()
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <!-- Time range -->
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconCalendar class="size-4" />
          {{ rangeLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="w-64">
        <DropdownMenuItem v-for="p in presets" :key="p" @click="applyPreset(p)">
          {{ t(`analytics.${p}`) }}
          <IconCheck v-if="preset === p" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <div class="space-y-2 p-2">
          <p class="text-muted-foreground text-xs">{{ t('analytics.custom') }}</p>
          <input v-model="customFrom" type="date" class="w-full rounded border px-2 py-1 text-sm">
          <input v-model="customTo" type="date" class="w-full rounded border px-2 py-1 text-sm">
          <Button size="sm" class="w-full" @click="applyCustom">{{ t('analytics.apply') }}</Button>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>

    <!-- Machines -->
    <DropdownMenu @update:open="(open: boolean) => { if (!open) loadAll() }">
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconBuildingStore class="size-4" />
          {{ machineLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="max-h-80 w-64 overflow-y-auto">
        <DropdownMenuItem @select.prevent="machineIds = []">
          {{ t('analytics.allMachines') }}
          <IconCheck v-if="!machineIds.length" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          v-for="m in machines" :key="m.id"
          @select.prevent="toggle(machineIds, m.id)"
        >
          {{ m.name }}
          <IconCheck v-if="machineIds.includes(m.id)" class="ml-auto size-4" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>

    <!-- Categories -->
    <DropdownMenu @update:open="(open: boolean) => { if (!open) loadAll() }">
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconCategory class="size-4" />
          {{ categoryLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="max-h-80 w-64 overflow-y-auto">
        <DropdownMenuItem @select.prevent="categoryIds = []">
          {{ t('analytics.allCategories') }}
          <IconCheck v-if="!categoryIds.length" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          v-for="c in categories" :key="c.id"
          @select.prevent="toggle(categoryIds, c.id)"
        >
          {{ c.name }}
          <IconCheck v-if="categoryIds.includes(c.id)" class="ml-auto size-4" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
</template>
```

If `DropdownMenu*` is not auto-imported in this project, add the explicit import used elsewhere — check with `grep -rn "from '@/components/ui/dropdown-menu'" management-frontend/app | head -3` and follow that file's pattern. Same for the `@tabler/icons-vue` icons (`IconCalendar`, `IconCheck`, `IconBuildingStore`, `IconCategory`).

- [ ] **Step 4: Write `management-frontend/app/components/analytics/AnalyticsKpiRow.vue`**

```vue
<script setup lang="ts">
import { deltaPct, metricValue, type AnalyticsMetric } from '~/lib/analytics'

const { t } = useI18n()
const { summary, metric, previousRangeLabel } = useAnalytics()

const empty = {
  units: 0, revenue_gross: 0, revenue_net: 0, cost_net: 0, gross_profit: 0,
  avg_ticket: 0, avg_daily_units: 0, avg_daily_revenue: 0, avg_daily_gross_profit: 0,
}

const cards = computed(() => {
  const totals = summary.value?.totals ?? empty
  const previous = summary.value?.previous ?? empty
  const defs: { key: AnalyticsMetric; label: string; money: boolean }[] = [
    { key: 'revenue', label: t('analytics.revenue'), money: true },
    { key: 'units', label: t('analytics.units'), money: false },
    { key: 'grossProfit', label: t('analytics.grossProfit'), money: true },
  ]
  return defs.map(def => {
    const value = metricValue(totals, def.key)
    return {
      ...def,
      display: def.money ? formatCurrency(value) : String(value),
      delta: deltaPct(value, metricValue(previous, def.key)),
    }
  })
})
</script>

<template>
  <div class="space-y-1">
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <Card
        v-for="card in cards" :key="card.key"
        :class="metric === card.key ? 'border-primary' : ''"
      >
        <CardHeader class="pb-2">
          <CardDescription>{{ card.label }}</CardDescription>
          <CardTitle class="text-2xl tabular-nums">{{ card.display }}</CardTitle>
        </CardHeader>
        <CardContent class="pt-0">
          <span
            v-if="card.delta !== null"
            class="text-xs font-semibold"
            :class="card.delta >= 0 ? 'text-green-600' : 'text-red-600'"
          >{{ card.delta >= 0 ? '▲' : '▼' }} {{ Math.abs(card.delta).toFixed(0) }} %</span>
        </CardContent>
      </Card>
    </div>
    <p class="text-muted-foreground text-xs">
      {{ t('analytics.vsPrevious', { range: previousRangeLabel }) }}
    </p>
    <p v-if="summary?.missing_cost_products" class="text-muted-foreground text-xs">
      {{ t('analytics.costHint', { count: summary.missing_cost_products }) }}
    </p>
  </div>
</template>
```

- [ ] **Step 5: Write `management-frontend/app/components/analytics/AnalyticsTrendChart.vue`**

```vue
<script setup lang="ts">
import { VisAxis, VisStackedBar, VisXYContainer } from '@unovis/vue'
import { chartBucket, metricValue } from '~/lib/analytics'

const { t } = useI18n()
const { summary, metric, bucketedDaily } = useAnalytics()

interface Point { date: Date; value: number }

const points = computed<Point[]>(() =>
  bucketedDaily.value.map(p => ({
    date: new Date(`${p.day}T00:00:00`),
    value: metricValue(p, metric.value),
  })))

const isWeekly = computed(() => chartBucket(summary.value?.range.days ?? 30) === 'week')

const tickFormat = (d: Date) =>
  d.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' })
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.trend') }}</CardDescription>
    </CardHeader>
    <CardContent>
      <p v-if="!points.length" class="text-muted-foreground py-8 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <VisXYContainer v-else :data="points" class="h-56 w-full">
        <VisStackedBar
          :x="(d: Point) => d.date"
          :y="[(d: Point) => d.value]"
          color="var(--primary)"
          :bar-padding="isWeekly ? 0.3 : 0.2"
          :rounded-corners="4"
        />
        <VisAxis type="x" :tick-format="tickFormat" :tick-line="false" :domain-line="false" />
        <VisAxis type="y" :num-ticks="3" :tick-line="false" :domain-line="false" />
      </VisXYContainer>
    </CardContent>
  </Card>
</template>
```

- [ ] **Step 6: Write `management-frontend/app/pages/analytics.vue`**

The breakdown, heatmap and channel components arrive in Tasks 13–14.

```vue
<script setup lang="ts">
const { t } = useI18n()
const {
  metric, summary, loading, error, backendUnsupported,
  loadAll, loadFilterOptions,
} = useAnalytics()

onMounted(async () => {
  await loadFilterOptions()
  if (!summary.value) await loadAll()
})

usePullToRefresh(async () => { await loadAll() })
</script>

<template>
  <div class="space-y-4 p-4">
    <div>
      <h1 class="text-2xl font-bold">{{ t('analytics.title') }}</h1>
      <p class="text-muted-foreground text-sm">{{ t('analytics.subtitle') }}</p>
    </div>

    <Alert v-if="backendUnsupported" variant="destructive">
      <AlertDescription>{{ t('analytics.unsupported') }}</AlertDescription>
    </Alert>

    <template v-else>
      <AnalyticsFilterBar />
      <AnalyticsKpiRow />

      <Tabs v-model="metric">
        <TabsList>
          <TabsTrigger value="units">{{ t('analytics.units') }}</TabsTrigger>
          <TabsTrigger value="revenue">{{ t('analytics.revenue') }}</TabsTrigger>
          <TabsTrigger value="grossProfit">{{ t('analytics.profit') }}</TabsTrigger>
        </TabsList>
      </Tabs>

      <AnalyticsTrendChart />

      <Alert v-if="error" variant="destructive">
        <AlertDescription>{{ error }}</AlertDescription>
      </Alert>

      <div v-if="loading" class="text-muted-foreground text-sm">…</div>
    </template>
  </div>
</template>
```

- [ ] **Step 7: Verify in the browser**

Start the dev server via the preview tooling (never `npm run dev` in a shell), open `/analytics`, and confirm:
- the sidebar shows "Analytics"/"Auswertung" and routes there
- the three KPI cards show values and a delta
- switching the metric tabs re-renders the chart
- switching the range chip to "Last 7 days" changes both KPIs and chart
- the browser console is free of errors

- [ ] **Step 8: Commit**

```bash
git add management-frontend/app/pages/analytics.vue management-frontend/app/components/analytics management-frontend/app/components/AppSidebar.vue management-frontend/i18n/locales/en.json management-frontend/i18n/locales/de.json
git commit -m "feat(frontend): add analytics page with filters, KPIs and trend chart"
```

---

## Task 13: PWA — breakdown table

**Files:**
- Create: `management-frontend/app/components/analytics/AnalyticsBreakdown.vue`
- Modify: `management-frontend/app/pages/analytics.vue`

**Interfaces:**
- Consumes: `useAnalytics()`, `metricValue`, `deltaPct`.
- Produces: `AnalyticsBreakdown` emitting `select` with a `BreakdownRow` (consumed by Task 15).

- [ ] **Step 1: Write `management-frontend/app/components/analytics/AnalyticsBreakdown.vue`**

```vue
<script setup lang="ts">
import { deltaPct, metricValue, type BreakdownRow } from '~/lib/analytics'

const emit = defineEmits<{ select: [row: BreakdownRow] }>()

const { t } = useI18n()
const { dimension, metric, sortedRows, loadingRows, loadBreakdown } = useAnalytics()

watch(dimension, () => { loadBreakdown() })

const maxValue = computed(() =>
  Math.max(0, ...sortedRows.value.map(r => metricValue(r, metric.value))))

function display(value: number) {
  return metric.value === 'units'
    ? String(Math.round(value))
    : formatCurrency(value)
}

function avgDaily(row: BreakdownRow) {
  const v = metric.value === 'units'
    ? row.avg_daily_units
    : metric.value === 'revenue' ? row.avg_daily_revenue : row.avg_daily_gross_profit
  return t('analytics.perDay', { value: metric.value === 'units' ? v.toFixed(1) : formatCurrency(v) })
}

function subtitle(row: BreakdownRow) {
  const parts = [avgDaily(row)]
  if (metric.value !== 'units') parts.push(t('analytics.nPieces', { count: row.units }))
  if (!row.has_cost && metric.value === 'grossProfit') parts.push(t('analytics.noPurchasePrice'))
  if (dimension.value === 'product' && row.machine_count) {
    parts.push(t('analytics.nMachines', { count: row.machine_count }))
  } else if (dimension.value !== 'product' && row.product_count) {
    parts.push(t('analytics.nProducts', { count: row.product_count }))
  }
  return parts.join(' · ')
}

const abcColor = (cls: string) =>
  cls === 'A' ? 'bg-green-100 text-green-700'
    : cls === 'B' ? 'bg-amber-100 text-amber-700'
      : 'bg-red-100 text-red-700'

function onRowClick(row: BreakdownRow) {
  if (dimension.value !== 'product' || !row.key) return
  emit('select', row)
}
</script>

<template>
  <Card>
    <CardHeader class="gap-3 pb-2">
      <CardDescription>{{ t('analytics.breakdown') }}</CardDescription>
      <Tabs v-model="dimension">
        <TabsList>
          <TabsTrigger value="product">{{ t('analytics.products') }}</TabsTrigger>
          <TabsTrigger value="category">{{ t('analytics.categories') }}</TabsTrigger>
          <TabsTrigger value="machine">{{ t('analytics.machines') }}</TabsTrigger>
        </TabsList>
      </Tabs>
    </CardHeader>

    <CardContent>
      <p v-if="loadingRows && !sortedRows.length" class="text-muted-foreground py-8 text-center text-sm">…</p>
      <p v-else-if="!sortedRows.length" class="text-muted-foreground py-8 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-else class="space-y-0.5">
        <button
          v-for="row in sortedRows" :key="row.key ?? row.label"
          type="button"
          class="relative flex w-full items-center gap-3 overflow-hidden rounded-md px-2 py-2 text-left"
          :class="dimension === 'product' && row.key ? 'hover:bg-accent cursor-pointer' : 'cursor-default'"
          @click="onRowClick(row)"
        >
          <span
            class="bg-primary/10 absolute inset-y-0 left-0"
            :style="{ width: maxValue > 0 ? `${(metricValue(row, metric) / maxValue) * 100}%` : '0%' }"
          />
          <span class="relative flex min-w-0 flex-1 items-center gap-2">
            <span
              v-if="dimension === 'product'"
              class="rounded px-1 py-0.5 text-[10px] font-bold"
              :class="abcColor(row.abc_class)"
            >{{ row.abc_class }}</span>
            <span class="min-w-0">
              <span class="block truncate text-sm font-medium">{{ row.label }}</span>
              <span class="text-muted-foreground block truncate text-xs">{{ subtitle(row) }}</span>
            </span>
          </span>
          <span class="relative text-right">
            <span class="block text-sm font-semibold tabular-nums">
              {{ display(metricValue(row, metric)) }}
            </span>
            <span
              v-if="deltaPct(metricValue(row, metric), metric === 'units' ? row.prev_units : metric === 'revenue' ? row.prev_revenue_gross : row.prev_gross_profit) !== null"
              class="block text-xs font-semibold"
              :class="metricValue(row, metric) >= (metric === 'units' ? row.prev_units : metric === 'revenue' ? row.prev_revenue_gross : row.prev_gross_profit) ? 'text-green-600' : 'text-red-600'"
            >
              {{ (deltaPct(metricValue(row, metric), metric === 'units' ? row.prev_units : metric === 'revenue' ? row.prev_revenue_gross : row.prev_gross_profit) ?? 0).toFixed(0) }} %
            </span>
          </span>
        </button>
      </div>
    </CardContent>
  </Card>
</template>
```

- [ ] **Step 2: Mount it in the page**

In `management-frontend/app/pages/analytics.vue`, add a `selectedRow` ref and the component below `<AnalyticsTrendChart />`:

```vue
      <AnalyticsBreakdown @select="row => (selectedRow = row)" />
```

with, in the script block:

```ts
import type { BreakdownRow } from '~/lib/analytics'
const selectedRow = ref<BreakdownRow | null>(null)
```

- [ ] **Step 3: Verify in the browser**

Confirm switching the metric tabs reorders the rows without a network request (watch the network panel), switching the dimension issues exactly one RPC call, ABC badges only appear for products, and rows are only clickable in the product dimension.

- [ ] **Step 4: Commit**

```bash
git add management-frontend/app/components/analytics/AnalyticsBreakdown.vue management-frontend/app/pages/analytics.vue
git commit -m "feat(frontend): add analytics breakdown table"
```

---

## Task 14: PWA — heatmap and channel split

**Files:**
- Create: `management-frontend/app/components/analytics/AnalyticsHeatmap.vue`
- Create: `management-frontend/app/components/analytics/AnalyticsChannelSplit.vue`
- Modify: `management-frontend/app/pages/analytics.vue`

**Interfaces:**
- Consumes: `useAnalytics()`, `heatIntensity`.
- Produces: `AnalyticsHeatmap`, `AnalyticsChannelSplit` — both read the composable, no props.

- [ ] **Step 1: Write `management-frontend/app/components/analytics/AnalyticsHeatmap.vue`**

```vue
<script setup lang="ts">
import { heatIntensity } from '~/lib/analytics'

const { t } = useI18n()
const { summary } = useAnalytics()

/** 2-hour columns keep the grid readable on a phone. */
const HOUR_STEP = 2
const hours = Array.from({ length: 24 / HOUR_STEP }, (_, i) => i * HOUR_STEP)
const days = [1, 2, 3, 4, 5, 6, 7]

/** Locale weekday names, reordered from Sunday-first to ISO Monday-first. */
const weekdayNames = computed(() => {
  const fmt = new Intl.DateTimeFormat(undefined, { weekday: 'short' })
  // 2026-06-01 is a Monday.
  return days.map(d => fmt.format(new Date(Date.UTC(2026, 5, d))))
})

const buckets = computed(() => {
  const map = new Map<number, number>()
  for (const cell of summary.value?.heatmap ?? []) {
    const key = cell.dow * 100 + Math.floor(cell.hour / HOUR_STEP) * HOUR_STEP
    map.set(key, (map.get(key) ?? 0) + cell.units)
  }
  return map
})

const maxUnits = computed(() => Math.max(0, ...buckets.value.values()))

const cellStyle = (dow: number, hour: number) => {
  const units = buckets.value.get(dow * 100 + hour) ?? 0
  return { opacity: String(0.08 + heatIntensity(units, maxUnits.value) * 0.92) }
}
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.peakHours') }}</CardDescription>
    </CardHeader>
    <CardContent>
      <p v-if="!summary?.heatmap.length" class="text-muted-foreground py-6 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-else class="overflow-x-auto">
        <div class="min-w-[320px] space-y-1">
          <div v-for="(dow, i) in days" :key="dow" class="flex items-center gap-1">
            <span class="text-muted-foreground w-8 shrink-0 text-right text-[10px]">
              {{ weekdayNames[i] }}
            </span>
            <span
              v-for="hour in hours" :key="hour"
              class="bg-primary h-4 flex-1 rounded-sm"
              :style="cellStyle(dow, hour)"
              :title="`${weekdayNames[i]} ${hour}:00`"
            />
          </div>
          <div class="flex items-center gap-1">
            <span class="w-8 shrink-0" />
            <span
              v-for="hour in hours" :key="hour"
              class="text-muted-foreground flex-1 text-center text-[9px]"
            >{{ hour % 6 === 0 ? hour : '' }}</span>
          </div>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
```

- [ ] **Step 2: Write `management-frontend/app/components/analytics/AnalyticsChannelSplit.vue`**

```vue
<script setup lang="ts">
const { t } = useI18n()
const { summary } = useAnalytics()

const channels = computed(() => summary.value?.channels ?? [])
const total = computed(() => channels.value.reduce((sum, c) => sum + c.revenue_gross, 0))

const label = (raw: string) => {
  const key = raw.toLowerCase()
  if (key === 'cash') return t('analytics.cash')
  if (key === 'cashless' || key === 'card') return t('analytics.cashless')
  return t('analytics.unknown')
}

const barClass = (raw: string) => {
  const key = raw.toLowerCase()
  if (key === 'cash') return 'bg-green-500'
  if (key === 'cashless' || key === 'card') return 'bg-blue-500'
  return 'bg-gray-400'
}
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardDescription>{{ t('analytics.paymentMethods') }}</CardDescription>
    </CardHeader>
    <CardContent class="space-y-3">
      <p v-if="!channels.length" class="text-muted-foreground py-6 text-center text-sm">
        {{ t('analytics.noSales') }}
      </p>
      <div v-for="channel in channels" :key="channel.channel" class="space-y-1">
        <div class="flex items-center gap-2 text-sm">
          <span class="flex-1">{{ label(channel.channel) }}</span>
          <span class="font-semibold tabular-nums">{{ formatCurrency(channel.revenue_gross) }}</span>
          <span class="text-muted-foreground w-12 text-right text-xs">
            {{ total > 0 ? Math.round((channel.revenue_gross / total) * 100) : 0 }} %
          </span>
        </div>
        <div class="bg-muted h-1.5 w-full rounded-full">
          <div
            class="h-1.5 rounded-full" :class="barClass(channel.channel)"
            :style="{ width: total > 0 ? `${(channel.revenue_gross / total) * 100}%` : '0%' }"
          />
        </div>
        <p class="text-muted-foreground text-xs">
          {{ t('analytics.nPieces', { count: channel.units }) }} · ⌀ {{ formatCurrency(channel.avg_ticket) }}
        </p>
      </div>
    </CardContent>
  </Card>
</template>
```

- [ ] **Step 3: Mount both in the page**

Below `<AnalyticsBreakdown …/>` in `management-frontend/app/pages/analytics.vue`:

```vue
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <AnalyticsHeatmap />
        <AnalyticsChannelSplit />
      </div>
```

- [ ] **Step 4: Verify in the browser**

Confirm the heatmap darkens during business hours, the weekday labels start at Monday, and the channel percentages sum to 100 %. Resize to mobile width and confirm the two cards stack and the heatmap scrolls horizontally instead of overflowing the page.

- [ ] **Step 5: Commit**

```bash
git add management-frontend/app/components/analytics/AnalyticsHeatmap.vue management-frontend/app/components/analytics/AnalyticsChannelSplit.vue management-frontend/app/pages/analytics.vue
git commit -m "feat(frontend): add analytics heatmap and channel split"
```

---

## Task 15: PWA — product detail dialog

**Files:**
- Create: `management-frontend/app/components/analytics/AnalyticsProductDialog.vue`
- Modify: `management-frontend/app/pages/analytics.vue`

**Interfaces:**
- Consumes: `useAnalytics().loadProductMachines(productId)` (Task 11), `BreakdownRow`, `deltaPct`.
- Produces: `AnalyticsProductDialog` with `v-model:open` and a `row: BreakdownRow | null` prop.

- [ ] **Step 1: Write `management-frontend/app/components/analytics/AnalyticsProductDialog.vue`**

```vue
<script setup lang="ts">
import { deltaPct, type BreakdownRow } from '~/lib/analytics'

const props = defineProps<{ row: BreakdownRow | null }>()
const open = defineModel<boolean>('open', { required: true })

const { t } = useI18n()
const { loadProductMachines } = useAnalytics()
const { getProductImageUrl } = useProducts()

const machineRows = ref<BreakdownRow[]>([])
const loading = ref(false)

watch(() => props.row?.key, async key => {
  if (!key) { machineRows.value = []; return }
  loading.value = true
  machineRows.value = await loadProductMachines(key)
  loading.value = false
}, { immediate: true })

const maxUnits = computed(() => Math.max(0, ...machineRows.value.map(r => r.units)))

const revenueDelta = computed(() =>
  props.row ? deltaPct(props.row.revenue_gross, props.row.prev_revenue_gross) : null)

function machineSubtitle(machine: BreakdownRow) {
  const parts = [t('analytics.perDay', { value: machine.avg_daily_units.toFixed(1) })]
  if (machine.total_capacity > 0) {
    parts.push(t('analytics.stockOf', {
      current: machine.total_stock, capacity: machine.total_capacity,
    }))
    if (machine.total_stock === 0) parts.push(t('analytics.empty'))
  }
  return parts.join(' · ')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent v-if="row" class="max-h-[85vh] overflow-y-auto sm:max-w-lg">
      <DialogHeader>
        <DialogTitle class="flex items-center gap-3">
          <img
            v-if="row.image_path" :src="getProductImageUrl(row.image_path)"
            class="size-10 rounded object-cover" alt=""
          >
          <span>{{ row.label }}</span>
        </DialogTitle>
        <DialogDescription>
          {{ t('analytics.abcClass', { class: row.abc_class, share: row.share_pct.toFixed(1) }) }}
        </DialogDescription>
      </DialogHeader>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.units') }}</p>
          <p class="text-lg font-semibold tabular-nums">{{ row.units }}</p>
        </div>
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.revenue') }}</p>
          <p class="text-lg font-semibold tabular-nums">{{ formatCurrency(row.revenue_gross) }}</p>
          <p
            v-if="revenueDelta !== null" class="text-xs font-semibold"
            :class="revenueDelta >= 0 ? 'text-green-600' : 'text-red-600'"
          >{{ revenueDelta.toFixed(0) }} %</p>
        </div>
        <div>
          <p class="text-muted-foreground text-xs">{{ t('analytics.grossProfit') }}</p>
          <p class="text-lg font-semibold tabular-nums">
            {{ row.has_cost ? formatCurrency(row.gross_profit) : '—' }}
          </p>
          <p v-if="!row.has_cost" class="text-muted-foreground text-xs">
            {{ t('analytics.noPurchasePrice') }}
          </p>
        </div>
      </div>

      <div class="space-y-2">
        <p class="text-muted-foreground text-xs uppercase">{{ t('analytics.perMachine') }}</p>
        <p v-if="loading" class="text-muted-foreground text-sm">…</p>
        <p v-else-if="!machineRows.length" class="text-muted-foreground text-sm">
          {{ t('analytics.noSales') }}
        </p>
        <div v-for="machine in machineRows" v-else :key="machine.key ?? machine.label" class="space-y-1">
          <div class="flex items-center gap-2 text-sm">
            <span class="flex-1 truncate">{{ machine.label }}</span>
            <span class="font-semibold tabular-nums">{{ machine.units }}</span>
          </div>
          <div class="bg-muted h-1.5 w-full rounded-full">
            <div
              class="bg-primary h-1.5 rounded-full"
              :style="{ width: maxUnits > 0 ? `${(machine.units / maxUnits) * 100}%` : '0%' }"
            />
          </div>
          <p class="text-xs" :class="machine.total_stock === 0 ? 'text-red-600' : 'text-muted-foreground'">
            {{ machineSubtitle(machine) }}
          </p>
        </div>
      </div>

      <div class="space-y-1">
        <p class="text-muted-foreground text-xs uppercase">{{ t('analytics.stock') }}</p>
        <p v-if="row.sell_through_pct !== null" class="text-muted-foreground text-sm">
          {{ t('analytics.sellThrough', { value: row.sell_through_pct.toFixed(1) }) }}
        </p>
        <p v-if="row.days_of_supply !== null" class="text-muted-foreground text-sm">
          {{ t('analytics.daysOfSupply', { days: row.days_of_supply.toFixed(0) }) }}
        </p>
      </div>
    </DialogContent>
  </Dialog>
</template>
```

- [ ] **Step 2: Mount it in the page**

In `management-frontend/app/pages/analytics.vue`, add to the script block:

```ts
const dialogOpen = ref(false)
```

change the breakdown handler to open the dialog:

```vue
      <AnalyticsBreakdown @select="row => { selectedRow = row; dialogOpen = true }" />
```

and add at the end of the template:

```vue
      <AnalyticsProductDialog v-model:open="dialogOpen" :row="selectedRow" />
```

- [ ] **Step 3: Verify in the browser**

Click a product row, confirm the dialog opens with per-machine bars, that machines with zero stock render their subtitle in red, and that the machine unit counts sum to the product's total.

- [ ] **Step 4: Run the full test suite**

```bash
cd management-frontend && npx vitest run
```

Expected: no failures.

- [ ] **Step 5: Commit**

```bash
git add management-frontend/app/components/analytics/AnalyticsProductDialog.vue management-frontend/app/pages/analytics.vue
git commit -m "feat(frontend): add analytics product detail dialog"
```

---

## Final verification

Run before declaring the feature done:

```bash
cd Docker/supabase && ./tests/run-sql-tests.sh
```

```bash
cd management-frontend && npx vitest run
```

```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Manual checks across both clients:
- Same filters produce the same numbers in iOS and PWA (compare units and revenue for a 30-day window, all machines).
- The sum of the breakdown rows' units equals the KPI unit count, including the "Unknown" row.
- A machine filter of one machine yields totals matching that machine's detail page for the same period.
- Pointing the iOS app at a server without the two migrations shows the "backend too old" screen rather than a raw error.



