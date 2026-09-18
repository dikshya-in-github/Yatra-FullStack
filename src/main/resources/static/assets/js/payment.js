document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. NAVBAR SCROLL EFFECT
    // ==========================================
    const navbar = document.getElementById('navbar');
    window.addEventListener('scroll', () => {
        navbar.classList.toggle('scrolled', window.scrollY > 50);
    });

    // ==========================================
    // 2. MOBILE MENU
    // ==========================================
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

    // ==========================================
    // 3. COUNTDOWN TIMER — the same hold booking.html started
    //    (fix-plan §8a)
    //
    //    This page used to persist its own decremented count back into
    //    `bookingTimeLeft`, which is how the two pages drifted apart: each
    //    wrote its own idea of what was left. There is now one instant, set
    //    on booking.html, and this page only reads it.
    //
    //    `start()` is safe here: it resumes the existing hold and only begins
    //    one if this session has none (payment.html opened directly), which is
    //    what the old `|| TIMER_DURATION` fallback did.
    // ==========================================
    const timerDisplay = document.getElementById('timerDisplay');
    const timerBar = document.querySelector('.timer-bar');

    YatraHold.start();

    YatraHold.watch({
        display: timerDisplay,
        bar: timerBar,
        /* §8b — same screen as booking.html, from the same place: see the note there. */
        onExpire: YatraHold.expireScreen
    });

    // ==========================================
    // 3b. BOOKING DETAILS — rendered from bookingData (item 17)
    //     The sidebar, the booking card and the passenger table used to be
    //     static demo markup, so any real booking showed the wrong route,
    //     price and passengers on the page where money changes hands. They
    //     now render from the wizard's own bookingData (§34) — the same shape
    //     the backend returns later. With no bookingData (page opened
    //     directly) the markup is left untouched so the demo still renders.
    // ==========================================
    let booking = null;
    try { booking = JSON.parse(sessionStorage.getItem('bookingData')); } catch (err) { /* ignore */ }

    if (booking && booking.flight) {
        const bf = booking.flight;
        const contact = booking.contact || {};
        const passengers = Array.isArray(booking.passengers) ? booking.passengers : [];
        const paying = (typeof bf.passengerCount === 'number' && bf.passengerCount > 0)
            ? bf.passengerCount : (passengers.length || 1);

        /* fare.js owns the money format now; the alias is kept for this page's call
           sites. It used to be a third implementation of the same format. */
        const money = (n) => YatraFare.format(n);
        const to12 = (hhmm) => {
            if (!hhmm) return '—';
            const parts = String(hhmm).split(':');
            const h = +parts[0];
            return ((h + 11) % 12 + 1) + ':' + parts[1] + ' ' + (h >= 12 ? 'PM' : 'AM');
        };
        const cityOf = (code) => (typeof MockDB !== 'undefined' && MockDB.AIRPORTS[code])
            ? MockDB.AIRPORTS[code].city : code;
        const titled = (t) => (!t ? '' : (/\.$/.test(t) ? t : t + '.'));
        const fullName = (p) => [titled(p.title), p.firstName, p.middleName, p.lastName]
            .filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
        const setText = (id, text) => {
            const el = document.getElementById(id);
            if (el) el.textContent = text;
        };

        // Flight summary
        setText('sbTime', to12(bf.depart) + ' – ' + to12(bf.arrive));
        const dep = bf.date ? new Date(bf.date + 'T00:00:00') : null;
        setText('sbDate', dep && !isNaN(dep)
            ? dep.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
            : '—');
        setText('sbRoute', cityOf(bf.from) + ' (' + bf.from + ') – ' + cityOf(bf.to) + ' (' + bf.to + ')');

        // Paying passengers (adults + children; infants ride on a lap)
        const children = passengers.filter((p) => p.type === 'CHD').length;
        const adults = paying - children;
        let paxLabel = adults + (adults === 1 ? ' Adult' : ' Adults');
        if (children) paxLabel += ', ' + children + (children === 1 ? ' Child' : ' Children');
        setText('sbPaxSub', '(' + paxLabel + ')');

        // Total — the same number the gateway charges (bookingData.flight.totalPrice)
        const total = (typeof bf.totalPrice === 'number' && bf.totalPrice > 0)
            ? bf.totalPrice
            : (bf.pricePerPassenger || 0) * paying;
        setText('sbPrice', money(total));
        setText('sbTag', bf.refundable ? 'Refundable' : 'Non Refundable');

        /* Fare breakdown — fare.js's, so this page and booking.html cannot disagree
           about the same booking again. This copy happened to print `base` correctly
           and booking.html's did not; one implementation means the question stops
           being asked per page. */
        YatraFare.render(document.getElementById('priceBreakdown'), total, {
            passengers: paying,
            unitPrice: bf.pricePerPassenger
        });

        // Booking card — the pending id POST /api/bookings minted (not a fake code)
        setText('bookingCode', booking.bookingId || 'Pending');

        const lead = passengers[0] || contact;
        const personal = document.getElementById('bookingPersonal');
        if (personal) {
            personal.innerHTML = (fullName(lead) || '—') +
                '<br />' + (lead.nationality || 'Nepal');
        }

        const contactEl = document.getElementById('bookingContact');
        if (contactEl) {
            // Same normalization the user store applies (MockDB.normalizePhone),
            // so "+977 9801-119-999" and "9801119999" render one way.
            const national = (typeof MockDB !== 'undefined' && MockDB.normalizePhone)
                ? MockDB.normalizePhone(contact.phone)
                : String(contact.phone || '').replace(/\D/g, '');
            const phone = national ? '977 ' + national : '';
            contactEl.innerHTML = [phone, contact.email].filter(Boolean).join('<br />') || '—';
        }

        // Passenger table — one row per paying passenger
        const rows = document.getElementById('passengerRows');
        if (rows) {
            rows.innerHTML = passengers.map((p, i) =>
                '<tr>' +
                '<td>' + (i + 1) + '</td>' +
                '<td>' + (p.type === 'CHD' ? 'Child' : 'Adult') + '</td>' +
                '<td>' + (fullName(p) || '—') + '</td>' +
                '<td>' + (p.nationality || 'Nepal') + '</td>' +
                '</tr>').join('');
        }
    }

    // ==========================================
    // 4. PAYMENT OPTION SELECTION
    // ==========================================
    document.querySelectorAll('.payment-option input').forEach(radio => {
        radio.addEventListener('change', () => {
            // Visual feedback handled by CSS :checked
            console.log('Selected payment:', radio.value);
        });
    });

    // ==========================================
    // 5. PROMO CODE
    // ==========================================
    const promoInput = document.getElementById('promoInput');
    const promoApplyBtn = document.getElementById('promoApplyBtn');
    if (promoApplyBtn && promoInput) {
        promoApplyBtn.addEventListener('click', () => {
            const code = promoInput.value.trim().toUpperCase();
            if (!code) {
                promoInput.style.borderColor = 'var(--accent)';
                setTimeout(() => { promoInput.style.borderColor = ''; }, 1500);
                return;
            }
            promoApplyBtn.textContent = 'Applied ✓';
            promoApplyBtn.classList.add('applied');
            promoInput.disabled = true;
            setTimeout(() => {
                promoApplyBtn.textContent = 'Apply';
                promoApplyBtn.classList.remove('applied');
                promoInput.disabled = false;
                promoInput.value = '';
            }, 2500);
        });
    }

    // ==========================================
    // 6. PRICE BREAKDOWN TOGGLE
    // ==========================================
    const breakdownBtn = document.getElementById('breakdownBtn');
    const priceBreakdown = document.getElementById('priceBreakdown');
    if (breakdownBtn && priceBreakdown) {
        breakdownBtn.addEventListener('click', () => {
            breakdownBtn.classList.toggle('open');
            priceBreakdown.classList.toggle('open');
        });
    }

    // ==========================================
    // 7. CONTINUE BUTTON — eSewa only for now (author decision)
    // ==========================================
    const continueBtn = document.getElementById('continueBtn');
    if (continueBtn) {
        continueBtn.addEventListener('click', (e) => {
            e.preventDefault();

            const selected = document.querySelector('input[name="payment"]:checked');
            if (!selected) {
                alert('Please select a payment method.');
                return;
            }

            // eSewa is the only integrated gateway right now. Other methods
            // (cards/banks, Khalti, IME Pay, ConnectIPS) come later.
            if (selected.value !== 'esewa') {
                alert('Only eSewa is available right now. Please select eSewa to continue.');
                return;
            }

            // Loading state
            continueBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Processing...';
            continueBtn.disabled = true;

            const method = selected.value;
            const promoCode = promoInput?.value.trim() || null;

            // Step 3a — initiate the payment through the API layer
            // (POST /api/payments/initiate, §36) and go where the response
            // sends us. Mock: gatewayRedirect = esewaLogin.html (the same
            // handoff as before). Real backend: PaymentService returns the
            // eSewa redirect URL. Fail-safe: if initiate fails, the demo
            // still walks the static gateway path — never dead-ends (same
            // rule as booking.js's pending-booking call).
            let booking = null;
            try { booking = JSON.parse(sessionStorage.getItem('bookingData')); } catch (err) { /* ignore */ }

            let gatewayUrl = './esewaLogin.html';
            apiPost('/api/payments/initiate', {
                bookingId: (booking && booking.bookingId) || null,
                method: method,
                amount: (booking && booking.flight && booking.flight.totalPrice != null)
                    ? booking.flight.totalPrice : null,
                promoCode: promoCode,
            }).then((init) => {
                if (init.gatewayRedirect) gatewayUrl = init.gatewayRedirect;
            }).catch(() => {
                /* initiate failed — keep the static gateway handoff */
            }).finally(() => {
                setTimeout(() => {
                    window.location.href = gatewayUrl;
                }, 1200);
            });
        });
    }

    // ==========================================
    // 8. SCROLL REVEAL
    // ==========================================
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

    // ==========================================
    // 9. BACK TO TOP
    // ==========================================
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
});