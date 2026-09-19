/* =====================================================
   E-TICKET — the document, read from the API

   The page renders GET /api/bookings/{id}: the customer's own booking,
   its ticket row, its payment and its passengers.

   What it must never do again: this file used to render entirely from
   sessionStorage and DERIVE the ticket number and PNR from the
   transaction id — `"DEMO" + Date.now()` when there was no transaction
   at all — so a customer could print a document whose two identifying
   numbers the browser had made up. Every value below now comes from the
   database or is rendered as absent, and a read that is refused or not
   yet settled shows a notice instead of a plausible-looking ticket.

   The booking id arrives one of two ways: `?bookingId=N` from the
   gateway redirect (EsewaController sends it there), or the booking the
   customer picked in My Bookings, which stores it in yatra_transaction.
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    function el(id) { return document.getElementById(id); }

    function text(id, value) {
        var node = el(id);
        if (node) node.textContent = value;
    }

    function fmt(n) {
        return "NPR " + Number(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /* "HH:mm" or "HH:mm:ss" → "6:50 AM". The API sends LocalTime, so the
       seconds are usually there; the old version split the string and
       printed "45 min" whenever either half was missing. */
    function to12(hhmm) {
        if (!hhmm) return "—";
        var p = String(hhmm).split(":"), h = +p[0];
        if (isNaN(h)) return "—";
        return ((h + 11) % 12 + 1) + ":" + p[1] + " " + (h >= 12 ? "PM" : "AM");
    }

    /* ISO date → "Sun, 20 Sep" — the form printed on the tile. */
    function shortDate(iso) {
        if (!iso) return "—";
        var d = new Date(iso + "T00:00:00");
        return isNaN(d) ? "—"
            : d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
    }

    function stamp(value) {
        if (!value) return "—";
        var d = new Date(value);
        return isNaN(d) ? "—"
            : d.toLocaleString("en-GB",
                { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
    }

    /* ---------- Which booking? ---------- */
    var query = new URLSearchParams(window.location.search);
    var txn = load("yatra_transaction") || {};
    var wizard = load("bookingData") || {};
    var rawId = query.get("bookingId") || txn.bookingId || wizard.bookingId || "";
    var bookingId = /^\d+$/.test(String(rawId)) ? parseInt(rawId, 10) : null;

    var banner = el("successBanner");
    var ticket = el("ticket");
    var notice = el("etNotice");

    /**
     * Show why there is no ticket, and hide the ticket that is not there.
     * `hint` is where to go next — the page has to end somewhere useful.
     */
    function explain(icon, title, body, hint) {
        if (banner) banner.hidden = true;
        if (ticket) ticket.hidden = true;
        if (el("printBtn")) el("printBtn").hidden = true;
        if (notice) {
            notice.hidden = false;
            var i = notice.querySelector("i");
            if (i) i.className = "fa-solid " + icon;
            text("etNoticeTitle", title);
            text("etNoticeBody", body);
            text("etNoticeHint", hint || "");
        }
    }

    /* ---------- The document ---------- */
    function render(b) {
        var method = b.method || "—";
        var flightNo = b.flightNo || "";

        /* Payment line — built as nodes rather than innerHTML, so a name or a
           reference from the database is never parsed as markup. */
        var line = el("paidLine");
        if (line) {
            line.textContent = "";
            var title = banner && banner.querySelector("h1");
            if (title) title.textContent = "Payment Successful!";

            line.append("Paid ");
            var strong = document.createElement("b");
            strong.textContent = fmt(b.amount);
            line.append(strong, " via ");
            var how = document.createElement("b");
            how.textContent = method;
            line.append(how, " · Txn ID: ");
            var ref = document.createElement("b");
            ref.id = "txnId";
            ref.textContent = b.txnId || "—";
            line.append(ref);
        }

        /* Route */
        text("tFromCode", b.fromCode || "—");
        text("tFromCity", b.fromCity || "");
        text("tToCode", b.toCode || "—");
        text("tToCity", b.toCity || "");
        text("tFlightNo", flightNo || "—");
        text("tDepTime", to12(b.depart));
        text("tArrTime", to12(b.arrive));
        text("tDuration", b.durationMinutes > 0 ? b.durationMinutes + " min" : "—");
        text("tAirline", b.airlineName
            ? "Operated by " + b.airlineName + (b.airlineCode ? " · " + b.airlineCode : "")
            : "");

        /* Passenger / date / class */
        var pax = b.passengers || [];
        var named = pax.length === 1
            ? pax[0].name
            : pax.length > 1
                ? pax[0].name + " + " + (pax.length - 1) + " more"
                : "—";
        text("tPassenger", named);
        text("tDate", shortDate(b.date));
        text("tClass", b.fareClass || "—");

        /* The identifiers the checkout minted — no longer derived from anything. */
        var pnr = b.pnr || "";
        text("tTicketNo", b.ticketNo || "—");
        text("tPnr", pnr || "—");
        text("tPnrBadge", pnr || "—");
        text("acPnr", pnr || "—");
        text("acRoute", (b.fromCode || "—") + " - " + (b.toCode || "—"));
        text("acDate", shortDate(b.date));

        /* The document's own status badge. */
        var docStatus = String(b.ticketStatus || "").toUpperCase();
        var statusEl = el("tStatus");
        if (statusEl) {
            statusEl.textContent = "";
            var statusIcon = document.createElement("i");
            statusIcon.className = "fa-solid "
                + (docStatus === "CANCELLED" ? "fa-circle-xmark" : "fa-circle-check");
            statusEl.append(statusIcon, " " + (docStatus || "—"));
        }

        /* Fare */
        text("tFare", fmt(b.productAmount != null ? b.productAmount : b.amount));
        if (el("tPromoRow") && b.productAmount != null && Number(b.productAmount) > Number(b.amount)) {
            el("tPromoRow").hidden = false;
            text("tPromoVal", "− " + fmt(Number(b.productAmount) - Number(b.amount)));
        }
        text("tTotal", fmt(b.amount));

        /* Payment block */
        text("tMethod", method);
        text("tTxn", b.txnId || "—");
        text("tPaidAt", stamp(b.paidAt));
        text("tIssuedBy", b.issuedAt ? method + " · issued " + stamp(b.issuedAt) : "not issued");

        /* Barcodes — decorative stripes, now seeded from the real ticket number
           and PNR so the two copies of the document carry the same pattern. */
        var seed = (b.ticketNo || "") + pnr.replace(/\s/g, "");
        if (!seed) seed = String(b.bookingId);
        var stripes = "";
        for (var i = 0; i < 48; i++) {
            var code = seed.charCodeAt(i % seed.length) + i * 7;
            stripes += '<i style="opacity:' + (0.55 + (code % 5) * 0.11) + '"></i>';
        }
        if (el("barcode")) el("barcode").innerHTML = stripes;
        if (el("barcode2")) el("barcode2").innerHTML = stripes;

        document.title = "E-Ticket " + (flightNo ? flightNo + " " : "") + (pnr ? "· " + pnr : "") + " | Yatra";

        if (banner) banner.hidden = false;
        if (ticket) ticket.hidden = false;

        /* A ticket row that says CANCELLED is shown as cancelled. Nothing in the
           API voids a document today — only the seeder writes CANCELLED — but the
           column is the document's own state, so the page reads it rather than
           inferring one from the booking's status. */
        if (ticketStatusCancelled(b) && notice) {
            notice.hidden = false;
            var icon = notice.querySelector("i");
            if (icon) icon.className = "fa-solid fa-circle-exclamation";
            text("etNoticeTitle", "This ticket is cancelled");
            text("etNoticeBody",
                "The booking is " + (b.status || "—") + " and the document's own status is CANCELLED.");
            text("etNoticeHint", "Contact support if you believe this is wrong.");
        }
    }

    function ticketStatusCancelled(b) {
        return String(b.ticketStatus || "").toUpperCase() === "CANCELLED";
    }

    /* ---------- Load ---------- */
    if (bookingId === null) {
        explain("fa-circle-question", "No booking to show",
            "This page prints the e-ticket for a booking you have already paid for, and it "
            + "was opened without one — no booking id in the address and none in this tab.",
            "Book a flight, or open a booking from My Tickets.");
    } else {
        apiGet("/api/bookings/" + bookingId)
            .then(function (b) {
                /* No ticket row yet: the booking exists but nothing has been issued,
                   which is a state to say plainly rather than to paper over with a
                   placeholder PNR. */
                if (!b.pnr && !b.ticketNo) {
                    explain("fa-hourglass-half", "Not ticketed yet",
                        "Booking " + b.bookingId + " is " + (b.status || "—")
                        + " and no ticket has been issued for it. A ticket is issued when the "
                        + "gateway confirms the payment.",
                        "If you have just paid, this page may be ahead of the callback — reopen it in a moment.");
                } else {
                    render(b);
                }
            })
            .catch(function (err) {
                var status = err && err.status;
                explain("fa-triangle-exclamation", "Could not load this ticket",
                    status === 401
                        ? "That booking belongs to an account, and this browser has no session — sign in with the account that made the booking."
                        : status === 404
                            ? "No booking with that id belongs to this account."
                            : (err && err.message) || "The API could not be reached.",
                    status === 401 ? "Sign in, then open this page again." : "Try again from My Tickets.");
            });
    }

    /* ---------- Print ---------- */
    if (el("printBtn")) {
        el("printBtn").addEventListener("click", function () { window.print(); });
    }

    /* ---------- Navbar + mobile menu ---------- */
    var navbar = el("navbar");
    if (navbar) {
        var onScroll = function () { navbar.classList.toggle("scrolled", window.scrollY > 40); };
        window.addEventListener("scroll", onScroll, { passive: true });
        onScroll();
    }

    var menuBtn = el("mobileMenuBtn");
    var mobileMenu = el("mobileMenu");
    if (menuBtn && mobileMenu) {
        menuBtn.addEventListener("click", function (e) {
            e.stopPropagation();
            mobileMenu.classList.toggle("open");
        });
        mobileMenu.querySelectorAll("a").forEach(function (a) {
            a.addEventListener("click", function () { mobileMenu.classList.remove("open"); });
        });
        document.addEventListener("click", function (e) {
            if (mobileMenu.classList.contains("open") && !mobileMenu.contains(e.target) && e.target !== menuBtn) {
                mobileMenu.classList.remove("open");
            }
        });
    }

    /* ---------- Back to top ---------- */
    var backToTop = el("backToTop");
    if (backToTop) {
        var toggleBackToTop = function () { backToTop.classList.toggle("show", window.scrollY > 500); };
        window.addEventListener("scroll", toggleBackToTop, { passive: true });
        toggleBackToTop();
        backToTop.addEventListener("click", function () {
            var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
            window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
        });
    }
});
