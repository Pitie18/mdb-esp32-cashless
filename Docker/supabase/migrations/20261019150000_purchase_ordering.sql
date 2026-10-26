-- ============================================================================
-- Ordering from suppliers (warehouse "Order" tab).
--
-- The order suggestion itself is computed in the clients: a product needs
-- enough stock to last until the delivery after next, i.e.
--   avg daily sales × (order interval + lead time + buffer)
-- minus what is in the warehouse, in machines (optional) and already on
-- order, rounded up to full order units (cartons). This migration only adds
-- the inputs and the two things that must be shared between web and apps:
--
--   suppliers.lead_time_days / order_interval_days / min_order_value
--   products.preferred_supplier_id / order_unit_size
--   companies.order_buffer_days / order_count_machine_stock / defaults
--   order_list_items          products someone added to the order list by hand
--   purchase_orders(+_lines)  orders marked as placed, "on the way" until received
--
--   add_to_order_list / remove_from_order_list   every member
--   place_purchase_order / cancel_purchase_order admins
--   receive_purchase_order                       every member (books the stock)
--   close_purchase_order                         every member: closes an order
--                                                whose goods were booked by hand
--                                                (e.g. with an older app)
--
-- Also fixes get_product_sales_velocity to count sales by the product sold
-- (sales.product_id, snapshotted at sale time) instead of whatever product
-- sits in the slot today.
-- Additive only; older app versions keep working unchanged.
-- ============================================================================

-- ── Inputs ──────────────────────────────────────────────────────────────────

-- NULL = use the company default.
ALTER TABLE public.suppliers
  ADD COLUMN IF NOT EXISTS lead_time_days      integer CHECK (lead_time_days BETWEEN 0 AND 365),
  ADD COLUMN IF NOT EXISTS order_interval_days integer CHECK (order_interval_days BETWEEN 1 AND 365),
  ADD COLUMN IF NOT EXISTS min_order_value     numeric(10,2) CHECK (min_order_value >= 0);

-- preferred_supplier_id NULL = the supplier of the product's latest purchase
-- price (the clients fall back to it), or no supplier at all.
ALTER TABLE public.products
  ADD COLUMN IF NOT EXISTS preferred_supplier_id uuid REFERENCES public.suppliers(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS order_unit_size       integer NOT NULL DEFAULT 1 CHECK (order_unit_size BETWEEN 1 AND 10000);

CREATE INDEX IF NOT EXISTS products_preferred_supplier_idx
  ON public.products (preferred_supplier_id) WHERE preferred_supplier_id IS NOT NULL;

ALTER TABLE public.companies
  ADD COLUMN IF NOT EXISTS order_buffer_days           integer NOT NULL DEFAULT 3  CHECK (order_buffer_days BETWEEN 0 AND 365),
  ADD COLUMN IF NOT EXISTS order_count_machine_stock   boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS order_default_lead_days     integer NOT NULL DEFAULT 3  CHECK (order_default_lead_days BETWEEN 0 AND 365),
  ADD COLUMN IF NOT EXISTS order_default_interval_days integer NOT NULL DEFAULT 14 CHECK (order_default_interval_days BETWEEN 1 AND 365);

-- ── Helpers ─────────────────────────────────────────────────────────────────

-- Company of a warehouse the caller belongs to; raises otherwise.
CREATE OR REPLACE FUNCTION public.ordering_assert_member(p_warehouse_id uuid, p_admin boolean)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company_id uuid;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'not authenticated' USING ERRCODE = '42501';
  END IF;

  SELECT w.company_id INTO v_company_id
    FROM public.warehouses w
   WHERE w.id = p_warehouse_id;

  IF v_company_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.organization_members om
     WHERE om.company_id = v_company_id
       AND om.user_id    = auth.uid()
       AND (NOT p_admin OR om.role = 'admin')
  ) THEN
    RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501';
  END IF;

  RETURN v_company_id;
END;
$$;

REVOKE ALL ON FUNCTION public.ordering_assert_member(uuid, boolean) FROM PUBLIC;

-- Display name of the caller, stamped onto rows so every client can show
-- "who" without reading other members' profiles.
CREATE OR REPLACE FUNCTION public.ordering_user_display()
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT coalesce(
    nullif(btrim(concat_ws(' ', u.first_name, u.last_name)), ''),
    u.email,
    (SELECT au.email FROM auth.users au WHERE au.id = auth.uid())
  )
  FROM (SELECT 1) one
  LEFT JOIN public.users u ON u.id = auth.uid()
$$;

REVOKE ALL ON FUNCTION public.ordering_user_display() FROM PUBLIC;

-- ── order_list_items ────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.order_list_items (
  id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  company_id    uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  warehouse_id  uuid        NOT NULL REFERENCES public.warehouses(id) ON DELETE CASCADE,
  product_id    uuid        NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
  added_by      uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  added_by_name text,
  added_via     text        NOT NULL DEFAULT 'web' CHECK (added_via IN ('web', 'ios', 'android')),
  CONSTRAINT order_list_items_uniq UNIQUE (warehouse_id, product_id)
);

ALTER TABLE public.order_list_items ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.order_list_items TO authenticated;
GRANT ALL ON public.order_list_items TO service_role;

DROP POLICY IF EXISTS order_list_items_select ON public.order_list_items;
CREATE POLICY order_list_items_select ON public.order_list_items
  FOR SELECT TO authenticated
  USING (company_id = public.my_company_id());

-- Add a product to the order list by hand. Adding it again keeps the first
-- entry (who and when). Returns the entry id.
CREATE OR REPLACE FUNCTION public.add_to_order_list(
  p_warehouse_id uuid,
  p_product_id   uuid,
  p_via          text DEFAULT 'web'
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company_id uuid := public.ordering_assert_member(p_warehouse_id, false);
  v_via        text := CASE WHEN p_via IN ('web', 'ios', 'android') THEN p_via ELSE 'web' END;
  v_id         uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.products p WHERE p.id = p_product_id AND p.company = v_company_id) THEN
    RAISE EXCEPTION 'product not found' USING ERRCODE = 'P0002';
  END IF;

  INSERT INTO public.order_list_items (company_id, warehouse_id, product_id, added_by, added_by_name, added_via)
  VALUES (v_company_id, p_warehouse_id, p_product_id, auth.uid(), public.ordering_user_display(), v_via)
  ON CONFLICT ON CONSTRAINT order_list_items_uniq DO NOTHING
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT i.id INTO v_id FROM public.order_list_items i
     WHERE i.warehouse_id = p_warehouse_id AND i.product_id = p_product_id;
  END IF;

  RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.add_to_order_list(uuid, uuid, text) TO authenticated;

-- Remove a manual entry. Returns true when there was one.
CREATE OR REPLACE FUNCTION public.remove_from_order_list(
  p_warehouse_id uuid,
  p_product_id   uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM public.ordering_assert_member(p_warehouse_id, false);
  DELETE FROM public.order_list_items i
   WHERE i.warehouse_id = p_warehouse_id AND i.product_id = p_product_id;
  RETURN FOUND;
END;
$$;

GRANT EXECUTE ON FUNCTION public.remove_from_order_list(uuid, uuid) TO authenticated;

-- ── purchase_orders ─────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.purchase_orders (
  id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at       timestamptz NOT NULL DEFAULT now(),
  company_id       uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  warehouse_id     uuid        NOT NULL REFERENCES public.warehouses(id) ON DELETE CASCADE,
  supplier_id      uuid        REFERENCES public.suppliers(id) ON DELETE SET NULL,
  supplier_name    text,
  -- ordered: on the way; received: booked via receive_purchase_order;
  -- closed: marked delivered without booking here; cancelled.
  status           text        NOT NULL DEFAULT 'ordered'
                               CHECK (status IN ('ordered', 'received', 'closed', 'cancelled')),
  note             text        CHECK (note IS NULL OR length(note) <= 2000),
  ordered_by       uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  ordered_by_name  text,
  finished_at      timestamptz,
  finished_by      uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  finished_by_name text
);

CREATE INDEX IF NOT EXISTS purchase_orders_open_idx
  ON public.purchase_orders (warehouse_id) WHERE status = 'ordered';
CREATE INDEX IF NOT EXISTS purchase_orders_company_idx
  ON public.purchase_orders (company_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.purchase_order_lines (
  id                uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id          uuid    NOT NULL REFERENCES public.purchase_orders(id) ON DELETE CASCADE,
  company_id        uuid    NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  product_id        uuid    REFERENCES public.products(id) ON DELETE SET NULL,
  product_name      text,
  -- Units ordered (cartons × unit_size).
  quantity          integer NOT NULL CHECK (quantity > 0),
  unit_size         integer NOT NULL DEFAULT 1 CHECK (unit_size >= 1),
  unit_price_net    numeric(10,4),
  received_quantity integer CHECK (received_quantity >= 0),
  sort_order        integer NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS purchase_order_lines_order_idx ON public.purchase_order_lines (order_id);
CREATE INDEX IF NOT EXISTS purchase_order_lines_product_idx ON public.purchase_order_lines (product_id);

ALTER TABLE public.purchase_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.purchase_order_lines ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.purchase_orders, public.purchase_order_lines TO authenticated;
GRANT ALL ON public.purchase_orders, public.purchase_order_lines TO service_role;

DROP POLICY IF EXISTS purchase_orders_select ON public.purchase_orders;
CREATE POLICY purchase_orders_select ON public.purchase_orders
  FOR SELECT TO authenticated
  USING (company_id = public.my_company_id());

DROP POLICY IF EXISTS purchase_order_lines_select ON public.purchase_order_lines;
CREATE POLICY purchase_order_lines_select ON public.purchase_order_lines
  FOR SELECT TO authenticated
  USING (company_id = public.my_company_id());

-- Mark an order as placed. p_lines: [{product_id, quantity, unit_size?,
-- unit_price_net?}], quantity in units. The manual order-list entries of the
-- ordered products are cleared. Returns the order id.
CREATE OR REPLACE FUNCTION public.place_purchase_order(
  p_warehouse_id uuid,
  p_supplier_id  uuid,
  p_lines        jsonb,
  p_note         text DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company_id    uuid := public.ordering_assert_member(p_warehouse_id, true);
  v_supplier_name text;
  v_order_id      uuid;
  v_line          jsonb;
  v_product_id    uuid;
  v_product_name  text;
  v_qty           integer;
  v_pos           integer := 0;
  v_ids           uuid[] := '{}';
BEGIN
  SELECT s.name INTO v_supplier_name
    FROM public.suppliers s
   WHERE s.id = p_supplier_id AND s.company_id = v_company_id;
  IF v_supplier_name IS NULL THEN
    RAISE EXCEPTION 'supplier not found' USING ERRCODE = 'P0002';
  END IF;

  IF jsonb_typeof(p_lines) IS DISTINCT FROM 'array' OR jsonb_array_length(p_lines) = 0 THEN
    RAISE EXCEPTION 'order has no lines' USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.purchase_orders (company_id, warehouse_id, supplier_id, supplier_name, note, ordered_by, ordered_by_name)
  VALUES (v_company_id, p_warehouse_id, p_supplier_id, v_supplier_name,
          nullif(btrim(coalesce(p_note, '')), ''), auth.uid(), public.ordering_user_display())
  RETURNING id INTO v_order_id;

  FOR v_line IN SELECT * FROM jsonb_array_elements(p_lines) LOOP
    v_product_id := (v_line->>'product_id')::uuid;
    v_qty := floor(coalesce((v_line->>'quantity')::numeric, 0));
    CONTINUE WHEN v_qty <= 0;

    SELECT p.name INTO v_product_name
      FROM public.products p
     WHERE p.id = v_product_id AND p.company = v_company_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'product not found' USING ERRCODE = 'P0002';
    END IF;
    IF v_product_id = ANY(v_ids) THEN
      RAISE EXCEPTION 'product listed twice' USING ERRCODE = '22023';
    END IF;

    v_ids := v_ids || v_product_id;
    v_pos := v_pos + 1;
    INSERT INTO public.purchase_order_lines (order_id, company_id, product_id, product_name, quantity, unit_size, unit_price_net, sort_order)
    VALUES (v_order_id, v_company_id, v_product_id, v_product_name, v_qty,
            greatest(1, coalesce((v_line->>'unit_size')::integer, 1)),
            (v_line->>'unit_price_net')::numeric, v_pos);
  END LOOP;

  IF v_pos = 0 THEN
    RAISE EXCEPTION 'order has no lines' USING ERRCODE = '22023';
  END IF;

  DELETE FROM public.order_list_items i
   WHERE i.warehouse_id = p_warehouse_id AND i.product_id = ANY(v_ids);

  RETURN v_order_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.place_purchase_order(uuid, uuid, jsonb, text) TO authenticated;

-- Locks an open order of the caller's company. p_admin: admins only.
CREATE OR REPLACE FUNCTION public.purchase_order_lock_open(p_order_id uuid, p_admin boolean)
RETURNS public.purchase_orders
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_order public.purchase_orders;
BEGIN
  SELECT * INTO v_order FROM public.purchase_orders o WHERE o.id = p_order_id FOR UPDATE;
  IF v_order.id IS NULL THEN
    RAISE EXCEPTION 'order not found' USING ERRCODE = 'P0002';
  END IF;
  PERFORM public.ordering_assert_member(v_order.warehouse_id, p_admin);
  RETURN v_order;
END;
$$;

REVOKE ALL ON FUNCTION public.purchase_order_lock_open(uuid, boolean) FROM PUBLIC;

-- Cancel an open order (admins). A finished order stays as it is.
CREATE OR REPLACE FUNCTION public.cancel_purchase_order(p_order_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_order public.purchase_orders := public.purchase_order_lock_open(p_order_id, true);
BEGIN
  IF v_order.status <> 'ordered' THEN
    RETURN v_order.status;
  END IF;
  UPDATE public.purchase_orders
     SET status = 'cancelled', finished_at = now(), finished_by = auth.uid(),
         finished_by_name = public.ordering_user_display()
   WHERE id = p_order_id;
  RETURN 'cancelled';
END;
$$;

GRANT EXECUTE ON FUNCTION public.cancel_purchase_order(uuid) TO authenticated;

-- Mark an open order as delivered without booking stock: for goods that were
-- already booked through the normal intake (older apps do not know orders).
CREATE OR REPLACE FUNCTION public.close_purchase_order(p_order_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_order public.purchase_orders := public.purchase_order_lock_open(p_order_id, false);
BEGIN
  IF v_order.status <> 'ordered' THEN
    RETURN v_order.status;
  END IF;
  UPDATE public.purchase_orders
     SET status = 'closed', finished_at = now(), finished_by = auth.uid(),
         finished_by_name = public.ordering_user_display()
   WHERE id = p_order_id;
  RETURN 'closed';
END;
$$;

GRANT EXECUTE ON FUNCTION public.close_purchase_order(uuid) TO authenticated;

-- Book the delivery of an open order into the warehouse. p_lines:
-- [{line_id, quantity, expiration_date?, batch_number?}], quantity = units
-- actually delivered (0 = nothing came). Lines left out count as 0. Each
-- delivered line becomes an 'incoming' transaction with the order's supplier,
-- merged into an existing batch with the same batch number and best-before,
-- exactly like a manual intake. A retry on a finished order books nothing and
-- returns {status, booked: 0}.
CREATE OR REPLACE FUNCTION public.receive_purchase_order(
  p_order_id uuid,
  p_lines    jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_order     public.purchase_orders := public.purchase_order_lock_open(p_order_id, false);
  v_line      public.purchase_order_lines;
  v_in        jsonb;
  v_qty       integer;
  v_exp       date;
  v_batch_no  text;
  v_batch_id  uuid;
  v_before    integer;
  v_booked    integer := 0;
  v_units     integer := 0;
  v_email     text := (SELECT au.email FROM auth.users au WHERE au.id = auth.uid());
  v_display   text := public.ordering_user_display();
BEGIN
  IF v_order.status <> 'ordered' THEN
    RETURN jsonb_build_object('status', v_order.status, 'booked', 0, 'units', 0);
  END IF;

  IF p_lines IS NOT NULL AND jsonb_typeof(p_lines) <> 'array' THEN
    RAISE EXCEPTION 'lines must be an array' USING ERRCODE = '22023';
  END IF;

  FOR v_line IN
    SELECT * FROM public.purchase_order_lines l WHERE l.order_id = p_order_id ORDER BY l.sort_order
  LOOP
    SELECT e INTO v_in
      FROM jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) e
     WHERE (e->>'line_id')::uuid = v_line.id
     LIMIT 1;

    v_qty      := greatest(0, floor(coalesce((v_in->>'quantity')::numeric, 0)))::integer;
    v_exp      := nullif(v_in->>'expiration_date', '')::date;
    v_batch_no := nullif(btrim(coalesce(v_in->>'batch_number', '')), '');

    UPDATE public.purchase_order_lines SET received_quantity = v_qty WHERE id = v_line.id;

    CONTINUE WHEN v_qty = 0 OR v_line.product_id IS NULL;

    SELECT b.id, b.quantity INTO v_batch_id, v_before
      FROM public.warehouse_stock_batches b
     WHERE b.warehouse_id = v_order.warehouse_id
       AND b.product_id   = v_line.product_id
       AND coalesce(b.batch_number, '') = coalesce(v_batch_no, '')
       AND b.expiration_date IS NOT DISTINCT FROM v_exp
     ORDER BY b.created_at
     LIMIT 1
     FOR UPDATE;

    IF v_batch_id IS NULL THEN
      v_before := 0;
      INSERT INTO public.warehouse_stock_batches
        (warehouse_id, product_id, batch_number, expiration_date, quantity, company_id, supplier_id)
      VALUES (v_order.warehouse_id, v_line.product_id, v_batch_no, v_exp, v_qty, v_order.company_id, v_order.supplier_id)
      RETURNING id INTO v_batch_id;
    ELSE
      UPDATE public.warehouse_stock_batches
         SET quantity = quantity + v_qty,
             supplier_id = coalesce(supplier_id, v_order.supplier_id)
       WHERE id = v_batch_id;
    END IF;

    INSERT INTO public.warehouse_transactions
      (company_id, warehouse_id, product_id, batch_id, user_id, transaction_type,
       quantity_change, quantity_before, quantity_after, batch_number, expiration_date,
       reference_id, supplier_id, metadata)
    VALUES
      (v_order.company_id, v_order.warehouse_id, v_line.product_id, v_batch_id, auth.uid(), 'incoming',
       v_qty, v_before, v_before + v_qty, v_batch_no, v_exp,
       p_order_id::text, v_order.supplier_id,
       jsonb_build_object(
         '_user_email', v_email,
         '_user_display', v_display,
         'purchase_order_id', p_order_id,
         'ordered_quantity', v_line.quantity
       ));

    v_booked := v_booked + 1;
    v_units  := v_units + v_qty;
    v_batch_id := NULL;
  END LOOP;

  UPDATE public.purchase_orders
     SET status = 'received', finished_at = now(), finished_by = auth.uid(), finished_by_name = v_display
   WHERE id = p_order_id;

  RETURN jsonb_build_object('status', 'received', 'booked', v_booked, 'units', v_units);
END;
$$;

GRANT EXECUTE ON FUNCTION public.receive_purchase_order(uuid, jsonb) TO authenticated;

-- Live sync between web and apps.
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['order_list_items', 'purchase_orders'] LOOP
    IF NOT EXISTS (
      SELECT 1 FROM pg_publication_tables
       WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = t
    ) THEN
      EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE public.%I', t);
    END IF;
  END LOOP;
END $$;

-- ── Sales velocity by the product actually sold ────────────────────────────
-- Sales carry the product they sold (product_id, snapshotted since
-- 20260412000000). Joining today's slot assignment instead credited a
-- product with the sales of whatever sat in its slot before. Older sales
-- without a snapshot still fall back to the slot.
CREATE OR REPLACE FUNCTION public.get_product_sales_velocity(
  p_company_id uuid,
  p_days integer DEFAULT 30
)
RETURNS TABLE (
  product_id uuid,
  units_sold bigint,
  avg_daily_units numeric
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  RETURN QUERY
  SELECT
    p.id                                                 AS product_id,
    count(s.id)                                          AS units_sold,
    round(count(s.id)::numeric / greatest(p_days, 1), 2) AS avg_daily_units
  FROM public.sales s
  LEFT JOIN public.machine_trays mt
    ON s.product_id IS NULL
   AND mt.machine_id = s.machine_id
   AND mt.item_number = s.item_number
  JOIN public.products p
    ON p.id = coalesce(s.product_id, mt.product_id)
  WHERE p.company = p_company_id
    AND s.created_at >= (now() - make_interval(days => p_days))
  GROUP BY p.id;
END;
$$;
