/* =====================================================
   ESEWA STEP 3 — confirmation, wallet/bank choice,
   insufficient balance check, cancel, success redirect
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    var fmt = function (n) {
        return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    /* ---------- Pending payment (set by balance page) ---------- */
    var pending = load("yatra_pending_payment") || {};
    var payable = typeof pending.amount === "number" ? pending.amount : 8299.99;
    var product = typeof pending.productAmount === "number" ? pending.productAmount : payable;
    var hasPromo = !!pending.promo;
    var user = pending.user || {
        phone: "9800000001",
        name: "Test User",
        email: "test@example.com",
        address: "Test Ward 1, Kathmandu, Bagmati Pradesh"
    };
    var flight = pending.flight || load("yatra_selected_flight");

    /* ---------- Fill details + amounts ---------- */
    document.getElementById("cId").textContent = user.phone;
    document.getElementById("cName").textContent = user.name;
    document.getElementById("cContact").textContent = user.phone;
    document.getElementById("cMail").textContent = user.email;
    document.getElementById("cAddress").textContent = user.address;

    document.getElementById("confProduct").textContent = fmt(product);
    document.getElementById("confTotal").textContent = fmt(payable);
    if (hasPromo) {
        document.getElementById("confPromoRow").hidden = false;
        document.getElementById("confPromoVal").textContent =
            product > payable ? "− " + fmt(product - payable) : pending.promo;
    }
    document.title = user.phone + " :: Confirmation | ePay";

    /* ---------- Balance ---------- */
    var BAL_KEY = "yatra_esewa_balance";
    var balance = parseFloat(sessionStorage.getItem(BAL_KEY));
    if (isNaN(balance)) balance = 26.35;

    /* ---------- Payment option toggle ---------- */
    var optWallet = document.getElementById("optWallet");
    var optBank = document.getElementById("optBank");
    var payBtn = document.getElementById("confPay");
    var method = "eSewa";

    optWallet.addEventListener("click", function () {
        optWallet.classList.add("selected");
        optBank.classList.remove("selected");
        method = "eSewa";
        payBtn.textContent = "PAY VIA ESEWA";
    });
    optBank.addEventListener("click", function () {
        optBank.classList.add("selected");
        optWallet.classList.remove("selected");
        method = "Linked Bank Account";
        payBtn.textContent = "PAY VIA BANK";
    });

    /* ---------- Modals ---------- */
    var insufModal = document.getElementById("insufModal");
    var cancelModal = document.getElementById("cancelModal");
    function show(m) { m.hidden = false; }
    function hide(m) { m.hidden = true; }

    /* ---------- PAY ---------- */
    payBtn.addEventListener("click", function () {
        if (method === "eSewa" && balance < payable) {
            show(insufModal);          // stacks over the confirmation modal
            return;
        }
        // Simulate processing, then success
        payBtn.disabled = true;
        payBtn.textContent = "PROCESSING...";
        setTimeout(function () {
            if (method === "eSewa") {
                balance -= payable;
                sessionStorage.setItem(BAL_KEY, balance.toFixed(2));
            }
            var txn = {
                txnId: "9A" + Date.now().toString().slice(-8),
                method: method,
                amount: payable,
                productAmount: product,
                paidAt: new Date().toISOString(),
                ref: flight || null
            };
            sessionStorage.setItem("yatra_transaction", JSON.stringify(txn));

            /* ---------- Persist the completed booking through the API layer
               (item 16): POST /api/payments/verify does what the backend's
               PaymentService does inside the verify transaction — writes the
               confirmed row to yatra_bookings (deterministic PNR/ticket
               derivation, same as eticket.js) and bumps bookedSeats on the
               matching yatra_admin_flights row. The mock has no database, so
               the pending booking is re-sent as body.booking; the real API
               accepts the same request minus that field (it looks the row up
               by bookingId). Failures never block the demo e-ticket. */
            var bk = load("bookingData") || {};
            var goEticket = function () { location.href = "./eticket.html"; };
            apiPost("/api/payments/verify", {
                txnId: txn.txnId,
                bookingId: bk.bookingId || null,
                method: txn.method,
                amount: txn.amount,
                productAmount: txn.productAmount,
                customerName: user.name || "",
                booking: {
                    contact: bk.contact || null,
                    passengers: bk.passengers || [],
                    flight: bk.flight || txn.ref || {}
                }
            }).then(goEticket, goEticket); // success or not → the e-ticket
        }, 1300);
    });

    /* ---------- Insufficient balance modal ---------- */
    document.getElementById("insufClose").addEventListener("click", function () { hide(insufModal); });
    document.getElementById("insufX").addEventListener("click", function () { hide(insufModal); });

    /* ---------- Cancel flow ---------- */
    document.getElementById("confCancel").addEventListener("click", function () { show(cancelModal); });
    document.getElementById("confClose").addEventListener("click", function () { show(cancelModal); });
    document.getElementById("cancelNo").addEventListener("click", function () { hide(cancelModal); });
    document.getElementById("cancelX").addEventListener("click", function () { hide(cancelModal); });
    document.getElementById("cancelYes").addEventListener("click", function () {
        sessionStorage.removeItem("yatra_pending_payment");
        location.href = "./searchFlight.html";
    });

    /* ---------- Esc closes the top modal ---------- */
    document.addEventListener("keydown", function (e) {
        if (e.key !== "Escape") return;
        if (!insufModal.hidden) { hide(insufModal); return; }
        if (!cancelModal.hidden) { hide(cancelModal); }
    });
});
