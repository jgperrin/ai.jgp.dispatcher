#!/bin/bash
#
# post-test-run.sh — POST a test-run summary to the diagnostics API.
#
# The standard reporting hook for shell-based suites (test-gate.sh): ships the
# run as a `type=test-run` diagnostics event so it shows up in WB2's Admin →
# Test Runs dashboard. Green runs self-resolve at ingest (svc DiagnosticsService
# #687). Shared, byte-for-byte, with the svc/mcp reporters (svc#738) — the test
# use-case stays out of the OORS/OOCS standards themselves (per JGP).
#
# Usage:
#   bin/post-test-run.sh --app dispatcher --suite gate --total 60 --passed 60 \
#     --failed 0 [--skipped 0] [--flaky 0] [--duration-ms 60000] \
#     [--version 0.5.0] [--schedule "daily 03:00"]
#
# Provenance comes from TEST_RUN_PROVENANCE (default `local`); target from
# TEST_RUN_API_BASE (default https://api.jgp.ai). POST_TEST_RUN=0 opts out. The
# schedule (or TEST_RUN_SCHEDULE) is the runner's cadence, shown on the cards.
#
# Never fails the caller: any error is a warning and the script exits 0.

set -u

APP="" SUITE="" TOTAL=0 PASSED=0 FAILED=0 SKIPPED=0 FLAKY=0 DURATION_MS="" VERSION=""
SCHEDULE="${TEST_RUN_SCHEDULE:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP="$2"; shift 2 ;;
    --suite) SUITE="$2"; shift 2 ;;
    --total) TOTAL="$2"; shift 2 ;;
    --passed) PASSED="$2"; shift 2 ;;
    --failed) FAILED="$2"; shift 2 ;;
    --skipped) SKIPPED="$2"; shift 2 ;;
    --flaky) FLAKY="$2"; shift 2 ;;
    --duration-ms) DURATION_MS="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --schedule) SCHEDULE="$2"; shift 2 ;;
    *) echo "[post-test-run] unknown arg: $1 (ignored)" >&2; shift ;;
  esac
done

if [ "${POST_TEST_RUN:-1}" = "0" ]; then
  echo "[post-test-run] POST_TEST_RUN=0 — skipping."
  exit 0
fi

if [ -z "$APP" ] || [ -z "$SUITE" ]; then
  echo "[post-test-run] WARNING: --app and --suite are required; not posting." >&2
  exit 0
fi

BASE="${TEST_RUN_API_BASE:-https://api.jgp.ai}"
PROVENANCE="${TEST_RUN_PROVENANCE:-local}"
OK=$([ "$FAILED" -eq 0 ] && echo true || echo false)
STATUS=$([ "$OK" = "true" ] && echo green || echo RED)
DURATION_TXT=""
if [ -n "$DURATION_MS" ]; then
  DURATION_TXT=" in $((DURATION_MS / 1000))s"
fi

# The payload is an OORS (Open Observability Results Standard, RFC-0018)
# `ObservabilityResults` document. The suite is the `source.process`; the run is
# reported as count metrics, with the `failed` metric carrying the pass/fail
# verdict (status `fail` when >0). Green is derived server-side from
# `results[].status` (no top-level `ok`). `flaky`/`skipped` exercise the
# `warn`/`skip` statuses; they do not affect green.
OBSERVED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FAILED_STATUS=$([ "$FAILED" -gt 0 ] && echo fail || echo pass)
FLAKY_STATUS=$([ "$FLAKY" -gt 0 ] && echo warn || echo pass)
SKIPPED_STATUS=$([ "$SKIPPED" -gt 0 ] && echo skip || echo pass)
SOURCE_SCHEDULE_JSON=${SCHEDULE:+, \"schedule\": \"$SCHEDULE\"}
DURATION_RESULT_JSON=""
if [ -n "$DURATION_MS" ]; then
  DURATION_RESULT_JSON=",
        { \"id\": \"$SUITE.duration\", \"type\": \"metric\", \"name\": \"Duration\", \"status\": \"pass\", \"measure\": { \"metric\": \"duration\", \"value\": $DURATION_MS, \"unit\": \"ms\" } }"
fi

BODY=$(cat <<JSON
{
  "type": "test-run",
  "app": "$APP",
  "provenance": "$PROVENANCE",
  "appVersion": "${VERSION:-}",
  "reportType": "auto",
  "timestamp": "$OBSERVED_AT",
  "errorMessage": "$APP $SUITE [$PROVENANCE]: $STATUS — $PASSED/$TOTAL passed, $FAILED failed, $SKIPPED skipped, $FLAKY flaky$DURATION_TXT",
  "payload": {
    "apiVersion": "v0.1.0",
    "kind": "ObservabilityResults",
    "observedAt": "$OBSERVED_AT",
    "source": { "process": "$SUITE", "vendor": "$APP"$SOURCE_SCHEDULE_JSON },
    "results": [
      { "id": "$SUITE.failed", "type": "metric", "name": "Failed", "status": "$FAILED_STATUS", "measure": { "metric": "failed", "value": $FAILED, "threshold": { "mustBe": 0 }, "unit": "tests" } },
      { "id": "$SUITE.passed", "type": "metric", "name": "Passed", "status": "pass", "measure": { "metric": "passed", "value": $PASSED, "unit": "tests" } },
      { "id": "$SUITE.total", "type": "metric", "name": "Total tests", "status": "pass", "measure": { "metric": "total", "value": $TOTAL, "unit": "tests" } },
      { "id": "$SUITE.skipped", "type": "metric", "name": "Skipped", "status": "$SKIPPED_STATUS", "measure": { "metric": "skipped", "value": $SKIPPED, "unit": "tests" } },
      { "id": "$SUITE.flaky", "type": "metric", "name": "Flaky", "status": "$FLAKY_STATUS", "measure": { "metric": "flaky", "value": $FLAKY, "unit": "tests" } }$DURATION_RESULT_JSON
    ]
  }
}
JSON
)

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 --max-time 10 \
  -X POST -H "Content-Type: application/json" -d "$BODY" \
  "$BASE/v4/diagnostics" 2>/dev/null) || HTTP_CODE="000"

if [ "$HTTP_CODE" = "201" ]; then
  echo "[post-test-run] posted: $APP $SUITE [$PROVENANCE] $STATUS ($PASSED/$TOTAL)"
else
  echo "[post-test-run] WARNING: POST to $BASE/v4/diagnostics returned $HTTP_CODE (ignored)" >&2
fi
exit 0
