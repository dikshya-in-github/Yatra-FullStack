#!/usr/bin/env python3
"""The shared harness for fix-plan §12/§13's admin-module walks.

§12's unit of work is one module's whole five-step workflow (OPEN, CREATE,
READ/LIST, UPDATE, DELETE/DISABLE), verified against the LIVE database — a page's own
toast is not evidence that a row exists. This module holds everything that is the
same for every module, so the five that remain (Destinations, Bookings, Users,
Payments, Tickets) do not each rediscover the same two traps:

   1. THE BUILD UNDER TEST.  A walk that runs against a stale jar tests the OLD page.
      That happened once: the runner only built when the jar was missing, so a run
      tested the previous page in mock mode, and the failures looked like bugs in the
      new code. Two guards here, and the first is the strong one:
        * `Walk.assert_serving_working_tree(assets=[...])` — fetch each static asset
          the page loads from the running app and compare the BYTES with the file on
          disk. That is direct proof the server is serving this working tree, not a
          jar from an hour ago. A template is checked by marker (`markers=[...]`),
          since Thymeleaf may rewrite it.
        * `walk.note_build_age()` — the cheap hint (jar mtime vs newest source file).
   2. THE WAIT THAT CANNOT FAIL.  Waiting for a row COUNT passes vacuously when two
      different searches happen to return the same count, and then the next assertion
      reads the PREVIOUS result. Every wait in here is therefore keyed on CONTENT:
      `Walk.search_until(...)` takes the name it expects and waits for that name to
      appear in the table, and `Walk.wait_content(...)` is the general form.

Usage, per module:

    from module_check import Walk           # (walks/=<dir> adds its parent to sys.path)

    walk = Walk("airlines")                 # names the report and the log
    walk.assert_serving_working_tree(
        assets=["assets/js/admin-airlines.js"],
        markers=[("admin-airlines.html", "Add Airline")])
    c = walk.browser()                      # headless Chrome + the same-origin trap
    walk.sign_in_admin(c)
    ...  walk.step("§12 step 1 — OPEN"); walk.check("...", ok) ...
    sys.exit(walk.finish(expected_failures=["409 http://127.0.0.1:8081/api/admin/x"]))

Finish with `walk.sweep(...)` for anything the walk creates, so the demo database is
left at its seeded floor — these walks run against the shared demo database.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cdp import CDP, WS, api_trap, new_target  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
BASE = os.environ.get("YATRA_BASE", "http://127.0.0.1:8081")
DEBUG = os.environ.get("YATRA_CHROME", "http://127.0.0.1:9222")

ADMIN_EMAIL = os.environ.get("YATRA_ADMIN_EMAIL", "admin@gmail.com")
ADMIN_PASSWORD = os.environ.get("YATRA_ADMIN_PASSWORD", "admin")


class Walk:
    def __init__(self, name):
        self.name = name
        self.results = []
        self.token = None
        self._started = time.time()

    # ---------------------------------------------------------------- reporting
    def step(self, title):
        print()
        print("== %s ==" % title)
        sys.stdout.flush()

    def check(self, name, ok, detail=""):
        self.results.append((bool(ok), name, str(detail)))
        print(("ok   " if ok else "FAIL ") + name
              + ("" if ok else "   [" + str(detail)[:300] + "]"))
        sys.stdout.flush()
        return bool(ok)

    def note(self, text):
        print("     " + text)
        sys.stdout.flush()

    def finish(self, expected_failures=()):
        """Print the summary and return an exit code. `expected_failures` are the
        4xx/5xx lines a walk ASKS FOR on purpose (e.g. a duplicate-code refusal the
        page must surface); they are filtered out and the count is reported."""
        probs = self._probs[:]
        allowed = [p for p in probs if any(x in p for x in expected_failures)]
        unexpected = [p for p in probs if p not in allowed]
        if allowed:
            self.note("(%d expected failure(s) filtered — the refusals this walk asks for: %s)"
                      % (len(allowed), ", ".join(expected_failures)))
        self.check("no unexpected JS exceptions or asset failures during the walk",
                   not unexpected, "; ".join(unexpected[:6]))

        failed = [r for r in self.results if not r[0]]
        print()
        print("%d passed, %d failed  (%s walk, %.0fs)"
              % (len(self.results) - len(failed), len(failed), self.name,
                 time.time() - self._started))
        for _, name, detail in failed:
            print("  - %s   [%s]" % (name, str(detail)[:200]))
        return 1 if failed else 0

    _probs = []

    # ---------------------------------------------------------------- the API
    def api(self, method, path, body=None, auth=False):
        """The database's own answer, read straight from python.

        Every write a walk makes is verified through here, never through the page.
        """
        req = urllib.request.Request(BASE + path, method=method)
        req.add_header("Accept", "application/json")
        if auth and self.token:
            req.add_header("Authorization", "Bearer " + self.token)
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

    def fetch(self, path):
        """A raw GET — for the endpoints that answer bytes rather than JSON (logos).

        Returns (status, content-type, body).
        """
        req = urllib.request.Request(BASE + path)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.headers.get("Content-Type", ""), r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Content-Type", ""), e.read()

    def get_list(self, path, key, query="", size=200):
        """A list read-back, AUTHENTICATED by default.

        The admin module lists live under `/api/admin/**`, so an unauthenticated read
        is a 403 whose body has no `key` — which returns `[]`, and a walk that trusts
        it then reports the database as empty and fails its own correct assertions.
        The Airlines walk hid this for a module because `/api/airlines` is public.
        A token is harmless on the public routes, so it goes on every read.
        """
        joiner = "&" if "?" in path else "?"
        _, body = self.api("GET", "%s%s%s&size=%d" % (path, joiner, query, size),
                           auth=True)
        return (body or {}).get(key) or []

    def login_admin(self):
        status, body = self.api("POST", "/api/auth/login",
                                {"loginId": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
        self.token = (body or {}).get("token")
        return status == 200 and bool(self.token)

    def wait_db(self, predicate, timeout=20):
        """Poll the API until the database agrees — a page's write is async."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.4)
        return False

    # ---------------------------------------------------------------- the build
    def note_build_age(self):
        """The cheap half of the stale-build guard: is the jar older than the code?"""
        jar = ROOT / "target" / "Yatra-0.0.1-SNAPSHOT.jar"
        if not jar.exists():
            self.note("build age: no jar found (the runner packages one)")
            return
        newest, newest_path = 0.0, ""
        for path in (ROOT / "src" / "main").rglob("*"):
            if path.is_file() and path.stat().st_mtime > newest:
                newest, newest_path = path.stat().st_mtime, str(path.relative_to(ROOT))
        if jar.stat().st_mtime < newest:
            self.note("WARNING: the jar (%s) is OLDER than %s — it may be a stale build;"
                      " re-run through tools/module-check.sh, which packages first"
                      % (time.strftime("%H:%M:%S", time.localtime(jar.stat().st_mtime)),
                         newest_path))
        else:
            self.note("build age: the jar is newer than every file under src/main")

    def assert_serving_working_tree(self, assets=(), markers=()):
        """Prove the running app serves THIS working tree — the real stale-build guard.

        `assets` are static files compared byte-for-byte with the copy on disk;
        `markers` are (template, substring) pairs, for pages Thymeleaf may rewrite.
        """
        self.note_build_age()
        mismatched = []
        for asset in assets:
            local = ROOT / "src" / "main" / "resources" / "static" / asset
            if not local.exists():
                mismatched.append("%s is not on disk" % asset)
                continue
            try:
                with urllib.request.urlopen("%s/%s" % (BASE, asset), timeout=30) as r:
                    served = r.read()
            except Exception as ex:
                mismatched.append("%s could not be fetched (%s)" % (asset, ex))
                continue
            if served != local.read_bytes():
                mismatched.append("%s DIFFERS from disk (%d served vs %d on disk)"
                                  % (asset, len(served), local.stat().st_size))

        self.check("the running app is serving this working tree, not a stale build",
                   not mismatched, "; ".join(mismatched))
        if mismatched:
            self.note("refusing to continue: a walk against an older build tests the "
                      "OLD page. Run it through tools/module-check.sh (it packages "
                      "first), or ./mvnw -q package -DskipTests and re-run.")
            return False

        for template, marker in markers:
            local = ROOT / "src" / "main" / "resources" / "templates" / template
            try:
                with urllib.request.urlopen("%s/%s" % (BASE, template), timeout=30) as r:
                    page = r.read().decode("utf-8", "replace")
            except Exception as ex:
                self.check("%s is served" % template, False, str(ex))
                continue
            self.check("%s is served from this working tree (marker: %r)"
                       % (template, marker), marker in page and marker in local.read_text(),
                       "served=%s local=%s" % (marker in page, marker in local.read_text()))
        return True

    # ---------------------------------------------------------------- the browser
    def browser(self, width=1440, height=1000):
        t = new_target(DEBUG)
        c = CDP(WS(t["webSocketDebuggerUrl"]))
        for domain in ("Page.enable", "Network.enable", "Runtime.enable", "Log.enable"):
            c.cmd(domain)
        c.cmd("Page.addScriptToEvaluateOnNewDocument", {"source": api_trap(BASE)})
        c.cmd("Emulation.setDeviceMetricsOverride",
              {"width": width, "height": height, "deviceScaleFactor": 1, "mobile": False})
        self.c = c
        return c

    def sign_in_admin(self, c=None):
        c = c or self.c
        c.goto(BASE + "/admin-login.html")
        c.js("""(function () {
            var i = document.getElementById('adminId'), p = document.getElementById('adminPassword');
            i.value = %s; p.value = %s;
            i.dispatchEvent(new Event('input', {bubbles:true}));
            p.dispatchEvent(new Event('input', {bubbles:true}));
            document.getElementById('adminLoginForm')
              .dispatchEvent(new Event('submit', {cancelable:true, bubbles:true}));
        })()""" % (json.dumps(ADMIN_EMAIL), json.dumps(ADMIN_PASSWORD)))
        return self.check("setup: an admin signed in against the real API",
                          c.wait_js("!!sessionStorage.getItem('yatra_admin_session')",
                                    timeout=30))

    def drain(self, c=None):
        """Collect browser-side failures. Nothing is filtered here on purpose: the
        refusals a walk ASKS FOR are declared by that walk in `finish()`, where the
        intent is visible, rather than hidden in this base."""
        c = c or self.c
        self._probs = c.drain() + self._probs
        return self._probs

    # ---------------------------------------------------------------- page helpers
    @staticmethod
    def fill(c, values):
        for element_id, value in values.items():
            c.js("(function(){var e=document.getElementById(%s);e.value=%s;"
                 "e.dispatchEvent(new Event('input',{bubbles:true}));})()"
                 % (json.dumps(element_id), json.dumps(value)))

    @staticmethod
    def submit(c, form_id):
        c.js("document.getElementById(%s)"
             ".dispatchEvent(new Event('submit', {cancelable:true, bubbles:true}))"
             % json.dumps(form_id))

    @staticmethod
    def click(c, selector):
        c.js("document.querySelector(%s).click()" % json.dumps(selector))

    @staticmethod
    def text(c, selector):
        return str(c.js("(function(){var e=document.querySelector(%s);"
                        "return e ? e.textContent : '';})()" % json.dumps(selector)) or "")

    @staticmethod
    def toast(c):
        return Walk.text(c, "#toast")

    @staticmethod
    def modal_open(c, modal_id):
        return c.js("document.getElementById(%s).hidden === false" % json.dumps(modal_id))

    def table(self, c, table_id, columns):
        """The visible rows, keyed by column name.

        `columns` is {name: cell-index} — 1-based, matching `td:nth-child(n)`.
        """
        expr = """JSON.stringify(
            Array.from(document.querySelectorAll('#%s tr')).map(function (tr) {
                var tds = tr.querySelectorAll('td');
                var out = {};
                %s
                return out;
            }))""" % (table_id, "\n".join(
            "out[%s] = (tds[%d] && tds[%d].textContent) ? tds[%d].textContent.trim() : '';"
            % (json.dumps(name), index - 1, index - 1, index - 1)
            for name, index in columns.items()))
        return json.loads(c.js(expr))

    def row_attr(self, c, table_id, attr):
        """The row-aligned values of a per-row attribute, e.g. `data-edit`.

        A row's identity lives on its action buttons, not in a cell, so `table()`
        cannot see it. Returns one value per visible row, in the same order, so
        `row_attr(...)[0]` is the id of `table(...)[0]`.
        """
        return json.loads(c.js(
            "JSON.stringify(Array.from(document.querySelectorAll('#%s tr'))"
            ".map(function (tr) { var b = tr.querySelector('[%s]');"
            "return b ? b.getAttribute(%s) : null; }))"
            % (table_id, attr, json.dumps(attr))))

    def wait_content(self, c, selector, needle, timeout=20):
        """THE fix for the vacuous wait: wait for the CONTENT, not for a count."""
        return c.wait_js(
            "(function(){var e=document.querySelector(%s);"
            "return !!e && e.textContent.indexOf(%s) !== -1;})()"
            % (json.dumps(selector), json.dumps(needle)), timeout=timeout)

    def search_until(self, c, input_id, table_id, term, expect_count=None,
                     expect_name=None, timeout=25):
        """Type a search term and wait until the table actually reflects it.

        Two searches in a row can both answer one row, so waiting on the count alone
        passes before the new query is even sent — and the assertion that follows
        reads the previous result. Naming the row being waited for is what makes the
        wait mean something; `expect_name` is therefore the parameter to use.
        """
        self.fill(c, {input_id: term})
        wait = "true"
        if expect_count is not None:
            wait = ("document.querySelectorAll('#%s tr').length === %d"
                    % (table_id, expect_count))
        if expect_name is not None:
            wait += (" && (function(){var r=document.querySelector('#%s tr td:nth-child(2)');"
                     "return !!r && r.textContent.indexOf(%s) !== -1;})()"
                     % (table_id, json.dumps(expect_name)))
        return c.wait_js(wait, timeout=timeout)

    def search_empty(self, c, input_id, table_id, term, timeout=25):
        """Type a term and wait for the table to be genuinely empty."""
        self.fill(c, {input_id: term})
        return c.wait_js("document.querySelectorAll('#%s tr').length === 0" % table_id,
                         timeout=timeout)

    def set_file_input(self, c, selector, path):
        """Give an `<input type=file>` a real file — the only way to exercise an upload.

        A file input cannot be filled from JS (its value is write-protected), so a
        walk that skips this can only ever test the branch that has no file. CDP sets
        the file at the browser level and fires `change`, so the page's own handler
        runs exactly as it does for a human picking the file.
        """
        c.cmd("DOM.enable")
        root = c.cmd("DOM.getDocument", {"depth": -1})["root"]["nodeId"]
        node = c.cmd("DOM.querySelector", {"nodeId": root, "selector": selector})["nodeId"]
        if not node:
            raise RuntimeError("no element matches %s" % selector)
        c.cmd("DOM.setFileInputFiles", {"files": [str(path)], "nodeId": node})

    def stub_confirm(self, c, accept=True):
        """Replace confirm() with a recording stub.

        A real dialog blocks the page, so it cannot be left to open. The decision and
        its wording are still the page's — they land in `window.__confirms`.
        """
        c.js("window.__confirms = []; window.confirm = function (m) "
             "{ __confirms.push(String(m)); return %s; };"
             % ("true" if accept else "false"))

    def confirms(self, c):
        return json.loads(c.js("JSON.stringify(window.__confirms || [])"))

    def resources(self, c, needle="/api/"):
        return json.loads(c.js("""JSON.stringify(
            performance.getEntriesByType('resource')
              .map(function (e) { return e.name; })
              .filter(function (n) { return n.indexOf(%s) !== -1; }))""" % json.dumps(needle)))
