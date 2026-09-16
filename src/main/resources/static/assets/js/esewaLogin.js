/* =====================================================
   MOCK ESEWA GATEWAY — payable amount from the BOOKING
   (fare × paying passengers, not one passenger's fare),
   eye toggle, fake captcha, simulated login → OTP step
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {

    var fmt = function (n) {
        return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };

    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    /* ---------- Payable amount (item 17 fix) ----------
       The gateway must show and charge exactly what the booking persists:
       bookingData.flight.totalPrice (fare × paying passengers). It used to read
       yatra_selected_flight.price — ONE fare — so a multi-passenger booking was
       shown/charged less here than on the e-ticket. The rule now lives in the
       mock layer (MockDB.payableTotal — the same value the backend's
       PaymentService derives from the booking row); the inline fallback keeps
       this page rendering if mock-data.js is ever not loaded. */
    var booking = load("bookingData");
    var flight = load("yatra_selected_flight");
    var amount = (typeof MockDB !== "undefined" && MockDB.payableTotal)
        ? MockDB.payableTotal(booking, flight)
        : ((booking && booking.flight && typeof booking.flight.totalPrice === "number" && booking.flight.totalPrice > 0)
            ? booking.flight.totalPrice
            : (flight && flight.price ? flight.price : 8299.99));

    document.getElementById("amountBig").textContent = fmt(amount);
    document.getElementById("amountProduct").textContent = fmt(amount);
    document.getElementById("amountTotal").textContent = fmt(amount);

    if (flight) {
        var d = new Date(flight.date + "T00:00:00");
        var dateStr = isNaN(d) ? "" : d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
        var p = flight.depart ? " · " + flight.depart : "";
        document.getElementById("flightRef").innerHTML =
            '<i class="fa-solid fa-plane"></i> ' + flight.from + " → " + flight.to +
            " · " + flight.flightNo + (flight.airline ? " · " + flight.airline.name : "") +
            (dateStr ? " · " + dateStr : "") + p;
    } else {
        document.getElementById("flightRef").innerHTML =
            '<i class="fa-solid fa-plane"></i> Demo booking — no flight selected';
    }

    /* ---------- Password eye toggle ---------- */
    var pinInput = document.getElementById("esewaPin");
    var eyeBtn = document.getElementById("eyeBtn");
    eyeBtn.addEventListener("click", function () {
        var show = pinInput.type === "password";
        pinInput.type = show ? "text" : "password";
        eyeBtn.innerHTML = show
            ? '<i class="fa-solid fa-eye"></i>'
            : '<i class="fa-solid fa-eye-slash"></i>';
        eyeBtn.setAttribute("aria-label", show ? "Hide password" : "Show password");
        pinInput.focus();
    });

    /* ---------- Mock reCAPTCHA ---------- */
    var captcha = document.getElementById("captcha");
    var captchaBox = document.getElementById("captchaBox");
    var captchaDone = false;

    function toggleCaptcha() {
        if (captchaDone || captcha.classList.contains("loading")) return;
        captcha.classList.add("loading");
        captchaBox.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i>';
        setTimeout(function () {
            captcha.classList.remove("loading");
            captcha.classList.add("verified");
            captchaBox.innerHTML = '<i class="fa-solid fa-check"></i>';
            captcha.setAttribute("aria-checked", "true");
            captchaDone = true;
            document.getElementById("capErr").textContent = "";
        }, 900);
    }
    captcha.addEventListener("click", toggleCaptcha);
    captcha.addEventListener("keydown", function (e) {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggleCaptcha(); }
    });

    /* ---------- Validation helpers ---------- */
    function setIdErr(msg) { document.getElementById("idErr").textContent = msg || ""; }
    function setPinErr(msg) { document.getElementById("pinErr").textContent = msg || ""; }
    function setCapErr(msg) { document.getElementById("capErr").textContent = msg || ""; }

    /* ---------- Login submit (simulated) ---------- */
    var form = document.getElementById("loginForm");
    var loginBtn = document.getElementById("loginBtn");
    var formErr = document.getElementById("formErr");

    form.addEventListener("submit", function (e) {
        e.preventDefault();
        formErr.textContent = "";
        setIdErr(""); setPinErr(""); setCapErr("");            var id = document.getElementById("esewaId").value.trim();
        var pin = pinInput.value;
        var ok = true;

        // eSewa ID: 10-digit mobile number or email
        var isPhone = /^9\d{9}$/.test(id);
        var isEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(id);
        if (!id) { setIdErr("Please enter your eSewa ID."); ok = false; }
        else if (!isPhone && !isEmail) { setIdErr("Enter a 10-digit mobile number or a valid email."); ok = false; }

        if (!pin) { setPinErr("Please enter your Password/MPIN."); ok = false; }
        else if (pin.length < 4) { setPinErr("Password/MPIN must be at least 4 characters."); ok = false; }

        if (!captchaDone) { setCapErr("Please confirm you're not a robot."); ok = false; }

        if (!ok) return;

        // Simulate gateway processing
        loginBtn.disabled = true;
        loginBtn.textContent = "PROCESSING...";
        setTimeout(function () {
            // Login accepted — create the PENDING payment. The transaction itself
            // is only recorded after the final confirm/verify step (esewaConfirm.js).
            var pax = null;
            try { pax = JSON.parse(sessionStorage.getItem("yatra_passenger")); } catch (e) { /* ignore */ }
            var pending = {
                amount: amount,
                productAmount: amount,
                method: "eSewa",
                createdAt: new Date().toISOString(),
                user: {
                    phone: (pax && pax.phone) || "9803660660",
                    name: (pax && pax.name) || "Dikshya Ghising",
                    email: (pax && pax.email) || "ghisingleeku@gmail.com",
                    address: (pax && pax.address) || "Pariwartan, Suryabinayak Municipality-8, Bhaktapur, Bagmati Pradesh"
                },
                flight: flight || null
            };
            sessionStorage.setItem("yatra_pending_payment", JSON.stringify(pending));
            location.href = "./esewaOtp.html"; // gateway step 2 — OTP verification
        }, 1400);
    });

    /* ---------- Cancel payment ---------- */
    document.querySelector(".cancel-pay").addEventListener("click", function (e) {
        e.preventDefault();
        if (confirm("Cancel this payment and return to flight search?")) {
            location.href = "./searchFlight.html";
        }
    });
});
