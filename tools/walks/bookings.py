#!/usr/bin/env python3
"""§12/§13 — the Bookings module, walked through the plan's five-step workflow.

This module is not shaped like the other three, and the walk says so rather than
pretending otherwise:

  * **Step 2 (CREATE) has no page control to press.** A booking is created by the
    customer wizard (§7), so the walk creates one through the *public* endpoint and
    checks it appears in the admin table. That is §13's reported defect verbatim
    ("Bookings made by users do not appear in admin-bookings.html"), and the only way
    to verify it is from the customer's side.
  * **Step 5 (DELETE) has no endpoint.** No booking DELETE exists anywhere in the API
    (a booking with a payment or a ticket is never hard-deleted, by design), so the
    walk *proves the absence* instead of assuming it, and Cancel is the module's
    disable.
  * **The only writable field is the status**, and the page offers Cancel for a
    Confirmed row and Confirm for a Pending one. So the UI's successful write needs a
    booking that is *paid* (two probes: one to pay and cancel through the page, one
    whose status is changed behind the page's back for the fresh-read proof).

**A finding this walk made:** the page's Confirm button can only ever produce a 409.
A booking becomes Confirmed through the payment flow itself
(`PaymentService.settle` → confirm + issue), so no reachable state is "PENDING with a
SUCCESS payment" — which is the one state Confirm accepts. The button is kept because
the API supports it and an admin-side payment capture would make it reachable; its
reachable outcome (the refusal, 409 CONFIRMATION_REQUIRES_PAYMENT) is what the walk
asserts.

It needs TWO probe bookings and **cannot remove them**: the API serves no booking
delete, so this walk leaves two bookings, their passengers, one payment and one ticket
behind, and says so. `tools/bookings-probe-cleanup.sh` removes all of it and releases
the seats they hold (dry-run by default; `--apply` to commit).

Run it with `tools/module-check.sh walks/bookings.py`.
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
# Distinct last names per probe, so a search can wait for the CONTENT it expects:
# two probes sharing a name would make `search_until(..., expect_name=...)` pass on the
# other one's row, which is the vacuous wait this harness exists to prevent.
PROBE_A_LAST = "Zzprobe%s" % TAG
PROBE_B_LAST = "Zzprobeb%s" % TAG
PROBE_EMAIL = "zz.probe.%s@example.com" % TAG
PAGE_SIZE = 8

TABLE = "bookingTableBody"
DETAIL = "bookingModal"
CONFIRM = "confirmModal"

walk = Walk("bookings")


def bookings(query=""):
    return walk.get_list("/api/admin/bookings", "bookings", query)


def total(query=""):
    """The server's own total — never a count of the rows on screen."""
    sep = "&" if "?" in query else "?"
    _, body = walk.api("GET", "/api/admin/bookings%s%ssize=1" % (query, sep), auth=True)
    return int((body or {}).get("totalElements") or 0)


def one(booking_id):
    _, body = walk.api("GET", "/api/admin/bookings/%d" % booking_id, auth=True)
    return body or {}


def probes():
    """Probe rows this walk (or an interrupted earlier one) created.

    Keyed on the EMAIL, not the name: `booking.contact_name` is built by the service
    as `"Mr Zz Zzprobe802032"` (title + first + last), so a name-prefix marker never
    matches — the first run of this walk left a booking behind and its own residue
    check said there was nothing to see.
    """
    return [b for b in bookings() if str(b.get("email", "")).startswith("zz.probe.")]


def rows(c):
    return walk.table(c, TABLE, {"pnr": 1, "customer": 2, "flight": 3, "route": 4,
                                 "pax": 5, "amount": 6, "payment": 7, "status": 8})


def ids(c, attr="data-view"):
    return walk.row_attr(c, TABLE, attr)


def present(c, attr):
    """The rows that actually carry this attribute.

    `row_attr` is row-ALIGNED — a row with no cancel button contributes `None` — so
    `not ids(c, 'data-cancel')` is False on a page where nothing has one. This is the
    version a check about "does any row offer X" wants.
    """
    return [v for v in ids(c, attr) if v]


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def page_info(c):
    return walk.text(c, "#pageInfo").strip()


def search(c, term, expect_count=None, expect_name=None, column=2):
    """`column` is the cell the name sits in: the customer is 2, the flight number 3."""
    return walk.search_until(c, "bookingSearch", TABLE, term, expect_count=expect_count,
                             expect_name=expect_name, column=column)


def set_filter(c, element_id, value):
    walk.fill(c, {element_id: value})
    c.js("document.getElementById(%s).dispatchEvent(new Event('change', {bubbles:true}))"
         % json.dumps(element_id))


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


def create_probe(flight, seat, last_name):
    """A booking made the way the wizard makes one — through the PUBLIC endpoint.

    No Authorization header: this is the customer's write, and the point of it is
    that the admin page can see the result. The write answers
    `{ bookingId, status, amount }` — `bookingId`, not `id`: the storefront reads it
    by that name, and the admin module's own response calls the same number `id`,
    so the two surfaces genuinely differ (the walk reads each one in its own
    vocabulary rather than assuming they match).
    """
    status, body = walk.api("POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Zz", "lastName": last_name,
                    "email": PROBE_EMAIL, "phone": "9800000099"},
        "passengers": [{"title": "Mr", "firstName": "Zz", "lastName": last_name,
                        "nationality": "Nepali", "type": "ADT", "seatNumber": seat}],
        "flight": {"flightNo": flight["no"], "from": flight["from"], "to": flight["to"]},
        "amount": 0
    })
    return status, body or {}


def pay(booking_id):
    """The customer's own payment path: initiate, then the mock gateway's callback.

    SUCCESS settles the payment AND confirms the booking (one settle path), which is
    what makes the row eligible for the page's Cancel action.
    """
    status, body = walk.api("POST", "/api/payments/initiate",
                            {"bookingId": booking_id, "method": "eSewa"})
    if status != 200:
        return status, body
    return walk.api("POST", "/api/payments/verify", {
        "txnId": "9A%08d" % (booking_id % 100000000),
        "bookingId": booking_id,
        "method": "eSewa",
        "outcome": "SUCCESS"
    })


def main():
    if not walk.login_admin():
        walk.check("setup: the admin API token", False)
        return 1

    before = total()
    stale = probes()
    if stale:
        walk.note("%d probe booking(s) from an earlier run are still here (ids: %s) — "
                  "tools/bookings-probe-cleanup.sh removes them"
                  % (len(stale), ", ".join(str(b["id"]) for b in stale)))
    walk.note("the database holds %d bookings before this walk" % before)

    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-bookings.js"],
            markers=[("admin-bookings.html", "All bookings")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's seed)")
    c.goto(BASE + "/admin-bookings.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE, timeout=25))
    walk.check("1: the page really called GET /api/admin/bookings (its own resource timings)",
               any("/api/admin/bookings" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "bookings" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d bookings" % before, "page=%r db=%d" % (count_text(c), before))

    first_page = [str(b["id"]) for b in bookings("size=%d" % PAGE_SIZE)]
    walk.check("1: the rows on screen ARE the server's first page (no seed merge)",
               [str(i) for i in ids(c)] == first_page,
               "page=%s api=%s" % (ids(c), first_page))
    walk.check("1: the mock's seed bookmarks are gone (this page wrote no store)",
               c.js("!localStorage.getItem('yatra_bookings_seeded_v1')"
                    " && !localStorage.getItem('yatra_bookings')"))
    walk.check("§13: neither modal is open on load",
               not walk.modal_open(c, DETAIL) and not walk.modal_open(c, CONFIRM))
    walk.check("1: no row offers a delete control (the API serves no booking delete)",
               not c.js("!!document.querySelector('#%s [data-delete]')" % TABLE))

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE: a booking is made by the WIZARD, not by this page (§13)")
    walk.check("2: the page offers no \"Add booking\" control, by design",
               c.js("!document.getElementById('addBookingBtn')"))

    flight, seat = bookable_flight()
    walk.check("2: (setup) a seeded flight with a free seat was found",
               flight is not None and seat is not None,
               "flight=%s seat=%s" % (flight and flight.get("no"), seat))
    if not flight:
        return 1

    status, body = create_probe(flight, seat, PROBE_A_LAST)
    probe_a = int(body.get("bookingId") or 0)
    seat_before = seat_status(flight["id"], seat)
    walk.check("2: a customer booking was created through the PUBLIC endpoint (no token)",
               status == 200 and probe_a > 0
               and body.get("status") == "PENDING",
               "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("2: its seat is now held on the flight",
               seat_status(flight["id"], seat) == "BOOKED",
               "%s was %s" % (seat, seat_before))
    walk.note("(the held seat is why this module's residue matters: the API's cancel is a "
              "STATUS change and does not release it, by design)")

    # §13's reported defect, verified from the customer's side.
    walk.check("2: §13 — THE ADMIN LIST SHOWS IT (searched by its id)",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["status"] == "Pending",
               "page=%s server=%s" % (rows(c), one(probe_a).get("status")))
    # `paymentStatus` is the booking's own column and reads "Pending" on a fresh
    # booking — the PAYMENT row is the thing that is absent, so that is what is
    # asserted (blank method/txn id), along with the missing ticket.
    walk.check("2: and its stored state is PENDING with no payment row and no ticket yet",
               one(probe_a).get("status") == "Pending"
               and one(probe_a).get("pnr") == ""
               and not (one(probe_a).get("payment") or {}).get("txnId")
               and not (one(probe_a).get("payment") or {}).get("method"),
               json.dumps(one(probe_a))[:260])
    walk.note("(this is the check §13 asks for: a booking made by a user, visible to the admin)")

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching a customer name narrows to the server's answer",
               search(c, "Anju", expect_count=1, expect_name="Anju"), str(rows(c)))
    walk.check("3: and the count line is the filtered total", count_text(c) == "1 booking",
               count_text(c))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "bookings" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "search=" in u)[:200])
    walk.check("3: searching a FLIGHT NUMBER finds its bookings",
               search(c, "U4 951", expect_count=None, expect_name="U4 951", column=3)
               and rows(c) and all("U4 951" in r["flight"] for r in rows(c)),
               str(rows(c))[:200])

    # Clear the box first: the filters below are queried with the search term still
    # applied, and "Confirmed × U4 951" is a different question from "Confirmed".
    search(c, "", expect_count=PAGE_SIZE)

    confirmed = total("?status=Confirmed")
    set_filter(c, "bookingStatusFilter", "Confirmed")
    walk.check("3: the booking-status filter is a query (only Confirmed rows, server's count)",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, min(PAGE_SIZE, confirmed)), timeout=25)
               and all(r["status"] == "Confirmed" for r in rows(c)),
               "page=%r api=%d" % (count_text(c), confirmed))
    walk.check("3: and its count is the server's filtered total",
               count_text(c) == "%d booking%s" % (confirmed, "" if confirmed == 1 else "s"),
               count_text(c))

    set_filter(c, "bookingStatusFilter", "Pending")
    pending = total("?status=Pending")
    walk.check("3: the Pending filter exists and finds the probe's own status",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, min(PAGE_SIZE, pending)), timeout=25)
               and all(r["status"] == "Pending" for r in rows(c)),
               "page=%r api=%d rows=%s" % (count_text(c), pending, rows(c)))
    walk.note("(Pending had no filter option before this pass — the API emits it and the "
              "badges colour it, so the page had no way to reach it)")

    set_filter(c, "bookingStatusFilter", "ALL")
    set_filter(c, "payStatusFilter", "Paid")
    paid = total("?paymentStatus=Paid")
    walk.check("3: the payment-status filter is a query too",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, min(PAGE_SIZE, paid)), timeout=25)
               and all("Paid" in r["payment"] for r in rows(c)),
               "api=%d rows=%s" % (paid, rows(c)))

    set_filter(c, "payStatusFilter", "ALL")
    after_probe = total()
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: paging is server-side (%d bookings over %d-per-page = 2 pages)"
               % (after_probe, PAGE_SIZE),
               after_probe > PAGE_SIZE
               and c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                             % (TABLE, after_probe - PAGE_SIZE), timeout=25)
               and ("of %d bookings" % after_probe) in page_info(c),
               "rows=%d info=%r api=%d" % (len(rows(c)), page_info(c), after_probe))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '1'; })[0];
        if (b) b.click();
    })()""")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)

    # ============================================================== 4. UPDATE
    walk.step("§12 step 4 — UPDATE (the status is the only writable field)")

    # (a) the API's gate: no SUCCESS payment, no confirmation.
    walk.check("4: (setup) the probe is the only row on screen — Pending, Confirm offered",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["status"] == "Pending"
               and len(present(c, "data-confirm")) == 1
               and not present(c, "data-cancel"),
               "rows=%s confirm=%s cancel=%s" % (rows(c), present(c, "data-confirm"),
                                                 present(c, "data-cancel")))
    walk.click(c, '[data-confirm="%s"]' % present(c, "data-confirm")[0])
    walk.check("4: it asks for confirmation first, naming the booking",
               c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
               and str(probe_a) in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg")[:160])
    walk.click(c, "#confirmYes")
    walk.check("4: the API REFUSES it — no successful payment on record",
               walk.wait_content(c, "#toast", "payment", timeout=25), walk.toast(c))
    walk.check("4: and the row is still Pending in the database",
               one(probe_a).get("status") == "Pending", one(probe_a).get("status"))
    walk.note("(409 CONFIRMATION_REQUIRES_PAYMENT — the rule that keeps \"Confirmed\" "
              "meaning something, surfaced as the server wrote it)")
    walk.note("(and the reachable behaviour of Confirm: the payment flow confirms the "
              "booking itself, so no Pending-with-a-SUCCESS-payment state survives for an "
              "admin to confirm by hand)")

    # (b) the customer pays — and the admin list follows the real state.
    status, body = pay(probe_a)
    walk.check("4: (setup) the customer's payment was taken (initiate + mock callback)",
               status == 200, "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("4: settling it confirmed the booking AND issued its ticket",
               one(probe_a).get("status") == "Confirmed"
               and one(probe_a).get("paymentStatus") == "Paid"
               and one(probe_a).get("pnr") != "",
               json.dumps(one(probe_a))[:220])

    # (c) the FRESH-read proof: a change behind the page's back, on a second probe.
    flight_b, seat_b = bookable_flight()
    status_b, body_b = create_probe(flight_b, seat_b, PROBE_B_LAST)
    probe_b = int(body_b.get("bookingId") or 0)
    walk.check("4: (setup) a second probe booking exists and is Pending",
               status_b == 200 and one(probe_b).get("status") == "Pending",
               "%s %s" % (status_b, json.dumps(body_b)[:120]))
    walk.check("4: (setup) it is the only row on screen",
               search(c, str(probe_b), expect_count=1, expect_name=PROBE_B_LAST))
    status, body = walk.api("PUT", "/api/admin/bookings/%d/status" % probe_b,
                            {"status": "Cancelled"}, auth=True)
    walk.check("4: (setup) it was cancelled through the API, not the page",
               status == 200 and (body or {}).get("status") == "Cancelled", str(body)[:160])
    walk.check("4: (setup) the table still shows the stale Pending it was rendered with",
               rows(c)[0]["status"] == "Pending", rows(c)[0]["status"])
    walk.click(c, '[data-view="%s"]' % probe_b)
    walk.check("4: the detail modal shows the FRESH status, not the cached row",
               c.wait_js("document.getElementById('%s').hidden === false" % DETAIL, timeout=25)
               and walk.text(c, "#dStatus").strip() == "Cancelled",
               "modal=%r table=%r" % (walk.text(c, "#dStatus").strip(), rows(c)[0]["status"]))
    walk.check("4: and its Cancel button is hidden, because the server's copy says Cancelled",
               c.js("document.getElementById('dCancelBtn').hidden === true"))
    walk.click(c, "#bookingModalDone")
    walk.check("4: Done closes it", not walk.modal_open(c, DETAIL))

    # (d) the matrix's other half, asked of the API: nothing returns to Pending.
    status, body = walk.api("PUT", "/api/admin/bookings/%d/status" % probe_b,
                            {"status": "Pending"}, auth=True)
    walk.check("4: a cancelled booking cannot be returned to Pending (409 from the API)",
               status == 409 and (body or {}).get("error") == "INVALID_STATUS_TRANSITION",
               "%s %s" % (status, json.dumps(body)[:160]))

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE (cancel IS the disable; there is no delete)")
    status, body = walk.api("DELETE", "/api/admin/bookings/%d" % probe_b, auth=True)
    walk.check("5: the API serves no booking delete at all (the absence, proved)",
               status in (404, 405), "%s %s" % (status, json.dumps(body)[:120]))
    walk.note("(R4: a booking with a payment or a ticket is never hard-deleted, so the "
              "mock's `_seed`-only trash had nothing left to act on once the seeds became "
              "database rows — it is gone from the page)")

    # The UI's successful write: Cancel on the PAID probe, which the page offers
    # because the server's copy says Confirmed.
    walk.check("5: (setup) the paid probe is on screen as Confirmed, Cancel offered",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["status"] == "Confirmed"
               and len(present(c, "data-cancel")) == 1
               and not present(c, "data-confirm"),
               "rows=%s cancel=%s" % (rows(c), present(c, "data-cancel")))
    walk.click(c, '[data-cancel="%s"]' % probe_a)
    # The message names the booking by its PNR once it has one, and the customer
    # either way — the numeric id is only the fallback.
    walk.check("5: Cancel asks for confirmation first, naming the booking and the customer",
               c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
               and PROBE_A_LAST in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg")[:160])
    walk.click(c, "#confirmNo")
    walk.check("5: declining writes nothing",
               one(probe_a).get("status") == "Confirmed"
               and not walk.modal_open(c, CONFIRM), one(probe_a).get("status"))

    walk.click(c, '[data-cancel="%s"]' % probe_a)
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
    walk.click(c, "#confirmYes")
    walk.check("5: confirming PUTs the status and the DATABASE says Cancelled",
               walk.wait_db(lambda: one(probe_a).get("status") == "Cancelled"),
               one(probe_a).get("status"))
    # The re-read keeps the row on screen — a Cancelled booking still matches its own
    # id — so the evidence is that the BADGE changed, not that the row left.
    walk.check("5: the list refreshed from the backend and shows the server's new status",
               c.wait_js("(function(){var td=document.querySelector('#%s tr td:nth-child(8)');"
                         "return !!td && td.textContent.trim() === 'Cancelled';})()" % TABLE,
                         timeout=25)
               and not present(c, "data-cancel") and not present(c, "data-confirm")
               and "cancelled" in walk.toast(c).lower(),
               "rows=%s toast=%r" % (rows(c), walk.toast(c)))
    walk.check("5: the seeds' seat stays counted, exactly as the page's subtitle says",
               seat_status(flight["id"], seat) == "BOOKED", seat_status(flight["id"], seat))

    # The reset button: present, honest about the real operation, and never pressed
    # (it re-creates the whole demo dataset).
    walk.check("5: Reset demo data exists and asks first",
               c.js("!!document.getElementById('resetBtn')") and not walk.modal_open(c, CONFIRM))
    walk.click(c, "#resetBtn")
    walk.check("5: its confirmation describes the API's own operation, not a bookings refresh",
               c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
               and "seeder" in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg")[:200])
    walk.click(c, "#confirmNo")
    walk.check("5: and declining it writes nothing", total() == after_probe + 1,
               "%d vs %d" % (total(), after_probe + 1))
    walk.note("(POST /api/admin/reset + /seed — the walk never runs it, because it "
              "re-creates the demo dataset)")

    # ---------------------------------------------------------------- tidy up
    walk.step("residue — what this walk cannot remove, and why")
    final = total()
    left = probes()
    walk.check("the two probe bookings this walk created are the only change",
               final == before + 2 and len(left) >= 2,
               "now=%d before=%d probes=%s" % (final, before, [b["id"] for b in left]))
    walk.note("(ids: %s — the API serves no booking delete, so a walk cannot clean up "
              "after itself here; `bash tools/bookings-probe-cleanup.sh` does, dry-run "
              "first)" % ", ".join(str(b["id"]) for b in left))

    walk.drain(c)
    return walk.finish(expected_failures=[
        "409 %s/api/admin/bookings" % BASE,
        "status of 409",
        "405 %s/api/admin/bookings" % BASE,
        "status of 405",
        "404 %s/api/admin/bookings" % BASE,
        "status of 404",
    ])


if __name__ == "__main__":
    sys.exit(main())
