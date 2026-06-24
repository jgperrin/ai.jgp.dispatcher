#!/bin/bash
#
# test-gate.sh — the local test gate for the Data Product Uploader / dispatcher (#36).
#
# Runs `mvn verify` (the full JUnit suite + the 0.80 JaCoCo line-coverage gate,
# bound to the verify phase), tallies the surefire XML reports, and POSTs the
# summary to the diagnostics API (app=dispatcher, suite=gate) as an OORS
# ObservabilityResults document, so the run shows up in WB2's Admin → Test Runs
# dashboard.
#
# Exit code is Maven's own — the POST never changes it.

set -u
cd "$(dirname "$0")/.."

START_MS=$(($(date +%s) * 1000))

mvn verify
MVN_EXIT=$?

END_MS=$(($(date +%s) * 1000))

# Tally surefire XML: <testsuite tests="n" failures="n" errors="n" skipped="n">
TOTAL=0 FAILED=0 SKIPPED=0
for f in target/surefire-reports/TEST-*.xml; do
  [ -e "$f" ] || continue
  line=$(grep -o '<testsuite[^>]*>' "$f" | head -1)
  t=$(echo "$line" | sed -n 's/.* tests="\([0-9]*\)".*/\1/p')
  fl=$(echo "$line" | sed -n 's/.* failures="\([0-9]*\)".*/\1/p')
  er=$(echo "$line" | sed -n 's/.* errors="\([0-9]*\)".*/\1/p')
  sk=$(echo "$line" | sed -n 's/.* skipped="\([0-9]*\)".*/\1/p')
  TOTAL=$((TOTAL + ${t:-0}))
  FAILED=$((FAILED + ${fl:-0} + ${er:-0}))
  SKIPPED=$((SKIPPED + ${sk:-0}))
done
PASSED=$((TOTAL - FAILED - SKIPPED))

# A Maven failure with a clean tally (e.g. compile error before tests ran, or a
# JaCoCo coverage-gate failure with all tests green) must still post as red.
if [ "$MVN_EXIT" -ne 0 ] && [ "$FAILED" -eq 0 ]; then
  FAILED=1
fi

VERSION=$(sed -n 's/.*String VERSION = "\(.*\)";.*/\1/p' src/main/java/ai/jgp/gha/dataproduct/K.java)

bin/post-test-run.sh --app dispatcher --suite gate \
  --total "$TOTAL" --passed "$PASSED" --failed "$FAILED" --skipped "$SKIPPED" \
  --duration-ms "$((END_MS - START_MS))" --version "$VERSION"

exit $MVN_EXIT
