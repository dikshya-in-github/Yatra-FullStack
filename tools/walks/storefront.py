#!/usr/bin/env python3
"""§10 — the storefront's booking flow, walked end to end on the real API.

This is the first walk in `walks/` that is **not** an admin module. §12/§13's five
steps are about a CRUD page; the storefront is a four-page handoff that ends at
somebody else's website, so the steps are read here as:

  * **OPEN/READ** — `searchFlight.html` renders the flights the API lists, for the
    date it asked about, with the airlines and prices of those rows.
  * **CREATE** — selecting a card writes a real flight number into the wizard's
    session, `booking.html` POSTs the booking, and the row and its **seat hold** are
    read back from the database, never from the page's own toast.
  * **the gateway** — `payment.html` initiates the payment and the browser lands on
    the server's own signed handoff to eSewa.
  * **the absences** — there is no customer booking delete and no booking update,
    so the walk proves they are not there, and proves the refusal that used to be
    invisible: a booking for a flight number that is not a row is a 404.

Four things it tests that no API test can, and one that no API test would think to:

  * **The mock's central lie is gone.** Until §10 the storefront's search answered
    from `mock-data.js`, which *invented* every flight number from the date
    (`al.iata + " " + (951 + i * 7 + dow)`), so the flight a customer selected was
    not a table row — and `POST /api/bookings` resolves the flight by number. The
    cards on screen are compared against the API's own list, and the walk then books
    one of them: that is the operation that would have 404'd.
  * **The class pill's price is the amount stored.** The page quotes six fare
    classes; the server used to price every one of them at the base fare. The walk
    takes Y Class and asserts the card, the Confirm modal, the booking sidebar, the
    payment page and the stored total all carry the same figure.
  * **The handoff is the server's, and it is not followed.** The end of this walk is
    a signed form POSTing to eSewa's hosted page. It is asserted from the served
    HTML (the action, the signature, the signed field list, the amount) and the
    browser's auto-submit is stubbed on that one path, so the walk never sends a real
    payment to the sandbox gateway — an automated run has no business moving money,
    and the sandbox credentials are a human's to type.
  * **A date change re-queries.** The strip is ±3 days around the selected one, so
    three of its seven buttons are in the past and can never hold a flight; the walk
    changes the date and asserts the list that comes back is the API's answer for
    *that* day, then changes it back.

**It needs a seed from today**, and that is a property of the data, not the walk: the
demo seed dates its flights relative to the day it runs (`daysFromToday`), so a
database seeded yesterday has no flights for today and the storefront correctly shows
an empty list. Run `YATRA_RESEED=1 tools/module-check.sh walks/storefront.py` to have
the walk perform the demo's own reset-then-seed first (about five minutes against the
remote demo database), or seed the demo yourself and run it plainly.

Its probe is an ordinary probe booking (`zz.probe.<tag>@example.com`, `Zzprobe...`
name), so `tools/bookings-probe-cleanup.sh` removes it, its payment row and its seat
hold — the same script the Bookings, Payments and Tickets walks use.

Run it with `tools/module-check.sh walks/storefront.py`.
"""

import json
import os
import re
import subprocess
import sys
import time
from urllib.parse import quote

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, ROOT, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
ORIGIN = "KTM"
DESTINATION = "PKR"
EMAIL = "zz.probe.%s@example.com" % TAG
# The cleanup script's marker is `contact_name LIKE '%Zzprobe%'` — the literal
# substring, because the service builds `booking.contact_name` as
# "Mr Zz Zzprobestore123456" and a bare "Zz Probe%" scope matches nothing at all.
LAST_NAME = "Zzprobestore%s" % TAG
CUSTOMER_EMAIL = "anju.karki@example.com"
CUSTOMER_PASSWORD = "Yatra@123"
TODAY = time.strftime("%Y-%m-%d")
TOMORROW = time.strftime("%Y-%m-%d", time.localtime(time.time() + 86400))

# Empty on purpose: every refusal this walk asks for is asked through the API, in
# python, where the harness's browser-side failure trap cannot see it. Nothing the
# pages do here is expected to fail.
EXPECTED = []

walk = Walk("storefront")
walk.assert_serving_working_tree(
    assets=["assets/js/searchFlight.js", "assets/js/booking.js",
            "assets/js/payment.js", "assets/js/config.js"],
    markers=[("searchFlight.html", "emptyHint"),
             ("booking.html", "toast.js"),
             ("payment.html", "toast.js")])

if not walk.login_admin():
    print("admin sign-in failed — cannot read the tables back")
    sys.exit(1)


# --------------------------------------------------------------------------- #
#  helpers                                                                   #
# --------------------------------------------------------------------------- #
def search_flights(origin, destination, date):
    """The storefront's own read, unauthenticated — which is the point of it."""
    _, body = walk.api("GET", "/api/flights/search?origin=%s&destination=%s&date=%s&passengers=1"
                       % (quote(origin), quote(destination), quote(date)))
    return (body or {}).get("flights") or []


def booking_row(booking_id):
    _, body = walk.api("GET", "/api/admin/bookings/%s" % booking_id, auth=True)
    return body or {}


def seats(flight_id):
    _, body = walk.api("GET", "/api/flights/%d/seats" % flight_id)
    return body or {}


def card_numbers(c):
    """The flight numbers on the cards, in order — read from the expandable block,
    which `cardHTML` builds whether or not the card is open."""
    raw = c.js("JSON.stringify(Array.from(document.querySelectorAll('.flight-expand .tl-flight'))"
               ".map(function (e) { return e.textContent.split('\\u00b7')[0].trim(); }))")
    return json.loads(raw or "[]")


def wait_for_number(c, flight_no, timeout=30):
    """A CONTENT-keyed wait. A count would already be satisfied by the previous day's
    cards, which is the trap this harness exists to avoid repeating."""
    return c.wait_js("Array.from(document.querySelectorAll('.flight-expand .tl-flight'))"
                     ".some(function (e) { return e.textContent.indexOf(%s) !== -1; })"
                     % json.dumps(flight_no), timeout=timeout)


def reseed_if_asked():
    """`YATRA_RESEED=1` runs the demo's own restore, because the seed is date-anchored."""
    if os.environ.get("YATRA_RESEED") != "1":
        return
    walk.note("YATRA_RESEED=1 — running the demo's own reset then seed (about five minutes)")
    status, _ = walk.api("POST", "/api/admin/reset", {}, auth=True)
    walk.check("setup: POST /api/admin/reset answered 200", status == 200, status)
    status, body = walk.api("POST", "/api/admin/seed", {}, auth=True)
    walk.check("setup: POST /api/admin/seed answered 200", status == 200, body)


# --------------------------------------------------------------------------- #
#  step 0 — the data this walk needs                                         #
# --------------------------------------------------------------------------- #
walk.step("step 0 — the storefront's week exists (a seed from today)")
today_flights = search_flights(ORIGIN, DESTINATION, TODAY)
if not today_flights:
    reseed_if_asked()
    today_flights = search_flights(ORIGIN, DESTINATION, TODAY)

walk.check("the demo database has flights on KTM–PKR today", bool(today_flights),
           "nothing for %s — re-run with YATRA_RESEED=1, or seed the demo first" % TODAY)
if not today_flights:
    sys.exit(walk.finish(expected_failures=EXPECTED))

tomorrow_flights = search_flights(ORIGIN, DESTINATION, TOMORROW)
walk.check("and on the next day too, so a date change has something to show",
           bool(tomorrow_flights), "nothing for %s" % TOMORROW)

# Sweep at BOTH ends, like the Tickets walk: a probe left by an interrupted earlier
# run holds a seat on a seeded flight, and the seat-release check at the end compares
# the flight's booked count with the one this walk read — so starting from a clean
# floor is what makes that comparison mean anything. (It is also how the first run of
# this walk failed: a leftover probe from the diagnostic made the release look like a
# two-seat drop.)
start_cleanup = subprocess.run([str(ROOT / "tools" / "bookings-probe-cleanup.sh"), "--apply"],
                               capture_output=True, text=True)
walk.check("setup: any probe booking left by an earlier run is cleared first",
           start_cleanup.returncode == 0, (start_cleanup.stderr or "")[-200:])

by_no = {f["flightNo"]: f for f in today_flights}
cheapest = min(today_flights, key=lambda f: float(f["baseFare"]))
y_class = next(o for o in cheapest["fareOptions"] if o["label"] == "Y Class")
y_price = "{:,.2f}".format(float(y_class["price"]))
walk.note("%d flight(s) today: %s" % (len(today_flights), ", ".join(by_no)))

# The heading is built from the destination rows' city names, so the walk needs them
# to know what the page should say.
_, dest_body = walk.api("GET", "/api/destinations?size=100")
cities = {d["code"]: str(d["city"]).upper() for d in (dest_body or {}).get("destinations", [])}

c = walk.browser()

# The checkout page signs the transaction and POSTs itself to eSewa. Stubbing the
# form submit on that ONE path is what proves the page tries to hand off, without the
# walk sending a real payment to the sandbox.
c.cmd("Page.addScriptToEvaluateOnNewDocument", {"source": """
(function () {
  if (location.pathname.indexOf('/api/payments/esewa/checkout/') !== 0) return;
  window.__autoSubmittedTo = null;
  window.__autoSubmittedForm = null;
  HTMLFormElement.prototype.submit = function () {
    window.__autoSubmittedTo = this.action;
    window.__autoSubmittedForm = this.id || '';
  };
})()
"""})

# The customer session. YatraAuth reads exactly these two keys and login.html writes
# them after POST /api/auth/login, so the wizard sees a signed-in customer — bought
# through the real endpoint rather than invented.
status, session = walk.api("POST", "/api/auth/login",
                           {"loginId": CUSTOMER_EMAIL, "password": CUSTOMER_PASSWORD})
walk.check("setup: a customer signed in against the real API", status == 200 and bool(session))

# --------------------------------------------------------------------------- #
#  step 1 — the search page reads the real API                                #
# --------------------------------------------------------------------------- #
walk.step("step 1 — the search page lists the API's flights (READ)")
search_url = "%s/searchFlight.html?from=%s&to=%s&date=%s&pax=1" % (BASE, ORIGIN, DESTINATION, TODAY)
c.goto(search_url)
c.js("sessionStorage.setItem('yatra_auth_token', %s); sessionStorage.setItem('yatra_auth_user', %s);"
     % (json.dumps((session or {}).get("token") or ""),
        json.dumps(json.dumps((session or {}).get("user")))))
c.goto(search_url)

# The cards first, THEN the resource log: reading `performance` straight after
# `goto` races the fetch and reports an empty list while the page is perfectly
# correct — which is how this check failed the first time it ran.
walk.check("the cards for that day appear", wait_for_number(c, today_flights[-1]["flightNo"]))
walk.check("the page asked the real API for this route and date",
           any("/api/flights/search" in url for url in walk.resources(c)),
           [u for u in walk.resources(c) if "api/" in u][:3])

numbers = card_numbers(c)
walk.check("one card per flight the API returned",
           len(numbers) == len(today_flights), "%s vs %d" % (numbers, len(today_flights)))
walk.check("the flight numbers on screen are the API's own",
           numbers == [f["flightNo"] for f in today_flights], numbers)

heading = str(c.js("document.getElementById('resultsTitle').textContent")).strip()
walk.check("the results heading names the real cities from the destination rows",
           heading == "Select your preferred flight from %s to %s"
           % (cities.get(ORIGIN, ORIGIN), cities.get(DESTINATION, DESTINATION)),
           heading)

card_prices = json.loads(c.js("JSON.stringify(Array.from(document.querySelectorAll('.fc-price strong'))"
                              ".map(function (e) { return e.textContent; }))") or "[]")
walk.check("the first card's price is that row's own fare",
           card_prices and "{:,.2f}".format(float(today_flights[0]["baseFare"])) in card_prices[0],
           card_prices[:2])
# Ties are real — today's schedule has two flights at the same cheapest fare — so the
# badge count is the number of rows tied at the minimum, not one. Asserting `1` here
# failed against a correct API.
tied = [f for f in today_flights if float(f["baseFare"]) == float(cheapest["baseFare"])]
walk.check("every flight tied at the cheapest fare carries the Low fare badge, and only those",
           c.js("document.querySelectorAll('.badge-low').length") == len(tied)
           and len(tied) >= 1,
           "%s badge(s) vs %d tied" % (c.js("document.querySelectorAll('.badge-low').length"), len(tied)))
walk.check("no strike-through price anywhere — the mock's invented 'was' price is gone",
           not c.js("!!document.querySelector('.fc-price s')"))

# The empty state, on a route that really has nothing: honest copy, and no lie that
# blames the two airports the customer picked.
c.goto("%s/searchFlight.html?from=%s&to=BDP&date=%s&pax=1" % (BASE, ORIGIN, TODAY))
walk.check("a route with no flights shows the empty state",
           c.wait_js("document.getElementById('emptyState').hidden === false", timeout=25))
walk.check("and its hint names the date rather than blaming the airports",
           "No flights on this route for" in
           str(c.js("document.getElementById('emptyHint').textContent")))

# --------------------------------------------------------------------------- #
#  step 2 — a date change re-queries                                          #
# --------------------------------------------------------------------------- #
walk.step("step 2 — the date strip re-queries the API")
c.goto(search_url)
c.wait_js("document.querySelectorAll('#dateStrip button').length === 7", timeout=20)
walk.check("the strip draws seven days around the selected one",
           c.js("document.querySelectorAll('#dateStrip button').length") == 7)

# The strip is built over i = -3..+3 with the selected day at index 3, so the button
# at index 4 is tomorrow. `nth-child` is 1-based: the first attempt clicked
# `:nth-child(4)`, which is today — it re-selected the day already showing and the
# wait below could only fail.
today_label = str(c.js("document.querySelectorAll('#dateStrip button')[3].textContent")).strip()
walk.click(c, "#dateStrip button:nth-child(5)")
walk.check("changing the date re-queries and shows that day's flights",
           wait_for_number(c, tomorrow_flights[-1]["flightNo"]))
walk.check("the cards now match tomorrow's API answer, not today's",
           card_numbers(c) == [f["flightNo"] for f in tomorrow_flights], card_numbers(c))
# The strip RE-RENDERS around the new day — the selected button is always the 4th,
# whatever day it names — so "the selection moved" is asserted as the invariant plus
# a changed label, not as a fixed index. (The first draft asserted index 4 was
# selected, which is only true before any change is made.)
selected_button = json.loads(c.js(
    "JSON.stringify({index: Array.from(document.querySelectorAll('#dateStrip button'))"
    ".findIndex(function (b) { return b.classList.contains('selected'); }),"
    "label: (document.querySelector('#dateStrip button.selected') || {}).textContent})") or "{}")
walk.check("and the strip re-drew around it, naming a different day",
           selected_button.get("index") == 3
           and str(selected_button.get("label")).strip() != today_label,
           (selected_button, today_label))

# Back to today by loading the page again rather than by guessing which button is
# today's after the strip re-centred itself — the date-change behaviour is already
# proven above, and step 3 needs a known day.
c.goto(search_url)
walk.check("and the page reloads on today's flights", wait_for_number(c, today_flights[-1]["flightNo"]))

# --------------------------------------------------------------------------- #
#  step 3 — selecting carries a REAL flight number into the wizard           #
# --------------------------------------------------------------------------- #
walk.step("step 3 — selecting a flight carries a real number and a real price")
# The card the walk drives is the cheapest row's, found by index rather than assumed
# to be the first: the cards come back in departure order and the cheapest fare is
# only first when the schedule happens to be sorted that way.
card_index = today_flights.index(cheapest) + 1
card = ".flight-card:nth-child(%d)" % card_index
walk.click(c, card + " .flight-row")
walk.click(c, card + " .pill[data-class-idx='5']")   # Y Class
walk.wait_content(c, card + " .fc-price strong", y_price)
walk.check("the Y Class pill reprices the card to the API's own figure",
           y_price in str(c.js("document.querySelector('%s .fc-price strong').textContent" % card)),
           c.js("document.querySelector('%s .fc-price strong').textContent" % card))
walk.check("and the card's fare tag follows the class's own rule",
           "Refundable" in str(c.js("document.querySelector('%s .fc-price .refund-word')"
                                    ".textContent" % card)))

walk.click(c, card + " .btn-select")
confirm_text = str(c.js("document.getElementById('confirmBody').textContent"))
walk.check("Confirm Flight opens on the same flight",
           c.wait_js("document.getElementById('confirmModal').classList.contains('show')", timeout=20)
           and cheapest["flightNo"] in confirm_text, confirm_text[:120])
walk.check("and quotes the same Y Class figure as the pill", y_price in confirm_text)
walk.check("without claiming a discount that does not exist",
           "NPR " + y_price in confirm_text and "<s>" not in str(
               c.js("document.getElementById('confirmBody').innerHTML")))

walk.click(c, "#confirmProceed")
walk.check("proceeding lands on booking.html", bool(c.wait_url("booking.html", timeout=25)),
           c.js("location.href"))

selected = json.loads(c.js("sessionStorage.getItem('yatra_selected_flight')") or "{}")
walk.check("the session holds a flight number that is a real row",
           selected.get("flightNo") in by_no, selected.get("flightNo"))
walk.check("and the class the pill was showing", selected.get("flightClass") == "Y Class",
           selected.get("flightClass"))
walk.check("and the cities the search response named, for the pages that print the route",
           bool(selected.get("fromCity")) and bool(selected.get("toCity")),
           [selected.get("fromCity"), selected.get("toCity")])

# --------------------------------------------------------------------------- #
#  step 4 — booking.html creates the row and holds the seat                   #
# --------------------------------------------------------------------------- #
walk.step("step 4 — booking.html POSTs the booking (CREATE)")
flight = by_no[selected["flightNo"]]
booked_before = seats(flight["id"]).get("bookedSeats")

route_text = str(c.js("document.getElementById('sbRoute').textContent"))
walk.check("the sidebar names the route with the real city names",
           ORIGIN in route_text and selected["fromCity"] in route_text, route_text)
walk.check("the sidebar total is the class price, not the base fare",
           y_price in str(c.js("document.getElementById('sbPrice').textContent")),
           c.js("document.getElementById('sbPrice').textContent"))
walk.check("and the fare tag follows the class's own rule",
           str(c.js("document.getElementById('sbTag').textContent")).strip() == "Refundable",
           c.js("document.getElementById('sbTag').textContent"))

walk.fill(c, {
    "contactTitle": "Mr", "contactFirstName": "Zz", "contactLastName": LAST_NAME,
    "contactMiddleName": "", "contactPhone": "9800000099", "contactEmail": EMAIL,
    "p1Title": "Mr", "p1FirstName": "Zz", "p1LastName": LAST_NAME,
    "p1MiddleName": "", "p1Nationality": "Nepali",
})
c.js("document.getElementById('termsCheck').checked = true")
walk.click(c, "#continueBtn")
walk.check("submitting creates the booking and hands off to payment.html",
           bool(c.wait_url("payment.html", timeout=45)), c.js("location.href"))

booking_data = json.loads(c.js("sessionStorage.getItem('bookingData')") or "{}")
booking_id = booking_data.get("bookingId")
walk.check("the page carries the id the API minted, not a placeholder", bool(booking_id),
           booking_data.get("bookingId"))
booking = booking_row(booking_id) if booking_id else {}

# `status` here is the ADMIN RESPONSE's display spelling ("Pending", title-cased,
# from AdminBookingResponse.displayStatus) — not the column's PENDING. The walk reads
# the vocabulary of the surface it asked.
walk.check("the database has the booking, Pending and unpaid",
           booking.get("status") == "Pending" and booking.get("paymentStatus") == "Pending",
           (booking.get("status"), booking.get("paymentStatus")))
walk.check("stored against the probe's own contact block",
           str(booking.get("email", "")).lower() == EMAIL.lower(), booking.get("email"))
walk.check("with the class's own price stored on it (quote == charge)",
           booking.get("amount") is not None
           and abs(float(booking["amount"]) - float(y_class["price"])) < 0.01,
           "%s vs %s" % (booking.get("amount"), y_class["price"]))

booked_after = seats(flight["id"]).get("bookedSeats")
walk.check("and exactly one seat on that flight is now held",
           booked_before is not None and booked_after == booked_before + 1,
           "%s -> %s" % (booked_before, booked_after))

# --------------------------------------------------------------------------- #
#  step 5 — payment.html hands off to the server's signed eSewa form          #
# --------------------------------------------------------------------------- #
walk.step("step 5 — the payment hands off to the real gateway (the server's form)")
walk.check("the payment page shows the booking the API minted",
           str(booking_id) in str(c.js("document.getElementById('bookingCode').textContent")),
           c.js("document.getElementById('bookingCode').textContent"))
walk.check("and the same route and total as the step before it",
           ORIGIN in str(c.js("document.getElementById('sbRoute').textContent"))
           and y_price in str(c.js("document.getElementById('sbPrice').textContent")),
           (c.js("document.getElementById('sbRoute').textContent"),
            c.js("document.getElementById('sbPrice').textContent")))

walk.click(c, "#continueBtn")
walk.check("Continue sends the browser to the SERVER's checkout path",
           bool(c.wait_url("/api/payments/esewa/checkout/", timeout=45)), c.js("location.href"))
walk.check("which is that booking's own handoff",
           "/api/payments/esewa/checkout/%s" % booking_id in str(c.js("location.pathname")),
           c.js("location.pathname"))

# The served HTML, read straight from the API: the exact bytes the browser got, with
# no race against the page's own auto-submit and without following it.
status, content_type, page = walk.fetch("/api/payments/esewa/checkout/%s" % booking_id)
page = page.decode("utf-8", "replace") if isinstance(page, bytes) else str(page)
walk.check("the checkout answers HTML for that booking",
           status == 200 and "text/html" in content_type.lower(), (status, content_type))
walk.check("its form POSTs to eSewa's own hosted page, not to a page of ours",
           'action="https://rc-epay.esewa.com.np/api/epay/main/v2/form"' in page)
walk.check("with the EPAYTEST product code this environment is configured for",
           'name="product_code" value="EPAYTEST"' in page)
walk.check("the signed field list and the signature are both on it",
           'name="signed_field_names"' in page and 'name="signature"' in page
           and 'name="transaction_uuid"' in page)
walk.check("the signed total is the amount the booking stored",
           re.search(r'name="total_amount" value="%s"' % re.escape("%.1f" % float(booking["amount"])),
                     page) is not None
           or re.search(r'name="total_amount" value="%s"' % re.escape("%.2f" % float(booking["amount"])),
                        page) is not None,
           re.findall(r'name="total_amount" value="([^"]*)"', page)[:1])
walk.check("and the page really tries to submit itself to that host",
           str(c.js("window.__autoSubmittedTo")) ==
           "https://rc-epay.esewa.com.np/api/epay/main/v2/form",
           c.js("window.__autoSubmittedTo"))

# The payments list answers under `bookings` — the module serves AdminBookingResponse
# rows (a payment is 1:1 with its booking), so the key is the booking shape's, not the
# route's name.
ledger = walk.get_list("/api/admin/payments", "bookings", "search=%s" % booking_id)
walk.check("the initiate call left a Pending row on the ledger for this booking",
           any(str(p.get("id")) == str(booking_id) and p.get("paymentStatus") == "Pending"
               for p in ledger),
           [(p.get("id"), p.get("paymentStatus")) for p in ledger][:4])

# --------------------------------------------------------------------------- #
#  step 6 — the absences, and the refusal that used to be invisible          #
# --------------------------------------------------------------------------- #
walk.step("step 6 — what the API deliberately does not do")


def post_booking(flight_field):
    return walk.api("POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Zz", "lastName": LAST_NAME,
                    "email": EMAIL, "phone": "9800000099"},
        "passengers": [{"title": "Mr", "firstName": "Zz", "lastName": LAST_NAME,
                        "nationality": "Nepali", "type": "ADT"}],
        "flight": flight_field,
        "amount": 1,
    })


status, _ = post_booking({"flightNo": "ZZ 9999", "from": ORIGIN, "to": DESTINATION})
walk.check("a flight number that is not a row is refused — the mock's whole defect",
           status == 404, status)

status, _ = post_booking({"flightNo": selected["flightNo"], "from": ORIGIN, "to": DESTINATION,
                          "flightClass": "Z Class"})
walk.check("a fare class this server does not sell is refused", status == 400, status)

# Asked WITH an admin token on purpose: unauthenticated, every /api/** route answers
# 401 first, which would prove the auth wall rather than the absence of the route.
status, _ = walk.api("DELETE", "/api/bookings/%s" % booking_id, auth=True)
walk.check("there is no booking delete, even for an admin", status in (404, 405), status)
walk.check("and the booking is still there afterwards",
           booking_row(booking_id).get("status") == "Pending")

status, empty = walk.api("GET", "/api/flights/search?origin=KTM&destination=TMI&date=%s" % TODAY)
walk.check("a route with nothing on it is an empty list, not an error",
           status == 200 and (empty or {}).get("flights") == [],
           (status, (empty or {}).get("flights"))[:2])

walk.drain(c)

# --------------------------------------------------------------------------- #
#  tidy up — the probe booking, its payment row and its seat hold             #
# --------------------------------------------------------------------------- #
walk.step("tidy up — the probe booking and the seat it holds")
walk.note("cleanup: tools/bookings-probe-cleanup.sh --apply")
result = subprocess.run([str(ROOT / "tools" / "bookings-probe-cleanup.sh"), "--apply"],
                        capture_output=True, text=True)
walk.check("tidy: the cleanup script removed the probe rows", result.returncode == 0,
           (result.stderr or result.stdout)[-300:])
walk.check("tidy: it verified 0 probe bookings and 0 orphans",
           "ok: 0 probe bookings" in (result.stdout or ""),
           (result.stdout or "")[-200:])

status, gone = walk.api("GET", "/api/admin/bookings/%s" % booking_id, auth=True)
walk.check("tidy: the booking is gone from the database", status == 404, status)

released = seats(flight["id"]).get("bookedSeats")
walk.check("tidy: and the seat it held was released", released == booked_before,
           "%s -> %s" % (booked_after, released))

sys.exit(walk.finish(expected_failures=EXPECTED))
