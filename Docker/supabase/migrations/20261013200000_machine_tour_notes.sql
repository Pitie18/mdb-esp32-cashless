-- ============================================================================
-- Notes for the next refill tour.
--
-- Standing at a machine, the refiller often notices something to bring next
-- time (new spirals, can risers, ...). From the refill step they write a free
-- text note for that machine. Every tour shows the open notes at the top of
-- the packing step until the refiller ticks them off.
--
--   machine_tour_notes          one row per note, open while done_at IS NULL
--   save_machine_tour_note      write/overwrite the machine's note of a tour
--   complete_machine_tour_notes tick notes off (packing step)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.machine_tour_notes (
  id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id       uuid        NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
  machine_id       uuid        NOT NULL REFERENCES public."vendingMachine"(id) ON DELETE CASCADE,
  note             text        NOT NULL CHECK (length(btrim(note)) > 0 AND length(note) <= 2000),
  -- Tour the note was written in (one open note per machine and tour).
  written_in_tour  text,
  created_by       uuid        REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  done_at          timestamptz,
  done_in_tour     text
);

CREATE INDEX IF NOT EXISTS machine_tour_notes_open_idx
  ON public.machine_tour_notes (company_id) WHERE done_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS machine_tour_notes_tour_uniq
  ON public.machine_tour_notes (machine_id, written_in_tour)
  WHERE done_at IS NULL AND written_in_tour IS NOT NULL;

ALTER TABLE public.machine_tour_notes ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.machine_tour_notes TO authenticated;
GRANT ALL ON public.machine_tour_notes TO service_role;

DROP POLICY IF EXISTS machine_tour_notes_select ON public.machine_tour_notes;
CREATE POLICY machine_tour_notes_select ON public.machine_tour_notes
  FOR SELECT TO authenticated
  USING (company_id = public.my_company_id());

-- ── save_machine_tour_note ──────────────────────────────────────────────────
-- Write the machine's note of this tour. Saving again in the same tour
-- overwrites it; an empty text deletes it. Returns the note id (NULL when
-- deleted). Admins only, like the rest of the refill tour.
CREATE OR REPLACE FUNCTION public.save_machine_tour_note(
  p_machine_id uuid,
  p_tour_id    text,
  p_note       text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_company_id uuid;
  v_note       text := btrim(coalesce(p_note, ''));
  v_tour       text := nullif(btrim(coalesce(p_tour_id, '')), '');
  v_id         uuid;
BEGIN
  v_company_id := public.slot_change_assert_admin(p_machine_id);

  IF length(v_note) > 2000 THEN
    RAISE EXCEPTION 'note too long' USING ERRCODE = '22023';
  END IF;

  IF v_tour IS NOT NULL THEN
    SELECT n.id INTO v_id
      FROM public.machine_tour_notes n
     WHERE n.machine_id = p_machine_id
       AND n.written_in_tour = v_tour
       AND n.done_at IS NULL
     FOR UPDATE;
  END IF;

  IF v_note = '' THEN
    IF v_id IS NOT NULL THEN
      DELETE FROM public.machine_tour_notes WHERE id = v_id;
    END IF;
    RETURN NULL;
  END IF;

  IF v_id IS NULL THEN
    INSERT INTO public.machine_tour_notes (company_id, machine_id, note, written_in_tour, created_by)
    VALUES (v_company_id, p_machine_id, v_note, v_tour, auth.uid())
    RETURNING id INTO v_id;
  ELSE
    UPDATE public.machine_tour_notes
       SET note = v_note, updated_at = now()
     WHERE id = v_id;
  END IF;

  RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.save_machine_tour_note(uuid, text, text) TO authenticated;

-- ── complete_machine_tour_notes ─────────────────────────────────────────────
-- Tick notes off; p_tour_id records the tour (if any) they were ticked in.
-- Notes of other companies and notes already done are left alone, so a retry
-- is a no-op. Returns the number of notes marked done.
CREATE OR REPLACE FUNCTION public.complete_machine_tour_notes(
  p_note_ids uuid[],
  p_tour_id  text
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_count   integer;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'not authenticated' USING ERRCODE = '42501';
  END IF;

  UPDATE public.machine_tour_notes n
     SET done_at = now(), done_in_tour = nullif(btrim(coalesce(p_tour_id, '')), ''), updated_at = now()
   WHERE n.id = ANY(coalesce(p_note_ids, '{}'))
     AND n.done_at IS NULL
     AND EXISTS (
       SELECT 1 FROM public.organization_members om
        WHERE om.company_id = n.company_id
          AND om.user_id    = v_user_id
          AND om.role       = 'admin'
     );

  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

GRANT EXECUTE ON FUNCTION public.complete_machine_tour_notes(uuid[], text) TO authenticated;
