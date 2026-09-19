#!/usr/bin/env python3
"""§12/§13 — every admin page's dialogs — and both profile pages' password forms —
audited in the state a USER first meets them.

One sentence from the admin-side report is the whole reason this file exists:

    "In almost every admin page the add/edit form opens by itself when the page is
     loaded, and its Close / Keep it / Confirm / Done buttons do nothing."

That was ONE defect, not seven. `admin.css` declared

    .modal-overlay { display: flex; }

and the UA stylesheet's `[hidden] { display: none }` loses to it on specificity, so
every `.modal-overlay[hidden]` in the markup painted itself open on load and
`el.hidden = true` could never visibly close it again. The same rule shape hit
`.a-btn` (`display: inline-flex`, so "Remove logo" and "Refund" showed before a row
needed them), `.pw-meter` and `.form-hint.rule`.

The seven module walks reported every module closed because of TWO blind spots, and
this walk is those two blind spots turned into assertions:

  1. **They only ever ask the DOM `hidden === false`.** `Walk.modal_open()` reads the
     property, and the property was always correct — the CSS is what lied. So this
     walk asks `getComputedStyle` + `getBoundingClientRect`: is the thing actually
     paint-visible to a person? (`.visually-hidden` and `opacity: 0` are still
     treated as not-visible, so `#sidebarBackdrop` does not false-positive.)
  2. **They only ever see a dialog after clicking its trigger.** The page-load state
     — the state the bug lived in — was never observed. So every page is audited on a
     FRESH navigation first, and nothing carrying `hidden` may be visible there.

`[hidden]` is enumerated rather than a list of modal ids on purpose: that one query
covers the dialogs, the conditionally-shown buttons, the password meter and the empty
states at once, on every page, which is exactly the breadth of the defect.

`--allow-stale` skips the byte-for-byte working-tree guard so this can be pointed at a
build that is deliberately BEHIND the tree. That is how the report's modal and
dropdown claims were separated from a stale-build artifact: run it against the running
app first (the defects reproduce), then against a fresh `tools/module-check.sh` build
(they do not).

The storefront's `profile.html` is audited here too, because it is the customer-side
twin of `admin-profile.html` and shares two of the three defects: the same
`Change Password` form, and the same CSS-vs-`hidden` shape in its own stylesheet
(`profile.css` also had a `display: flex` rule beating `[hidden]`, so the strength
meter painted itself open with its empty "—" state). It is one page in one audit
rather than a second walk for one page. It is audited LAST and needs its own session:
`auth.js`'s guard reads the customer keys, and `/api/users/me` resolves the caller
from the JWT, so the walk signs in as the seeded customer — replacing the admin token
the pages above were using, which is exactly why nothing may follow it.

Read-only. It creates no rows, so there is nothing to sweep; the one write it does
attempt is a delete the API is KNOWN to refuse (409 `DESTINATION_HAS_FLIGHTS`, a
destination a flight route still uses), which is what lets it click "Confirm" for real
without touching the demo data.

Run it with `tools/module-check.sh walks/admin-modals.py`, or against an app that is
already up with
`YATRA_BASE=http://127.0.0.1:8080 python3 tools/walks/admin-modals.py --allow-stale`.
"""

import json
import os
import sys

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, Walk  # noqa: E402


# profile.html needs a CUSTOMER session: auth.js's guard reads its own two keys, and
# /api/users/me resolves the caller from the JWT — so this is the seeded customer, the
# same account storefront.py signs in as.
CUSTOMER_EMAIL = os.environ.get("YATRA_CUSTOMER_EMAIL", "anju.karki@example.com")
CUSTOMER_PASSWORD = os.environ.get("YATRA_CUSTOMER_PASSWORD", "Yatra@123")


# ---------------------------------------------------------------- the primitives
# "Visible" as a HUMAN means it, not as `hidden` claims: the whole defect was markup
# saying hidden while CSS painted it. opacity:0 and clip-based .visually-hidden count
# as invisible, so the sidebar backdrop and the file inputs are not false positives.
VISIBLE = """(function () {
  var el = document.querySelector(%s);
  if (!el) return null;
  var cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
  var r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
})()"""

NOT_VISIBLE = """(function () {
  var el = document.querySelector(%s);
  if (!el) return false;
  var cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return true;
  var r = el.getBoundingClientRect();
  return r.width === 0 || r.height === 0;
})()"""

# Every element that SAYS it is hidden, that a person can still see. One query, and
# it is the defect's whole surface: modals, .a-btn (inline-flex), .pw-meter,
# .form-hint.rule, the empty states.
STILL_VISIBLE_HIDDEN = """JSON.stringify(
  Array.from(document.querySelectorAll('[hidden]')).filter(function (el) {
    var cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }).map(function (el) {
    var id = el.id ? '#' + el.id : '';
    var cls = (typeof el.className === 'string' && el.className.trim())
      ? '.' + el.className.trim().split(/\\s+/).join('.') : '';
    return '<' + el.tagName.toLowerCase() + '>' + id + cls;
  }))"""


def has_rows(tbody):
    return "document.querySelectorAll('#%s tr').length > 0" % tbody


# ---------------------------------------------------------------- the pages
# `dialogs` is the page's own trigger→dialog pairs, taken from the markup and the
# row-action data attributes. `closes` is every control the report named on that
# dialog. `optional` is for a trigger that depends on live data (a Paid row to
# refund); it reports a note instead of failing, so a thin database cannot be
# mistaken for a broken page.
PAGES = [
    {
        "page": "admin-login.html",
        "title": "admin-login.html — the sign-in page carries no dialog at all",
        "ready": "!!document.getElementById('adminLoginForm')",
        "dialogs": [],
    },
    {
        "page": "admin-dashboard.html",
        "title": "admin-dashboard.html — stat cards and the Export button",
        "ready": "document.querySelectorAll('#recentBookingsBody tr').length > 0"
                 " || !document.getElementById('recentEmpty').hidden",
        "dialogs": [],
        "checks": ["export"],
    },
    {
        "page": "admin-flights.html",
        "title": "admin-flights.html — Add Flight, and its three dropdowns",
        "ready": has_rows("flightTableBody"),
        "dialogs": [{"id": "flightModal", "trigger": "#addFlightBtn",
                     "closes": ["#modalClose", "#modalCancel"]}],
        "checks": ["route-selects"],
    },
    {
        "page": "admin-airlines.html",
        "title": "admin-airlines.html — Add Airline, plus the conditional Remove-logo button",
        "ready": has_rows("airlineTableBody"),
        "dialogs": [{"id": "airlineModal", "trigger": "#addAirlineBtn",
                     "closes": ["#modalClose", "#modalCancel"]}],
    },
    {
        "page": "admin-bookings.html",
        "title": "admin-bookings.html — the booking detail dialog",
        "ready": has_rows("bookingTableBody"),
        "dialogs": [{"id": "bookingModal", "trigger": "#bookingTableBody [data-view]",
                     "closes": ["#bookingModalClose", "#bookingModalDone"]}],
        # The confirm dialog needs a Pending row (only those can be confirmed) or a
        # Confirmed one (only those can be cancelled); a seeded floor can have
        # neither, and "no row to act on" is not a broken dialog.
        "optional_dialogs": [{"id": "confirmModal",
                              "trigger": "#bookingTableBody [data-confirm], #bookingTableBody [data-cancel]",
                              "closes": ["#confirmClose", "#confirmNo"]}],
    },
    {
        "page": "admin-users.html",
        "title": "admin-users.html — Add User, per-user bookings, and the confirm dialog",
        "ready": has_rows("userTableBody"),
        "dialogs": [
            {"id": "userModal", "trigger": "#addUserBtn",
             "closes": ["#modalClose", "#modalCancel"]},
            {"id": "bookingsModal", "trigger": "#userTableBody [data-bookings]",
             "closes": ["#bookingsClose", "#bookingsDone"]},
        ],
        "optional_dialogs": [{"id": "confirmModal", "trigger": "#userTableBody [data-delete]",
                              "closes": ["#confirmClose", "#confirmNo"]}],
    },
    {
        "page": "admin-destinations.html",
        "title": "admin-destinations.html — Add Destination, and the confirm dialog's Confirm",
        "ready": has_rows("destTableBody"),
        "dialogs": [{"id": "destModal", "trigger": "#addDestBtn",
                     "closes": ["#modalClose", "#modalCancel"]}],
        "checks": ["confirm-yes-refusal"],
    },
    {
        "page": "admin-payments.html",
        "title": "admin-payments.html — the transaction detail dialog",
        "ready": has_rows("paymentTableBody"),
        "dialogs": [{"id": "payModal", "trigger": "#paymentTableBody [data-view]",
                     "closes": ["#payModalClose", "#payModalDone"]}],
        "optional_dialogs": [{"id": "confirmModal", "trigger": "#paymentTableBody [data-refund]",
                              "closes": ["#confirmClose", "#confirmNo"]}],
    },
    {
        "page": "admin-tickets.html",
        "title": "admin-tickets.html — the read-only ticket detail dialog",
        "ready": has_rows("ticketTableBody"),
        "dialogs": [{"id": "ticketModal", "trigger": "#ticketTableBody [data-view]",
                     "closes": ["#ticketModalClose", "#ticketModalDone"]}],
    },
    {
        "page": "admin-profile.html",
        "title": "admin-profile.html — the password meter, and a form that must not lie",
        # The account id renders as '#<id>' only once the profile read has resolved;
        # the markup ships an em dash, so this waits for the real load.
        "ready": "document.getElementById('sessId').textContent.charAt(0) === '#'",
        "dialogs": [],
        "checks": ["password-honest"],
    },
]

# The storefront twin, kept OUT of PAGES on purpose: it needs the customer session, and
# signing in as a customer replaces the token every admin page above relies on — so it
# is audited last, after everything else, and nothing follows it.
PROFILE_SPEC = {
    "page": "profile.html",
    "title": "profile.html (storefront) — the password meter, and a form that must not lie",
    # The account id is an em dash in the markup until GET /api/users/me answers.
    "ready": "document.getElementById('pcId').textContent.charAt(0) === '#'",
    "dialogs": [],
    "checks": ["password-honest"],
}


# ---------------------------------------------------------------- helpers
def hidden_still_visible(c):
    return json.loads(c.js(STILL_VISIBLE_HIDDEN))


def audit_hidden(walk, c, where):
    offenders = hidden_still_visible(c)
    walk.check("%s: nothing carrying `hidden` is visible" % where, not offenders,
               "; ".join(offenders))


def open_dialog(walk, c, dlg, where):
    """Click the trigger and wait for the dialog to be paint-visible."""
    exists = c.js("!!document.querySelector(%s)" % json.dumps(dlg["trigger"]))
    if not exists:
        return False
    walk.click(c, dlg["trigger"])
    return c.wait_js(VISIBLE % json.dumps("#" + dlg["id"]), timeout=10)


def close_controls(walk, c, dlg, where):
    """Every close control must actually hide the dialog, and keep it hidden."""
    for sel in dlg["closes"]:
        if not c.js("!!document.querySelector(%s)" % json.dumps(sel)):
            walk.check("%s: %s exists on #%s" % (where, sel, dlg["id"]), False, "no such control")
            continue
        if not c.js(VISIBLE % json.dumps("#" + dlg["id"])):
            if not open_dialog(walk, c, dlg, where):
                walk.check("%s: #%s could be re-opened to test %s" % (where, dlg["id"], sel),
                           False, "trigger did not open it")
                continue
        walk.click(c, sel)
        gone = c.wait_js(NOT_VISIBLE % json.dumps("#" + dlg["id"]), timeout=10)
        # ...and STAY gone: the defect was a dialog that popped straight back.
        stayed = gone and c.wait_js(NOT_VISIBLE % json.dumps("#" + dlg["id"]), timeout=2)
        walk.check("%s: %s closes #%s and it stays closed" % (where, sel, dlg["id"]),
                   bool(stayed),
                   "closed=%s stayed=%s" % (gone, stayed))


def check_route_selects(walk, c):
    """The Add Flight dropdowns: what "I can't add a flight, the boxes are empty" was.

    The form is only filled by openModal(), so a modal that showed BEFORE any click
    was always empty. Asserted with the modal genuinely open.
    """
    counts = json.loads(c.js("""JSON.stringify(
        ['flightAirline', 'flightFrom', 'flightTo'].reduce(function (o, id) {
          var s = document.getElementById(id);
          o[id] = s ? s.options.length : -1;
          return o;
        }, {}))"""))
    for field in ("flightAirline", "flightFrom", "flightTo"):
        # 1 option means only the "Select…" placeholder, or the "No airlines /
        # destinations — add one first" fallback: an unaddable form either way.
        walk.check("admin-flights.html: #%s offers real choices when the modal opens" % field,
                   counts.get(field, 0) >= 2,
                   "%s option(s) — %s" % (counts.get(field), counts))


def check_export(walk, c):
    """The Export button must produce a CSV download, not `onclick="return false"`."""
    if not c.js("!!document.getElementById('dashExport')"):
        walk.check("admin-dashboard.html: the Export button has a handler (id=dashExport)",
                   False, "no #dashExport — the button is still the onclick stub")
        return
    started = c.js("""(function () {
        window.__csv = null; window.__dl = null;
        var realCreate = URL.createObjectURL;
        URL.createObjectURL = function (b) { window.__csv = b; return realCreate.call(URL, b); };
        HTMLAnchorElement.prototype.click = function () {
          window.__dl = this.getAttribute('download') || '';
        };
        document.getElementById('dashExport').click();
        return !!window.__csv;
      })()""")
    walk.check("admin-dashboard.html: Export builds a file to download", bool(started),
               "no Blob was created by the click")
    if not started:
        return
    text = str(c.js("window.__csv ? window.__csv.text() : Promise.resolve('')",
                    await_promise=True) or "")
    name = str(c.js("window.__dl || ''"))
    lines = [line for line in text.splitlines() if line.strip()]
    walk.check("admin-dashboard.html: the download is a named .csv", name.endswith(".csv"),
               "download=%r" % name)
    walk.check("admin-dashboard.html: the CSV has a header and the booking rows",
               len(lines) >= 2 and "PNR" in lines[0],
               "%d line(s), first=%r" % (len(lines), lines[0] if lines else ""))


def check_password_form(walk, c, page):
    """A Change Password form must not report a change it never made.

    Both profile pages used to send nothing, reset the fields and toast "Password
    updated" from a setTimeout — a success message with no write behind it. The API
    serves no password-change route for either surface, so the expected behaviour is a
    refusal in the open: the note appears, the fields KEEP their values (clearing them
    is the visual language of a successful save), and the toast says nothing was saved.

    `page` is the spec's own file name: the two forms share these ids, so a check name
    that hard-coded one page would read as the other's when it failed.
    """
    if not c.js("!!document.getElementById('passwordForm')"):
        walk.check("%s: the Change Password form is on the page" % page, False,
                   "no #passwordForm")
        return

    walk.fill(c, {"pwCurrent": "Yatra-2026",
                  "pwNew": "Yatra-Profile-2026",
                  "pwConfirm": "Yatra-Profile-2026"})
    walk.click(c, "#passwordSaveBtn")

    walk.check("%s: submitting the password form shows the not-available note" % page,
               bool(c.wait_js(VISIBLE % json.dumps("#pwUnavailable"), timeout=10)))

    kept = c.js("document.getElementById('pwNew').value")
    walk.check("%s: the password fields are NOT cleared (no false success)" % page,
               kept == "Yatra-Profile-2026", "pwNew=%r" % kept)

    toast = str(c.js("(document.getElementById('toast')||{}).textContent || ''")).lower()
    walk.check("%s: the toast says nothing was saved, never \"updated\"" % page,
               "nothing was saved" in toast and "updated" not in toast, "toast=%r" % toast)


def sign_in_customer(walk, c):
    """A customer session for profile.html, bought through the real endpoint.

    YatraAuth reads exactly `yatra_auth_token` / `yatra_auth_user`, which the admin
    sign-in never writes, and an admin's token would not do for this page anyway —
    /api/users/me answers for whoever the JWT says, and the page is about the
    customer's own account.
    """
    status, session = walk.api("POST", "/api/auth/login",
                              {"loginId": CUSTOMER_EMAIL, "password": CUSTOMER_PASSWORD})
    ok = status == 200 and bool((session or {}).get("token"))
    walk.check("setup: a customer signed in against the real API for profile.html", ok,
               "POST /api/auth/login answered %s" % status)
    if not ok:
        return False
    c.js("sessionStorage.setItem('yatra_auth_token', %s);"
         "sessionStorage.setItem('yatra_auth_user', %s);"
         % (json.dumps(session.get("token")), json.dumps(json.dumps(session.get("user")))))
    return True



def check_confirm_yes(walk, c, dest):
    """Click "Confirm" for REAL on a delete the API must refuse.

    The report could not tell "the button is dead" from "clicking it changes nothing
    on screen". Both are covered here: the click is made against a destination a
    flight route still uses, so the server answers 409 DESTINATION_HAS_FLIGHTS and no
    row is written — which lets the walk assert the dialog closed AND the data is
    untouched, i.e. the handler ran rather than the page looking frozen.
    """
    walk.fill(c, {"destSearch": dest["code"]})
    if not c.wait_js("!!document.querySelector('#destTableBody [data-delete=\"%s\"]')" % dest["id"],
                     timeout=20):
        walk.check("admin-destinations.html: the confirm test's destination is on screen",
                   False, "no row for id=%s code=%s" % (dest["id"], dest["code"]))
        return

    walk.click(c, "#destTableBody [data-delete=\"%s\"]" % dest["id"])
    opened = c.wait_js(VISIBLE % json.dumps("#confirmModal"), timeout=10)
    walk.check("admin-destinations.html: a row's delete opens the confirm dialog", opened)

    walk.click(c, "#confirmYes")
    closed = c.wait_js(NOT_VISIBLE % json.dumps("#confirmModal"), timeout=10)
    walk.check("admin-destinations.html: #confirmYes closes the confirm dialog", closed,
               "the action ran; the dialog must still go away")

    # Read the roster back through the API, not through the page: the row must still
    # be there, and the count must be unchanged, because the delete was refused.
    after = walk.get_list("/api/destinations", "destinations")
    walk.check("admin-destinations.html: the refused delete wrote nothing (row still on the roster)",
               any(str(d.get("id")) == str(dest["id"]) for d in after),
               "%d destinations after, id=%s is %s"
               % (len(after), dest["id"],
                  "still present" if any(str(d.get("id")) == str(dest["id"]) for d in after)
                  else "GONE"))


def audit_page(walk, c, spec):
    """Everything this walk asserts about ONE page, in the order a user meets it.

    Split out of main() because profile.html is audited with it too — a customer page
    that cannot sit in PAGES (it is last, and it needs the other session).
    """
    walk.step(spec["title"])
    c.goto(BASE + "/" + spec["page"])
    ready = c.wait_js(spec["ready"], timeout=30)
    walk.check("%s: the page finished its first read" % spec["page"], ready,
               "waited on: %s" % spec["ready"])
    walk.check("%s: reads the real API, not the mock layer" % spec["page"],
               c.js("USE_MOCK_DATA") is False, "USE_MOCK_DATA=%r" % c.js("USE_MOCK_DATA"))

    # 1. The page-load state — where the defect lived and no walk had looked.
    audit_hidden(walk, c, spec["page"])
    dialogs = c.js("Array.from(document.querySelectorAll('.modal-overlay'))"
                   ".map(function (el) { return el.id; })")
    visible = json.loads(c.js("JSON.stringify("
                              "Array.from(document.querySelectorAll('.modal-overlay'))"
                              ".filter(function (el) {"
                              "  var cs = getComputedStyle(el);"
                              "  if (cs.display === 'none' || cs.visibility === 'hidden') return false;"
                              "  var r = el.getBoundingClientRect();"
                              "  return r.width > 0 && r.height > 0;"
                              "}).map(function (el) { return el.id; }))"))
    walk.check("%s: NO dialog is open on a fresh load (%d declared in the markup)"
               % (spec["page"], len(dialogs)), not visible, "open: %s" % ", ".join(visible))

    # 2. The named control opens it, and every close control closes it.
    for dlg in spec.get("dialogs", []):
        where = spec["page"]
        walk.check("%s: #%s is closed before its trigger is used" % (where, dlg["id"]),
                   bool(c.js(NOT_VISIBLE % json.dumps("#" + dlg["id"]))))
        if open_dialog(walk, c, dlg, where):
            walk.check("%s: %s opens #%s" % (where, dlg["trigger"], dlg["id"]), True)
            # Inside an OPEN dialog is where the conditional buttons live
            # (.a-btn is inline-flex, so `hidden` lost there too).
            audit_hidden(walk, c, "%s with #%s open" % (where, dlg["id"]))
            close_controls(walk, c, dlg, where)
        else:
            walk.check("%s: %s opens #%s" % (where, dlg["trigger"], dlg["id"]), False,
                       "clicked the trigger; the dialog never became visible")

    for dlg in spec.get("optional_dialogs", []):
        where = spec["page"]
        if not c.js("!!document.querySelector(%s)" % json.dumps(dlg["trigger"])):
            walk.note("%s: no row offers %s right now, so #%s gets no trigger test "
                      "(the load-state check above still covered it)"
                      % (where, dlg["trigger"], dlg["id"]))
            continue
        if open_dialog(walk, c, dlg, where):
            walk.check("%s: %s opens #%s" % (where, dlg["trigger"], dlg["id"]), True)
            close_controls(walk, c, dlg, where)
        else:
            walk.check("%s: %s opens #%s" % (where, dlg["trigger"], dlg["id"]), False)

    # 3. The reported behaviours that are not a dialog.
    checks = spec.get("checks", [])
    if "route-selects" in checks:
        open_dialog(walk, c, {"id": "flightModal", "trigger": "#addFlightBtn"}, spec["page"])
        check_route_selects(walk, c)
    if "export" in checks:
        check_export(walk, c)
    if "password-honest" in checks:
        check_password_form(walk, c, spec["page"])


def main():
    allow_stale = "--allow-stale" in sys.argv
    walk = Walk("admin-modals")

    walk.step("the build under test")
    if allow_stale:
        walk.note("--allow-stale: skipping the byte-for-byte guard on purpose. This run "
                  "audits a build that is ALLOWED to be behind the working tree — used to "
                  "reproduce a defect against the app that is currently running.")
    else:
        walk.check("the running app is serving this working tree, not a stale build",
                   walk.assert_serving_working_tree(
                       assets=["assets/css/admin.css", "assets/js/admin-flights.js",
                               "assets/js/admin-dashboard.js", "assets/js/admin-profile.js",
                               "assets/js/config.js", "assets/css/profile.css",
                               "assets/js/profile.js"],
                       markers=[("admin-flights.html", "addFlightBtn"),
                                ("admin-bookings.html", "confirmModal"),
                                ("admin-tickets.html", "Ticket details"),
                                ("admin-profile.html", "pwUnavailable"),
                                ("profile.html", "pwUnavailable")]))

    c = walk.browser()

    # The browser session (sign_in_admin, below) and the python-side token are two
    # different things: `get_list` reads `/api/admin/**` straight from here and needs
    # this one, or every read answers 403 and the walk sees an empty database.
    walk.check("setup: an admin token for the python-side reads", walk.login_admin())

    # admin-login.html FIRST, before a session exists: the page bounces a signed-in
    # admin straight to the dashboard, so auditing it after sign_in_admin() would
    # measure the dashboard instead.
    login = PAGES[0]
    walk.step(login["title"])
    c.goto(BASE + "/" + login["page"])
    c.wait_js(login["ready"], timeout=25)
    audit_hidden(walk, c, login["page"])

    # Not a dialog check, but the same class of blind spot: every admin page must be
    # reading the real API. A page left off config.js's REAL_API_PAGES answers from
    # mock-data.js, and the dashboard sat that way (permanently empty "Recent
    # Bookings") while the endpoint behind it worked.
    walk.check("%s: reads the real API, not the mock layer" % login["page"],
               c.js("USE_MOCK_DATA") is False, "USE_MOCK_DATA=%r" % c.js("USE_MOCK_DATA"))

    if not walk.sign_in_admin(c):
        walk.drain(c)
        return walk.finish()

    for spec in PAGES[1:]:
        audit_page(walk, c, spec)

    # 4. "Confirm" clicked for real, on a write the API refuses. Done last so the
    #    search it leaves in the toolbar cannot confuse an earlier page's checks.
    dest_page = next(s for s in PAGES if s["page"] == "admin-destinations.html")
    walk.step("admin-destinations.html — the confirm dialog's Confirm button, clicked")
    flights = walk.get_list("/api/admin/flights", "flights")
    dests = walk.get_list("/api/destinations", "destinations")
    used = {str(f.get("from") or "").upper() for f in flights} \
        | {str(f.get("to") or "").upper() for f in flights}
    pinned = [d for d in dests if str(d.get("code") or "").upper() in used]
    if not pinned:
        walk.note("no destination is referenced by a flight, so there is no refusal to "
                  "click Confirm against — the load-state and close-control checks above "
                  "still stand. Add a flight on a route first to enable this check.")
    else:
        dest = pinned[0]
        walk.note("using %s (%s, id=%s) — a flight route still uses it, so the API "
                  "refuses the delete (409 DESTINATION_HAS_FLIGHTS) and NOTHING is written"
                  % (dest.get("city"), dest.get("code"), dest.get("id")))
        c.goto(BASE + "/" + dest_page["page"])
        c.wait_js(dest_page["ready"], timeout=30)
        check_confirm_yes(walk, c, dest)

    # 5. The storefront profile page — the customer-side twin of admin-profile.html,
    #    sharing its password form and its CSS-vs-`hidden` shape. LAST on purpose:
    #    signing in as a customer replaces the admin token every page above used, so
    #    nothing may follow it.
    if sign_in_customer(walk, c):
        audit_page(walk, c, PROFILE_SPEC)

    walk.drain(c)
    # The 409 this walk asks for: the destination delete it clicks Confirm on. Chrome
    # reports one refused request TWICE — `Network.responseReceived` ("409 <url>") and a
    # console `Log.entryAdded` ("Failed to load resource: the server responded with a
    # status of 409") — so the intent has to be spelled out in both of Chrome's shapes
    # or the console half fails the run on a refusal the walk deliberately provoked.
    return walk.finish(expected_failures=[
        "409 %s/api/admin/destinations" % BASE,
        "the server responded with a status of 409",
    ])


if __name__ == "__main__":
    sys.exit(main())
