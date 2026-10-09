-- Category age restriction: slot change items snapshot the old and new
-- product's min_age, and apply_slot_change records the refiller's age_set.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_dev      uuid := gen_random_uuid();
  v_machine  uuid;
  v_drinks   uuid;
  v_alcohol  uuid;
  v_cola     uuid := gen_random_uuid();
  v_beer     uuid := gen_random_uuid();
  v_t11      uuid;
  v_req      uuid;
  v_item     record;
  v_failed   boolean;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Age Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin, '00000000-0000-0000-0000-000000000000', 'age-admin@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'age-admin@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('Club', v_company, v_dev) RETURNING id INTO v_machine;

  INSERT INTO public.product_category (name, company) VALUES ('Softdrinks', v_company) RETURNING id INTO v_drinks;
  INSERT INTO public.product_category (name, company, min_age) VALUES ('Bier', v_company, 16) RETURNING id INTO v_alcohol;
  INSERT INTO public.products (id, name, company, sellprice, category) VALUES
    (v_cola, 'Cola', v_company, 2.00, v_drinks),
    (v_beer, 'Bier', v_company, 2.50, v_alcohol);

  v_failed := false;
  BEGIN
    UPDATE public.product_category SET min_age = 0 WHERE id = v_drinks;
  EXCEPTION WHEN check_violation THEN v_failed := true;
  END;
  ASSERT v_failed, 'min_age must be positive';

  INSERT INTO public.machine_trays (machine_id, item_number, product_id, capacity, current_stock)
    VALUES (v_machine, 11, v_cola, 10, 2) RETURNING id INTO v_t11;

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  v_req := public.save_slot_change_request(v_machine, jsonb_build_array(
    jsonb_build_object('tray_id', v_t11, 'to_product_id', v_beer, 'to_capacity', 10)));
  SELECT * INTO v_item FROM public.slot_change_request_items WHERE request_id = v_req AND status = 'pending';
  ASSERT v_item.from_min_age IS NULL AND v_item.to_min_age = 16,
    'snapshot min ages, got ' || coalesce(v_item.from_min_age::text, 'null') || ' → ' || coalesce(v_item.to_min_age::text, 'null');

  PERFORM public.apply_slot_change(v_req, 'tour-age', NULL, jsonb_build_array(jsonb_build_object(
    'item_id', v_item.id, 'action', 'done', 'removed', 2, 'filled', 8, 'price_set', true, 'age_set', true)), '[]'::jsonb);
  SELECT * INTO v_item FROM public.slot_change_request_items WHERE id = v_item.id;
  ASSERT v_item.status = 'done' AND v_item.age_set, 'age_set must be recorded';

  RAISE NOTICE 'category_min_age: all assertions passed';
END $$;

ROLLBACK;
