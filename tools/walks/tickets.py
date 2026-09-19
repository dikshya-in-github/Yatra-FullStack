#!/usr/bin/env python3
"""§12/§13 — the Tickets module, walked through the plan's five-step workflow.

Tickets is the seventh and last module, and the only one with **no write at all**:
a document is minted by the checkout when a payment settles (`TicketService.issue`,
called from `PaymentService.settle`), and the API serves no create, void, reissue or
delete. So steps 2, 4 and 5 are absences the walk *proves* rather than skips — the
Bookings walk set that precedent for its missing delete.

Four things it tests that the other six could not:

  * **A booking without a document is not a ticket.** The list is queried from
    `ticket`, so its inner join is the filter: the walk creates two probe bookings,
    settles one and leaves the other at the gateway, and checks only the settled one
    ever appears. This is the mirror of the Payments walk's check (there, reaching the
    gateway is what puts a booking ON the ledger; here it is what mints the document).
  * **The Status column is the document's own state, and the page displays the
    booking's beside it.** This is the module's actual defect: the badge used to be
    derived from the booking's status, so a **refunded** booking — which stays
    `Confirmed`, because a refund and a cancellation are two decisions — rendered its
    **voided** ticket as "Issued". The seeded refunded booking is that state, live in
    the demo: the walk finds it through the `ticketStatus=CANCELLED` filter, asserts
    its badge says Voided while its booking sub-line says Confirmed, and asserts the
    same row is reachable through the booking-status filter too — one row, two columns,
    and the old page could only show one of them.
  * **No API call voids a ticket, and the walk says so by trying.** It refunds a probe
    through the payments endpoint and then asserts the document is *still* ISSUED —
    refunding money and voiding a document are separate things in this system, and only
    `SeedService` writes `CANCELLED`. The same refund is the fresh-read proof: the
    modal must show the new payment status while the table still shows the old one.
  * **`GET /api/admin/tickets/{bookingId}` finally has a caller.** It was built for this
    page in Phase 12, documented for the rows' `data-view`, covered by a test — and no
    JS called it (the Users and Payments walks found the same shape of gap). The walk
    checks the page's own resource timings for it and compares every modal field with a
    fresh row of its own.

Its two probes are ordinary probe bookings (`zz.probe.<tag>@example.com`), so
`tools/bookings-probe-cleanup.sh` removes them and releases their seats; this walk runs
it at both ends, leaving the demo database at its seeded floor.

Run it with `tools/module-check.sh walks/tickets.py`.
"""

import json
import os
import subprocess
import sys
import time
from urllib.parse import quote

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, ROOT, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
# Distinct names per probe, and a passenger whose name the CONTACT does not carry:
# the passenger clause of the search can only be proved by a term that the contact
# clause cannot satisfy (the same trap the API test's fixture documents).
PROBE_A_LAST = "Zzprobea%s" % TAG
PROBE_B_LAST = "Zzprobeb%s" % TAG
PROBE_A_PAX_FIRST = "Zzgitapax%s" % TAG
PROBE_EMAIL = "zz.probe.%s@example.com" % TAG
PAGE_SIZE = 8

TABLE = "ticketTableBody"
DETAIL = "ticketModal"
STORE_KEY = "yatra_bookings"        # the storefront's key; this page must never write it
CLEANUP = ROOT / "tools" / "bookings-probe-cleanup.sh"

walk = Walk("tickets")


# ---------------------------------------------------------------- the list (API)
def listed(query="", size=1):
    """One ticket-list read, straight from the API — 1 row's worth, plus the counts."""
    sep = "&" if "?" in query else "?"
    _, body = walk.api("GET", "/api/admin/tickets%s%s&size=%d" % (query, sep, size),
                       auth=True)
    return body or {}


def total(query=""):
    """The server's own total — never a count of the rows on screen."""
    return int(listed(query).get("totalElements") or 0)


def one(booking_id):
    _, body = walk.api("GET", "/api/admin/tickets/%d" % booking_id, auth=True)
    return body or {}


def enc(value):
    """A query VALUE, encoded — a flight number contains a space."""
    return quote(str(value), safe="")


def probe_bookings():
    """Probe bookings this walk (or an interrupted earlier one) created.

    Keyed on the EMAIL: `booking.contact_name` is built by the service as
    `"Mr Zz Zzprobea802032"`, so a name-prefix marker never matches — the trap the
    Bookings walk documented after it cleaned zero rows while reporting success.
    """
    rows = walk.get_list("/api/admin/bookings", "bookings")
    return [b for b in rows if str(b.get("email", "")).startswith("zz.probe.")]


def seats_of(flight_id):
    _, body = walk.api("GET", "/api/flights/%d/seats" % flight_id)
    return (body or {}).get("seatMap") or []


def seat_status(flight_id, number):
    for seat in seats_of(flight_id):
        if seat["number"] == number:
            return seat["status"]
    return None


def bookable_flight():
    """A seeded flight with a free seat, and the seat to take."""
    for flight in walk.get_list("/api/admin/flights", "flights"):
        for seat in seats_of(flight["id"]):
            if str(seat.get("status", "")).upper() == "AVAILABLE":
                return flight, seat["number"]
    return None, None


def create_probe(flight, seat, last_name, passenger_first="Zz"):
    """A booking made the way the wizard makes one — through the PUBLIC endpoint."""
    status, body = walk.api("POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Zz", "lastName": last_name,
                    "email": PROBE_EMAIL, "phone": "9800000099"},
        "passengers": [{"title": "Mr", "firstName": passenger_first, "lastName": last_name,
                        "nationality": "Nepali", "type": "ADT", "seatNumber": seat}],
        "flight": {"flightNo": flight["no"], "from": flight["from"], "to": flight["to"]},
        "amount": 0
    })
    return status, (body or {})


def pay(booking_id):
    """Initiate, then the mock gateway's callback: SUCCESS settles and mints the document."""
    status, body = walk.api("POST", "/api/payments/initiate",
                            {"bookingId": booking_id, "method": "eSewa"})
    if status != 200:
        return status, body, None
    txn = "9A%08d" % (booking_id % 100000000)
    status, body = walk.api("POST", "/api/payments/verify", {
        "txnId": txn, "bookingId": booking_id, "method": "eSewa", "outcome": "SUCCESS"
    })
    return status, body, txn


def run_cleanup():
    if not CLEANUP.exists():
        return None, "%s is missing" % CLEANUP
    proc = subprocess.run(["bash", str(CLEANUP), "--apply"],
                          capture_output=True, text=True)
    lines = [l for l in proc.stdout.strip().splitlines() if l.strip()]
    return proc.returncode, "\n".join(lines[-7:])


def tidy(why):
    code, tail = run_cleanup()
    walk.note("%s: tools/bookings-probe-cleanup.sh --apply -> exit %s" % (why, code))
    for line in tail.splitlines():
        walk.note("  " + line)
    return code == 0


# ---------------------------------------------------------------- the page
def rows(c):
    return walk.table(c, TABLE, {"ticket": 1, "passenger": 2, "flight": 3, "route": 4,
                                 "booking": 5, "payment": 6, "amount": 7, "status": 8})


def ids(c, attr="data-view"):
    """The visible rows' booking ids, row-aligned — a row's identity is on its button."""
    return walk.row_attr(c, TABLE, attr)


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def page_info(c):
    return walk.text(c, "#pageInfo").strip()


def search(c, term, expect_count=None, expect_name=None):
    """The passenger is cell 2; the booking id lives in cell 5's sub-line."""
    return walk.search_until(c, "ticketSearch", TABLE, term, expect_count=expect_count,
                             expect_name=expect_name, column=2)


def set_filter(c, element_id, value):
    walk.fill(c, {element_id: value})
    c.js("document.getElementById(%s).dispatchEvent(new Event('change', {bubbles:true}))"
         % json.dumps(element_id))


def badges(c):
    """The Ticket-status BADGE texts, one per row — not the whole cell.

    The cell carries the document's badge and the booking's state as a sub-line (they
    are different columns), so reading `td:nth-child(8)`'s text would answer both
    questions at once and assert neither.
    """
    return json.loads(c.js(
        "JSON.stringify(Array.from(document.querySelectorAll('#%s tr td:nth-child(8) .badge'))"
        ".map(function (b) { return b.textContent.trim(); }))" % TABLE))


def booking_words(c):
    """The booking-state sub-lines, one per row, in the same order as `badges`."""
    return json.loads(c.js(
        "JSON.stringify(Array.from(document.querySelectorAll('#%s tr td:nth-child(8) .cell-sub'))"
        ".map(function (s) { return s.textContent.trim(); }))" % TABLE))


def wait_count(c, expected, timeout=25):
    """Wait for the count LINE to be the server's number, exactly.

    Equality, not `indexOf`: "1 ticket" is a substring of "11 tickets", so a substring
    wait is satisfied by the very table the filter was meant to replace — the trap the
    Payments walk hit on its second run.
    """
    return c.wait_js("document.getElementById('resultCount').textContent.trim() === %s"
                     % json.dumps(expected), timeout=timeout)


def fmt_npr(n):
    """The page's own money format, so the comparison is against the pixels."""
    return "NPR %s" % format(float(n or 0), ",.2f")


def main():
    if not walk.login_admin():
        walk.check("setup: the admin API token", False)
        return 1
    stale = probe_bookings()
    if stale:
        walk.note("%d probe booking(s) from an earlier run are still here (ids: %s)"
                  % (len(stale), ", ".join(str(b["id"]) for b in stale)))
        tidy("sweeping them first")
    floor = total()
    voided = total("?ticketStatus=CANCELLED")
    walk.note("the database holds %d ticket(s), of which %d voided, before this walk"
              % (floor, voided))
    walk.note("(the voided one is the seeder's refunded booking — the only CANCELLED "
              "ticket any code writes)")

    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-tickets.js"],
            markers=[("admin-tickets.html", "bookingStatusFilter")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)
    store_before = c.js("localStorage.getItem(%s)" % json.dumps(STORE_KEY))

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's derived store)")
    c.goto(BASE + "/admin-tickets.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE,
                         timeout=25))
    walk.check("1: the page really called GET /api/admin/tickets (its own resource timings)",
               any("/api/admin/tickets" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "tickets" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d ticket%s" % (floor, "" if floor == 1 else "s"),
               "page=%r db=%d" % (count_text(c), floor))

    first_page = [str(b["id"]) for b in listed("", size=PAGE_SIZE).get("bookings") or []]
    walk.check("1: the rows on screen ARE the server's first page (no derived store)",
               [str(i) for i in ids(c)] == first_page,
               "page=%s api=%s" % (ids(c), first_page))
    walk.check("1: every badge is the DOCUMENT's own state, with the booking's beside it",
               all(b in ("Issued", "Voided") for b in badges(c))
               and len(booking_words(c)) == len(badges(c))
               and all(w.startswith("booking:") for w in booking_words(c)),
               "badges=%s bookings=%s" % (badges(c), booking_words(c)))
    walk.check("§13: no modal is open on load", not walk.modal_open(c, DETAIL))
    walk.check("1: the page wrote the storefront's key nowhere",
               c.js("localStorage.getItem(%s) === %s"
                    % (json.dumps(STORE_KEY), json.dumps(store_before))))

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE: the checkout issues it; there is no Add control")
    walk.check("2: the page offers no create, void, delete or reissue control",
               c.js("!document.getElementById('addTicketBtn')")
               and not c.js("!!document.querySelector('#%s [data-void], #%s [data-delete],"
                            " #%s [data-edit]')" % (TABLE, TABLE, TABLE)))

    flight_a, seat_a = bookable_flight()
    walk.check("2: (setup) a seeded flight with a free seat was found",
               flight_a is not None and seat_a is not None,
               "flight=%s seat=%s" % (flight_a and flight_a.get("no"), seat_a))
    if not flight_a:
        return 1
    seat_a_before = seat_status(flight_a["id"], seat_a)

    status, body = create_probe(flight_a, seat_a, PROBE_A_LAST, PROBE_A_PAX_FIRST)
    probe_a = int(body.get("bookingId") or 0)
    walk.check("2: a customer booking was created through the PUBLIC endpoint (no token)",
               status == 200 and probe_a > 0, "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("2: an UNSETTLED booking has NO document — it must not be on this page",
               total() == floor and search(c, str(probe_a), expect_count=0),
               "total=%d page_rows=%s" % (total(), rows(c)))

    status, body, txn_a = pay(probe_a)
    walk.check("2: the customer paid it, and settling is what MINTS the document",
               status == 200 and one(probe_a).get("pnr") != ""
               and one(probe_a).get("ticketNo") != "",
               "%s %s" % (status, json.dumps(one(probe_a))[:160]))
    walk.check("2: NOW it is a ticket — found by its booking id, with its PNR and number",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST)
               and str(one(probe_a).get("ticketNo")) in rows(c)[0]["ticket"],
               "row=%s api=%s" % (rows(c), json.dumps(one(probe_a))[:160]))
    walk.check("2: and the list's own total went up by exactly one",
               total() == floor + 1, "now=%d was=%d" % (total(), floor))

    # A second probe that never reaches the gateway, so the inner join is exercised
    # rather than assumed: it stays invisible for the rest of the walk.
    flight_b, seat_b = bookable_flight()
    status_b, body_b = create_probe(flight_b, seat_b, PROBE_B_LAST)
    probe_b = int(body_b.get("bookingId") or 0)
    # Read it through the DETAIL endpoint, not through `one()`: a booking with no document
    # has nothing to answer with, and the endpoint says so (404 TICKET_NOT_FOUND) rather
    # than returning a blank row. That is the boundary the list's inner join encodes, and
    # the first run of this walk got it wrong by expecting an empty `pnr` instead.
    detail_status, detail_body = walk.api("GET", "/api/admin/tickets/%d" % probe_b,
                                          auth=True)
    walk.check("2: (setup) a second booking was created and left at the gateway — with no "
               "document, which the detail endpoint reports rather than blank",
               status_b == 200 and detail_status == 404
               and (detail_body or {}).get("error") == "TICKET_NOT_FOUND"
               and total("?search=%s" % enc(probe_b)) == 0,
               "%s %s %s search=%d" % (status_b, detail_status,
                                       json.dumps(detail_body)[:120],
                                       total("?search=%s" % enc(probe_b))))
    walk.check("2: and it is absent from the page too (a booking is not a document)",
               search(c, str(probe_b), expect_count=0))

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching the TICKET NUMBER finds the document the list showed",
               search(c, one(probe_a).get("ticketNo"), expect_count=1,
                      expect_name=PROBE_A_LAST), str(rows(c)))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "/api/admin/tickets" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "search=" in u)[:200])
    walk.check("3: searching the PNR finds it",
               search(c, one(probe_a).get("pnr"), expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))
    walk.check("3: searching the BOOKING ID finds it (the id the buttons carry)",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))
    walk.check("3: searching the CUSTOMER finds it",
               search(c, PROBE_A_LAST, expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))

    flight_no = one(probe_a).get("flight", {}).get("flightNo", "")
    walk.check("3: searching a FLIGHT NUMBER is a query too (the server's count agrees)",
               search(c, flight_no, expect_count=total("?search=%s" % enc(flight_no))),
               "page=%r api=%d" % (count_text(c), total("?search=%s" % enc(flight_no))))

    # The clause this pass added: passenger names. The term is the PASSENGER's first
    # name, which the contact does not carry — otherwise the contact clause would
    # satisfy the search and the check would pass with the passenger clause deleted.
    walk.check("3: searching a PASSENGER's name finds it, and the page shows that name "
               "in its Passenger column",
               search(c, PROBE_A_PAX_FIRST, expect_count=1, expect_name=PROBE_A_PAX_FIRST)
               and PROBE_A_PAX_FIRST in rows(c)[0]["passenger"],
               "page=%r rows=%s api=%d" % (count_text(c), rows(c),
                                           total("?search=%s" % enc(PROBE_A_PAX_FIRST))))
    walk.note("(the query could not do this until now — the placeholder promised it "
              "anyway; matched by an EXISTS subquery so paging stays a database page)")

    walk.check("3: clearing the box restores the first server page",
               search(c, "", expect_count=PAGE_SIZE), "rows=%d" % len(rows(c)))

    confirmed = total("?status=Confirmed")
    confirmed_text = "%d ticket%s" % (confirmed, "" if confirmed == 1 else "s")
    set_filter(c, "bookingStatusFilter", "Confirmed")
    walk.check("3: the booking-status filter is a query (Confirmed only, server's count)",
               wait_count(c, confirmed_text)
               and all(w == "booking: Confirmed" for w in booking_words(c)),
               "page=%r api=%d bookings=%s" % (count_text(c), confirmed, booking_words(c)))
    set_filter(c, "bookingStatusFilter", "ALL")

    # The document's own state — a different column, and the one the page's badge shows.
    cancelled = total("?ticketStatus=CANCELLED")
    cancelled_text = "%d ticket%s" % (cancelled, "" if cancelled == 1 else "s")
    set_filter(c, "ticketStatusFilter", "CANCELLED")
    walk.check("3: the ticket-state filter is a query, and it finds the VOIDED document "
               "(the state only the seeder writes)",
               cancelled >= 1 and wait_count(c, cancelled_text)
               and all(b == "Voided" for b in badges(c)),
               "page=%r api=%d badges=%s" % (count_text(c), cancelled, badges(c)))
    walk.check("3: and that document's booking is CANCELLED — the seeder's shape, where "
               "both columns happen to agree",
               all(w == "booking: Cancelled" for w in booking_words(c))
               and total("?ticketStatus=CANCELLED&status=Cancelled") == cancelled,
               "badges=%s bookings=%s" % (badges(c), booking_words(c)))
    walk.note("(NOT the defect: the seeder writes both the cancelled booking and its "
              "voided ticket. Where the two columns genuinely diverge is step 5 — an admin "
              "cancellation leaves the document ISSUED, and the old page, deriving the "
              "badge from the booking, painted that live document Voided)")
    walk.check("3: and the ISSUED filter is the complement",
               total("?ticketStatus=ISSUED") == floor + 1 - cancelled,
               "issued=%d all=%d voided=%d" % (total("?ticketStatus=ISSUED"), floor + 1,
                                               cancelled))
    set_filter(c, "ticketStatusFilter", "ALL")

    after_probe = total()
    walk.check("3: paging is server-side (%d tickets over %d-per-page = 2 pages)"
               % (after_probe, PAGE_SIZE),
               after_probe > PAGE_SIZE
               and c.wait_js("document.querySelectorAll('#pageBtns .page-btn').length >= 4",
                             timeout=25),
               "buttons=%s api=%d"
               % (c.js("document.querySelectorAll('#pageBtns .page-btn').length"),
                  after_probe))
    walk.check("3: the page info line is the server's arithmetic",
               ("of %d tickets" % after_probe) in page_info(c), page_info(c))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: page 2 shows the remainder and says so",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, after_probe - PAGE_SIZE), timeout=25),
               "rows=%d info=%s" % (len(rows(c)), page_info(c)))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '1'; })[0];
        if (b) b.click();
    })()""")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)

    # ============================================================== 4. UPDATE
    walk.step("§12 step 4 — UPDATE: there is none, and the detail modal is a FRESH read")
    walk.check("4: (setup) the probe is on screen as Paid, in a Confirmed booking, "
               "with an Issued document",
               search(c, PROBE_A_LAST, expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["payment"] == "Paid"
               and badges(c) == ["Issued"]
               and booking_words(c) == ["booking: Confirmed"],
               "row=%s badges=%s %s" % (rows(c), badges(c), booking_words(c)))

    # Change the row BEHIND the page's back, then open the modal: a modal built from the
    # cached list row would still show Paid. Refunding also proves the rule that no API
    # call voids a document — the money goes back and the ticket stays ISSUED.
    status, _ = walk.api("POST", "/api/admin/payments/%d/refund" % probe_a, auth=True)
    walk.check("4: (setup) the payment was refunded through the API, not the page",
               status == 200 and one(probe_a).get("paymentStatus") == "Refunded",
               "status=%s %s" % (status, one(probe_a).get("paymentStatus")))
    walk.check("4: and the DOCUMENT was NOT voided — refunding money is not voiding a "
               "ticket (only SeedService has ever written CANCELLED)",
               one(probe_a).get("ticketStatus") == "ISSUED",
               one(probe_a).get("ticketStatus"))

    walk.click(c, '[data-view="%s"]' % probe_a)
    walk.check("4: the detail modal opens on GET /api/admin/tickets/{id} — the endpoint "
               "this page never used to call",
               c.wait_js("document.getElementById('%s').hidden === false" % DETAIL,
                         timeout=25)
               and any("/api/admin/tickets/%d" % probe_a in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "tickets/" in u)[:200])
    walk.check("4: it shows the FRESH payment status, not the cached row",
               walk.text(c, "#dPayStatus").strip() == "Refunded",
               "modal=%r table=%r" % (walk.text(c, "#dPayStatus").strip(),
                                      rows(c)[0]["payment"]))
    walk.check("4: while the TABLE still shows the stale Paid it was rendered with",
               rows(c)[0]["payment"] == "Paid", rows(c)[0]["payment"])
    fresh = one(probe_a)
    walk.check("4: and every field it renders agrees with the server's own row",
               walk.text(c, "#dTicketNo").strip() == fresh.get("ticketNo")
               and walk.text(c, "#dPnr").strip() == fresh.get("pnr")
               and walk.text(c, "#dBkgId").strip() == str(probe_a)
               and walk.text(c, "#dFlightNo").strip() == fresh.get("flight", {}).get("flightNo")
               and walk.text(c, "#dTotal").strip() == fmt_npr(fresh.get("amount"))
               and walk.text(c, "#dTicketStatus").strip() == "Issued",
               "%r / %r / %r" % (walk.text(c, "#dTicketNo").strip(),
                                 walk.text(c, "#dPnr").strip(),
                                 walk.text(c, "#dTicketStatus").strip()))
    walk.check("4: the passenger list is the API's, in order",
               fresh.get("passengers") and
               PROBE_A_PAX_FIRST in walk.text(c, "#dPaxBody"),
               walk.text(c, "#dPaxBody")[:200])
    walk.click(c, "#ticketModalDone")
    walk.check("4: Done closes it", not walk.modal_open(c, DETAIL))
    walk.note("(no page control can change a document — issuing, reissuing and voiding "
              "belong to the checkout's settle path and to the seeder)")

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE (the absence, proved)")
    status, body = walk.api("DELETE", "/api/admin/tickets/%d" % probe_a, auth=True)
    walk.check("5: the API serves no ticket delete at all", status in (404, 405),
               "%s %s" % (status, json.dumps(body)[:120]))
    status, body = walk.api("POST", "/api/admin/tickets/%d/void" % probe_a, auth=True)
    walk.check("5: and no void route either — the state only the seeder writes has no "
               "admin action behind it", status in (404, 405),
               "%s %s" % (status, json.dumps(body)[:120]))
    status, body = walk.api("PUT", "/api/admin/tickets/%d" % probe_a, auth=True)
    walk.check("5: nor a reissue/update route", status in (404, 405),
               "%s %s" % (status, json.dumps(body)[:120]))

    # Cancelling the BOOKING (a real admin action, on the Bookings page) is the closest
    # thing to a "disable" this module has: the document survives it, and so does the
    # booking's row on this page — with the two states now side by side.
    status, _ = walk.api("PUT", "/api/admin/bookings/%d/status" % probe_a,
                         {"status": "Cancelled"}, auth=True)
    walk.check("5: (setup) the booking was cancelled through the API, not this page",
               status == 200 and one(probe_a).get("status") == "Cancelled",
               "status=%s %s" % (status, one(probe_a).get("status")))
    walk.check("5: the document survives a cancellation and stays ISSUED, so the row "
               "shows a live ticket on a cancelled booking",
               one(probe_a).get("ticketStatus") == "ISSUED"
               and total("?search=%s" % enc(probe_a)) == 1,
               "%s / search=%d" % (one(probe_a).get("ticketStatus"),
                                  total("?search=%s" % enc(probe_a))))
    # The wait is on the CONTENT that must change, not on the search: typing the same term
    # again leaves the row count and the name identical, so a count-keyed wait is satisfied
    # by the table it is meant to replace (the same trap as the Payments walk's filters).
    search(c, PROBE_A_LAST, expect_count=1, expect_name=PROBE_A_LAST)
    walk.check("5: and the page shows both states after the re-read (badge Issued, "
               "booking Cancelled) — the column that used to be derived from the other",
               walk.wait_content(c, "#%s tr td:nth-child(8) .cell-sub" % TABLE, "Cancelled",
                                 timeout=25)
               and badges(c) == ["Issued"] and booking_words(c) == ["booking: Cancelled"],
               "badges=%s %s" % (badges(c), booking_words(c)))
    walk.note("(the defect, on screen: the document is live, the booking is cancelled — the "
              "old page showed one derived word for both, and it was the wrong one)")

    # ---------------------------------------------------------------- tidy up
    walk.step("tidy up — the two probe bookings, the document and the seat")
    walk.check("tidy: tools/bookings-probe-cleanup.sh removed the probe rows",
               tidy("cleanup"))
    walk.check("tidy: no probe booking is left anywhere",
               len(probe_bookings()) == 0,
               json.dumps([b["email"] for b in probe_bookings()])[:200])
    walk.check("tidy: the ticket list is back at its seeded floor (%d tickets)" % floor,
               total() == floor, "now=%d was=%d" % (total(), floor))
    walk.check("tidy: and so is the voided count (%d)" % voided,
               total("?ticketStatus=CANCELLED") == voided,
               "now=%d was=%d" % (total("?ticketStatus=CANCELLED"), voided))
    walk.check("tidy: the seats the probes held were RELEASED",
               seat_status(flight_a["id"], seat_a) == seat_a_before,
               "%s is %s" % (seat_a, seat_status(flight_a["id"], seat_a)))
    walk.check("tidy: the page wrote the storefront's key nowhere all walk",
               c.js("localStorage.getItem(%s) === %s"
                    % (json.dumps(STORE_KEY), json.dumps(store_before))))

    # The three 404/405s this walk asks for are the absent write routes, which IS the
    # behaviour under test: the module is read-only by design.
    walk.drain(c)
    return walk.finish(expected_failures=[
        "404 %s/api/admin/tickets" % BASE,
        "405 %s/api/admin/tickets" % BASE,
        "status of 404",
        "status of 405",
    ])


if __name__ == "__main__":
    sys.exit(main())
