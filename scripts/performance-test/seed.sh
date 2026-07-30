#!/usr/bin/env bash
#
# Creates the fixture links the load scenarios resolve.
#
#   ./scripts/performance-test/seed.sh [count] [base-url]
#
# Writes codes.json next to this script, which load-test.js reads at init.

set -Eeuo pipefail

COUNT="${1:-500}"
BASE_URL="${2:-http://localhost:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${HERE}/codes.json"
RAW="$(mktemp)"
trap 'rm -f "$RAW"' EXIT

echo "seeding ${COUNT} links against ${BASE_URL}"

# Created in parallel. A sequential loop spends nearly all its wall time spawning curl rather
# than exercising the service - seeding this many links that way takes longer than the load
# test it is preparing for.
#
# xargs substitutes directly into curl's argument. An earlier version wrapped this in
# `sh -c "..."` so it could pipe through sed, and the resulting three levels of quoting
# silently produced empty output while every individual piece worked when run by hand.
seq 1 "$COUNT" \
  | xargs -P 16 -I{} curl -fsS -X POST "${BASE_URL}/api/v1/links" \
      -H 'Content-Type: application/json' \
      -d '{"destinationUrl":"https://example.com/seed/{}?utm_campaign=perf"}' \
  >> "$RAW"

# Parsing is delegated to python rather than done with shell string surgery: the response
# stream is concatenated JSON objects with no separators, and getting that wrong quietly
# yields a short list rather than an error.
python3 - "$RAW" "$OUT" <<'PYTHON'
import json, re, sys

raw = open(sys.argv[1], encoding="utf-8").read()
codes = re.findall(r'"code"\s*:\s*"([^"]+)"', raw)

if not codes:
    sys.exit("no links were created - is the service running?")

# The hot key is simply the first seeded code. Scenario B concentrates every request on it,
# so the same row is written on every redirect - the contention the synchronous-counter
# decision knowingly accepted, and what this harness exists to measure rather than assume.
with open(sys.argv[2], "w", encoding="utf-8") as out:
    json.dump({"hot": codes[0], "spread": codes}, out, indent=2)

print(f"wrote {sys.argv[2]} ({len(codes)} codes, hot key {codes[0]})")
PYTHON
