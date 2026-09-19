#!/usr/bin/env python3
"""Live probe of the real eSewa ePay v2 flow's handoff and both callbacks.

Run it with tools/esewa-callback-probe.sh (which boots the app and cleans up after),
or by hand against an app already listening on 8081:

    python3 tools/esewa-callback-probe.py              # provisions its own booking
    python3 tools/esewa-callback-probe.py --booking 123

WHY THIS EXISTS
    The end-to-end walk (a real booking, eSewa's own login/OTP pages, a real payment)
    can only be completed by a human, because eSewa's login gates its LOGIN button
    behind a Google reCAPTCHA. On 2026-09-19 their widget reported "This site is
    exceeding reCAPTCHA Enterprise free quota", their page answered "Service is
    currently unavailable", and the walk stopped there — see requirements.md's
    Session 64 entry. Everything AFTER that point is reachable without their UI, and
    this script is how: it drives the endpoints over plain HTTP against a real
    database and, where it matters, against eSewa's real sandbox.

WHAT IT PROVES
    1. The signed handoff: the checkout page signs the booking's own amount and the
       uuid STORED ON THE PAYMENT ROW, with the field list and the order eSewa
       documents, and the signature on the page equals one computed here
       independently from the shared secret.
    2. The three refusals, none of which may change anything: no payload, a tampered
       signature, and — the one that matters most — a payload signed CORRECTLY with
       the real key that claims COMPLETE while eSewa's own ledger disagrees.
    3. The replay refusal: an earlier attempt's signed payload is dead once the
       booking re-initiates.
    4. The failure callback: an abandoned attempt is recorded as not paid, and the
       booking keeps its seat and stays Pending. A failed payment is not a cancellation.
    5. Every callback is called WITHOUT a token, on purpose — the bug class this
       guards is a signed-out customer being 401'd after their money has moved.

THE 406 ASSERTION IS PART OF THE CONTRACT
    The checkout page is `produces = text/html`, so it answers 406 to a request whose
    Accept is `application/json`. A browser navigation always sends text/html, which
    is the only thing that ever requests this page, so the behaviour is correct — but
    a JSON-based integration test or proxy would see that 406 and read it as a broken
    endpoint. Both behaviours are asserted below, so the contract is recorded rather
    than rediscovered by whoever trips over it next.

SAFETY
    Read-only except for: opening a transaction (`POST /api/payments/initiate`) and,
    on a database with no probe booking left, creating ONE cheap probe flight and one
    real PENDING booking, exactly as the walk does. That is why the runner calls
    tools/esewa-probe-cleanup.sh afterwards. Re-runs REUSE the probe booking when one
    is already there, so they add no rows.

Exits non-zero if any check fails.
"""

import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPS = ROOT / "src/main/resources/application-local.properties"

# eSewa's OWN PUBLISHED SANDBOX KEY, used only if the local properties cannot be read.
# application-local.properties is git-ignored and is where the app's key lives; a live
# merchant key belongs in the same slot, and this probe must sign with whatever the
# SERVER signs with, which is why the file is read rather than the value hard-coded.
PUBLISHED_UAT_KEY = "8gBm/:&EnhH.1/q"

PRODUCT_CODE = "EPAYTEST"
ESEWA_FORM_URL = "https://rc-epay.esewa.com.np/api/epay/main/v2/form"
ESEWA_STATUS_URL = "https://rc.esewa.com.np/api/epay/transaction/status/"

ADMIN_EMAIL, ADMIN_PASSWORD = "admin@gmail.com", "admin"

PROBE_FARE = "10.00"      # cheap on purpose: eSewa's UAT wallets hold a small balance
PROBE_SEATS = 2
PROBE_EMAIL_PREFIX = "uat-"

BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
DEFAULT_BASE = "http://127.0.0.1:8081"

# Set from the command line in main(); module-level so the helpers can see them.
SECRET = PUBLISHED_UAT_KEY
BASE = urllib.parse.urlsplit(DEFAULT_BASE)

# eSewa's documented RESPONSE field list, in their own order. The app verifies whatever
# list a payload names; this is what a real callback carries.
RESPONSE_FIELDS = ["transaction_code", "status", "total_amount", "transaction_uuid",
                   "product_code", "signed_field_names"]

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((bool(ok), name, str(detail)))
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else "   [" + str(detail)[:400] + "]"))
    sys.stdout.flush()


def note(text):
    print("     " + text)
    sys.stdout.flush()


# --------------------------------------------------------------------------
# configuration
# --------------------------------------------------------------------------
def load_secret():
    """The key the running server signs with, from the app's own local properties."""
    from_env = os.environ.get("ESEWA_SECRET_KEY")
    if from_env:
        note("signing key: ESEWA_SECRET_KEY from the environment")
        return from_env
    try:
        for line in PROPS.read_text().splitlines():
            if line.startswith("yatra.esewa.secret-key="):
                value = line.split("=", 1)[1].strip()
                if value:
                    note("signing key: %s" % PROPS.relative_to(ROOT))
                    return value
    except OSError:
        pass
    note("WARNING: no local key found — falling back to eSewa's published sandbox key")
    return PUBLISHED_UAT_KEY


# --------------------------------------------------------------------------
# the API, with the stdlib
# --------------------------------------------------------------------------
def req(method, path, body=None, token=None, accept="application/json", timeout=90):
    """One request, redirects NOT followed, so a 302's Location is readable."""
    conn = http.client.HTTPConnection(BASE.hostname, BASE.port, timeout=timeout)
    headers = {"Accept": accept}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    try:
        conn.request(method, path, data, headers)
        response = conn.getresponse()
        raw = response.read().decode("utf-8", "replace")
        return response.status, {k.lower(): v for k, v in response.getheaders()}, raw
    finally:
        conn.close()


def as_json(raw):
    try:
        return json.loads(raw)
    except Exception:
        return None


def api(method, path, body=None, token=None):
    status, _, raw = req(method, path, body, token)
    return status, as_json(raw)


def uid():
    return "%d" % (time.time() * 1000)


# --------------------------------------------------------------------------
# eSewa's signature
# --------------------------------------------------------------------------
def sign(fields, names=None):
    """HMAC-SHA256 over name=value pairs joined by commas, in the named order."""
    order = names or fields["signed_field_names"].split(",")
    message = ",".join("%s=%s" % (n, fields[n]) for n in order)
    digest = hmac.new(SECRET.encode(), message.encode(), hashlib.sha256).digest()
    return base64.b64encode(digest).decode()


def encode_payload(fields):
    return base64.b64encode(json.dumps(fields).encode()).decode()


# --------------------------------------------------------------------------
# the booking under test
# --------------------------------------------------------------------------
def find_probe_booking(token):
    """An existing PENDING probe booking, so a re-run adds no rows."""
    status, listing = api("GET", "/api/admin/bookings", token=token)
    if status != 200:
        return None
    for row in (listing or {}).get("bookings") or []:
        if str(row.get("email", "")).startswith(PROBE_EMAIL_PREFIX) \
                and row.get("status") == "Pending":
            return int(row["id"])
    return None


def create_probe_booking(token):
    """One cheap flight plus one real PENDING booking, through the real APIs."""
    _, airlines = api("GET", "/api/airlines")
    _, destinations = api("GET", "/api/destinations")
    airline_list = (airlines or {}).get("airlines") or []
    codes = [d["code"] for d in ((destinations or {}).get("destinations") or [])]
    if not airline_list or len(codes) < 2:
        check("the seeded airline/airport data is present", False,
              "airlines=%d codes=%s" % (len(airline_list), codes))
        return None, None

    flight_no = "LP " + uid()[-4:]
    status, flight = api("POST", "/api/admin/flights", {
        "no": flight_no, "airlineId": airline_list[0]["id"],
        "from": codes[0], "to": codes[1],
        "dep": "06:50", "arr": "07:35", "aircraft": "ATR 72 (UAT probe)",
        "fare": PROBE_FARE, "seats": PROBE_SEATS, "status": "Active"}, token=token)
    if status != 200:
        check("a probe flight was created", False, "%s %s" % (status, flight))
        return None, None
    check("a probe flight was created (capacity %d, fare %s)" % (PROBE_SEATS, PROBE_FARE),
          True, "%s %s->%s" % (flight_no, codes[0], codes[1]))

    stamp = uid()
    status, booking = api("POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Uat", "middleName": "Callback",
                    "lastName": "Probe", "email": "%s%s@example.com" % (PROBE_EMAIL_PREFIX, stamp),
                    "phone": "98068" + stamp[-5:], "invoiceParty": "Self"},
        "passengers": [{"title": "Mr", "firstName": "Uat", "middleName": "Callback",
                        "lastName": "Probe", "nationality": "Nepali", "type": "ADT"}],
        "flight": {"from": codes[0], "to": codes[1], "date": time.strftime("%Y-%m-%d"),
                   "depart": "06:50", "arrive": "07:35", "flightNo": flight_no,
                   "airline": "Probe", "flightClass": "E Class", "refundable": True,
                   "pricePerPassenger": float(PROBE_FARE), "passengerCount": 1,
                   "totalPrice": float(PROBE_FARE)},
        "amount": float(PROBE_FARE)})
    if status != 200:
        check("a real PENDING booking was created", False, "%s %s" % (status, booking))
        return None, None
    check("a real PENDING booking was created (holding a real seat)",
          booking.get("status") == "PENDING",
          "bookingId=%s status=%s" % (booking.get("bookingId"), booking.get("status")))
    return booking["bookingId"], flight_no


def booking_state(token, booking_id):
    status, _, raw = req("GET", "/api/admin/bookings/%d" % booking_id, token=token)
    return status, as_json(raw) or {}


def ticket_state(token, booking_id):
    status, _, raw = req("GET", "/api/admin/tickets/%d" % booking_id, token=token)
    return status, as_json(raw) or {}


# --------------------------------------------------------------------------
# the probe
# --------------------------------------------------------------------------
def main():
    global SECRET, BASE

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base", default=os.environ.get("YATRA_BASE", DEFAULT_BASE),
                        help="the running app (default %s)" % DEFAULT_BASE)
    parser.add_argument("--booking", type=int,
                        help="reuse this booking instead of provisioning/finding one")
    parser.add_argument("--keep", action="store_true",
                        help="do not print the cleanup command at the end")
    args = parser.parse_args()

    SECRET = load_secret()
    BASE = urllib.parse.urlsplit(args.base)

    print("== 0. sign in and open a transaction ==")
    status, auth = api("POST", "/api/auth/login",
                       {"loginId": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
    if status != 200 or not (auth or {}).get("token"):
        check("admin sign-in", False, "%s %s" % (status, auth))
        return 1
    token = auth["token"]
    check("admin sign-in", True, "as %s" % ADMIN_EMAIL)

    created_flight = None
    if args.booking:
        booking_id = args.booking
        check("reusing booking %d as asked" % booking_id, True)
    else:
        booking_id = find_probe_booking(token)
        if booking_id:
            check("reusing the probe booking already in the database", True,
                  "bookingId=%d (re-initiating adds no rows)" % booking_id)
        else:
            booking_id, created_flight = create_probe_booking(token)
            if not booking_id:
                return 1

    status, initiated = api("POST", "/api/payments/initiate",
                            {"bookingId": booking_id, "method": "esewa", "amount": 1})
    if status != 200:
        check("a transaction is open on booking %d" % booking_id, False,
              "%s %s" % (status, initiated))
        return 1
    check("a transaction is open on booking %d" % booking_id, True,
          "txn=%s amount=%s" % (initiated.get("paymentRef"), initiated.get("amount")))
    note("paymentRef=%s (the payment ROW's id in the mock's PAY… shape, not the uuid)"
         % initiated.get("paymentRef"))
    check("initiate points the wizard at the real handoff",
          str(initiated.get("gatewayRedirect", "")).endswith(
              "/api/payments/esewa/checkout/%d" % booking_id),
          str(initiated.get("gatewayRedirect")))

    # ---------------------------------------------------------------- 1. handoff
    print()
    print("== 1. the signed handoff page (no token — a signed-out visitor must be able "
          "to pay) ==")
    status, headers, page = req("GET", "/api/payments/esewa/checkout/%d" % booking_id,
                                accept=BROWSER_ACCEPT)
    check("checkout answers 200 HTML to a browser",
          status == 200 and "text/html" in headers.get("content-type", ""),
          "%s %s" % (status, headers.get("content-type")))

    # The contract assertion: `produces = text/html` means a JSON Accept gets 406. That
    # is correct for a page only ever reached by navigation, and it is recorded here so
    # nobody reads it as a broken endpoint later. See the module docstring.
    status, headers, raw = req("GET", "/api/payments/esewa/checkout/%d" % booking_id)
    check("checkout is 406 to a JSON Accept header, naming text/html as what it produces",
          status == 406, "%s %s" % (status, raw[:120]))
    check("checkout is not cacheable", "no-store" in headers.get("cache-control", ""),
          headers.get("cache-control"))

    fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)">', page))
    note("form fields: %s" % ", ".join(sorted(fields)))
    action = re.search(r'<form[^>]*action="([^"]+)"', page)
    action = action.group(1) if action else ""
    check("the form POSTs to eSewa's own UAT endpoint", action == ESEWA_FORM_URL, action)

    signed_names = fields.get("signed_field_names", "")
    check("the form names exactly the three signed fields, in eSewa's order",
          signed_names == "total_amount,transaction_uuid,product_code", signed_names)
    check("the form's signature is the one this machine computes from the secret",
          fields.get("signature") == sign(fields), "ours=%s form=%s" % (
              sign(fields) if fields.get("signed_field_names") else "?",
              fields.get("signature")))

    # The uuid is what the flow is keyed on, and the initiate response does not expose
    # it — it is only on the payment row and in this signed form, so read it from here
    # and then check the row really holds it.
    uuid_now = fields.get("transaction_uuid", "")
    check("the form signs a freshly minted uuid",
          bool(re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                            uuid_now)) and uuid_now != initiated.get("paymentRef"),
          uuid_now)
    status, detail = booking_state(token, booking_id)
    check("the uuid it signs is the one stored on the payment row",
          ((detail.get("payment") or {}).get("txnId")) == uuid_now,
          "row=%s form=%s" % ((detail.get("payment") or {}).get("txnId"), uuid_now))
    # Compared as money, not as strings: the form carries a string ("10.00") and the
    # initiate response a JSON number, and scale is not part of an amount — the same
    # rule the callback's own amount check uses (compareTo, never equals).
    check("the form signs the booking's own amount, never a page's",
          float(fields.get("total_amount", "nan")) == float(initiated.get("amount", -1)),
          "form=%s initiate=%s" % (fields.get("total_amount"), initiated.get("amount")))

    for key in ("success_url", "failure_url"):
        url = fields.get(key, "")
        check("%s is a path-segment URL on our own host" % key,
              url == "%s/api/payments/esewa/%s/%d" % (args.base.rstrip("/"),
                                                      key.replace("_url", ""), booking_id),
              url)
    check("neither callback URL carries a query string (eSewa appends ?data=… to it)",
          "?" not in fields.get("success_url", "") + fields.get("failure_url", ""),
          fields.get("success_url"))

    # ---------------------------------------------------------------- 2. refusals
    print()
    print("== 2. the success callback's refusals (no token, as a customer's browser) ==")
    status, headers, raw = req("GET", "/api/payments/esewa/success/%d" % booking_id)
    check("a success callback with no payload is refused (not 401, not a confirmation)",
          status == 400 and "ESEWA_PAYLOAD_MISSING" in raw, "%s %s" % (status, raw[:200]))

    forged = {"transaction_code": "0000", "status": "COMPLETE",
              "total_amount": str(initiated.get("amount")), "transaction_uuid": uuid_now,
              "product_code": PRODUCT_CODE,
              "signed_field_names": ",".join(RESPONSE_FIELDS)}
    forged["signature"] = sign(forged)
    # A customer editing the URL can rewrite any of it, including the signature.
    tampered = dict(forged, signature="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    status, _, raw = req("GET", "/api/payments/esewa/success/%d?data=%s" % (
        booking_id, urllib.parse.quote(encode_payload(tampered))))
    check("a tampered signature is refused",
          status == 400 and "ESEWA_SIGNATURE_INVALID" in raw, "%s %s" % (status, raw[:200]))

    # This one is signed correctly with the real key and still must NOT confirm: the only
    # thing that can confirm is eSewa's own ledger. It is the check that keeps the
    # signature from being the only lock on the door.
    status, headers, raw = req("GET", "/api/payments/esewa/success/%d?data=%s" % (
        booking_id, urllib.parse.quote(encode_payload(forged))))
    check("a correctly-signed COMPLETE payload eSewa's ledger does not back is NOT a "
          "confirmation",
          status != 302 or "payment=success" not in headers.get("location", ""),
          "%s %s %s" % (status, headers.get("location", ""), raw[:200]))
    note("the app answered HTTP %s: %s"
         % (status, (raw or headers.get("location", ""))[:180]))

    status, detail = booking_state(token, booking_id)
    check("the booking is still Pending after three refused callbacks",
          detail.get("status") == "Pending", str(detail.get("status")))
    status, ticket = ticket_state(token, booking_id)
    check("and no ticket exists", status != 200, "ticket read %s" % status)

    # What eSewa's OWN ledger says about the transaction we just tried to confirm, read
    # directly from them: the second lock, and the only thing that can ever say "paid".
    url = (ESEWA_STATUS_URL + "?product_code=%s&total_amount=%s&transaction_uuid=%s"
           % (PRODUCT_CODE, urllib.parse.quote(fields["total_amount"]),
              urllib.parse.quote(uuid_now)))
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            body = response.read().decode()
            note("eSewa's own status API, for the uuid we signed: HTTP %s %s"
                 % (response.status, body[:200]))
            check("eSewa's live ledger does not report our sandbox transaction COMPLETE "
                  "(which is why the forged COMPLETE claim above was refused)",
                  "COMPLETE" not in body.upper(), body[:200])
    except Exception as ex:
        note("eSewa's own status API could not be read from here: %s" % ex)

    # ---------------------------------------------------------------- 3. replay
    print()
    print("== 3. a replayed callback from the previous attempt ==")
    status, _ = api("POST", "/api/payments/initiate",
                    {"bookingId": booking_id, "method": "esewa", "amount": 1}, token=token)
    check("re-initiating the same booking is allowed", status == 200, str(status))
    status, _, page = req("GET", "/api/payments/esewa/checkout/%d" % booking_id,
                          accept=BROWSER_ACCEPT)
    fields_now = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)">',
                                 page))
    check("re-initiating mints a NEW uuid for the same booking",
          fields_now.get("transaction_uuid") not in ("", uuid_now),
          "old=%s new=%s" % (uuid_now, fields_now.get("transaction_uuid")))
    status, detail = booking_state(token, booking_id)
    check("the row now holds the new uuid, and the old one is dead",
          ((detail.get("payment") or {}).get("txnId")) == fields_now.get("transaction_uuid"),
          "row=%s" % ((detail.get("payment") or {}).get("txnId")))

    status, headers, raw = req("GET", "/api/payments/esewa/success/%d?data=%s" % (
        booking_id, urllib.parse.quote(encode_payload(forged))))
    check("the old attempt's signed payload is refused as no longer current",
          status == 409 and "ESEWA_TRANSACTION_NOT_CURRENT" in raw,
          "%s %s" % (status, raw[:200]))

    # ---------------------------------------------------------------- 4. failure
    print()
    print("== 4. the failure callback ==")
    status, headers, raw = req("GET", "/api/payments/esewa/failure/%d" % booking_id)
    check("failure answers a redirect to the payment page, naming the failure",
          status == 302
          and headers.get("location") == "/payment.html?bookingId=%d&payment=failed"
          % booking_id, "%s %s" % (status, headers.get("location")))
    check("the redirect is not cacheable", "no-store" in headers.get("cache-control", ""),
          headers.get("cache-control"))

    status, detail = booking_state(token, booking_id)
    payment = detail.get("payment") or {}
    print("  booking status=%s paymentStatus=%s payment=%s/%s"
          % (detail.get("status"), detail.get("paymentStatus"),
             payment.get("method"), payment.get("txnId")))
    check("the abandoned attempt is recorded as not paid",
          str(detail.get("paymentStatus")).lower() not in ("paid", ""),
          str(detail.get("paymentStatus")))
    check("but the booking itself is untouched — a failed attempt is not a cancellation",
          detail.get("status") == "Pending", str(detail.get("status")))
    status, ticket = ticket_state(token, booking_id)
    check("and still no ticket", status != 200, "ticket read %s" % status)

    # ---------------------------------------------------------------- summary
    failed = [r for r in RESULTS if not r[0]]
    print()
    print("%d passed, %d failed" % (len(RESULTS) - len(failed), len(failed)))
    for _, name, detail_text in failed:
        print("  - %s   [%s]" % (name, str(detail_text)[:200]))

    print()
    print("booking under test: %d%s"
          % (booking_id, " (created by this run%s)" % (", flight %s" % created_flight
                                                       if created_flight else "")
             if created_flight else ""))
    if created_flight and not args.keep:
        print("rows were added — remove them with:  bash tools/esewa-probe-cleanup.sh --apply")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
