#!/usr/bin/env bash
# Run the full Eirbone peer test suite (unit + integration tests).
# Usage:
#   ./run_tests.sh             – compile + run all tests
#   ./run_tests.sh unit        – run only unit tests  (*Test.java)
#   ./run_tests.sh integration – run only integration tests (*IT.java)
#   ./run_tests.sh report      – run all tests and open the Surefire HTML report

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'

banner() { echo -e "${YELLOW}=== $* ===${NC}"; }

MODE="${1:-all}"

banner "Eirbone P2P Peer — Test Suite"
echo "Mode : $MODE"
echo "Dir  : $SCRIPT_DIR"
echo ""

case "$MODE" in
  unit)
    banner "Running unit tests only"
    mvn test -Dtest="*Test" -Dit.test= -Dfile.encoding=UTF-8 --no-transfer-progress
    ;;
  integration)
    banner "Running integration tests only"
    mvn test -Dtest="*IT" -Dfile.encoding=UTF-8 --no-transfer-progress
    ;;
  report)
    banner "Running all tests + generating HTML report"
    mvn clean test -Dfile.encoding=UTF-8 --no-transfer-progress || true
    REPORT="target/surefire-reports"
    if [ -d "$REPORT" ]; then
      echo ""
      banner "Surefire report"
      echo "Text summaries in: $REPORT/"
      ls "$REPORT"/*.txt 2>/dev/null | while read -r f; do
        echo ""
        echo "--- $(basename "$f") ---"
        cat "$f"
      done
    fi
    ;;
  all|*)
    banner "Running all tests (unit + integration)"
    mvn clean test -Dfile.encoding=UTF-8 --no-transfer-progress
    ;;
esac

RC=$?
echo ""
if [ $RC -eq 0 ]; then
  echo -e "${GREEN}All tests passed.${NC}"
else
  echo -e "${RED}Tests failed (exit code $RC). Check output above.${NC}"
fi
exit $RC
