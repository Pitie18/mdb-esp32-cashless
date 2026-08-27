-- Per-machine item_number_offset: server-stamped cut-over + DEX guard.
-- Rolled back. Plain ASSERTs. See plan 2026-08-27-mdb-item-number-offset.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_admin   uuid := gen_random_uuid();
  v_dev     uuid := gen_random_uuid();
  v_machine uuid;
  v_since   timestamptz;
  v_since2  timestamptz;
  n         int;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Offset Co');
  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_admin, '00000000-0000-0000-0000-000000000000', 'offset@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'offset@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded)
    VALUES ('M-Offset', v_company, v_dev) RETURNING id INTO v_machine;

  -- 1. Default is a no-op offset with no cut-over stamp.
  SELECT item_number_offset, item_number_offset_since
    INTO n, v_since FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT n = 0, 'default offset must be 0, got ' || n;
  ASSERT v_since IS NULL, 'default cut-over must be NULL';

  -- 2. Setting a non-zero offset stamps the cut-over server-side, ignoring
  --    whatever the client sent for the timestamp.
  UPDATE public."vendingMachine"
     SET item_number_offset = 9,
         item_number_offset_since = '1999-01-01T00:00:00Z'
   WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since IS NOT NULL, 'cut-over must be stamped when offset becomes non-zero';
  ASSERT v_since > now() - interval '1 minute',
    'cut-over must be server now(), got ' || v_since;

  -- 3. An unrelated update must NOT move the cut-over.
  UPDATE public."vendingMachine" SET name = 'M-Offset renamed' WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since2
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since2 = v_since, 'unrelated update must not restamp the cut-over';

  -- 4. Clearing the offset clears the cut-over.
  UPDATE public."vendingMachine" SET item_number_offset = 0 WHERE id = v_machine;
  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since IS NULL, 'clearing the offset must clear the cut-over';

  -- 5. Out-of-range offsets are rejected.
  BEGIN
    UPDATE public."vendingMachine" SET item_number_offset = 100000 WHERE id = v_machine;
    ASSERT false, 'offset 100000 should have violated the range check';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;

  RAISE NOTICE 'machine_item_number_offset: trigger assertions passed';
END $$;

-- 6. DEX guard: snapshots from before the cut-over must not be compared with
--    snapshots after it.
DO $$
DECLARE
  v_company uuid := gen_random_uuid();
  v_admin   uuid := gen_random_uuid();
  v_dev     uuid := gen_random_uuid();
  v_machine uuid;
  v_since   timestamptz;
  v_gap     bigint;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Dex Co');
  INSERT INTO auth.users (id, instance_id, email, created_at)
    VALUES (v_admin, '00000000-0000-0000-0000-000000000000', 'dex@test.local', now());
  INSERT INTO public.users (id, company, email) VALUES (v_admin, v_company, 'dex@test.local')
    ON CONFLICT (id) DO UPDATE SET company = EXCLUDED.company;
  INSERT INTO public.organization_members (company_id, user_id, role)
    VALUES (v_company, v_admin, 'admin');
  INSERT INTO public.embeddeds (id, company, owner_id, status, status_at)
    VALUES (v_dev, v_company, v_admin, 'online', now());
  INSERT INTO public."vendingMachine" (name, company, embedded, item_number_offset)
    VALUES ('M-Dex', v_company, v_dev, 9) RETURNING id INTO v_machine;

  SELECT item_number_offset_since INTO v_since
    FROM public."vendingMachine" WHERE id = v_machine;
  ASSERT v_since IS NOT NULL, 'insert with a non-zero offset must stamp the cut-over';

  -- Pre-cut-over snapshot with raw keys and a large lifetime counter.
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"1": {"vends": 5000, "value_cents": 500000}}'::jsonb,
            5000, 500000, v_since - interval '2 hours');
  -- Two post-cut-over snapshots with shifted keys, three vends apart.
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"10": {"vends": 5000, "value_cents": 500000}}'::jsonb,
            5000, 500000, v_since + interval '1 minute');
  INSERT INTO public.dex_snapshots (embedded_id, raw, slot_counters, total_vends, total_value, captured_at)
    VALUES (v_dev, '\x00', '{"10": {"vends": 5003, "value_cents": 500300}}'::jsonb,
            5003, 500300, v_since + interval '2 hours');

  -- Window spans the cut-over. Without the guard the start snapshot would be
  -- the raw one, key "10" would be missing from it, and the gap would be 5003.
  SELECT coalesce(max(gap), 0) INTO v_gap
  FROM public.dex_reconcile_gaps(v_dev, v_since - interval '3 hours', v_since + interval '3 hours');

  ASSERT v_gap = 3,
    'gap across the cut-over must be the real delta 3, got ' || v_gap;

  RAISE NOTICE 'machine_item_number_offset: dex guard assertions passed';
END $$;

ROLLBACK;
