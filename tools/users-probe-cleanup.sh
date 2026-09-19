#!/usr/bin/env bash
# Removes the accounts tools/walks/users.py creates, and the one probe booking that
# has to exist for the walk to reach USER_HAS_BOOKINGS — in FK-safe order, releasing
# the seat that booking holds.
#
# Why a walk needs this: two of the module's five steps cannot clean up after
# themselves through the UI, and both are deliberate.
#   * The walk creates a booking as one probe account (through the PUBLIC endpoint,
#     as the customer) so that GET /api/admin/users/{id}/bookings has something real
#     to show and so the delete refusal can be aimed at a probe row instead of at a
#     seeded one. The API serves no booking delete — a booking with a payment or a
#     ticket is never hard-deleted — so the booking cannot be removed by any call.
#   * The account holding that booking then *cannot* be deleted either: that is the
#     behaviour under test (409 USER_HAS_BOOKINGS, "set it Inactive instead"), so the
#     page is not allowed to remove it. Leaving it to the UI would mean the walk
#     proves the refusal by leaving two rows behind in the demo database.
#
# Hence SQL, and hence this script. Run it after the walk, every time.
#
# Usage: tools/users-probe-cleanup.sh [--dry|--apply]
#   --dry    (default) the deletes run inside a transaction, print what would go, and
#            the verification runs, then it ROLLBACKs. Proves the scope without
#            changing anything.
#   --apply  the same deletes, COMMIT, then verify again from a fresh session.
#
# Scope is by MARKER, never by id, so it survives the walk minting new accounts:
#   users    email LIKE 'zz.userprobe.%@example.com'
#   booking  user_id IN (those accounts) OR contact_email LIKE the same marker
# The booking scope is the FK *and* the contact string on purpose: `booking.user_id`
# is the real link (and is NULL for a guest booking), while the contact email is what
# would remain if a probe account were somehow removed before its bookings. Both are
# narrow, and neither can reach a seeded row — no seeded account or booking carries
# the marker.
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

MARKER="email LIKE 'zz.userprobe.%@example.com'"
PROBE_USERS="SELECT id FROM users WHERE $MARKER"
PROBE_BOOKINGS="SELECT id FROM booking
                 WHERE user_id IN ($PROBE_USERS)
                    OR contact_email LIKE 'zz.userprobe.%@example.com'"

echo "== the probe accounts this will remove =="
run -e "SELECT id, name, email, phone, role, status,
               (password IS NULL OR password = '') AS no_credential
          FROM users WHERE $MARKER ORDER BY id;"
COUNT=$(scalar "SELECT COUNT(*) FROM users WHERE $MARKER;")
if [ "$COUNT" = "0" ]; then
  echo "no probe accounts found — the database is already clean of them"
fi

echo
echo "== the probe bookings that go with them (they are why one account cannot be deleted) =="
run -e "SELECT id, contact_name, contact_email, booking_status, payment_status, user_id
          FROM booking WHERE id IN ($PROBE_BOOKINGS) ORDER BY id;"

echo
echo "== what goes with those (FK order: ticket, payment, passenger, booking, then user) =="
run -e "SELECT
          (SELECT COUNT(*) FROM ticket    WHERE booking_id IN ($PROBE_BOOKINGS)) AS tickets,
          (SELECT COUNT(*) FROM payment   WHERE booking_id IN ($PROBE_BOOKINGS)) AS payments,
          (SELECT COUNT(*) FROM passenger WHERE booking_id IN ($PROBE_BOOKINGS)) AS passengers,
          (SELECT COUNT(*) FROM seat s
             JOIN passenger p ON p.seat_number = s.seat_number
             JOIN booking b   ON b.id = p.booking_id AND b.flight_id = s.flight_id
            WHERE b.id IN ($PROBE_BOOKINGS) AND s.status = 'BOOKED') AS seats_to_release;"

echo
echo "== before =="
run -e "SELECT
          (SELECT COUNT(*) FROM users)   AS users,
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
--    holds), so a probe booking would otherwise strand its seat for good.
UPDATE seat s
  JOIN passenger p ON p.seat_number = s.seat_number
  JOIN booking b   ON b.id = p.booking_id AND b.flight_id = s.flight_id
   SET s.status = 'AVAILABLE'
 WHERE b.id IN ($PROBE_BOOKINGS) AND s.status = 'BOOKED';
-- 2. the document, the gateway row, the travellers, then the booking itself.
DELETE FROM ticket    WHERE booking_id IN ($PROBE_BOOKINGS);
DELETE FROM payment   WHERE booking_id IN ($PROBE_BOOKINGS);
DELETE FROM passenger WHERE booking_id IN ($PROBE_BOOKINGS);
DELETE FROM booking   WHERE id IN ($PROBE_BOOKINGS);
-- 3. only now can the accounts go: booking.user_id is the one reference to users,
--    which is exactly why the page's delete is refused while a booking exists.
DELETE FROM users     WHERE $MARKER;
$END"

echo
echo "== verification (fresh session) =="
if [ "$MODE" = "--apply" ]; then
  USERS_LEFT=$(scalar "SELECT COUNT(*) FROM users WHERE $MARKER;")
  BOOKINGS_LEFT=$(scalar "SELECT COUNT(*) FROM booking WHERE id IN ($PROBE_BOOKINGS);")
  run -e "SELECT
            (SELECT COUNT(*) FROM users)   AS users,
            (SELECT COUNT(*) FROM booking) AS bookings,
            (SELECT COUNT(*) FROM payment) AS payments,
            (SELECT COUNT(*) FROM ticket)  AS tickets,
            (SELECT COUNT(*) FROM seat WHERE status = 'BOOKED') AS booked_seats,
            (SELECT COUNT(*) FROM users WHERE $MARKER) AS probe_users_left,
            (SELECT COUNT(*) FROM booking b LEFT JOIN users u ON u.id = b.user_id
              WHERE b.user_id IS NOT NULL AND u.id IS NULL) AS orphan_booking_users,
            (SELECT COUNT(*) FROM passenger p LEFT JOIN booking b ON b.id = p.booking_id
              WHERE b.id IS NULL) AS orphan_passengers,
            (SELECT COUNT(*) FROM payment p LEFT JOIN booking b ON b.id = p.booking_id
              WHERE b.id IS NULL) AS orphan_payments;"
  if [ "$USERS_LEFT" != "0" ] || [ "$BOOKINGS_LEFT" != "0" ]; then
    echo "FAIL: $USERS_LEFT probe account(s) and $BOOKINGS_LEFT probe booking(s) survived" >&2
    exit 1
  fi
  echo "ok: 0 probe accounts, 0 probe bookings, 0 orphans on every edge"
else
  echo "(dry run — nothing was committed; re-run with --apply to remove the rows above)"
fi
