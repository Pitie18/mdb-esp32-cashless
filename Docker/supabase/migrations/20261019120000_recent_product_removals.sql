-- Recently removed products: a cool-down for the refill review's replacement
-- picker.
--
-- When a slot is sold out the refill review offers products that are not in
-- the machine yet. Until now nothing told the refiller that one of them had
-- just been taken out of that machine because it sold badly, so a slow seller
-- with stock left in the warehouse could walk straight back in.
--
--  • machine_product_removals keeps one row per time a product left a machine
--    (it was in no slot any more): since when it had been offered and when it
--    went. maintain_machine_product_offerings already closes the offering at
--    exactly that moment, so it now records the removal as well.
--  • companies.product_removal_cooldown_days (default 30, 0 = off) is how long
--    a removed product is flagged.
--  • get_recent_product_removals(machine_ids) returns, per machine, the
--    products removed within the cool-down that are still not back, with what
--    they sold while they were offered. Web, iOS and Android read the same.
--  • Backfill: removals before this migration were never recorded. A product
--    that sold in a machine during the last year and is in none of its slots
--    now was removed; the date is the slot change that took it out, else its
--    last sale (estimated = true).

-- ── A. Setting ───────────────────────────────────────────────────────────────
ALTER TABLE public.companies
  ADD COLUMN IF NOT EXISTS product_removal_cooldown_days integer NOT NULL DEFAULT 30;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'companies_product_removal_cooldown_days_check'
  ) THEN
    ALTER TABLE public.companies
      ADD CONSTRAINT companies_product_removal_cooldown_days_check
      CHECK (product_removal_cooldown_days BETWEEN 0 AND 365);
  END IF;
END $$;

-- ── B. Removal history ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.machine_product_removals (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id    uuid NOT NULL REFERENCES public."vendingMachine"(id) ON DELETE CASCADE,
  product_id    uuid NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
  offered_since timestamptz,
  removed_at    timestamptz NOT NULL DEFAULT now(),
  estimated     boolean NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS machine_product_removals_machine_removed_idx
  ON public.machine_product_removals (machine_id, removed_at DESC);

ALTER TABLE public.machine_product_removals ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON public.machine_product_removals TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.machine_product_removals TO service_role;

DROP POLICY IF EXISTS "machine_product_removals_select" ON public.machine_product_removals;
CREATE POLICY "machine_product_removals_select" ON public.machine_product_removals
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public."vendingMachine" vm
      WHERE vm.id = machine_product_removals.machine_id
        AND vm.company = public.my_company_id()
    )
  );

-- ── C. Record removals where the offering closes ─────────────────────────────
-- Same function as before, plus: closing an offering writes a removal row, and
-- a product coming back within the same transaction drops the row again. The
-- latter is what a slot change does when a product only moves (swap, or into
-- a freed slot): apply_slot_change switches the trays one at a time, so the
-- product is briefly in no slot. now() is the transaction start, so "removed
-- in this transaction" is exactly removed_at = now().
CREATE OR REPLACE FUNCTION public.maintain_machine_product_offerings()
  RETURNS trigger
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = ''
AS $$
DECLARE
  v_since timestamptz;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.product_id IS NOT NULL THEN
      INSERT INTO public.machine_product_offerings (machine_id, product_id, offered_since)
      VALUES (NEW.machine_id, NEW.product_id, now())
      ON CONFLICT (machine_id, product_id) DO NOTHING;
      DELETE FROM public.machine_product_removals
       WHERE machine_id = NEW.machine_id AND product_id = NEW.product_id
         AND removed_at = now() AND NOT estimated;
    END IF;

  ELSIF TG_OP = 'UPDATE' THEN
    IF NEW.product_id IS DISTINCT FROM OLD.product_id THEN
      IF NEW.product_id IS NOT NULL THEN
        INSERT INTO public.machine_product_offerings (machine_id, product_id, offered_since)
        VALUES (NEW.machine_id, NEW.product_id, now())
        ON CONFLICT (machine_id, product_id) DO NOTHING;
        DELETE FROM public.machine_product_removals
         WHERE machine_id = NEW.machine_id AND product_id = NEW.product_id
           AND removed_at = now() AND NOT estimated;
      END IF;
      IF OLD.product_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.machine_trays mt
        WHERE mt.machine_id = OLD.machine_id AND mt.product_id = OLD.product_id
      ) THEN
        DELETE FROM public.machine_product_offerings
        WHERE machine_id = OLD.machine_id AND product_id = OLD.product_id
        RETURNING offered_since INTO v_since;
        -- Not when the product itself is being deleted (its slots are set
        -- to NULL by the foreign key): the row would point at nothing.
        IF EXISTS (SELECT 1 FROM public.products p WHERE p.id = OLD.product_id) THEN
          INSERT INTO public.machine_product_removals (machine_id, product_id, offered_since, removed_at)
          VALUES (OLD.machine_id, OLD.product_id, v_since, now());
        END IF;
      END IF;
    END IF;

  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.product_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM public.machine_trays mt
      WHERE mt.machine_id = OLD.machine_id AND mt.product_id = OLD.product_id
    ) THEN
      DELETE FROM public.machine_product_offerings
      WHERE machine_id = OLD.machine_id AND product_id = OLD.product_id
      RETURNING offered_since INTO v_since;
      -- Not when the whole machine is being deleted (its trays go with it):
      -- the row would point at a machine that is gone.
      IF EXISTS (SELECT 1 FROM public."vendingMachine" vm WHERE vm.id = OLD.machine_id)
         AND EXISTS (SELECT 1 FROM public.products p WHERE p.id = OLD.product_id) THEN
        INSERT INTO public.machine_product_removals (machine_id, product_id, offered_since, removed_at)
        VALUES (OLD.machine_id, OLD.product_id, v_since, now());
      END IF;
    END IF;
  END IF;

  RETURN NULL; -- AFTER trigger
END;
$$;

-- ── D. Backfill ──────────────────────────────────────────────────────────────
INSERT INTO public.machine_product_removals (machine_id, product_id, offered_since, removed_at, estimated)
SELECT s.machine_id,
       s.product_id,
       min(s.created_at),
       GREATEST(max(s.created_at), (
         SELECT max(i.applied_at)
           FROM public.slot_change_request_items i
           JOIN public.slot_change_requests r ON r.id = i.request_id
          WHERE r.machine_id = s.machine_id
            AND i.from_product_id = s.product_id
            AND i.status = 'done'
       )),
       true
  FROM public.sales s
  JOIN public."vendingMachine" vm ON vm.id = s.machine_id
  JOIN public.products p ON p.id = s.product_id
 WHERE s.product_id IS NOT NULL
   AND NOT EXISTS (
     SELECT 1 FROM public.machine_trays mt
      WHERE mt.machine_id = s.machine_id AND mt.product_id = s.product_id
   )
   AND NOT EXISTS (
     SELECT 1 FROM public.machine_product_removals x
      WHERE x.machine_id = s.machine_id AND x.product_id = s.product_id
   )
 GROUP BY s.machine_id, s.product_id
HAVING max(s.created_at) >= now() - interval '365 days';

-- ── E. Read RPC ──────────────────────────────────────────────────────────────
-- Per machine of the caller's company: products removed within the company's
-- cool-down that are in none of the machine's slots now. Only the latest
-- removal per product counts. units_sold / revenue_eur cover the sales of the
-- product in that machine while it was offered (sales.product_id is the
-- snapshot at sale time, so a slot that changed product later does not blur
-- it).
CREATE OR REPLACE FUNCTION public.get_recent_product_removals(p_machine_ids uuid[])
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company uuid := public.my_company_id();
  v_days    integer;
  v_result  jsonb;
BEGIN
  IF v_company IS NULL THEN
    RETURN jsonb_build_object('cooldown_days', 0, 'removals', '[]'::jsonb);
  END IF;

  SELECT c.product_removal_cooldown_days INTO v_days
    FROM public.companies c WHERE c.id = v_company;
  v_days := COALESCE(v_days, 30);

  IF v_days <= 0 THEN
    RETURN jsonb_build_object('cooldown_days', 0, 'removals', '[]'::jsonb);
  END IF;

  WITH latest AS (
    SELECT DISTINCT ON (r.machine_id, r.product_id)
           r.machine_id, r.product_id, r.offered_since, r.removed_at, r.estimated
      FROM public.machine_product_removals r
      JOIN public."vendingMachine" vm ON vm.id = r.machine_id AND vm.company = v_company
     WHERE r.machine_id = ANY (p_machine_ids)
     ORDER BY r.machine_id, r.product_id, r.removed_at DESC
  ), recent AS (
    SELECT l.*
      FROM latest l
     WHERE l.removed_at >= now() - make_interval(days => v_days)
       AND NOT EXISTS (
         SELECT 1 FROM public.machine_trays mt
          WHERE mt.machine_id = l.machine_id AND mt.product_id = l.product_id
       )
  )
  SELECT COALESCE(jsonb_agg(jsonb_build_object(
           'machine_id',     x.machine_id,
           'product_id',     x.product_id,
           'offered_since',  x.offered_since,
           'removed_at',     x.removed_at,
           'estimated',      x.estimated,
           'days_offered',   CASE WHEN x.offered_since IS NULL THEN NULL
                                  ELSE GREATEST(1, ceil(extract(epoch FROM (x.removed_at - x.offered_since)) / 86400.0)::int)
                             END,
           'units_sold',     x.units_sold,
           'revenue_eur',    x.revenue_eur,
           'cooldown_until', x.removed_at + make_interval(days => v_days)
         ) ORDER BY x.machine_id, x.removed_at DESC), '[]'::jsonb)
    INTO v_result
    FROM (
      SELECT rc.*,
             count(s.id)::int AS units_sold,
             round(COALESCE(sum(s.item_price), 0)::numeric, 2) AS revenue_eur
        FROM recent rc
        LEFT JOIN public.sales s
          ON s.machine_id = rc.machine_id
         AND s.product_id = rc.product_id
         AND s.created_at <= rc.removed_at
         AND (rc.offered_since IS NULL OR s.created_at >= rc.offered_since)
       GROUP BY rc.machine_id, rc.product_id, rc.offered_since, rc.removed_at, rc.estimated
    ) x;

  RETURN jsonb_build_object('cooldown_days', v_days, 'removals', v_result);
END;
$$;

REVOKE ALL ON FUNCTION public.get_recent_product_removals(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_recent_product_removals(uuid[]) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_recent_product_removals(uuid[]) TO service_role;
