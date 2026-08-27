-- Per-machine MDB/DEX item-number offset.
--
-- Some VMCs (observed on a Sanden Vendo outdoor unit) number their selections
-- densely from 1 while the front labels are two-digit tray.column ("10", "11",
-- ...). The firmware forwards the raw MDB number verbatim, so the backend
-- stores 1 where the customer pressed 10 and the tray join misses.
--
-- The offset is ADDED to the raw number at ingest time:  effective = raw + offset.
-- There is deliberately NO backfill: rows written before the offset was set keep
-- their raw numbers. `item_number_offset_since` records the cut-over so readers
-- that span it (Nayax reconciliation, DEX gap maths) can decide per row.

ALTER TABLE public."vendingMachine"
  ADD COLUMN IF NOT EXISTS item_number_offset integer NOT NULL DEFAULT 0;

ALTER TABLE public."vendingMachine"
  ADD COLUMN IF NOT EXISTS item_number_offset_since timestamptz;

COMMENT ON COLUMN public."vendingMachine".item_number_offset IS
  'Added to the raw MDB/DEX selection number at ingest: effective = raw + offset. 0 = machine numbers its selections the same way its labels read.';
COMMENT ON COLUMN public."vendingMachine".item_number_offset_since IS
  'Server-stamped moment the current offset took effect. NULL when the offset is 0. Data written before this timestamp is raw and must not be shifted retroactively.';

-- Sanity bound: a selection number is a uint16 on the wire, so an offset outside
-- this range can only be a typo.
ALTER TABLE public."vendingMachine"
  DROP CONSTRAINT IF EXISTS vendingmachine_item_number_offset_range;
ALTER TABLE public."vendingMachine"
  ADD CONSTRAINT vendingmachine_item_number_offset_range
  CHECK (item_number_offset BETWEEN -9999 AND 9999);

-- The cut-over timestamp is server-owned: no client may write it, and it is
-- restamped on every actual change of the offset value.
CREATE OR REPLACE FUNCTION public.stamp_item_number_offset_since()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.item_number_offset_since :=
      CASE WHEN NEW.item_number_offset <> 0 THEN now() ELSE NULL END;
    RETURN NEW;
  END IF;

  IF NEW.item_number_offset IS DISTINCT FROM OLD.item_number_offset THEN
    NEW.item_number_offset_since :=
      CASE WHEN NEW.item_number_offset <> 0 THEN now() ELSE NULL END;
  ELSE
    -- Unchanged offset: keep the original stamp regardless of what was sent.
    NEW.item_number_offset_since := OLD.item_number_offset_since;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS stamp_item_number_offset_since ON public."vendingMachine";
CREATE TRIGGER stamp_item_number_offset_since
  BEFORE INSERT OR UPDATE ON public."vendingMachine"
  FOR EACH ROW EXECUTE FUNCTION public.stamp_item_number_offset_since();

-- DEX gap maths: snapshots taken before the cut-over carry raw keys, snapshots
-- after it carry shifted keys. Comparing across the boundary would subtract a
-- missing key from a lifetime counter and report the whole counter as a gap.
-- Clamp the window to the cut-over when an offset is active.
CREATE OR REPLACE FUNCTION public.dex_reconcile_gaps(
  p_embedded_id uuid,
  p_window_start timestamptz,
  p_window_end timestamptz
)
RETURNS TABLE (
  item_number integer,
  dex_delta   bigint,
  sales_count bigint,
  gap         bigint
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  with cutover as (
    select max(vm.item_number_offset_since) as since
    from public."vendingMachine" vm
    where vm.embedded = p_embedded_id
      and vm.item_number_offset <> 0
  ),
  bounds as (
    select
      greatest(p_window_start, coalesce((select since from cutover), p_window_start)) as w_start,
      p_window_end as w_end
  ),
  start_snap as (
    select d.slot_counters
    from public.dex_snapshots d, bounds b
    where d.embedded_id = p_embedded_id
      and d.captured_at <= b.w_start
      and (
        (select since from cutover) is null
        or d.captured_at >= (select since from cutover)
      )
    order by d.captured_at desc
    limit 1
  ),
  end_snap as (
    select d.slot_counters
    from public.dex_snapshots d, bounds b
    where d.embedded_id = p_embedded_id
      and d.captured_at <= b.w_end
      and (
        (select since from cutover) is null
        or d.captured_at >= (select since from cutover)
      )
    order by d.captured_at desc
    limit 1
  ),
  slot_deltas as (
    select
      (key)::integer as item_number,
      coalesce(((end_snap.slot_counters -> key) ->> 'vends')::bigint, 0)
        - coalesce(((start_snap.slot_counters -> key) ->> 'vends')::bigint, 0) as dex_delta
    from end_snap
    cross join start_snap
    cross join jsonb_object_keys(end_snap.slot_counters) as key
  ),
  sales_counts as (
    select s.item_number, count(*)::bigint as sales_count
    from public.sales s, bounds b
    where s.embedded_id = p_embedded_id
      and s.created_at >= b.w_start
      and s.created_at <  b.w_end
    group by s.item_number
  )
  select
    d.item_number,
    d.dex_delta,
    coalesce(c.sales_count, 0) as sales_count,
    d.dex_delta - coalesce(c.sales_count, 0) as gap
  from slot_deltas d
  left join sales_counts c on c.item_number = d.item_number
  where d.dex_delta - coalesce(c.sales_count, 0) > 0
  order by gap desc;
$$;

COMMENT ON FUNCTION public.dex_reconcile_gaps(uuid, timestamptz, timestamptz) IS
  'Slots whose DEX vend counters grew by more than the recorded sales in the window. When the machine has an item_number_offset, the window is clamped to the cut-over so raw and shifted snapshot keys are never compared.';
