#!/usr/bin/env sh
# Host test for VMflow/Models/OrderSuggestion.swift — the warehouse order
# suggestion (cartons per product, supplier groups, the copied order text).
# Same cases as the web's lib/__tests__/orderSuggestion.test.ts. Pure
# Foundation, so it runs with any Swift toolchain (macOS or Linux), no Xcode
# project needed.
#
#   ./run.sh            (uses `swiftc` from PATH, or $SWIFTC)
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

"${SWIFTC:-swiftc}" \
  "$DIR/../../VMflow/Models/OrderSuggestion.swift" \
  "$DIR/main.swift" \
  -o "$OUT/ordersuggestion-tests"

"$OUT/ordersuggestion-tests"
