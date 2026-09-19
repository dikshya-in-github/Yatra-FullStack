#!/usr/bin/env bash
# Runs the §12/§13 admin-module walks against a freshly built jar.
#
#   tools/module-check.sh [--all] [--no-build] [walks/<module>.py]
#
# THIS SCRIPT PACKAGES BEFORE IT BOOTS, and that is the point of it. The first
# Airlines walk ran against a jar built an hour earlier, so it tested the OLD page in
# mock mode and the failures looked like bugs in the new code. The harness also
# asserts it is serving this working tree (byte-comparing the assets the page loads
# against the files on disk), but building first is the cheap half of that guard.
#
#   --all        every walk in walks/, in name order, packaging once
#   --no-build   reuse the existing jar (only when you know it is current)
#
# Port 8081, not 8080: 8080 is usually the IntelliJ instance, which serves whatever
# build is open in the IDE. The harness's same-origin trap points the pages at the
# port they are served from, so the walk always talks to the app it started.
#
# Each walk gets its OWN app, stopped before the next walk starts. The stop WAITS
# for the process to actually go: the first `--all` run reported
# `an instance … is already running` for the second walk, because the previous trap
# `kill`ed the JVM and returned before the port and the process were gone — a walk
# that did not run, reported as a failure by a runner racing its own shutdown.
#
# Headless Chrome on 9222 is {re,}used if it is already up, and left up. The app this
# script starts is always stopped on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Walk paths are named relative to `tools/` (`walks/<module>.py`), which is how the
# module docstrings describe them; they are stored here already prefixed with `tools/`.
WALKS=()
BUILD=1
ALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --all) ALL=1; shift ;;
    --no-build) BUILD=0; shift ;;
    walks/*) WALKS+=("tools/$1"); shift ;;
    tools/walks/*) WALKS+=("$1"); shift ;;
    *) echo "usage: $0 [--all] [--no-build] [walks/<module>.py ...]" >&2; exit 2 ;;
  esac
done
if [ "$ALL" = "1" ]; then
  # NOTE the path: `for f in walks/*.py` here expanded to nothing (this script runs
  # from the project root, and the walks live under tools/), so the literal glob was
  # passed to python and every `--all` run failed with "can't open file 'walks/*.py'"
  # — silently, as a walk failure rather than a usage error. Absolute-from-root it is.
  for f in tools/walks/*.py; do WALKS+=("$f"); done
fi
if [ "${#WALKS[@]}" = "0" ]; then
  WALKS=("tools/walks/airlines.py")
fi

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

CHROME=""
APP=""
# Stop the app AND wait for it: `kill` returns immediately, and the next walk's
# startup guard checks for exactly the process this one is leaving behind.
stop_app() {
  [ -n "$APP" ] || return 0
  kill "$APP" 2>/dev/null || true
  for _ in $(seq 1 60); do
    kill -0 "$APP" 2>/dev/null || break
    sleep 0.5
  done
  APP=""
  # The port is released a moment after the process goes; belt and braces.
  for _ in $(seq 1 20); do
    lsof -ti "tcp:8081" > /dev/null 2>&1 || break
    sleep 0.5
  done
}
stop_chrome() {
  [ -n "$CHROME" ] || return 0
  kill "$CHROME" 2>/dev/null || true
  CHROME=""
}
trap 'stop_app; stop_chrome' EXIT

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

RC=0
for WALK in "${WALKS[@]}"; do
  echo
  echo "=================================================================="
  echo "== $WALK"
  echo "=================================================================="

  rm -f "$LOG"
  nohup java -jar "$JAR" --server.port=8081 > "$LOG" 2>&1 &
  APP=$!

  for _ in $(seq 1 60); do
    sleep 2
    grep -q "Started YatraApplication" "$LOG" && break
  done
  if ! grep -q "Started YatraApplication" "$LOG"; then
    echo "the app did not start — see $LOG" >&2
    stop_app
    RC=1
    continue
  fi
  echo "   app up on 8081"

  WALK_RC=0
  python3 "$WALK" || WALK_RC=$?
  echo "walk exit=$WALK_RC"
  [ "$WALK_RC" = "0" ] || RC="$WALK_RC"
  stop_app
done

if [ "${#WALKS[@]}" -gt 1 ]; then
  echo
  echo "=================================================================="
  echo "MODULE-CHECK SUMMARY: ${#WALKS[@]} walk(s), exit=$RC"
  echo "=================================================================="
fi
exit "$RC"
