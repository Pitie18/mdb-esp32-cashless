-- Fix: a product that only moves within the machine during a slot change
-- (swap, or into a freed slot) must keep its offering. apply_slot_change
-- switched the trays one at a time, so the offering trigger saw the product
-- in no slot for a moment, dropped its offering and re-created it with
-- now(): the Analysis tab then showed a long-running product as "testing".
--
-- 1. apply_slot_change remembers each product's offered_since before the
--    switch and restores it for every product still in the machine after.
-- 2. Repair offerings already restarted by an applied slot change: when the
--    product moved within the machine (the same change emptied one of its
--    old slots) and sold there before, the offering goes back to its first
--    sale in that machine.

-- ── 1. Keep the offering during the switch ──────────────────────────────
CREATE OR REPLACE FUNCTION public.apply_slot_change(
  p_request_id   uuid,
  p_tour_id      text,
  p_warehouse_id uuid,
  p_items        jsonb,
  p_leftovers    jsonb DEFAULT '[]'::jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
#variable_conflict use_column
DECLARE
  v_user_id     uuid := auth.uid();
  v_user_email  text;
  v_request     record;
  v_company_id  uuid;
  v_prior       jsonb;
  v_elem        jsonb;
  v_item        record;
  v_tray        record;
  v_removed     integer;
  v_filled      integer;
  v_done        jsonb := '[]'::jsonb;
  v_skipped     jsonb := '[]'::jsonb;
  v_returns     jsonb := '[]'::jsonb;
  v_waste       jsonb := '[]'::jsonb;
  v_product_id  uuid;
  v_qty         integer;
  v_left        integer;
  v_take        integer;
  v_src         record;
  v_batch       record;
  v_batch_id    uuid;
  v_machine_name text;
  v_result      jsonb;
  v_open_left   integer;
  v_offerings   jsonb;
BEGIN
  IF p_tour_id IS NULL OR length(trim(p_tour_id)) = 0 THEN
    RAISE EXCEPTION 'tour_id required' USING ERRCODE = '22023';
  END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
    RAISE EXCEPTION 'items must be a JSON array' USING ERRCODE = '22023';
  END IF;
  IF p_leftovers IS NULL THEN
    p_leftovers := '[]'::jsonb;
  END IF;
  IF jsonb_typeof(p_leftovers) <> 'array' THEN
    RAISE EXCEPTION 'leftovers must be a JSON array' USING ERRCODE = '22023';
  END IF;

  SELECT r.* INTO v_request
    FROM public.slot_change_requests r
   WHERE r.id = p_request_id
   FOR UPDATE;
  IF v_request.id IS NULL THEN
    RAISE EXCEPTION 'change request not found' USING ERRCODE = '42704';
  END IF;

  v_company_id := public.slot_change_assert_admin(v_request.machine_id);

  -- Idempotency: a retry of the same tour gets the first result back.
  SELECT a.result INTO v_prior
    FROM public.slot_change_applications a
   WHERE a.request_id = p_request_id AND a.tour_id = p_tour_id;
  IF v_prior IS NOT NULL THEN
    RETURN v_prior || jsonb_build_object('was_already_applied', true);
  END IF;

  IF v_request.status <> 'open' THEN
    RAISE EXCEPTION 'change request is no longer open' USING ERRCODE = '55000';
  END IF;

  IF p_warehouse_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.warehouses w
     WHERE w.id = p_warehouse_id AND w.company_id = v_company_id
  ) THEN
    RAISE EXCEPTION 'warehouse not found' USING ERRCODE = '42704';
  END IF;

  SELECT u.email INTO v_user_email FROM auth.users u WHERE u.id = v_user_id;
  SELECT vm.name INTO v_machine_name FROM public."vendingMachine" vm WHERE vm.id = v_request.machine_id;

  -- Remember since when each product has been offered here. The trays are
  -- switched one by one, and maintain_machine_product_offerings drops a
  -- product's offering the moment it is in no slot — which is briefly true
  -- for a product that only moves (a swap, or into a freed slot), so it
  -- would come back as a brand-new offering and show as "testing".
  SELECT coalesce(jsonb_object_agg(o.product_id::text, o.offered_since), '{}'::jsonb)
    INTO v_offerings
    FROM public.machine_product_offerings o
   WHERE o.machine_id = v_request.machine_id;

  -- ── Slots ────────────────────────────────────────────────────────────────
  FOR v_elem IN SELECT * FROM jsonb_array_elements(p_items)
  LOOP
    SELECT i.* INTO v_item
      FROM public.slot_change_request_items i
     WHERE i.id = (v_elem->>'item_id')::uuid
       AND i.request_id = p_request_id
     FOR UPDATE;
    IF v_item.id IS NULL THEN
      RAISE EXCEPTION 'item % does not belong to this request', v_elem->>'item_id' USING ERRCODE = '22023';
    END IF;
    IF v_item.status <> 'pending' THEN
      CONTINUE;  -- withdrawn or done meanwhile
    END IF;

    IF v_elem->>'action' = 'done' THEN
      SELECT mt.* INTO v_tray
        FROM public.machine_trays mt
       WHERE mt.id = v_item.tray_id
       FOR UPDATE;

      v_removed := GREATEST(0, COALESCE((v_elem->>'removed')::int, 0));
      v_filled  := CASE WHEN v_item.to_product_id IS NULL THEN 0
                        ELSE LEAST(v_item.to_capacity, GREATEST(0, COALESCE((v_elem->>'filled')::int, 0)))
                   END;

      UPDATE public.machine_trays
         SET product_id      = v_item.to_product_id,
             capacity        = v_item.to_capacity,
             current_stock   = v_filled,
             min_stock       = LEAST(COALESCE(min_stock, 0), v_item.to_capacity),
             fill_when_below = LEAST(COALESCE(fill_when_below, 0), v_item.to_capacity)
       WHERE id = v_item.tray_id;

      UPDATE public.slot_change_request_items
         SET status = 'done',
             removed_qty = v_removed,
             filled_qty = v_filled,
             price_set = COALESCE((v_elem->>'price_set')::boolean, false),
             applied_tour_id = p_tour_id,
             applied_at = now(),
             applied_by = v_user_id
       WHERE id = v_item.id;

      v_done := v_done || jsonb_build_object(
        'item_id', v_item.id,
        'tray_id', v_item.tray_id,
        'item_number', v_item.item_number,
        'from_product_id', v_item.from_product_id,
        'to_product_id', v_item.to_product_id,
        'from_capacity', v_tray.capacity,
        'to_capacity', v_item.to_capacity,
        'old_stock', v_tray.current_stock,
        'removed', v_removed,
        'filled', v_filled,
        'price_set', COALESCE((v_elem->>'price_set')::boolean, false)
      );
    ELSIF v_elem->>'action' = 'skip' THEN
      UPDATE public.slot_change_request_items
         SET skip_count = skip_count + 1,
             last_skipped_at = now(),
             last_skip_tour_id = p_tour_id
       WHERE id = v_item.id;
      v_skipped := v_skipped || jsonb_build_object(
        'item_id', v_item.id,
        'item_number', v_item.item_number
      );
    ELSE
      RAISE EXCEPTION 'unknown action %', v_elem->>'action' USING ERRCODE = '22023';
    END IF;
  END LOOP;

  -- A product still in the machine after the switch keeps its old offering.
  UPDATE public.machine_product_offerings o
     SET offered_since = (v_offerings ->> o.product_id::text)::timestamptz
   WHERE o.machine_id = v_request.machine_id
     AND v_offerings ? o.product_id::text
     AND o.offered_since > (v_offerings ->> o.product_id::text)::timestamptz;

  -- ── Leftover goods ───────────────────────────────────────────────────────
  FOR v_elem IN SELECT * FROM jsonb_array_elements(p_leftovers)
  LOOP
    v_product_id := (v_elem->>'product_id')::uuid;
    IF v_product_id IS NULL THEN
      CONTINUE;
    END IF;

    IF COALESCE(v_elem->>'destination', 'warehouse') = 'waste' THEN
      v_qty := GREATEST(0, COALESCE((v_elem->>'van_qty')::int, 0))
             + GREATEST(0, COALESCE((v_elem->>'machine_qty')::int, 0));
      IF v_qty > 0 THEN
        v_waste := v_waste || jsonb_build_object('product_id', v_product_id, 'quantity', v_qty);
      END IF;
      CONTINUE;
    END IF;

    IF p_warehouse_id IS NULL THEN
      RAISE EXCEPTION 'warehouse required to return leftovers' USING ERRCODE = '22023';
    END IF;

    -- Van goods: back to the batches this tour took them from (latest
    -- deduction first), never more than was deducted from a batch.
    v_left := GREATEST(0, COALESCE((v_elem->>'van_qty')::int, 0));
    IF v_left > 0 THEN
      FOR v_src IN
        SELECT wt.batch_id, sum(-wt.quantity_change)::int AS deducted, max(wt.created_at) AS last_at
          FROM public.warehouse_transactions wt
         WHERE wt.company_id = v_company_id
           AND wt.warehouse_id = p_warehouse_id
           AND wt.product_id = v_product_id
           AND wt.transaction_type = 'outgoing_refill'
           AND wt.metadata->>'tour_id' = p_tour_id
           AND wt.batch_id IS NOT NULL
         GROUP BY wt.batch_id
         ORDER BY max(wt.created_at) DESC
      LOOP
        EXIT WHEN v_left <= 0;
        v_take := LEAST(v_left, v_src.deducted);
        SELECT b.* INTO v_batch FROM public.warehouse_stock_batches b WHERE b.id = v_src.batch_id FOR UPDATE;
        IF v_batch.id IS NULL THEN
          CONTINUE;
        END IF;
        UPDATE public.warehouse_stock_batches SET quantity = quantity + v_take WHERE id = v_batch.id;
        INSERT INTO public.warehouse_transactions (
          company_id, warehouse_id, product_id, batch_id, user_id,
          transaction_type, quantity_change, quantity_before, quantity_after,
          batch_number, expiration_date, reference_id, notes, metadata
        ) VALUES (
          v_company_id, p_warehouse_id, v_product_id, v_batch.id, v_user_id,
          'adjustment_refill_return', v_take, v_batch.quantity, v_batch.quantity + v_take,
          v_batch.batch_number, v_batch.expiration_date, v_request.machine_id::text,
          'Slot change: unused', jsonb_build_object(
            'tour_id', p_tour_id, 'request_id', p_request_id,
            'source', 'slot_change', 'origin', 'van', '_user_email', v_user_email)
        );
        v_returns := v_returns || jsonb_build_object(
          'product_id', v_product_id, 'origin', 'van', 'quantity', v_take, 'batch_id', v_batch.id);
        v_left := v_left - v_take;
      END LOOP;
      -- Whatever could not be matched to a batch of this tour is treated like
      -- goods from the machine below (new batch).
    END IF;

    -- Machine goods (+ unmatched van goods): a new return batch.
    v_qty := GREATEST(0, COALESCE((v_elem->>'machine_qty')::int, 0)) + v_left;
    IF v_qty > 0 THEN
      INSERT INTO public.warehouse_stock_batches (
        warehouse_id, product_id, batch_number, expiration_date, quantity, company_id
      ) VALUES (
        p_warehouse_id, v_product_id,
        NULLIF(trim(COALESCE(v_elem->>'batch_number', '')), ''),
        NULLIF(v_elem->>'expiration_date', '')::date,
        v_qty, v_company_id
      )
      RETURNING id INTO v_batch_id;

      INSERT INTO public.warehouse_transactions (
        company_id, warehouse_id, product_id, batch_id, user_id,
        transaction_type, quantity_change, quantity_before, quantity_after,
        batch_number, expiration_date, reference_id, notes, metadata
      ) VALUES (
        v_company_id, p_warehouse_id, v_product_id, v_batch_id, v_user_id,
        'adjustment_refill_return', v_qty, 0, v_qty,
        NULLIF(trim(COALESCE(v_elem->>'batch_number', '')), ''),
        NULLIF(v_elem->>'expiration_date', '')::date,
        v_request.machine_id::text,
        'Slot change: taken out of machine', jsonb_build_object(
          'tour_id', p_tour_id, 'request_id', p_request_id,
          'source', 'slot_change', 'origin', 'machine', '_user_email', v_user_email)
      );
      v_returns := v_returns || jsonb_build_object(
        'product_id', v_product_id, 'origin', 'machine', 'quantity', v_qty, 'batch_id', v_batch_id,
        'expiration_date', NULLIF(v_elem->>'expiration_date', ''));
    END IF;
  END LOOP;

  -- ── Close the request when nothing is pending any more ───────────────────
  SELECT count(*) INTO v_open_left
    FROM public.slot_change_request_items i
   WHERE i.request_id = p_request_id AND i.status = 'pending';

  IF v_open_left = 0 THEN
    UPDATE public.slot_change_requests
       SET status = 'done', closed_at = now(), updated_at = now()
     WHERE id = p_request_id;
  ELSE
    UPDATE public.slot_change_requests SET updated_at = now() WHERE id = p_request_id;
  END IF;

  v_result := jsonb_build_object(
    'request_id', p_request_id,
    'tour_id', p_tour_id,
    'machine_id', v_request.machine_id,
    'done', v_done,
    'skipped', v_skipped,
    'returns', v_returns,
    'waste', v_waste,
    'request_status', CASE WHEN v_open_left = 0 THEN 'done' ELSE 'open' END
  );

  INSERT INTO public.slot_change_applications (request_id, tour_id, company_id, applied_by, result)
  VALUES (p_request_id, p_tour_id, v_company_id, v_user_id, v_result);

  IF jsonb_array_length(v_done) > 0 OR jsonb_array_length(v_skipped) > 0 THEN
    INSERT INTO public.activity_log (company_id, user_id, entity_type, entity_id, action, metadata)
    VALUES (
      v_company_id, v_user_id, 'stock', v_request.machine_id::text, 'slot_change_applied',
      v_result || jsonb_build_object(
        'machine_name', v_machine_name,
        'warehouse_id', p_warehouse_id,
        '_user_email', v_user_email)
    );
  END IF;

  RETURN v_result || jsonb_build_object('was_already_applied', false);
END;
$$;

GRANT EXECUTE ON FUNCTION public.apply_slot_change(uuid, text, uuid, jsonb, jsonb) TO authenticated;


-- ── 2. Repair ────────────────────────────────────────────────────────────
WITH restarted AS (
  SELECT o.machine_id, o.product_id, min(i.applied_at) AS applied_at
    FROM public.machine_product_offerings o
    JOIN public.slot_change_requests r ON r.machine_id = o.machine_id
    JOIN public.slot_change_request_items i
      ON i.request_id = r.id AND i.status = 'done' AND i.to_product_id = o.product_id
   WHERE o.offered_since >= i.applied_at - interval '1 minute'
     -- it only moved: the same change also emptied one of its old slots
     AND EXISTS (
       SELECT 1 FROM public.slot_change_request_items j
        WHERE j.request_id = r.id AND j.status = 'done' AND j.from_product_id = o.product_id
     )
   GROUP BY o.machine_id, o.product_id
), first_sale AS (
  SELECT x.machine_id, x.product_id, min(s.created_at) AS first_sold
    FROM restarted x
    JOIN public.sales s
      ON s.machine_id = x.machine_id AND s.product_id = x.product_id AND s.created_at < x.applied_at
   GROUP BY x.machine_id, x.product_id
)
UPDATE public.machine_product_offerings o
   SET offered_since = f.first_sold
  FROM first_sale f
 WHERE o.machine_id = f.machine_id
   AND o.product_id = f.product_id
   AND o.offered_since > f.first_sold;
