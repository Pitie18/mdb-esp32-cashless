-- Integration test for get_sales_analytics_summary
-- (migration 20260811000000_sales_analytics_summary.sql).
--
-- Runs inside one transaction that is rolled back at the end → no dev data touched.
-- Plain ASSERT statements in a DO block (no pgTAP). Fake JWT via set_config so the
-- SECURITY DEFINER function's my_company_id() check can be exercised.
--
-- Requires `supabase start` + `supabase migration up`.
-- Run via Docker/supabase/tests/run-sql-tests.sh.
--
-- Fixture window: 2026-07-01T00:00+02 .. 2026-07-11T00:00+02 (10 days, Berlin).
--   Cola   (Drinks, EK 0.40 net) sold 3x in window, 1x in the previous window
--   Bar    (Sweets, no EK)       sold 2x in window (one of them legacy)
--   NoCost (Drinks, no EK)       sold 1x in window
--   one unresolvable sale (item_number 99, no tray)
--   one sale exactly on the exclusive upper bound (must NOT count)

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
  v_legacy   uuid;
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
  INSERT INTO public.product_purchase_prices
    (company_id, product_id, supplier_id, price_net, price_gross, price_basis, tax_rate, observed_on)
    SELECT v_company, v_prod_a, s.id, 0.40, 0.48, 'net', 0.19, DATE '2026-01-01'
    FROM public.suppliers s WHERE s.company_id = v_company;

  -- ─── Sales ───────────────────────────────────────────────────────────────
  -- Boundary probes: exactly at from (counts), one microsecond before to
  -- (counts), exactly at to (does NOT count).
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

  -- Legacy sale: product_id must be NULL so the machine_trays fallback is
  -- exercised. The BEFORE INSERT trigger stamps product_id unconditionally,
  -- so it has to be cleared afterwards (the trigger is INSERT-only).
  INSERT INTO public.sales (machine_id, item_number, product_id, item_price, price_net, channel, created_at)
    VALUES (v_mach_1, 12, NULL, 2.00, 1.68, 'cash', '2026-07-07 10:00+02')
    RETURNING id INTO v_legacy;
  UPDATE public.sales SET product_id = NULL WHERE id = v_legacy;

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
  -- Cola sold 3x in window: (0.84 - 0.40) * 3 = 1.32. Bar/NoCost have no EK.
  ASSERT (r->'totals'->>'gross_profit')::numeric = 1.32,
    format('expected 1.32 gross profit, got %s', r->'totals'->>'gross_profit');
  -- Products sold without any EK notation: Bar, NoCost → 2
  ASSERT (r->>'missing_cost_products')::int = 2,
    format('expected 2 products without cost, got %s', r->>'missing_cost_products');

  -- ─── 4. Legacy sale resolved via machine_trays, not dropped ──────────────
  -- The legacy sale (item 12, product_id NULL) must be attributed to Bar, so
  -- it does NOT show up as an unknown-product unit.
  ASSERT (r->>'unknown_product_units')::int = 1,
    format('expected 1 unknown-product unit, got %s', r->>'unknown_product_units');

  -- ─── 5. Daily series is gapless ──────────────────────────────────────────
  ASSERT json_array_length(r->'daily') = 10,
    format('expected 10 daily buckets, got %s', json_array_length(r->'daily'));

  -- ─── 6. Timezone affects bucketing ───────────────────────────────────────
  -- The 2026-07-10 23:59:59.999999+02 sale is 21:59 UTC on 2026-07-10, but
  -- 23:59 local on the same (Friday) day in Berlin.
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
  -- Drinks = Cola (3x) + NoCost (1x) = 4.
  r := public.get_sales_analytics_summary(v_company, v_from, v_to, NULL, ARRAY[v_cat_a], 'Europe/Berlin');
  ASSERT (r->'totals'->>'units')::int = 4,
    format('expected 4 Drinks units, got %s', r->'totals'->>'units');
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
