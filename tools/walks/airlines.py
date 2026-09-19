#!/usr/bin/env python3
"""§12/§13 — the Airlines module, walked through the plan's five-step workflow.

THE WORKED EXAMPLE. This is what the remaining modules (Destinations, Bookings,
Users, Payments, Tickets) are built from: it shows the shape of a module walk, and
the shared half of it — the stale-build guard, the content-keyed waits, the API
client that checks the database after every write — lives in `tools/module_check.py`
so it is written once.

Run it with `tools/module-check.sh walks/airlines.py` (that runner packages the jar
FIRST, which is the fix for the stale-build trap), or by hand against a jar already
listening on 8081 with Chrome on 9222:

    python3 tools/walks/airlines.py

It leaves the demo database at its seeded floor: the two carriers it creates are
deleted through the same UI at the end, and leftovers from an interrupted run are
swept first, because the floor this walk asserts is only meaningful if none of its
own rows are already there.
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
NAME_A = "Zz Probe Air %s" % TAG
NAME_B = "Zz Probe Link %s" % TAG
NAME_A_EDITED = NAME_A + " Edited"
IATA_A, IATA_B = "Z1", "Z7"
PAGE_SIZE = 5
COLUMNS = {"name": 2, "iata": 3, "desc": 4, "status": 5}
TABLE = "airlineTableBody"
MODAL = "airlineModal"
FORM = "airlineForm"

walk = Walk("airlines")


def carriers(query=""):
    return walk.get_list("/api/airlines", "airlines", query)


def named(name):
    return [a for a in carriers() if a.get("name") == name]


def rows(c):
    return walk.table(c, TABLE, COLUMNS)


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def ids(c, attr="data-edit"):
    """The visible rows' ids, row-aligned — a row's identity is on its buttons."""
    return walk.row_attr(c, TABLE, attr)


def logo_srcs(c):
    """Every <img> the table rendered, as an attribute read — not as our API's DTO.

    The row dicts hold the page's TEXT cells; a logo is an element, so asking the API
    what it returned proves nothing about what the page put on screen.
    """
    return json.loads(c.js(
        "JSON.stringify(Array.from(document.querySelectorAll('#%s img'))"
        ".map(function (i) { return i.getAttribute('src') || ''; }))" % TABLE))


def sweep():
    """Delete any probe carrier an earlier interrupted run left behind."""
    gone = []
    for a in carriers():
        if str(a.get("name", "")).startswith("Zz Probe"):
            walk.api("DELETE", "/api/admin/airlines/%d" % a["id"], auth=True)
            gone.append(a["name"])
    return gone


def create(c, name, iata, status, description):
    walk.click(c, "#addAirlineBtn")
    c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)
    walk.fill(c, {"airlineName": name, "airlineIata": iata, "airlineDesc": description,
                  "airlineStatus": status})
    walk.submit(c, FORM)
    return c.wait_js("document.getElementById('%s').hidden === true" % MODAL, timeout=25)


def main():
    if not walk.login_admin():
        walk.check("setup: the admin API token", False)
        return 1
    stale = sweep()
    if stale:
        walk.note("removed %d leftover probe carrier(s) from an earlier run: %s"
                  % (len(stale), ", ".join(stale)))
    floor = len(carriers())
    walk.note("the database holds %d airlines before this walk (the seeded floor)" % floor)

    # The guard the last module's failures came from: this must be the build under test.
    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-airlines.js"],
            markers=[("admin-airlines.html", "Add Airline")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's seed)")
    c.goto(BASE + "/admin-airlines.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE, timeout=25))
    walk.check("1: the page really called GET /api/airlines (its own resource timings)",
               any("airlines" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "airlines" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d airlines" % floor, "page=%r db=%d" % (count_text(c), floor))
    srcs = logo_srcs(c)
    walk.check("1: every logo on screen is the API's bytes endpoint, not a bundled asset",
               srcs and all(s.startswith("/api/airlines/") and s.endswith("/logo")
                            for s in srcs)
               and not any("assets/imgs" in s for s in srcs),
               str(srcs)[:240])
    status, ctype, body = walk.fetch(srcs[0]) if srcs else (0, "", b"")
    walk.check("1: and that endpoint really answers with image bytes",
               status == 200 and ctype.startswith("image/") and len(body) > 0,
               "%s %s %d bytes" % (status, ctype, len(body)))
    walk.note("(the seeded carriers all store a Base64 logo, so this is not a vacuous "
              "0-image page — %d chip(s) rendered)" % len(srcs))
    walk.check("1: the page-private localStorage store is gone (nothing wrote it)",
               c.js("!localStorage.getItem('yatra_admin_airlines')"))
    walk.check("§13: the Add modal is NOT open on load", not walk.modal_open(c, MODAL))

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE")
    walk.click(c, "#addAirlineBtn")
    walk.check("2: \"Add Airline\" opens the modal (and only the click did)",
               c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8))
    walk.check("2: it opens as Add, with empty fields",
               walk.text(c, "#modalTitle") == "Add Airline"
               and c.js("document.getElementById('airlineName').value") == ""
               and c.js("document.getElementById('airlineId').value") == "")

    walk.submit(c, FORM)
    walk.check("2: required-field validation shows INLINE errors",
               c.wait_js("document.querySelectorAll('#%s .error-msg').length >= 2" % FORM,
                         timeout=8),
               "errors=%s" % c.js("document.querySelectorAll('#%s .error-msg').length" % FORM))
    walk.check("2: and writes nothing", len(carriers()) == floor)
    walk.check("2: and says so in a toast", "highlighted fields" in walk.toast(c), walk.toast(c))

    walk.click(c, "#modalCancel")
    walk.check("2: Cancel closes the modal without saving",
               not walk.modal_open(c, MODAL) and len(carriers()) == floor)
    walk.click(c, "#addAirlineBtn")
    c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)
    walk.click(c, "#modalClose")
    walk.check("2: the × closes it too, saving nothing",
               not walk.modal_open(c, MODAL) and len(carriers()) == floor)

    walk.check("2: Save POSTs and closes the modal",
               create(c, NAME_A, IATA_A, "Active", "Probe carrier A — tools/walks/airlines.py"),
               walk.toast(c))
    walk.check("2: the toast names the new airline",
               NAME_A in walk.toast(c) and "added" in walk.toast(c), walk.toast(c))
    stored_a = named(NAME_A)
    walk.check("2: THE DATABASE has the row (read back through the API, not the page)",
               len(stored_a) == 1 and stored_a[0]["iata"] == IATA_A
               and stored_a[0]["status"] == "Active", json.dumps(stored_a)[:200])
    walk.check("2: the list refreshed from the backend (the server's new total is on screen)",
               count_text(c) == "%d airlines" % (floor + 1), count_text(c))
    walk.check("2: a second carrier lands too (the paging check needs a second page)",
               create(c, NAME_B, IATA_B, "Inactive", "Probe carrier B — tools/walks/airlines.py")
               and len(named(NAME_B)) == 1, walk.toast(c))

    # Duplicates are the SERVER's answer: this page holds one page of rows.
    walk.click(c, "#addAirlineBtn")
    c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)
    walk.fill(c, {"airlineName": "Zz Probe Dup %s" % TAG, "airlineIata": "U4"})
    walk.submit(c, FORM)
    # Wait for the CONTENT and for it to sit on the IATA field, then read it: reading
    # the message before waiting is how a check reports "" and looks like a failure.
    iata_error = c.wait_js(
        "(function(){var f=document.getElementById('airlineIata').closest('.a-field');"
        "var m=f&&f.querySelector('.error-msg');"
        "return !!m && m.textContent.indexOf('already exists') !== -1;})()", timeout=25)
    walk.check("2: a taken IATA code is refused with an INLINE error on that field",
               iata_error, walk.text(c, "#%s .error-msg" % FORM))
    walk.check("2: and nothing was written", len(named("Zz Probe Dup %s" % TAG)) == 0)
    walk.note("(that sentence is the API's: 409 IATA_EXISTS — the client cannot know it)")

    walk.fill(c, {"airlineName": NAME_A, "airlineIata": "Z9"})
    walk.submit(c, FORM)
    walk.check("2: a duplicate NAME is refused inline (asked of the server, not of 5 rows)",
               c.wait_js("!!document.querySelector('#%s .error-msg')" % FORM, timeout=25)
               and "already exists" in walk.text(c, "#%s .error-msg" % FORM),
               walk.text(c, "#%s .error-msg" % FORM))
    walk.check("2: the modal stayed open on both refusals (nothing silently lost)",
               walk.modal_open(c, MODAL) and len(carriers()) == floor + 2)
    walk.click(c, "#modalCancel")

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching a name narrows the table to the server's answer",
               walk.search_until(c, "airlineSearch", TABLE, NAME_A, expect_count=1,
                                 expect_name=NAME_A),
               str(rows(c)))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "airlines" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "airlines" in u)[:200])
    walk.check("3: the count line is the filtered total", count_text(c) == "1 airline",
               count_text(c))

    iata_hits = [a["name"] for a in carriers("search=U4")]
    walk.check("3: searching an IATA code finds the carrier (the promise the API now keeps)",
               walk.search_until(c, "airlineSearch", TABLE, "U4", expect_count=1,
                                 expect_name="Buddha")
               and any("Buddha" in n for n in iata_hits),
               "page=%s api=%s" % ([r["name"] for r in rows(c)], iata_hits))
    walk.note("(AirlineRepository.searchAll matches name OR iata — one query)")

    walk.check("3: clearing the box restores the first server page",
               walk.search_until(c, "airlineSearch", TABLE, "", expect_count=PAGE_SIZE),
               "rows=%d" % len(rows(c)))

    walk.fill(c, {"statusFilter": "Inactive"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    inactive = carriers("status=Inactive")
    walk.check("3: the status filter is a query too (Inactive shows the Inactive carrier)",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, len(inactive)), timeout=25)
               and rows(c) and rows(c)[0]["name"] == NAME_B,
               str([(r["name"], r["status"]) for r in rows(c)]))
    walk.check("3: and its count is the server's filtered total",
               count_text(c) == "%d airline%s" % (len(inactive), "" if len(inactive) == 1 else "s"),
               "page=%r api=%d" % (count_text(c), len(inactive)))

    walk.fill(c, {"statusFilter": "ALL"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    walk.check("3: paging is server-side (%d airlines over %d-per-page = 2 pages)"
               % (floor + 2, PAGE_SIZE),
               c.wait_js("document.querySelectorAll('#pageBtns .page-btn').length >= 4",
                         timeout=25),
               "buttons=%s" % c.js("document.querySelectorAll('#pageBtns .page-btn').length"))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: page 2 shows the remainder and says so",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, (floor + 2) % PAGE_SIZE or PAGE_SIZE), timeout=25),
               "rows=%d info=%s" % (len(rows(c)), walk.text(c, "#pageInfo")))
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '1'; })[0];
        if (b) b.click();
    })()""")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE),
              timeout=25)

    # ============================================================== 4. UPDATE
    walk.step("§12 step 4 — UPDATE")
    walk.check("4: (setup) the row to edit is on screen with its stored description",
               walk.search_until(c, "airlineSearch", TABLE, NAME_A, expect_count=1,
                                 expect_name=NAME_A)
               and rows(c)[0]["desc"].startswith("Probe carrier A"),
               str([(r["name"], r["desc"]) for r in rows(c)])[:160])
    before = rows(c)[0]
    edit_id = ids(c)[0]

    # Change the row BEHIND the page's back, then open Edit. A modal filled from the
    # table's cached row would show the old text — the bug step 4 names.
    status, _ = walk.api("PUT", "/api/admin/airlines/%s" % edit_id, {
        "name": NAME_A, "iata": IATA_A, "description": "CHANGED BEHIND THE PAGE",
        "status": "Active", "logo": stored_a[0]["logo"] if stored_a else ""}, auth=True)
    walk.check("4: (setup) the row was changed through the API, not the page", status == 200)

    walk.click(c, '[data-edit="%s"]' % edit_id)
    walk.check("4: Edit opens pre-filled from a FRESH GET, not from the cached row",
               c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=25)
               and c.js("document.getElementById('airlineDesc').value")
               == "CHANGED BEHIND THE PAGE",
               "desc=%r (the table still shows %r)"
               % (c.js("document.getElementById('airlineDesc').value"), rows(c)[0]["desc"]))
    walk.check("4: while the TABLE still shows the stale text it was rendered with",
               rows(c)[0]["desc"] != "CHANGED BEHIND THE PAGE", rows(c)[0]["desc"])
    walk.check("4: the modal is titled Edit and carries the row's id",
               walk.text(c, "#modalTitle") == "Edit Airline"
               and str(c.js("document.getElementById('airlineId').value")) == str(edit_id))

    walk.fill(c, {"airlineName": NAME_A_EDITED, "airlineDesc": "Edited in the browser"})
    walk.submit(c, FORM)
    walk.check("4: saving PUTs and closes the modal",
               c.wait_js("document.getElementById('%s').hidden === true" % MODAL, timeout=25),
               walk.toast(c))
    stored = named(NAME_A_EDITED)
    walk.check("4: THE DATABASE has the new name and description",
               len(stored) == 1 and stored[0]["description"] == "Edited in the browser",
               json.dumps(stored)[:200])
    walk.check("4: the old name is gone (one row, not two)", len(named(NAME_A)) == 0)

    walk.check("4: (setup) the row is Active before the toggle",
               walk.search_until(c, "airlineSearch", TABLE, NAME_A_EDITED, expect_count=1,
                                 expect_name=NAME_A_EDITED)
               and rows(c)[0]["status"] == "Active",
               str([(r["name"], r["status"]) for r in rows(c)]))
    row_id = ids(c)[0]
    walk.click(c, '[data-toggle="%s"]' % row_id)
    walk.check("4: the toggle writes through the API and comes back Inactive",
               c.wait_js("document.querySelector('#%s tr td:nth-child(5)')"
                         ".textContent.trim() === 'Inactive'" % TABLE, timeout=25)
               and named(NAME_A_EDITED)[0]["status"] == "Inactive",
               json.dumps(named(NAME_A_EDITED))[:160])
    walk.click(c, '[data-toggle="%s"]' % row_id)
    walk.check("4: and back to Active",
               c.wait_js("document.querySelector('#%s tr td:nth-child(5)')"
                         ".textContent.trim() === 'Active'" % TABLE, timeout=25)
               and named(NAME_A_EDITED)[0]["status"] == "Active")

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE")
    walk.stub_confirm(c)
    walk.check("5: (setup) the carrier is the only row on screen",
               walk.search_until(c, "airlineSearch", TABLE, NAME_A_EDITED, expect_count=1,
                                 expect_name=NAME_A_EDITED))
    walk.click(c, '[data-delete="%s"]' % row_id)
    # The page asks about usage FIRST (an async flights read), so the stub is waited for.
    walk.check("5: it asks for confirmation first, naming the airline",
               c.wait_js("window.__confirms.length > 0", timeout=25)
               and NAME_A_EDITED in walk.confirms(c)[0], str(walk.confirms(c))[:160])
    walk.check("5: the row is gone from the DATABASE",
               walk.wait_db(lambda: len(named(NAME_A_EDITED)) == 0),
               json.dumps(named(NAME_A_EDITED))[:120])
    walk.check("5: the list refreshed from the backend after the delete",
               c.wait_js("document.querySelectorAll('#%s tr').length === 0" % TABLE, timeout=25),
               str(rows(c)))
    walk.check("5: and the toast is the page's own success message",
               "deleted" in walk.toast(c), walk.toast(c))

    # The refusal path: a carrier that flights use must not be deleted.
    walk.fill(c, {"statusFilter": "ALL"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE, timeout=25)
    walk.search_until(c, "airlineSearch", TABLE, "Buddha", expect_count=1, expect_name="Buddha")
    buddha = rows(c)[0]
    buddha_id = ids(c)[0]
    _, used = walk.api("GET", "/api/admin/flights?airlineId=%s" % buddha_id, auth=True)
    used_count = len((used or {}).get("flights") or [])
    walk.click(c, '[data-delete="%s"]' % buddha_id)
    walk.check("5: an airline flights use is refused with the API's own advice",
               walk.wait_content(c, "#toast", "disable it instead", timeout=25)
               and ("%d flight" % used_count) in walk.toast(c),
               "%s (flights on it: %d)" % (walk.toast(c), used_count))
    walk.check("5: and that row is still in the database", len(named(buddha["name"])) == 1)

    walk.check("5: (setup) the second carrier is the only row on screen",
               walk.search_until(c, "airlineSearch", TABLE, NAME_B, expect_count=1,
                                 expect_name=NAME_B))
    walk.click(c, '[data-delete="%s"]' % ids(c)[0])
    walk.check("5: the second probe airline is deleted too",
               walk.wait_db(lambda: len(named(NAME_B)) == 0)
               and c.wait_js("document.querySelectorAll('#%s tr').length === 0" % TABLE,
                             timeout=25),
               json.dumps(named(NAME_B))[:120])

    # ---------------------------------------------------------------- tidy up
    walk.step("tidy up")
    leftover = sweep()
    if leftover:
        walk.note("removed %d carrier(s) the UI delete did not reach: %s"
                  % (len(leftover), ", ".join(leftover)))
    final = len(carriers())
    walk.check("the demo database is back where it started (%d airlines)" % floor,
               final == floor, "now=%d was=%d" % (final, floor))

    # The duplicate-IATA test above makes the browser log ONE expected 409 — the
    # server refusing the write and the page surfacing it, which is the behaviour
    # under test. Everything else must be clean.
    walk.drain(c)
    return walk.finish(expected_failures=[
        "409 %s/api/admin/airlines" % BASE,
        "status of 409",
    ])


if __name__ == "__main__":
    sys.exit(main())
