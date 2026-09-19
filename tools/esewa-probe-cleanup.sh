#!/usr/bin/env bash
# Removes the rows the live eSewa UAT harness leaves in the demo database, in FK-safe
# order, and then proves the database is back at its seeded floor.
#
# The pair to this script is tools/esewa-callback-probe.py: that tool MUST be run
# against a booking, so on a clean database it creates its own probe flight and a real
# PENDING booking — real rows, holding a real seat, in the shared demo database. This
# script is how those rows go away again. Run it after the probe, every time.
#
# Usage: tools/esewa-probe-cleanup.sh [--dry|--apply]
#   --dry    (default) run the deletes inside a transaction, print what would go and
#            the verification, then ROLLBACK. Proves the scope without changing anything.
#   --apply  the same deletes, COMMIT, then verify again from a fresh session.
#
# Scope is by MARKER, never by id, so it survives the probe minting new rows:
#   bookings  contact_email LIKE 'uat-%@example.com'   (what the probe and the walk write)
#   flights   aircraft LIKE '%UAT probe%' OR flight_no LIKE 'LP %'
# and it refuses to run at all if any booking OUTSIDE that scope points at one of those
# flights — the one way a marker-based delete could take a real customer's booking with
# it.
#
# NOTE: `head` is shadowed by an HTTP client on this machine -> use `sed -n '1p'`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/src/main/resources/application-local.properties"

MODE="${1:---dry}"
case "$MODE" in
  --dry|--apply) ;;
  *) echo "usage: $0 [--dry|--apply]" >&2; exit 2 ;;
esac

# The mysql client: XAMPP's on this machine, PATH's anywhere else.
MYSQL="${MYSQL:-/Applications/XAMPP/xamppfiles/bin/mysql}"
if [ ! -x "$MYSQL" ]; then MYSQL="$(command -v mysql || true)"; fi
if [ -z "$MYSQL" ]; then echo "no mysql client found; set MYSQL=..." >&2; exit 2; fi

prop() { grep -E "^$1=" "$PROPS" | sed -n '1p' | cut -d= -f2-; }
URL=$(prop 'spring\.datasource\.url')
HOSTPORT=$(printf '%s' "$URL" | sed -E 's#jdbc:mysql://([^/]+)/.*#\1#')
DB=$(printf '%s' "$URL" | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')
HOST=${HOSTPORT%%:*}
PORT=${HOSTPORT##*:}
MYSQL_USER=$(prop 'spring\.datasource\.username')
export MYSQL_PWD=$(prop 'spring\.datasource\.password')

run() { "$MYSQL" -h "$HOST" -P "$PORT" -u "$MYSQL_USER" --ssl --batch --raw --table "$DB" "$@"; }
scalar() { "$MYSQL" -h "$HOST" -P "$PORT" -u "$MYSQL_USER" --ssl --batch --skip-column-names "$DB" -e "$1"; }

P_BOOKING="contact_email LIKE 'uat-%@example.com'"
P_FLIGHT="aircraft LIKE '%UAT probe%' OR flight_no LIKE 'LP %'"
P_SEAT="flight_id IN (SELECT id FROM flight WHERE $P_FLIGHT)"

# --- guard: no out-of-scope booking may reference a probe flight --------------------
STRAY=$(scalar "SELECT COUNT(*) FROM booking WHERE flight_id IN (SELECT id FROM flight WHERE $P_FLIGHT) AND NOT ($P_BOOKING);")
if [ "$STRAY" != "0" ]; then
  echo "REFUSING: $STRAY booking(s) outside the probe scope reference a probe flight." >&2
  echo "           Narrow or extend the scope by hand; a marker delete would take them." >&2
  exit 1
fi

echo "== probe scope before =="
run -e "
SELECT (SELECT COUNT(*) FROM flight  WHERE $P_FLIGHT)  AS flights,
       (SELECT COUNT(*) FROM seat    WHERE $P_SEAT)    AS seats,
       (SELECT COUNT(*) FROM booking WHERE $P_BOOKING) AS bookings,
       (SELECT COUNT(*) FROM passenger WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS passengers,
       (SELECT COUNT(*) FROM payment   WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS payments,
       (SELECT COUNT(*) FROM ticket    WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS tickets;"

# The floor recorded in requirements.md at the end of Session 62 — what this database
# looks like with nothing pending. Advisory: a mismatch is reported, not fatal, because
# the Postman collection and other live checks legitimately add rows too.
FLOOR_SQL="
SELECT 'SEEDED floor (expect all 1)' AS check_name,
       (SELECT COUNT(*) FROM airline)     = 4   AS airline_4,
       (SELECT COUNT(*) FROM destination) = 11  AS destination_11,
       (SELECT COUNT(*) FROM flight)      = 12  AS flight_12,
       (SELECT COUNT(*) FROM seat)        = 682 AS seat_682,
       (SELECT COUNT(*) FROM booking)     = 8   AS booking_8,
       (SELECT COUNT(*) FROM passenger)   = 15  AS passenger_15,
       (SELECT COUNT(*) FROM payment)     = 8   AS payment_8,
       (SELECT COUNT(*) FROM ticket)      = 8   AS ticket_8,
       (SELECT COUNT(*) FROM users)       = 11  AS users_11;"

SQL=$(cat <<SQL
START TRANSACTION;

-- children first, then the parents they point at
DELETE FROM ticket    WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING);
SELECT 'ticket'   AS tbl, ROW_COUNT() AS deleted;
DELETE FROM payment   WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING);
SELECT 'payment'  AS tbl, ROW_COUNT() AS deleted;
DELETE FROM passenger WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING);
SELECT 'passenger' AS tbl, ROW_COUNT() AS deleted;
DELETE FROM booking   WHERE $P_BOOKING;
SELECT 'booking'  AS tbl, ROW_COUNT() AS deleted;
DELETE FROM seat      WHERE $P_SEAT;
SELECT 'seat'     AS tbl, ROW_COUNT() AS deleted;
DELETE FROM flight    WHERE $P_FLIGHT;
SELECT 'flight'   AS tbl, ROW_COUNT() AS deleted;

SELECT 'REMAINING probe-scope rows (expect all 0)' AS check_name;
SELECT
  (SELECT COUNT(*) FROM flight    WHERE $P_FLIGHT)  AS flights,
  (SELECT COUNT(*) FROM seat      WHERE $P_SEAT)    AS seats,
  (SELECT COUNT(*) FROM booking   WHERE $P_BOOKING) AS bookings,
  (SELECT COUNT(*) FROM passenger WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS passengers,
  (SELECT COUNT(*) FROM payment   WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS payments,
  (SELECT COUNT(*) FROM ticket    WHERE booking_id IN (SELECT id FROM booking WHERE $P_BOOKING)) AS tickets;

-- no edge may be left dangling by the deletes above
SELECT 'ORPHANS (expect all 0)' AS check_name;
SELECT
  (SELECT COUNT(*) FROM booking   b LEFT JOIN flight   f ON f.id = b.flight_id   WHERE f.id IS NULL) AS booking_flight,
  (SELECT COUNT(*) FROM seat      s LEFT JOIN flight   f ON f.id = s.flight_id   WHERE f.id IS NULL) AS seat_flight,
  (SELECT COUNT(*) FROM payment   p LEFT JOIN booking  b ON b.id = p.booking_id  WHERE b.id IS NULL) AS payment_booking,
  (SELECT COUNT(*) FROM passenger g LEFT JOIN booking  b ON b.id = g.booking_id  WHERE b.id IS NULL) AS passenger_booking,
  (SELECT COUNT(*) FROM ticket    t LEFT JOIN booking  b ON b.id = t.booking_id  WHERE b.id IS NULL) AS ticket_booking;

$FLOOR_SQL

SQL
)

if [ "$MODE" = "--apply" ]; then
  echo
  echo "== applying =="
  run -e "${SQL}COMMIT;"
  echo
  echo "== verified from a fresh session =="
  run -e "SELECT (SELECT COUNT(*) FROM flight WHERE $P_FLIGHT) AS probe_flights, (SELECT COUNT(*) FROM booking WHERE $P_BOOKING) AS probe_bookings; $FLOOR_SQL"
else
  echo
  echo "== DRY RUN (rolled back) =="
  run -e "${SQL}ROLLBACK;"
  echo
  echo "Nothing was changed. Re-run with --apply to commit."
fi
