-- ============================================================================
-- Slot change leftovers are booked at the end of the tour, back at the
-- warehouse, instead of at each machine.
--
-- At the machine the refiller only switches the slots (apply_slot_change with
-- an empty leftovers list). The goods taken out of the machines and the
-- packed units that were not used ride along in the van; once every machine
-- is done the refiller looks at them at the warehouse, sets the best-before
-- date and books them in one go with return_slot_change_leftovers.
--
--   slot_change_tour_returns   idempotency record per (company, tour)
--
-- apply_slot_change keeps accepting leftovers, so older app versions that
-- still book them at the machine keep working.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.slot_change_tour_returns (
  company_id  uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  tour_id     text        NOT NULL,
  returned_at timestamptz NOT NULL DEFAULT now(),
  returned_by uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  result      jsonb       NOT NULL,
  PRIMARY KEY (company_id, tour_id)
);

ALTER TABLE public.slot_change_tour_returns ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.slot_change_tour_returns TO authenticated;
GRANT ALL ON public.slot_change_tour_returns TO service_role;

DROP POLICY IF EXISTS slot_change_tour_returns_select ON public.slot_change_tour_returns;
CREATE POLICY slot_change_tour_returns_select ON public.slot_change_tour_returns
  FOR SELECT TO authenticated
  USING (company_id = public.my_company_id());

-- ── return_slot_change_leftovers ────────────────────────────────────────────
-- Book the slot change leftovers of a whole tour.
--
--   p_leftovers: [{"product_id": uuid, "van_qty": int, "machine_qty": int,
--                  "destination": "warehouse"|"waste",
--                  "expiration_date": "YYYY-MM-DD"|null, "batch_number": text}]
--
-- Van units go back to the batches this tour deducted them from (latest
-- first); machine units (and van units no batch of the tour can take) become
-- a new return batch. Waste is recorded in the activity log only: van units
-- were already deducted at packing, machine units were never in stock.
-- Idempotent per (company, tour): a retry gets the first result back.
CREATE OR REPLACE FUNCTION public.return_slot_change_leftovers(
  p_tour_id      text,
  p_warehouse_id uuid,
  p_leftovers    jsonb
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
  v_company_id  uuid;
  v_prior       jsonb;
  v_elem        jsonb;
  v_returns     jsonb := '[]'::jsonb;
  v_waste       jsonb := '[]'::jsonb;
  v_product_id  uuid;
  v_qty         integer;
  v_left        integer;
  v_take        integer;
  v_src         record;
  v_batch       record;
  v_batch_id    uuid;
  v_result      jsonb;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'not authenticated' USING ERRCODE = '42501';
  END IF;
  IF p_tour_id IS NULL OR length(trim(p_tour_id)) = 0 THEN
    RAISE EXCEPTION 'tour_id required' USING ERRCODE = '22023';
  END IF;
  IF p_leftovers IS NULL OR jsonb_typeof(p_leftovers) <> 'array' THEN
    RAISE EXCEPTION 'leftovers must be a JSON array' USING ERRCODE = '22023';
  END IF;

  IF p_warehouse_id IS NOT NULL THEN
    SELECT w.company_id INTO v_company_id FROM public.warehouses w WHERE w.id = p_warehouse_id;
    IF v_company_id IS NULL THEN
      RAISE EXCEPTION 'warehouse not found' USING ERRCODE = '42704';
    END IF;
  ELSE
    v_company_id := public.my_company_id();
  END IF;

  IF v_company_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.organization_members om
     WHERE om.company_id = v_company_id
       AND om.user_id    = v_user_id
       AND om.role       = 'admin'
  ) THEN
    RAISE EXCEPTION 'not authorized' USING ERRCODE = '42501';
  END IF;

  -- Serialise retries of the same tour, then replay a finished one.
  PERFORM pg_advisory_xact_lock(hashtext('slot_change_tour_returns:' || v_company_id::text || ':' || p_tour_id));
  SELECT r.result INTO v_prior
    FROM public.slot_change_tour_returns r
   WHERE r.company_id = v_company_id AND r.tour_id = p_tour_id;
  IF v_prior IS NOT NULL THEN
    RETURN v_prior || jsonb_build_object('was_already_applied', true);
  END IF;

  SELECT u.email INTO v_user_email FROM auth.users u WHERE u.id = v_user_id;

  FOR v_elem IN SELECT * FROM jsonb_array_elements(p_leftovers)
  LOOP
    v_product_id := (v_elem->>'product_id')::uuid;
    IF v_product_id IS NULL THEN
      CONTINUE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.products p WHERE p.id = v_product_id AND p.company = v_company_id) THEN
      RAISE EXCEPTION 'product % not found', v_product_id USING ERRCODE = '42704';
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

    -- Van goods: back to the batches this tour took them from.
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
          v_batch.batch_number, v_batch.expiration_date, p_tour_id,
          'Slot change: unused', jsonb_build_object(
            'tour_id', p_tour_id, 'source', 'slot_change', 'origin', 'van',
            '_user_email', v_user_email)
        );
        v_returns := v_returns || jsonb_build_object(
          'product_id', v_product_id, 'origin', 'van', 'quantity', v_take, 'batch_id', v_batch.id);
        v_left := v_left - v_take;
      END LOOP;
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
        p_tour_id,
        'Slot change: taken out of machine', jsonb_build_object(
          'tour_id', p_tour_id, 'source', 'slot_change', 'origin', 'machine',
          '_user_email', v_user_email)
      );
      v_returns := v_returns || jsonb_build_object(
        'product_id', v_product_id, 'origin', 'machine', 'quantity', v_qty, 'batch_id', v_batch_id,
        'expiration_date', NULLIF(v_elem->>'expiration_date', ''));
    END IF;
  END LOOP;

  v_result := jsonb_build_object(
    'tour_id', p_tour_id,
    'warehouse_id', p_warehouse_id,
    'returns', v_returns,
    'waste', v_waste
  );

  INSERT INTO public.slot_change_tour_returns (company_id, tour_id, returned_by, result)
  VALUES (v_company_id, p_tour_id, v_user_id, v_result);

  IF jsonb_array_length(v_returns) > 0 OR jsonb_array_length(v_waste) > 0 THEN
    INSERT INTO public.activity_log (company_id, user_id, entity_type, entity_id, action, metadata)
    VALUES (
      v_company_id, v_user_id, 'stock', p_tour_id, 'slot_change_leftovers_returned',
      v_result || jsonb_build_object('_user_email', v_user_email)
    );
  END IF;

  RETURN v_result || jsonb_build_object('was_already_applied', false);
END;
$$;

REVOKE ALL ON FUNCTION public.return_slot_change_leftovers(text, uuid, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.return_slot_change_leftovers(text, uuid, jsonb) TO authenticated;
