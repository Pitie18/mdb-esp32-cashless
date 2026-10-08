-- ============================================================================
-- Slot change requests ("Fächer umbelegen")
-- ============================================================================
-- An admin plans a new slot layout for a machine (which product goes into
-- which spiral, and the spiral's new capacity). Saving that plan must NOT
-- change machine_trays: the machine is physically rebuilt only during the next
-- refill tour, and sales keep coming in for the OLD product until then.
--
-- So the plan is stored as a change request. The next refill tour shows it as
-- a change note; the refiller packs the extra units, accepts or skips each
-- slot, and quits the rebuild at the machine. Only that quittance
-- (apply_slot_change) switches product, capacity and stock of the slots, in
-- one transaction, and books what is left over back into the warehouse (or as
-- waste). Web, iOS and Android all call the same RPCs so the bookkeeping can't
-- drift between clients.
--
--   slot_change_requests        one open request per machine
--   slot_change_request_items   one row per changed slot (pending → done)
--   slot_change_applications    idempotency record per (request, tour)
--
-- Tables are read-only for members; every write goes through the
-- SECURITY DEFINER functions below, which check that the caller is an admin
-- of the machine's company (same rule as refill_machine_trays).
-- ============================================================================

-- ── Tables ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.slot_change_requests (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  company_id  uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  machine_id  uuid        NOT NULL REFERENCES public."vendingMachine"(id) ON DELETE CASCADE,
  status      text        NOT NULL DEFAULT 'open'
                          CHECK (status IN ('open', 'done', 'withdrawn')),
  created_by  uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  note        text,
  closed_at   timestamptz
);

-- One open request per machine; a new plan edits the open one.
CREATE UNIQUE INDEX IF NOT EXISTS slot_change_requests_one_open
  ON public.slot_change_requests (machine_id) WHERE status = 'open';
CREATE INDEX IF NOT EXISTS idx_slot_change_requests_company
  ON public.slot_change_requests (company_id, status);

CREATE TABLE IF NOT EXISTS public.slot_change_request_items (
  id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at       timestamptz NOT NULL DEFAULT now(),
  request_id       uuid        NOT NULL REFERENCES public.slot_change_requests(id) ON DELETE CASCADE,
  company_id       uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  tray_id          uuid        NOT NULL REFERENCES public.machine_trays(id) ON DELETE CASCADE,
  -- Snapshot at planning time (the tray itself does not change until done)
  item_number      integer     NOT NULL,
  from_product_id  uuid        REFERENCES public.products(id) ON DELETE SET NULL,
  to_product_id    uuid        REFERENCES public.products(id) ON DELETE SET NULL,
  from_capacity    integer     NOT NULL,
  to_capacity      integer     NOT NULL CHECK (to_capacity > 0),
  from_price       double precision,
  to_price         double precision,
  status           text        NOT NULL DEFAULT 'pending'
                               CHECK (status IN ('pending', 'done', 'withdrawn')),
  -- A refiller who does not rebuild a slot leaves it pending for the next tour
  skip_count       integer     NOT NULL DEFAULT 0,
  last_skipped_at  timestamptz,
  last_skip_tour_id text,
  -- Filled in by the quittance
  removed_qty      integer,
  filled_qty       integer,
  price_set        boolean,
  applied_tour_id  text,
  applied_at       timestamptz,
  applied_by       uuid        REFERENCES auth.users(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS slot_change_request_items_one_pending
  ON public.slot_change_request_items (request_id, tray_id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_slot_change_request_items_request
  ON public.slot_change_request_items (request_id);

CREATE TABLE IF NOT EXISTS public.slot_change_applications (
  request_id  uuid        NOT NULL REFERENCES public.slot_change_requests(id) ON DELETE CASCADE,
  tour_id     text        NOT NULL,
  company_id  uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  applied_at  timestamptz NOT NULL DEFAULT now(),
  applied_by  uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  result      jsonb       NOT NULL,
  PRIMARY KEY (request_id, tour_id)
);

ALTER TABLE public.slot_change_requests      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.slot_change_request_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.slot_change_applications  ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON public.slot_change_requests      TO authenticated;
GRANT SELECT ON public.slot_change_request_items TO authenticated;
GRANT SELECT ON public.slot_change_applications  TO authenticated;
GRANT ALL ON public.slot_change_requests      TO service_role;
GRANT ALL ON public.slot_change_request_items TO service_role;
GRANT ALL ON public.slot_change_applications  TO service_role;

DROP POLICY IF EXISTS slot_change_requests_select ON public.slot_change_requests;
CREATE POLICY slot_change_requests_select ON public.slot_change_requests
  FOR SELECT TO authenticated
  USING (company_id = (SELECT public.my_company_id()));

DROP POLICY IF EXISTS slot_change_request_items_select ON public.slot_change_request_items;
CREATE POLICY slot_change_request_items_select ON public.slot_change_request_items
  FOR SELECT TO authenticated
  USING (company_id = (SELECT public.my_company_id()));

DROP POLICY IF EXISTS slot_change_applications_select ON public.slot_change_applications;
CREATE POLICY slot_change_applications_select ON public.slot_change_applications
  FOR SELECT TO authenticated
  USING (company_id = (SELECT public.my_company_id()));

-- ── Authorization helper ────────────────────────────────────────────────────

-- Returns the machine's company when the caller is an admin of it; raises
-- otherwise. Mirrors the check inside refill_machine_trays.
CREATE OR REPLACE FUNCTION public.slot_change_assert_admin(p_machine_id uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_user_id    uuid := auth.uid();
  v_company_id uuid;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'not authenticated' USING ERRCODE = '42501';
  END IF;

  SELECT vm.company INTO v_company_id
    FROM public."vendingMachine" vm
   WHERE vm.id = p_machine_id;

  IF v_company_id IS NULL THEN
    RAISE EXCEPTION 'machine not found' USING ERRCODE = '42704';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.organization_members om
     WHERE om.company_id = v_company_id
       AND om.user_id    = v_user_id
       AND om.role       = 'admin'
  ) THEN
    RAISE EXCEPTION 'not authorized for this machine' USING ERRCODE = '42501';
  END IF;

  RETURN v_company_id;
END;
$$;

REVOKE ALL ON FUNCTION public.slot_change_assert_admin(uuid) FROM PUBLIC;

-- ── save_slot_change_request ────────────────────────────────────────────────
-- Replace the pending items of the machine's open request with p_items
-- (creating the request if there is none). Items that change nothing are
-- dropped; an empty plan withdraws the request. From/to snapshots are taken
-- from the database, never from the client.
--
--   p_items: [{"tray_id": uuid, "to_product_id": uuid|null, "to_capacity": int}]
--
-- Returns the open request id, or NULL when nothing is left to change.
CREATE OR REPLACE FUNCTION public.save_slot_change_request(
  p_machine_id uuid,
  p_items      jsonb,
  p_note       text DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company_id uuid := public.slot_change_assert_admin(p_machine_id);
  v_request_id uuid;
  v_count      integer;
  v_bad        integer;
BEGIN
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
    RAISE EXCEPTION 'items must be a JSON array' USING ERRCODE = '22023';
  END IF;

  -- Every tray must belong to this machine, and appear only once.
  SELECT count(*) INTO v_bad
    FROM jsonb_array_elements(p_items) e
    LEFT JOIN public.machine_trays mt
      ON mt.id = (e->>'tray_id')::uuid AND mt.machine_id = p_machine_id
   WHERE mt.id IS NULL;
  IF v_bad > 0 THEN
    RAISE EXCEPTION 'tray does not belong to this machine' USING ERRCODE = '22023';
  END IF;

  SELECT count(*) - count(DISTINCT e->>'tray_id') INTO v_bad
    FROM jsonb_array_elements(p_items) e;
  IF v_bad > 0 THEN
    RAISE EXCEPTION 'duplicate tray in plan' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1 FROM jsonb_array_elements(p_items) e
     WHERE COALESCE((e->>'to_capacity')::int, 0) < 1
  ) THEN
    RAISE EXCEPTION 'to_capacity must be > 0' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1 FROM jsonb_array_elements(p_items) e
     WHERE e->>'to_product_id' IS NOT NULL
       AND NOT EXISTS (
         SELECT 1 FROM public.products p
          WHERE p.id = (e->>'to_product_id')::uuid AND p.company = v_company_id
       )
  ) THEN
    RAISE EXCEPTION 'product not found' USING ERRCODE = '22023';
  END IF;

  SELECT r.id INTO v_request_id
    FROM public.slot_change_requests r
   WHERE r.machine_id = p_machine_id AND r.status = 'open'
   FOR UPDATE;

  IF v_request_id IS NULL THEN
    INSERT INTO public.slot_change_requests (company_id, machine_id, created_by, note)
    VALUES (v_company_id, p_machine_id, auth.uid(), p_note)
    RETURNING id INTO v_request_id;
  ELSE
    UPDATE public.slot_change_requests
       SET updated_at = now(), note = COALESCE(p_note, note)
     WHERE id = v_request_id;
  END IF;

  DELETE FROM public.slot_change_request_items
   WHERE request_id = v_request_id AND status = 'pending';

  INSERT INTO public.slot_change_request_items (
    request_id, company_id, tray_id, item_number,
    from_product_id, to_product_id, from_capacity, to_capacity,
    from_price, to_price
  )
  SELECT
    v_request_id, v_company_id, mt.id, mt.item_number,
    mt.product_id, (e->>'to_product_id')::uuid, mt.capacity, (e->>'to_capacity')::int,
    pf.sellprice, pt.sellprice
  FROM jsonb_array_elements(p_items) e
  JOIN public.machine_trays mt ON mt.id = (e->>'tray_id')::uuid
  LEFT JOIN public.products pf ON pf.id = mt.product_id
  LEFT JOIN public.products pt ON pt.id = (e->>'to_product_id')::uuid
  WHERE (e->>'to_product_id')::uuid IS DISTINCT FROM mt.product_id
     OR (e->>'to_capacity')::int <> mt.capacity;

  GET DIAGNOSTICS v_count = ROW_COUNT;

  IF v_count = 0 THEN
    -- Nothing pending any more: close the request (done if part of it was
    -- already rebuilt on an earlier tour, withdrawn otherwise).
    UPDATE public.slot_change_requests r
       SET status = CASE WHEN EXISTS (
                      SELECT 1 FROM public.slot_change_request_items i
                       WHERE i.request_id = r.id AND i.status = 'done'
                    ) THEN 'done' ELSE 'withdrawn' END,
           closed_at = now(), updated_at = now()
     WHERE r.id = v_request_id;
    RETURN NULL;
  END IF;

  RETURN v_request_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.save_slot_change_request(uuid, jsonb, text) TO authenticated;

-- ── withdraw_slot_change_request ────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.withdraw_slot_change_request(p_request_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_machine_id uuid;
BEGIN
  SELECT r.machine_id INTO v_machine_id
    FROM public.slot_change_requests r
   WHERE r.id = p_request_id AND r.status = 'open'
   FOR UPDATE;
  IF v_machine_id IS NULL THEN
    RETURN;  -- already closed: nothing to do
  END IF;

  PERFORM public.slot_change_assert_admin(v_machine_id);

  UPDATE public.slot_change_request_items
     SET status = 'withdrawn'
   WHERE request_id = p_request_id AND status = 'pending';

  UPDATE public.slot_change_requests
     SET status = 'withdrawn', closed_at = now(), updated_at = now()
   WHERE id = p_request_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.withdraw_slot_change_request(uuid) TO authenticated;

-- ── apply_slot_change (quittance at the machine) ───────────────────────────
-- Called once per machine visit, before the normal refill of the remaining
-- slots. Idempotent per (request, tour): a retry returns the first result.
--
--   p_items: [{"item_id": uuid, "action": "done"|"skip",
--              "removed": int, "filled": int, "price_set": bool}]
--     done → the slot switches to the new product and capacity; its stock is
--            set to what was filled (the old stock was taken out and counted
--            as "removed"). Sales up to now stayed booked to the old product.
--     skip → the slot is untouched and stays pending for the next tour.
--
--   p_leftovers: [{"product_id": uuid, "van_qty": int, "machine_qty": int,
--                  "destination": "warehouse"|"waste",
--                  "expiration_date": date|null, "batch_number": text|null}]
--     van_qty      units packed for the rebuild that were not used. They go
--                  back to the batches this tour took them from.
--     machine_qty  units taken out of the machine that did not go into
--                  another slot. They become a new batch (batch_number, with
--                  the best-before date the refiller confirmed).
--     waste        nothing is booked into the warehouse; the activity entry
--                  records the write-off.
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
