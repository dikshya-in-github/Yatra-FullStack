/* =====================================================
   FLIGHT SEARCH — URL params, date strip, flight cards,
   ticket-class pricing, policy modal, step-1 select

   Item 16 (mock API refactor): all flight data — routes,
   schedules, carriers, fare classes, prices — now comes
   from apiGet("/api/flights/search") (mock layer in
   mock-data.js). This file keeps only rendering + page
   interactions; no hardcoded flight data remains here.
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    /* Airport, schedule, carrier and fare-class data moved to
       mock-data.js — served through api.js (see loadFlights). */

    /* ---------- URL params ---------- */
    var params = new URLSearchParams(location.search);
    var from = (params.get("from") || "KTM").toUpperCase();
    var to = (params.get("to") || "BDP").toUpperCase();
    var pax = parseInt(params.get("pax"), 10) || 1;

    function parseDate(s) {
        if (!s) return null;
        var d = new Date(s + "T00:00:00");
        return isNaN(d) ? null : d;
    }
    var selected = parseDate(params.get("date")) || new Date(new Date().setHours(0, 0, 0, 0));

    function iso(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }
    function shortDate(d) {
        return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
    }
    function to12(hhmm) {
        var p = hhmm.split(":"), h = +p[0], m = p[1];
        var ap = h >= 12 ? "PM" : "AM";
        return ((h + 11) % 12 + 1) + ":" + m + " " + ap;
    }
    function duration(dep, arr) {
        var a = dep.split(":"), b = arr.split(":");
        var mins = (+b[0] * 60 + +b[1]) - (+a[0] * 60 + +a[1]);
        return mins + " min";
    }
    function npr(n) {
        return "NPR " + n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /* ---------- Header text (URL params — route names arrive with the search) ---------- */
    document.title = from + " - " + to + " :: Flight search | Yatra";
    document.getElementById("sumFrom").textContent = from;
    document.getElementById("sumTo").textContent = to;
    document.getElementById("sumPax").textContent = pax + (pax > 1 ? " Adults" : " Adult");

    /* ---------- Flight data — through api.js (item 16) ----------
       Mock: apiGet resolves from mock-data.js. Later: the same call
       hits GET /api/flights/search on the Spring API (USE_MOCK_DATA
       = false in config.js) — this page never changes for that. */
    var currentSearch = null; // last search response
    var currentFlights = [];  // convenience: currentSearch.flights
    var fromCity = "", toCity = "";
    var searchSeq = 0;        // ignore stale responses (date-strip races)

    function loadFlights() {
        var qs = "?origin=" + encodeURIComponent(from) +
            "&destination=" + encodeURIComponent(to) +
            "&date=" + encodeURIComponent(iso(selected)) +
            "&passengers=" + encodeURIComponent(pax);
        var seq = ++searchSeq;
        return apiGet("/api/flights/search" + qs).then(function (resp) {
            if (seq !== searchSeq) return; // a newer search superseded this one
            currentSearch = resp;
            currentFlights = resp.flights || [];
            fromCity = (currentSearch.origin && currentSearch.origin.city) || from;
            toCity = (currentSearch.destination && currentSearch.destination.city) || to;
            document.getElementById("resultsTitle").textContent =
                "Select your preferred flight from " + currentSearch.origin.label +
                " to " + currentSearch.destination.label;
            renderFlights();
        });
    }

    /* ---------- Date strip (selected day sits 4th of 7) ---------- */
    var strip = document.getElementById("dateStrip");
    var list = document.getElementById("flightsList");
    var emptyState = document.getElementById("emptyState");

    function renderStrip() {
        strip.innerHTML = "";
        for (var i = -3; i <= 3; i++) {
            var d = new Date(selected);
            d.setDate(d.getDate() + i);
            var btn = document.createElement("button");
            btn.type = "button";
            btn.className = "date-btn" + (i === 0 ? " selected" : "");
            btn.textContent = shortDate(d);
            btn.setAttribute("aria-pressed", i === 0 ? "true" : "false");
            (function (date) {
                btn.addEventListener("click", function () {
                    selected = date;
                    params.set("date", iso(date));
                    history.replaceState(null, "", location.pathname + "?" + params.toString());
                    renderStrip();
                    loadFlights();
                });
            })(d);
            strip.appendChild(btn);
        }
        document.getElementById("sumDate").textContent = shortDate(selected);
    }

    /* ---------- Render flight cards (from the search response) ---------- */
    function renderFlights() {
        if (!currentFlights.length) {
            list.innerHTML = "";
            emptyState.hidden = false;
            return;
        }
        emptyState.hidden = true;
        list.classList.add("fading");
        setTimeout(function () {
            list.innerHTML = "";
            currentFlights.forEach(function (f, idx) {
                list.appendChild(cardHTML(f, idx));
            });
            list.classList.remove("fading");
        }, 150);
    }

    function cardHTML(f, idx) {
        var card = document.createElement("article");
        card.className = "flight-card";
        card.dataset.idx = idx;
        card.dataset.classIdx = "0";

        var was = f.isLowest ? "<s>" + npr(f.comparePrice) + "</s> " : "";
        var row = document.createElement("button");
        row.type = "button";
        row.className = "flight-row";
        row.setAttribute("aria-expanded", "false");
        row.innerHTML =
            '<div class="fc-times"><h3>' + to12(f.depart) + " - " + to12(f.arrive) + '</h3>' +
            '<p>' + fromCity + " (" + from + ")&nbsp; - &nbsp;" + toCity + " (" + to + ')</p></div>' +
            '<div class="fc-airline"><span class="al-mark"><span class="al-code">' + f.airline.code + '</span>' +
            '<img class="al-logo" src="' + f.airline.logo + '" alt="" onerror="this.remove()"></span>' +
            '<span class="al-txt"><strong>' + f.airline.name + '</strong><span class="al-sub">Airline</span></span></div>' +
            '<div class="fc-cell fc-class"><strong>' + f.fareOptions[0].label + '</strong><span>Ticket type</span></div>' +
            '<div class="fc-cell fc-bag"><strong>15kg</strong><span><i class="fa-solid fa-briefcase"></i> Baggage</span></div>' +
            '<div class="fc-cell fc-hand"><strong>5kg</strong><span><i class="fa-solid fa-suitcase-rolling"></i> Hand carry</span></div>' +
            '<div class="fc-price"><strong>' + npr(f.baseFare) +
            (f.isLowest ? ' <span class="badge-low">Low fare</span>' : "") +
            '</strong><p>' + was + '<span class="refund-word">Non Refundable</span></p></div>' +
            '<span class="fc-chev"><i class="fa-solid fa-chevron-down"></i></span>';
        card.appendChild(row);

        var expand = document.createElement("div");
        expand.className = "flight-expand";
        var dateStr = shortDate(selected);
        expand.innerHTML =
            '<div class="fe-inner">' +
            '<div class="fe-head"><h4><span>Departure</span> &nbsp;·&nbsp; ' + dateStr + '</h4>' +
            '<div><span class="fe-price">' + npr(f.baseFare) + '</span> ' +
            '<span class="fe-refund">Non Refundable</span></div></div>' +
            '<div class="fe-body">' +
            '<div class="fe-timeline">' +
            '<div class="tl-row"><span class="tl-time">' + to12(f.depart) + '</span><span class="tl-place">' + fromCity + " (" + from + ')</span></div>' +
            '<div class="tl-mid"><span class="tl-flight"><i class="fa-solid fa-plane"></i> ' + f.flightNo + ' · ' + f.airline.name + '</span>' +
            '<span class="tl-sep"></span><span>Flight Duration: ' + duration(f.depart, f.arrive) + '</span></div>' +
            '<div class="tl-row"><span class="tl-time">' + to12(f.arrive) + '</span><span class="tl-place">' + toCity + " (" + to + ')</span></div>' +
            '</div>' +
            '<div class="fe-config">' +
            '<p class="fe-label">Select Ticket Type</p>' +
            '<div class="class-pills">' + pillsHTML(f) + '</div>' +
            '<p class="fe-bag"><i class="fa-solid fa-briefcase"></i>Baggage: <strong>15kg</strong>' +
            '<span class="gap"></span><i class="fa-solid fa-suitcase-rolling"></i>Hand carry: <strong>5kg</strong></p>' +
            '</div>' +
            /* Miles feature removed — footer is policy link + select button only */
            '<div class="fe-foot">' +
            '<button type="button" class="linklike" data-policy>Cancellation Policy</button>' +
            '<button type="button" class="btn btn-primary btn-select"><i class="fa-solid fa-circle-check"></i> Select Departure Flight</button>' +
            '</div>' +
            '</div>' +
            '</div>';
        card.appendChild(expand);
        return card;
    }

    function pillsHTML(f) {
        return f.fareOptions.map(function (c, i) {
            return '<button type="button" class="pill' + (i === 0 ? " active" : "") +
                '" data-class-idx="' + i + '">' + c.label + '</button>';
        }).join("");
    }

    /* ---------- Airline logos: flag loaded images (load doesn't bubble,
       so listen in capture phase — covers lazily added card images) ---------- */
    list.addEventListener("load", function (e) {
        var img = e.target;
        if (img.classList && img.classList.contains("al-logo")) {
            img.closest(".al-mark").classList.add("has-logo");
        }
    }, true);

    /* ---------- Card interactions (event delegation) ---------- */
    list.addEventListener("click", function (e) {
        var card = e.target.closest(".flight-card");
        if (!card) return;
        var expand = card.querySelector(".flight-expand");

        // 1) Toggle expand/collapse
        if (e.target.closest(".flight-row")) {
            var isOpen = card.classList.contains("open");
            document.querySelectorAll(".flight-card.open").forEach(function (c) {
                c.classList.remove("open");
                c.querySelector(".flight-expand").style.maxHeight = null;
                c.querySelector(".flight-row").setAttribute("aria-expanded", "false");
            });
            if (!isOpen) {
                card.classList.add("open");
                expand.style.maxHeight = expand.scrollHeight + "px";
                card.querySelector(".flight-row").setAttribute("aria-expanded", "true");
            }
            return;
        }

        // 2) Ticket-class pill — update price and refund text
        var pill = e.target.closest(".pill");
        if (pill) {
            card.querySelectorAll(".pill").forEach(function (p) { p.classList.remove("active"); });
            pill.classList.add("active");
            var ci = +pill.dataset.classIdx;
            card.dataset.classIdx = ci;

            var idx = +card.dataset.idx;
            var f = currentFlights[idx];
            var cls = f.fareOptions[ci];
            var price = cls.price;
            var was = f.isLowest && ci === 0 ? "<s>" + npr(f.comparePrice) + "</s> " : "";

            card.querySelector(".fc-class strong").textContent = cls.label;
            card.querySelector(".fc-price strong").innerHTML = npr(price) +
                (f.isLowest && ci === 0 ? ' <span class="badge-low">Low fare</span>' : "");
            card.querySelector(".fc-price .refund-word").textContent =
                cls.refundable ? "Refundable" : "Non Refundable";
            var wasEl = card.querySelector(".fc-price s");
            if (wasEl) wasEl.remove();
            if (was) card.querySelector(".fc-price p").insertAdjacentHTML("afterbegin", was);

            card.querySelector(".fe-price").textContent = npr(price);
            card.querySelector(".fe-refund").textContent = cls.refundable ? "Refundable" : "Non Refundable";
            expand.style.maxHeight = expand.scrollHeight + "px"; // re-measure
            return;
        }

        // 3) Cancellation policy modal
        if (e.target.closest("[data-policy]")) {
            var pf = currentFlights[+card.dataset.idx];
            openPolicy(pf.fareOptions[+card.dataset.classIdx].refundable);
            return;
        }

        // 4) Select flight → step 2
        if (e.target.closest(".btn-select")) {
            var ci2 = +card.dataset.classIdx;
            var idx2 = +card.dataset.idx;
            var fl = currentFlights[idx2];
            var cl = fl.fareOptions[ci2];
            sessionStorage.setItem("yatra_selected_flight", JSON.stringify({
                from: from, to: to, date: iso(selected),
                flightNo: fl.flightNo, depart: fl.depart, arrive: fl.arrive,
                airline: fl.airline,
                flightClass: cl.label, refundable: cl.refundable,
                price: cl.price,
                passengers: pax
            }));
            location.href = "./booking.html"; // step 2 — Passenger Details
        }
    });

    /* ---------- Policy modal ---------- */
    var modal = document.getElementById("policyModal");
    var policyList = document.getElementById("policyList");
    var closeBtn = document.getElementById("policyClose");

    function openPolicy(refundable) {
        var policies = (currentSearch && currentSearch.farePolicies) || { nonRefundable: [], refundable: [] };
        var items = refundable ? policies.refundable : policies.nonRefundable;
        policyList.innerHTML = items.map(function (t) { return "<li>" + t + "</li>"; }).join("");
        modal.classList.add("show");
        modal.setAttribute("aria-hidden", "false");
        closeBtn.focus();
    }
    function closePolicy() {
        modal.classList.remove("show");
        modal.setAttribute("aria-hidden", "true");
    }
    closeBtn.addEventListener("click", closePolicy);
    modal.addEventListener("click", function (e) { if (e.target === modal) closePolicy(); });
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape" && modal.classList.contains("show")) closePolicy();
    });

    /* ---------- Navbar + mobile menu (same as other pages) ---------- */
    var navbar = document.getElementById("navbar");
    function onScroll() { navbar.classList.toggle("scrolled", window.scrollY > 40); }
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();

    var menuBtn = document.getElementById("mobileMenuBtn");
    var mobileMenu = document.getElementById("mobileMenu");
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

    /* ---------- Back to top ---------- */
    var backToTop = document.getElementById("backToTop");
    if (backToTop) {
        var toggleBackToTop = function () { backToTop.classList.toggle("show", window.scrollY > 500); };
        window.addEventListener("scroll", toggleBackToTop, { passive: true });
        toggleBackToTop();
        backToTop.addEventListener("click", function () {
            var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
            window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
        });
    }

    /* ---------- Init ---------- */
    renderStrip();
    loadFlights();
});
