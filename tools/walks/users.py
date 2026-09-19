#!/usr/bin/env python3
"""§12/§13 — the Users module, walked through the plan's five-step workflow.

Users has **full CRUD** (create, edit, toggle, delete), so unlike Bookings it fits the
five steps exactly. Four things about it do not fit, and the walk tests all four rather
than stepping around them:

  * **The admin lock is a page rule AND an API rule, and they are different rules.**
    The page hides toggle/delete on an ADMIN row and disables the role/status selects
    when editing one; the API refuses the same three actions independently
    (409 `ADMIN_ACCOUNT_PROTECTED`) plus *creating* an Inactive admin. Same shape as
    the Destinations 11-code gate: the page's lock hides the server's rule, so the
    server's rule is unreachable from the UI and would never be exercised by clicking.
    The walk clicks (the UI's proof) **and** asks the API directly (the rule's proof),
    and then checks the admin row is byte-for-byte unchanged.
  * **The delete has a second refusal the page cannot show without a booking.**
    `409 USER_HAS_BOOKINGS` only fires for an account that has history, so the walk
    gives **its own** probe account a real booking — created through the PUBLIC
    endpoint as that customer, which is also what distinguishes this from a seeded
    row: a refusal aimed at seeded data would risk deleting it if the guard broke.
  * **`GET /api/admin/users/{id}/bookings` had no caller at all.** Phase 11 built it
    for this page and the page never asked. It now backs the row's receipts modal, so
    the walk checks the modal shows exactly what that endpoint and MySQL agree on.
  * **An account can now have no credential**, and `canSignIn` was unread. The walk
    creates one *without* a password and one *with*, checks the page distinguishes
    them, then **logs in as that account** with the password the edit modal set —
    which is the only independent proof that the optional field provisions a usable
    login and that `canSignIn` is telling the truth.

It needs one probe booking, and the API serves no booking DELETE, so the account
holding it cannot be deleted through the page either (that is the refusal under test).
`tools/users-probe-cleanup.sh` removes both accounts and the booking chain and releases
the seat; this walk runs it at the end (and at the start, for an interrupted run), so
it leaves the demo database at its seeded floor.

Run it with `tools/module-check.sh walks/users.py`.
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import ADMIN_EMAIL, BASE, ROOT, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
NAME_A = "Zz Probe Alpha %s" % TAG
NAME_B = "Zz Probe Bravo %s" % TAG
NAME_B_EDITED = NAME_B + " Edited"
EMAIL_A = "zz.userprobe.a.%s@example.com" % TAG
EMAIL_B = "zz.userprobe.b.%s@example.com" % TAG
EMAIL_C = "zz.userprobe.c.%s@example.com" % TAG   # only ever used to be refused
# 10 digits starting 96/97/98, the rule the page and the API both enforce. The 97
# prefix is deliberate: no seeded account's mobile starts with it, so a duplicate-phone
# test can never collide with the demo roster by accident.
PHONE_A = "9701%s" % TAG
PHONE_B = "9702%s" % TAG
PHONE_B_HIDDEN = "9703%s" % TAG   # written through the API behind the page's back
PASSWORD_A = "ProbeAlpha%s" % TAG
PASSWORD_B = "ProbeBravo%s" % TAG
PAGE_SIZE = 8

TABLE = "userTableBody"
MODAL = "userModal"
FORM = "userForm"
CONFIRM = "confirmModal"
BOOKINGS = "bookingsModal"
CLEANUP = ROOT / "tools" / "users-probe-cleanup.sh"

walk = Walk("users")


# ---------------------------------------------------------------- the database
def roster(query=""):
    return walk.get_list("/api/admin/users", "users", query)


def total(query=""):
    """The server's own total — never a count of the rows on screen."""
    sep = "&" if "?" in query else "?"
    _, body = walk.api("GET", "/api/admin/users%s%ssize=1" % (query, sep), auth=True)
    return int((body or {}).get("totalElements") or 0)


def one(user_id):
    _, body = walk.api("GET", "/api/admin/users/%d" % user_id, auth=True)
    return body or {}


def by_email(email):
    return [u for u in roster() if str(u.get("email", "")) == email]


def code_of(body):
    """The refusal's code.

    The wire key is `error`, not `code` — `ErrorResponse(String error, String message)`,
    which `api.js` maps onto `ApiError.code` for the pages. Reading `body["code"]` here
    is the mistake this walk made on its first run: three checks about refusals that had
    in fact just been refused, reported as failures.
    """
    return (body or {}).get("error")


def probes():
    """Accounts this walk (or an interrupted earlier one) created."""
    return [u for u in roster() if str(u.get("email", "")).startswith("zz.userprobe.")]


def admin_row():
    found = [u for u in roster() if str(u.get("email", "")) == ADMIN_EMAIL]
    return found[0] if found else {}


def booking_total():
    _, body = walk.api("GET", "/api/admin/bookings?size=1", auth=True)
    return int((body or {}).get("totalElements") or 0)


def user_bookings(user_id):
    _, body = walk.api("GET", "/api/admin/users/%d/bookings" % user_id, auth=True)
    return (body or {}).get("bookings") or []


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


def api_as(token, method, path, body=None):
    """A call AS someone else — the customer's own token, not the admin's."""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=60) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def login(email, password):
    """The real sign-in — the only proof a password field produced a usable login."""
    status, body = walk.api("POST", "/api/auth/login",
                            {"loginId": email, "password": password})
    return status, (body or {}).get("token")


def run_cleanup():
    if not CLEANUP.exists():
        return None, "tools/users-probe-cleanup.sh is missing"
    proc = subprocess.run(["bash", str(CLEANUP), "--apply"],
                          capture_output=True, text=True)
    lines = [l for l in proc.stdout.strip().splitlines() if l.strip()]
    return proc.returncode, "\n".join(lines[-7:])


def tidy(why):
    code, tail = run_cleanup()
    walk.note("%s: tools/users-probe-cleanup.sh --apply -> exit %s" % (why, code))
    for line in tail.splitlines():
        walk.note("  " + line)
    return code == 0


# ---------------------------------------------------------------- the page
def rows(c):
    return walk.table(c, TABLE, {"user": 1, "phone": 2, "role": 3, "registered": 4,
                                 "status": 5})


def ids(c, attr="data-edit"):
    """The visible rows' ids, row-aligned — a row's identity is on its buttons."""
    return walk.row_attr(c, TABLE, attr)


def present(c, attr):
    """Only the rows that carry this attribute (`row_attr` is row-aligned)."""
    return [v for v in ids(c, attr) if v]


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def page_info(c):
    return walk.text(c, "#pageInfo").strip()


def search(c, term, expect_count=None, expect_name=None):
    """The name is cell 1 (the name and its email sub-line share the cell)."""
    return walk.search_until(c, "userSearch", TABLE, term, expect_count=expect_count,
                             expect_name=expect_name, column=1)


def no_credential_rows(c):
    """Rows the page marked "No sign-in" — `canSignIn === false` made visible."""
    return json.loads(c.js(
        "JSON.stringify(Array.from(document.querySelectorAll('#%s [title^=\"No usable credential\"]'))"
        ".map(function (b) { return b.getAttribute('title'); }))" % TABLE))


def set_filter(c, element_id, value):
    walk.fill(c, {element_id: value})
    c.js("document.getElementById(%s).dispatchEvent(new Event('change', {bubbles:true}))"
         % json.dumps(element_id))


def open_modal(c):
    walk.click(c, "#addUserBtn")
    return c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)


def create(c, name, email, phone, role, status, password):
    """Add a user the way the page does. Returns True once the modal has closed."""
    open_modal(c)
    walk.fill(c, {"userName": name, "userEmail": email, "userPhone": phone,
                  "userRole": role, "userStatus": status, "userPassword": password})
    walk.submit(c, FORM)
    return c.wait_js("document.getElementById('%s').hidden === true" % MODAL, timeout=25)


def confirm_dialog(c):
    """The page's own confirm modal (§13: no browser `confirm()` on this page)."""
    return walk.modal_open(c, CONFIRM)


def main():
    if not walk.login_admin():
        walk.check("setup: the admin API token", False)
        return 1
    stale = probes()
    if stale:
        walk.note("%d probe account(s) from an earlier run are still here (ids: %s)"
                  % (len(stale), ", ".join(str(u["id"]) for u in stale)))
        tidy("sweeping them first")
    floor = total()
    bookings_floor = booking_total()
    admin = admin_row()
    walk.note("the database holds %d users (%d bookings) before this walk"
              % (floor, bookings_floor))
    walk.note("the panel's own admin account: id %s, %s" % (admin.get("id"), ADMIN_EMAIL))

    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-users.js"],
            markers=[("admin-users.html", "Add User")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's seed)")
    c.goto(BASE + "/admin-users.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE,
                         timeout=25))
    walk.check("1: the page really called GET /api/admin/users (its own resource timings)",
               any("/api/admin/users" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "users" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d users" % floor,
               "page=%r db=%d" % (count_text(c), floor))

    _, body = walk.api("GET", "/api/admin/users?page=0&size=%d" % PAGE_SIZE, auth=True)
    first_page = [str(u["id"]) for u in ((body or {}).get("users") or [])]
    walk.check("1: the rows on screen ARE the server's first page (no seed merge)",
               [str(i) for i in ids(c)] == first_page,
               "page=%s api=%s" % (ids(c), first_page))
    walk.check("1: the page-private localStorage roster is gone (nothing wrote it)",
               c.js("!localStorage.getItem('yatra_admin_users')"))
    walk.check("§13: no modal is open on load",
               not walk.modal_open(c, MODAL) and not confirm_dialog(c)
               and not walk.modal_open(c, BOOKINGS))
    walk.check("1: the search box starts empty (the list is the server's, unfiltered)",
               c.js("document.getElementById('userSearch').value") == "")

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE")
    walk.check("2: \"Add User\" opens the modal (and only the click did)", open_modal(c))
    walk.check("2: it opens as Add, with empty fields",
               walk.text(c, "#modalTitle") == "Add User"
               and c.js("document.getElementById('userName').value") == ""
               and c.js("document.getElementById('userId').value") == "")

    walk.submit(c, FORM)
    walk.check("2: required-field validation shows INLINE errors",
               c.wait_js("document.querySelectorAll('#%s .error-msg').length >= 2" % FORM,
                         timeout=8),
               "errors=%s" % c.js("document.querySelectorAll('#%s .error-msg').length"
                                  % FORM))
    walk.check("2: and writes nothing", total() == floor)
    walk.check("2: and says so in a toast",
               "highlighted fields" in walk.toast(c), walk.toast(c))

    walk.click(c, "#modalCancel")
    walk.check("2: Cancel closes the modal without saving",
               not walk.modal_open(c, MODAL) and total() == floor)
    open_modal(c)
    walk.click(c, "#modalClose")
    walk.check("2: the × closes it too, saving nothing",
               not walk.modal_open(c, MODAL) and total() == floor)

    # --- probe A: created WITH a password, so `canSignIn` is true ---
    walk.check("2: the form offers an OPTIONAL password field (the way out of the "
               "\"created but can never sign in\" trap)",
               c.js("!!document.getElementById('userPassword')")
               and "Optional" in walk.text(c, "#passwordHint"),
               walk.text(c, "#passwordHint"))
    walk.click(c, "#addUserBtn")
    c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)
    walk.fill(c, {"userName": NAME_A, "userEmail": EMAIL_A, "userPhone": PHONE_A,
                  "userRole": "USER", "userStatus": "Active", "userPassword": "short"})
    walk.submit(c, FORM)
    walk.check("2: a too-short password is refused locally, before any request is sent",
               c.wait_js(
                   "(function(){var f=document.getElementById('userPassword').closest('.a-field');"
                   "var m=f&&f.querySelector('.error-msg');return !!m;})()", timeout=8)
               and total() == floor and walk.modal_open(c, MODAL),
               walk.text(c, "#%s .error-msg" % FORM))

    walk.fill(c, {"userPassword": PASSWORD_A})
    walk.submit(c, FORM)
    walk.check("2: Save POSTs and closes the modal",
               c.wait_js("document.getElementById('%s').hidden === true" % MODAL,
                         timeout=25), walk.toast(c))
    walk.check("2: the toast names the new account",
               NAME_A in walk.toast(c) and "added" in walk.toast(c), walk.toast(c))
    stored_a = by_email(EMAIL_A)
    walk.check("2: THE DATABASE has the row (read back through the API, not the page)",
               len(stored_a) == 1 and stored_a[0]["name"] == NAME_A
               and stored_a[0]["role"] == "USER" and stored_a[0]["status"] == "Active",
               json.dumps(stored_a)[:240])
    walk.check("2: and it reports canSignIn=true, because the password really was set",
               stored_a and stored_a[0]["canSignIn"] is True, json.dumps(stored_a)[:200])
    probe_a = stored_a[0]["id"] if stored_a else 0
    # The independent proof: sign in AS that account with the password the modal set.
    status, token_a = login(EMAIL_A, PASSWORD_A)
    walk.check("2: the account can actually SIGN IN (POST /api/auth/login as it)",
               status == 200 and bool(token_a), "status=%s" % status)
    walk.check("2: the list refreshed from the backend (the server's new total is on screen)",
               c.wait_js("document.getElementById('resultCount').textContent.trim() === %s"
                         % json.dumps("%d users" % (floor + 1)), timeout=25),
               count_text(c))

    # --- probe B: created with NO password, so `canSignIn` is false ---
    walk.check("2: a second account, this one with no credential at all",
               create(c, NAME_B, EMAIL_B, PHONE_B, "USER", "Active", ""), walk.toast(c))
    stored_b = by_email(EMAIL_B)
    walk.check("2: it is in the database and reports canSignIn=false",
               len(stored_b) == 1 and stored_b[0]["canSignIn"] is False,
               json.dumps(stored_b)[:200])
    probe_b = stored_b[0]["id"] if stored_b else 0
    walk.check("2: the table says so (the \"No sign-in\" chip the page could not show before)",
               search(c, EMAIL_B, expect_count=1, expect_name=NAME_B)
               and len(no_credential_rows(c)) == 1, str(no_credential_rows(c)))
    walk.check("2: while the account WITH a password shows no such chip",
               search(c, EMAIL_A, expect_count=1, expect_name=NAME_A)
               and not no_credential_rows(c))

    # Duplicates are the SERVER's answer: this page holds one page of the roster.
    open_modal(c)
    walk.fill(c, {"userName": "Zz Probe Dup %s" % TAG, "userEmail": EMAIL_A})
    walk.submit(c, FORM)
    # Wait for the CONTENT and for it to sit on the email field, then read it: reading
    # the message before waiting is how a check reports "" and looks like a failure.
    email_error = c.wait_js(
        "(function(){var f=document.getElementById('userEmail').closest('.a-field');"
        "var m=f&&f.querySelector('.error-msg');"
        "return !!m && m.textContent.indexOf('already exists') !== -1;})()", timeout=25)
    walk.check("2: a taken EMAIL is refused with an INLINE error on that field",
               email_error, walk.text(c, "#%s .error-msg" % FORM))
    walk.check("2: and nothing was written", total() == floor + 2)
    walk.note("(that sentence is the API's: 409 EMAIL_EXISTS — the client cannot know it)")

    walk.fill(c, {"userEmail": EMAIL_C, "userPhone": PHONE_A})
    walk.submit(c, FORM)
    walk.check("2: a taken MOBILE number is refused inline, on the phone field",
               c.wait_js(
                   "(function(){var f=document.getElementById('userPhone').closest('.a-field');"
                   "var m=f&&f.querySelector('.error-msg');"
                   "return !!m && m.textContent.indexOf('already exists') !== -1;})()",
                   timeout=25),
               walk.text(c, "#%s .error-msg" % FORM))
    walk.check("2: the modal stayed open on both refusals (nothing silently lost)",
               walk.modal_open(c, MODAL) and total() == floor + 2
               and len(by_email(EMAIL_C)) == 0)
    walk.click(c, "#modalCancel")

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching a name narrows the table to the server's answer",
               search(c, NAME_A, expect_count=1, expect_name=NAME_A), str(rows(c)))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "/api/admin/users" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "search=" in u)[:200])
    walk.check("3: the count line is the filtered total", count_text(c) == "1 user",
               count_text(c))

    walk.check("3: searching an EMAIL finds the account (what the box promises)",
               search(c, EMAIL_A, expect_count=1, expect_name=NAME_A), str(rows(c)))
    walk.check("3: searching a MOBILE number finds the account",
               search(c, PHONE_A, expect_count=1, expect_name=NAME_A), str(rows(c)))
    walk.check("3: searching an ID finds the account (pasting back an id the list gave)",
               search(c, str(probe_a), expect_count=1, expect_name=NAME_A), str(rows(c)))
    walk.note("(UserRepository.searchAll matches name, email, phone OR id — one query)")

    walk.check("3: clearing the box restores the first server page",
               search(c, "", expect_count=PAGE_SIZE), "rows=%d" % len(rows(c)))

    admins = total("?role=ADMIN")
    set_filter(c, "roleFilter", "ADMIN")
    walk.check("3: the role filter is a query (ADMIN alone is the server's answer)",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, min(PAGE_SIZE, admins)), timeout=25)
               and all(r["role"] == "ADMIN" for r in rows(c)),
               "page=%r api=%d" % (count_text(c), admins))
    walk.check("3: and its count is the server's filtered total",
               count_text(c) == "%d user%s" % (admins, "" if admins == 1 else "s"),
               count_text(c))

    set_filter(c, "roleFilter", "ALL")
    inactive = total("?status=Inactive")
    set_filter(c, "statusFilter", "Inactive")
    walk.check("3: the status filter is a query too (Inactive included)",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, min(PAGE_SIZE, inactive)), timeout=25)
               and all(r["status"] == "Inactive" for r in rows(c)),
               "page=%r api=%d" % (count_text(c), inactive))
    set_filter(c, "statusFilter", "ALL")

    walk.check("3: paging is server-side (%d users over %d-per-page = 2 pages)"
               % (floor + 2, PAGE_SIZE),
               c.wait_js("document.querySelectorAll('#pageBtns .page-btn').length >= 4",
                         timeout=25),
               "buttons=%s" % c.js("document.querySelectorAll('#pageBtns .page-btn').length"))
    walk.check("3: the page info line is the server's arithmetic",
               ("of %d users" % (floor + 2)) in page_info(c), page_info(c))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: page 2 shows the remainder and says so",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, (floor + 2) - PAGE_SIZE), timeout=25),
               "rows=%d info=%s" % (len(rows(c)), page_info(c)))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '1'; })[0];
        if (b) b.click();
    })()""")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)

    # ============================================================== 4. UPDATE
    walk.step("§12 step 4 — UPDATE")
    walk.check("4: (setup) the row to edit is on screen with its stored mobile",
               search(c, EMAIL_B, expect_count=1, expect_name=NAME_B)
               and rows(c)[0]["phone"] == PHONE_B, str(rows(c)))
    # Change the row BEHIND the page's back, then open Edit. A modal filled from the
    # table's cached row would show the old number — the bug step 4 names.
    status, _ = walk.api("PUT", "/api/admin/users/%d" % probe_b, {
        "name": NAME_B, "email": EMAIL_B, "phone": PHONE_B_HIDDEN,
        "role": "USER", "status": "Active", "password": ""}, auth=True)
    walk.check("4: (setup) the row was changed through the API, not the page", status == 200,
               "status=%s" % status)

    walk.click(c, '[data-edit="%s"]' % probe_b)
    walk.check("4: Edit opens pre-filled from a FRESH GET, not from the cached row",
               c.wait_js("document.getElementById('%s').hidden === false" % MODAL,
                         timeout=25)
               and c.js("document.getElementById('userPhone').value") == PHONE_B_HIDDEN,
               "modal=%r (the table still shows %r)"
               % (c.js("document.getElementById('userPhone').value"), rows(c)[0]["phone"]))
    walk.check("4: while the TABLE still shows the stale value it was rendered with",
               rows(c)[0]["phone"] != PHONE_B_HIDDEN, rows(c)[0]["phone"])
    walk.check("4: the modal is titled Edit and carries the row's id",
               walk.text(c, "#modalTitle") == "Edit User"
               and str(c.js("document.getElementById('userId').value")) == str(probe_b))

    walk.fill(c, {"userName": NAME_B_EDITED, "userPassword": PASSWORD_B})
    walk.submit(c, FORM)
    walk.check("4: saving PUTs and closes the modal",
               c.wait_js("document.getElementById('%s').hidden === true" % MODAL,
                         timeout=25), walk.toast(c))
    stored_edited = by_email(EMAIL_B)
    walk.check("4: THE DATABASE has the new name and the mobile the API wrote",
               len(stored_edited) == 1 and stored_edited[0]["name"] == NAME_B_EDITED
               and stored_edited[0]["phone"] == PHONE_B_HIDDEN,
               json.dumps(stored_edited)[:220])
    walk.check("4: the edit PROVISIONED a credential — canSignIn flipped to true",
               stored_edited and stored_edited[0]["canSignIn"] is True,
               json.dumps(stored_edited)[:200])
    status, _ = login(EMAIL_B, PASSWORD_B)
    walk.check("4: and that account can now sign in with the password set in the modal",
               status == 200, "status=%s" % status)
    walk.check("4: the page no longer marks it \"No sign-in\"",
               search(c, NAME_B_EDITED, expect_count=1, expect_name=NAME_B_EDITED)
               and not no_credential_rows(c), str(no_credential_rows(c)))

    walk.check("4: (setup) the row is Active before the toggle",
               rows(c) and rows(c)[0]["status"] == "Active", str(rows(c)))
    walk.click(c, '[data-toggle="%s"]' % probe_b)
    walk.check("4: the toggle writes through the API and comes back Inactive",
               c.wait_js(
                   "(function(){var e=document.querySelector('#%s tr td:nth-child(5)');"
                   "return !!e && e.textContent.trim() === 'Inactive';})()" % TABLE,
                   timeout=25)
               and one(probe_b).get("status") == "Inactive",
               json.dumps(one(probe_b))[:160])
    walk.check("4: a deactivated account really cannot sign in (the point of the toggle)",
               login(EMAIL_B, PASSWORD_B)[0] != 200
               and one(probe_b).get("canSignIn") is False,
               json.dumps(one(probe_b))[:160])
    walk.click(c, '[data-toggle="%s"]' % probe_b)
    walk.check("4: and back to Active",
               c.wait_js("(function(){var e=document.querySelector('#%s tr td:nth-child(5)');"
                         "return !!e && e.textContent.trim() === 'Active';})()" % TABLE,
                         timeout=25)
               and one(probe_b).get("status") == "Active")

    # --- the admin lock: the page's rule, and then the API's ---
    walk.check("4: (setup) the panel's own admin account is on screen",
               search(c, ADMIN_EMAIL, expect_count=1, expect_name=admin.get("name", ""))
               and rows(c)[0]["role"] == "ADMIN", str(rows(c)))
    walk.check("4: the page LOCKS that row (no toggle, no delete — a lock instead)",
               not present(c, "data-toggle") and not present(c, "data-delete")
               and bool(present(c, "data-edit"))
               and not c.js("!!document.querySelector('#%s button[data-toggle]')" % TABLE)
               and c.js("!!document.querySelector('#%s [title^=\"Admin accounts are protected\"]')"
                        % TABLE),
               "edits=%s toggles=%s" % (present(c, "data-edit"), present(c, "data-toggle")))
    walk.click(c, '[data-edit="%s"]' % admin["id"])
    walk.check("4: and Edit disables the role/status selects for it, keeping its values",
               c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=25)
               and c.js("document.getElementById('userRole').disabled") is True
               and c.js("document.getElementById('userStatus').disabled") is True
               and c.js("document.getElementById('userRole').value") == admin["role"]
               and c.js("document.getElementById('userStatus').value") == admin["status"]
               and c.js("document.getElementById('lockNote').hidden") is False)
    walk.click(c, "#modalCancel")

    # The lock is a UI convenience; the API enforces the rule itself. Ask it directly —
    # a lock the page paints is exactly the thing that hides an unenforced rule.
    noop = {"name": admin["name"], "email": admin["email"], "phone": admin["phone"],
            "role": admin["role"], "status": admin["status"], "password": ""}
    status, _ = walk.api("PUT", "/api/admin/users/%s" % admin["id"], noop, auth=True)
    walk.check("4: the API accepts name/email/phone edits on an admin (echoing its own "
               "values is a no-op, not a conflict)", status == 200, "status=%s" % status)

    status, body = walk.api("PUT", "/api/admin/users/%s" % admin["id"],
                            dict(noop, role="USER"), auth=True)
    walk.check("4: but DEMOTING it is refused by the API itself (409 ADMIN_ACCOUNT_PROTECTED)",
               status == 409 and code_of(body) == "ADMIN_ACCOUNT_PROTECTED",
               "%s %s" % (status, json.dumps(body)[:200]))

    status, body = walk.api("PUT", "/api/admin/users/%s/status" % admin["id"],
                            {"status": "Inactive"}, auth=True)
    walk.check("4: so is DEACTIVATING it (the one write that could lock everyone out)",
               status == 409 and code_of(body) == "ADMIN_ACCOUNT_PROTECTED",
               "%s %s" % (status, json.dumps(body)[:200]))

    status, body = walk.api("DELETE", "/api/admin/users/%s" % admin["id"], auth=True)
    walk.check("4: and so is DELETING it",
               status == 409 and code_of(body) == "ADMIN_ACCOUNT_PROTECTED",
               "%s %s" % (status, json.dumps(body)[:200]))

    after = one(admin["id"])
    walk.check("4: after all four attempts the admin row is UNCHANGED",
               after.get("role") == admin["role"] and after.get("status") == admin["status"]
               and after.get("email") == admin["email"]
               and after.get("phone") == admin["phone"]
               and after.get("name") == admin["name"],
               json.dumps(after)[:220])

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE (and the two refusals)")
    # Probe A gets a booking, made through the PUBLIC endpoint AS that customer — which
    # is the only way `GET /api/admin/users/{id}/bookings` has anything to show, and the
    # reason the delete below really is refused. Aiming that refusal at a SEEDED account
    # would risk the seeded row if the guard ever broke.
    flight, seat = bookable_flight()
    walk.check("5: (setup) a seeded flight with a free seat was found",
               flight is not None and seat is not None,
               "flight=%s seat=%s" % (flight and flight.get("no"), seat))
    if not flight or not probe_a:
        return 1
    seat_before = seat_status(flight["id"], seat)
    last = "Probeuser%s" % TAG
    status, body = api_as(token_a, "POST", "/api/bookings", {
        "contact": {"title": "Mr", "firstName": "Zz", "lastName": last,
                    "email": EMAIL_A, "phone": PHONE_A},
        "passengers": [{"title": "Mr", "firstName": "Zz", "lastName": last,
                        "nationality": "Nepali", "type": "ADT", "seatNumber": seat}],
        "flight": {"flightNo": flight["no"], "from": flight["from"], "to": flight["to"]},
        "amount": 0
    })
    probe_booking = int((body or {}).get("bookingId") or 0)
    walk.check("5: the customer account made a real booking (public endpoint, no admin token)",
               status == 200 and probe_booking > 0, "%s %s" % (status, json.dumps(body)[:160]))
    walk.check("5: and it is linked to THAT account (the FK, not just the contact block)",
               any(str(b.get("id")) == str(probe_booking) for b in user_bookings(probe_a)),
               json.dumps(user_bookings(probe_a))[:200])

    # The endpoint Phase 11 built for this page, and the modal that now calls it.
    walk.check("5: (setup) the account is the only row on screen",
               search(c, EMAIL_A, expect_count=1, expect_name=NAME_A))
    walk.click(c, '[data-bookings="%s"]' % probe_a)
    walk.check("5: the row's receipts modal opens on GET /api/admin/users/{id}/bookings",
               c.wait_js("document.getElementById('%s').hidden === false" % BOOKINGS,
                         timeout=25)
               and any("/api/admin/users/%d/bookings" % probe_a in u
                       for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "bookings" in u)[:200])
    modal_rows = walk.table(c, "bookingsBody", {"booking": 1, "flight": 2, "route": 3,
                                                "amount": 4, "status": 5})
    api_booking = (user_bookings(probe_a) or [{}])[0]
    walk.check("5: it shows exactly the one booking the API reports",
               len(modal_rows) == 1 and str(probe_booking) in modal_rows[0]["booking"],
               str(modal_rows))
    walk.check("5: with the flight number and the amount the server holds",
               api_booking.get("flight", {}).get("flightNo", "") in modal_rows[0]["flight"]
               and ("NPR %s" % format(float(api_booking.get("amount") or 0), ",.2f"))
               in modal_rows[0]["amount"],
               "row=%s api=%s" % (modal_rows[0], json.dumps(api_booking)[:200]))
    walk.check("5: and the meta line counts them",
               "1 booking on this account" in walk.text(c, "#bookingsMeta"),
               walk.text(c, "#bookingsMeta"))
    walk.click(c, "#bookingsDone")
    walk.check("5: Close closes it", not walk.modal_open(c, BOOKINGS))

    # The cancel path first, so the refusal below is the only thing that writes nothing.
    walk.click(c, '[data-delete="%s"]' % probe_a)
    walk.check("5: Delete asks for confirmation in the page's own dialog, naming the account",
               confirm_dialog(c) and NAME_A in walk.text(c, "#confirmMsg")
               and "Delete this user?" in walk.text(c, "#confirmTitle"),
               "%r / %r" % (walk.text(c, "#confirmTitle"), walk.text(c, "#confirmMsg")))
    walk.click(c, "#confirmNo")
    walk.check("5: \"Keep it\" closes it and deletes nothing",
               not confirm_dialog(c) and len(by_email(EMAIL_A)) == 1)

    walk.click(c, '[data-delete="%s"]' % probe_a)
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=8)
    walk.click(c, "#confirmYes")
    walk.check("5: an account with bookings is REFUSED with the API's own sentence",
               walk.wait_content(c, "#toast", "cannot be deleted", timeout=25)
               and "set the account to Inactive instead" in walk.toast(c),
               walk.toast(c))
    walk.check("5: and the refusal names how much history it has (1 booking)",
               "1 booking(s)" in walk.toast(c), walk.toast(c))
    walk.check("5: the account is still in the database, untouched",
               len(by_email(EMAIL_A)) == 1 and len(user_bookings(probe_a)) == 1)
    walk.check("5: and still on screen (a refused delete refreshes nothing away)",
               search(c, EMAIL_A, expect_count=1, expect_name=NAME_A))
    walk.note("(deactivating is the supported answer — step 4 just proved the toggle works)")

    # The successful delete: an account with no history.
    walk.check("5: (setup) the other account is the only row on screen",
               search(c, NAME_B_EDITED, expect_count=1, expect_name=NAME_B_EDITED))
    walk.click(c, '[data-delete="%s"]' % probe_b)
    walk.check("5: the dialog names THAT account (not the previous one)",
               confirm_dialog(c) and NAME_B_EDITED in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg"))
    walk.click(c, "#confirmYes")
    walk.check("5: the row is gone from the DATABASE",
               walk.wait_db(lambda: len(by_email(EMAIL_B)) == 0),
               json.dumps(by_email(EMAIL_B))[:120])
    walk.check("5: the list refreshed from the backend after the delete",
               c.wait_js("document.querySelectorAll('#%s tr').length === 0" % TABLE,
                         timeout=25), str(rows(c)))
    walk.check("5: the empty state is the page's own message",
               c.js("!document.getElementById('emptyState').hidden")
               and "No users" in walk.text(c, "#emptyState"),
               walk.text(c, "#emptyState"))
    walk.check("5: and the toast is the page's own success message",
               "deleted" in walk.toast(c), walk.toast(c))

    # ---------------------------------------------------------------- tidy up
    walk.step("tidy up — the account that could not be deleted, and its booking")
    walk.check("tidy: tools/users-probe-cleanup.sh removed the probe rows",
               tidy("cleanup"))
    walk.check("tidy: no probe account is left anywhere",
               len(probes()) == 0, json.dumps([u["email"] for u in probes()])[:200])
    final = total()
    walk.check("tidy: the user roster is back at its seeded floor (%d users)" % floor,
               final == floor, "now=%d was=%d" % (final, floor))
    walk.check("tidy: and the booking it created is gone too (%d bookings)" % bookings_floor,
               booking_total() == bookings_floor,
               "now=%d was=%d" % (booking_total(), bookings_floor))
    walk.check("tidy: the seat it held was RELEASED (%s back to %s)" % (seat, seat_before),
               seat_status(flight["id"], seat) == seat_before,
               "%s is %s" % (seat, seat_status(flight["id"], seat)))

    # The two duplicate refusals and the refused delete make three expected 409s — the
    # server saying no and the page surfacing it, which is the behaviour under test.
    walk.drain(c)
    return walk.finish(expected_failures=[
        "409 %s/api/admin/users" % BASE,
        "status of 409",
    ])


if __name__ == "__main__":
    sys.exit(main())
