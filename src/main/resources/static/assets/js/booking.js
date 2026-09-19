document.addEventListener('DOMContentLoaded', () => {

    /* ==========================================================
       0. DATA — selected flight + search split (with demo fallback)
       ========================================================== */
    function load(key) {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch (e) { return null; }
    }

    var flight = load("yatra_selected_flight");
    var search = load("flightSearchData");

    /* Fix-plan §10 — no fabricated selection.
       This page used to fall back to MockDB.DEMO_FLIGHT so that opening booking.html
       directly still rendered something. On the real API that fallback is worse than
       useless: POST /api/bookings resolves the flight by number, so the fabricated
       flight either 404s or — if its number is one the database happens to hold — puts
       a real seat hold behind a flight the customer never chose. A page whose entire
       input is "the flight you selected" is a page that cannot invent one, so with
       nothing selected it says so and hands the visitor back to the search, which is
       where a selection comes from. */
    if (!flight) {
        if (typeof showToast === "function") {
            showToast("Choose a flight to continue.", "error");
        }
        setTimeout(function () { location.replace("./searchFlight.html"); }, 1200);
        return;
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

    /* City names travel with the selection now (§10): searchFlight.js writes the
       names the search response named (`fromCity`/`toCity`, from the destination
       rows) into yatra_selected_flight, so this page prints the real city rather
       than looking the code up itself.

       MockDB.AIRPORTS stays as the fallback for a selection stored before §10 — a
       back-button visit can still hold one — and goes when mock-data.js does (the
       last page off the allow-list). A code that resolves nowhere is printed as the
       code, which is what this function did before the map existed at all. */
    function cityName(code, known) {
        if (known) return known;
        if (typeof MockDB !== "undefined" && MockDB.AIRPORTS && MockDB.AIRPORTS[code]) {
            return MockDB.AIRPORTS[code].city;
        }
        return code;
    }

    function to12(hhmm) {
        if (!hhmm) return "—";
        var p = hhmm.split(":"), h = +p[0];
        return ((h + 11) % 12 + 1) + ":" + p[1] + " " + (h >= 12 ? "PM" : "AM");
    }
    /* One money format for the whole flow, now that fare.js owns it. Kept as a local
       alias so the call sites below read as they always did. */
    function fmt(n) {
        return YatraFare.format(n);
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
            cityName(flight.from, flight.fromCity) + " (" + flight.from + ") – " +
            cityName(flight.to, flight.toCity) + " (" + flight.to + ")";
    }

    if (sbPaxSub) sbPaxSub.textContent = "(" + paxLabel + ")";
    if (sbPrice) sbPrice.textContent = fmt(totalPrice);
    if (sbTag) sbTag.textContent = flight.refundable ? "Refundable" : "Non Refundable";

    /* Fare breakdown — from fare.js, the one place that splits a total. §5 asks the
       "Confirm Flight" modal to reuse this same logic, which it could not while it
       lived here; payment.js already carried the second copy.

       The Base Fare row now reports `base`. It reported the TOTAL here, so on this
       page alone the three rows summed to 8,299.99 + 581.00 + 415.00 against a stated
       Total of 8,299.99 — the breakdown did not add up. payment.js printed `base`, so
       the same booking showed two different breakdowns on its two steps. */
    YatraFare.render(sbBreakdown, totalPrice, { passengers: paying, unitPrice: flight.price });

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
       5. COUNTDOWN TIMER — the 15-minute hold, from an absolute
          expiry (fix-plan §8a)

          This was a decrementing counter (`timeLeft--` every second,
          seeded from `bookingTimeLeft`). A background tab throttles
          `setInterval`, so the countdown fell behind real time on
          every tab switch and a back/forward navigation re-ran the
          page against a stored value that meant something else — the
          bug §8a reports. Remaining time is now always
          `expiry - Date.now()`, which nothing can accumulate away.

          The clock itself lives in hold.js: payment.html needs the
          same one, and two copies of a countdown is how two pages
          come to disagree about one hold.
       ========================================================== */
    const timerDisplay = document.getElementById('timerDisplay');
    const timerBar = document.querySelector('.timer-bar');

    // Idempotent: starts a hold only if this session has none, so arriving here
    // from a fresh search begins one and re-rendering does not restart it.
    YatraHold.start();

    YatraHold.watch({
        display: timerDisplay,
        bar: timerBar,
        /* §8b — the expiry screen and the key-clearing live in hold.js so that both
           wizard pages show one screen with one copy and one key set. This used to be
           an `alert()` written out in this file and again in payment.js, each clearing
           a different subset of the abandoned attempt's keys. */
        onExpire: YatraHold.expireScreen
    });

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
                    /* Carried through to payment.html, which prints the same route on
                       the page where money changes hands (§10). The API's booking DTO
                       ignores unknown keys, so this is free. */
                    fromCity: flight.fromCity || null,
                    toCity: flight.toCity || null,
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

            /* Nothing to persist for the countdown any more: the absolute
               expiry is already in sessionStorage (§8a), so payment.html reads
               the same instant instead of a duration this page had to hand on. */

            /* Loading state */
            continueBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Processing...';
            continueBtn.disabled = true;

            /* Item 16 — create the pending booking through the API layer
               (POST /api/bookings). Real backend (§10): this persists the PENDING row
               and holds the seats, and `pending.bookingId` is the id every later step
               needs — payment.html posts it to /api/payments/initiate, which 400s
               without it.

               The old catch was silent and the navigation sat in `finally`, so a
               failed POST walked the customer to the payment page anyway with no
               bookingId: on the mock that was survivable (the mock mints an id), on
               the real API it is a dead end at the gateway. The page now stays put,
               says what happened and lets them retry. */
            apiPost('/api/bookings', {
                contact: bookingData.contact,
                passengers: bookingData.passengers,
                /* The carrier goes over the wire as its NAME.
                   `bookingData.flight.airline` is the object the search response gave
                   the cards ({name, code, logo}) and eticket.js renders it as one, so
                   the session keeps the object — but BookingRequest.SelectedFlight.airline
                   is a String, and Jackson refuses the whole body when an object meets a
                   String field (400 "Request body is missing or is not valid JSON").
                   That went unnoticed for as long as this page was on the mock, because
                   the mock never bound the payload to a DTO — it is exactly the kind of
                   mismatch the first real POST to this API surfaces. The value is
                   informational there (the booking's carrier is the flight row's). */
                flight: Object.assign({}, bookingData.flight, {
                    airline: (bookingData.flight.airline && bookingData.flight.airline.name)
                        || bookingData.flight.airline || null,
                }),
                amount: bookingData.flight.totalPrice,
            }).then((pending) => {
                bookingData.bookingId = pending.bookingId;
                bookingData.bookingStatus = pending.status;
                sessionStorage.setItem('bookingData', JSON.stringify(bookingData));
                setTimeout(() => {
                    window.location.href = './payment.html';
                }, 1200);
            }).catch((err) => {
                /* Restored to the button's own markup (booking.html), not a new label. */
                continueBtn.innerHTML = 'Continue <i class="fa-solid fa-arrow-right"></i>';
                continueBtn.disabled = false;
                if (typeof showToast === "function") {
                    showToast((err && err.message) || 'The booking could not be created.', 'error');
                }
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
       12. PRE-FILL the contact block from the signed-in account
           (fix-plan §7). GET /api/users/me is the same call
           profile.html makes, and the answer is the real account in
           BOTH modes: in mock mode api.js's mockSessionUser() reads
           YATRA_CONFIG.AUTH_USER_KEY — the session the REAL login
           wrote — so this is the signed-up name, email and mobile
           rather than a placeholder.

           Two rules, both deliberate:
           - Anonymous visitors are left alone. With no session the
             mock route synthesises a "Demo Traveller" record (its
             never-dead-end rule), and writing that into a guest's
             form is worse than leaving it blank.
           - Only EMPTY fields are filled. A visitor who has already
             typed must not have their typing replaced by a slower
             response, and a returning back-button visit keeps what
             they entered.
       ========================================================== */
    function setIfEmpty(field, value) {
        if (field && !field.value.trim() && value) field.value = String(value).trim();
    }

    /* The account carries one `name`; the form has three boxes. First token,
       last token, and whatever sits between them — the convention this
       roster's own names use ("Sanjay Thapa Magar"). */
    function splitName(name) {
        var parts = String(name || '').trim().split(/\s+/).filter(Boolean);
        if (!parts.length) return { first: '', middle: '', last: '' };
        return {
            first: parts[0],
            middle: parts.slice(1, -1).join(' '),
            last: parts.length > 1 ? parts[parts.length - 1] : ''
        };
    }

    if (typeof YatraAuth !== 'undefined' && YatraAuth.isLoggedIn() && typeof apiGet === 'function') {
        apiGet('/api/users/me').then(function (res) {
            var user = (res && res.user) || null;
            if (!user) return;

            var name = splitName(user.name);
            setIfEmpty(document.getElementById('contactFirstName'), name.first);
            setIfEmpty(document.getElementById('contactMiddleName'), name.middle);
            setIfEmpty(document.getElementById('contactLastName'), name.last);
            setIfEmpty(document.getElementById('contactEmail'), user.email);
            setIfEmpty(document.getElementById('contactPhone'), user.phone);

            /* "I am a passenger" may already be ticked. Its own handler only
               fires on `change`, and nothing changed here — so without this
               the pre-filled contact would not reach the passenger block. */
            mirrorContactToPassenger1();
        }).catch(function () {
            /* A stale token on a page guests may reach: leave the form as it is. */
        });
    }

    /* ==========================================================
       13. AUTO-FILL passenger 1 from contact ("I'm a passenger")
       ========================================================== */
    const imPassenger = document.getElementById('imPassenger');

    function mirrorContactToPassenger1() {
        if (!imPassenger || !imPassenger.checked) return;
        document.getElementById('p1Title').value = document.getElementById('contactTitle').value;
        document.getElementById('p1LastName').value = document.getElementById('contactLastName').value;
        document.getElementById('p1FirstName').value = document.getElementById('contactFirstName').value;
        document.getElementById('p1MiddleName').value = document.getElementById('contactMiddleName').value;
    }

    if (imPassenger) {
        imPassenger.addEventListener('change', mirrorContactToPassenger1);
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
