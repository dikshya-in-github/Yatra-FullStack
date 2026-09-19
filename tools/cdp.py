#!/usr/bin/env python3
"""A stdlib-only Chrome DevTools Protocol client, for the module verification walks.

This is the transport half of the project's browser verification, extracted so a
walk in `tools/` does not need the git-ignored `target/clickthrough.py` to exist.
It has no dependencies: a raw WebSocket over `socket`, and CDP over JSON — which
is why it works on this machine at all (no automation library is installed).

`target/clickthrough.py` still carries its own copy of these two classes, because it
is the large legacy harness and rewriting it is not what this extraction was for.
**That duplication is known and recorded in requirements.md**: `target/` is
git-ignored and wiped by `mvn clean`, so this file is the copy that survives, and new
harnesses should import from here.

Two things live here that every walk needs and neither is interesting on its own:

  * `WS` / `CDP` — the client. `CDP.goto` waits for the NEW document rather than for
    `Page.loadEventFired`, because that event is buffered and a stale one from the
    previous page satisfies the wait instantly, so the check that follows measures
    the page you just left.
  * `api_trap()` — the same-origin fix. `config.js` hard-codes
    `API_BASE_URL = http://localhost:8080`, which is normally the IntelliJ instance
    (a different build), and the API sends no CORS header, so a cross-origin call
    would simply be blocked. The trap rewrites API_BASE_URL to the port the pages are
    actually served from, keeping every request same-origin and faithful instead of
    weakening browser security to make a test pass.
"""

import base64
import json
import os
import socket
import struct
import time
import urllib.request


class WS:
    """The smallest thing that can speak WebSocket well enough for CDP."""

    def __init__(self, url, timeout=30):
        assert url.startswith("ws://"), url
        rest = url[5:]
        hostport, _, path = rest.partition("/")
        path = "/" + path
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
            "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n" % (path, hostport, key)
        )
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("handshake closed early")
            buf += chunk
        head, _, self.buf = buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise RuntimeError("handshake failed: " + head.decode(errors="replace")[:200])

    def _take(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(1 << 16)
            if not chunk:
                raise RuntimeError("socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, text):
        payload = text.encode()
        n = len(payload)
        if n < 126:
            header = struct.pack("!BB", 0x81, 0x80 | n)
        elif n < 65536:
            header = struct.pack("!BBH", 0x81, 0x80 | 126, n)
        else:
            header = struct.pack("!BBQ", 0x81, 0x80 | 127, n)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def recv(self):
        data = b""
        while True:
            b0, b1 = self._take(2)
            fin, opcode = b0 & 0x80, b0 & 0x0F
            length = b1 & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._take(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._take(8))[0]
            payload = self._take(length) if length else b""
            if opcode == 0x9:                      # ping -> masked pong
                mask = os.urandom(4)
                masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
                n = len(payload)
                if n < 126:
                    frame = struct.pack("!BB", 0x8A, 0x80 | n)
                else:
                    frame = struct.pack("!BBH", 0x8A, 0x80 | 126, n)
                self.sock.sendall(frame + mask + masked)
                continue
            if opcode == 0xA:                      # pong
                continue
            if opcode == 0x8:
                raise RuntimeError("closed by peer")
            data += payload
            if fin:
                return data.decode()


class CDP:
    def __init__(self, ws):
        self.ws = ws
        self.seq = 0
        self.events = []

    def cmd(self, method, params=None, timeout=40):
        self.seq += 1
        mid = self.seq
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError("%s -> %s" % (method, msg["error"]))
                return msg.get("result", {})
            if "method" in msg:
                self.events.append(msg)
        raise TimeoutError(method)

    def wait_url(self, needle, timeout=25):
        """Wait for the browser to actually BE somewhere else.

        The URL cannot lie, and `Page.loadEventFired` can: it is buffered, so a
        stale event from the page just left satisfies the wait instantly.
        `readyState` is part of the same expression because a URL updates at commit,
        when the new document has not run a line yet.
        """
        return self.wait_js(
            "location.href.indexOf(%s) !== -1 && document.readyState === 'complete'"
            % json.dumps(needle), timeout=timeout, interval=0.05)

    def js(self, expr, await_promise=False):
        r = self.cmd("Runtime.evaluate", {
            "expression": expr, "returnByValue": True, "awaitPromise": await_promise})
        if r.get("exceptionDetails"):
            d = r["exceptionDetails"]
            raise RuntimeError("JS error: " + json.dumps(
                d.get("exception", {}).get("description") or d.get("text"))[:300])
        return r["result"].get("value")

    def goto(self, url, wait=True):
        """Navigate and wait for the NEW document — never for a load event."""
        try:
            self.js("window.__ct_prev_page = 1")
        except RuntimeError:
            pass
        self.cmd("Page.navigate", {"url": url})
        if wait:
            self.wait_js("typeof window.__ct_prev_page === 'undefined'"
                         " && document.readyState === 'complete'",
                         timeout=40, interval=0.05)

    def wait_js(self, expr, timeout=20, interval=0.3):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.js(expr):
                    return True
            except RuntimeError:
                pass
            time.sleep(interval)
        return False

    def drain(self, keep_js=None):
        """Pull asset failures / JS exceptions out of the buffered events."""
        out = []
        for e in list(self.events):
            m = e["method"]
            p = e.get("params", {})
            if m == "Network.responseReceived":
                r = p.get("response", {})
                if r.get("status", 0) >= 400 and "favicon" not in r.get("url", ""):
                    out.append("%d %s" % (r["status"], r["url"]))
            elif m == "Network.loadingFailed":
                url = p.get("url") or ""
                if "favicon" not in url:
                    out.append("LOAD FAILED %s %s" % (p.get("errorText"), url))
            elif m == "Runtime.exceptionThrown":
                d = p.get("exceptionDetails", {})
                desc = (d.get("exception", {}) or {}).get("description") or d.get("text", "")
                out.append("JS EXCEPTION: " + desc.replace("\n", " ")[:200])
            elif m == "Log.entryAdded":
                en = p.get("entry", {})
                if en.get("level") == "error" and "favicon" not in (en.get("url") or ""):
                    out.append("LOG: " + (en.get("text") or "")[:200])
        self.events = []
        return out


def api_trap(api_base):
    """Rewrite API_BASE_URL to the origin the pages are served from.

    Must be installed with `Page.addScriptToEvaluateOnNewDocument`, so it runs
    before `config.js` on every document. Also records `alert()` calls, because a
    headless dialog would otherwise block the page.
    """
    return """
(function () {
  var real;
  try {
    Object.defineProperty(window, 'YATRA_CONFIG', {
      configurable: true,
      get: function () { return real; },
      set: function (v) {
        try { v.API_BASE_URL = '%s'; } catch (e) {}
        real = v;
        Object.defineProperty(window, 'YATRA_CONFIG', {
          configurable: true, enumerable: true, writable: true, value: v
        });
      }
    });
  } catch (e) {}

  window.__alerts = [];
  window.alert = function (m) { window.__alerts.push(String(m)); };
})();
""" % api_base


def new_target(debug_url="http://127.0.0.1:9222", url="about:blank"):
    """A fresh Chrome tab. PUT first: newer Chrome rejects GET on /json/new."""
    for method in ("PUT", "GET"):
        try:
            req = urllib.request.Request("%s/json/new?%s" % (debug_url, url), method=method)
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.load(r)
        except Exception:
            continue
    raise RuntimeError("could not create a Chrome target — is Chrome on %s?" % debug_url)


def wait_for_chrome(debug_url="http://127.0.0.1:9222", timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(debug_url + "/json/version", timeout=3):
                return True
        except Exception:
            time.sleep(1)
    return False
