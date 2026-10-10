#!/usr/bin/env sh
# Host test for VMflow/Models/SlotChange.swift — the slot re-assignment tour
# maths (rebuildPackNeeds / fillPlan / computeLeftovers, the merged packing
# list order and the end-of-tour leftover totals). Pure Foundation, so
# it runs with any Swift toolchain (macOS or Linux), no Xcode project needed.
# Same cases as management-frontend/app/lib/__tests__/slotChange.test.ts.
#
#   ./run.sh            (uses `swiftc` from PATH, or $SWIFTC)
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

"${SWIFTC:-swiftc}" \
  "$DIR/../../VMflow/Models/SlotChange.swift" \
  "$DIR/main.swift" \
  -o "$OUT/slotchange-tests"

"$OUT/slotchange-tests"
