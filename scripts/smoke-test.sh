#!/usr/bin/env bash
#
# SmartLink - end-to-end smoke test.
#
# Exercises the reviewer path against a running service: create a link, follow the
# redirect, check validation, read analytics, check health. Asserts on every step and
# exits non-zero on failure, so it is usable as a gate rather than only as a demo.
#
#   ./scripts/smoke-test.sh [base-url]
#
# Defaults match docker-compose.yml. Link creation is anonymous (GF-03), so no
# credential is required or accepted.

set -Eeuo pipefail

BASE_URL="${1:-${SMARTLINK_BASE_URL:-http://localhost:8080}}"

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

create_status() {
  curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/links" \
    -H 'Content-Type: application/json' -d "{\"destinationUrl\":\"$1\"}"
}

# An ISO-8601 UTC instant N seconds from now.
#
# GNU and BSD date take incompatible flags for relative time, and this script has to run
# on both - a reviewer's Linux CI and the macOS it was written on. Detected by asking for
# --version, which GNU has and BSD does not, rather than by guessing from `uname`: the
# question is which date implementation is on PATH, and on macOS with coreutils installed
# that is not decided by the operating system.
#
# The same class of assumption already broke this repository once, when `mapfile` (bash 4)
# was used on a machine shipping bash 3.2.
if date --version >/dev/null 2>&1; then
  iso_utc_in() { date -u -d "+${1} seconds" '+%Y-%m-%dT%H:%M:%SZ'; }   # GNU
else
  iso_utc_in() { date -u -v"+${1}S" '+%Y-%m-%dT%H:%M:%SZ'; }           # BSD
fi

trap 'red "smoke-test aborted on line ${LINENO}"; exit 2' ERR

echo
echo "SmartLink smoke test"
dim  "  base url: ${BASE_URL}"
echo

# ---------------------------------------------------------------------------
# 1. Service is up and ready
# ---------------------------------------------------------------------------
echo "[1] health"
READY=$(curl -fsS "${BASE_URL}/actuator/health/readiness" | tr -d ' \n')
check "readiness reports UP" '{"status":"UP"}' "$READY"

LIVE=$(curl -fsS "${BASE_URL}/actuator/health/liveness" | tr -d ' \n')
check "liveness reports UP" '{"status":"UP"}' "$LIVE"

# ---------------------------------------------------------------------------
# 2. Create a link  (GF-01, GF-02, GF-03)
# ---------------------------------------------------------------------------
echo
echo "[2] create"
TARGET="https://example.com/campaign?utm_source=smoke&id=$RANDOM"
CREATE_BODY=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
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
# 3. Independent links for a repeated destination  (GF-04)
# ---------------------------------------------------------------------------
echo
echo "[3] duplicate destination"
SECOND=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' -d "{\"destinationUrl\":\"${TARGET}\"}" \
  | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [[ -n "$SECOND" && "$SECOND" != "$CODE" ]]; then
  green "  PASS  same destination yields an independent code (${SECOND})"
  PASS=$((PASS + 1))
else
  red "  FAIL  expected a distinct code for a repeated destination"
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# 4. Redirect  (GF-07, GF-08)
# ---------------------------------------------------------------------------
echo
echo "[4] redirect"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/${CODE}")
check "resolve returns 302" "302" "$STATUS"

HEADERS=$(curl -sI "${BASE_URL}/${CODE}")
LOCATION=$(printf '%s' "$HEADERS" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
check "Location is byte-identical to the destination" "$TARGET" "$LOCATION"

CACHE=$(printf '%s' "$HEADERS" | awk 'tolower($1)=="cache-control:"{print $2}' | tr -d '\r')
check "redirect is not cacheable" "no-store" "$CACHE"

# ---------------------------------------------------------------------------
# 5. Unknown code  (GF-09)
# ---------------------------------------------------------------------------
echo
echo "[5] unknown code"
check "unknown code returns 404" "404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/zzzzzzz")"
check "malformed code also returns 404, not 400" "404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/!!!")"

# ---------------------------------------------------------------------------
# 6. Destination validation  (GF-14, GF-15, GF-16, GF-18)
# ---------------------------------------------------------------------------
echo
echo "[6] destination validation"
check "javascript: scheme rejected"          "422" "$(create_status 'javascript:alert(1)')"
check "data: scheme rejected"                "422" "$(create_status 'data:text/html,<script>1</script>')"
check "file: scheme rejected"                "422" "$(create_status 'file:///etc/passwd')"
check "cloud metadata address rejected"      "422" "$(create_status 'http://169.254.169.254/latest/meta-data/')"
check "loopback rejected"                    "422" "$(create_status 'http://127.0.0.1:8080/')"
check "private range rejected"               "422" "$(create_status 'http://10.0.0.1/')"
check "decimal-encoded metadata rejected"    "422" "$(create_status 'http://2852039166/')"
check "hex-encoded metadata rejected"        "422" "$(create_status 'http://0xA9FEA9FE/')"
check "octal-encoded metadata rejected"      "422" "$(create_status 'http://0251.0376.0251.0376/')"
check "IPv6-mapped metadata rejected"        "422" "$(create_status 'http://[::ffff:169.254.169.254]/')"
check "credential-embedded host rejected"    "422" "$(create_status 'http://expected.com@169.254.169.254/')"
check "CRLF in destination rejected"         "422" "$(create_status 'https://example.com/%0d%0aX-Injected:%20yes')"

# ---------------------------------------------------------------------------
# 7. Error body does not reflect raw input  (NFR-04)
# ---------------------------------------------------------------------------
echo
echo "[7] error safety"
ERR_BODY=$(curl -s -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"javascript:alert(document.cookie)"}')
if printf '%s' "$ERR_BODY" | grep -q 'alert(document.cookie)'; then
  red "  FAIL  error body reflects raw submitted input"
  FAIL=$((FAIL + 1))
else
  green "  PASS  error body does not reflect raw input"
  PASS=$((PASS + 1))
fi
if printf '%s' "$ERR_BODY" | grep -qiE 'exception|stacktrace|jdbc|postgres|at com\.'; then
  red "  FAIL  error body leaks implementation detail"
  FAIL=$((FAIL + 1))
else
  green "  PASS  error body leaks no implementation detail"
  PASS=$((PASS + 1))
fi

# ---------------------------------------------------------------------------
# 8. Analytics  (GF-11, GF-12)
# ---------------------------------------------------------------------------
echo
echo "[8] analytics"
# A dedicated link, resolved a known number of times. Reusing $CODE would make the
# expected count depend on how many probes earlier steps happened to send - including
# the HEAD requests from `curl -I` - which is the kind of brittle assertion that gets
# "fixed" later by loosening it until it proves nothing.
STATS_TARGET="https://example.com/analytics-probe?id=$RANDOM"
STATS_CODE=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' -d "{\"destinationUrl\":\"${STATS_TARGET}\"}" \
  | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

for _ in 1 2 3; do
  curl -fsS -o /dev/null -X GET "${BASE_URL}/${STATS_CODE}"
done

STATS=$(curl -fsS "${BASE_URL}/api/v1/links/${STATS_CODE}/analytics")
TOTAL=$(printf '%s' "$STATS" | sed -n 's/.*"totalRedirects"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
check "counter records exactly 3 redirects" "3" "$TOTAL"

check "analytics for unknown code returns 404" "404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/links/zzzzzzz/analytics")"

# ---------------------------------------------------------------------------
# 9. Expiry  (scenario 02 - BF-01..BF-07, A-11)
# ---------------------------------------------------------------------------
echo
echo "[9] expiry"

# The service decides expiry against the DATABASE clock (ADR-012), not this host's.
# In compose they are the same machine, but the window below is deliberately wider
# than any plausible skew - a smoke test that fails intermittently on timing gets
# ignored, which is worse than not having it.
EXPIRES_AT=$(iso_utc_in 5)

EXPIRING_CODE=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d "{\"destinationUrl\":\"https://example.com/campaign-ends?id=$RANDOM\",\"expiresAt\":\"${EXPIRES_AT}\"}" \
  | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

check "a link with a future expiry resolves" "302" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/${EXPIRING_CODE}")"

dim  "        waiting for the link to expire"
sleep 7

check "the same link then returns 410, not 404" "410" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/${EXPIRING_CODE}")"

# The destination is still in the database and must not be emitted. A redirect-following
# client has to be unable to reach it - a 410 that still carried Location would honour
# the status code and defeat the feature.
EXPIRED_LOCATION=$(curl -sI "${BASE_URL}/${EXPIRING_CODE}" \
  | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
check "the 410 carries no Location header" "" "$EXPIRED_LOCATION"

EXPIRED_STATS=$(curl -fsS "${BASE_URL}/api/v1/links/${EXPIRING_CODE}/analytics")
EXPIRED_STATUS=$(printf '%s' "$EXPIRED_STATS" \
  | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
check "analytics reports the link as EXPIRED" "EXPIRED" "$EXPIRED_STATUS"

# One resolve happened while active, one was refused. Only the first is a redirect.
EXPIRED_TOTAL=$(printf '%s' "$EXPIRED_STATS" \
  | sed -n 's/.*"totalRedirects"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
check "the refused attempt was not counted as a redirect" "1" "$EXPIRED_TOTAL"

# Malformed and past expiries are the caller's mistake, and must say which mistake:
# INVALID_EXPIRY rather than the generic parse failure (reviewer finding P2b).
PAST_BODY=$(curl -s -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/past","expiresAt":"2020-01-01T00:00:00Z"}')
check "an expiry in the past is refused as INVALID_EXPIRY" "INVALID_EXPIRY" \
  "$(printf '%s' "$PAST_BODY" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"

ZONELESS_BODY=$(curl -s -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/zoneless","expiresAt":"2030-01-01T00:00:00"}')
check "a zone-less expiry is refused rather than guessed" "INVALID_EXPIRY" \
  "$(printf '%s' "$ZONELESS_BODY" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"

# ---------------------------------------------------------------------------
# 10. Reliability signals  (scenario 03 - R-1, R-5)
# ---------------------------------------------------------------------------
echo
echo "[10] reliability signals"

# Fault injection lives in the test suite, where the database can be made slow or taken
# away. What a smoke test can prove against a healthy service is that the signals an
# operator would reach for during an incident actually exist - an SLO defined over
# telemetry nobody publishes is a documentation artifact, not a measurement.
check "request metrics are published" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/metrics/http.server.requests")"

check "the analytics-failure counter is registered before it is needed" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/metrics/smartlink.analytics.write.failures")"

# Adding metrics is the obvious way to widen an actuator surface by accident.
check "the actuator surface is still narrow" "404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/env")"

# NFR-14 keeps destinations out of logs; metrics must not reopen that exposure in a
# place nobody thinks to check. A URI tag of the full request would also be unbounded
# cardinality, which is how a metrics backend falls over.
LEAK_MARKER="smoke-leak-canary-$RANDOM"
LEAK_CODE=$(curl -fsS -X POST "${BASE_URL}/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d "{\"destinationUrl\":\"https://example.com/reset?token=${LEAK_MARKER}\"}" \
  | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
curl -fsS -o /dev/null "${BASE_URL}/${LEAK_CODE}"

if curl -fsS "${BASE_URL}/actuator/metrics/http.server.requests" | grep -q "$LEAK_MARKER"; then
  red "  FAIL  metrics leak the destination URL"
  FAIL=$((FAIL + 1))
else
  green "  PASS  metrics do not leak destination URLs"
  PASS=$((PASS + 1))
fi

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
