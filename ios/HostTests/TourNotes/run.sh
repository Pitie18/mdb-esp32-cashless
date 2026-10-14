#!/usr/bin/env sh
# Host test for VMflow/Models/MachineTourNote.swift — which open "notes for
# the next tour" the packing step shows, and the text sent to
# save_machine_tour_note. Pure Foundation, so it runs with any Swift toolchain
# (macOS or Linux), no Xcode project needed.
#
#   ./run.sh            (uses `swiftc` from PATH, or $SWIFTC)
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

"${SWIFTC:-swiftc}" \
  "$DIR/../../VMflow/Models/MachineTourNote.swift" \
  "$DIR/main.swift" \
  -o "$OUT/tournotes-tests"

"$OUT/tournotes-tests"
