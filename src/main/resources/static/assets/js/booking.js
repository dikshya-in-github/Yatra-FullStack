document.addEventListener('DOMContentLoaded', () => {

    /* ==========================================================
       0. DATA — selected flight + search split (with demo fallback)
       ========================================================== */
    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    var flight = load("yatra_selected_flight");
    var search = load("flightSearchData");

    /* Demo fallback so the page still works when opened directly — the data
       itself lives in the mock layer (MockDB.DEMO_FLIGHT), not in page JS
       (§47 dynamic-readiness: no hardcoded flight data outside mock-data.js). */
    if (!flight) {
        flight = JSON.parse(JSON.stringify(MockDB.DEMO_FLIGHT));
    }

    /* Passenger split: prefer the home search, else the selection count */
    var counts = (search && search.passengers)
        ? {
            adult: search.passengers.adult || 0,
            child: search.passengers.child || 0,
            infant: search.passengers.infant || 0
        }
        : { adult: flight.passengers || 1, child: 0, infant: 0 };
    if (counts.adult === 0 && counts.child === 0) counts.adult = 1;

    /* Paying travellers (adults + children — infants ride on a lap) */
    var paying = counts.adult + counts.child;
    var paxLabel = counts.adult + (counts.adult === 1 ? " Adult" : " Adults") +
        (counts.child ? (", " + counts.child + (counts.child === 1 ? " Child" : " Children")) : "");

    /* Airport city names come from the mock data layer (MockDB.AIRPORTS) —
       the same 11-airport map the search results and admin pages use, so
       this page no longer keeps a second copy of it (item 17). */
    function cityName(code) {
        var airport = MockDB.AIRPORTS[code];
        return airport ? airport.city : code;
    }

    function to12(hhmm) {
        if (!hhmm) return "—";
        var p = hhmm.split(":"), h = +p[0];
        return ((h + 11) % 12 + 1) + ":" + p[1] + " " + (h >= 12 ? "PM" : "AM");
    }
    function fmt(n) {
        return "NPR " + n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    var totalPrice = Math.round(flight.price * paying * 100) / 100;

    /* ==========================================================
       1. NAVBAR SCROLL EFFECT
       ========================================================== */
    const navbar = document.getElementById('navbar');
    window.addEventListener('scroll', () => {
        navbar.classList.toggle('scrolled', window.scrollY > 50);
    });

    /* ==========================================================
       2. MOBILE MENU
       ========================================================== */
    const mobileBtn = document.getElementById('mobileMenuBtn');
    const mobileMenu = document.getElementById('mobileMenu');
    if (mobileBtn && mobileMenu) {
        mobileBtn.addEventListener('click', () => {
            mobileMenu.classList.toggle('open');
            const icon = mobileBtn.querySelector('i');
            icon.classList.toggle('fa-bars');
            icon.classList.toggle('fa-xmark');
        });
        mobileMenu.querySelectorAll('a').forEach(link => {
            link.addEventListener('click', () => {
                mobileMenu.classList.remove('open');
                const icon = mobileBtn.querySelector('i');
                icon.classList.add('fa-bars');
                icon.classList.remove('fa-xmark');
            });
        });
    }

    /* ==========================================================
       3. SIDEBAR — Booking Details from the selected flight
       ========================================================== */
    var sbTime = document.getElementById('sbTime');
    var sbDate = document.getElementById('sbDate');
    var sbRoute = document.getElementById('sbRoute');
    var sbPaxSub = document.getElementById('sbPaxSub');
    var sbPrice = document.getElementById('sbPrice');
    var sbTag = document.getElementById('sbTag');
    var sbBreakdown = document.getElementById('sbBreakdown');

    if (sbTime) sbTime.textContent = to12(flight.depart) + " – " + to12(flight.arrive);

    if (sbDate) {
        var d = flight.date ? new Date(flight.date + "T00:00:00") : null;
        sbDate.textContent = d && !isNaN(d)
            ? d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" })
            : "—";
    }

    if (sbRoute) {
        sbRoute.textContent =
            cityName(flight.from) + " (" + flight.from + ") – " +
            cityName(flight.to) + " (" + flight.to + ")";
    }

    if (sbPaxSub) sbPaxSub.textContent = "(" + paxLabel + ")";
    if (sbPrice) sbPrice.textContent = fmt(totalPrice);
    if (sbTag) sbTag.textContent = flight.refundable ? "Refundable" : "Non Refundable";

    /* Fare breakdown — deterministic split of the total (mock data) */
    if (sbBreakdown) {
        var base = Math.round(totalPrice * 0.88 * 100) / 100;
        var tax = Math.round(totalPrice * 0.07 * 100) / 100;
        var service = Math.round((totalPrice - base - tax) * 100) / 100;
        sbBreakdown.innerHTML =
            '<div class="breakdown-row"><span>Base Fare (' + paying + ' × ' + fmt(flight.price) + ')</span><span>' + fmt(totalPrice) + '</span></div>' +
            '<div class="breakdown-row"><span>Airport Tax (est.)</span><span>' + fmt(tax) + '</span></div>' +
            '<div class="breakdown-row"><span>Service Fee</span><span>' + fmt(service) + '</span></div>' +
            '<div class="breakdown-row total"><span>Total</span><span>' + fmt(totalPrice) + '</span></div>';
    }

    /* ==========================================================
       4. N PASSENGER FORMS — rendered from the pax split
       ========================================================== */
    var formsWrap = document.getElementById('passengerForms');
    var TYPE = { adult: { code: "ADT", label: "Adult" }, child: { code: "CHD", label: "Child" } };

    function passengerCard(n, kind) {
        var t = TYPE[kind];
        var nat = MockDB.NATIONALITIES[(search && search.nationality) || "NP"] || "Nepal";
        return (
            '<div class="form-card reveal delay-1">' +
            '<h2 class="form-card-title">Passenger ' + n +
            ' <span class="passenger-type">(' + t.label + ')</span></h2>' +
            '<div class="form-row">' +
            '<div class="form-group">' +
            '<label>Courtesy Title</label>' +
            '<div class="select-wrapper">' +
            '<select id="p' + n + 'Title">' +
            '<option value="">Title</option>' +
            '<option value="Mr">Mr.</option>' +
            '<option value="Ms" selected>Ms.</option>' +
            '<option value="Mrs">Mrs.</option>' +
            '<option value="Dr">Dr.</option>' +
            '</select>' +
            '<i class="fa-solid fa-chevron-down"></i>' +
            '</div></div>' +
            '<div class="form-group">' +
            '<label>Last Name</label>' +
            '<input type="text" id="p' + n + 'LastName" placeholder="Last Name" />' +
            '</div></div>' +
            '<div class="form-row">' +
            '<div class="form-group">' +
            '<label>First Name</label>' +
            '<input type="text" id="p' + n + 'FirstName" placeholder="First Name" />' +
            '</div>' +
            '<div class="form-group">' +
            '<label>Middle Name</label>' +
            '<input type="text" id="p' + n + 'MiddleName" placeholder="Middle Name" />' +
            '</div></div>' +
            '<div class="form-row">' +
            '<div class="form-group">' +
            '<label>Nationality</label>' +
            '<input type="text" id="p' + n + 'Nationality" placeholder="Nationality" value="' + nat + '" />' +
            '</div>' +
            '<div class="form-group">' +
            '<!-- Royal Club Card Number intentionally omitted -->' +
            '</div></div>' +
            '</div>'
        );
    }

    if (formsWrap) {
        var html = "";
        var n = 1;
        for (var a = 0; a < counts.adult; a++, n++) html += passengerCard(n, "adult");
        for (var c = 0; c < counts.child; c++, n++) html += passengerCard(n, "child");
        formsWrap.innerHTML = html;
        formsWrap.querySelectorAll('.reveal').forEach(function (el) { el.classList.add('active'); });
    }

    /* ==========================================================
       5. COUNTDOWN TIMER (15 min — resumes from sessionStorage)
       ========================================================== */
    const TIMER_DURATION = 15 * 60;
    let timeLeft = parseInt(sessionStorage.getItem('bookingTimeLeft')) || TIMER_DURATION;
    const timerDisplay = document.getElementById('timerDisplay');
    const timerBar = document.querySelector('.timer-bar');

    function formatTime(seconds) {
        const m = Math.floor(seconds / 60);
        const s = seconds % 60;
        return `${m} minute${m !== 1 ? 's' : ''} ${s} second${s !== 1 ? 's' : ''}`;
    }

    function updateTimerDisplay() {
        if (!timerDisplay || !timerBar) return;
        timerDisplay.textContent = formatTime(timeLeft);
        timerBar.classList.remove('warning', 'danger');
        if (timeLeft <= 120) timerBar.classList.add('danger');
        else if (timeLeft <= 300) timerBar.classList.add('warning');
    }

    updateTimerDisplay();

    const timerInterval = setInterval(() => {
        timeLeft--;
        updateTimerDisplay();
        if (timeLeft <= 0) {
            clearInterval(timerInterval);
            if (timerDisplay) timerDisplay.textContent = '0 minutes 0 seconds';
            alert('Your booking session has expired. Please start again.');
            sessionStorage.removeItem('bookingTimeLeft');
            sessionStorage.removeItem('bookingData');
            window.location.href = './homeLogged.html';
        }
    }, 1000);

    /* ==========================================================
       6. PHONE INPUT — clear button
       ========================================================== */
    document.querySelectorAll('.phone-clear').forEach(btn => {
        btn.addEventListener('click', () => {
            const input = btn.closest('.phone-input').querySelector('input');
            input.value = '';
            input.focus();
        });
    });

    /* ==========================================================
       7. SSR TOGGLE
       ========================================================== */
    const ssrCheck = document.getElementById('ssrCheck');
    const ssrOptions = document.getElementById('ssrOptions');
    if (ssrCheck && ssrOptions) {
        ssrCheck.addEventListener('change', () => {
            ssrOptions.classList.toggle('show', ssrCheck.checked);
        });
    }

    /* ==========================================================
       8. PRICE BREAKDOWN TOGGLE
       ========================================================== */
    const breakdownBtn = document.getElementById('breakdownBtn');
    const priceBreakdown = document.getElementById('priceBreakdown');
    if (breakdownBtn && priceBreakdown) {
        breakdownBtn.addEventListener('click', () => {
            breakdownBtn.classList.toggle('open');
            priceBreakdown.classList.toggle('open');
        });
    }

    /* ==========================================================
       9. CONTINUE — validate contact + EVERY passenger, then save
       ========================================================== */
    const continueBtn = document.getElementById('continueBtn');

    if (continueBtn) {
        continueBtn.addEventListener('click', (e) => {
            e.preventDefault();

            /* Shared client-side rules (spec §35 validation.js): the contact
               block, every rendered passenger block and the terms checkbox.
               The messages, patterns and date rules live in one place now —
               this page only decides what to do when something is wrong.
               `paying` is the count rendered above, so nothing is probed. */
            const check = validatePassengerForm({ paying: paying });
            if (!check.valid) {
                continueBtn.style.animation = 'shake 0.4s ease';
                setTimeout(() => { continueBtn.style.animation = ''; }, 400);
                if (check.firstInvalid) check.firstInvalid.scrollIntoView({ behavior: 'smooth', block: 'center' });
                return;
            }

            /* Collect passengers */
            var passengers = [];
            for (var j = 1; j <= paying; j++) {
                passengers.push({
                    title: document.getElementById('p' + j + 'Title').value,
                    lastName: document.getElementById('p' + j + 'LastName').value.trim(),
                    firstName: document.getElementById('p' + j + 'FirstName').value.trim(),
                    middleName: document.getElementById('p' + j + 'MiddleName').value.trim(),
                    nationality: document.getElementById('p' + j + 'Nationality').value.trim(),
                    type: j <= counts.adult ? 'ADT' : 'CHD'
                });
            }

            const bookingData = {
                contact: {
                    title: document.getElementById('contactTitle').value,
                    lastName: document.getElementById('contactLastName').value,
                    firstName: document.getElementById('contactFirstName').value,
                    middleName: document.getElementById('contactMiddleName').value,
                    phone: document.getElementById('contactPhone').value,
                    email: document.getElementById('contactEmail').value,
                    invoiceParty: document.getElementById('invoiceParty').value,
                    panNo: document.getElementById('panNo').value,
                    isPassenger: document.getElementById('imPassenger').checked,
                },
                passengers: passengers,
                ssr: Array.from(document.querySelectorAll('input[name="ssr"]:checked')).map(c => c.value),
                flight: {
                    from: flight.from, to: flight.to,
                    date: flight.date,
                    depart: flight.depart, arrive: flight.arrive,
                    flightNo: flight.flightNo,
                    airline: flight.airline,
                    flightClass: flight.flightClass,
                    refundable: flight.refundable,
                    pricePerPassenger: flight.price,
                    passengerCount: paying,
                    totalPrice: totalPrice,
                },
            };

            console.log('Booking Data:', bookingData);
            sessionStorage.setItem('bookingData', JSON.stringify(bookingData));

            /* Passenger record for downstream pages (eticket.js / esewaOtp.js
               look for `yatra_passenger`) — first paying passenger. */
            const p1 = passengers[0];
            sessionStorage.setItem('yatra_passenger', JSON.stringify({
                name: `${p1.firstName} ${p1.lastName}`.trim(),
                firstName: p1.firstName,
                lastName: p1.lastName,
                title: p1.title,
                nationality: p1.nationality,
                phone: bookingData.contact.phone,
                email: bookingData.contact.email,
            }));

            /* Persist the remaining countdown so payment.html resumes it */
            sessionStorage.setItem('bookingTimeLeft', String(timeLeft));

            /* Loading state */
            continueBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Processing...';
            continueBtn.disabled = true;

            /* Item 16 — create the pending booking through the API layer
               (POST /api/bookings, §36). Mock: returns the bookingId only
               (the confirmed row is written at POST /api/payments/verify).
               Real backend: persists the PENDING row here. Either way the
               id rides inside the existing bookingData key (§31.2) — no
               new one-off sessionStorage keys. */
            apiPost('/api/bookings', {
                contact: bookingData.contact,
                passengers: bookingData.passengers,
                flight: bookingData.flight,
                amount: bookingData.flight.totalPrice,
            }).then((pending) => {
                bookingData.bookingId = pending.bookingId;
                bookingData.bookingStatus = pending.status;
                sessionStorage.setItem('bookingData', JSON.stringify(bookingData));
            }).catch(() => {
                /* demo must never dead-end: payment/verify tolerate a
                   missing bookingId (the verify mock mints one if absent) */
            }).finally(() => {
                setTimeout(() => {
                    window.location.href = './payment.html';
                }, 1200);
            });
        });
    }

    /* ==========================================================
       10. SCROLL REVEAL
       ========================================================== */
    const revealElements = document.querySelectorAll('.reveal');
    const revealObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('active');
                revealObserver.unobserve(entry.target);
            }
        });
    }, { threshold: 0.15, rootMargin: '0px 0px -50px 0px' });
    revealElements.forEach(el => revealObserver.observe(el));

    /* ==========================================================
       11. BACK TO TOP
       ========================================================== */
    const backToTop = document.getElementById('backToTop');
    if (backToTop) {
        const toggleBackToTop = () => backToTop.classList.toggle('show', window.scrollY > 500);
        window.addEventListener('scroll', toggleBackToTop, { passive: true });
        toggleBackToTop();
        backToTop.addEventListener('click', () => {
            const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
        });
    }

    /* ==========================================================
       12. AUTO-FILL passenger 1 from contact ("I'm a passenger")
       ========================================================== */
    const imPassenger = document.getElementById('imPassenger');
    if (imPassenger) {
        imPassenger.addEventListener('change', () => {
            if (imPassenger.checked) {
                document.getElementById('p1Title').value = document.getElementById('contactTitle').value;
                document.getElementById('p1LastName').value = document.getElementById('contactLastName').value;
                document.getElementById('p1FirstName').value = document.getElementById('contactFirstName').value;
                document.getElementById('p1MiddleName').value = document.getElementById('contactMiddleName').value;
            }
        });
    }
});

/* Shake keyframe (injected dynamically) */
const shakeStyle = document.createElement('style');
shakeStyle.textContent = `
@keyframes shake {
    0%, 100% { transform: translateX(0); }
    20% { transform: translateX(-6px); }
    40% { transform: translateX(6px); }
    60% { transform: translateX(-4px); }
    80% { transform: translateX(4px); }
}`;
document.head.appendChild(shakeStyle);
