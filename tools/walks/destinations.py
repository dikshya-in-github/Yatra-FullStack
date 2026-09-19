#!/usr/bin/env python3
"""§12/§13 — the Destinations module, walked through the plan's five-step workflow.

The second module on the shared harness (`tools/module_check.py`), and the first that
exercises a write the others do not: the multipart image upload. Two things make it
different from the Airlines walk, and both are the point of doing modules one at a
time rather than by pattern-matching:

  * the upload is a REAL Cloudinary upload (a 1x1 PNG posted through
    `POST /api/admin/destinations/image`), so the row's `imageUrl` is asserted to be
    a `res.cloudinary.com` URL — the page cannot pass that by inventing a data URL;
  * the delete refusal is the API's own 409 rather than a page-side guess, so the
    walk counts a destination's flights the way the server does, with the `from`/`to`
    queries the flights API already exposes.

Run it with `tools/module-check.sh walks/destinations.py`. It leaves the demo
database at its seeded floor: both probe destinations are deleted through the same UI
at the end (which also hands their Cloudinary assets back), and leftovers from an
interrupted run are swept first.
"""

import base64
import json
import os
import sys
import time

sys.path.insert(0, os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
from module_check import BASE, ROOT, Walk  # noqa: E402

TAG = str(int(time.time()))[-6:]
CITY_A = "Zz Probe City %s" % TAG
CITY_B = "Zz Probe Port %s" % TAG
CODE_A, CODE_B = "ZQA", "ZQB"          # three letters: the shape the API validates
AIRPORT_A = "Zz Probe Airport %s" % TAG
AIRPORT_B = "Zz Probe Harbour %s" % TAG
EDITED_DESC = "Edited by tools/walks/destinations.py"
BEHIND_THE_PAGE = "CHANGED BEHIND THE PAGE"
PAGE_SIZE = 8

TABLE = "destTableBody"
MODAL = "destModal"
FORM = "destForm"
CONFIRM = "confirmModal"

# A 1x1 transparent PNG: real bytes Cloudinary accepts, small enough to be free.
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
    "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
PNG_PATH = ROOT / "target" / "probe-destination.png"

walk = Walk("destinations")


def destinations(query=""):
    return walk.get_list("/api/admin/destinations", "destinations", query)


def named(city):
    return [d for d in destinations() if d.get("city") == city]


def ids(c, attr="data-edit"):
    return walk.row_attr(c, TABLE, attr)


def rows(c):
    return walk.table(c, TABLE, {"city": 2, "airport": 3, "code": 4, "desc": 5,
                                 "status": 6})


def count_text(c):
    return walk.text(c, "#resultCount").strip()


def page_info(c):
    return walk.text(c, "#pageInfo").strip()


def img_srcs(c):
    """Every <img> the table rendered — where an image actually comes from."""
    return json.loads(c.js(
        "JSON.stringify(Array.from(document.querySelectorAll('#%s img'))"
        ".map(function (i) { return i.getAttribute('src') || ''; }))" % TABLE))


def search(c, term, expect_count=None, expect_city=None):
    return walk.search_until(c, "destSearch", TABLE, term, expect_count=expect_count,
                             expect_name=expect_city)


def sweep():
    """Delete any probe destination an earlier interrupted run left behind."""
    gone = []
    for d in destinations():
        if str(d.get("city", "")).startswith("Zz Probe"):
            walk.api("DELETE", "/api/admin/destinations/%d" % d["id"], auth=True)
            gone.append(d["city"])
    return gone


def open_modal(c):
    walk.click(c, "#addDestBtn")
    return c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=8)


def fill(c, city, code, airport, desc="", status="Active"):
    walk.fill(c, {"destCity": city, "destCode": code, "destAirport": airport,
                  "destDesc": desc, "destStatus": status})


def create(c, city, code, airport, desc, status, image=False):
    """Add through the modal. `image=True` uploads the probe PNG first."""
    open_modal(c)
    fill(c, city, code, airport, desc, status)
    if image:
        walk.set_file_input(c, "#imgInput", PNG_PATH)
        uploaded = c.wait_js(
            "(function(){var i=document.querySelector('#imgPreview img');"
            "return !!i && (i.getAttribute('src')||'').indexOf('res.cloudinary.com') !== -1;})()",
            timeout=60)
        if not uploaded:
            return False, "preview never showed a Cloudinary URL"
    walk.submit(c, FORM)
    closed = c.wait_js("document.getElementById('%s').hidden === true" % MODAL, timeout=30)
    return closed, walk.toast(c)


def flights_using(code):
    """A destination's usage, counted the way the SERVER counts it — two queries, not
    a full flights read filtered in the browser."""
    total = 0
    for side in ("from", "to"):
        _, body = walk.api("GET", "/api/admin/flights?%s=%s&size=200" % (side, code),
                           auth=True)
        total += len((body or {}).get("flights") or [])
    return total


def main():
    if not walk.login_admin():
        walk.check("setup: the admin API token", False)
        return 1

    PNG_PATH.write_bytes(PNG)
    stale = sweep()
    if stale:
        walk.note("removed %d leftover probe destination(s): %s" % (len(stale), ", ".join(stale)))
    floor = len(destinations())
    walk.note("the database holds %d destinations before this walk (the seeded floor)" % floor)

    # The guard the first Airlines run's failures came from: this must be the build under test.
    if not walk.assert_serving_working_tree(
            assets=["assets/js/admin-destinations.js"],
            markers=[("admin-destinations.html", "Add Destination")]):
        return 1

    c = walk.browser()
    walk.sign_in_admin(c)

    # ================================================================ 1. OPEN
    walk.step("§12 step 1 — OPEN (real data, not the mock's seed)")
    c.goto(BASE + "/admin-destinations.html")
    walk.check("1: the table rendered rows from the backend",
               c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE, timeout=25))
    walk.check("1: the page really called the destinations API (its own resource timings)",
               any("destinations" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "destinations" in u)[:200])
    walk.check("1: the count line reports the DATABASE's total",
               count_text(c) == "%d destinations" % floor, "page=%r db=%d" % (count_text(c), floor))
    walk.check("1: paging is server-side (%d destinations over %d-per-page = 2 pages)"
               % (floor, PAGE_SIZE),
               c.wait_js("document.querySelectorAll('#pageBtns .page-btn').length >= 4", timeout=25)
               and "of %d destinations" % floor in page_info(c),
               "buttons=%s info=%r" % (c.js("document.querySelectorAll('#pageBtns .page-btn').length"),
                                       page_info(c)))
    walk.check("1: the page-private localStorage store is gone (nothing wrote it)",
               c.js("!localStorage.getItem('yatra_admin_destinations')"))
    walk.check("§13: neither modal is open on load",
               not walk.modal_open(c, MODAL) and not walk.modal_open(c, CONFIRM))
    walk.check("1: no image on screen comes from a bundled asset path (the mock's assets/imgs/*)",
               not any("assets/imgs" in s for s in img_srcs(c)), str(img_srcs(c))[:160])
    walk.note("(the seeded airports carry no image_url at all — %d <img> in this table — so"
              " step 2 uploads a real one and this table gains a Cloudinary src)"
              % len(img_srcs(c)))

    # ============================================================== 2. CREATE
    walk.step("§12 step 2 — CREATE")
    walk.check("2: \"Add Destination\" opens the modal (and only the click did)", open_modal(c))
    walk.check("2: it opens as Add, with empty fields",
               walk.text(c, "#modalTitle") == "Add Destination"
               and c.js("document.getElementById('destCity').value") == ""
               and c.js("document.getElementById('destId').value") == "")

    walk.submit(c, FORM)
    walk.check("2: required-field validation shows INLINE errors",
               c.wait_js("document.querySelectorAll('#%s .error-msg').length >= 3" % FORM,
                         timeout=8),
               "errors=%s" % c.js("document.querySelectorAll('#%s .error-msg').length" % FORM))
    walk.check("2: and writes nothing", len(destinations()) == floor)

    walk.click(c, "#modalCancel")
    walk.check("2: Cancel closes the modal without saving",
               not walk.modal_open(c, MODAL) and len(destinations()) == floor)
    open_modal(c)
    walk.click(c, "#modalClose")
    walk.check("2: the × closes it too, saving nothing",
               not walk.modal_open(c, MODAL) and len(destinations()) == floor)

    # --- the upload, and the CREATE that carries it
    closed, toast = create(c, CITY_A, CODE_A, AIRPORT_A,
                           "Probe destination — tools/walks/destinations.py",
                           "Active", image=True)
    walk.check("2: the file button uploads to Cloudinary and the preview shows the URL",
               closed, toast)
    stored = named(CITY_A)
    walk.check("2: THE DATABASE has the row (read back through the API, not the page)",
               len(stored) == 1 and stored[0]["code"] == CODE_A
               and stored[0]["status"] == "Active", json.dumps(stored)[:200])
    walk.check("2: and its image is the Cloudinary URL the upload returned, not a data URL",
               len(stored) == 1
               and str(stored[0].get("imageUrl", "")).startswith("https://res.cloudinary.com/"),
               json.dumps(stored)[:220])
    walk.check("2: the toast names the new destination and the list refreshed",
               CITY_A in toast and "added" in toast
               and count_text(c) == "%d destinations" % (floor + 1), "%r %r" % (toast, count_text(c)))

    # --- the image is now on screen, from Cloudinary
    walk.check("2: the table renders that image as an <img> from Cloudinary",
               search(c, CITY_A, expect_count=1, expect_city=CITY_A)
               and img_srcs(c)
               and all(s.startswith("https://res.cloudinary.com/") for s in img_srcs(c)),
               str(img_srcs(c))[:200])

    # --- a second probe destination, no image, Inactive: the status filter and the
    #     paging checks need a second page and a second state.
    closed_b, _ = create(c, CITY_B, CODE_B, AIRPORT_B, "Probe destination B", "Inactive")
    walk.check("2: a second destination lands too (no image, Inactive)",
               closed_b and len(named(CITY_B)) == 1
               and named(CITY_B)[0]["status"] == "Inactive"
               and named(CITY_B)[0]["imageUrl"] == "", json.dumps(named(CITY_B))[:200])

    # --- duplicates are the SERVER's answer: this page holds one page of rows
    open_modal(c)
    fill(c, "Zz Probe Dup %s" % TAG, CODE_A, AIRPORT_B)
    walk.submit(c, FORM)
    code_error = c.wait_js(
        "(function(){var f=document.getElementById('destCode').closest('.a-field');"
        "var m=f&&f.querySelector('.error-msg');"
        "return !!m && m.textContent.indexOf('already used') !== -1;})()", timeout=25)
    walk.check("2: a taken airport code is refused with an INLINE error on that field",
               code_error, walk.text(c, "#%s .error-msg" % FORM))
    walk.check("2: and nothing was written", len(named("Zz Probe Dup %s" % TAG)) == 0)
    walk.note("(that sentence is the API's: 409 DESTINATION_CODE_EXISTS)")

    fill(c, CITY_A, "ZQC", AIRPORT_B)
    walk.submit(c, FORM)
    city_error = c.wait_js(
        "(function(){var f=document.getElementById('destCity').closest('.a-field');"
        "var m=f&&f.querySelector('.error-msg');"
        "return !!m && m.textContent.indexOf('already exists') !== -1;})()", timeout=25)
    walk.check("2: a duplicate CITY is refused inline on its own field", city_error,
               walk.text(c, "#%s .error-msg" % FORM))
    walk.note("(409 DESTINATION_CITY_EXISTS — no database constraint behind it, and the"
              " page can no longer see every row, so the server owns the check)")
    walk.check("2: the modal stayed open on both refusals (nothing silently lost)",
               walk.modal_open(c, MODAL) and len(destinations()) == floor + 2)
    walk.click(c, "#modalCancel")

    # ================================================================ 3. READ
    walk.step("§12 step 3 — READ/LIST (queries, not array filters)")
    walk.check("3: searching a city narrows the table to the server's answer",
               search(c, CITY_A, expect_count=1, expect_city=CITY_A), str(rows(c)))
    walk.check("3: and the count line is the filtered total", count_text(c) == "1 destination",
               count_text(c))
    walk.check("3: the request carried the term (it was not filtered in the browser)",
               any("search=" in u and "destinations" in u for u in walk.resources(c)),
               "; ".join(u for u in walk.resources(c) if "destinations" in u)[:200])

    walk.check("3: searching the CODE finds the same row (the placeholder's promise)",
               search(c, CODE_A, expect_count=1, expect_city=CITY_A), str(rows(c)))
    walk.check("3: searching the AIRPORT NAME finds it too",
               search(c, "Zz Probe Airport", expect_count=1, expect_city=CITY_A), str(rows(c)))
    walk.note("(DestinationRepository.searchAll matches city OR airport OR code — one query)")

    walk.check("3: clearing the box restores the first server page",
               search(c, "", expect_count=PAGE_SIZE), "rows=%d" % len(rows(c)))

    walk.fill(c, {"statusFilter": "Inactive"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    inactive = destinations("status=Inactive")
    walk.check("3: the status filter is a query too (Inactive shows the Inactive row)",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, len(inactive)), timeout=25)
               and rows(c) and CITY_B in [r["city"] for r in rows(c)],
               str([(r["city"], r["status"]) for r in rows(c)]))
    walk.check("3: and its count is the server's filtered total",
               count_text(c) == "%d destination%s" % (len(inactive), "" if len(inactive) == 1 else "s"),
               "page=%r api=%d" % (count_text(c), len(inactive)))
    walk.check("3: every row it shows really is Inactive",
               all(r["status"] == "Inactive" for r in rows(c)), str(rows(c))[:200])

    walk.fill(c, {"statusFilter": "ALL"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    c.wait_js("document.querySelectorAll('#%s tr').length === %d" % (TABLE, PAGE_SIZE), timeout=25)
    c.js("""(function () {
        var b = Array.from(document.querySelectorAll('#pageBtns .page-btn'))
          .filter(function (x) { return x.textContent.trim() === '2'; })[0];
        if (b) b.click();
    })()""")
    walk.check("3: page 2 is the server's remainder, and the info line says so",
               c.wait_js("document.querySelectorAll('#%s tr').length === %d"
                         % (TABLE, (floor + 2) % PAGE_SIZE or PAGE_SIZE), timeout=25)
               and ("of %d destinations" % (floor + 2)) in page_info(c),
               "rows=%d info=%r" % (len(rows(c)), page_info(c)))
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
               search(c, CITY_A, expect_count=1, expect_city=CITY_A)
               and rows(c)[0]["desc"].startswith("Probe destination"),
               str([(r["city"], r["desc"]) for r in rows(c)])[:160])
    edit_id = ids(c)[0]

    # Change the row BEHIND the page's back, then open Edit. A modal filled from the
    # table's cached row would show the old text — the bug step 4 names.
    current = named(CITY_A)
    status, _ = walk.api("PUT", "/api/admin/destinations/%s" % edit_id, {
        "city": CITY_A, "code": CODE_A, "airport": AIRPORT_A,
        "description": BEHIND_THE_PAGE,
        "imageUrl": current[0]["imageUrl"] if current else "",
        "status": "Active"}, auth=True)
    walk.check("4: (setup) the row was changed through the API, not the page", status == 200)

    walk.click(c, '[data-edit="%s"]' % edit_id)
    walk.check("4: Edit opens pre-filled from a FRESH GET, not from the cached row",
               c.wait_js("document.getElementById('%s').hidden === false" % MODAL, timeout=25)
               and c.js("document.getElementById('destDesc').value") == BEHIND_THE_PAGE,
               "desc=%r (the table still shows %r)"
               % (c.js("document.getElementById('destDesc').value"), rows(c)[0]["desc"]))
    walk.check("4: while the TABLE still shows the stale text it was rendered with",
               rows(c)[0]["desc"] != BEHIND_THE_PAGE, rows(c)[0]["desc"])
    walk.check("4: the modal is titled Edit and carries the row's id",
               walk.text(c, "#modalTitle") == "Edit Destination"
               and str(c.js("document.getElementById('destId').value")) == str(edit_id))
    walk.check("4: the image URL came back with the row (so an untouched image is kept)",
               str(c.js("document.getElementById('destImageUrl').value"))
               .startswith("https://res.cloudinary.com/"),
               str(c.js("document.getElementById('destImageUrl').value"))[:120])

    walk.fill(c, {"destAirport": AIRPORT_A + " Edited", "destDesc": EDITED_DESC})
    walk.submit(c, FORM)
    walk.check("4: saving PUTs and closes the modal",
               c.wait_js("document.getElementById('%s').hidden === true" % MODAL, timeout=25),
               walk.toast(c))
    stored = named(CITY_A)
    walk.check("4: THE DATABASE has the new airport and description",
               len(stored) == 1 and stored[0]["airport"] == AIRPORT_A + " Edited"
               and stored[0]["description"] == EDITED_DESC, json.dumps(stored)[:220])
    walk.check("4: and the image it did not touch is still the same Cloudinary URL",
               len(stored) == 1
               and str(stored[0].get("imageUrl", "")).startswith("https://res.cloudinary.com/"),
               json.dumps(stored)[:220])
    walk.note("(the pair rule: the same URL with no public id keeps the stored one, so an"
              " edit that ignores the image cannot orphan the asset)")

    walk.check("4: (setup) the row is Active before the toggle",
               search(c, CITY_A, expect_count=1, expect_city=CITY_A)
               and rows(c)[0]["status"] == "Active",
               str([(r["city"], r["status"]) for r in rows(c)]))
    row_id = ids(c)[0]
    walk.click(c, '[data-toggle="%s"]' % row_id)
    walk.check("4: the toggle writes through the API and comes back Inactive",
               c.wait_js("document.querySelector('#%s tr td:nth-child(6)'"
                         ").textContent.trim() === 'Inactive'" % TABLE, timeout=25)
               and named(CITY_A)[0]["status"] == "Inactive",
               json.dumps(named(CITY_A))[:160])
    walk.click(c, '[data-toggle="%s"]' % row_id)
    walk.check("4: and back to Active",
               c.wait_js("document.querySelector('#%s tr td:nth-child(6)'"
                         ").textContent.trim() === 'Active'" % TABLE, timeout=25)
               and named(CITY_A)[0]["status"] == "Active")

    # ============================================================== 5. DELETE
    walk.step("§12 step 5 — DELETE/DISABLE")
    walk.check("5: (setup) the probe destination is the only row on screen",
               search(c, CITY_A, expect_count=1, expect_city=CITY_A))

    walk.click(c, '[data-delete="%s"]' % ids(c)[0])
    walk.check("5: it asks for confirmation first, naming the destination",
               c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
               and CITY_A in walk.text(c, "#confirmMsg")
               and CODE_A in walk.text(c, "#confirmMsg"),
               walk.text(c, "#confirmMsg")[:160])
    walk.click(c, "#confirmNo")
    walk.check("5: declining leaves the row exactly where it was",
               len(named(CITY_A)) == 1 and not walk.modal_open(c, CONFIRM))

    walk.click(c, '[data-delete="%s"]' % ids(c)[0])
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
    walk.click(c, "#confirmYes")
    walk.check("5: the row is gone from the DATABASE",
               walk.wait_db(lambda: len(named(CITY_A)) == 0), json.dumps(named(CITY_A))[:120])
    walk.check("5: the list refreshed from the backend after the delete",
               c.wait_js("document.querySelectorAll('#%s tr').length === 0" % TABLE, timeout=25),
               str(rows(c)))
    walk.check("5: and the toast is the page's own success message",
               "deleted" in walk.toast(c), walk.toast(c))

    # The refusal path: an airport a flight's route uses must not be deleted, and the
    # message (with the count) is the API's.
    walk.fill(c, {"statusFilter": "ALL"})
    c.js("document.getElementById('statusFilter')"
         ".dispatchEvent(new Event('change', {bubbles:true}))")
    c.wait_js("document.querySelectorAll('#%s tr').length > 0" % TABLE, timeout=25)
    search(c, "Kathmandu", expect_count=1, expect_city="Kathmandu")
    kathmandu_id = ids(c)[0]
    used = flights_using("KTM")
    walk.note("(counted with the flights API's own from=/to= queries: %d flight(s))" % used)
    walk.click(c, '[data-delete="%s"]' % kathmandu_id)
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
    walk.click(c, "#confirmYes")
    walk.check("5: an airport flights use is refused with the API's own advice",
               walk.wait_content(c, "#toast", "disable it instead", timeout=25)
               and ("%d flight" % used) in walk.toast(c),
               "%s (flights on it: %d)" % (walk.toast(c), used))
    walk.check("5: and that row is still in the database", len(named("Kathmandu")) == 1)

    walk.check("5: (setup) the second probe destination is the only row on screen",
               search(c, CITY_B, expect_count=1, expect_city=CITY_B))
    walk.click(c, '[data-delete="%s"]' % ids(c)[0])
    c.wait_js("document.getElementById('%s').hidden === false" % CONFIRM, timeout=25)
    walk.click(c, "#confirmYes")
    walk.check("5: the second probe destination is deleted too",
               walk.wait_db(lambda: len(named(CITY_B)) == 0)
               and c.wait_js("document.querySelectorAll('#%s tr').length === 0" % TABLE,
                             timeout=25),
               json.dumps(named(CITY_B))[:120])

    # ---------------------------------------------------------------- tidy up
    walk.step("tidy up")
    leftover = sweep()
    if leftover:
        walk.note("removed %d destination(s) the UI delete did not reach: %s"
                  % (len(leftover), ", ".join(leftover)))
    final = len(destinations())
    walk.check("the demo database is back where it started (%d destinations)" % floor,
               final == floor, "now=%d was=%d" % (final, floor))
    PNG_PATH.unlink(missing_ok=True)

    # The duplicate-code and duplicate-city refusals above make the browser log two
    # expected 409s, plus the delete refusal — the server declining and the page
    # surfacing it, which is the behaviour under test. Everything else must be clean.
    walk.drain(c)
    return walk.finish(expected_failures=[
        "409 %s/api/admin/destinations" % BASE,
        "status of 409",
    ])


if __name__ == "__main__":
    sys.exit(main())
