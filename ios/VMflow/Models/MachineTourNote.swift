import Foundation

// MARK: - Notes for the next refill tour ("Notiz für nächste Tour")
//
// At a machine the refiller writes a free-text note for the next tour (new
// spirals, can risers, ...) via `save_machine_tour_note`. The next tour shows
// every open note (`done_at IS NULL`) at the top of the packing step, and
// starting that tour marks the shown notes done
// (`complete_machine_tour_notes`). Table and RPCs:
// `Docker/supabase/migrations/20261013200000_machine_tour_notes.sql`.
//
// Pure Foundation (no Supabase/SwiftUI), so the filtering compiles on its own:
// host tests in `ios/HostTests/TourNotes/run.sh`.

/// One row of `machine_tour_notes`, joined with the machine's name.
struct MachineTourNote: Identifiable, Equatable, Decodable {
    let id: UUID
    let machineId: UUID
    let note: String
    /// Tour the note was written in (nil for notes written outside a tour).
    let writtenInTour: String?
    let createdAt: Date
    /// `vendingMachine(name)`; nil when the machine has no name.
    let machineName: String?

    /// Columns for `.select(...)`, including the machine name join.
    static let selectColumns = "id, machine_id, note, written_in_tour, created_at, vendingMachine(name)"

    init(id: UUID, machineId: UUID, note: String, writtenInTour: String?, createdAt: Date, machineName: String?) {
        self.id = id
        self.machineId = machineId
        self.note = note
        self.writtenInTour = writtenInTour
        self.createdAt = createdAt
        self.machineName = machineName
    }

    private struct MachineRef: Decodable {
        let name: String?
    }

    private enum CodingKeys: String, CodingKey {
        case id, note
        case machineId = "machine_id"
        case writtenInTour = "written_in_tour"
        case createdAt = "created_at"
        case vendingMachine
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        machineId = try c.decode(UUID.self, forKey: .machineId)
        note = try c.decode(String.self, forKey: .note)
        writtenInTour = try c.decodeIfPresent(String.self, forKey: .writtenInTour)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        machineName = try c.decodeIfPresent(MachineRef.self, forKey: .vendingMachine)?.name
    }
}

enum MachineTourNotes {
    /// Longest note the server accepts (`length(note) <= 2000`).
    static let maxLength = 2000

    /// The notes the packing step shows: oldest first, without the notes
    /// written in the current tour (`currentTourId`, ignored when empty) —
    /// those are meant for the *next* tour.
    static func notesForPacking(_ notes: [MachineTourNote], currentTourId: String?) -> [MachineTourNote] {
        let tour = currentTourId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return notes
            .filter { tour.isEmpty || $0.writtenInTour != tour }
            .sorted { $0.createdAt < $1.createdAt }
    }

    /// The text sent to `save_machine_tour_note`: trimmed and capped at
    /// `maxLength` Unicode scalars (Postgres `length()` counts code points,
    /// not grapheme clusters). Empty means "delete the note".
    static func normalized(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.unicodeScalars.count > maxLength else { return trimmed }
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: trimmed.unicodeScalars.prefix(maxLength))
        return String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
