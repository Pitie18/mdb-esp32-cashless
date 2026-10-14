-- machine_tour_notes: a note written at the machine during a tour, overwritten
-- or deleted within the same tour, and marked done when the next tour starts.
-- Rolled back. Plain ASSERTs. Fake JWT for the authenticated path.
BEGIN;
SET LOCAL TIMEZONE = 'UTC';

DO $$
DECLARE
  v_company  uuid := gen_random_uuid();
  v_other    uuid := gen_random_uuid();
  v_admin    uuid := gen_random_uuid();
  v_viewer   uuid := gen_random_uuid();
  v_machine  uuid := gen_random_uuid();
  v_foreign  uuid := gen_random_uuid();
  v_id       uuid;
  v_id2      uuid;
  v_count    int;
  v_failed   boolean := false;
BEGIN
  INSERT INTO public.companies (id, name) VALUES (v_company, 'Note Co'), (v_other, 'Other Co');
  INSERT INTO auth.users (id, instance_id, email, created_at) VALUES
    (v_admin,  '00000000-0000-0000-0000-000000000000', 'note-admin@test.local', now()),
    (v_viewer, '00000000-0000-0000-0000-000000000000', 'note-viewer@test.local', now());
  INSERT INTO public.organization_members (company_id, user_id, role) VALUES
    (v_company, v_admin, 'admin'), (v_company, v_viewer, 'viewer');
  INSERT INTO public."vendingMachine" (id, name, company) VALUES
    (v_machine, 'Halle', v_company), (v_foreign, 'Fremd', v_other);

  -- A viewer may not write notes
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  BEGIN
    PERFORM public.save_machine_tour_note(v_machine, 'T1', 'Spiralen');
  EXCEPTION WHEN insufficient_privilege THEN
    v_failed := true;
  END;
  ASSERT v_failed, 'viewer must not write a note';

  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);

  -- Not for a machine of another company
  v_failed := false;
  BEGIN
    PERFORM public.save_machine_tour_note(v_foreign, 'T1', 'x');
  EXCEPTION WHEN insufficient_privilege THEN
    v_failed := true;
  END;
  ASSERT v_failed, 'admin of another company must not write';

  v_id := public.save_machine_tour_note(v_machine, 'T1', '  2 neue Spiralen  ');
  ASSERT v_id IS NOT NULL, 'note created';
  ASSERT (SELECT note FROM public.machine_tour_notes WHERE id = v_id) = '2 neue Spiralen', 'text trimmed';
  ASSERT (SELECT company_id FROM public.machine_tour_notes WHERE id = v_id) = v_company, 'company from machine';

  -- Saving again in the same tour overwrites
  v_id2 := public.save_machine_tour_note(v_machine, 'T1', '2 neue Spiralen, Dosenerhöhung');
  ASSERT v_id2 = v_id, 'same tour overwrites the note';
  ASSERT (SELECT count(*) FROM public.machine_tour_notes WHERE machine_id = v_machine) = 1, 'still one note';

  -- Empty text deletes it
  ASSERT public.save_machine_tour_note(v_machine, 'T1', '   ') IS NULL, 'empty returns null';
  ASSERT NOT EXISTS (SELECT 1 FROM public.machine_tour_notes WHERE id = v_id), 'empty deletes';

  -- Two tours, two notes
  v_id  := public.save_machine_tour_note(v_machine, 'T1', 'Spiralen');
  v_id2 := public.save_machine_tour_note(v_machine, 'T2', 'Dosenerhöhung');
  ASSERT v_id <> v_id2, 'another tour adds a note';

  -- The next tour shows both and marks them done when it starts
  v_count := public.complete_machine_tour_notes(ARRAY[v_id, v_id2], 'T3');
  ASSERT v_count = 2, 'two notes done, got ' || v_count;
  ASSERT (SELECT count(*) FROM public.machine_tour_notes WHERE machine_id = v_machine
           AND done_at IS NOT NULL AND done_in_tour = 'T3') = 2, 'done in T3';
  ASSERT public.complete_machine_tour_notes(ARRAY[v_id, v_id2], 'T3') = 0, 'retry is a no-op';

  -- A done note is not overwritten by a later save in its tour
  v_id2 := public.save_machine_tour_note(v_machine, 'T1', 'neu');
  ASSERT v_id2 <> v_id, 'done note stays, a new one is written';

  -- The viewer cannot complete notes
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_viewer::text, 'role', 'authenticated')::text, true);
  ASSERT public.complete_machine_tour_notes(ARRAY[v_id2], 'T4') = 0, 'viewer completes nothing';
END;
$$;

ROLLBACK;
