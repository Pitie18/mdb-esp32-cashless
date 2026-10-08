-- Slot change requests: save (no tray change), quittance at the machine
-- (slot switch, skip, leftover returns), idempotency, withdraw, auth.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_viewer   uuid := gen_random_uuid();
  v_dev      uuid := gen_random_uuid();
  v_machine  uuid;
  v_wh       uuid;
  v_cola     uuid := gen_random_uuid();
  v_schorle  uuid := gen_random_uuid();
  v_redbull  uuid := gen_random_uuid();
  v_t11      uuid;
  v_t12      uuid;
  v_t13      uuid;
  v_batch    uuid;
  v_req      uuid;
  v_req2     uuid;
  v_i12      uuid;
  v_i13      uuid;
  v_res      jsonb;
  v_tray     record;
  v_item     record;
  n          int;
  v_failed   boolean;
BEGIN
  -- ── Fixtures ──
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Slot Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin,  '00000000-0000-0000-0000-000000000000', 'slot-admin@test.local',  now()),
    (v_viewer, '00000000-0000-0000-0000-000000000000', 'slot-viewer@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES
    (v_admin,  v_company, 'slot-admin@test.local'),
    (v_viewer, v_company, 'slot-viewer@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES
    (v_company, v_admin,  'admin'),
    (v_company, v_viewer, 'viewer');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('Bahnhof', v_company, v_dev) RETURNING id INTO v_machine;

  INSERT INTO public.products (id, name, company, sellprice) VALUES
    (v_cola,    'Cola',    v_company, 2.00),
    (v_schorle, 'Schorle', v_company, 1.80),
    (v_redbull, 'Red Bull', v_company, 2.50);

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 11, v_cola, 10, 3) RETURNING id INTO v_t11;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock, fill_when_below)
    VALUES (v_machine, 12, v_schorle, 10, 8, 5) RETURNING id INTO v_t12;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock, fill_when_below)
    VALUES (v_machine, 13, v_redbull, 8, 2, 7) RETURNING id INTO v_t13;

  INSERT INTO public.warehouses (company_id, name) VALUES (v_company, 'Lager') RETURNING id INTO v_wh;
  INSERT INTO public.warehouse_stock_batches (warehouse_id, product_id, batch_number, expiration_date, quantity, company_id)
    VALUES (v_wh, v_cola, 'C-1', DATE '2027-01-01', 20, v_company) RETURNING id INTO v_batch;

  -- ── 1. Viewers cannot save a plan ──
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  v_failed := false;
  BEGIN
    PERFORM public.save_slot_change_request(v_machine,
      jsonb_build_array(jsonb_build_object('tray_id', v_t12, 'to_product_id', v_cola, 'to_capacity', 10)));
  EXCEPTION WHEN insufficient_privilege THEN v_failed := true;
  END;
  ASSERT v_failed, 'a viewer must not be able to save a change request';

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  -- ── 2. Saving stores a request and leaves the trays untouched ──
  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', v_cola,    'to_capacity', 10),  -- no-op, dropped
    jsonb_build_object('tray_id', v_t12, 'to_product_id', v_cola,    'to_capacity', 10),  -- schorle → cola
    jsonb_build_object('tray_id', v_t13, 'to_product_id', v_redbull, 'to_capacity', 6)    -- 8 → 6 spiral
  ));
  ASSERT v_req IS NOT NULL, 'save must return the request id';
  SELECT count(*) INTO n FROM public.slot_change_request_items WHERE request_id = v_req AND status = 'pending';
  ASSERT n = 2, 'no-op items must be dropped, got ' || n;

  SELECT * INTO v_item FROM public.slot_change_request_items WHERE request_id = v_req AND tray_id = v_t12;
  v_i12 := v_item.id;
  ASSERT v_item.from_product_id = v_schorle AND v_item.to_product_id = v_cola, 'snapshot products';
  ASSERT v_item.from_capacity = 10 AND v_item.to_capacity = 10, 'snapshot capacity';
  ASSERT v_item.from_price = 1.80 AND v_item.to_price = 2.00, 'snapshot prices';
  SELECT id INTO v_i13 FROM public.slot_change_request_items WHERE request_id = v_req AND tray_id = v_t13;

  SELECT * INTO v_tray FROM public.machine_trays WHERE id = v_t12;
  ASSERT v_tray.product_id = v_schorle AND v_tray.current_stock = 8, 'saving must not touch the tray';

  -- Saving again edits the same open request (one per machine)
  v_req2 := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t12, 'to_product_id', v_cola,    'to_capacity', 10),
    jsonb_build_object('tray_id', v_t13, 'to_product_id', v_redbull, 'to_capacity', 6)
  ));
  ASSERT v_req2 = v_req, 're-saving must reuse the open request';
  SELECT count(*) INTO n FROM public.slot_change_requests WHERE machine_id = v_machine AND status = 'open';
  ASSERT n = 1, 'only one open request per machine';
  SELECT id INTO v_i12 FROM public.slot_change_request_items WHERE request_id = v_req AND tray_id = v_t12 AND status = 'pending';
  SELECT id INTO v_i13 FROM public.slot_change_request_items WHERE request_id = v_req AND tray_id = v_t13 AND status = 'pending';

  -- A tray of another machine is rejected
  v_failed := false;
  BEGIN
    PERFORM public.save_slot_change_request(v_machine, jsonb_build_array(
      jsonb_build_object('tray_id', gen_random_uuid(), 'to_product_id', v_cola, 'to_capacity', 10)));
  EXCEPTION WHEN invalid_parameter_value THEN v_failed := true;
  END;
  ASSERT v_failed, 'foreign tray must be rejected';

  -- ── 3. Packing: the tour takes 10 cola from batch C-1 ──
  PERFORM public.deduct_warehouse_stock_fifo(v_wh, v_cola, 10, v_admin, v_machine::text,
    'Refill tour', jsonb_build_object('tour_id', 'tour-1'));
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 10, 'packing deducts';

  -- A sale of the old product between packing and quittance
  UPDATE public.machine_trays SET current_stock = 7 WHERE id = v_t12;

  -- ── 4. Quittance: slot 12 rebuilt, slot 13 skipped ──
  v_res := public.apply_slot_change(v_req, 'tour-1', v_wh,
    jsonb_build_array(
      jsonb_build_object('item_id', v_i12, 'action', 'done', 'removed', 7, 'filled', 7, 'price_set', true),
      jsonb_build_object('item_id', v_i13, 'action', 'skip')
    ),
    jsonb_build_array(
      jsonb_build_object('product_id', v_cola, 'van_qty', 3, 'machine_qty', 0, 'destination', 'warehouse'),
      jsonb_build_object('product_id', v_schorle, 'van_qty', 0, 'machine_qty', 7, 'destination', 'warehouse',
                         'expiration_date', '2026-12-01', 'batch_number', 'Rückgabe aus Automat')
    ));
  ASSERT (v_res->>'was_already_applied')::boolean = false, 'first apply is not a replay';
  ASSERT v_res->>'request_status' = 'open', 'request stays open while a slot is pending';

  SELECT * INTO v_tray FROM public.machine_trays WHERE id = v_t12;
  ASSERT v_tray.product_id = v_cola, 'slot 12 now holds cola';
  ASSERT v_tray.capacity = 10 AND v_tray.current_stock = 7, 'slot 12 stock = filled, got ' || v_tray.current_stock;

  SELECT * INTO v_tray FROM public.machine_trays WHERE id = v_t13;
  ASSERT v_tray.capacity = 8 AND v_tray.current_stock = 2, 'skipped slot untouched';
  SELECT * INTO v_item FROM public.slot_change_request_items WHERE id = v_i13;
  ASSERT v_item.status = 'pending' AND v_item.skip_count = 1, 'skipped item stays pending';
  SELECT * INTO v_item FROM public.slot_change_request_items WHERE id = v_i12;
  ASSERT v_item.status = 'done' AND v_item.removed_qty = 7 AND v_item.filled_qty = 7 AND v_item.price_set,
    'done item records the quittance';

  -- Van goods back to the batch they came from
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 13,
    'unused cola must return to batch C-1';
  -- Machine goods into a new batch with the confirmed best-before date
  SELECT count(*) INTO n FROM public.warehouse_stock_batches
   WHERE warehouse_id = v_wh AND product_id = v_schorle AND quantity = 7
     AND expiration_date = DATE '2026-12-01' AND batch_number = 'Rückgabe aus Automat';
  ASSERT n = 1, 'schorle from the machine must become a new batch';
  SELECT count(*) INTO n FROM public.warehouse_transactions
   WHERE company_id = v_company AND transaction_type = 'adjustment_refill_return';
  ASSERT n = 2, 'two return transactions, got ' || n;
  SELECT count(*) INTO n FROM public.activity_log
   WHERE company_id = v_company AND action = 'slot_change_applied';
  ASSERT n = 1, 'one activity entry';

  -- ── 5. Retrying the same tour changes nothing ──
  v_res := public.apply_slot_change(v_req, 'tour-1', v_wh,
    jsonb_build_array(jsonb_build_object('item_id', v_i12, 'action', 'done', 'removed', 7, 'filled', 7)),
    jsonb_build_array(jsonb_build_object('product_id', v_cola, 'van_qty', 3, 'destination', 'warehouse')));
  ASSERT (v_res->>'was_already_applied')::boolean, 'retry must be reported as a replay';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 13, 'retry must not book twice';

  -- ── 6. Next tour: the skipped slot is rebuilt, the request closes ──
  v_res := public.apply_slot_change(v_req, 'tour-2', v_wh,
    jsonb_build_array(jsonb_build_object('item_id', v_i13, 'action', 'done', 'removed', 2, 'filled', 9)),
    jsonb_build_array(jsonb_build_object('product_id', v_redbull, 'machine_qty', 0, 'van_qty', 0, 'destination', 'waste')));
  ASSERT v_res->>'request_status' = 'done', 'request is done once nothing is pending';
  SELECT * INTO v_tray FROM public.machine_trays WHERE id = v_t13;
  ASSERT v_tray.capacity = 6 AND v_tray.current_stock = 6, 'filled is clamped to the new capacity';
  ASSERT v_tray.fill_when_below = 6, 'thresholds are clamped to the new capacity';
  ASSERT (SELECT status FROM public.slot_change_requests WHERE id = v_req) = 'done', 'request closed';

  -- A closed request cannot be applied by a new tour
  v_failed := false;
  BEGIN
    PERFORM public.apply_slot_change(v_req, 'tour-3', v_wh, '[]'::jsonb, '[]'::jsonb);
  EXCEPTION WHEN object_not_in_prerequisite_state THEN v_failed := true;
  END;
  ASSERT v_failed, 'closed request must not apply again';

  -- ── 7. Withdraw, and an empty plan withdraws as well ──
  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', NULL, 'to_capacity', 10)));
  ASSERT v_req IS NOT NULL, 'emptying a slot is a change';
  PERFORM public.withdraw_slot_change_request(v_req);
  ASSERT (SELECT status FROM public.slot_change_requests WHERE id = v_req) = 'withdrawn', 'withdrawn';
  ASSERT (SELECT status FROM public.slot_change_request_items WHERE request_id = v_req LIMIT 1) = 'withdrawn',
    'items withdrawn with it';

  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', v_redbull, 'to_capacity', 10)));
  v_req2 := public.save_slot_change_request(v_machine, '[]'::jsonb);
  ASSERT v_req2 IS NULL, 'empty plan returns NULL';
  ASSERT (SELECT status FROM public.slot_change_requests WHERE id = v_req) = 'withdrawn', 'empty plan withdraws';

  SELECT * INTO v_tray FROM public.machine_trays WHERE id = v_t11;
  ASSERT v_tray.product_id = v_cola AND v_tray.current_stock = 3, 'withdrawn plans never touch trays';

  RAISE NOTICE 'slot_change_requests: all assertions passed';
END;
$$;

ROLLBACK;
