import Foundation

// MachineTourNotes.notesForPacking / normalized. Run via ./run.sh.

var failures = 0
var checks = 0

func expect<T: Equatable>(_ actual: T, _ expected: T, _ label: String, line: Int = #line) {
    checks += 1
    if actual != expected {
        failures += 1
        print("FAIL [\(label)] line \(line):\n  got      \(actual)\n  expected \(expected)")
    }
}

let machine = UUID()
func note(_ text: String, tour: String?, at seconds: TimeInterval) -> MachineTourNote {
    MachineTourNote(
        id: UUID(), machineId: machine, note: text, writtenInTour: tour,
        createdAt: Date(timeIntervalSince1970: seconds), machineName: "M1"
    )
}

let older = note("spirals", tour: "tour-a", at: 100)
let newer = note("can risers", tour: "tour-b", at: 200)
let untoured = note("coins", tour: nil, at: 150)

// ── packing list ─────────────────────────────────────────────────────────────

expect(
    MachineTourNotes.notesForPacking([newer, older, untoured], currentTourId: nil).map(\.note),
    ["spirals", "coins", "can risers"],
    "oldest first, nothing excluded without a tour"
)
expect(
    MachineTourNotes.notesForPacking([newer, older], currentTourId: "").map(\.note),
    ["spirals", "can risers"],
    "an empty tour id excludes nothing"
)
expect(
    MachineTourNotes.notesForPacking([newer, older, untoured], currentTourId: "tour-b").map(\.note),
    ["spirals", "coins"],
    "notes of the current tour are for the next one"
)
expect(MachineTourNotes.notesForPacking([], currentTourId: "tour-b").count, 0, "no notes")

// ── text sent to save_machine_tour_note ──────────────────────────────────────

expect(MachineTourNotes.normalized("  bring spirals \n"), "bring spirals", "trimmed")
expect(MachineTourNotes.normalized(" \n\t "), "", "whitespace only deletes")
expect(MachineTourNotes.normalized(String(repeating: "a", count: 2500)).count, 2000, "capped at 2000")
let emoji = String(repeating: "👍🏽", count: 1500) // 2 scalars each
expect(MachineTourNotes.normalized(emoji).unicodeScalars.count <= 2000, true, "capped in code points")

if failures > 0 {
    print("\(failures) of \(checks) checks failed")
    exit(1)
}
print("tour notes: all \(checks) checks passed")
