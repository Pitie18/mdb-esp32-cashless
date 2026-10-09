-- Slot change quittance must not restart a product's offering when the
-- product only moves within the machine (swap, or moving to another slot):
-- the Analysis tab would otherwise show it as "testing" again.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_dev      uuid := gen_random_uuid();
  v_machine  uuid;
  v_cola     uuid := gen_random_uuid();
  v_schorle  uuid := gen_random_uuid();
  v_redbull  uuid := gen_random_uuid();
  v_t11      uuid;
  v_t12      uuid;
  v_t13      uuid;
  v_t14      uuid;
  v_req      uuid;
  v_items    jsonb;
  v_since    timestamptz;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Offer Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin, '00000000-0000-0000-0000-000000000000', 'offer-admin@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'offer-admin@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('Schule', v_company, v_dev) RETURNING id INTO v_machine;
  INSERT INTO public.products (id, name, company, sellprice) VALUES
    (v_cola, 'Cola', v_company, 2.00),
    (v_schorle, 'Schorle', v_company, 1.80),
    (v_redbull, 'Red Bull', v_company, 2.50);

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 11, v_cola, 10, 4) RETURNING id INTO v_t11;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 12, v_schorle, 10, 6) RETURNING id INTO v_t12;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 13, v_redbull, 8, 5) RETURNING id INTO v_t13;
  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 14, NULL, 8, 0) RETURNING id INTO v_t14;

  -- All three have been offered for two months
  UPDATE public.machine_product_offerings SET offered_since = now() - interval '60 days'
   WHERE machine_id = v_machine;

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  -- Swap cola ↔ schorle, move red bull to the empty slot 14
  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', v_schorle, 'to_capacity', 10),
    jsonb_build_object('tray_id', v_t12, 'to_product_id', v_cola,    'to_capacity', 10),
    jsonb_build_object('tray_id', v_t13, 'to_product_id', NULL,      'to_capacity', 8),
    jsonb_build_object('tray_id', v_t14, 'to_product_id', v_redbull, 'to_capacity', 8)
  ));
  SELECT jsonb_agg(jsonb_build_object('item_id', i.id, 'action', 'done',
           'removed', 0, 'filled', 5, 'price_set', true))
    INTO v_items
    FROM public.slot_change_request_items i
   WHERE i.request_id = v_req AND i.status = 'pending';

  PERFORM public.apply_slot_change(v_req, 'tour-offer', NULL, v_items, '[]'::jsonb);

  ASSERT (SELECT product_id FROM public.machine_trays WHERE id = v_t11) = v_schorle, 'swap applied';
  ASSERT (SELECT product_id FROM public.machine_trays WHERE id = v_t14) = v_redbull, 'move applied';

  SELECT offered_since INTO v_since FROM public.machine_product_offerings
   WHERE machine_id = v_machine AND product_id = v_cola;
  ASSERT v_since < now() - interval '59 days', 'swapped cola must keep its offering, got ' || v_since;
  SELECT offered_since INTO v_since FROM public.machine_product_offerings
   WHERE machine_id = v_machine AND product_id = v_schorle;
  ASSERT v_since < now() - interval '59 days', 'swapped schorle must keep its offering, got ' || v_since;
  SELECT offered_since INTO v_since FROM public.machine_product_offerings
   WHERE machine_id = v_machine AND product_id = v_redbull;
  ASSERT v_since < now() - interval '59 days', 'moved red bull must keep its offering, got ' || v_since;

  RAISE NOTICE 'slot_change_offerings: all assertions passed';
END $$;

ROLLBACK;
