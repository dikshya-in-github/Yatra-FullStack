#!/usr/bin/env bash
# Boots the app on 8081, runs the eSewa callback probe against it, then cleans up.
#
# The probe (tools/esewa-callback-probe.py) needs a RUNNING app and, on a clean
# database, provisions its own probe booking — so the runner is what makes the whole
# thing one command:
#
#   1. build   ./mvnw -q package -DskipTests      (only if the jar is missing)
#   2. boot    java -jar target/Yatra-0.0.1-SNAPSHOT.jar --server.port=8081
#   3. probe   python3 tools/esewa-callback-probe.py [--booking N]
#   4. clean   tools/esewa-probe-cleanup.sh --apply   (removes the probe rows)
#   5. stop    the app it started
#
# Port 8081, not 8080: 8080 is usually the IntelliJ instance, which serves whatever
# build is open in the IDE rather than the jar. Same rule as the click-through.
#
# Usage: tools/esewa-callback-probe.sh [--booking N] [--no-clean]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAR="target/Yatra-0.0.1-SNAPSHOT.jar"
LOG="target/callback-app.log"
BOOKING=""
CLEAN=1
while [ $# -gt 0 ]; do
  case "$1" in
    --booking) BOOKING="${2:-}"; shift 2 ;;
    --no-clean) CLEAN=0; shift ;;
    *) echo "usage: $0 [--booking N] [--no-clean]" >&2; exit 2 ;;
  esac
done

if [ ! -f "$JAR" ]; then
  echo "== building =="
  ./mvnw -q package -DskipTests
fi

# A stale instance on 8081 would be probed instead of this jar, and the probe would
# then be measuring a different build — refuse rather than guess.
if pgrep -f "Yatra-0.0.1-SNAPSHOT.jar" > /dev/null 2>&1; then
  echo "an instance of $JAR is already running; stop it first so this run is the jar" >&2
  exit 1
fi

echo "== booting the app on 8081 =="
nohup java -jar "$JAR" --server.port=8081 > "$LOG" 2>&1 &
APP=$!
trap 'kill "$APP" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  sleep 2
  grep -q "Started YatraApplication" "$LOG" && break
done
if ! grep -q "Started YatraApplication" "$LOG"; then
  echo "the app did not start — see $LOG" >&2
  exit 1
fi

RC=0
if [ -n "$BOOKING" ]; then
  python3 tools/esewa-callback-probe.py --booking "$BOOKING" || RC=$?
else
  python3 tools/esewa-callback-probe.py || RC=$?
fi

if [ "$CLEAN" = "1" ]; then
  echo
  echo "== cleaning the probe rows =="
  bash tools/esewa-probe-cleanup.sh --apply | tail -20
fi

exit "$RC"
