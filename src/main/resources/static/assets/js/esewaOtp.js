/* =====================================================
   eSewa OTP — amount/phone from sessionStorage,
   01:34 countdown, verify → balance, resend, cancel
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    var fmt = function (n) {
        return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    /* ---------- Amount (flight price → pending payment → demo) ---------- */
    var pending = load("yatra_pending_payment") || {};
    var flight = load("yatra_selected_flight") || {};
    var amount = typeof pending.amount === "number" ? pending.amount
        : (flight.price ? flight.price : 8299.99);

    document.querySelectorAll(".js-amount").forEach(function (el) { el.textContent = fmt(amount); });
    document.querySelectorAll(".js-amount-total").forEach(function (el) { el.textContent = "NPR. " + fmt(amount); });

    // Promo row (if a promo was applied on the flight/balance step)
    var promo = load("yatra_promo");
    if (promo && typeof pending.productAmount === "number" && pending.productAmount > amount) {
        var rows = document.querySelector(".summary-rows");
        var row = document.createElement("div");
        row.className = "amount-row";
        row.innerHTML = '<span>Promo Discount</span><span class="promo-val">− ' +
            fmt(pending.productAmount - amount) + "</span>";
        rows.insertBefore(row, rows.querySelector(".amount-row.total"));
    }

    /* ---------- Phone (passenger form → pending → demo) ---------- */
    var pax = load("yatra_passenger") || load("yatra_passenger_details") || {};
    var phone = (pax.phone || pax.contact) || (pending.user && pending.user.phone) || "9800000001";
    var phoneEl = document.getElementById("otpPhone");
    if (phoneEl) phoneEl.textContent = phone;

    /* ---------- Elements ---------- */
    var otpInput = document.getElementById("otpInput");
    var verifyBtn = document.getElementById("verifyBtn");
    var resendBtn = document.getElementById("resendOtp");
    var timerEl = document.getElementById("otpTimer");
    var timerRow = document.getElementById("otpExpiresRow");
    var errEl = document.getElementById("otpError");

    /* ---------- Countdown (01:34 = 94s) ---------- */
    var DURATION = 94;
    var remaining = DURATION;
    var timerId = null;

    function renderTime() {
        var m = String(Math.floor(remaining / 60)).padStart(2, "0");
        var s = String(remaining % 60).padStart(2, "0");
        timerEl.textContent = m + ":" + s;
    }
    function startTimer() {
        clearInterval(timerId);
        remaining = DURATION;
        timerRow.classList.remove("expired");
        timerRow.firstChild.textContent = "OTP expires in ";
        renderTime();
        otpInput.disabled = false;
        verifyBtn.disabled = false;
        timerId = setInterval(function () {
            remaining--;
            if (remaining <= 0) {
                clearInterval(timerId);
                remaining = 0;
                renderTime();
                timerRow.classList.add("expired");
                timerRow.firstChild.textContent = "OTP expired. ";
                otpInput.disabled = true;
                verifyBtn.disabled = true;
                showToast("OTP expired — tap Resend OTP");
                return;
            }
            renderTime();
        }, 1000);
    }
    startTimer();

    /* ---------- Verify ---------- */
    function fail(msg) {
        errEl.textContent = msg;
        otpInput.classList.remove("shake");
        void otpInput.offsetWidth; // restart animation
        otpInput.classList.add("shake");
    }
    verifyBtn.addEventListener("click", function () {
        var code = otpInput.value.trim();
        if (!/^\d{6}$/.test(code)) {
            fail("Please enter the 6-digit verification code.");
            return;
        }
        errEl.textContent = "";
        verifyBtn.disabled = true;
        verifyBtn.textContent = "VERIFYING...";
        setTimeout(function () {
            // Simulated gateway: any 6-digit code passes (demo mode)
            location.href = "./esewaBalance.html";
        }, 900);
    });
    otpInput.addEventListener("keydown", function (e) {
        if (e.key === "Enter") verifyBtn.click();
    });
    otpInput.addEventListener("input", function () {
        otpInput.value = otpInput.value.replace(/\D/g, "").slice(0, 6);
        if (errEl.textContent) errEl.textContent = "";
    });

    /* ---------- Resend ---------- */
    resendBtn.addEventListener("click", function () {
        otpInput.value = "";
        errEl.textContent = "";
        startTimer();
        otpInput.focus();
        showToast("New OTP sent to " + phone);
    });

    /* ---------- Back To Login ---------- */
    // (plain link to ./esewaLogin.html — no JS needed)

    /* ---------- Cancel payment modal ---------- */
    var cancelModal = document.getElementById("cancelModal");
    document.getElementById("cancelPaymentBtn").addEventListener("click", function () {
        cancelModal.classList.add("open");
    });
    document.getElementById("noClose").addEventListener("click", function () {
        cancelModal.classList.remove("open");
    });
    document.getElementById("yesCancel").addEventListener("click", function () {
        sessionStorage.removeItem("yatra_pending_payment");
        location.href = "./searchFlight.html";
    });
    cancelModal.addEventListener("click", function (e) {
        if (e.target === cancelModal) cancelModal.classList.remove("open");
    });
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape") cancelModal.classList.remove("open");
    });

    /* ---------- Toast helper ---------- */
    var toast = document.getElementById("epayToast");
    var toastId = null;
    function showToast(msg) {
        toast.textContent = msg;
        toast.classList.add("show");
        clearTimeout(toastId);
        toastId = setTimeout(function () { toast.classList.remove("show"); }, 2600);
    }
});
