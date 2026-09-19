#!/usr/bin/env bash
# Runs one §12/§13 admin-module walk against a freshly built jar.
#
#   tools/module-check.sh [walks/<module>.py]
#
# THIS SCRIPT PACKAGES BEFORE IT BOOTS, and that is the point of it. The first
# Airlines walk ran against a jar built an hour earlier, so it tested the OLD page in
# mock mode and the failures looked like bugs in the new code. The harness also
# asserts it is serving this working tree (byte-comparing the assets the page loads
# against the files on disk), but building first is the cheap half of that guard.
#
#   --no-build   reuse the existing jar (only when you know it is current)
#
# Port 8081, not 8080: 8080 is usually the IntelliJ instance, which serves whatever
# build is open in the IDE. The harness's same-origin trap points the pages at the
# port they are served from, so the walk always talks to the app it started.
#
# Headless Chrome on 9222 is {re,}used if it is already up, and left up. The app this
# script starts is always stopped on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WALK="walks/airlines.py"
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    walks/*) WALK="$1"; shift ;;
    *) echo "usage: $0 [--no-build] [walks/<module>.py]" >&2; exit 2 ;;
  esac
done

JAR="target/Yatra-0.0.1-SNAPSHOT.jar"
LOG="target/module-check-app.log"
CHROME_LOG="target/chrome-module-check.log"

if pgrep -f "Yatra-0.0.1-SNAPSHOT.jar" > /dev/null 2>&1; then
  echo "an instance of $JAR is already running — stop it first, so the walk is the jar" >&2
  exit 1
fi

if [ "$BUILD" = "1" ]; then
  echo "== packaging (so the jar is the code under test) =="
  ./mvnw -q package -DskipTests
  ls -l "$JAR" | sed 's/^/   /'
fi

echo "== booting the app on 8081 =="
rm -f "$LOG"
nohup java -jar "$JAR" --server.port=8081 > "$LOG" 2>&1 &
APP=$!
CHROME=""
trap 'kill "$APP" $CHROME 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  sleep 2
  grep -q "Started YatraApplication" "$LOG" && break
done
if ! grep -q "Started YatraApplication" "$LOG"; then
  echo "the app did not start — see $LOG" >&2
  exit 1
fi
echo "   app up"

if ! curl -s -o /dev/null "http://127.0.0.1:9222/json/version"; then
  echo "== starting headless Chrome on 9222 =="
  rm -rf target/chrome-module-profile
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    --headless=new --remote-debugging-port=9222 \
    --user-data-dir=target/chrome-module-profile \
    --no-first-run --no-default-browser-check > "$CHROME_LOG" 2>&1 &
  CHROME=$!
  for _ in $(seq 1 40); do sleep 1; curl -s -o /dev/null "http://127.0.0.1:9222/json/version" && break; done
fi
echo "   chrome up$([ -n "$CHROME" ] && echo " (started here; stopped on exit)" || echo " (reused; left alone)")"

echo
python3 "tools/$WALK"
RC=$?
echo
echo "walk exit=$RC"
exit "$RC"
