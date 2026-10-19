-- Recently removed products (refill review cool-down): removals are recorded
-- where the offering closes, a product that only moves within the machine
-- leaves no removal behind, and get_recent_product_removals returns what was
-- removed within the company's cool-down together with its sales while it
-- was offered.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_other_co uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_dev      uuid := gen_random_uuid();
  v_machine  uuid;
  v_machine2 uuid;
  v_other_m  uuid;
  v_cola     uuid := gen_random_uuid();
  v_schorle  uuid := gen_random_uuid();
  v_redbull  uuid := gen_random_uuid();
  v_mars     uuid := gen_random_uuid();
  v_t11      uuid;
  v_t12      uuid;
  v_t13      uuid;
  v_req      uuid;
  v_items    jsonb;
  v_res      jsonb;
  v_row      jsonb;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Removal Co'), (v_other_co, 'Other Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin, '00000000-0000-0000-0000-000000000000', 'removal-admin@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'removal-admin@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('Schule', v_company, v_dev) RETURNING id INTO v_machine;
  INSERT INTO public."vendingMachine" (name, company)
    VALUES ('Halle', v_company) RETURNING id INTO v_machine2;
  INSERT INTO public."vendingMachine" (name, company)
    VALUES ('Fremd', v_other_co) RETURNING id INTO v_other_m;
  INSERT INTO public.products (id, name, company, sellprice) VALUES
    (v_cola, 'Cola', v_company, 2.00),
    (v_schorle, 'Schorle', v_company, 1.80),
    (v_redbull, 'Red Bull', v_company, 2.50),
    (v_mars, 'Mars', v_company, 1.20);

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 11, v_cola, 10, 4) RETURNING id INTO v_t11;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 12, v_schorle, 10, 6) RETURNING id INTO v_t12;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 13, v_redbull, 8, 0) RETURNING id INTO v_t13;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine2, 11, v_redbull, 8, 3);
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_other_m, 11, v_mars, 8, 3);

  UPDATE public.machine_product_offerings SET offered_since = now() - interval '21 days'
   WHERE machine_id IN (v_machine, v_machine2, v_other_m);

  -- Red bull sold 3× here while offered, once before (an earlier offering),
  -- and twice in the other machine — only the 3 belong to this offering.
  INSERT INTO public.sales (machine_id, item_number, item_price, channel, created_at, product_id) VALUES
    (v_machine,  13, 2.50, 'cash', now() - interval '20 days', v_redbull),
    (v_machine,  13, 2.50, 'cash', now() - interval '15 days', v_redbull),
    (v_machine,  13, 2.50, 'cash', now() - interval '3 days',  v_redbull),
    (v_machine,  13, 2.50, 'cash', now() - interval '40 days', v_redbull),
    (v_machine2, 11, 2.50, 'cash', now() - interval '2 days',  v_redbull),
    (v_machine2, 11, 2.50, 'cash', now() - interval '1 days',  v_redbull);
  UPDATE public.sales SET product_id = v_redbull WHERE machine_id IN (v_machine, v_machine2);

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  -- ─── 1. A swap (products only move) leaves no removal behind ────────────
  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', v_schorle, 'to_capacity', 10),
    jsonb_build_object('tray_id', v_t12, 'to_product_id', v_cola,    'to_capacity', 10)
  ));
  SELECT jsonb_agg(jsonb_build_object('item_id', i.id, 'action', 'done',
           'removed', 0, 'filled', 5, 'price_set', true))
    INTO v_items
    FROM public.slot_change_request_items i
   WHERE i.request_id = v_req AND i.status = 'pending';
  PERFORM public.apply_slot_change(v_req, 'tour-swap', NULL, v_items, '[]'::jsonb);

  ASSERT (SELECT product_id FROM public.machine_trays WHERE id = v_t11) = v_schorle, 'swap applied';
  ASSERT NOT EXISTS (SELECT 1 FROM public.machine_product_removals WHERE machine_id = v_machine),
    'a swap must not record a removal';

  -- ─── 2. Replacing red bull through the review records its removal ───────
  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t13, 'to_product_id', v_mars, 'to_capacity', 8)
  ));
  SELECT jsonb_agg(jsonb_build_object('item_id', i.id, 'action', 'done',
           'removed', 0, 'filled', 8, 'price_set', true))
    INTO v_items
    FROM public.slot_change_request_items i
   WHERE i.request_id = v_req AND i.status = 'pending';
  PERFORM public.apply_slot_change(v_req, 'tour-replace', NULL, v_items, '[]'::jsonb);

  ASSERT (SELECT count(*) FROM public.machine_product_removals
           WHERE machine_id = v_machine AND product_id = v_redbull) = 1, 'red bull removal recorded';
  ASSERT (SELECT offered_since FROM public.machine_product_removals
           WHERE machine_id = v_machine AND product_id = v_redbull) = now() - interval '21 days',
    'removal keeps the offering start';
  ASSERT NOT EXISTS (SELECT 1 FROM public.machine_product_removals
           WHERE machine_id = v_machine2), 'other machine untouched';

  -- Pretend the rebuild was a week ago (everything here shares one now()).
  UPDATE public.machine_product_removals SET removed_at = now() - interval '7 days'
   WHERE machine_id = v_machine AND product_id = v_redbull;
  -- A sale after the removal does not belong to the offering.
  UPDATE public.sales SET created_at = now() - interval '8 days'
   WHERE machine_id = v_machine AND created_at = now() - interval '3 days';

  v_res := public.get_recent_product_removals(ARRAY[v_machine, v_machine2, v_other_m]);
  ASSERT (v_res->>'cooldown_days')::int = 30, 'default cool-down is 30 days, got ' || v_res::text;
  ASSERT jsonb_array_length(v_res->'removals') = 1, 'one recent removal, got ' || v_res::text;
  v_row := v_res->'removals'->0;
  ASSERT (v_row->>'machine_id')::uuid = v_machine, 'removal machine';
  ASSERT (v_row->>'product_id')::uuid = v_redbull, 'removal product';
  ASSERT (v_row->>'units_sold')::int = 3, 'units sold while offered, got ' || v_row::text;
  ASSERT (v_row->>'revenue_eur')::numeric = 7.50, 'revenue while offered, got ' || v_row::text;
  ASSERT (v_row->>'days_offered')::int = 14, 'days offered, got ' || v_row::text;
  ASSERT (v_row->>'estimated')::boolean = false, 'recorded, not estimated';

  -- ─── 3. Cool-down setting ────────────────────────────────────────────────
  UPDATE public.companies SET product_removal_cooldown_days = 5 WHERE id = v_company;
  v_res := public.get_recent_product_removals(ARRAY[v_machine]);
  ASSERT jsonb_array_length(v_res->'removals') = 0, 'outside a 5-day cool-down, got ' || v_res::text;

  UPDATE public.companies SET product_removal_cooldown_days = 0 WHERE id = v_company;
  v_res := public.get_recent_product_removals(ARRAY[v_machine]);
  ASSERT (v_res->>'cooldown_days')::int = 0 AND jsonb_array_length(v_res->'removals') = 0,
    '0 turns the hint off, got ' || v_res::text;

  UPDATE public.companies SET product_removal_cooldown_days = 30 WHERE id = v_company;

  -- ─── 4. Back in the machine: no longer flagged ───────────────────────────
  -- (slot 12 held cola since the swap, so cola is the one removed now)
  UPDATE public.machine_trays SET product_id = v_redbull WHERE id = v_t12;
  v_res := public.get_recent_product_removals(ARRAY[v_machine]);
  ASSERT jsonb_array_length(v_res->'removals') = 1
     AND (v_res->'removals'->0->>'product_id')::uuid = v_cola,
    're-added red bull is not flagged, removed cola is, got ' || v_res::text;

  -- ─── 5. Another company's machine is never visible ──────────────────────
  UPDATE public.machine_trays SET product_id = NULL WHERE machine_id = v_other_m;
  ASSERT EXISTS (SELECT 1 FROM public.machine_product_removals WHERE machine_id = v_other_m),
    'other company removal recorded';
  v_res := public.get_recent_product_removals(ARRAY[v_other_m]);
  ASSERT jsonb_array_length(v_res->'removals') = 0, 'foreign machine hidden, got ' || v_res::text;

  -- ─── 6. Deleting a product or a machine still works ─────────────────────
  PERFORM set_config('request.jwt.claims', '', true);
  DELETE FROM public.products WHERE id = v_schorle;
  ASSERT NOT EXISTS (SELECT 1 FROM public.machine_product_removals WHERE product_id = v_schorle),
    'deleted product leaves no removal';
  DELETE FROM public."vendingMachine" WHERE id = v_machine2;
  ASSERT NOT EXISTS (SELECT 1 FROM public.machine_product_removals WHERE machine_id = v_machine2),
    'deleted machine leaves no removal';

  RAISE NOTICE 'recent_product_removals: all assertions passed';
END $$;

ROLLBACK;
