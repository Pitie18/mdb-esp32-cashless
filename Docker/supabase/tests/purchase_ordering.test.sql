-- Ordering from suppliers: manual order-list entries, placing an order,
-- booking its delivery into the warehouse (once), closing an order booked by
-- hand, and sales velocity by the product actually sold.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_other    uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_viewer   uuid := gen_random_uuid();
  v_outsider uuid := gen_random_uuid();
  v_wh       uuid;
  v_sup      uuid;
  v_cola     uuid := gen_random_uuid();
  v_mate     uuid := gen_random_uuid();
  v_machine  uuid;
  v_order    uuid;
  v_order2   uuid;
  v_line_c   uuid;
  v_line_m   uuid;
  v_batch    uuid;
  v_res      jsonb;
  v_failed   boolean;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Order Co'), (v_other, 'Other Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin,    '00000000-0000-0000-0000-000000000000', 'po-admin@test.local', now()),
    (v_viewer,   '00000000-0000-0000-0000-000000000000', 'po-viewer@test.local', now()),
    (v_outsider, '00000000-0000-0000-0000-000000000000', 'po-out@test.local', now());
  INSERT INTO public.users (id, company, email, first_name) VALUES
    (v_admin, v_company, 'po-admin@test.local', 'Ada'),
    (v_viewer, v_company, 'po-viewer@test.local', NULL),
    (v_outsider, v_other, 'po-out@test.local', NULL)
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company, first_name = EXCLUDED.first_name;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES
    (v_company, v_admin, 'admin'), (v_company, v_viewer, 'viewer'), (v_other, v_outsider, 'admin');
  INSERT INTO public.warehouses (company_id, name) VALUES (v_company, 'Lager') RETURNING id INTO v_wh;
  INSERT INTO public.suppliers (company_id, name, lead_time_days, order_interval_days)
    VALUES (v_company, 'Becker', 2, 14) RETURNING id INTO v_sup;
  INSERT INTO public.products (id, name, company, sellprice, order_unit_size) VALUES
    (v_cola, 'Cola', v_company, 2.00, 24),
    (v_mate, 'Mate', v_company, 2.50, 1);
  INSERT INTO public.warehouse_stock_batches (warehouse_id, product_id, batch_number, expiration_date, quantity, company_id)
    VALUES (v_wh, v_cola, NULL, '2027-03-31', 5, v_company) RETURNING id INTO v_batch;

  -- ── Order list ────────────────────────────────────────────────────────────
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  PERFORM public.add_to_order_list(v_wh, v_mate, 'ios');
  PERFORM public.add_to_order_list(v_wh, v_mate, 'web');
  ASSERT (SELECT count(*) FROM public.order_list_items WHERE warehouse_id = v_wh) = 1, 'one entry per product';
  ASSERT (SELECT added_via FROM public.order_list_items WHERE product_id = v_mate) = 'ios', 'first entry kept';
  ASSERT (SELECT added_by_name FROM public.order_list_items WHERE product_id = v_mate) = 'po-viewer@test.local',
    'name falls back to the email';

  -- A viewer may not place an order
  v_failed := false;
  BEGIN
    PERFORM public.place_purchase_order(v_wh, v_sup, jsonb_build_array(jsonb_build_object('product_id', v_mate, 'quantity', 6)));
  EXCEPTION WHEN insufficient_privilege THEN v_failed := true;
  END;
  ASSERT v_failed, 'viewer must not place orders';

  -- Another company may not touch this warehouse
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_outsider::text, 'role', 'authenticated')::text, true);
  v_failed := false;
  BEGIN
    PERFORM public.add_to_order_list(v_wh, v_cola, 'web');
  EXCEPTION WHEN insufficient_privilege THEN v_failed := true;
  END;
  ASSERT v_failed, 'outsider must not add to the list';

  -- ── Place an order ────────────────────────────────────────────────────────
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);
  PERFORM public.add_to_order_list(v_wh, v_cola, 'web');
  v_order := public.place_purchase_order(v_wh, v_sup, jsonb_build_array(
    jsonb_build_object('product_id', v_cola, 'quantity', 48, 'unit_size', 24, 'unit_price_net', 0.52),
    jsonb_build_object('product_id', v_mate, 'quantity', 6),
    jsonb_build_object('product_id', v_mate, 'quantity', 0)   -- zero lines are dropped
  ), 'per mail');
  ASSERT (SELECT status FROM public.purchase_orders WHERE id = v_order) = 'ordered', 'order is on the way';
  ASSERT (SELECT ordered_by_name FROM public.purchase_orders WHERE id = v_order) = 'Ada', 'who ordered';
  ASSERT (SELECT supplier_name FROM public.purchase_orders WHERE id = v_order) = 'Becker', 'supplier snapshot';
  ASSERT (SELECT count(*) FROM public.purchase_order_lines WHERE order_id = v_order) = 2, 'two lines';
  ASSERT NOT EXISTS (SELECT 1 FROM public.order_list_items WHERE warehouse_id = v_wh),
    'ordered products leave the manual list';

  SELECT id INTO v_line_c FROM public.purchase_order_lines WHERE order_id = v_order AND product_id = v_cola;
  SELECT id INTO v_line_m FROM public.purchase_order_lines WHERE order_id = v_order AND product_id = v_mate;

  -- A supplier of another company is rejected
  v_failed := false;
  BEGIN
    PERFORM public.place_purchase_order(v_wh, gen_random_uuid(), jsonb_build_array(jsonb_build_object('product_id', v_cola, 'quantity', 1)));
  EXCEPTION WHEN no_data_found THEN v_failed := true;
  END;
  ASSERT v_failed, 'unknown supplier must fail';

  -- ── Receive it (as a viewer) ──────────────────────────────────────────────
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  v_res := public.receive_purchase_order(v_order, jsonb_build_array(
    -- 47 of 48 cola came, same best-before as the existing batch: merged
    jsonb_build_object('line_id', v_line_c, 'quantity', 47, 'expiration_date', '2027-03-31'),
    -- 6 mate with a batch number: a new batch
    jsonb_build_object('line_id', v_line_m, 'quantity', 6, 'expiration_date', '2027-01-15', 'batch_number', 'M7')
  ));
  ASSERT v_res->>'status' = 'received' AND (v_res->>'booked')::int = 2 AND (v_res->>'units')::int = 53,
    'delivery booked: ' || v_res::text;
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 52, 'merged into the batch';
  ASSERT (SELECT supplier_id FROM public.warehouse_stock_batches WHERE id = v_batch) = v_sup, 'batch gets the supplier';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE product_id = v_mate AND batch_number = 'M7') = 6,
    'new batch for mate';
  ASSERT (SELECT count(*) FROM public.warehouse_transactions
           WHERE reference_id = v_order::text AND transaction_type = 'incoming' AND supplier_id = v_sup) = 2,
    'two incoming transactions with the supplier';
  ASSERT (SELECT (metadata->>'ordered_quantity')::int FROM public.warehouse_transactions
           WHERE reference_id = v_order::text AND product_id = v_cola) = 48, 'ordered quantity recorded';
  ASSERT (SELECT received_quantity FROM public.purchase_order_lines WHERE id = v_line_c) = 47, 'received quantity';

  -- A retry books nothing
  v_res := public.receive_purchase_order(v_order, jsonb_build_array(
    jsonb_build_object('line_id', v_line_c, 'quantity', 47)));
  ASSERT (v_res->>'booked')::int = 0, 'retry books nothing';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 52, 'stock unchanged by retry';

  -- ── Close an order booked by hand / cancel ───────────────────────────────
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);
  v_order2 := public.place_purchase_order(v_wh, v_sup, jsonb_build_array(jsonb_build_object('product_id', v_cola, 'quantity', 24)));
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  v_failed := false;
  BEGIN
    PERFORM public.cancel_purchase_order(v_order2);
  EXCEPTION WHEN insufficient_privilege THEN v_failed := true;
  END;
  ASSERT v_failed, 'viewer must not cancel';
  ASSERT public.close_purchase_order(v_order2) = 'closed', 'viewer closes a delivered order';
  ASSERT (SELECT quantity FROM public.warehouse_stock_batches WHERE id = v_batch) = 52, 'closing books no stock';
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);
  ASSERT public.cancel_purchase_order(v_order2) = 'closed', 'a finished order cannot be cancelled';

  -- ── Velocity by the product sold ──────────────────────────────────────────
  INSERT INTO public."vendingMachine" (company, name) VALUES (v_company, 'VM') RETURNING id INTO v_machine;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 11, v_cola, 10, 10);
  INSERT INTO public.sales (machine_id, item_number, item_price, product_id, created_at) VALUES
    (v_machine, 11, 2.0, v_cola, now() - interval '1 day'),
    (v_machine, 11, 2.0, v_cola, now() - interval '2 days'),
    (v_machine, 11, 2.0, NULL, now() - interval '3 days');
  -- An old sale from before the snapshot existed
  UPDATE public.sales SET product_id = NULL WHERE machine_id = v_machine AND created_at < now() - interval '60 hours';
  -- The slot now holds mate: cola keeps its snapshotted sales
  UPDATE public.machine_trays SET product_id = v_mate WHERE machine_id = v_machine AND item_number = 11;
  ASSERT (SELECT units_sold FROM public.get_product_sales_velocity(v_company, 30) WHERE product_id = v_cola) = 2,
    'snapshotted sales stay with cola';
  ASSERT (SELECT units_sold FROM public.get_product_sales_velocity(v_company, 30) WHERE product_id = v_mate) = 1,
    'a sale without a snapshot falls back to the slot';

  RAISE NOTICE 'purchase_ordering: all assertions passed';
END $$;

ROLLBACK;
