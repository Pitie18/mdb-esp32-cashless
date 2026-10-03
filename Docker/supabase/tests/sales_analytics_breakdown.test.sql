-- Integration test for get_sales_analytics_breakdown
-- (migration 20260811010100_sales_analytics_breakdown.sql).
--
-- Rolled-back transaction, plain ASSERTs, fake JWT — same pattern as
-- sales_analytics_summary.test.sql. Run via tests/run-sql-tests.sh.
--
-- Revenue split in the window: BigSeller 17.00 (85 %), MidSeller 2.00 (10 %),
-- Tail 1.00 (5 %). Pareto classes are assigned by the cumulative share BEFORE
-- each row, so the dominant seller lands in A rather than being pushed to B
-- by its own weight.

BEGIN;

SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_other   uuid := gen_random_uuid();
  v_user    uuid := gen_random_uuid();
  v_cat_a   uuid := gen_random_uuid();
  v_cat_b   uuid := gen_random_uuid();
  v_big     uuid := gen_random_uuid();
  v_mid     uuid := gen_random_uuid();
  v_small   uuid := gen_random_uuid();
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
  INSERT INTO public.product_purchase_prices
    (company_id, product_id, supplier_id, price_net, price_gross, price_basis, tax_rate, observed_on)
    SELECT v_company, v_big, s.id, 0.50, 0.60, 'net', 0.19, DATE '2026-01-01'
    FROM public.suppliers s WHERE s.company_id = v_company;

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

  -- The sales trigger decrements current_stock, so pin the stock levels the
  -- assertions below expect AFTER the sales have been inserted.
  UPDATE public.machine_trays SET current_stock = 4 WHERE machine_id = v_mach_1 AND item_number = 11;
  UPDATE public.machine_trays SET current_stock = 6 WHERE machine_id = v_mach_2 AND item_number = 11;
  UPDATE public.machine_trays SET current_stock = 5 WHERE machine_id = v_mach_1 AND item_number IN (12, 13);

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
  -- BigSeller holds 85 % on its own: classifying by the share BEFORE the row
  -- keeps it in A. Everything up to 95 % cumulative is B, the tail is C.
  ASSERT (row_big->>'abc_class') = 'A', format('expected A, got %s', row_big->>'abc_class');
  ASSERT (row_big->>'share_pct')::numeric = 85.00,
    format('expected 85.00 share, got %s', row_big->>'share_pct');
  ASSERT (SELECT e->>'abc_class' FROM json_array_elements(r) e WHERE e->>'key' = v_mid::text) = 'B',
    'MidSeller must be class B';
  ASSERT (SELECT e->>'abc_class' FROM json_array_elements(r) e WHERE e->>'key' = v_small::text) = 'C',
    'Tail must be class C';

  -- ─── 4. Rows without cost are marked, not silently zeroed ────────────────
  ASSERT (SELECT (e->>'has_cost')::boolean FROM json_array_elements(r) e
          WHERE e->>'key' = v_mid::text) IS FALSE,
    'MidSeller has no EK price and must be flagged';
  ASSERT (SELECT (e->>'gross_profit')::numeric FROM json_array_elements(r) e
          WHERE e->>'key' = v_mid::text) = 0,
    'a row without an EK price must not invent a gross profit';

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

  -- ─── 8. Machine filter ───────────────────────────────────────────────────
  r := public.get_sales_analytics_breakdown(
         v_company, v_from, v_to, ARRAY[v_mach_2], NULL, 'Europe/Berlin', 'product', NULL);
  ASSERT (SELECT (e->>'units')::int FROM json_array_elements(r) e
          WHERE e->>'key' = v_big::text) = 5,
    'restricting to M2 must leave only its 5 BigSeller units';
  ASSERT (SELECT (e->>'total_capacity')::int FROM json_array_elements(r) e
          WHERE e->>'key' = v_big::text) = 10,
    'capacity must only count trays of the filtered machines';

  -- ─── 9. Invalid dimension rejected ───────────────────────────────────────
  BEGIN
    r := public.get_sales_analytics_breakdown(
           v_company, v_from, v_to, NULL, NULL, 'UTC', 'supplier', NULL);
    RAISE EXCEPTION 'expected invalid dimension rejection';
  EXCEPTION WHEN sqlstate 'P0001' THEN
    IF SQLERRM = 'expected invalid dimension rejection' THEN RAISE; END IF;
  END;

  -- ─── 10. Tenant isolation ────────────────────────────────────────────────
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
