#!/usr/bin/env bash
# Removes the probe bookings the admin-module walks must leave in the demo database,
# in FK-safe order, and releases the seats they hold.
#
# Callers, both of them walks:
#   * tools/walks/bookings.py — its module has no CREATE and no DELETE, so it cannot
#     clean up after itself through the UI (see below).
#   * tools/walks/payments.py (added Session 68) — its three probes ARE probe bookings
#     (a transaction on that page is a booking that reached the gateway), so it reuses
#     this script rather than copying its 130 lines.
#
# Why this is needed at all: the module has no CREATE and no DELETE. The walk creates
# its probe bookings through the PUBLIC endpoint (that is the only way to verify §13's
# reported defect — "bookings made by users do not appear in admin-bookings.html"), and
# the API deliberately serves no booking delete, because a booking with a payment or a
# ticket is never hard-deleted. So the walk cannot clean up after itself, and this
# script is how the rows go away again. Run it after the walk, every time.
#
# There are no JSON endpoints for any of this: `booking` has no delete route and the
# `seat` rows are only ever released by the hold sweep (which looks at PENDING holds,
# not at a booking an admin cancelled). Hence SQL.
#
# Usage: tools/bookings-probe-cleanup.sh [--dry|--apply]
#   --dry    (default) the deletes run inside a transaction, print what would go, and
#            the verification runs, then it ROLLBACKs. Proves the scope without
#            changing anything.
#   --apply  the same deletes, COMMIT, then verify again from a fresh session.
#
# Scope is by MARKER, never by id, so it survives the walk minting new rows (both
# walks mint from the same marker, which is what makes one script enough):
#   bookings  contact_email LIKE 'zz.probe.%@example.com' AND contact_name LIKE '%Zzprobe%'
# The pair is deliberate: a name alone could match something a human typed, and the
# probe writes both. **The EMAIL is the load-bearing half**: `booking.contact_name`
# is built by the service as `"Mr Zz Zzprobe802032"` (title + first + last), so a
# `contact_name LIKE 'Zz Probe%'` scope matches nothing at all — it silently cleaned
# zero rows while reporting success, which the `before`/`after` counts in this very
# output is what caught. The seats released are exactly the seats of those bookings'
# passengers, on those bookings' own flights.
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

MARKER="contact_email LIKE 'zz.probe.%@example.com' AND contact_name LIKE '%Zzprobe%'"
PROBE_IDS="SELECT id FROM booking WHERE $MARKER"

echo "== the probe bookings this will remove =="
run -e "SELECT id, contact_name, booking_status, payment_status, total_amount
          FROM booking WHERE $MARKER ORDER BY id;"
COUNT=$(scalar "SELECT COUNT(*) FROM booking WHERE $MARKER;")
if [ "$COUNT" = "0" ]; then
  echo "no probe bookings found — the database is already clean of them"
fi

echo
echo "== what goes with them (FK order: ticket, payment, passenger, then booking) =="
run -e "SELECT
          (SELECT COUNT(*) FROM ticket    WHERE booking_id IN ($PROBE_IDS)) AS tickets,
          (SELECT COUNT(*) FROM payment   WHERE booking_id IN ($PROBE_IDS)) AS payments,
          (SELECT COUNT(*) FROM passenger WHERE booking_id IN ($PROBE_IDS)) AS passengers,
          (SELECT COUNT(*) FROM seat s
             JOIN passenger p ON p.seat_number = s.seat_number
             JOIN booking b   ON b.id = p.booking_id AND b.flight_id = s.flight_id
            WHERE b.id IN ($PROBE_IDS) AND s.status = 'BOOKED') AS seats_to_release;"

echo
echo "== before =="
run -e "SELECT
          (SELECT COUNT(*) FROM booking) AS bookings,
          (SELECT COUNT(*) FROM payment) AS payments,
          (SELECT COUNT(*) FROM ticket)  AS tickets,
          (SELECT COUNT(*) FROM seat WHERE status = 'BOOKED') AS booked_seats;"

BEGIN="START TRANSACTION;"
END="$([ "$MODE" = "--apply" ] && echo "COMMIT;" || echo "ROLLBACK;")"

echo
echo "== ${MODE#--} ($BEGIN … $END) =="
run -e "
$BEGIN
-- 1. give the seats back: the API's cancel is a status change and never releases
--    them (that release belongs to the hold sweep, which only looks at PENDING
--    holds), so a cancelled probe would otherwise strand its seat for good.
UPDATE seat s
  JOIN passenger p ON p.seat_number = s.seat_number
  JOIN booking b   ON b.id = p.booking_id AND b.flight_id = s.flight_id
   SET s.status = 'AVAILABLE'
 WHERE b.id IN ($PROBE_IDS) AND s.status = 'BOOKED';
-- 2. the document, then the gateway row, then the travellers, then the booking.
DELETE FROM ticket    WHERE booking_id IN ($PROBE_IDS);
DELETE FROM payment   WHERE booking_id IN ($PROBE_IDS);
DELETE FROM passenger WHERE booking_id IN ($PROBE_IDS);
DELETE FROM booking   WHERE $MARKER;
$END"

echo
echo "== verification (fresh session) =="
if [ "$MODE" = "--apply" ]; then
  LEFT=$(scalar "SELECT COUNT(*) FROM booking WHERE $MARKER;")
  run -e "SELECT
            (SELECT COUNT(*) FROM booking) AS bookings,
            (SELECT COUNT(*) FROM payment) AS payments,
            (SELECT COUNT(*) FROM ticket)  AS tickets,
            (SELECT COUNT(*) FROM seat WHERE status = 'BOOKED') AS booked_seats,
            (SELECT COUNT(*) FROM booking WHERE $MARKER) AS probe_bookings_left,
            (SELECT COUNT(*) FROM passenger p LEFT JOIN booking b ON b.id = p.booking_id
              WHERE b.id IS NULL) AS orphan_passengers,
            (SELECT COUNT(*) FROM ticket t LEFT JOIN booking b ON b.id = t.booking_id
              WHERE b.id IS NULL) AS orphan_tickets,
            (SELECT COUNT(*) FROM payment p LEFT JOIN booking b ON b.id = p.booking_id
              WHERE b.id IS NULL) AS orphan_payments;"
  if [ "$LEFT" != "0" ]; then
    echo "FAIL: $LEFT probe booking(s) survived the delete" >&2
    exit 1
  fi
  echo "ok: 0 probe bookings, 0 orphans on every edge"
else
  echo "(dry run — nothing was committed; re-run with --apply to remove the rows above)"
fi
