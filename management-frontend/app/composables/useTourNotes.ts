import { useSupabaseClient } from '#imports'

// ─────────────────────────────────────────────────────────────────────────────
// Notes for the next refill tour
//
// At the machine the refiller writes down what to bring next time (new
// spirals, can risers, ...). The next tour shows every open note at the top of
// the packing step and marks them done when it starts. Writes go through the
// SECURITY DEFINER RPCs in 20261013200000_machine_tour_notes.sql, which iOS and
// Android call the same way.
// ─────────────────────────────────────────────────────────────────────────────

export interface TourNote {
  id: string
  machine_id: string
  machine_name: string | null
  note: string
  written_in_tour: string | null
  created_at: string
}

export const TOUR_NOTE_MAX_LENGTH = 2000

/** Open notes to show while packing, oldest first; the running tour's own notes are left out. */
export function notesForPacking(rows: TourNote[], currentTourId: string | null | undefined): TourNote[] {
  return rows
    .filter(n => !currentTourId || n.written_in_tour !== currentTourId)
    .sort((a, b) => a.created_at.localeCompare(b.created_at))
}

export function useTourNotes() {
  const supabase = useSupabaseClient()

  async function fetchOpenNotes(): Promise<TourNote[]> {
    const { data, error } = await (supabase as any)
      .from('machine_tour_notes')
      .select('id, machine_id, note, written_in_tour, created_at, vendingMachine(name)')
      .is('done_at', null)
      .order('created_at', { ascending: true })
    if (error) throw error
    return ((data ?? []) as any[]).map(r => ({
      id: r.id,
      machine_id: r.machine_id,
      machine_name: r.vendingMachine?.name ?? null,
      note: r.note,
      written_in_tour: r.written_in_tour ?? null,
      created_at: r.created_at,
    }))
  }

  /** This tour's open note for the machine, '' when there is none. */
  async function fetchTourNote(machineId: string, tourId: string): Promise<string> {
    const { data, error } = await (supabase as any)
      .from('machine_tour_notes')
      .select('note')
      .eq('machine_id', machineId)
      .eq('written_in_tour', tourId)
      .is('done_at', null)
      .maybeSingle()
    if (error) throw error
    return data?.note ?? ''
  }

  /** Write (or overwrite) the machine's note of this tour; empty text deletes it. */
  async function saveTourNote(machineId: string, tourId: string, note: string): Promise<void> {
    const { error } = await (supabase as any).rpc('save_machine_tour_note', {
      p_machine_id: machineId,
      p_tour_id: tourId,
      p_note: note,
    })
    if (error) throw error
  }

  async function completeNotes(noteIds: string[], tourId: string): Promise<void> {
    if (noteIds.length === 0) return
    const { error } = await (supabase as any).rpc('complete_machine_tour_notes', {
      p_note_ids: noteIds,
      p_tour_id: tourId,
    })
    if (error) throw error
  }

  return { fetchOpenNotes, fetchTourNote, saveTourNote, completeNotes }
}
