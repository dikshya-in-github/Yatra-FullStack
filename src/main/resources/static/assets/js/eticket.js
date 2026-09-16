/* =====================================================
   E-TICKET — transaction + flight from sessionStorage,
   airline branding, PNR badge, issued-by, airport copy,
   barcodes, print, navbar
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    var txn = load("yatra_transaction");
    var flight = (txn && txn.ref) || load("yatra_selected_flight") || {};
    var pax = load("yatra_passenger") || load("yatra_passenger_details") || {};
    var promo = load("yatra_promo");

    var fmt = function (n) {
        return "NPR " + n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    function to12(hhmm) {
        if (!hhmm) return "—";
        var p = hhmm.split(":"), h = +p[0];
        return ((h + 11) % 12 + 1) + ":" + p[1] + " " + (h >= 12 ? "PM" : "AM");
    }

    /* ---------- Payment line ---------- */
    var amount = txn && txn.amount ? txn.amount : (flight.price || 8299.99);
    var method = txn && txn.method ? txn.method : "eSewa";
    var txnId = txn && txn.txnId ? txn.txnId : "DEMO" + Date.now().toString().slice(-8);

    document.getElementById("txnId").textContent = txnId;
    document.getElementById("paidLine").innerHTML =
        'Paid <b>' + fmt(amount) + '</b> via <b>' + method + '</b> · Txn ID: <b id="txnId">' + txnId + '</b>';

    /* ---------- Route ---------- */
    var fromCode = flight.from || "KTM", toCode = flight.to || "BDP";
    document.getElementById("tFromCode").textContent = fromCode;
    document.getElementById("tToCode").textContent = toCode;
    document.getElementById("tFlightNo").textContent = flight.flightNo || "U4 951";
    document.getElementById("tDepTime").textContent = to12(flight.depart);
    document.getElementById("tArrTime").textContent = to12(flight.arrive);

    /* ---------- Airline (operating carrier) ---------- */
    var al = flight.airline || { name: "Yatra Air", code: "YT" };
    var alEl = document.getElementById("tAirline");
    if (alEl) alEl.textContent = "Operated by " + al.name + " · " + al.code;
    document.title = "E-Ticket " + (flight.flightNo || "") + " | Yatra";

    document.getElementById("tDuration").textContent =
        (flight.depart && flight.arrive)
            ? ((+flight.arrive.split(":")[0] * 60 + +flight.arrive.split(":")[1]) -
                (+flight.depart.split(":")[0] * 60 + +flight.depart.split(":")[1])) + " min"
            : "45 min";

    /* ---------- Passenger / date / class ---------- */
    document.getElementById("tPassenger").textContent =
        pax.name || pax.fullName || "Dikshya Ghising";
    var d = flight.date ? new Date(flight.date + "T00:00:00") : new Date();
    var dateStr = isNaN(d)
        ? "Tue, 15 Sep"
        : d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
    document.getElementById("tDate").textContent = dateStr;
    document.getElementById("tClass").textContent = flight.flightClass || "E Class";

    /* ---------- Ticket no. + PNR (deterministic from txn) ---------- */
    var seed = txnId.replace(/\D/g, "") || "00000000";
    document.getElementById("tTicketNo").textContent = "784-24" + seed.slice(0, 10);
    var pnrVal = "YTRA" + seed.slice(0, 2).split("").map(function (c) {
        return String.fromCharCode(65 + (+c) % 26);
    }).join("") + seed.slice(2, 4);
    document.getElementById("tPnr").textContent = pnrVal;

    /* ---------- Authentic details: PNR badge, issued-by, airport copy ---------- */
    document.getElementById("tPnrBadge").textContent = pnrVal;
    document.getElementById("acPnr").textContent = pnrVal;
    document.getElementById("acRoute").textContent = fromCode + " - " + toCode;
    document.getElementById("acDate").textContent = dateStr;
    document.getElementById("tIssuedBy").textContent = method;

    /* ---------- Fare breakdown ---------- */
    var product = txn && txn.productAmount ? txn.productAmount : amount;
    document.getElementById("tFare").textContent = fmt(product);
    if (promo && product > amount) {
        document.getElementById("tPromoRow").hidden = false;
        document.getElementById("tPromoVal").textContent = "− " + fmt(product - amount);
    }
    document.getElementById("tTotal").textContent = fmt(amount);

    /* ---------- Payment block ---------- */
    document.getElementById("tMethod").textContent = method;
    document.getElementById("tTxn").textContent = txnId;
    var paid = txn && txn.paidAt ? new Date(txn.paidAt) : new Date();
    document.getElementById("tPaidAt").textContent =
        paid.toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });

    /* ---------- Fake barcodes (stripes from txn id) ---------- */
    var html = "";
    for (var i = 0; i < 48; i++) {
        var code = seed.charCodeAt(i % seed.length) + i;
        html += '<i style="opacity:' + (0.55 + (code % 5) * 0.11) + '"></i>';
    }
    var bc = document.getElementById("barcode");
    var bc2 = document.getElementById("barcode2");
    if (bc) bc.innerHTML = html;
    if (bc2) bc2.innerHTML = html;

    /* ---------- Print ---------- */
    document.getElementById("printBtn").addEventListener("click", function () {
        window.print();
    });

    /* ---------- Navbar + mobile menu ---------- */
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
});
