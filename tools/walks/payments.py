#!/usr/bin/env python3
"""§12/§13 — the Payments module, walked through the plan's five-step workflow.

Payments is the second module with no CREATE and no DELETE (Bookings was the first),
and it is read-mostly in a way Bookings is not: **the only thing an admin can decide
about a transaction is whether the money went back**. So the walk tests that one write
from several sides and proves the absences rather than stepping around them:

  * **The ledger is not a bookings list.** Every row on it has a payment row behind it,
    so the walk creates its own bookings through the PUBLIC endpoint and drives them to
    the gateway (initiate, then the mock's verify) — because that is the only way a
    transaction comes into existence. A booking that never reached the gateway must NOT
    appear: that is checked before and after the payment, and it is the difference
    between "Transactions" counting transactions and counting bookings.
  * **The tiles are the database's numbers, and the page's rows are not enough to
    compute them.** Both halves of that matter: the walk asserts the four tiles against
    `stats` from the API (which are `sum`/`count` queries over the whole ledger), and it
    asserts they *do not move* when the toolbar is filtered. A client-side sum would have
    passed the first check and failed the second.
  * **The page's own store is what the fix removed.** The old page derived its ledger
    from the storefront's `yatra_bookings` key and wrote a refund back into it. The walk
    snapshots that key before opening the page and compares it after a real refund: a
    refund that changes a browser array while MySQL stays SUCCESS is the defect, and the
    only way to see its absence is to look at the key.
  * **The refusal the UI cannot produce.** A refund is refused with 409
    PAYMENT_NOT_REFUNDABLE when the payment never succeeded — and the page hides the
    button in exactly that state, so the rule is unreachable by clicking. The walk asks
    the API directly (the same class of gap the Users walk found in the admin lock:
    a page-side rule hiding the server's own). The refund is also idempotent, so the
    walk refunds the same transaction twice and expects the second to be a no-op.

It needs THREE probe bookings — one to refund through the page, one to refund behind
the page's back (the fresh-read proof), one that only ever gets initiated (the refusal)
— and the API serves no booking or payment DELETE, so it cannot remove them itself.
They are ordinary probe bookings (`zz.probe.<tag>@example.com`), so
`tools/bookings-probe-cleanup.sh` removes them and releases the seats they hold; this
walk runs it at the end (and at the start, for an interrupted run), leaving the demo
database at its seeded floor. Reusing that script rather than copying its 130 lines is
deliberate: a payments probe IS a probe booking, and the marker is the same.

Run it with `tools/module-check.sh walks/payments.py`.
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
# Distinct last names, so a search can wait for the CONTENT it expects: two probes
# sharing a name would make `search_until(..., expect_name=...)` pass on the other
# one's row, which is the vacuous wait this harness exists to prevent. All three
# contain "Zzprobe", which is half of the cleanup script's marker.
PROBE_A_LAST = "Zzprobea%s" % TAG
PROBE_B_LAST = "Zzprobeb%s" % TAG
PROBE_C_LAST = "Zzprobec%s" % TAG
PROBE_EMAIL = "zz.probe.%s@example.com" % TAG
PAGE_SIZE = 8

TABLE = "paymentTableBody"
DETAIL = "payModal"
CONFIRM = "confirmModal"
STORE_KEY = "yatra_bookings"        # the storefront's key; this page must never write it
CLEANUP = ROOT / "tools" / "bookings-probe-cleanup.sh"

walk = Walk("payments")


# ---------------------------------------------------------------- the ledger (API)
def ledger(query="", size=1):
    """One ledger read, straight from the API — `totalElements` AND the tiles.

    `size=1` for the counts (the server's own arithmetic, never a count of the rows
    on screen) and the same call carries `stats`, so a check about the tiles is not
    a second round trip that could see a different database.
    """
    sep = "&" if "?" in query else "?"
    _, body = walk.api("GET", "/api/admin/payments%s%s&size=%d" % (query, sep, size),
                       auth=True)
    return body or {}


def total(query=""):
    return int(ledger(query).get("totalElements") or 0)


def stats():
    return ledger().get("stats") or {}


def one(booking_id):
    _, body = walk.api("GET", "/api/admin/bookings/%d" % booking_id, auth=True)
    return body or {}


def probe_bookings():
    """Probe bookings this walk (or an interrupted earlier one) created.

    Keyed on the EMAIL: `booking.contact_name` is built by the service as
    `"Mr Zz Zzprobea802032"`, so a name-prefix marker never matches — the same trap
    the Bookings walk documented after it cleaned zero rows while reporting success.
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


def create_probe(flight, seat, last_name):
    """A booking made the way the wizard makes one — through the PUBLIC endpoint.

    No Authorization header: this is the customer's write, and every transaction on
    the admin ledger starts here. The write answers `{ bookingId, ... }` — the
    storefront's key, which is not the admin module's `id`.
    """
    status, body = walk.api("POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Zz", "lastName": last_name,
                    "email": PROBE_EMAIL, "phone": "9800000099"},
        "passengers": [{"title": "Mr", "firstName": "Zz", "lastName": last_name,
                        "nationality": "Nepali", "type": "ADT", "seatNumber": seat}],
        "flight": {"flightNo": flight["no"], "from": flight["from"], "to": flight["to"]},
        "amount": 0
    })
    return status, (body or {})


def initiate(booking_id, method="eSewa"):
    """Opens the transaction — the customer's own first step, and the PENDING row."""
    return walk.api("POST", "/api/payments/initiate",
                    {"bookingId": booking_id, "method": method})


def pay(booking_id, method="eSewa"):
    """Initiate, then the mock gateway's callback: SUCCESS settles and confirms."""
    status, body = initiate(booking_id, method)
    if status != 200:
        return status, body, None
    txn = "9A%08d" % (booking_id % 100000000)
    status, body = walk.api("POST", "/api/payments/verify", {
        "txnId": txn, "bookingId": booking_id, "method": method, "outcome": "SUCCESS"
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
    return walk.table(c, TABLE, {"txn": 1, "customer": 2, "booking": 3, "method": 4,
                                 "date": 5, "amount": 6, "status": 7})


def ids(c, attr="data-view"):
    """The visible rows' booking ids, row-aligned — a row's identity is on its buttons."""
    return walk.row_attr(c, TABLE, attr)


def present(c, attr):
    """Only the rows that carry this attribute (`row_attr` is row-aligned)."""
    return [v for v in ids(c, attr) if v]


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def page_info(c):
    return walk.text(c, "#pageInfo").strip()


def search(c, term, expect_count=None, expect_name=None):
    """The customer's name is cell 2 (the name and its phone sub-line share the cell)."""
    return walk.search_until(c, "paySearch", TABLE, term, expect_count=expect_count,
                             expect_name=expect_name, column=2)


def set_filter(c, element_id, value):
    walk.fill(c, {element_id: value})
    c.js("document.getElementById(%s).dispatchEvent(new Event('change', {bubbles:true}))"
         % json.dumps(element_id))


def enc(value):
    """A query VALUE, encoded. A flight number is `"U4 951"`, and an unencoded space
    is not a URL — the first run of this walk died in urllib with InvalidURL, which
    is the good version of that mistake (the alternative is a search for the wrong
    term reported as a page bug).
    """
    return quote(str(value), safe="")


def wait_count(c, expected, timeout=25):
    """Wait for the count LINE to be the server's number, EXACTLY.

    A substring wait is not enough on this page, and the second run of this walk is
    why: a filtered total of 1 renders "1 transaction", which is a substring of the
    unfiltered "11 transactions" — so `indexOf` was satisfied instantly by the very
    table the filter was meant to replace, and the check read the old rows. Equality
    against the whole line cannot be satisfied by an unrelated total.
    """
    return c.wait_js("document.getElementById('resultCount').textContent.trim() === %s"
                     % json.dumps(expected), timeout=timeout)


def fmt_npr(n):
    """The page's own money format, so the comparison is against the pixels."""
    return "NPR %s" % format(float(n or 0), ",.2f")


def tiles(c):
    return {
        "transactions": walk.text(c, "#payTxns").strip(),
        "collected": walk.text(c, "#payCollected").strip(),
        "refunded": walk.text(c, "#payRefunded").strip(),
        "pending": walk.text(c, "#payPending").strip()
    }


def expected_tiles(s):
    return {
        "transactions": str(int(s.get("transactions") or 0)),
        "collected": fmt_npr(s.get("collected")),
        "refunded": fmt_npr(s.get("refunded")),
        "pending": str(int(s.get("pending") or 0))
    }


def open_detail(c, booking_id):
    walk.click(c, '[data-view="%s"]' % booking_id)
    return c.wait_js("document.getElementById('%s').hidden === false" % DETAIL, timeout=25)


def close_detail(c):
    walk.click(c, "#payModalDone")


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
    floor_stats = stats()
    walk.note("the ledger holds %d transaction(s) before this walk" % floor)
    walk.note("the database's own tiles: %s" % json.dumps(floor_stats))

    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-payments.js"],
            markers=[("admin-payments.html", "payTxns")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)
    # The storefront's key, BEFORE this page is ever opened: the old page both derived
    # its ledger from it and wrote a refund back into it.
    store_before = c.js("localStorage.getItem(%s)" % json.dumps(STORE_KEY))

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's derived store)")
    c.goto(BASE + "/admin-payments.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE,
                         timeout=25))
    walk.check("1: the page really called GET /api/admin/payments (its own resource timings)",
               any("/api/admin/payments" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "payments" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d transaction%s" % (floor, "" if floor == 1 else "s"),
               "page=%r db=%d" % (count_text(c), floor))

    first_page = [str(b["id"]) for b in ledger("", size=PAGE_SIZE).get("bookings") or []]
    walk.check("1: the rows on screen ARE the server's first page (no derived store)",
               [str(i) for i in ids(c)] == first_page,
               "page=%s api=%s" % (ids(c), first_page))
    walk.check("1: the four tiles are the SERVER's numbers (sum/count over the ledger)",
               tiles(c) == expected_tiles(floor_stats),
               "page=%s api=%s" % (tiles(c), expected_tiles(floor_stats)))
    walk.check("§13: no modal is open on load",
               not walk.modal_open(c, DETAIL) and not walk.modal_open(c, CONFIRM))
    walk.check("1: the page wrote the storefront's key nowhere",
               c.js("localStorage.getItem(%s) === %s"
                    % (json.dumps(STORE_KEY), json.dumps(store_before))))

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE: a transaction comes from the CHECKOUT, not this page")
    walk.check("2: the page offers no add/edit/delete control — the ledger is read-only "
               "except for the refund",
               c.js("!document.getElementById('addPaymentBtn')")
               and not c.js("!!document.querySelector('#%s [data-edit]')" % TABLE)
               and not c.js("!!document.querySelector('#%s [data-delete]')" % TABLE))

    flight_a, seat_a = bookable_flight()
    walk.check("2: (setup) a seeded flight with a free seat was found",
               flight_a is not None and seat_a is not None,
               "flight=%s seat=%s" % (flight_a and flight_a.get("no"), seat_a))
    if not flight_a:
        return 1
    seat_a_before = seat_status(flight_a["id"], seat_a)

    status, body = create_probe(flight_a, seat_a, PROBE_A_LAST)
    probe_a = int(body.get("bookingId") or 0)
    walk.check("2: a customer booking was created through the PUBLIC endpoint (no token)",
               status == 200 and probe_a > 0, "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("2: it is NOT on the ledger yet — the gateway has not been reached "
               "(Transactions counts transactions, not bookings)",
               total() == floor
               and search(c, str(probe_a), expect_count=0),
               "total=%d page_rows=%s" % (total(), rows(c)))

    status, body, txn_a = pay(probe_a)
    walk.check("2: the customer paid it (initiate + the mock's verify: SUCCESS)",
               status == 200, "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("2: (setup) its booking is Confirmed, Paid and ticketed",
               one(probe_a).get("status") == "Confirmed"
               and one(probe_a).get("paymentStatus") == "Paid"
               and one(probe_a).get("pnr") != "",
               json.dumps(one(probe_a))[:200])
    walk.check("2: NOW it is a transaction — found by its booking id, Paid, with its txn id",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["status"] == "Paid"
               and str(txn_a) in rows(c)[0]["txn"],
               "row=%s api=%s" % (rows(c), json.dumps(one(probe_a))[:160]))
    walk.check("2: and the ledger's own total went up by exactly one",
               total() == floor + 1, "now=%d was=%d" % (total(), floor))

    # A second transaction to refund BEHIND the page's back (the fresh-read proof), and
    # a third that is only ever INITIATED — the state the refund rule refuses.
    flight_b, seat_b = bookable_flight()
    status_b, body_b = create_probe(flight_b, seat_b, PROBE_B_LAST)
    probe_b = int(body_b.get("bookingId") or 0)
    status_b, body_b, txn_b = pay(probe_b)
    walk.check("2: (setup) a second probe is Paid too (it is the fresh-read proof)",
               status_b == 200 and one(probe_b).get("paymentStatus") == "Paid",
               "%s %s" % (status_b, json.dumps(body_b)[:120]))

    flight_c, seat_c = bookable_flight()
    status_c, body_c = create_probe(flight_c, seat_c, PROBE_C_LAST)
    probe_c = int(body_c.get("bookingId") or 0)
    status_c, body_c = initiate(probe_c, "Linked Bank Account")
    walk.check("2: (setup) a third is INITIATED only — Pending, bank-linked, no callback",
               status_c == 200 and str((body_c or {}).get("bookingId")) == str(probe_c)
               and one(probe_c).get("paymentStatus") == "Pending",
               "%s %s" % (status_c, json.dumps(body_c)[:160]))
    # Against the floor the walk recorded, not against a literal 1: the tile describes the
    # WHOLE ledger, and this is a shared live database — an abandoned attempt somebody left
    # behind is a PENDING row this walk did not make, so `pending == 1` was an assertion
    # about the database's state rather than about the walk's own three transactions.
    walk.check("2: the tiles followed the ledger (3 more transactions, 1 more pending)",
               total() == floor + 3
               and int(stats().get("pending") or 0) == int(floor_stats.get("pending") or 0) + 1,
               "total=%d pending=%s (floor pending=%s)"
               % (total(), stats().get("pending"), floor_stats.get("pending")))

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching a TXN ID finds the transaction the list showed",
               search(c, txn_a, expect_count=1, expect_name=PROBE_A_LAST), str(rows(c)))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "/api/admin/payments" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "search=" in u)[:200])
    walk.check("3: and the count line is the server's filtered total (1)",
               count_text(c) == "1 transaction", count_text(c))
    walk.check("3: the amount is the booking's own total, formatted as the page does",
               rows(c)[0]["amount"] == fmt_npr(one(probe_a).get("amount")),
               "row=%r api=%s" % (rows(c)[0]["amount"], one(probe_a).get("amount")))

    walk.check("3: searching a BOOKING ID finds it (the id the buttons carry)",
               search(c, str(probe_a), expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))
    walk.check("3: searching the PNR finds it",
               search(c, one(probe_a).get("pnr"), expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))
    walk.check("3: searching the CUSTOMER's name finds it",
               search(c, PROBE_A_LAST, expect_count=1, expect_name=PROBE_A_LAST),
               str(rows(c)))
    flight_no = one(probe_a).get("flight", {}).get("flightNo", "")
    walk.check("3: searching a FLIGHT NUMBER is a query too (the server's count agrees)",
               search(c, flight_no, expect_count=total("?search=%s" % enc(flight_no))),
               "page=%r api=%d" % (count_text(c), total("?search=%s" % enc(flight_no))))

    walk.check("3: clearing the box restores the first server page",
               search(c, "", expect_count=PAGE_SIZE), "rows=%d" % len(rows(c)))

    # The waits below are keyed on the COUNT LINE, not on the row count. The first run
    # of this walk waited for "as many rows as the filter's total" — and the unfiltered
    # page already had exactly that many (Paid = 9, one page = 8), so the wait passed
    # vacuously and the check read the table it was replacing. The count line carries the
    # server's own filtered total, so it cannot be satisfied by the previous answer.
    paid = total("?status=Paid")
    paid_text = "%d transaction%s" % (paid, "" if paid == 1 else "s")
    set_filter(c, "payStatusFilter", "Paid")
    walk.check("3: the status filter is a query (Paid rows only, server's count)",
               wait_count(c, paid_text)
               and all(r["status"] == "Paid" for r in rows(c)),
               "page=%r api=%d rows=%s" % (count_text(c), paid, rows(c)))
    walk.check("3: and its count is the server's filtered total",
               count_text(c) == paid_text and len(rows(c)) == min(PAGE_SIZE, paid),
               count_text(c))

    pending = total("?status=Pending")
    pending_text = "%d transaction%s" % (pending, "" if pending == 1 else "s")
    set_filter(c, "payStatusFilter", "Pending")
    walk.check("3: the Pending filter is the other vocabulary it accepts, and finds the "
               "transaction that is still awaiting the gateway",
               wait_count(c, pending_text)
               and all(r["status"] == "Pending" for r in rows(c))
               and str(probe_c) in present(c, "data-view"),
               "page=%r api=%d rows=%s" % (count_text(c), pending, rows(c)))
    set_filter(c, "payStatusFilter", "ALL")

    bank = total("?method=" + enc("Linked Bank Account"))
    bank_text = "%d transaction%s" % (bank, "" if bank == 1 else "s")
    set_filter(c, "payMethodFilter", "Linked Bank Account")
    walk.check("3: the method filter is a query (the bank-linked row, not an eSewa one)",
               wait_count(c, bank_text)
               and rows(c)
               and all(r["method"] == "Linked Bank Account" for r in rows(c))
               and str(probe_c) in present(c, "data-view"),
               "page=%r api=%d rows=%s" % (count_text(c), bank, rows(c)))
    set_filter(c, "payMethodFilter", "ALL")

    after_probes = total()
    walk.check("3: paging is server-side (%d transactions over %d-per-page = 2 pages)"
               % (after_probes, PAGE_SIZE),
               after_probes > PAGE_SIZE
               and c.wait_js("document.querySelectorAll('#pageBtns .page-btn').length >= 4",
                             timeout=25),
               "buttons=%s api=%d" % (c.js("document.querySelectorAll('#pageBtns .page-btn').length"),
                                      after_probes))
    walk.check("3: the page info line is the server's arithmetic",
               ("of %d transactions" % after_probes) in page_info(c), page_info(c))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: page 2 shows the remainder and says so",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, after_probes - PAGE_SIZE), timeout=25),
               "rows=%d info=%s" % (len(rows(c)), page_info(c)))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '1'; })[0];
        if (b) b.click();
    })()""")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)

    # ============================================================== 4. UPDATE
    walk.step("§12 step 4 — UPDATE (the refund is the only writable thing)")
    walk.check("4: (setup) the second probe is on screen as Paid, with a refund button",
               search(c, PROBE_B_LAST, expect_count=1, expect_name=PROBE_B_LAST)
               and rows(c)[0]["status"] == "Paid"
               and present(c, "data-refund") == [str(probe_b)],
               "row=%s refund=%s" % (rows(c), present(c, "data-refund")))

    # Change it BEHIND the page's back, then open the modal. A modal filled from the
    # table's cached row would show Paid and offer a refund the API would refuse.
    status, _ = walk.api("POST", "/api/admin/payments/%d/refund" % probe_b, auth=True)
    walk.check("4: (setup) it was refunded through the API, not the page",
               status == 200 and one(probe_b).get("paymentStatus") == "Refunded",
               "status=%s %s" % (status, one(probe_b).get("paymentStatus")))
    open_detail(c, probe_b)
    walk.check("4: the detail modal shows the FRESH status, not the cached row",
               walk.text(c, "#dPayStatus").strip() == "Refunded",
               "modal=%r table=%r" % (walk.text(c, "#dPayStatus").strip(),
                                      rows(c)[0]["status"]))
    walk.check("4: while the TABLE still shows the stale Paid it was rendered with",
               rows(c)[0]["status"] == "Paid", rows(c)[0]["status"])
    walk.check("4: and its refund button is hidden, because the server's copy says so",
               c.js("document.getElementById('dRefundBtn').hidden === true"))
    walk.check("4: the modal is titled by the transaction and carries the booking's id",
               txn_b in walk.text(c, "#payModalTitle")
               and walk.text(c, "#dBkgId").strip() == str(probe_b),
               "%r / %r" % (walk.text(c, "#payModalTitle"), walk.text(c, "#dBkgId")))
    close_detail(c)
    walk.check("4: Done closes it", not walk.modal_open(c, DETAIL))

    # The UI's write: the FIRST probe, refunded through the page.
    walk.check("4: (setup) the first probe is on screen as Paid, refund offered",
               search(c, PROBE_A_LAST, expect_count=1, expect_name=PROBE_A_LAST)
               and rows(c)[0]["status"] == "Paid"
               and present(c, "data-refund") == [str(probe_a)],
               "row=%s refund=%s" % (rows(c), present(c, "data-refund")))
    walk.click(c, '[data-refund="%s"]' % probe_a)
    walk.check("4: it asks for confirmation first, naming the transaction and the amount",
               c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM,
                         timeout=25)
               and str(txn_a) in walk.text(c, "#confirmMsg")
               and fmt_npr(one(probe_a).get("amount")) in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg")[:200])
    walk.click(c, "#confirmNo")
    walk.check("4: declining writes nothing",
               not walk.modal_open(c, CONFIRM)
               and one(probe_a).get("paymentStatus") == "Paid",
               one(probe_a).get("paymentStatus"))

    walk.click(c, '[data-refund="%s"]' % probe_a)
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
    walk.click(c, "#confirmYes")
    walk.check("4: confirming POSTs the refund and the DATABASE says Refunded",
               walk.wait_db(lambda: one(probe_a).get("paymentStatus") == "Refunded"),
               one(probe_a).get("paymentStatus"))
    walk.check("4: the list refreshed from the backend and shows the server's new status",
               c.wait_js("(function(){var td=document.querySelector('#%s tr td:nth-child(7)');"
                         "return !!td && td.textContent.trim() === 'Refunded';})()" % TABLE,
                         timeout=25)
               and not present(c, "data-refund")
               and "refunded" in walk.toast(c).lower(),
               "row=%s toast=%r" % (rows(c), walk.toast(c)))
    walk.check("4: the tiles moved with it — Refunded up, Collected down, nothing added",
               int(stats().get("transactions") or 0) == after_probes
               and tiles(c) == expected_tiles(stats()),
               "page=%s api=%s" % (tiles(c), expected_tiles(stats())))
    walk.check("4: THE PAGE WROTE NO BROWSER STORE — the storefront's key is untouched "
               "(the defect this pass removed: a refund that changed a JSON array while "
               "MySQL stayed SUCCESS)",
               c.js("localStorage.getItem(%s) === %s"
                    % (json.dumps(STORE_KEY), json.dumps(store_before))))
    walk.check("4: a refund is NOT a cancellation — the booking kept its status and seat",
               one(probe_a).get("status") == "Confirmed"
               and seat_status(flight_a["id"], seat_a) == "BOOKED",
               "%s / %s" % (one(probe_a).get("status"), seat_status(flight_a["id"], seat_a)))
    walk.note("(refund and cancel stay two decisions — the API's own note, R4's policy)")

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE (the refund IS the write; there is no delete)")
    status, body = walk.api("DELETE", "/api/admin/payments/%d" % probe_a, auth=True)
    walk.check("5: the API serves no payment delete at all (the absence, proved)",
               status in (404, 405), "%s %s" % (status, json.dumps(body)[:120]))
    walk.check("5: and the page offers none either (a payment is the gateway's record)",
               not c.js("!!document.querySelector('#%s [data-delete]')" % TABLE))

    # The rule the PAGE CANNOT REACH: a refund of a payment that never succeeded is
    # refused, and the page hides the button in exactly that state. So ask the API.
    walk.check("5: (setup) the initiated-only probe is on screen — Pending, no refund offered",
               search(c, PROBE_C_LAST, expect_count=1, expect_name=PROBE_C_LAST)
               and rows(c)[0]["status"] == "Pending"
               and not present(c, "data-refund"),
               "row=%s refund=%s" % (rows(c), present(c, "data-refund")))
    status, body = walk.api("POST", "/api/admin/payments/%d/refund" % probe_c, auth=True)
    walk.check("5: the API REFUSES it — there is no money to give back (409)",
               status == 409 and (body or {}).get("error") == "PAYMENT_NOT_REFUNDABLE",
               "%s %s" % (status, json.dumps(body)[:200]))
    walk.check("5: and the refusal is the server's own sentence, naming the state",
               "PENDING" in json.dumps(body), json.dumps(body)[:200])
    walk.note("(the page hides the refund button for a non-Paid row, so this rule is "
              "unreachable by clicking — asked directly, like the Users walk's admin lock)")

    status, body = walk.api("POST", "/api/admin/payments/%d/refund" % probe_a, auth=True)
    walk.check("5: refunding an already-refunded payment is a NO-OP, not an error "
               "(the button can be pressed twice)",
               status == 200 and (body or {}).get("paymentStatus") == "Refunded",
               "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("5: and it did not move a tile or add a transaction",
               tiles(c) == expected_tiles(stats())
               and int(stats().get("transactions") or 0) == after_probes,
               "page=%s api=%s" % (tiles(c), expected_tiles(stats())))

    # ---------------------------------------------------------------- tidy up
    walk.step("tidy up — the three probe bookings, their payments and their seats")
    walk.check("tidy: tools/bookings-probe-cleanup.sh removed the probe rows",
               tidy("cleanup"))
    walk.check("tidy: no probe booking is left anywhere",
               len(probe_bookings()) == 0,
               json.dumps([b["email"] for b in probe_bookings()])[:200])
    walk.check("tidy: the ledger is back at its seeded floor (%d transactions)" % floor,
               total() == floor, "now=%d was=%d" % (total(), floor))
    walk.check("tidy: and the four tiles are back at the database's own numbers",
               stats() == floor_stats,
               "now=%s was=%s" % (json.dumps(stats()), json.dumps(floor_stats)))
    walk.check("tidy: the seats the probes held were RELEASED",
               seat_status(flight_a["id"], seat_a) == seat_a_before,
               "%s is %s" % (seat_a, seat_status(flight_a["id"], seat_a)))

    # The two refusals this walk asks for are the server saying no — 409
    # PAYMENT_NOT_REFUNDABLE from a direct API call, and the 404/405 that proves the
    # absent payment delete. Both are the behaviour under test, not page failures.
    walk.drain(c)
    return walk.finish(expected_failures=[
        "409 %s/api/admin/payments" % BASE,
        "status of 409",
        "404 %s/api/admin/payments" % BASE,
        "405 %s/api/admin/payments" % BASE,
        "status of 404",
        "status of 405",
    ])


if __name__ == "__main__":
    sys.exit(main())
