-- return_slot_change_leftovers: slot change leftovers booked once at the end
-- of the tour. Van units go back to the tour's own batch, machine units become
-- a return batch with the confirmed best-before date, waste only shows in the
-- result, and a retry replays the first result without booking twice.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_viewer   uuid := gen_random_uuid();
  v_wh       uuid;
  v_cola     uuid := gen_random_uuid();
  v_mate     uuid := gen_random_uuid();
  v_batch    uuid;
  v_res      jsonb;
  v_qty      int;
  v_failed   boolean := false;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Return Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin,  '00000000-0000-0000-0000-000000000000', 'ret-admin@test.local', now()),
    (v_viewer, '00000000-0000-0000-0000-000000000000', 'ret-viewer@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES
    (v_admin, v_company, 'ret-admin@test.local'),
    (v_viewer, v_company, 'ret-viewer@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES
    (v_company, v_admin, 'admin'), (v_company, v_viewer, 'viewer');
  INSERT INTO public.warehouses (company_id, name) VALUES (v_company, 'Lager') RETURNING id INTO v_wh;
  INSERT INTO public.products (id, name, company, sellprice) VALUES
    (v_cola, 'Cola', v_company, 2.00),
    (v_mate, 'Mate', v_company, 2.50);

  -- Packing took 6 cola from one batch for tour T1 (now 4 left in it)
  INSERT INTO public.warehouse_stock_batches (warehouse_id, product_id, batch_number, expiration_date, quantity, company_id)
    VALUES (v_wh, v_cola, 'L1', '2027-01-31', 4, v_company) RETURNING id INTO v_batch;
  INSERT INTO public.warehouse_transactions (company_id, warehouse_id, product_id, batch_id, transaction_type,
      quantity_change, quantity_before, quantity_after, metadata)
    VALUES (v_company, v_wh, v_cola, v_batch, 'outgoing_refill', -6, 10, 4, jsonb_build_object('tour_id', 'T1'));

  -- A viewer may not book
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  BEGIN
    PERFORM public.return_slot_change_leftovers('T1', v_wh, '[]'::jsonb);
  EXCEPTION WHEN insufficient_privilege THEN
    v_failed := true;
  END;
  ASSERT v_failed, 'viewer must not book leftovers';

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  v_res := public.return_slot_change_leftovers('T1', v_wh, jsonb_build_array(
    -- 2 unused cola from the van, 3 cola taken out of a machine
    jsonb_build_object('product_id', v_cola, 'van_qty', 2, 'machine_qty', 3,
      'destination', 'warehouse', 'expiration_date', '2026-12-24', 'batch_number', 'Machine return'),
    -- mate taken out of a machine and thrown away
    jsonb_build_object('product_id', v_mate, 'van_qty', 0, 'machine_qty', 5, 'destination', 'waste')
  ));

  ASSERT (v_res->>'was_already_applied')::boolean = false, 'first call books';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 6,
    'van units go back to the tour batch';
  SELECT quantity INTO v_qty FROM public.warehouse_stock_batches
   WHERE product_id = v_cola AND id <> v_batch AND expiration_date = '2026-12-24' AND batch_number = 'Machine return';
  ASSERT v_qty = 3, 'machine units become a return batch, got ' || coalesce(v_qty::text, 'none');
  ASSERT NOT EXISTS (SELECT 1 FROM public.warehouse_stock_batches WHERE product_id = v_mate),
    'waste books no stock';
  ASSERT jsonb_array_length(v_res->'waste') = 1 AND (v_res->'waste'->0->>'quantity')::int = 5, 'waste reported';
  ASSERT (SELECT count(*) FROM public.activity_log
           WHERE company_id = v_company AND action = 'slot_change_leftovers_returned' AND entity_id = 'T1') = 1,
    'one activity entry';

  -- Retry replays, books nothing twice
  v_res := public.return_slot_change_leftovers('T1', v_wh, jsonb_build_array(
    jsonb_build_object('product_id', v_cola, 'van_qty', 2, 'machine_qty', 3, 'destination', 'warehouse')));
  ASSERT (v_res->>'was_already_applied')::boolean, 'retry is a replay';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 6, 'retry books nothing';
  ASSERT (SELECT count(*) FROM public.warehouse_stock_batches WHERE product_id = v_cola) = 2, 'no second return batch';

  -- Van units more than the tour deducted spill into a new batch
  v_res := public.return_slot_change_leftovers('T2', v_wh, jsonb_build_array(
    jsonb_build_object('product_id', v_cola, 'van_qty', 1, 'machine_qty', 0, 'destination', 'warehouse')));
  ASSERT (SELECT count(*) FROM public.warehouse_stock_batches WHERE product_id = v_cola) = 3,
    'unmatched van units become a new batch';

  -- Returning to the warehouse needs a warehouse
  v_failed := false;
  BEGIN
    PERFORM public.return_slot_change_leftovers('T3', NULL, jsonb_build_array(
      jsonb_build_object('product_id', v_cola, 'van_qty', 0, 'machine_qty', 1, 'destination', 'warehouse')));
  EXCEPTION WHEN invalid_parameter_value THEN
    v_failed := true;
  END;
  ASSERT v_failed, 'warehouse destination without a warehouse must fail';

  RAISE NOTICE 'slot_change_tour_returns: all assertions passed';
END $$;

ROLLBACK;
