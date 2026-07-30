#!/usr/bin/env bash
#
# SmartLink — end-to-end smoke test.
#
# Exercises the reviewer path against a running service: create a link, follow the
# redirect, read analytics, check health. Asserts on every step; exits non-zero on the
# first failure so it is usable as a gate, not just as a demo.
#
#   ./scripts/smoke-test.sh [base-url] [api-key]
#
# Defaults match docker-compose.yml.

set -Eeuo pipefail

BASE_URL="${1:-${SMARTLINK_BASE_URL:-http://localhost:8080}}"
API_KEY="${2:-${SMARTLINK_API_KEYS:-local-dev-key-alpha}}"
API_KEY="${API_KEY%%,*}" # accept a comma-separated list, use the first

PASS=0
FAIL=0

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
dim()   { printf '\033[2m%s\033[0m\n' "$1"; }

check() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    green "  PASS  ${label}"
    PASS=$((PASS + 1))
  else
    red   "  FAIL  ${label}"
    red   "        expected: ${expected}"
    red   "        actual:   ${actual}"
    FAIL=$((FAIL + 1))
  fi
}

trap 'red "smoke-test aborted on line ${LINENO}"; exit 2' ERR

echo
echo "SmartLink smoke test"
dim  "  base url: ${BASE_URL}"
echo

# ---------------------------------------------------------------------------
# 1. Service is up and ready
# ---------------------------------------------------------------------------
echo "[1] readiness"
READY=$(curl -fsS "${BASE_URL}/actuator/health/readiness" | tr -d ' \n')
check "readiness reports UP" '{"status":"UP"}' "$READY"

# ---------------------------------------------------------------------------
# 2. Create a link  (FR-1)
# ---------------------------------------------------------------------------
echo
echo "[2] create"
TARGET="https://example.com/campaign?utm_source=smoke&id=$RANDOM"
CREATE_BODY=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -d "{\"destinationUrl\":\"${TARGET}\"}")

CODE=$(printf '%s' "$CREATE_BODY" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [[ -z "$CODE" ]]; then
  red "  FAIL  could not parse short code from create response"
  dim  "        $CREATE_BODY"
  exit 1
fi
green "  PASS  created code: ${CODE}"
PASS=$((PASS + 1))

# ---------------------------------------------------------------------------
# 3. Creation is authenticated  (AC-1.6)
# ---------------------------------------------------------------------------
echo
echo "[3] auth"
NOKEY=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/links" \
  -H "Content-Type: application/json" -d "{\"destinationUrl\":\"${TARGET}\"}")
check "create without API key is rejected" "401" "$NOKEY"

# ---------------------------------------------------------------------------
# 4. Redirect  (AC-2.1, AC-2.2, AC-2.4)
# ---------------------------------------------------------------------------
echo
echo "[4] redirect"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/${CODE}")
check "resolve returns 302" "302" "$STATUS"

LOCATION=$(curl -sI "${BASE_URL}/${CODE}" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
check "Location is byte-identical to the destination" "$TARGET" "$LOCATION"

CACHE=$(curl -sI "${BASE_URL}/${CODE}" | awk 'tolower($1)=="cache-control:"{print $2}' | tr -d '\r')
check "redirect is not cacheable" "no-store" "$CACHE"

# ---------------------------------------------------------------------------
# 5. Unknown code  (AC-2.3)
# ---------------------------------------------------------------------------
echo
echo "[5] unknown code"
MISSING=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/zzzzzzz")
check "unknown code returns 404" "404" "$MISSING"

# ---------------------------------------------------------------------------
# 6. Destination validation  (AC-4.1)
# ---------------------------------------------------------------------------
echo
echo "[6] validation"
BADSCHEME=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/links" \
  -H "Content-Type: application/json" -H "X-API-Key: ${API_KEY}" \
  -d '{"destinationUrl":"javascript:alert(1)"}')
check "javascript: scheme rejected" "422" "$BADSCHEME"

PRIVATE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/links" \
  -H "Content-Type: application/json" -H "X-API-Key: ${API_KEY}" \
  -d '{"destinationUrl":"http://169.254.169.254/latest/meta-data/"}')
check "cloud metadata address rejected" "422" "$PRIVATE"

# ---------------------------------------------------------------------------
# 7. Analytics  (AC-5.1, AC-5.2)
# ---------------------------------------------------------------------------
echo
echo "[7] analytics"
# A dedicated link, resolved a known number of times. Reusing $CODE would make the
# expected count depend on how many probes the earlier steps happened to send —
# including the HEAD requests from `curl -I` — which is exactly the kind of brittle
# assertion that gets "fixed" later by loosening it until it proves nothing.
STATS_TARGET="https://example.com/analytics-probe?id=$RANDOM"
STATS_CODE=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H "Content-Type: application/json" -H "X-API-Key: ${API_KEY}" \
  -d "{\"destinationUrl\":\"${STATS_TARGET}\"}" \
  | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

for _ in 1 2 3; do
  curl -fsS -o /dev/null -X GET "${BASE_URL}/${STATS_CODE}"
done

STATS=$(curl -fsS "${BASE_URL}/api/v1/links/${STATS_CODE}/stats" -H "X-API-Key: ${API_KEY}")
TOTAL=$(printf '%s' "$STATS" | sed -n 's/.*"totalResolutions"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
check "counter records exactly 3 resolutions" "3" "$TOTAL"

# ---------------------------------------------------------------------------
echo
echo "─────────────────────────────────────"
if [[ $FAIL -eq 0 ]]; then
  green "  ${PASS} passed, 0 failed"
  echo
  exit 0
else
  red   "  ${PASS} passed, ${FAIL} FAILED"
  echo
  exit 1
fi
