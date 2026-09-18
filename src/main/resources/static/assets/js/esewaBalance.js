/* =====================================================
   ESEWA STEP 2b — wallet balance page.
   Reads yatra_pending_payment (set by esewaLogin.js),
   shows amount breakdown + user details + wallet balance,
   applies promo codes, CONTINUE → esewaConfirm.html.
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    var fmt = function (n) {
        return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    /* ---------- Pending payment (set at gateway login) ---------- */
    var pending = load("yatra_pending_payment") || {};
    var flight = pending.flight || load("yatra_selected_flight");
    var product = typeof pending.productAmount === "number" ? pending.productAmount
        : (typeof pending.amount === "number" ? pending.amount
            : (flight && flight.price ? flight.price : 8299.99));
    var discount = 0;
    var promoCode = null;

    /* ---------- Mock wallet balance ---------- */
    var BAL_KEY = "yatra_esewa_balance";
    var balance = parseFloat(sessionStorage.getItem(BAL_KEY));
    if (isNaN(balance)) balance = 26.35; // demo wallet — intentionally low

    function renderBalance() {
        document.getElementById("balAmount").textContent = fmt(balance);
    }

    /* ---------- Amounts breakdown ---------- */
    function renderAmounts() {
        var payable = Math.max(product - discount, 0);
        document.getElementById("amountBig").textContent = fmt(payable);
        document.getElementById("amountProduct").textContent = fmt(product);
        if (discount > 0) {
            document.getElementById("promoRow").hidden = false;
            document.getElementById("promoVal").textContent = "− " + fmt(discount);
            document.getElementById("payableRow").hidden = false;
            document.getElementById("amountPayable").textContent = fmt(payable);
            document.getElementById("totalRow").hidden = true;
        } else {
            document.getElementById("promoRow").hidden = true;
            document.getElementById("payableRow").hidden = true;
            document.getElementById("totalRow").hidden = false;
        }
        document.getElementById("amountTotal").textContent = fmt(payable);
    }

    /* ---------- Flight reference line ---------- */
    var flightRef = document.getElementById("flightRef");
    if (flight && flight.from) {
        var d = flight.date ? new Date(flight.date + "T00:00:00") : null;
        var dateStr = d && !isNaN(d)
            ? " · " + d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })
            : "";
        flightRef.innerHTML = '<i class="fa-solid fa-plane"></i> ' + flight.from + " → " + flight.to +
            " · " + (flight.flightNo || "") + dateStr;
    }

    /* ---------- User details ---------- */
    var user = pending.user || {};
    document.getElementById("udPhone").textContent = user.phone || "9800000001";
    document.getElementById("udName").textContent = user.name || "Test User";
    document.getElementById("udEmail").textContent = user.email || "test@example.com";
    document.getElementById("udContact").textContent = user.phone || "9800000001";
    document.getElementById("udAddress").textContent =
        user.address || "Test Ward 1, Kathmandu, Bagmati Pradesh";

    /* ---------- Balance refresh (mock) ---------- */
    var refreshBtn = document.getElementById("refreshBal");
    refreshBtn.addEventListener("click", function () {
        var icon = refreshBtn.querySelector("i");
        icon.classList.add("fa-spin");
        setTimeout(function () {
            icon.classList.remove("fa-spin");
            renderBalance();
        }, 700);
    });

    /* ---------- Promo codes (mock) ---------- */
    var PROMOS = {
        YATRA10: { type: "percent", value: 10, label: "10% discount applied" },
        YATRA500: { type: "flat", value: 500, label: "NPR 500 discount applied" }
    };
    var promoInput = document.getElementById("promoInput");
    var promoErr = document.getElementById("promoErr");
    var promoOk = document.getElementById("promoOk");
    var promoOkText = document.getElementById("promoOkText");

    promoInput.addEventListener("input", function () {
        promoErr.textContent = "";
        promoOk.hidden = true;
    });

    promoInput.addEventListener("change", function () {
        var code = promoInput.value.trim().toUpperCase();
        if (!code) { return; }
        var promo = PROMOS[code];
        if (!promo) {
            discount = 0;
            promoCode = null;
            promoErr.textContent = "Invalid promo code. Try YATRA10 or YATRA500.";
            renderAmounts();
            return;
        }
        promoCode = code;
        discount = promo.type === "percent"
            ? Math.round(product * promo.value) / 100
            : Math.min(promo.value, product);
        promoErr.textContent = "";
        promoOkText.textContent = promo.label + " (" + code + ")";
        promoOk.hidden = false;
        renderAmounts();
    });

    /* ---------- CONTINUE → confirmation step ---------- */
    document.getElementById("continueBtn").addEventListener("click", function () {
        pending.productAmount = product;
        pending.amount = Math.max(product - discount, 0);
        pending.promo = promoCode;
        pending.balance = balance;
        sessionStorage.setItem("yatra_pending_payment", JSON.stringify(pending));
        location.href = "./esewaConfirm.html"; // gateway step 3 — confirmation
    });

    /* ---------- CANCEL PAYMENT ---------- */
    document.getElementById("cancelPay").addEventListener("click", function (e) {
        e.preventDefault();
        if (confirm("Cancel this payment and return to flight search?")) {
            sessionStorage.removeItem("yatra_pending_payment");
            location.href = "./searchFlight.html";
        }
    });

    /* ---------- Init ---------- */
    renderBalance();
    renderAmounts();
});
